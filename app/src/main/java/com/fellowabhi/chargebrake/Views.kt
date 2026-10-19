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
    var visFrac = 0f                       // first part of the bar in teal (render brake), the rest in `color`
    private val p = Paint(Paint.ANTI_ALIAS_FLAG); private val rect = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val r = h / 2
        p.shader = null; p.maskFilter = null; p.color = 0xFF262A31.toInt()
        rect.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(rect, r, r, p)
        rect.right = width * frac.coerceIn(0f, 1f)
        if (rect.right < 1f) return
        if (gradient) p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, C_GREEN, C_AMBER, Shader.TileMode.CLAMP) else p.color = color
        c.drawRoundRect(rect, r, r, p)
        if (visFrac > 0.005f) { p.shader = null; p.color = C_TEAL; rect.right = width * minOf(visFrac, frac).coerceIn(0f, 1f); c.drawRoundRect(rect, r, r, p) }
    }
}

/**
 * Render brake: soft drifting glows behind the content, redrawn every frame (Live view only). The number of
 * blurred layers follows `level` (0..1); the controller treats this drawing as the first stage of the brake.
 */
class RenderLoad(ctx: Context) : View(ctx) {
    var level = 0f; var color = C_ORANGE
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blurs = Array(4) { BlurMaskFilter((30 + 12 * it) * d, BlurMaskFilter.Blur.NORMAL) }
    override fun onDraw(c: Canvas) {
        if (level < 0.01f) return
        val n = 1 + (level * (MAX_LAYERS - 1)).toInt()
        val t = SystemClock.uptimeMillis() / 1000.0
        val w = width.toFloat(); val h = height.toFloat()
        for (k in 0 until n) {
            val ph = k * 1.7
            val x = w * (0.5f + 0.46f * sin(t * 0.13 * (1 + k % 5) + ph).toFloat())
            val y = h * (0.5f + 0.46f * kotlin.math.cos(t * 0.11 * (1 + k % 3) + ph * 1.3).toFloat())
            p.maskFilter = blurs[k % blurs.size]
            p.color = withAlpha(if (k % 2 == 0) color else C_TEAL, 0.03f * (0.6f + level))
            c.drawCircle(x, y, (60f + (k % 4) * 22f + 6f * sin(t * 2 + k).toFloat()) * d, p)
        }
    }
    companion object { const val MAX_LAYERS = 24 }
}

/** Pulsing LIVE dot. */
class PulseDot(ctx: Context) : View(ctx) {
    var color = 0xFFFF4D4D.toInt()
    var animate = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val k = if (!animate) 0.6f else 0.5f + 0.5f * sin(SystemClock.elapsedRealtime() / 1000.0 * 2 * PI).toFloat()
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

/** Main graph: net mW (smoothed + raw), target band, brake % area, axes. Time span shared with the charge graph. */
class ChartView(ctx: Context, private val h: History) : View(ctx) {
    var glowColor = C_ORANGE
    var headColor = C_GREEN
    var animate = true                       // false = low visual level: static head dot, no glow line
    var simple = false                       // lowest visual level: no raw line, no brake fill
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
    private val pNote = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_AMBER; textSize = 11 * d; isFakeBoldText = true }
    private val path = Path(); private val path2 = Path()
    private var bandK = 0f
    private var burnShader: Shader? = null; private var shaderH = 0f

    init { setOnClickListener { Monitor.nextSpan(); onSpan?.invoke() } }
    var onSpan: (() -> Unit)? = null

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val hh = height.toFloat()
        val L = 40 * d; val R = 34 * d; val T = 8 * d; val B = 18 * d
        val W = w - L - R; val H = hh - T - B
        val windowMs = Monitor.spanMs
        val now = SystemClock.elapsedRealtime() - Monitor.TICK_MS   // lag one tick so the head scrolls in smoothly
        val t0 = now - windowMs
        synchronized(h) {
            val n = h.size
            val first = h.first(t0)
            val stride = maxOf(1, (n - first) / 700)      // ≤ ~700 points per line, whatever the span
            fun ks(f: (Int) -> Unit) { var k = first; while (k < n) { f(k); if (k == n - 1) break; k = minOf(n - 1, k + stride) } }
            var lo = -500f; var hi = 500f
            ks { k -> val i = h.idx(k); lo = minOf(lo, h.ema[i], h.net[i] * 0.7f); hi = maxOf(hi, h.ema[i], h.net[i] * 0.7f) }
            lo = (Math.floor(lo / 500.0) * 500).toFloat(); hi = (Math.ceil(hi / 500.0) * 500).toFloat()
            fun x(t: Long) = L + (t - t0).toFloat() / windowMs * W
            fun y(v: Float) = T + (hi - v) / (hi - lo) * H
            fun yb(b: Float) = T + H - b * H
            val step = intArrayOf(250, 500, 1000, 2000, 5000, 10000).firstOrNull { (hi - lo) / it * 24 * d <= H } ?: 20000
            lo = (Math.floor(lo / step.toDouble()) * step).toFloat(); hi = (Math.ceil(hi / step.toDouble()) * step).toFloat()
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
                ks { k -> val i = h.idx(k); val px = x(h.t[i]); val py = yb(h.burn[i]); path.lineTo(px, py); if (k == first) path2.moveTo(px, py) else path2.lineTo(px, py) }
                path.lineTo(x(h.t[h.idx(n - 1)]), yb(0f)); path.close()
                if (burnShader == null || shaderH != hh) { shaderH = hh; burnShader = LinearGradient(0f, T, 0f, T + H, 0x73FF7043, 0x1AFF7043, Shader.TileMode.CLAMP) }
                pBurn.shader = burnShader
                if (!simple) c.drawPath(path, pBurn)
                c.drawPath(path2, pBurnLine)
            }
            // target band around the current target
            // shown only while holding; dimmed + labelled while charging up to a target; hidden otherwise
            val st = Monitor.state
            val bandA = when (st) { St.HOLDING, St.BRAKING, St.IN_USE, St.IDLE -> 1f; St.CHARGING_UP, St.DRAINING -> 0.3f; else -> 0f }
            bandK = if (animate) bandK + (bandA - bandK) * 0.12f else bandA
            if (bandK > 0.02f) {
                val tg = if (n > 0) h.tgt[h.idx(n - 1)] else 0f
                pBandFill.alpha = (0x1F * bandK).toInt(); pBand.alpha = (0xB2 * bandK).toInt()
                c.drawRect(L, y(tg + 100), L + W, y(tg - 100), pBandFill)
                c.drawLine(L, y(tg + 100), L + W, y(tg + 100), pBand); c.drawLine(L, y(tg - 100), L + W, y(tg - 100), pBand)
                if (st == St.CHARGING_UP || st == St.DRAINING) { pNote.color = if (st == St.DRAINING) C_PURPLE else C_AMBER; pNote.textAlign = Paint.Align.LEFT
                    c.drawText(if (st == St.DRAINING) "draining to target · then holds here" else "charging up to target · brake idle", L + 6 * d, y(tg + 100) - 4 * d, pNote) }
            }
            if (n >= 2) {
                path.reset(); ks { k -> val i = h.idx(k); if (k == first) path.moveTo(x(h.t[i]), y(h.net[i])) else path.lineTo(x(h.t[i]), y(h.net[i])) }
                if (!simple) c.drawPath(path, pRaw)
                path.reset(); ks { k -> val i = h.idx(k); if (k == first) path.moveTo(x(h.t[i]), y(h.ema[i])) else path.lineTo(x(h.t[i]), y(h.ema[i])) }
                if (animate) { pEmaGlow.color = withAlpha(glowColor, 0.30f); c.drawPath(path, pEmaGlow) }
                c.drawPath(path, pEma)
            }
            c.restore()
            if (n >= 1) {
                val i = h.idx(n - 1); val px = x(h.t[i]); val py = y(h.ema[i])
                val k = if (!animate) 0.4f else 0.5f + 0.5f * sin(SystemClock.elapsedRealtime() / 1000.0 * 2 * PI).toFloat()
                pDot.color = withAlpha(headColor, 0.25f + 0.25f * k); c.drawCircle(px, py, (5 + 5 * k) * d, pDot)
                pDot.color = headColor; c.drawCircle(px, py, 3.5f * d, pDot)
            }
            pTxt.textAlign = Paint.Align.LEFT; c.drawText("−" + Monitor.spanLabel() + " · tap to change", L, hh - 5 * d, pTxt)
            pTxt.textAlign = Paint.Align.RIGHT; c.drawText("now", L + W, hh - 5 * d, pTxt)
        }
    }
}

/** Colour of a measured net power: green steady (±100 mW), amber gaining, blue draining. */
fun netColor(mw: Float) = when { mw > 100f -> C_AMBER; mw < -100f -> C_BLUE; else -> C_GREEN }

/**
 * Charge graph: mAh over time (fuel gauge interpolated with the live current, or counted) with the hold/target line.
 * Coloured by the measured net. Auto-zooms on the data in the shared time span; a far-away target is pinned at the edge.
 */
class ChargeView(ctx: Context, private val h: ChargeHistory) : View(ctx) {
    var animate = true
    private val d = ctx.resources.displayMetrics.density
    private fun stroke(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c; strokeWidth = w * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val pGrid = stroke(0xFF22262D.toInt(), 1f)
    private val pLine = stroke(C_BLUE, 2.2f)
    private val pTgt = stroke(C_GREEN, 1.5f).apply { pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_DIM; textSize = 10 * d }
    private val pTgtTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; isFakeBoldText = true }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Path(); private val fill = Path()
    var onSpan: (() -> Unit)? = null
    init { setOnClickListener { Monitor.nextSpan(); onSpan?.invoke() } }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val hh = height.toFloat()
        val L = 46 * d; var R = 34 * d; val T = 16 * d; val B = 16 * d; var W = w - L - R; val H = hh - T - B
        val m = Monitor; val now = SystemClock.elapsedRealtime()
        val win = m.spanMs; val t0 = now - win
        val headV = m.smooth.value
        // target line: live target when on; the configured target (estimated) when off in target mode
        val tgt = Look.goalMah(m); val tgtLive = m.enabled
        synchronized(h) {
            val n = h.size
            val first = h.first(t0)
            val stride = maxOf(1, (n - first) / 600)
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            var k = first
            while (k < n) { val v = h.mah[h.idx(k)]; if (!v.isNaN() && h.t[h.idx(k)] >= t0 - 2000) { lo = minOf(lo, v); hi = maxOf(hi, v) }; k++ }
            if (!headV.isNaN()) { lo = minOf(lo, headV); hi = maxOf(hi, headV) }
            if (lo > hi) { pTxt.textAlign = Paint.Align.LEFT; c.drawText("no charge reading yet", L, hh / 2, pTxt); return }
            // auto-zoom on the recent data; include the target only when it is close
            var sp = maxOf(6f, (hi - lo) * 1.3f)
            if (!tgt.isNaN() && tgt in (lo - 2 * sp)..(hi + 2 * sp)) { lo = minOf(lo, tgt); hi = maxOf(hi, tgt); sp = maxOf(6f, (hi - lo) * 1.3f) }
            val mid = (lo + hi) / 2; lo = mid - sp / 2; hi = mid + sp / 2
            fun x(t: Long) = L + (t - t0).toFloat() / win * W
            fun y(v: Float) = T + (hi - v) / (hi - lo) * H
            // grid + axes: mAh left, % right (mapped through the current reading)
            val ticks = if (H > 90 * d) 4 else 2
            val fine = sp < 40f
            val pctSpan = sp * 100f / m.cfg.capacityMah
            val pctFmt = if (pctSpan < 0.4f) "%.2f%%" else "%.1f%%"
            if (!m.mah.isNaN()) { R = maxOf(34 * d, pTxt.measureText(String.format(java.util.Locale.US, pctFmt, m.pct + (hi - m.mah) * 100f / m.cfg.capacityMah)) + 8 * d); W = w - L - R }
            for (kk in 0..ticks) {
                val v = lo + (hi - lo) * kk / ticks; val yy = y(v)
                pTxt.textAlign = Paint.Align.RIGHT
                c.drawLine(L, yy, L + W, yy, pGrid); c.drawText(if (fine) String.format(java.util.Locale.US, "%,.1f", v) else fmtInt(v), L - 5 * d, yy + 3.5f * d, pTxt)
                if (!m.mah.isNaN()) {
                    pTxt.textAlign = Paint.Align.LEFT
                    c.drawText(String.format(java.util.Locale.US, pctFmt, m.pct + (v - m.mah) * 100f / m.cfg.capacityMah), L + W + 4 * d, yy + 3.5f * d, pTxt)
                }
            }
            pTxt.textAlign = Paint.Align.LEFT; c.drawText("−" + m.spanLabel(win) + " · tap to change", L, hh - 3 * d, pTxt)
            pTxt.textAlign = Paint.Align.RIGHT; c.drawText("now", L + W, hh - 3 * d, pTxt)
            // target / hold line (pinned to the edge with an arrow when off-scale)
            if (!tgt.isNaN()) {
                val inside = tgt in lo..hi
                val ty = y(tgt).coerceIn(T, T + H); pTgt.alpha = if (tgtLive) 255 else 110
                if (inside) c.drawLine(L, ty, L + W, ty, pTgt)
                val st = m.state
                val lbl = when {
                    !tgtLive -> "target ${m.mahLabel(tgt)} (when started)"
                    st == St.DRAINING && !m.mah.isNaN() -> "target ${m.mahLabel(tgt)} · ~${fmtInt(maxOf(0f, m.mah - tgt))} mAh to go"
                    st == St.CHARGING_UP && !m.mah.isNaN() -> "target ${m.mahLabel(tgt)} · ${fmtInt(maxOf(0f, tgt - m.mah))} mAh to go"
                    else -> "hold ${m.mahLabel(tgt)}"
                }.let { if (inside) it else (if (tgt > hi) "↑ " else "↓ ") + it }
                pTgtTxt.color = if (tgtLive) C_GREEN else C_DIM; pTgtTxt.textAlign = Paint.Align.LEFT
                val above = if (inside) ty - 5 * d > T + 2 * d else tgt > hi
                c.drawText(lbl, L + 6 * d, if (above) maxOf(ty - 5 * d, T - 4 * d) else minOf(ty + 13 * d, T + H - 3 * d), pTgtTxt)
            }
            // charge line in runs of one colour (by measured net), each with a soft fill
            c.save(); c.clipRect(L, 0f, L + W, T + H)
            var px = Float.NaN; var py = Float.NaN; var runCol = 0; var runX0 = 0f; var open = false
            fun flush() {
                if (!open) return
                open = false
                pLine.color = runCol; c.drawPath(line, pLine)
                fill.lineTo(px, T + H); fill.lineTo(runX0, T + H); fill.close()
                pFill.color = withAlpha(runCol, 0.10f); c.drawPath(fill, pFill)
            }
            fun start(xx: Float, yy: Float, col: Int) { line.reset(); fill.reset(); line.moveTo(xx, yy); fill.moveTo(xx, yy); runCol = col; runX0 = xx; open = true }
            fun point(xx: Float, yy: Float, col: Int) {
                if (!open) { start(xx, yy, col); px = xx; py = yy; return }
                line.lineTo(xx, yy); fill.lineTo(xx, yy); px = xx; py = yy
                if (col != runCol) { flush(); start(xx, yy, col) }
            }
            k = first
            while (k < n) {
                val i = h.idx(k); val v = h.mah[i]
                if (v.isNaN()) flush() else point(x(h.t[i]), y(v), netColor(h.net[i]))
                if (k == n - 1) break; k = minOf(n - 1, k + stride)
            }
            if (!headV.isNaN()) point(x(now), y(headV), netColor(m.avgMw))
            flush()
            c.restore()
            if (!px.isNaN() && !headV.isNaN()) {
                val kk = if (!animate) 0.4f else 0.5f + 0.5f * sin(now / 1000.0 * 2 * PI).toFloat(); val cc = netColor(m.avgMw)
                pDot.color = withAlpha(cc, 0.25f + 0.25f * kk); c.drawCircle(px, py, (4 + 4 * kk) * d, pDot)
                pDot.color = cc; c.drawCircle(px, py, 3f * d, pDot)
            }
        }
    }
}

/** Simple wrapping row: lays children left to right and wraps to a new line when out of width. */
class FlowRow(ctx: Context, private val hGap: Int, private val vGap: Int) : android.view.ViewGroup(ctx) {
    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = MeasureSpec.getSize(ws) - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0; var usedW = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i); if (v.visibility == GONE) continue
            v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            x += v.measuredWidth + hGap; usedW = maxOf(usedW, x - hGap); lineH = maxOf(lineH, v.measuredHeight)
        }
        val w = if (MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY) MeasureSpec.getSize(ws) else usedW + paddingLeft + paddingRight
        setMeasuredDimension(w, y + lineH + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        // vertically centre items within each line
        val lines = ArrayList<Pair<Int, Int>>(); var start = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i); if (v.visibility == GONE) continue
            if (x > 0 && x + v.measuredWidth > maxW) { lines += start to i; start = i; x = 0 }
            x += v.measuredWidth + hGap
        }
        lines += start to childCount
        for ((a, e) in lines) {
            lineH = 0; for (i in a until e) { val v = getChildAt(i); if (v.visibility != GONE) lineH = maxOf(lineH, v.measuredHeight) }
            x = 0
            for (i in a until e) {
                val v = getChildAt(i); if (v.visibility == GONE) continue
                val top = paddingTop + y + (lineH - v.measuredHeight) / 2
                v.layout(paddingLeft + x, top, paddingLeft + x + v.measuredWidth, top + v.measuredHeight)
                x += v.measuredWidth + hGap
            }
            y += lineH + vGap
        }
    }
}

private fun tnumPaint(ctx: Context, sp: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = sp * ctx.resources.displayMetrics.scaledDensity
    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    fontFeatureSettings = "tnum"
}

/**
 * One line of text that shrinks to fit its width (down to minSp, then ends with "…"). Fixed height (from maxSp),
 * so changing the text never re-lays out anything: only a redraw.
 */
class FitLabel(ctx: Context, private val maxSp: Float, private val minSp: Float, bold: Boolean = false) : View(ctx) {
    private val p = tnumPaint(ctx, maxSp, bold)
    private val maxPx = p.textSize; private val minPx = minSp * ctx.resources.displayMetrics.scaledDensity
    var text: String = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    var color = C_TXT
        set(v) { if (v != field) { field = v; invalidate() } }
    var glow = 0f; var glowColor = 0
    var center = false
    override fun onMeasure(ws: Int, hs: Int) {
        val fm = Paint(p).apply { textSize = maxPx }.fontMetrics
        val h = kotlin.math.ceil(fm.descent - fm.ascent).toInt() + 2
        val w = if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) p.apply { textSize = maxPx }.measureText(text).toInt() + 2 else MeasureSpec.getSize(ws)
        setMeasuredDimension(w, h)
    }
    /** Text size (px) this text gets at width w. */
    fun fitPx(s: String, w: Float): Float { p.textSize = maxPx; val tw = p.measureText(s); return if (tw <= w || tw <= 0f) maxPx else maxOf(minPx, maxPx * w / tw) }
    override fun onDraw(c: Canvas) {
        val w = width - paddingLeft - paddingRight.toFloat()
        var s = text
        p.textSize = fitPx(s, w)
        if (p.measureText(s) > w) { while (s.length > 1 && p.measureText("$s…") > w) s = s.dropLast(1); s = s.trimEnd() + "…" }
        p.color = color
        if (glow > 0.01f) p.setShadowLayer(glow, 0f, 0f, glowColor) else p.clearShadowLayer()
        val fm = p.fontMetrics
        val y = (height - (fm.descent - fm.ascent)) / 2f - fm.ascent
        if (center) { p.textAlign = Paint.Align.CENTER; c.drawText(s, width / 2f, y, p) } else { p.textAlign = Paint.Align.LEFT; c.drawText(s, paddingLeft.toFloat(), y, p) }
    }
}

/**
 * The big net number + unit. Sized once from its width so the widest normal value ("+8,888") fits; digits are
 * tabular and right-aligned in a fixed box, so the unit never moves. Wider values (fast chargers) shrink only themselves.
 */
class BigNumber(ctx: Context, private val maxSp: Float) : View(ctx) {
    private val pN = tnumPaint(ctx, maxSp, true)
    private val pU = tnumPaint(ctx, maxSp * 0.42f, true)
    private val sd = ctx.resources.displayMetrics.scaledDensity
    var text = "+0"; set(v) { if (v != field) { field = v; invalidate() } }
    var unit = "mW"
    var color = C_GREEN; var glow = 0f
    private var sizePx = maxSp * sd
    private fun widthAt(px: Float): Float { pN.textSize = px; pU.textSize = px * 0.42f; return pN.measureText(TEMPLATE) + px * 0.12f + pU.measureText(unit) }
    override fun onMeasure(ws: Int, hs: Int) {
        val avail = if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) widthAt(maxSp * sd) else MeasureSpec.getSize(ws).toFloat()
        val full = widthAt(maxSp * sd)
        sizePx = if (full > avail) maxSp * sd * avail / full else maxSp * sd
        setMeasuredDimension(if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) avail.toInt() + 1 else MeasureSpec.getSize(ws), (sizePx * 1.08f).toInt())
    }
    override fun onDraw(c: Canvas) {
        pN.textSize = sizePx; pU.textSize = sizePx * 0.42f
        val boxW = pN.measureText(TEMPLATE)
        val tw = pN.measureText(text); if (tw > boxW) pN.textSize = sizePx * boxW / tw
        val base = height - pN.fontMetrics.descent * 0.6f
        pN.color = color; pU.color = color
        if (glow > 0.01f) pN.setShadowLayer(glow, 0f, 0f, withAlpha(color, 0.5f)) else pN.clearShadowLayer()
        pN.textAlign = Paint.Align.RIGHT; c.drawText(text, boxW, base, pN)
        pU.textAlign = Paint.Align.LEFT; c.drawText(unit, boxW + sizePx * 0.12f, base, pU)
    }
    companion object { const val TEMPLATE = "+8,888" }
}

/** Small fixed-width two-column table (label · right-aligned tabular value). Changing values only redraws. */
class RawTable(ctx: Context, private val labels: List<String>, private val template: String, sp: Float = 10.5f) : View(ctx) {
    private val pL = tnumPaint(ctx, sp, false).apply { color = C_FAINT }
    private val pV = tnumPaint(ctx, sp, false).apply { color = 0xFFC9CDD3.toInt(); textAlign = Paint.Align.RIGHT }
    private val d = ctx.resources.displayMetrics.density
    private val rowH = sp * ctx.resources.displayMetrics.scaledDensity * 1.42f
    val values = Array(labels.size) { "—" }
    fun set(i: Int, v: String) { if (values[i] != v) { values[i] = v; invalidate() } }
    private val lw = labels.maxOf { pL.measureText(it) }
    override fun onMeasure(ws: Int, hs: Int) = setMeasuredDimension((lw + 10 * d + pV.measureText(template)).toInt() + 1, (rowH * labels.size).toInt() + 1)
    override fun onDraw(c: Canvas) {
        val fm = pV.fontMetrics
        for (i in labels.indices) {
            val y = rowH * i + (rowH - (fm.descent - fm.ascent)) / 2f - fm.ascent
            c.drawText(labels[i], 0f, y, pL)
            val v = values[i]; pV.textSize = pL.textSize
            val maxW = width - lw - 8 * d; val tw = pV.measureText(v); if (tw > maxW) pV.textSize = pL.textSize * maxW / tw
            c.drawText(v, width.toFloat(), y, pV)
            if (i < labels.size - 1) { pL.alpha = 40; c.drawLine(0f, rowH * (i + 1), width.toFloat(), rowH * (i + 1), pL); pL.alpha = 255 }
        }
    }
}

/** "Visual level" indicator: eye icon, a thin teal bar and "42% · 51 fps" (fixed width). */
class VisualMeter(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val icon = ctx.getDrawable(R.drawable.ic_eye)!!.mutate()
    private val pT = tnumPaint(ctx, 10.5f, false).apply { color = C_DIM }
    private val pB = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var level = 0f; var fps = 0f; var smooth = false
    private val template = "100% · 120 fps"
    override fun onMeasure(ws: Int, hs: Int) = setMeasuredDimension((14 * d + 6 * d + 26 * d + 6 * d + pT.measureText(template) + 2 * d).toInt(), (22 * d).toInt())
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val cy = h / 2
        icon.setTint(lerpColor(C_FAINT, C_TEAL, level.coerceIn(0f, 1f))); icon.setBounds(0, (cy - 7 * d).toInt(), (14 * d).toInt(), (cy + 7 * d).toInt()); icon.draw(c)
        val bx = 20 * d; rect.set(bx, cy - 2 * d, bx + 26 * d, cy + 2 * d)
        pB.color = 0xFF262A31.toInt(); c.drawRoundRect(rect, 2 * d, 2 * d, pB)
        rect.right = bx + 26 * d * level.coerceIn(0f, 1f); pB.color = C_TEAL; if (rect.width() > 1f) c.drawRoundRect(rect, 2 * d, 2 * d, pB)
        val s = String.format(java.util.Locale.US, "%d%% · %d fps", (level * 100).safeRound(), fps.safeRound())
        c.drawText(s, bx + 32 * d, cy - (pT.fontMetrics.ascent + pT.fontMetrics.descent) / 2, pT)
    }
}
