package com.fellowabhi.chargebrake

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** One 1 Hz sample of what the bubble shows. */
data class BubbleData(
    val net: Float, val brake: Float, val st: St, val pct: Int, val hold: Int, val holdMah: Float, val mah: Float,
    val temp: Float, val minPerPct: Float, val paused: Boolean,
) {
    val holding get() = st == St.HOLDING || st == St.BRAKING || st == St.IDLE || st == St.IN_USE
    val healthy get() = holding && !paused && abs(net) <= BAND
    companion object {
        const val BAND = 100f
        fun from(m: Monitor) = BubbleData(m.avgMw, m.ctl.burn, m.state, m.pct, m.ctl.hold(), Look.goalMah(m), m.mah, m.temp, m.minPerPct, m.ctl.paused)
    }
}

/** Option-6 scale: green inside ±BAND, then towards amber (gaining) / blue (draining) by ±2 W. */
fun netScale(v: Float): Int {
    val a = abs(v); if (a <= BubbleData.BAND) return C_GREEN
    val k = ((a - BubbleData.BAND) / (2000f - BubbleData.BAND)).coerceIn(0f, 1f).pow(0.7f)
    return lerpColor(C_GREEN, if (v > 0) C_AMBER else C_BLUE, k)
}
fun netFrac(v: Float) = (abs(v) / 2500f).pow(0.6f).coerceAtMost(1f)
fun fmtNetShort(v: Float): String { val a = abs(v); val s = if (v >= 0) "+" else "−"; return if (a >= 1000) s + String.format(Locale.US, "%.1fk", a / 1000) else s + a.toInt() }

/**
 * Floating bubble: outer ring = net power (option-6 colours, ticks at the ±100 mW holding band),
 * inner ring = brake. Expands to a pill with live text. Values arrive at ~1 Hz and ease for ~400 ms,
 * then the view is idle (no redraw until the next value). Drawn in dp units; glows are cheap radial gradients.
 */
class BubbleView(ctx: Context) : View(ctx) {
    private val dn = ctx.resources.displayMetrics.density
    var expanded = false; private set
    var mirror = false                         // docked on the right: bubble at the right end of the pill
        set(v) { if (field != v) { field = v; invalidate() } }
    private var morph = 0f; private var mFrom = 0f; private var mTo = 0f; private var mT0 = 0L
    // eased values
    private var net = 0f; private var brake = 0f; private var hk = 0f; private var ik = 0f; private var netCol = C_GREEN; private var stCol = C_GREY
    private var f = FloatArray(4); private var t = FloatArray(4); private var cF = IntArray(2); private var cT = IntArray(2)
    private var aT0 = 0L; private var moveN = 0f; private var moveB = 0f
    private var d: BubbleData? = null
    private val spark = FloatArray(30); private var sparkN = 0

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val pTxtN = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF(); private val mtx = Matrix()
    private val path = android.graphics.Path()

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun setExpanded(e: Boolean, animate: Boolean = true) {
        if (e == expanded && (animate || morph == (if (e) 1f else 0f))) return
        expanded = e; mFrom = morph; mTo = if (e) 1f else 0f; mT0 = SystemClock.uptimeMillis()
        if (!animate) { morph = mTo; mT0 = 0 }
        requestLayout(); invalidate()
    }
    /** Width in px of the window: the full pill while expanded (or collapsing). */
    fun wantW() = ((if (expanded || morph > 0.001f) PW else D) + 2 * M) * dn
    fun wantH() = (D + 2 * M) * dn

    override fun onMeasure(ws: Int, hs: Int) = setMeasuredDimension(wantW().toInt(), wantH().toInt())

    /** New value (≈1 Hz): ease from what is on screen now to it over ANIM ms. */
    fun push(v: BubbleData, animate: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        current(now)
        f[0] = net; f[1] = brake; f[2] = hk; f[3] = ik; cF[0] = netCol; cF[1] = stCol
        t[0] = v.net; t[1] = v.brake; t[2] = if (v.healthy) 1f else 0f
        t[3] = maxOf(((abs(v.net) - 300f) / 2700f).coerceIn(0f, 1f), ((v.brake - 0.75f) / 0.25f).coerceIn(0f, 1f)).pow(1.3f)
        cT[0] = netScale(v.net); cT[1] = Look.color(v.st)
        moveN = abs(netFrac(t[0]) - netFrac(f[0])); moveB = abs(t[1] - f[1])
        d = v
        if (sparkN < spark.size) spark[sparkN++] = v.net else { System.arraycopy(spark, 1, spark, 0, spark.size - 1); spark[spark.size - 1] = v.net }
        if (!animate) { aT0 = 0; current(now) } else aT0 = now
        invalidate()
    }

    private fun ease(k: Float) = 1f - (1f - k).pow(3)
    /** Sets the eased values for time `now`; returns true while still animating. */
    private fun current(now: Long): Boolean {
        val k = if (aT0 == 0L) 1f else ((now - aT0) / ANIM).coerceIn(0f, 1f)
        val e = ease(k)
        if (d != null) {
            net = f[0] + (t[0] - f[0]) * e; brake = f[1] + (t[1] - f[1]) * e; hk = f[2] + (t[2] - f[2]) * e; ik = f[3] + (t[3] - f[3]) * e
            netCol = lerpColor(cF[0], cT[0], e); stCol = lerpColor(cF[1], cT[1], e)
        }
        val mk = if (mT0 == 0L) 1f else ((now - mT0) / MORPH).coerceIn(0f, 1f)
        morph = mFrom + (mTo - mFrom) * ease(mk)
        if (mk >= 1f && mT0 != 0L) { mT0 = 0; if (!expanded) { morph = 0f; post { requestLayout(); onCollapsed?.invoke() } } }
        return k < 1f || mT0 != 0L
    }
    var onCollapsed: (() -> Unit)? = null
    private var animK = 0f   // 1 at the start of a move, 0 when settled (head glow)

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val anim = current(now)
        animK = if (aT0 == 0L) 0f else 1f - ((now - aT0) / ANIM).coerceIn(0f, 1f)
        c.save(); c.scale(dn, dn)
        val R = D / 2f; val cy = M + R
        val pw = D + (PW - D) * morph
        val wTot = width / dn
        val cx = if (mirror) wTot - M - R else M + R
        // pill background
        if (morph > 0.01f) {
            val left = if (mirror) wTot - M - pw else M
            rect.set(left, M, left + pw, M + D)
            pFill.shader = null; pFill.color = 0xF215181D.toInt(); c.drawRoundRect(rect, R, R, pFill)
            pRing.shader = null; pRing.strokeWidth = 1f
            pRing.color = if (hk > 0.05f) withAlpha(C_GREEN, 0.22f + 0.28f * hk) else withAlpha(stCol, 0.30f)
            c.drawRoundRect(rect, R, R, pRing)
            if (morph > 0.4f) drawPillText(c, left, pw, cy, R, ((morph - 0.4f) / 0.6f).coerceIn(0f, 1f))
        }
        drawBubble(c, cx, cy, R)
        c.restore()
        if (anim) postInvalidateOnAnimation()
    }

    private fun halo(c: Canvas, cx: Float, cy: Float, r0: Float, r1: Float, col: Int, a: Float) {
        if (a < 0.02f) return
        pFill.color = Color.WHITE
        pFill.shader = RadialGradient(cx, cy, r1, intArrayOf(withAlpha(col, a), withAlpha(col, a), withAlpha(col, 0f)), floatArrayOf(0f, r0 / r1, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r1, pFill); pFill.shader = null
    }

    private fun drawBubble(c: Canvas, cx: Float, cy: Float, R: Float) {
        val w1 = D * 0.075f; val r1 = R - w1 / 2 - 2.5f; val w2 = D * 0.06f; val r2 = r1 - w1 / 2 - w2 / 2 - 2f
        // halos (subtler than the mockup): state colour, stronger at extremes; soft green when the hold is healthy
        if (morph < 0.5f) {
            halo(c, cx, cy, R * 0.9f, R + M * 0.95f, stCol, (0.08f + 0.30f * ik) * (1f - morph * 2))
            halo(c, cx, cy, R * 0.9f, R + M * 0.95f, C_GREEN, 0.28f * hk * (1f - morph * 2))
        }
        // glass
        pFill.color = 0xFF15181D.toInt(); c.drawCircle(cx, cy, R, pFill)
        if (hk > 0.01f) {
            pFill.color = Color.WHITE
            pFill.shader = RadialGradient(cx, cy, R, withAlpha(C_GREEN, 0.02f * hk), withAlpha(C_GREEN, 0.14f * hk), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, R, pFill); pFill.shader = null
        }
        pRing.shader = null; pRing.strokeWidth = 1f
        pRing.color = if (hk > 0.01f) withAlpha(lerpColor(Color.WHITE, C_GREEN, hk), 0.10f + 0.25f * hk) else 0x1AFFFFFF
        c.drawCircle(cx, cy, R - 0.5f, pRing)
        // tracks
        pRing.color = 0x14FFFFFF; pRing.strokeWidth = w1; c.drawCircle(cx, cy, r1, pRing)
        pRing.strokeWidth = w2; c.drawCircle(cx, cy, r2, pRing)
        // band ticks: ±100 mW on the net ring
        val bandA = netFrac(BubbleData.BAND) * 360f
        pLine.strokeWidth = 1.2f; pLine.color = 0x8CFFFFFF.toInt()
        for (s in intArrayOf(-1, 1)) {
            val a = Math.toRadians((-90f + s * bandA).toDouble())
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            c.drawLine(cx + ca * (r1 - w1 * 0.75f), cy + sa * (r1 - w1 * 0.75f), cx + ca * (r1 + w1 * 0.75f), cy + sa * (r1 + w1 * 0.75f), pLine)
        }
        // net arc: gradient along the scale, from 12 o'clock clockwise (gaining) or anticlockwise (draining)
        val fN = netFrac(net); val sw = fN * 360f
        rect.set(cx - r1, cy - r1, cx + r1, cy + r1)
        if (sw > 0.6f) {
            val n = 6; val cols = IntArray(n + 2); val pos = FloatArray(n + 2)
            val dir = if (net >= 0) 1 else -1
            val off = Math.toDegrees((w1 / 2f / r1).toDouble()).toFloat()          // room for the round cap before the start
            for (i in 0..n) { val s = i / n.toFloat(); val v = (if (dir > 0) s else 1 - s) * abs(net) * dir; cols[i] = netScale(v); pos[i] = ((off + s * sw) / 360f).coerceAtMost(0.998f) }
            cols[n + 1] = cols[n]; pos[n + 1] = 1f
            val sg = SweepGradient(cx, cy, cols, pos)
            mtx.setRotate((if (dir > 0) -90f else -90f - sw) - off, cx, cy); sg.setLocalMatrix(mtx)
            pRing.color = Color.WHITE; pRing.shader = sg; pRing.strokeWidth = w1; pRing.strokeCap = Paint.Cap.ROUND
            c.drawArc(rect, if (dir > 0) -90f else -90f - sw, sw, false, pRing)
            pRing.shader = null
            headGlow(c, cx, cy, r1, if (dir > 0) -90f + sw else -90f - sw, w1, netCol, (moveN * 3f).coerceAtMost(1f) * animK * 0.5f + 0.22f * ik)
        }
        // brake arc
        val swB = brake.coerceIn(0f, 1f) * 360f
        if (swB > 0.6f) {
            rect.set(cx - r2, cy - r2, cx + r2, cy + r2)
            val off = Math.toDegrees((w2 / 2f / r2).toDouble()).toFloat()
            val c0 = lerpColor(C_ORANGE, Color.BLACK, 0.4f); val c1 = lerpColor(C_ORANGE, Color.WHITE, 0.2f)
            val sg = SweepGradient(cx, cy, intArrayOf(c0, c0, c1, c1), floatArrayOf(0f, off / 360f, ((off + swB) / 360f).coerceAtMost(0.999f), 1f))
            mtx.setRotate(-90f - off, cx, cy); sg.setLocalMatrix(mtx)
            pRing.color = Color.WHITE; pRing.shader = sg; pRing.strokeWidth = w2
            c.drawArc(rect, -90f, swB, false, pRing); pRing.shader = null
            headGlow(c, cx, cy, r2, -90f + swB, w2, C_ORANGE, (moveB * 4f).coerceAtMost(1f) * animK * 0.5f + 0.35f * ((brake - 0.75f) / 0.25f).coerceIn(0f, 1f))
        }
        // centre text
        val numCol = lerpColor(netCol, Color.WHITE, 0.35f)
        pTxt.textAlign = Paint.Align.CENTER; pTxt.textSize = D * 0.235f; pTxt.color = numCol
        c.drawText(fmtNetShort(net), cx, cy + pTxt.textSize * 0.30f, pTxt)
        pTxtN.textAlign = Paint.Align.CENTER; pTxtN.textSize = 8f; pTxtN.color = 0x8CFFFFFF.toInt()
        c.drawText(d?.let { "mW · ${it.pct}%" } ?: "mW", cx, cy + R * 0.48f, pTxtN)
        if (hk > 0.05f) check(c, cx, cy - R * 0.43f, D * 0.10f, withAlpha(C_GREEN, hk), 1.6f)
    }

    private fun headGlow(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, w: Float, col: Int, k: Float) {
        if (k < 0.03f) return
        val a = Math.toRadians(deg.toDouble()); val x = cx + cos(a).toFloat() * r; val y = cy + sin(a).toFloat() * r
        val rr = w * (1.0f + 0.8f * k)
        pFill.color = Color.WHITE
        pFill.shader = RadialGradient(x, y, rr, withAlpha(col, 0.75f * k.coerceAtMost(1f)), withAlpha(col, 0f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, rr, pFill); pFill.shader = null
    }

    private fun check(c: Canvas, x: Float, y: Float, s: Float, col: Int, lw: Float) {
        pLine.color = col; pLine.strokeWidth = lw
        path.reset(); path.moveTo(x - s * 0.45f, y); path.lineTo(x - s * 0.12f, y + s * 0.32f); path.lineTo(x + s * 0.5f, y - s * 0.36f)
        c.drawPath(path, pLine)
    }

    /** Draw text left-aligned at x, shrinking it if it would run past maxW. */
    private fun fit(c: Canvas, s: String, x: Float, y: Float, size: Float, maxW: Float, p: Paint, align: Paint.Align = Paint.Align.LEFT) {
        p.textSize = size; p.textAlign = align
        val w = p.measureText(s); if (w > maxW && w > 0) p.textSize = size * maxW / w
        c.drawText(s, x, y, p)
    }

    private fun drawPillText(c: Canvas, left: Float, pw: Float, cy: Float, R: Float, a: Float) {
        val v = d ?: return
        val tx = if (mirror) left + 16f else left + D + 8f
        val right = if (mirror) left + pw - D - 8f else left + pw - 14f
        val tw = right - tx
        val al = (a * 255).toInt()
        // line 1: net + state
        val healthy = hk > 0.5f
        val stTxt = when { healthy -> "Holding ✓"; v.paused -> "Paused"; else -> when (v.st) {
            St.BRAKING -> "Braking"; St.HOLDING, St.IDLE -> "Holding"; St.IN_USE -> "In use"; St.CHARGING_UP -> "Charging up"; St.DRAINING -> "Draining"
            St.HOT -> "Too hot"; St.FAST -> "Fast charger"; St.NO_READING -> "No reading"; St.UNPLUGGED -> "Unplugged"; else -> "Off" } }
        pTxt.color = lerpColor(netCol, Color.WHITE, 0.35f); pTxt.alpha = al
        val l1 = "${fmtNetShort(net)} mW"
        fit(c, l1, tx, cy - R * 0.36f, 13f, tw * 0.55f, pTxt)
        pTxt.color = if (healthy) C_GREEN else stCol; pTxt.alpha = al
        fit(c, stTxt, right, cy - R * 0.36f, 10f, tw * 0.45f, pTxt, Paint.Align.RIGHT)
        // line 2: the target, in % and mAh; while charging up / draining as "54% → 56%"
        val mahTxt = if (v.holdMah.isNaN()) "" else " · " + fmtInt(v.holdMah) + " mAh"
        val l2 = when (v.st) {
            St.CHARGING_UP -> "${v.pct}% → ${v.hold}%" + (if (!v.holdMah.isNaN() && !v.mah.isNaN()) " · ${fmtInt(maxOf(0f, v.holdMah - v.mah))} mAh to go" else "")
            St.DRAINING -> "${v.pct}% → ${v.hold}%" + (if (!v.holdMah.isNaN() && !v.mah.isNaN()) " · ~${fmtInt(maxOf(0f, v.mah - v.holdMah))} mAh to go" else "")
            St.OFF -> "Brake off · ${v.pct}%"
            else -> "Hold ${v.hold}%$mahTxt"
        }
        pTxtN.color = 0xD9FFFFFF.toInt(); pTxtN.alpha = (0.85f * al).toInt()
        fit(c, l2, tx, cy + 1.5f, 10f, tw, pTxtN)
        // line 3: brake + temperature, hold quality on the right
        val q = v.minPerPct
        val qTxt = when { !v.holding -> ""; q.isNaN() -> "measuring"; q.isInfinite() -> "steady"; q >= 60f -> "~${(q / 60f).toInt()} h/1%"; else -> "~${q.toInt()} m/1%" }
        pTxtN.color = 0x9EFFFFFF.toInt(); pTxtN.alpha = (0.62f * al).toInt()
        fit(c, String.format(Locale.US, "Brake %d%% · %.1f °C", (brake * 100).toInt(), v.temp), tx, cy + R * 0.40f, 9.5f, tw * 0.66f, pTxtN)
        if (qTxt.isNotEmpty()) {
            fit(c, qTxt, right, cy + R * 0.40f, 9f, tw * 0.32f, pTxtN, Paint.Align.RIGHT)
            pFill.color = Quality.color(q); pFill.alpha = al
            c.drawCircle(right - pTxtN.measureText(qTxt) - 5f, cy + R * 0.40f - 3f, 2.6f, pFill)
        }
        // sparkline of the last 30 s of net
        val sy = cy + R * 0.72f; val sh = 4.5f
        pLine.strokeWidth = 1f; pLine.color = 0x1FFFFFFF; pLine.alpha = (0x1F * a).toInt(); c.drawLine(tx, sy, right, sy, pLine)
        if (sparkN >= 2) {
            path.reset()
            for (i in 0 until sparkN) { val px = tx + i / (spark.size - 1f) * tw; val py = sy - (spark[i] / 1500f).coerceIn(-1f, 1f) * sh; if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
            pLine.strokeWidth = 1.3f; pLine.color = netCol; pLine.alpha = (230 * a).toInt(); c.drawPath(path, pLine)
        }
    }

    /** Is (x, y) in px on the round bubble part (vs. the pill text)? */
    fun onBubble(x: Float, y: Float): Boolean {
        val R = D / 2f * dn; val cy = (M + D / 2f) * dn
        val cx = if (mirror) width - (M + D / 2f) * dn else (M + D / 2f) * dn
        return hypot(x - cx, y - cy) <= R + M * dn * 0.5f
    }

    companion object { const val D = 64f; const val PW = 248f; const val M = 9f; const val ANIM = 400f; const val MORPH = 260f }
}

/**
 * Owns the overlay window: shows the bubble while the brake service runs and the user turned it on.
 * Tap = small / expanded (persisted), drag = move, release = snap to the nearest side edge (persisted),
 * long-press or tap on the pill = open the app. Updates once per second, only while the screen is on.
 */
class BubbleOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val prefs = Monitor.p
    private val main = Handler(Looper.getMainLooper())
    private var view: BubbleView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var right = prefs.getBoolean("bRight", true)
    private var yFrac = prefs.getFloat("bY", 0.35f)
    private var snapAnim: ValueAnimator? = null
    val shown get() = view != null

    private val tick = object : Runnable {
        override fun run() {
            main.postDelayed(this, 1000)
            val v = view ?: return
            if (pm?.isInteractive == false) return
            v.push(BubbleData.from(Monitor))
        }
    }

    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val v = BubbleView(ctx)
        v.setExpanded(prefs.getBoolean("bExp", false), animate = false)
        v.mirror = right
        v.push(BubbleData.from(Monitor), animate = false)
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val p = WindowManager.LayoutParams(v.wantW().toInt(), v.wantH().toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        p.gravity = Gravity.TOP or Gravity.START
        lp = p; view = v
        place(animate = false)
        v.onCollapsed = { resize() }
        v.setOnTouchListener(Touch())
        runCatching { wm.addView(v, p) }.onFailure { view = null; lp = null; return }
        main.removeCallbacks(tick); main.postDelayed(tick, 1000)
    }

    fun hide() {
        main.removeCallbacks(tick); snapAnim?.cancel()
        view?.let { runCatching { wm.removeView(it) } }
        view = null; lp = null
    }

    private fun screen(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else ctx.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    /** Put the window on its edge at the saved height (also after fold/unfold or a size change). */
    fun place(animate: Boolean = false) {
        val v = view ?: return; val p = lp ?: return
        val (sw, sh) = screen()
        p.width = v.wantW().toInt(); p.height = v.wantH().toInt()
        val tx = if (right) sw - p.width else 0
        p.y = (yFrac * sh).toInt().coerceIn(0, maxOf(0, sh - p.height))
        v.mirror = right
        if (!animate) { p.x = tx; update(); return }
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(p.x, tx).apply { duration = 220; addUpdateListener { p.x = it.animatedValue as Int; update() }; start() }
    }

    private fun resize() { place(animate = false) }
    private fun update() { val v = view ?: return; runCatching { if (v.isAttachedToWindow) wm.updateViewLayout(v, lp) } }

    private fun openApp() {
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        runCatching { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }

    private inner class Touch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var dx = 0f; private var dy = 0f; private var x0 = 0; private var y0 = 0
        private var drag = false; private var long = false
        private val longPress = Runnable { long = true; openApp() }
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = lp ?: return false; val bv = view ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel(); dx = e.rawX; dy = e.rawY; x0 = p.x; y0 = p.y; drag = false; long = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!drag && hypot(e.rawX - dx, e.rawY - dy) > slop) { drag = true; main.removeCallbacks(longPress) }
                    if (drag && !long) { p.x = (x0 + e.rawX - dx).toInt(); p.y = (y0 + e.rawY - dy).toInt(); update() }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (long) return true
                    val (sw, sh) = screen()
                    if (drag) {
                        right = p.x + p.width / 2 > sw / 2
                        yFrac = (p.y.toFloat() / sh).coerceIn(0f, 1f)
                        prefs.edit().putBoolean("bRight", right).putFloat("bY", yFrac).apply()
                        place(animate = true)
                    } else if (bv.expanded && !bv.onBubble(e.x, e.y)) openApp()
                    else {
                        val ex = !bv.expanded
                        prefs.edit().putBoolean("bExp", ex).apply()
                        bv.setExpanded(ex)
                        if (ex) resize()       // grow the window now; it shrinks after the collapse animation
                    }
                }
                MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(longPress); if (drag) place(animate = true) }
            }
            return true
        }
    }
}
