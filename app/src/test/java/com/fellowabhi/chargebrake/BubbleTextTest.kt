package com.fellowabhi.chargebrake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleTextTest {
    private fun d(st: St, net: Float = 12f, holdMah: Float = 2916f, mah: Float = 2900f, temp: Float = 31.2f, q: Float = Float.POSITIVE_INFINITY, paused: Boolean = false) =
        BubbleData(net, 0.38f, st, 55, 55, holdMah, mah, temp, q, paused)

    @Test fun everyStateHasShortSafeTexts() {
        for (st in St.values()) for (nan in listOf(false, true)) {
            val v = if (nan) d(st, net = Float.NaN, holdMah = Float.NaN, mah = Float.NaN, temp = Float.NaN, q = Float.NaN) else d(st)
            val texts = listOf(BubbleText.centre(v.net), BubbleText.brake(v.brake), BubbleText.status(v, v.healthy), BubbleText.line2(v), BubbleText.line3(v, v.brake), BubbleText.quality(v))
            for (t in texts) { assertFalse("$st '$t'", t.contains("NaN") || t.contains("Infinity")); assertTrue("$st too long '$t'", t.length <= 40) }
            assertTrue(BubbleText.status(v, v.healthy).isNotEmpty())
        }
    }

    @Test fun centreIsCompact() {
        assertEquals("+420", BubbleText.centre(420f)); assertEquals("−1.5k", BubbleText.centre(-1520f)); assertEquals("—", BubbleText.centre(Float.NaN))
        assertEquals("B 38%", BubbleText.brake(0.38f)); assertEquals("B 100%", BubbleText.brake(1.4f))
        assertEquals("Holding", BubbleText.status(d(St.BRAKING), true)); assertEquals("Paused", BubbleText.status(d(St.HOLDING, paused = true), false))
    }
}
