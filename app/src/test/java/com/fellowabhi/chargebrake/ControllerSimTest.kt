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
    val heatC: Float = 0f,          // °C rise at burn = 1.0 (thermal lag 2 min); 0 = constant 35 °C
) {
    var temp = 35f
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

    var visActual = 0f              // real render-stage power (mW)
    fun step(dt: Float, burnCmd: Float, visMw: Float = 0f) {
        t += dt
        burnActual += (burnCmd - burnActual) * dt / 0.5f
        visActual += (visMw - visActual) * dt / 0.2f
        temp += (35f + heatC * burnActual - temp) * dt / 120f
        if (spikes) {
            if (spikeLeft <= 0f) {
                usageMw = 0f
                if (r.nextFloat() < dt / 180f) { spikeLeft = 5f + r.nextFloat() * 50f; usageMw = 500f + r.nextFloat() * 1500f }
            } else spikeLeft -= dt
        }
        val charger = if (!plugged) 0f else if (t < surgeS) surgeMw else steadyMw
        trueNet = charger - baseMw - burnActual * burnGain - visActual - usageMw
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
                    split: BrakeSplit? = null, renderOn: (Float) -> Boolean = { false }, visRealMw: Float = 0f,
                    onTick: (Float, Controller, MahTracker) -> Unit = { _, _, _ -> }): Res {
        var visMw = 0f
        val c = Controller(cfg); val mt = MahTracker()
        val dt = 0.05f; var nextCtl = 0f; var cmd = 0f; var armed = false
        var n = 0; var sN = 0.0; var sN2 = 0.0; var sB = 0.0
        var pMin = 999; var pMax = -1; var errMax = 0f; var errEnd = 0f; var useTrig = 0; var wasUse = false
        val bl = ArrayList<Float>(); val zf = ZeroFilter()
        while (p.t < hours * 3600f) {
            p.step(dt, if (split != null) split.cpu else cmd, visMw)
            if (p.t >= nextCtl) {
                nextCtl += 0.25f
                val tMs = (p.t * 1000).toLong()
                val mA = zf.filter(Math.round(p.sensorMw / 3.95f * 1000)) / 1000f
                if (mt.update(tMs, p.gaugeUah, mA, mwOk, p.pct, cfg.capacityMah) && armed) c.retarget(p.pct, mt.mah)
                if (!armed) { c.arm(p.pct, mt.mah); armed = true }
                val cap = split?.update(0.25f, renderOn(p.t), cfg.burnMwFull) ?: 0f
                cfg.visCap = cap
                cmd = c.step(Input(tMs, if (mwOk) mA * 3.95f else 0f, mwOk, p.pct, p.plugged, p.temp, mt.mah))
                if (split != null) { split.split(cmd, cap, cfg.burnMwFull); visMw = split.visLevel * visRealMw }
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
        println("=== Charge Brake v0.5.1 simulation: field-log noise (±700 mW, 1.8 Hz), 5 W charger with surge; stats after 45 min (surge recovered) ===")
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

    /** Render stage first, CPU for the rest; the app goes on/off screen (and the real render power differs from the estimate). */
    @Test fun renderStageHandoff() {
        println("=== render brake (Live view on screen) first, then CPU; on screen 10 min / hidden 5 min ===")
        for ((name, visReal) in listOf("render 400 mW (= estimate)" to 400f, "render 250 mW (estimate 400)" to 250f, "render 700 mW (estimate 400)" to 700f)) {
            val p = FakePhone(60, surgeS = 0f)
            val sp = BrakeSplit()
            var visOnS = 0f; var visSum = 0.0; var cpuSum = 0.0; var n = 0
            val avg = ArrayDeque<Float>(); var worst = 0f; var lastS = 0f
            val on = { t: Float -> (t % 900f) < 600f }
            val r = run(p, Cfg(), 3f, split = sp, renderOn = on, visRealMw = visReal) { t, _, _ ->
                if (t > 1800f) {
                    if (on(t)) { visOnS += 0.25f; visSum += sp.vis; cpuSum += sp.cpu; n++ }
                    if (t - lastS >= 1f) { lastS = t; avg.addLast(p.trueNet); if (avg.size > 30) avg.removeFirst(); if (avg.size == 30) worst = maxOf(worst, abs(avg.average().toFloat())) }
                }
            }
            val visShare = (visSum / maxOf(1e-9, visSum + cpuSum)).toFloat()
            println(line(name, r) + String.format(" | on screen: render %.0f%% of the brake | worst 30 s net %+.0f mW", visShare * 100, worst))
            assertTrue(name, abs(r.meanNet) < 100f)
            assertTrue(name, r.pctMax - r.pctMin <= 1)
            assertTrue(name, r.mahErrMax < 12f)
            assertTrue(name, visShare > 0.3f)           // render goes first
            assertTrue(name, worst < 450f)              // handoffs don't kick the net far
            assertTrue(name, r.jitter < 0.08f)          // no oscillation
        }
        // with the render stage off it must equal the CPU-only controller
        val a = run(FakePhone(61, surgeS = 0f), Cfg(), 1.5f); val b = run(FakePhone(61, surgeS = 0f), Cfg(), 1.5f, split = BrakeSplit())
        assertEquals(a.meanNet, b.meanNet, 0.01f)
        val s2 = BrakeSplit(); s2.update(10f, true, 1500f); s2.split(0.5f, s2.update(0f, true, 1500f), 1500f)
        assertEquals(400f / 1500f, s2.vis, 1e-4f); assertEquals(0.5f - 400f / 1500f, s2.cpu, 1e-4f)
        s2.split(0.5f, s2.update(0.25f, false, 1500f), 1500f)   // app hidden: CPU takes the whole output at once
        assertEquals(0f, s2.vis, 0f); assertEquals(0.5f, s2.cpu, 1e-6f)
    }

    /** Exact mAh target: charges up to it and holds it, not the % step. */
    @Test fun exactMahTarget() {
        val cfg = Cfg().apply { holdCurrent = false; capacityMah = 4860f }
        val c = Controller(cfg)
        c.setTargetPct(60, 57, 2790f)                      // at 57 % (2,790 mAh) the user picks 60 %
        assertEquals(2790f + 3 * 48.6f, cfg.targetMahSet, 0.01f)
        c.setTargetMah(2916f, 57, 2790f); assertEquals(2916f, cfg.targetMahSet, 0f); assertEquals(59, cfg.targetPct)
        val p = FakePhone(70, startPct = 57.4f, surgeS = 0f)
        val cfg2 = Cfg().apply { holdCurrent = false; targetPct = 60 }
        var tgt = Float.NaN; var first = Float.NaN
        val r = run(p, cfg2, 4f, statsAfterS = 2.5f * 3600f) { _, cc, _ -> if (first.isNaN() && !cc.targetMah.isNaN()) first = cc.targetMah; tgt = cc.targetMah }
        println(line("target 60% -> exact mAh", r) + String.format(" | target %.1f mAh (converted once, end %.1f)", first, tgt))
        assertEquals("converted once", first, tgt, 0.01f)
        assertTrue(r.mahErrMax < 6f && abs(r.mahErrEnd) < 3f)
        // hold current locks the exact mAh
        val c3 = Controller(Cfg()); c3.arm(55, 2697.3f); assertEquals(2697.3f, c3.targetMah, 0f)
        c3.shiftScale(12.5f); assertEquals(2709.8f, c3.targetMah, 0.01f)
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

    @Test fun drainToTargetBelow() {
        println("=== drain: start 57.5%, target 55% (5 W charger, steady) ===")
        for ((label, phone) in listOf("field phone (gain 1.2 W)" to 0, "strong brake (gain 3 W)" to 1)) {
            for (speed in listOf(Drain.GENTLE, Drain.NORMAL, Drain.FAST)) {
                val cfg = Cfg().apply { holdCurrent = false; targetPct = 55; drainSpeed = speed }
                val p = if (phone == 0) FakePhone(20 + speed, startPct = 57.5f, surgeS = 0f, heatC = 14f)
                        else FakePhone(30 + speed, startPct = 57.5f, surgeS = 0f, burnGain = 3000f, heatC = 14f)
                var reached = -1f; var reachedMah = -1f; var drainS = 0f; var drainNet = 0.0; var drainN = 0; var sawDrain = false
                var drainAfter = 0; var tMax = 0f; var hotSeen = false; var wasDrain = false
                val r = run(p, cfg, 7f, statsAfterS = 6f * 3600f) { t, c, _ ->
                    val d = c.st == St.DRAINING
                    if (d) { sawDrain = true; drainN++; drainNet += p.trueNet; drainS += 0.25f }
                    if (reached < 0 && p.pct <= 55) reached = t
                    // the target is an exact mAh (mid 55 %): count re-drains only after that mAh was reached
                    if (reachedMah < 0 && !c.targetMah.isNaN() && p.mahTrue <= c.targetMah + Controller.DRAIN_END_MAH) reachedMah = t
                    if (reachedMah >= 0 && t > reachedMah + 1800f && d && !wasDrain) drainAfter++
                    wasDrain = d
                    tMax = maxOf(tMax, p.temp); if (c.st == St.HOT) hotSeen = true
                }
                val mean = if (drainN > 0) drainNet / drainN else 0.0
                println(String.format("%-26s %-6s net while draining %+5.0f mW | reached 55%% after %5.1f min | then level %d-%d%%, mAh err max %4.1f | temp max %.1f°C%s | re-drains %d",
                    label, Drain.name(speed), mean, reached / 60, r.pctMin, r.pctMax, r.mahErrMax, tMax, if (hotSeen) " (hot pause)" else "", drainAfter))
                assertTrue("$label ${Drain.name(speed)} drained", sawDrain && reached > 0)
                assertTrue("$label ${Drain.name(speed)} holds at target", r.pctMin >= 54 && r.pctMax <= 55)
                assertTrue("$label ${Drain.name(speed)} temp", tMax < cfg.tempCutoffC + 1f)
                assertTrue("$label ${Drain.name(speed)} no re-drain", drainAfter <= 1)
                // where the drain rate is reachable, it is held near the chosen speed
                if (phone == 1 && speed != Drain.FAST) assertTrue("$label ${Drain.name(speed)} rate", abs(mean - Drain.mw(speed)) < 120)
                if (phone == 1 && speed == Drain.FAST) assertTrue("$label Fast rate", mean < -1000)
            }
        }
    }

    @Test fun holdCurrentLockedThroughDip() {
        // locked at 55% (55.4 %); 3 W phone use for 20 min pulls it to 54 %; settings change + mAh source switch mid-dip
        for (gauge in listOf(true, false)) {
            val p = FakePhone(40, startPct = 55.4f, surgeS = 0f, gauge = gauge)
            var lockedPct = -1; var lockedMah = Float.NaN; var minPct = 99; var sawUp = false; var maxHoldDrift = 0
            var tgtAfterDip = Float.NaN; var banner = ""
            val r = run(p, Cfg(), 3f, statsAfterS = 2.4f * 3600f) { t, c, mt ->
                if (lockedPct < 0 && !c.targetMah.isNaN()) { lockedPct = c.holdPct; lockedMah = c.targetMah }
                p.usageMw = if (t in 1200f..2400f) 3000f else 0f
                if (t in 1800f..1800.3f) c.retarget(p.pct, mt.mah)          // a settings change must not re-capture
                if (!gauge && t in 2000f..2000.3f) c.rebase(p.pct, mt.mah)  // mAh source switch keeps the locked %
                minPct = minOf(minPct, p.pct); if (c.st == St.CHARGING_UP) { sawUp = true; banner = "Charging up to ${c.hold()}%" }
                if (lockedPct >= 0) maxHoldDrift = maxOf(maxHoldDrift, abs(c.holdPct - lockedPct))
                if (t > 2.4f * 3600f && tgtAfterDip.isNaN()) tgtAfterDip = c.targetMah
            }
            println(String.format("%-36s locked %d%% / %.0f mAh | dip to %d%% (%s) | target after %.0f mAh | then level %d-%d%%, mAh err max %.1f",
                "hold current, dip (gauge=$gauge)", lockedPct, lockedMah, minPct, banner, tgtAfterDip, r.pctMin, r.pctMax, r.mahErrMax))
            assertEquals(55, lockedPct)
            assertTrue("dipped below", minPct <= 54 && sawUp && banner == "Charging up to 55%")
            assertEquals("hold % never re-captured", 0, maxHoldDrift)
            if (gauge) assertEquals("locked mAh kept", lockedMah, tgtAfterDip, 0.01f) else assertTrue(tgtAfterDip >= lockedMah - 0.6f * 48.6f)
            assertTrue("back at the locked level", r.pctMin == 55 && r.pctMax == 55)
        }
    }

    @Test fun holdLockSurvivesRestart() {
        val cfg = Cfg(); val c = Controller(cfg)
        c.arm(55, 2680f)                       // user enables: lock 55 % / 2680 mAh
        c.arm(54, 2640f, keepHold = true)      // service restart while the level is lower
        c.retarget(54, 2640f)
        println(String.format("%-36s after restart at 54%%: hold %d%%, target %.0f mAh", "hold lock + restart", c.hold(), c.targetMah))
        assertEquals(55, c.hold()); assertEquals(2680f, c.targetMah, 0.01f)
        c.lockHold(54, 2640f); c.retarget(54, 2640f)   // user taps Hold current again: re-lock
        assertEquals(54, c.hold()); assertEquals(2640f, c.targetMah, 0.01f)
    }

    @Test fun holdQuality() {
        for ((name, p, expect) in listOf(
            Triple("field charger", FakePhone(50, surgeS = 0f), "ok"),
            Triple("charger too strong (4 W)", FakePhone(51, surgeS = 0f, steadyMw = 4000f), "charger too strong, brake maxed"),
            Triple("charger too weak (0.5 W, phone 2.5 W)", FakePhone(52, surgeS = 0f, steadyMw = 500f, baseMw = 2500f), "charger too weak, draining"),
        )) {
            val dm = DriftMeter(); var last = 0f
            run(p, Cfg(), 1f, statsAfterS = 0f) { t, c, _ ->
                if (t >= 1800f && t - last >= 1f) { last = t; dm.add(p.sensorMw, 3.95f, 4860f, c.burn, Cfg().maxBurn) }
            }
            val q = dm.minPerPct
            println(String.format("%-36s drift %+5.0f mW (±%.0f) -> %s per 1%% %s", "hold quality: $name", dm.mw, dm.noise,
                if (q.isInfinite()) "∞ (steady)" else String.format("%.0f min", q), dm.reason))
            if (expect == "ok") assertTrue(name, q.isInfinite() || q >= 60f) else { assertTrue(name, q < 10f); assertEquals(expect, dm.reason) }
        }
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
