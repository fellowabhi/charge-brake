package com.fellowabhi.chargebrake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SignalsTest {
    /** Gauge in 1 mAh steps every ~7 s at +500 mA: the display value must move smoothly (no steps > 0.3 mAh per s). */
    @Test fun smootherInterpolatesGaugeSteps() {
        val s = MahSmoother()
        var trueMah = 2900.0; var maxStep = 0f; var maxErr = 0f; var last = Float.NaN
        for (k in 0 until 600) {
            trueMah += 500.0 / 3600.0
            val gauge = Math.floor(trueMah).toFloat()
            assertEquals(0f, s.update(1f, gauge, MahTracker.SRC_GAUGE, 500f, true), 0f)
            if (!last.isNaN()) maxStep = maxOf(maxStep, abs(s.value - last))
            if (k > 60) maxErr = maxOf(maxErr, abs(s.value - trueMah.toFloat()))
            last = s.value
        }
        println("smoother: max step %.3f mAh/s, max error %.2f mAh".format(maxStep, maxErr))
        assertTrue(maxStep < 0.3f); assertTrue(maxErr < 1.5f)
    }

    /** Switching counted -> fuel gauge returns the jump so the history is shifted, and the graph has no step. */
    @Test fun sourceSwitchShiftsHistory() {
        val s = MahSmoother(); val h = ChargeHistory(100)
        for (k in 0 until 10) { s.update(1f, 2900f, MahTracker.SRC_COUNT, 0f, true); h.add(k * 1000L, s.value, 2905f, 60, St.HOLDING) }
        val d = s.update(1f, 2950f, MahTracker.SRC_GAUGE, 0f, true)
        assertEquals(50f, d, 0.01f)
        h.shift(d); h.add(10_000L, s.value, 2955f, 60, St.HOLDING)
        for (k in 1 until h.size) assertEquals(h.mah[h.idx(k - 1)], h.mah[h.idx(k)], 0.01f)
        assertEquals(2955f, h.tmah[h.idx(0)], 0.01f)
    }

    @Test fun firstSampleOfWindow() {
        val h = ChargeHistory(50)
        for (k in 0 until 80) h.add(k * 1000L, k.toFloat(), 0f, 0, St.OFF)   // ring wrapped: 30..79 kept
        assertEquals(0, h.first(10_000L))
        assertEquals(19, h.first(50_000L))                                   // sample at 49 s (just before 50 s)
        assertEquals(49L * 1000, h.t[h.idx(h.first(50_000L))])
        assertEquals(49, h.first(999_000L))
    }

    /** UI cost: 120 s background at +300 mW, app at +260 (mode 1), mode 2 at +180 -> ~40 / ~120 mW. */
    @Test fun uiCostEstimate() {
        val c = UiCost()
        assertTrue(c.cost(1, true).isNaN())
        repeat(120) { c.add(Monitor.UI_NONE, true, true, 300f, 1f); c.add(Monitor.UI_NONE, false, true, 900f, 1f) }   // screen off: ignored
        repeat(120) { c.add(1, true, true, 260f, 1f) }
        repeat(120) { c.add(2, true, true, 180f, 1f) }
        assertEquals(40f, c.cost(1, true), 0.5f)
        assertEquals(120f, c.cost(2, true), 0.5f)
        assertTrue(c.cost(1, false).isNaN())
    }

    @Test fun bubbleScaleAndHealth() {
        assertEquals(C_GREEN, netScale(80f)); assertEquals(C_AMBER, netScale(2500f)); assertEquals(C_BLUE, netScale(-2500f))
        assertTrue(netFrac(100f) in 0.13f..0.16f); assertEquals(1f, netFrac(9000f), 0f)
        val d = BubbleData(40f, 0.6f, St.HOLDING, 60, 60, 2916f, 2917f, 37f, Float.POSITIVE_INFINITY, false)
        assertTrue(d.healthy); assertTrue(!d.copy(net = 300f).healthy); assertTrue(!d.copy(st = St.CHARGING_UP).healthy)
        assertEquals("+1.5k", fmtNetShort(1450f)); assertEquals("−420", fmtNetShort(-420f))
    }
}
