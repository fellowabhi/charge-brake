package com.fellowabhi.chargebrake

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/** Fake phone: charger surplus, burn power per duty unit (unknown to the controller), usage spikes, noisy laggy sensor. */
class FakePhone(
    seed: Int,
    var chargerMw: Float = 1200f,   // what the charger delivers
    val baseMw: Float = 800f,       // phone idle draw (Spotify, screen off) -> +400 mW surplus
    val burnGain: Float = 1200f,    // real mW at burn = 1.0
    val sensorAvgS: Float = 1f,     // sensor averaging window
    val sensorPeriodS: Float = 1f,  // sensor update period
    val noiseMw: Float = 40f,
    val spikes: Boolean = false,
    startPct: Float = 60.5f,
) {
    private val r = Random(seed)
    val capMwh = 17_000f
    var energyMwh = capMwh * startPct / 100f
    var burnActual = 0f
    var usageMw = 0f
    var trueNet = 0f
    private var spikeLeft = 0f
    private val win = ArrayDeque<Float>()
    private var sinceUpdate = 0f
    var sensorMw = 0f
    val pct get() = floor(energyMwh / capMwh * 100f).toInt()

    fun step(dt: Float, burnCmd: Float) {
        burnActual += (burnCmd - burnActual) * dt / 0.5f
        if (spikes) {
            if (spikeLeft <= 0f) {
                usageMw = 0f
                if (r.nextFloat() < dt / 180f) { spikeLeft = 10f + r.nextFloat() * 50f; usageMw = 300f + r.nextFloat() * 1200f }
            } else spikeLeft -= dt
        }
        trueNet = chargerMw - baseMw - burnActual * burnGain - usageMw
        energyMwh += trueNet * dt / 3600f
        win.addLast(trueNet)
        while (win.size > (sensorAvgS / dt).toInt().coerceAtLeast(1)) win.removeFirst()
        sinceUpdate += dt
        if (sinceUpdate >= sensorPeriodS) {
            sinceUpdate = 0f
            val g = (r.nextFloat() + r.nextFloat() + r.nextFloat() - 1.5f) * 2f * noiseMw
            sensorMw = Math.round((win.average().toFloat() + g) / 3.85f) * 3.85f // 1 mA quantisation
        }
    }
}

class ControllerSimTest {
    data class Res(val meanNet: Float, val sdNet: Float, val meanBurn: Float, val sdBurn: Float, val pctMin: Int, val pctMax: Int, val settleS: Float, val jitter: Float)

    private fun run(p: FakePhone, cfg: Cfg, hours: Float, onTick: (Float, Controller) -> Unit = { _, _ -> }): Res {
        val c = Controller(cfg); c.arm(p.pct)
        val dt = 0.05f; var t = 0f; var nextCtl = 0f; var cmd = 0f
        var n = 0; var sN = 0.0; var sN2 = 0.0; var sB = 0.0; var sB2 = 0.0
        val bl = ArrayList<Float>(); var pMin = 999; var pMax = -1; var settle = -1f; var avg = 0f
        while (t < hours * 3600f) {
            p.step(dt, cmd); t += dt
            avg += (p.trueNet - avg) * dt / 30f
            if (t >= nextCtl) {
                nextCtl += 0.25f
                cmd = c.step(Input((t * 1000).toLong(), p.sensorMw, true, p.pct, true, 30f))
                onTick(t, c)
                if (settle < 0 && t > 30 && abs(avg - cfg.targetMw) < 75f) settle = t
                if (t > 600f) { // stats after 10 min
                    n++; sN += p.trueNet; sN2 += p.trueNet * p.trueNet; sB += cmd; sB2 += cmd * cmd; bl += cmd
                    pMin = minOf(pMin, p.pct); pMax = maxOf(pMax, p.pct)
                }
            }
        }
        val mN = sN / n; val mB = sB / n
        // short-term jitter: mean stdev of burn inside 60 s windows (240 ticks)
        var js = 0.0; var jn = 0
        for (w in 0 until bl.size / 240) { val sub = bl.subList(w * 240, w * 240 + 240); val m = sub.average()
            js += sqrt(sub.sumOf { (it - m) * (it - m) } / 240); jn++ }
        return Res(mN.toFloat(), sqrt(sN2 / n - mN * mN).toFloat(), mB.toFloat(), sqrt(maxOf(0.0, sB2 / n - mB * mB)).toFloat(), pMin, pMax, settle, (js / jn).toFloat())
    }

    private fun fmt(name: String, r: Res) = String.format(
        "%-34s net mean %+6.0f mW  sd %5.0f  burn %4.1f%% (sd %4.1f)  level %d-%d%%  settle %4.0fs  jitter/60s %.1f%%",
        name, r.meanNet, r.sdNet, r.meanBurn * 100, r.sdBurn * 100, r.pctMin, r.pctMax, r.settleS, r.jitter * 100)

    @Test fun steadyHold() {
        println("=== Charge Brake controller simulation (true net power, after first 10 min) ===")
        for ((name, p) in listOf(
            "base: +400mW, gain 1.2W, 1s sensor" to FakePhone(1),
            "weak burn gain 0.6W" to FakePhone(2, burnGain = 600f),
            "strong burn gain 3W" to FakePhone(3, burnGain = 3000f),
            "laggy sensor (5s avg, 2s update)" to FakePhone(4, sensorAvgS = 5f, sensorPeriodS = 2f, noiseMw = 80f),
            "bigger surplus +800mW" to FakePhone(5, chargerMw = 1600f),
        )) {
            var reversals = 0; var lastSign = 0; var lastB = 0f
            val r = run(p, Cfg(), 3f) { t, c ->
                if (t > 600f) { val d = c.burn - lastB; val s = if (d > 0.002f) 1 else if (d < -0.002f) -1 else 0
                    if (s != 0 && lastSign != 0 && s != lastSign) reversals++; if (s != 0) lastSign = s }
                lastB = c.burn
            }
            println(fmt(name, r) + "  big burn reversals/h ${reversals / 3}")
            assertTrue(name, abs(r.meanNet - 0f) < 150f)
            assertTrue(name, r.pctMax - r.pctMin <= 1)
            assertTrue(name, r.jitter < 0.03f)
        }
    }

    @Test fun usageSpikes() {
        val p = FakePhone(7, spikes = true)
        var spikeT = -1f; var pre = 0f; val backoff = mutableListOf<Float>(); var wasUsage = false
        val r = run(p, Cfg(), 3f) { t, c ->
            val u = p.usageMw > 0f
            if (u && !wasUsage) { spikeT = t; pre = c.burn }
            if (u && spikeT >= 0 && pre > 0.05f && c.burn < pre * 0.2f) { backoff += t - spikeT; spikeT = -1f }
            wasUsage = u
        }
        println(fmt("random usage spikes 0.3-1.5W", r) + String.format("  backoff to <20%% in median %.1fs (n=%d)", backoff.sorted().getOrElse(backoff.size / 2) { -1f }, backoff.size))
        assertTrue(backoff.isNotEmpty() && backoff.sorted()[backoff.size / 2] < 5f)
        assertTrue(r.pctMax - r.pctMin <= 1)
    }

    @Test fun saturation() {
        val r = run(FakePhone(11, chargerMw = 1800f), Cfg(), 3f)
        println(fmt("surplus +1000mW > max burn (info)", r))
    }

    @Test fun fastCharger() {
        val p = FakePhone(8, chargerMw = 10_000f)
        var latchedAt = -1f; var maxBurnAfter = 0f
        run(p, Cfg(), 0.1f) { t, c -> if (c.fastLatched && latchedAt < 0) latchedAt = t; if (latchedAt > 0) maxBurnAfter = maxOf(maxBurnAfter, c.burn) }
        println(String.format("%-34s latched off after %.1fs, burn after latch %.0f%%", "fast charger 10W", latchedAt, maxBurnAfter * 100))
        assertTrue(latchedAt in 0f..8f && maxBurnAfter == 0f)
    }

    @Test fun belowTargetCharges() {
        val cfg = Cfg().apply { holdCurrent = false; targetPct = 60 }
        val p = FakePhone(9, startPct = 59.6f)
        var burnBefore = 0f; var reachedAt = -1f
        run(p, cfg, 3f) { t, c -> if (p.pct < 60) burnBefore = maxOf(burnBefore, c.burn) else if (reachedAt < 0) reachedAt = t }
        println(String.format("%-34s max burn below target %.0f%%, reached 60%% after %.0f min, then held at %d%%", "start 59.6%, target 60%", burnBefore * 100, reachedAt / 60, p.pct))
        assertTrue(burnBefore == 0f && p.pct in 60..61)
    }

    @Test fun percentMode() {
        val cfg = Cfg().apply { mode = Mode.PCT }
        val r = run(FakePhone(10, spikes = true), cfg, 6f)
        println(fmt("% mode (backup), 6h, spikes", r))
        assertTrue(r.pctMax - r.pctMin <= 1)
    }
}
