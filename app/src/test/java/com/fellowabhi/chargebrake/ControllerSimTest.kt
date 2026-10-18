package com.fellowabhi.chargebrake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Fake phone tuned to the 18 Oct field log: ~1.8 Hz sensor, ±700 mW sample-to-sample noise,
 * 5 W charger with a plug-in surge that thermally settles to a ~+0.7 W surplus.
 */
class FakePhone(
    seed: Int,
    val surgeMw: Float = 5000f,     // charger output for the first surgeS
    val surgeS: Float = 150f,
    val steadyMw: Float = 1700f,    // charger output after the surge (thermal limit)
    val baseMw: Float = 1000f,      // phone draw at idle (screen off, Spotify)
    val burnGain: Float = 1200f,    // real mW at burn = 1.0 (unknown to the controller)
    val sensorPeriodS: Float = 0.55f,
    val noiseSd: Float = 500f,      // per sample -> ~700 mW sample-to-sample
    val spikes: Boolean = false,
    startPct: Float = 60.5f,
    val capMah: Float = 4860f,
    val gauge: Boolean = true,
) {
    private val r = Random(seed)
    var t = 0f
    var mahTrue = capMah * startPct / 100f
    var burnActual = 0f
    var usageMw = 0f
    var trueNet = 0f
    var plugged = true
    private var spikeLeft = 0f
    private var sinceUpdate = 0f
    private val win = ArrayDeque<Float>()
    var sensorMw = 0f
    var gaugeUah = 0
    private var sinceGauge = 0f
    val pct get() = floor(mahTrue / capMah * 100f).toInt()
    private fun gauss() = (r.nextFloat() + r.nextFloat() + r.nextFloat() + r.nextFloat() - 2f) * 1.73f

    fun step(dt: Float, burnCmd: Float) {
        t += dt
        burnActual += (burnCmd - burnActual) * dt / 0.5f
        if (spikes) {
            if (spikeLeft <= 0f) {
                usageMw = 0f
                if (r.nextFloat() < dt / 180f) { spikeLeft = 5f + r.nextFloat() * 50f; usageMw = 500f + r.nextFloat() * 1500f }
            } else spikeLeft -= dt
        }
        val charger = if (!plugged) 0f else if (t < surgeS) surgeMw else steadyMw
        trueNet = charger - baseMw - burnActual * burnGain - usageMw
        mahTrue += trueNet / 3.95f * dt / 3600f
        win.addLast(trueNet); while (win.size > 10) win.removeFirst()
        sinceUpdate += dt
        if (sinceUpdate >= sensorPeriodS) {
            sinceUpdate = 0f
            sensorMw = Math.round((win.average().toFloat() + gauss() * noiseSd) / 3.95f) * 3.95f
        }
        sinceGauge += dt
        if (sinceGauge >= 5f) { sinceGauge = 0f; gaugeUah = if (gauge) (Math.round(mahTrue) * 1000) else Int.MIN_VALUE }
    }
}

class ControllerSimTest {
    class Res(val meanNet: Float, val sdNet: Float, val meanBurn: Float, val jitter: Float, val pctMin: Int, val pctMax: Int,
              val mahErrMax: Float, val mahErrEnd: Float, val useTrig: Int)

    private fun run(p: FakePhone, cfg: Cfg, hours: Float, statsAfterS: Float = 2700f, mwOk: Boolean = true,
                    onTick: (Float, Controller, MahTracker) -> Unit = { _, _, _ -> }): Res {
        val c = Controller(cfg); val mt = MahTracker()
        val dt = 0.05f; var nextCtl = 0f; var cmd = 0f; var armed = false
        var n = 0; var sN = 0.0; var sN2 = 0.0; var sB = 0.0
        var pMin = 999; var pMax = -1; var errMax = 0f; var errEnd = 0f; var useTrig = 0; var wasUse = false
        val bl = ArrayList<Float>(); val zf = ZeroFilter()
        while (p.t < hours * 3600f) {
            p.step(dt, cmd)
            if (p.t >= nextCtl) {
                nextCtl += 0.25f
                val tMs = (p.t * 1000).toLong()
                val mA = zf.filter(Math.round(p.sensorMw / 3.95f * 1000)) / 1000f
                if (mt.update(tMs, p.gaugeUah, mA, mwOk, p.pct, cfg.capacityMah) && armed) c.retarget(p.pct, mt.mah)
                if (!armed) { c.arm(p.pct, mt.mah); armed = true }
                cmd = c.step(Input(tMs, if (mwOk) mA * 3.95f else 0f, mwOk, p.pct, p.plugged, 35f, mt.mah))
                onTick(p.t, c, mt)
                val use = c.st == St.IN_USE
                if (use && !wasUse) useTrig++
                wasUse = use
                if (p.t > statsAfterS) {
                    n++; sN += p.trueNet; sN2 += p.trueNet * p.trueNet; sB += cmd; bl += cmd
                    pMin = minOf(pMin, p.pct); pMax = maxOf(pMax, p.pct)
                    if (!c.targetMah.isNaN()) { val e = p.mahTrue - c.targetMah; errEnd = e; errMax = maxOf(errMax, abs(e)) }
                }
            }
        }
        val mN = sN / n
        var js = 0.0; var jn = 0
        for (w in 0 until bl.size / 240) { val sub = bl.subList(w * 240, w * 240 + 240); val m = sub.average()
            js += sqrt(sub.sumOf { (it - m) * (it - m) } / 240); jn++ }
        return Res(mN.toFloat(), sqrt(sN2 / n - mN * mN).toFloat(), (sB / n).toFloat(), (js / maxOf(1, jn)).toFloat(),
            pMin, pMax, errMax, errEnd, useTrig)
    }

    private fun line(name: String, r: Res) = String.format(
        "%-36s net %+5.0f mW (sd %4.0f) | burn %4.1f%% jitter %4.1f%% | level %d-%d%% | mAh err max %5.1f end %+5.1f | in-use trig %d",
        name, r.meanNet, r.sdNet, r.meanBurn * 100, r.jitter * 100, r.pctMin, r.pctMax, r.mahErrMax, r.mahErrEnd, r.useTrig)

    @Test fun holdWithFieldNoise() {
        println("=== Charge Brake v0.2 simulation: field-log noise (±700 mW, 1.8 Hz), 5 W charger with surge; stats after 45 min (surge recovered) ===")
        for ((name, p) in listOf(
            "field: surplus +700 mW, gain 1.2 W" to FakePhone(1),
            "weak burn gain 0.8 W" to FakePhone(2, burnGain = 800f, steadyMw = 1500f),
            "strong burn gain 3 W" to FakePhone(3, burnGain = 3000f),
            "surplus +300 mW" to FakePhone(4, steadyMw = 1300f),
            "lag: 2 s sensor period" to FakePhone(5, sensorPeriodS = 2f),
        )) {
            val r = run(p, Cfg(), 3f)
            println(line(name, r))
            assertTrue(name, abs(r.meanNet) < 100f)
            assertTrue(name, r.pctMax - r.pctMin <= 1)
            assertTrue(name, r.mahErrMax < 12f)
            assertTrue(name, r.useTrig <= 24)   // noise alone must rarely look like phone use (<= 8/h)
        }
    }

    @Test fun usageDips() {
        val p = FakePhone(7, spikes = true)
        var start = -1f; val delays = mutableListOf<Float>(); var pre = 0f
        val r = run(p, Cfg(), 3f) { t, c, _ ->
            if (p.usageMw > 0f && start < 0) { start = t; pre = c.burn }
            if (p.usageMw == 0f) start = -1f
            if (start >= 0 && pre > 0.1f && c.burn < pre * 0.3f) { delays += t - start; start = -2e9f }
        }
        val med = delays.sorted().getOrElse(delays.size / 2) { -1f }
        println(line("random use 0.5-2 W, 5-55 s", r) + String.format(" | back-off to 30%% in median %.1f s (n=%d)", med, delays.size))
        assertTrue(delays.size >= 10 && med < 6f)
        assertTrue(r.pctMax - r.pctMin <= 1)
    }

    @Test fun fastCharger() {
        val p = FakePhone(8, surgeMw = 15000f, surgeS = 1e9f)
        val offs = mutableListOf<Float>(); var was = false; var burnWhileOff = 0f
        run(p, Cfg(), 0.15f, statsAfterS = 0f) { t, c, _ ->
            val f = c.st == St.FAST; if (f && !was) offs += t; was = f; if (f) burnWhileOff = maxOf(burnWhileOff, c.burn)
        }
        println(String.format("%-36s off at %s s, burn while off %.0f%%", "15 W fast charger", offs.joinToString { "%.0f".format(it) }, burnWhileOff * 100))
        assertTrue(offs.isNotEmpty() && offs[0] <= 13f && burnWhileOff == 0f)
        assertTrue(offs.size >= 2 && offs[1] - offs[0] in 120f..140f)   // retried after 2 min, still fast -> off again
        // the field charger (5 W surge) must not trip it
        var tripped = false
        run(FakePhone(9), Cfg(), 0.5f, statsAfterS = 0f) { _, c, _ -> if (c.st == St.FAST) tripped = true }
        println(String.format("%-36s tripped: %b", "5 W surge (field charger)", tripped))
        assertTrue(!tripped)
        // Ignore button
        var ignBurn = 0f
        run(FakePhone(10, surgeMw = 15000f, surgeS = 1e9f), Cfg(), 0.1f, statsAfterS = 0f) { t, c, _ -> if (t > 30f) { c.ignoreFast = true; ignBurn = maxOf(ignBurn, c.burn) } }
        println(String.format("%-36s burn after Ignore %.0f%%", "15 W + Ignore", ignBurn * 100))
        assertTrue(ignBurn > 0.5f)
    }

    @Test fun targetBelowCharges() {
        val cfg = Cfg().apply { holdCurrent = false; targetPct = 60 }
        val p = FakePhone(11, startPct = 57.5f, surgeS = 0f)
        var burnBelow = 0f; var reached = -1f
        val r = run(p, cfg, 4f, statsAfterS = 3300f) { t, c, _ -> if (p.pct < 60) burnBelow = maxOf(burnBelow, c.burn) else if (reached < 0) reached = t }
        println(line("start 57.5%, target 60%", r) + String.format(" | burn below target %.0f%%, reached after %.0f min", burnBelow * 100, reached / 60))
        assertTrue(burnBelow == 0f && r.pctMin >= 60 && r.pctMax <= 61)
    }

    @Test fun noGaugeCoulombCounting() {
        val r = run(FakePhone(12, gauge = false), Cfg(), 4f)
        println(line("no fuel gauge -> coulomb counting", r))
        assertTrue(r.pctMax - r.pctMin <= 1 && abs(r.meanNet) < 100f)
    }

    @Test fun percentFallback() {
        val r = run(FakePhone(13, gauge = false), Cfg(), 6f, mwOk = false)
        println(line("no mW reading -> % fallback (6 h)", r))
        assertTrue(r.pctMax - r.pctMin <= 1)
    }

    @Test fun zeroFilter() {
        val z = ZeroFilter()
        val out = listOf(500, 0, 520, 0, 0, 0, 480).map { z.filter(it) }
        println("zero filter: [500, 0, 520, 0, 0, 0, 480] -> $out")
        assertEquals(listOf(500, 500, 520, 520, 0, 0, 480), out)
    }
}
