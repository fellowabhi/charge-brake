package com.fellowabhi.chargebrake

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import kotlin.math.PI
import kotlin.math.sin

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** Rounded card with an optional coloured LED-like glow, drawn inside its own bounds (inset by GLOW). */
class GlowFrame(ctx: Context) : LinearLayout(ctx) {
    val inset = ctx.dpf(GLOW_DP)
    private val r = ctx.dpf(16f)
    var glowColor = C_ORANGE; var glow = 0f; var tint = 0f; var stroke = 0f
    var base = C_CARD
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(inset * 1.1f, BlurMaskFilter.Blur.OUTER) }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1f) }
    private val rect = RectF()
    init { setWillNotDraw(false); orientation = VERTICAL }
    fun pad(l: Int, t: Int, rr: Int, b: Int) = setPadding(inset.toInt() + l, inset.toInt() + t, inset.toInt() + rr, inset.toInt() + b)
    override fun onDraw(c: Canvas) {
        rect.set(inset, inset, width - inset, height - inset)
        if (glow > 0.01f) {
            pGlow.color = withAlpha(glowColor, 0.55f * glow)
            c.drawRoundRect(rect, r, r, pGlow)
        }
        pFill.shader = null; pFill.color = base
        c.drawRoundRect(rect, r, r, pFill)
        if (tint > 0.01f) {
            pFill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, withAlpha(glowColor, 0.16f * tint), withAlpha(glowColor, 0.04f * tint), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, r, r, pFill)
        }
        if (stroke > 0.01f) { pStroke.color = withAlpha(glowColor, 0.6f * stroke); c.drawRoundRect(rect, r, r, pStroke) }
    }
    companion object { const val GLOW_DP = 5f }
}

/** Eased progress bar. */
class BarView(ctx: Context) : View(ctx) {
    var frac = 0f; var color = C_ORANGE; var gradient = false; var glow = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val rect = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val r = h / 2
        p.shader = null; p.maskFilter = null; p.color = 0xFF262A31.toInt()
        rect.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(rect, r, r, p)
        rect.right = width * frac.coerceIn(0f, 1f)
        if (rect.right < 1f) return
        if (gradient) p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, C_GREEN, C_AMBER, Shader.TileMode.CLAMP) else p.color = color
        c.drawRoundRect(rect, r, r, p)
    }
}

/** Pulsing LIVE dot. */
class PulseDot(ctx: Context) : View(ctx) {
    var color = 0xFFFF4D4D.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val k = 0.5f + 0.5f * sin(SystemClock.elapsedRealtime() / 1000.0 * 2 * PI).toFloat()
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy)
        p.color = withAlpha(color, 0.25f * k); c.drawCircle(cx, cy, r, p)
        p.color = withAlpha(color, 0.45f + 0.55f * k); c.drawCircle(cx, cy, r * 0.55f, p)
    }
}

/** Big pill toggle with an eased knob. */
class PillSwitch(ctx: Context) : View(ctx) {
    var on = false; var pos = 0f; var color = C_ORANGE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val rect = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat(); val r = h / 2
        rect.set(0f, 0f, w, h)
        p.color = lerpColor(0xFF3A3F47.toInt(), color, pos); c.drawRoundRect(rect, r, r, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(r + (w - 2 * r) * pos, r, r - context.dpf(4f), p)
    }
}

/** Main graph: net mW (smoothed + raw), target band, brake % area, axes. Scrolls smoothly with real time. */
class ChartView(ctx: Context, private val h: History) : View(ctx) {
    var windowMs = 120_000L
    var glowColor = C_ORANGE
    var headColor = C_GREEN
    private val d = ctx.resources.displayMetrics.density
    private fun stroke(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c; strokeWidth = w * d; strokeJoin = Paint.Join.ROUND }
    private val pGrid = stroke(0xFF22262D.toInt(), 1f)
    private val pZero = stroke(0xFF454B55.toInt(), 1f)
    private val pRaw = stroke(0x47FFFFFF, 1f)
    private val pEma = stroke(0xFFFFFFFF.toInt(), 2.2f)
    private val pEmaGlow = stroke(0xFFFFFFFF.toInt(), 6f)
    private val pBand = stroke(0xB24CD07D.toInt(), 1f).apply { pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f) }
    private val pBandFill = Paint().apply { color = 0x1F4CD07D }
    private val pBurnLine = stroke(0xCCFF7043.toInt(), 1f)
    private val pBurn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_DIM; textSize = 10 * d }
    private val pTxtR = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9785F.toInt(); textSize = 10 * d }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path(); private val path2 = Path()

    init { setOnClickListener { windowMs = when (windowMs) { 60_000L -> 120_000L; 120_000L -> 300_000L; else -> 60_000L } } }

    fun label() = when (windowMs) { 60_000L -> "1 min"; 120_000L -> "2 min"; else -> "5 min" }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val hh = height.toFloat()
        val L = 40 * d; val R = 34 * d; val T = 8 * d; val B = 18 * d
        val W = w - L - R; val H = hh - T - B
        val now = SystemClock.elapsedRealtime() - Monitor.TICK_MS   // lag one tick so the head scrolls in smoothly
        val t0 = now - windowMs
        synchronized(h) {
            val n = h.size
            var lo = -500f; var hi = 500f; var first = 0
            while (first < n - 1 && h.t[h.idx(first + 1)] < t0) first++
            for (k in first until n) { val i = h.idx(k); lo = minOf(lo, h.ema[i], h.net[i] * 0.7f); hi = maxOf(hi, h.ema[i], h.net[i] * 0.7f) }
            lo = (Math.floor(lo / 500.0) * 500).toFloat(); hi = (Math.ceil(hi / 500.0) * 500).toFloat()
            fun x(t: Long) = L + (t - t0).toFloat() / windowMs * W
            fun y(v: Float) = T + (hi - v) / (hi - lo) * H
            fun yb(b: Float) = T + H - b * H
            val step = if (hi - lo > 3000) 1000 else 500
            var v = lo.toInt()
            pTxt.textAlign = Paint.Align.RIGHT
            while (v <= hi) { c.drawLine(L, y(v.toFloat()), L + W, y(v.toFloat()), if (v == 0) pZero else pGrid)
                c.drawText((if (v > 0) "+" else "") + v, L - 5 * d, y(v.toFloat()) + 3.5f * d, pTxt); v += step }
            pTxtR.textAlign = Paint.Align.LEFT
            for (p in intArrayOf(0, 50, 100)) c.drawText("$p%", L + W + 5 * d, yb(p / 100f) + 3.5f * d, pTxtR)
            c.save(); c.clipRect(L, T - 2 * d, L + W, T + H + 2 * d)
            if (n >= 2) {
                // brake area
                path.reset(); path2.reset()
                path.moveTo(x(h.t[h.idx(first)]), yb(0f))
                for (k in first until n) { val i = h.idx(k); val px = x(h.t[i]); val py = yb(h.burn[i]); path.lineTo(px, py); if (k == first) path2.moveTo(px, py) else path2.lineTo(px, py) }
                path.lineTo(x(h.t[h.idx(n - 1)]), yb(0f)); path.close()
                pBurn.shader = LinearGradient(0f, T, 0f, T + H, 0x73FF7043, 0x1AFF7043, Shader.TileMode.CLAMP)
                c.drawPath(path, pBurn); c.drawPath(path2, pBurnLine)
            }
            // target band around the current target
            val tg = if (n > 0) h.tgt[h.idx(n - 1)] else 0f
            c.drawRect(L, y(tg + 100), L + W, y(tg - 100), pBandFill)
            c.drawLine(L, y(tg + 100), L + W, y(tg + 100), pBand); c.drawLine(L, y(tg - 100), L + W, y(tg - 100), pBand)
            if (n >= 2) {
                path.reset(); for (k in first until n) { val i = h.idx(k); if (k == first) path.moveTo(x(h.t[i]), y(h.net[i])) else path.lineTo(x(h.t[i]), y(h.net[i])) }
                c.drawPath(path, pRaw)
                path.reset(); for (k in first until n) { val i = h.idx(k); if (k == first) path.moveTo(x(h.t[i]), y(h.ema[i])) else path.lineTo(x(h.t[i]), y(h.ema[i])) }
                pEmaGlow.color = withAlpha(glowColor, 0.30f); c.drawPath(path, pEmaGlow)
                c.drawPath(path, pEma)
            }
            c.restore()
            if (n >= 1) {
                val i = h.idx(n - 1); val px = x(h.t[i]); val py = y(h.ema[i])
                val k = 0.5f + 0.5f * sin(SystemClock.elapsedRealtime() / 1000.0 * 2 * PI).toFloat()
                pDot.color = withAlpha(headColor, 0.25f + 0.25f * k); c.drawCircle(px, py, (5 + 5 * k) * d, pDot)
                pDot.color = headColor; c.drawCircle(px, py, 3.5f * d, pDot)
            }
            pTxt.textAlign = Paint.Align.LEFT; c.drawText("−" + label(), L, hh - 5 * d, pTxt)
            pTxt.textAlign = Paint.Align.RIGHT; c.drawText("now", L + W, hh - 5 * d, pTxt)
        }
    }
}

/** Charge strip: mAh over time with the hold target as a dashed line. */
class StripView(ctx: Context, private val h: History, private val chart: () -> Long) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = C_BLUE; strokeWidth = 2 * d }
    private val pTgt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = C_BLUE; strokeWidth = 1 * d; pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f) }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_DIM; textSize = 10 * d }
    private val pTgtTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7DB8FF.toInt(); textSize = 10 * d }
    private val path = Path()
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val hh = height.toFloat()
        val L = 40 * d; val R = 34 * d; val T = 6 * d; val B = 6 * d; val W = w - L - R; val H = hh - T - B
        val win = chart(); val now = SystemClock.elapsedRealtime() - Monitor.TICK_MS; val t0 = now - win
        synchronized(h) {
            val n = h.size; if (n < 2) return
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var first = 0
            while (first < n - 1 && h.t[h.idx(first + 1)] < t0) first++
            for (k in first until n) { val i = h.idx(k); if (!h.mah[i].isNaN()) { lo = minOf(lo, h.mah[i]); hi = maxOf(hi, h.mah[i]) }; if (!h.tmah[i].isNaN()) { lo = minOf(lo, h.tmah[i]); hi = maxOf(hi, h.tmah[i]) } }
            if (lo > hi) { pTxt.textAlign = Paint.Align.LEFT; c.drawText("no charge reading yet", L, hh / 2, pTxt); return }
            val mid = (lo + hi) / 2; val span = maxOf(10f, (hi - lo) * 1.3f); lo = mid - span / 2; hi = mid + span / 2
            fun x(t: Long) = L + (t - t0).toFloat() / win * W
            fun y(v: Float) = T + (hi - v) / (hi - lo) * H
            pTxt.textAlign = Paint.Align.RIGHT
            c.drawText(fmtInt(hi), L - 5 * d, T + 8 * d, pTxt); c.drawText(fmtInt(lo), L - 5 * d, T + H, pTxt)
            val tm = h.tmah[h.idx(n - 1)]
            if (!tm.isNaN()) {
                c.drawLine(L, y(tm), L + W, y(tm), pTgt)
                pTgtTxt.textAlign = Paint.Align.LEFT; c.drawText("target " + fmtInt(tm), L + 4 * d, y(tm) - 4 * d, pTgtTxt)
            }
            path.reset(); var started = false
            c.save(); c.clipRect(L, 0f, L + W, hh)
            for (k in first until n) { val i = h.idx(k); val m = h.mah[i]; if (m.isNaN()) continue
                if (!started) { path.moveTo(x(h.t[i]), y(m)); started = true } else path.lineTo(x(h.t[i]), y(m)) }
            c.drawPath(path, pLine); c.restore()
        }
    }
}
