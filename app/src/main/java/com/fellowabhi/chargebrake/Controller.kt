package com.fellowabhi.chargebrake

import kotlin.math.abs

object Mode {
    const val AUTO = 0
    const val MW = 1
    const val PCT = 2
    fun name(m: Int) = when (m) { MW -> "mW"; PCT -> "%"; else -> "Auto" }
}

enum class St { OFF, UNPLUGGED, CHARGING_UP, BRAKING, HOLDING, IN_USE, IDLE, HOT, FAST, NO_READING, PAUSED }

/** User settings. Plain fields so the controller stays Android-free (unit testable). */
class Cfg {
    @Volatile var mode = Mode.AUTO
    @Volatile var holdCurrent = true
    @Volatile var targetPct = 60
    @Volatile var targetMw = 100          // net target when no mAh reading is available
    @Volatile var drainMw = -150          // used (no-mAh fallback) when above the hold level
    @Volatile var fastCutoffMw = 6000     // 10 s average of (net + brake) above this = fast charger
    @Volatile var tempCutoffC = 45f
    @Volatile var aggr = 3                // 1..5
    @Volatile var maxBurn = 0.8f          // 0..1
    @Volatile var burnMwFull = 1500f      // rough mW at burn = 1.0
    @Volatile var capacityMah = 4860f
}

class Input(
    val tMs: Long,
    val netMw: Float,
    val mwOk: Boolean,
    val pct: Int,
    val plugged: Boolean,
    val tempC: Float,
    val mah: Float = Float.NaN,   // fuel gauge or coulomb-counted charge, NaN if unknown
) { val mahOk get() = !mah.isNaN() }

/**
 * Inner loop: CPU "burn" (0..1) keeps net battery power near tgtMw (ramp up slowly, back off fast on real use).
 * Outer loop: tgtMw is nudged so the charge (mAh) stays at the hold target. Fallback: % level.
 */
class Controller(val cfg: Cfg) {
    var burn = 0f; private set
    var emaFast = 0f; private set
    var emaUse = 0f; private set
    var emaSlow = 0f; private set
    var tgtMw = 0f; private set
    var holdPct = -1
    var targetMah = Float.NaN; private set
    var st = St.OFF; private set
    var effMode = Mode.MW; private set
    var fastMw = 0f; private set          // 10 s average of gross estimate
    var fastRetryInS = 0; private set
    var ignoreFast = false                // user tapped Ignore (until unplug)
    var paused = false                    // user tapped Pause
    var hot = false; private set
    var draining = false; private set
    var useS = 0f; private set

    private var lastT = -1L
    private var lastNet = Float.NaN
    private var lastChgMs = -1L
    var sensorPeriodS = 0.55f; private set   // estimated from how often the reading changes
    private var primed = false
    private var drainSince = Long.MIN_VALUE / 2
    private var fastUntil = -1L
    private val win = ArrayDeque<Pair<Long, Float>>()
    private var winSum = 0.0
    private var reachedTarget = false

    fun arm(pct: Int, mah: Float, keepHold: Boolean = false) {
        if (!keepHold || holdPct < 0) holdPct = pct
        burn = 0f; primed = false; lastT = -1; draining = false; fastUntil = -1; win.clear(); winSum = 0.0
        targetMah = Float.NaN; reachedTarget = false
        retarget(pct, mah)
        st = St.IDLE
    }

    fun disarm() { burn = 0f; primed = false; lastT = -1; st = St.OFF }

    /** Called on hold-mode / target changes: resets the drain/charge choice and the mAh target. */
    fun retarget(pct: Int, mah: Float) {
        draining = false; drainSince = Long.MIN_VALUE / 2; reachedTarget = false
        targetMah = Float.NaN
        if (cfg.holdCurrent) { holdPct = pct; if (!mah.isNaN()) targetMah = mah; reachedTarget = true }
        else if (pct >= cfg.targetPct && !mah.isNaN()) {
            targetMah = mah - (pct - cfg.targetPct - 0.5f) * cfg.capacityMah / 100f; reachedTarget = true
        }
    }

    fun hold(): Int = if (cfg.holdCurrent) holdPct else cfg.targetPct

    fun step(i: Input): Float {
        val dt = if (lastT < 0) 0.25f else ((i.tMs - lastT) / 1000f).coerceIn(0f, 2f)
        lastT = i.tMs
        val a = cfg.aggr.coerceIn(1, 5) - 1

        if (!i.plugged) {
            burn = 0f; primed = false; draining = false; fastUntil = -1; ignoreFast = false; win.clear(); winSum = 0.0
            st = St.UNPLUGGED
            return burn
        }
        if (i.mwOk && i.netMw != lastNet) {
            if (lastChgMs >= 0) sensorPeriodS += (((i.tMs - lastChgMs) / 1000f).coerceIn(0.1f, 5f) - sensorPeriodS) * 0.05f
            lastChgMs = i.tMs; lastNet = i.netMw
        }
        if (i.mwOk) {
            if (!primed) { emaFast = i.netMw; emaUse = i.netMw; emaSlow = i.netMw; primed = true }
            else {
                emaFast += (i.netMw - emaFast) * dt / (0.8f + dt)
                // usage detector: 2.5 s at the field sensor rate (~1.8 Hz), longer for slower sensors
                val tauUse = (2.5f * sensorPeriodS / 0.55f).coerceIn(2.5f, 6f)
                emaUse += (i.netMw - emaUse) * dt / (tauUse + dt)
                emaSlow += (i.netMw - emaSlow) * dt / (TAU_SLOW[a] + dt)
            }
            // fast charger: 10 s boxcar average of net + our own burn
            val g = i.netMw + burn * cfg.burnMwFull
            win.addLast(i.tMs to g); winSum += g
            while (win.isNotEmpty() && i.tMs - win.first().first > 10_000) winSum -= win.removeFirst().second
            fastMw = (winSum / win.size).toFloat()
            val full = win.isNotEmpty() && i.tMs - win.first().first >= 9_000
            if (!ignoreFast && fastUntil < 0 && full && fastMw > cfg.fastCutoffMw) fastUntil = i.tMs + 120_000
        }
        if (fastUntil >= 0 && (i.tMs >= fastUntil || ignoreFast)) { fastUntil = -1; win.clear(); winSum = 0.0 }
        fastRetryInS = if (fastUntil >= 0) ((fastUntil - i.tMs) / 1000).toInt() else 0
        if (i.tempC >= cfg.tempCutoffC) hot = true else if (i.tempC < cfg.tempCutoffC - 1f) hot = false

        // target-% mode: once the level reaches the target, lock the mAh target there
        if (!cfg.holdCurrent && !reachedTarget && i.pct >= cfg.targetPct) { reachedTarget = true; if (i.mahOk) targetMah = i.mah + cfg.capacityMah * 0.004f }
        if (targetMah.isNaN() && i.mahOk && reachedTarget) targetMah = i.mah

        effMode = if (cfg.mode == Mode.AUTO) (if (i.mwOk) Mode.MW else Mode.PCT) else cfg.mode
        val h = hold()
        when {
            paused -> { burn = 0f; st = St.PAUSED }
            fastUntil >= 0 -> { burn = 0f; st = St.FAST }
            hot -> { burn = 0f; st = St.HOT }
            i.pct < h || (!cfg.holdCurrent && !reachedTarget) -> { burn = 0f; draining = false; st = St.CHARGING_UP }
            effMode == Mode.MW -> mw(i, dt, h, a)
            else -> pct(i, dt, h, a)
        }
        burn = burn.coerceIn(0f, cfg.maxBurn)
        return burn
    }

    private fun mw(i: Input, dt: Float, h: Int, a: Int) {
        if (!i.mwOk) { burn -= dt / 5f; st = St.NO_READING; return }
        tgtMw = if (i.mahOk && !targetMah.isNaN()) {
            ((targetMah - i.mah) * MAH_GAIN).coerceIn(-250f, 150f)           // outer mAh loop
        } else {
            val wantDrain = i.pct > h                                          // fallback: % guard, 10 min dwell
            if (wantDrain != draining && i.tMs - drainSince >= DWELL_MS) { draining = wantDrain; drainSince = i.tMs }
            if (draining) cfg.drainMw.toFloat() else cfg.targetMw.toFloat()
        }
        val g = cfg.burnMwFull
        val kUp = 1f / (g * TAU_UP[a])
        val kDn = 1f / (g * 8f)
        val kUse = 1f / (g * 2f)
        val eS = emaSlow - tgtMw
        val eU = emaUse - tgtMw
        useS = if (eU < -USE_MW) useS + dt else 0f
        when {
            useS >= 2f -> { burn -= kUse * (-eU) * dt; st = St.IN_USE }
            eS < -BAND -> { burn -= kDn * (-eS - BAND) * dt; st = if (burn > 0.005f) St.HOLDING else St.IDLE }
            eS > BAND -> { burn += kUp * (eS - BAND) * dt; st = St.BRAKING }
            else -> st = if (burn > 0.005f) St.HOLDING else St.IDLE
        }
        if (st == St.BRAKING && eS < 250f) st = St.HOLDING
    }

    private fun pct(i: Input, dt: Float, h: Int, a: Int) {
        tgtMw = 0f
        val up = cfg.maxBurn / PCT_UP_S[a]
        if (i.pct > h) { burn += up * dt; st = St.BRAKING } else { burn -= up * 1.5f * dt; st = St.HOLDING }
    }

    companion object {
        const val BAND = 50f
        const val USE_MW = 400f
        const val MAH_GAIN = 60f          // mW of target per mAh of error
        const val DWELL_MS = 600_000L
        val TAU_SLOW = floatArrayOf(6f, 5f, 4f, 3f, 2.5f)
        val TAU_UP = floatArrayOf(27f, 20f, 13f, 9f, 7f)   // v0.1 / 1.5
        val PCT_UP_S = floatArrayOf(400f, 300f, 200f, 140f, 100f)
    }
}
