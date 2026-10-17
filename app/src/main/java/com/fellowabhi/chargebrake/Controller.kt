package com.fellowabhi.chargebrake

object Mode {
    const val AUTO = 0
    const val MW = 1
    const val PCT = 2
    fun name(m: Int) = when (m) { MW -> "mW"; PCT -> "%"; else -> "Auto" }
}

/** User settings. Plain fields so the controller stays Android-free (unit testable). */
class Cfg {
    @Volatile var mode = Mode.AUTO
    @Volatile var holdCurrent = true
    @Volatile var targetPct = 60
    @Volatile var targetMw = 100          // net power to hold while at the hold level
    @Volatile var drainMw = -150          // net power used when above the hold level
    @Volatile var fastCutoffMw = 2000     // gross estimate above this = fast charger
    @Volatile var tempCutoffC = 38f
    @Volatile var aggr = 3                // 1..5
    @Volatile var maxBurn = 0.8f          // 0..1, cap on burn duty
    @Volatile var burnMwFull = 1500f      // rough mW at burn = 1.0 (only used for gain + gross estimate)
}

class Input(
    val tMs: Long,
    val netMw: Float,
    val mwOk: Boolean,
    val pct: Int,
    val plugged: Boolean,
    val tempC: Float,
)

/**
 * Holds the battery level by adding CPU load ("burn", 0..1) so net battery power stays near a small target.
 * Ramps burn up slowly, backs off fast (so normal phone use never fights the controller).
 */
class Controller(val cfg: Cfg) {
    var burn = 0f; private set
    var emaFast = 0f; private set
    var emaSlow = 0f; private set
    var tgtMw = 0f; private set
    var holdPct = -1
    var state = "Off"; private set
    var effMode = Mode.MW; private set
    var fastLatched = false; private set
    var hot = false; private set
    var draining = false; private set

    private var lastT = -1L
    private var overT = 0f
    private var primed = false
    private var drainSince = 0L

    fun arm(pct: Int, keepHold: Boolean = false) {
        if (!keepHold || holdPct < 0) holdPct = pct
        burn = 0f; fastLatched = false; overT = 0f; primed = false; lastT = -1; draining = false
        state = "Starting"
    }

    fun disarm() { burn = 0f; primed = false; lastT = -1; state = "Off" }

    fun hold(): Int = if (cfg.holdCurrent) holdPct else cfg.targetPct

    fun step(i: Input): Float {
        val dt = if (lastT < 0) 0.25f else ((i.tMs - lastT) / 1000f).coerceIn(0f, 2f)
        lastT = i.tMs
        val a = cfg.aggr.coerceIn(1, 5) - 1

        if (!i.plugged) {
            burn = 0f; fastLatched = false; overT = 0f; primed = false; draining = false
            state = "Unplugged"
            return burn
        }
        if (i.mwOk) {
            if (!primed) { emaFast = i.netMw; emaSlow = i.netMw; primed = true }
            else {
                val tauS = TAU_SLOW[a]
                emaFast += (i.netMw - emaFast) * dt / (0.8f + dt)
                emaSlow += (i.netMw - emaSlow) * dt / (tauS + dt)
            }
            // Fast-charger detection: net + our own burn ~= what the charger pushes in. Latched until unplug.
            val gross = emaFast + burn * cfg.burnMwFull
            overT = if (gross > cfg.fastCutoffMw) overT + dt else 0f
            if (overT >= 5f) fastLatched = true
        }
        if (i.tempC >= cfg.tempCutoffC) hot = true else if (i.tempC < cfg.tempCutoffC - 1f) hot = false

        effMode = if (cfg.mode == Mode.AUTO) (if (i.mwOk) Mode.MW else Mode.PCT) else cfg.mode
        val h = hold()
        when {
            fastLatched -> { burn = 0f; state = "Fast charger: off" }
            hot -> { burn = 0f; state = "Too hot: paused" }
            i.pct < h -> { burn = 0f; draining = false; state = "Charging to $h%" }
            effMode == Mode.MW -> mw(i, dt, h, a)
            else -> pct(i, dt, h, a)
        }
        burn = burn.coerceIn(0f, cfg.maxBurn)
        return burn
    }

    private fun mw(i: Input, dt: Float, h: Int, a: Int) {
        if (!i.mwOk) { burn -= dt / 5f; state = "No mW reading"; return }
        // Level guard with a 10 min dwell: above hold -> slight drain, otherwise slight charge.
        val wantDrain = i.pct > h
        if (wantDrain != draining && i.tMs - drainSince >= DWELL_MS) { draining = wantDrain; drainSince = i.tMs }
        tgtMw = if (draining) cfg.drainMw.toFloat() else cfg.targetMw.toFloat()

        val g = cfg.burnMwFull
        val kUp = 1f / (g * TAU_UP[a])
        val kDn = 1f / (g * 3f)
        val eS = emaSlow - tgtMw
        val eF = emaFast - tgtMw
        when {
            eF < -3 * BAND -> { burn -= kDn * 1.5f * (-eF) * dt; state = "Backing off (in use)" }
            eS < -BAND -> { burn -= kDn * (-eS - BAND) * dt; state = "Backing off" }
            eS > BAND && eF > 0f -> { burn += kUp * (eS - BAND) * dt; state = "Braking" }
            else -> state = if (burn > 0.005f) "Holding" else "Idle (no surplus)"
        }
        if (draining && state == "Holding") state = "Holding (draining to $h%)"
    }

    private fun pct(i: Input, dt: Float, h: Int, a: Int) {
        tgtMw = 0f
        val up = cfg.maxBurn / PCT_UP_S[a]
        if (i.pct > h) { burn += up * dt; state = "Burning to $h%" }
        else { burn -= up * 1.5f * dt; state = "Holding $h% (% mode)" }
    }

    companion object {
        const val BAND = 50f
        const val DWELL_MS = 600_000L
        val TAU_SLOW = floatArrayOf(6f, 5f, 4f, 3f, 2f)
        val TAU_UP = floatArrayOf(40f, 30f, 20f, 14f, 10f)
        val PCT_UP_S = floatArrayOf(400f, 300f, 200f, 140f, 100f)
    }
}
