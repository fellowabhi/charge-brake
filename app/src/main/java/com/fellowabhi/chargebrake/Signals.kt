package com.fellowabhi.chargebrake

import kotlin.math.abs

/** Drops a single isolated 0 (or unsupported) current reading; two in a row are passed through. */
class ZeroFilter {
    private var last = 0
    private var zeros = 0
    fun filter(raw: Int): Int {
        if (raw == 0) { zeros++; if (zeros == 1 && last != 0) return last } else zeros = 0
        last = raw
        return raw
    }
}

/**
 * Battery charge in mAh. Prefers the fuel gauge (BATTERY_PROPERTY_CHARGE_COUNTER) when it is plausible and
 * actually changes; otherwise counts current (coulomb counting) and re-anchors it whenever the % changes.
 */
class MahTracker {
    var mah = Float.NaN; private set
    var source = "none"; private set
    var gaugeMah = Float.NaN; private set
    private var lastCc = Float.NaN
    private var changes = 0
    private var sinceChange = 0f     // signed mAh counted since the gauge last moved
    private var lastPct = -1
    private var lastT = -1L

    val gaugeOk get() = source == SRC_GAUGE

    fun reset() { mah = Float.NaN; source = "none"; lastCc = Float.NaN; changes = 0; sinceChange = 0f; lastPct = -1; lastT = -1 }

    /** @return true if the source changed (caller should re-target). */
    fun update(tMs: Long, ccRaw: Int, mA: Float, mAok: Boolean, pct: Int, cap: Float): Boolean {
        val dt = if (lastT < 0) 0f else ((tMs - lastT) / 1000f).coerceIn(0f, 5f)
        lastT = tMs
        val dMah = if (mAok) mA * dt / 3600f else 0f
        // gauge: µAh per the docs; some devices report mAh
        val v = when {
            ccRaw == Int.MIN_VALUE || ccRaw == Int.MAX_VALUE || ccRaw <= 0 -> Float.NaN
            ccRaw > 50_000 -> ccRaw / 1000f
            else -> ccRaw.toFloat()
        }
        val plausible = !v.isNaN() && v in cap * 0.02f..cap * 1.3f
        gaugeMah = v
        if (plausible) {
            if (lastCc.isNaN() || v != lastCc) { if (!lastCc.isNaN()) changes++; lastCc = v; sinceChange = 0f }
            else sinceChange += dMah
        }
        val gauge = plausible && changes >= 2 && abs(sinceChange) < 6f
        val old = source
        if (gauge) { mah = v; source = SRC_GAUGE }
        else {
            if (mah.isNaN() || pct < 0) mah = (pct.coerceAtLeast(0) + 0.5f) * cap / 100f
            else mah += dMah
            if (lastPct >= 0 && pct != lastPct) {   // re-anchor at the % boundary we just crossed
                mah = if (pct > lastPct) pct * cap / 100f + 0.1f else (pct + 1) * cap / 100f - 0.1f
            }
            // keep inside the reported % bucket
            mah = mah.coerceIn(pct * cap / 100f, (pct + 1) * cap / 100f)
            source = SRC_COUNT
        }
        lastPct = pct
        return old != "none" && old != source
    }

    companion object { const val SRC_GAUGE = "fuel gauge"; const val SRC_COUNT = "counted" }
}

/** Hold quality: 5 min mean of the net power (1 Hz samples) -> minutes to drift 1 % of capacity. */
class DriftMeter(private val window: Int = 300) {
    private val win = ArrayDeque<Float>()
    var mw = Float.NaN; private set           // mean net (mW)
    var noise = 0f; private set               // 2 standard errors of the mean (min 15 mW)
    var minPerPct = Float.NaN; private set    // +Inf = steady (within noise); NaN = measuring / not holding
    var reason = ""; private set
    fun reset() { win.clear(); mw = Float.NaN; minPerPct = Float.NaN; reason = "" }
    fun add(netMw: Float, volt: Float, capMah: Float, burn: Float, maxBurn: Float) {
        win.addLast(netMw); while (win.size > window) win.removeFirst()
        val n = win.size
        val mean = win.average().toFloat()
        val sd = kotlin.math.sqrt(win.sumOf { ((it - mean) * (it - mean)).toDouble() } / maxOf(1, n - 1)).toFloat()
        mw = mean; noise = maxOf(15f, 2f * sd / kotlin.math.sqrt(n.toFloat()))
        if (n < 60) { minPerPct = Float.NaN; reason = "measuring"; return }
        if (kotlin.math.abs(mean) < noise) { minPerPct = Float.POSITIVE_INFINITY; reason = ""; return }
        minPerPct = capMah / 100f / (kotlin.math.abs(mean) / volt.coerceAtLeast(3f)) * 60f
        reason = when {
            minPerPct >= 10f -> ""
            mean > 0 && burn >= maxBurn - 0.02f -> "charger too strong, brake maxed"
            mean < 0 && burn < 0.03f -> "charger too weak, draining"
            mean > 0 -> "brake still catching up"
            else -> "phone use above the charger"
        }
    }
}

/**
 * Display value for the charge graph: follows the mAh reading (fuel gauge or counted) but moves smoothly between
 * its steps by integrating the live current, then is pulled gently onto the reading. A source switch or any
 * jump > JUMP mAh is returned as a shift so the caller can move the old curve instead of drawing a step.
 */
class MahSmoother {
    var value = Float.NaN; private set
    private var src = ""
    fun reset() { value = Float.NaN; src = "" }
    fun update(dt: Float, mah: Float, source: String, mA: Float, mAok: Boolean): Float {
        if (mah.isNaN()) { value = Float.NaN; return 0f }
        if (value.isNaN()) { value = mah; src = source; return 0f }
        if (source != src || abs(mah - value) > JUMP) { val d = mah - value; value = mah; src = source; return d }
        if (mAok) value += mA * dt / 3600f
        val tau = if (source == MahTracker.SRC_GAUGE) 30f else 4f
        value += (mah - value) * (dt / tau).coerceAtMost(1f)
        return 0f
    }
    companion object { const val JUMP = 15f }
}

/**
 * Own UI power cost (estimate): average of (net + brake) — the power left over after the phone's own use — while the
 * screen is on with the app in front (eco or live) vs. with it in the background. The difference is what the
 * app's screen costs. 10 min averages, separate for plugged / on battery; needs ≥ 60 s of each.
 */
class UiCost {
    private val mean = Array(2) { FloatArray(3) { Float.NaN } }   // [plugged][ui mode]
    private val secs = Array(2) { FloatArray(3) }
    fun add(mode: Int, screenOn: Boolean, plugged: Boolean, sur: Float, dt: Float) {
        if (!screenOn || mode !in 0..2) return
        val p = if (plugged) 1 else 0; val m = mean[p][mode]
        mean[p][mode] = if (m.isNaN()) sur else m + (sur - m) * dt / (600f + dt)
        secs[p][mode] += dt
    }
    fun seconds(mode: Int, plugged: Boolean) = secs[if (plugged) 1 else 0][mode]
    /** mW the given UI mode costs vs. the app in the background (NaN = not enough data). */
    fun cost(mode: Int, plugged: Boolean): Float {
        val p = if (plugged) 1 else 0
        return if (secs[p][mode] >= 60f && secs[p][0] >= 60f) mean[p][0] - mean[p][mode] else Float.NaN
    }
}
