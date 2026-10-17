package com.fellowabhi.chargebrake

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Three stacked panels: net power (raw, smoothed, target), burn %, battery %. Tap to switch 5 min / 1 min. */
class Chart(ctx: Context, private val h: History) : View(ctx) {
    private var windowMs = 300_000L
    private val d = resources.displayMetrics.density
    private fun stroke(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c; strokeWidth = w * d }
    private val pRaw = stroke(0x55FFFFFF, 1f)
    private val pEma = stroke(0xFF26C6DA.toInt(), 2f)
    private val pTgt = stroke(0xFFFFB300.toInt(), 1.2f).apply { pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) }
    private val pZero = stroke(0x33FFFFFF, 1f)
    private val pPct = stroke(0xFF64B5F6.toInt(), 2f)
    private val pBurn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFF7043.toInt() }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A8F98.toInt(); textSize = 10 * d }
    private val path = Path()

    init { setOnClickListener { windowMs = if (windowMs == 300_000L) 60_000L else 300_000L; invalidate() } }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF16181D.toInt())
        val w = width.toFloat(); val hh = height.toFloat(); val pad = 4 * d
        val aT = pad; val aB = hh * 0.58f
        val bT = hh * 0.63f; val bB = hh * 0.79f
        val pT = hh * 0.84f; val pB = hh - pad
        synchronized(h) {
            val n = h.size
            if (n < 2) { c.drawText("collecting…", pad * 2, hh / 2, pTxt); return }
            val now = h.t[h.idx(n - 1)]; val t0 = now - windowMs
            var first = 0
            while (first < n - 1 && h.t[h.idx(first)] < t0) first++
            var lo = -300f; var hi = 300f; var pLo = 101; var pHi = -1
            for (k in first until n) { val i = h.idx(k)
                lo = minOf(lo, h.net[i], h.tgt[i]); hi = maxOf(hi, h.net[i], h.tgt[i]); pLo = minOf(pLo, h.pct[i]); pHi = maxOf(pHi, h.pct[i]) }
            val span = hi - lo; lo -= span * 0.08f; hi += span * 0.08f
            fun x(t: Long) = (t - t0).toFloat() / windowMs * w
            fun y(v: Float) = aB - (v - lo) / (hi - lo) * (aB - aT)
            c.drawLine(0f, y(0f), w, y(0f), pZero)
            fun line(get: (Int) -> Float, p: Paint) {
                path.reset()
                for (k in first until n) { val i = h.idx(k); val px = x(h.t[i]); val py = get(i); if (k == first) path.moveTo(px, py) else path.lineTo(px, py) }
                c.drawPath(path, p)
            }
            line({ y(h.net[it]) }, pRaw)
            line({ y(h.tgt[it]) }, pTgt)
            line({ y(h.ema[it]) }, pEma)
            c.drawText("net mW  ${hi.toInt()}", pad, aT + 10 * d, pTxt)
            c.drawText("${lo.toInt()}", pad, aB - 2 * d, pTxt)
            c.drawText(if (windowMs == 300_000L) "5 min" else "1 min", w - 34 * d, aT + 10 * d, pTxt)
            // burn
            c.drawLine(0f, bB, w, bB, pZero)
            path.reset(); path.moveTo(x(h.t[h.idx(first)]), bB)
            for (k in first until n) { val i = h.idx(k); path.lineTo(x(h.t[i]), bB - h.burn[i] * (bB - bT)) }
            path.lineTo(x(now), bB); path.close()
            c.drawPath(path, pBurn)
            c.drawText("burn ${(h.burn[h.idx(n - 1)] * 100).toInt()}%", pad, bT + 10 * d, pTxt)
            // battery %
            val l = pLo - 1f; val u = pHi + 1f
            line({ pB - (h.pct[it] - l) / (u - l) * (pB - pT) }, pPct)
            c.drawText("battery ${h.pct[h.idx(n - 1)]}%  ($pLo–$pHi)", pad, pT + 10 * d, pTxt)
        }
    }
}
