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
    var animate = true                       // false = eco: static head dot, no glow line
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
                c.drawPath(path, pBurn); c.drawPath(path2, pBurnLine)
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
                c.drawPath(path, pRaw)
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
                    !tgtLive -> "target ${m.cfg.targetPct}% ≈ ${fmtInt(tgt)} mAh (when started)"
                    st == St.DRAINING && !m.mah.isNaN() -> "target ${m.ctl.hold()}% · ${fmtInt(tgt)} mAh · ~${fmtInt(maxOf(0f, m.mah - tgt))} mAh to go"
                    st == St.CHARGING_UP && !m.mah.isNaN() -> "target ${m.ctl.hold()}% ≈ ${fmtInt(tgt)} mAh · ${fmtInt(maxOf(0f, tgt - m.mah))} mAh to go"
                    else -> "hold ${m.ctl.hold()}% · ${fmtInt(tgt)} mAh"
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
