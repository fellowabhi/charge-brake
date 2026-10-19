package com.fellowabhi.chargebrake

import kotlin.math.abs

object Mode {
    const val AUTO = 0
    const val MW = 1
    const val PCT = 2
    fun name(m: Int) = when (m) { MW -> "mW"; PCT -> "%"; else -> "Auto" }
}

enum class St { OFF, UNPLUGGED, CHARGING_UP, DRAINING, BRAKING, HOLDING, IN_USE, IDLE, HOT, FAST, NO_READING, PAUSED }

/** How fast to come down when the target is below the current level. */
object Drain {
    const val GENTLE = 0
    const val NORMAL = 1
    const val FAST = 2
    fun name(d: Int) = when (d) { GENTLE -> "Gentle"; FAST -> "Fast"; else -> "Normal" }
    fun desc(d: Int) = when (d) { GENTLE -> "−150 mW"; FAST -> "max brake"; else -> "−400 mW" }
    fun mw(d: Int) = when (d) { GENTLE -> -150f; FAST -> Float.NEGATIVE_INFINITY; else -> -400f }
}

/** User settings. Plain fields so the controller stays Android-free (unit testable). */
class Cfg {
    @Volatile var mode = Mode.AUTO
    @Volatile var holdCurrent = true
    @Volatile var targetPct = 60
    @Volatile var targetMahSet = Float.NaN   // target mode: the target in mAh (converted once from the % the user picked, or typed in mAh)
    @Volatile var visCap = 0f             // render-brake capacity available now, in burn units (0 = CPU only)
    @Volatile var targetMw = 100          // net target when no mAh reading is available
    @Volatile var drainSpeed = Drain.NORMAL  // above the target: Gentle −150 mW, Normal −400 mW, Fast = max brake (temp cutoff still applies)
    @Volatile var fastCutoffMw = 6000     // 10 s average of (net + brake) above this = fast charger
    @Volatile var tempCutoffC = 45f
    @Volatile var aggr = 3                // 1..6 (6 = Direct)
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
    var holdPct = -1                      // Hold current: locked % (only re-captured by lockHold)
    var holdMah = Float.NaN               // Hold current: locked mAh (NaN until known)
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
    var short = false; private set       // net below the target (brake should come down): the split drops visuals first
    // feed-forward: predicted error once the brake already commanded shows up in the (lagging) reading
    var ePred = 0f; private set
    private var emaFF = 0f
    private var ffHold = 0f; private var ffCool = 0f
    private var burnLag = 0f
    private var burnLagS = 0f            // the same through the slow filter: the integral works on the predicted error too
    // time to settle after a step (power change > STEP_MW): until the net stays within SETTLE_MW for SETTLE_HOLD_S
    var lastSettleS = Float.NaN; private set
    var avgSettleS = Float.NaN; private set
    var settling = false; private set
    private var settleT0 = 0L; private var inBandSince = -1L
    private var emaSettle = 0f              // fixed 6 s average for the settle measure (same for every level)

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

    /** Lock the Hold-current target (the exact mAh now). Called only when the user enables the brake or taps Hold current. */
    fun lockHold(pct: Int, mah: Float) { holdPct = pct; holdMah = mah }

    /** Most brake output: CPU (Max brake) plus the render stage when it is available. */
    val maxOut get() = cfg.maxBurn + cfg.visCap

    /** User picked a target %: convert it ONCE to mAh (middle of that % step, relative to the reading now). */
    fun setTargetPct(p: Int, pct: Int, mah: Float) {
        cfg.targetPct = p
        cfg.targetMahSet = if (mah.isNaN() || pct < 0) Float.NaN else mah + (p - pct) * cfg.capacityMah / 100f
    }
    /** User typed the target in mAh. */
    fun setTargetMah(v: Float, pct: Int, mah: Float) {
        cfg.targetMahSet = v
        if (!mah.isNaN() && pct >= 0) cfg.targetPct = (pct + (v - mah) * 100f / cfg.capacityMah).toInt().coerceIn(1, 100)
    }
    /** The mAh the hold aims at (NaN = not known yet -> % fallback). */
    fun goal() = if (cfg.holdCurrent) holdMah else cfg.targetMahSet
    /** ≈ % of an mAh value, through the reading now. */
    fun approxPct(v: Float, pct: Int, mah: Float): Int = if (v.isNaN() || mah.isNaN()) hold() else (pct + 0.5f + (v - mah) * 100f / cfg.capacityMah).toInt()

    fun arm(pct: Int, mah: Float, keepHold: Boolean = false) {
        if (!keepHold || holdPct < 0) lockHold(pct, mah)
        burn = 0f; primed = false; lastT = -1; draining = false; fastUntil = -1; win.clear(); winSum = 0.0
        targetMah = Float.NaN; reachedTarget = false; burnLag = 0f; burnLagS = 0f; settling = false; inBandSince = -1
        retarget(pct, mah)
        st = St.IDLE
    }

    fun disarm() { burn = 0f; burnLag = 0f; burnLagS = 0f; primed = false; lastT = -1; st = St.OFF; settling = false; short = false }

    /** Called on hold-mode / target changes: resets the drain/charge choice; the target mAh itself is never re-captured here. */
    fun retarget(pct: Int, mah: Float) {
        draining = false; drainSince = Long.MIN_VALUE / 2
        if (cfg.holdCurrent && holdPct < 0) lockHold(pct, mah)
        derive(pct, mah)
        targetMah = goal()
        reachedTarget = !targetMah.isNaN() && !mah.isNaN() && mah >= targetMah - REARM_MAH
    }
    /** First mAh reading after a lock / % choice made without one: derive the mAh from the %. */
    private fun derive(pct: Int, mah: Float) {
        if (mah.isNaN() || pct < 0) return
        if (cfg.holdCurrent) { if (holdMah.isNaN() && holdPct >= 0) holdMah = mah + (holdPct - pct) * cfg.capacityMah / 100f }
        else if (cfg.targetMahSet.isNaN()) cfg.targetMahSet = mah + (cfg.targetPct - pct) * cfg.capacityMah / 100f
    }

    /** The mAh scale changed (fuel gauge <-> counted) and we don't know the offset: re-derive from the locked %. */
    fun rebase(pct: Int, mah: Float) { if (cfg.holdCurrent) holdMah = Float.NaN else cfg.targetMahSet = Float.NaN; retarget(pct, mah) }
    /** The mAh scale changed by a known offset d: move the targets with it (exact). */
    fun shiftScale(d: Float) {
        if (!holdMah.isNaN()) holdMah += d
        if (!cfg.targetMahSet.isNaN()) cfg.targetMahSet += d
        targetMah = goal()
    }

    fun hold(): Int = if (cfg.holdCurrent) holdPct else cfg.targetPct

    fun step(i: Input): Float {
        val dt = if (lastT < 0) 0.25f else ((i.tMs - lastT) / 1000f).coerceIn(0f, 2f)
        lastT = i.tMs
        val a = cfg.aggr.coerceIn(1, AGGR_MAX) - 1

        if (!i.plugged) {
            burn = 0f; burnLag = 0f; burnLagS = 0f; primed = false; draining = false; fastUntil = -1; ignoreFast = false; win.clear(); winSum = 0.0
            settling = false; inBandSince = -1; short = false
            st = St.UNPLUGGED
            return burn
        }
        // a garbage reading (NaN / inf) is treated as no reading
        if (i.mwOk && !i.netMw.isFinite()) return step(Input(i.tMs, 0f, false, i.pct, i.plugged, i.tempC, i.mah))
        if (i.mwOk && i.netMw != lastNet) {
            if (lastChgMs >= 0) sensorPeriodS += (((i.tMs - lastChgMs) / 1000f).coerceIn(0.1f, 5f) - sensorPeriodS) * 0.05f
            lastChgMs = i.tMs; lastNet = i.netMw
        }
        if (i.mwOk) {
            if (!primed) { emaFast = i.netMw; emaUse = i.netMw; emaSlow = i.netMw; emaFF = i.netMw; emaSettle = i.netMw; burnLag = burn; burnLagS = burn; primed = true }
            else {
                emaFast += (i.netMw - emaFast) * dt / (0.8f + dt)
                emaFF += (i.netMw - emaFF) * dt / (TAU_FF[a] + dt)
                emaSettle += (i.netMw - emaSettle) * dt / (6f + dt)
                // usage detector: 2.5 s at the field sensor rate (~1.8 Hz), longer for slower sensors; scaled by aggressiveness
                val tauUse = (USE_TAU_K[a] * 2.5f * sensorPeriodS / 0.55f).coerceIn(1.5f, 7f)
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

        // the target is an exact mAh: charge up to it (brake idle), then hold it. No % rounding, no capture margin.
        if (i.mahOk) { derive(i.pct, i.mah); targetMah = goal() }
        val tOk = i.mahOk && !targetMah.isNaN()
        if (tOk) {
            if (!reachedTarget && i.mah >= targetMah) reachedTarget = true
            else if (reachedTarget && i.mah < targetMah - REARM_MAH) reachedTarget = false   // big dip: charge back up to it
        }

        effMode = if (cfg.mode == Mode.AUTO) (if (i.mwOk) Mode.MW else Mode.PCT) else cfg.mode
        val h = hold()
        val below = if (tOk && effMode == Mode.MW) !reachedTarget else i.pct < h
        when {
            paused -> { burn = 0f; st = St.PAUSED }
            fastUntil >= 0 -> { burn = 0f; st = St.FAST }
            hot -> { burn = 0f; st = St.HOT }
            below -> { burn = 0f; draining = false; st = St.CHARGING_UP }
            effMode == Mode.MW -> mw(i, dt, h, a)
            else -> pct(i, dt, h, a)
        }
        burn = burn.coerceIn(0f, maxOut)
        // what the reading will show of the brake commanded so far (actuator + sensor + filter lag)
        val tauP = TAU_FF[a] + PLANT_LAG_S + sensorPeriodS * 0.5f
        burnLag += (burn - burnLag) * dt / (tauP + dt)
        val tauS = TAU_SLOW[a] + PLANT_LAG_S + sensorPeriodS * 0.5f
        burnLagS += (burn - burnLagS) * dt / (tauS + dt)
        settle(i.tMs)
        return burn
    }

    /**
     * Time to settle (shown in Details): a step starts when the 6 s average net leaves ±STEP_MW of the target. It is
     * settled once that average has stayed within ±SETTLE_MW for SETTLE_HOLD_S; the time is when it entered the band.
     * Excursions without a real brake change (noise) are dropped. Approximate: the reading is noisy.
     */
    private val bHist = ArrayDeque<Pair<Long, Float>>()
    private var settleB0 = 0f
    private var inBand = -1L
    private fun settle(t: Long) {
        val active = when (st) { St.HOLDING, St.BRAKING, St.IN_USE, St.IDLE -> true; else -> false }
        if (!active || !primed || draining) { settling = false; bHist.clear(); return }
        val e = abs(emaSettle - tgtMw)
        bHist.addLast(t to burn); while (bHist.isNotEmpty() && t - bHist.first().first > 10_000L) bHist.removeFirst()
        if (!settling) { if (e > STEP_MW) { settling = true; settleT0 = t; settleB0 = bHist.first().second; inBand = -1 }; return }
        if (e < SETTLE_MW) { if (inBand < 0) inBand = t } else inBand = -1
        if (inBand >= 0 && t - inBand >= (SETTLE_HOLD_S * 1000).toLong()) {
            settling = false
            if (abs(burn - settleB0) * cfg.burnMwFull < MIN_STEP_BRAKE_MW) return      // no real brake change: noise
            lastSettleS = (inBand - settleT0) / 1000f
            avgSettleS = if (avgSettleS.isNaN()) lastSettleS else avgSettleS + (lastSettleS - avgSettleS) * 0.3f
        }
        if (settling && t - settleT0 > 600_000L) settling = false   // never settled in 10 min: drop it
    }

    private fun mw(i: Input, dt: Float, h: Int, a: Int) {
        if (!i.mwOk) { burn -= dt / 5f; st = St.NO_READING; return }
        val drainMw = Drain.mw(cfg.drainSpeed)
        tgtMw = if (i.mahOk && !targetMah.isNaN()) {
            val err = targetMah - i.mah                                        // < 0: above the target
            if (!draining && err < -DRAIN_START_MAH) draining = true
            else if (draining && err > -DRAIN_END_MAH) draining = false
            val floor = if (draining) maxOf(drainMw, -1e6f) else -250f
            (err * MAH_GAIN).coerceIn(floor, 150f)                             // outer mAh loop
        } else {
            val wantDrain = i.pct > h                                          // fallback: % guard, 10 min dwell
            if (wantDrain != draining && i.tMs - drainSince >= DWELL_MS) { draining = wantDrain; drainSince = i.tMs }
            if (draining) maxOf(drainMw, -1e6f) else cfg.targetMw.toFloat()
        }
        if (draining && cfg.drainSpeed == Drain.FAST) {
            // Fast: max brake (limited by Max brake and the temperature cutoff), back off only for real phone use
            burn += maxOut / FAST_RAMP_S * dt
            st = St.DRAINING
            return
        }
        val g = cfg.burnMwFull
        val kUp = 1f / (g * TAU_UP[a])
        val kDn = 1f / (g * TAU_DN[a])
        val kUse = 1f / (g * 2f)
        val eS = emaSlow - (burn - burnLagS) * g - tgtMw      // predicted: minus brake already on its way
        val eU = emaUse - tgtMw
        // feed-forward: jump towards the brake the estimated surplus needs (predicted through the lag), then fine-tune
        ePred = emaFF - (burn - burnLag) * g - tgtMw
        if (a == AGGR_MAX - 1) {
            // Direct: a step confirmed for FF_CONFIRM_S -> jump straight to the estimated brake, then the loop fine-tunes
            ffHold = if (abs(ePred) > FF_MIN[a]) ffHold + dt else 0f
            ffCool -= dt
            if (ffHold >= FF_CONFIRM_S && ffCool <= 0f) { burn += DIRECT_JUMP * ePred / g; ffHold = 0f; ffCool = 3f * (TAU_FF[a] + PLANT_LAG_S + sensorPeriodS) }
        } else if (abs(ePred) > FF_MIN[a]) burn += FF_RATE[a] * (ePred - kotlin.math.sign(ePred) * FF_MIN[a] * 0.5f) / g * dt
        short = eS < -BAND || eU < -USE_MW
        useS = if (eU < -USE_MW) useS + dt else 0f
        when {
            useS >= USE_HOLD_S[a] -> { burn -= kUse * (-eU) * dt; st = St.IN_USE }
            eS < -BAND -> { burn -= kDn * (-eS - BAND) * dt; st = if (burn > 0.005f) St.HOLDING else St.IDLE }
            eS > BAND -> { burn += kUp * (eS - BAND) * dt; st = St.BRAKING }
            else -> st = if (burn > 0.005f) St.HOLDING else St.IDLE
        }
        if (st == St.BRAKING && eS < 250f) st = St.HOLDING
        if (draining && st != St.IN_USE) st = St.DRAINING
    }

    private fun pct(i: Input, dt: Float, h: Int, a: Int) {
        tgtMw = 0f
        val up = maxOut / PCT_UP_S[a]
        if (i.pct > h) { burn += up * (if (cfg.drainSpeed == Drain.FAST) 4f else 1f) * dt; st = St.DRAINING; draining = true } else { burn -= up * 1.5f * dt; st = St.HOLDING; draining = false }
    }

    companion object {
        const val AGGR_MAX = 6
        fun aggrName(a: Int) = when (a) { 1 -> "Gentle"; 2 -> "Calm"; 3 -> "Normal"; 4 -> "Quick"; 5 -> "Fast"; else -> "Direct" }
        const val STEP_MW = 450f
        const val SETTLE_MW = 180f
        const val SETTLE_HOLD_S = 10f
        const val MIN_STEP_BRAKE_MW = 200f
        const val PLANT_LAG_S = 0.6f
        const val FF_CONFIRM_S = 1.2f      // Direct: the step must persist this long (noise rejection)
        const val DIRECT_JUMP = 0.85f      // Direct: fraction of the estimated brake change applied at once      // CPU/render power reaching the battery reading + the sensor's own averaging
        const val BAND = 50f
        const val USE_MW = 400f
        const val MAH_GAIN = 60f          // mW of target per mAh of error
        const val DWELL_MS = 600_000L
        const val DRAIN_START_MAH = 8f     // above the target by more than this -> drain at the chosen speed
        const val DRAIN_END_MAH = 1f
        const val FAST_RAMP_S = 20f
        const val REARM_MAH = 20f          // more than this below the target after reaching it -> "charging up" again
        // per aggressiveness 1..6: filtering, delays and ramps scale together
        val TAU_SLOW = floatArrayOf(6f, 5f, 4f, 3f, 2.5f, 2.5f)
        val TAU_UP = floatArrayOf(27f, 20f, 13f, 9f, 7f, 6f)   // v0.1 / 1.5
        val TAU_DN = floatArrayOf(10f, 9f, 8f, 6f, 5f, 4f)
        val USE_TAU_K = floatArrayOf(1.3f, 1.15f, 1f, 0.9f, 0.85f, 0.85f)
        val USE_HOLD_S = floatArrayOf(3f, 2.5f, 2f, 1.75f, 1.6f, 1.6f)
        val TAU_FF = floatArrayOf(4f, 3.2f, 2.5f, 2f, 1.8f, 3f)
        val FF_RATE = floatArrayOf(0.06f, 0.1f, 0.18f, 0.3f, 0.5f, 1.6f)   // fraction of the predicted error per second
        val FF_MIN = floatArrayOf(550f, 500f, 460f, 430f, 410f, 450f)       // only for real steps, not noise
        val PCT_UP_S = floatArrayOf(400f, 300f, 200f, 140f, 100f, 80f)
    }
}

/**
 * Splits the controller's brake output between the app's own drawing ("visual": frame rate, glows, effects; only
 * while the app is on screen) and CPU burn threads. The visual stage is the first responder in BOTH directions:
 * more brake raises the visuals first (up to the display max), less brake drops the visuals first (down to the
 * minimum ~3 fps, no glows); the CPU only covers what the visuals can't. The total always equals the controller's
 * output, so handoffs are smooth: app hidden -> the CPU takes the visual share at once; while not short, CPU share
 * moves slowly back to the visuals. `floor` (Always smooth) keeps the visuals at least that level (a fixed cost).
 */
class BrakeSplit(var visMwEst: Float = VIS_MW_EST) {
    var avail = 0f; private set         // 0..1 how much of the visual stage is usable now (ramps in)
    var vis = 0f; private set           // visual share (burn units, above the floor)
    var cpu = 0f; private set           // CPU share (burn units = duty of the burn threads)
    var visLevel = 0f; private set      // 0..1 visual intensity to apply
    var floor = 0f                       // 0..1 minimum visual level (Always smooth)
    fun fullCap(burnMwFull: Float) = visMwEst / burnMwFull.coerceAtLeast(1f)
    /** @return the controllable visual capacity (burn units) to give the controller this tick. */
    fun update(dt: Float, renderOk: Boolean, burnMwFull: Float): Float {
        avail = if (!renderOk) 0f else (avail + dt / RAMP_S).coerceAtMost(1f)
        return fullCap(burnMwFull) * avail * (1f - floor.coerceIn(0f, 1f))
    }
    fun reset() { vis = 0f; cpu = 0f; visLevel = floor; avail = 0f }
    /** @param short the net is below the target: don't move share back to the visuals now. */
    fun split(total: Float, cap: Float, burnMwFull: Float, dt: Float = 0.25f, short: Boolean = false) {
        val t = total.coerceAtLeast(0f)
        val d = t - (vis + cpu)
        var v = vis; var c = cpu
        if (d >= 0f) { val add = minOf(d, (cap - v).coerceAtLeast(0f)); v += add; c += d - add }
        else { val sub = minOf(-d, v); v -= sub; c -= -d - sub }
        if (v > cap) { c += v - cap; v = cap }                         // visual stage shrank / went away: CPU takes it now
        if (!short && c > 0f && v < cap) { val m = minOf(c, cap - v, fullCap(burnMwFull) * dt / REBAL_S); v += m; c -= m }
        c = c.coerceAtLeast(0f); v = v.coerceAtLeast(0f)
        val err = t - (v + c); if (err != 0f) { if (c + err >= 0f) c += err else v = (v + err).coerceAtLeast(0f) }   // exact total
        vis = v; cpu = c
        val fl = floor.coerceIn(0f, 1f)
        visLevel = if (avail > 0f) (fl + (1f - fl) * v / (fullCap(burnMwFull) * (1f - fl)).coerceAtLeast(1e-6f)).coerceIn(0f, 1f) else fl
    }
    companion object { const val VIS_MW_EST = 500f; const val RAMP_S = 4f; const val REBAL_S = 30f }
}
