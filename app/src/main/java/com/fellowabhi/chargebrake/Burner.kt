package com.fellowabhi.chargebrake

import android.os.Process

/** Low-priority CPU load: N threads, each busy for `level` of every 20 ms slice. */
class Burner {
    @Volatile var level = 0f
    @Volatile private var gen = 0
    var threads = 0; private set
    @Volatile var sink = 0.0

    fun start(n: Int) {
        stop()
        val g = ++gen
        threads = n
        repeat(n) { k ->
            Thread({ loop(g) }, "burn$k").apply { isDaemon = true; start() }
        }
    }

    fun stop() { gen++; threads = 0; level = 0f }

    private fun loop(g: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
        var x = 1.0
        while (g == gen) {
            val l = level
            if (l <= 0.002f) { Thread.sleep(100); continue }
            val busy = (PERIOD_NS * l).toLong()
            val t0 = System.nanoTime()
            while (System.nanoTime() - t0 < busy) {
                for (i in 0 until 2000) x = x * 1.0000001 + 1e-9
            }
            sink = x
            val rest = PERIOD_NS - busy
            if (rest > 200_000) Thread.sleep(rest / 1_000_000, (rest % 1_000_000).toInt())
        }
    }

    companion object { const val PERIOD_NS = 20_000_000L }
}
