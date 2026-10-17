package com.fellowabhi.chargebrake

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

class DebugActivity : Activity() {
    private lateinit var tv: TextView
    private val tick: () -> Unit = { refresh() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Monitor.init(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(32)) }
        col.add(text(13f, C_DIM).apply { text = "DEBUG" })
        tv = text(13f, C_TXT, mono = true); col.add(tv, dp(8))
        col.add(button("Share log (CSV)", 0xFF00838F.toInt()) { share() }, dp(14), dp(52))
        col.add(button("Reset unit/sign detection") { Monitor.resetDetection(); Toast.makeText(this, "Reset", Toast.LENGTH_SHORT).show() }, dp(10), dp(52))
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(col) })
    }

    override fun onStart() { super.onStart(); Monitor.acquire(this); Monitor.listeners.add(tick) }
    override fun onStop() { Monitor.listeners.remove(tick); Monitor.release(); super.onStop() }

    private fun refresh() {
        val m = Monitor; val c = m.ctl; val f = m.cfg
        tv.text = String.format(Locale.US, """
            raw CURRENT_NOW   %d
            raw CURRENT_AVG   %d
            unit              %s (known %b)
            sign flip (auto)  %b (verified %b)
            invert (manual)   %b
            current           %+.1f mA
            voltage           %.3f V
            power             %+.0f mW
            mW reading ok     %b
            sensor rate       %.2f Hz
            last change       %d ms ago
            charge counter    %d µAh
            max chg current   %d µA
            max chg voltage   %d µV
            level             %d %%
            temperature       %.1f °C
            plugged           %s
            status            %d   health %d

            enabled           %b
            state             %s
            mode / effective  %s / %s
            hold level        %d %%
            target power      %+.0f mW
            ema slow / fast   %+.0f / %+.0f mW
            burn              %.1f %% x %d threads
            fast latched      %b
            too hot           %b
            draining          %b
            cutoffs           %.2f W, %.1f °C
            log rows          %d
        """.trimIndent(),
            m.raw, m.rawAvg, if (m.unitUa) "µA" else "mA", m.unitKnown, m.flip, m.signKnown, m.invert,
            m.mA, m.volt, m.mW, m.mwOk, m.rateHz, android.os.SystemClock.elapsedRealtime() - m.lastChange,
            m.chargeCounter, m.maxChgUa, m.maxChgUv, m.pct, m.temp, plugName(m.plugged), m.status, m.health,
            m.enabled, c.state, Mode.name(f.mode), Mode.name(c.effMode), c.hold(), c.tgtMw, c.emaSlow, c.emaFast,
            c.burn * 100, m.burner.threads, c.fastLatched, c.hot, c.draining, f.fastCutoffMw / 1000f, f.tempCutoffC, m.logRows())
    }

    private fun share() {
        File(cacheDir, LogProvider.NAME).writeText(Monitor.csv())
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, LogProvider.URI)
            putExtra(Intent.EXTRA_SUBJECT, "Charge Brake log")
            clipData = ClipData.newRawUri(LogProvider.NAME, LogProvider.URI)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share log"))
    }
}
