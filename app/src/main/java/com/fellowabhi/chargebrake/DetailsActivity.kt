package com.fellowabhi.chargebrake

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/** Settings, raw sensor values and the CSV log. */
class DetailsActivity : Activity() {
    private lateinit var tv: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var batBtn: TextView
    private val tick: () -> Unit = { refresh() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Monitor.init(this)
        val cfg = Monitor.cfg
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(32)) }
        col.add(text(13f, C_DIM, txt = "DETAILS").apply { letterSpacing = 0.12f })
        fun section(s: String) = col.add(text(12f, C_DIM, bold = true, txt = s).apply { letterSpacing = 0.1f }, dp(22))
        section("MODE")
        modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; col.add(modeRow, dp(8)); updMode()
        col.add(text(11.5f, C_FAINT, txt = "Auto = mW controller with mAh hold, falls back to % if the power reading looks bad."), dp(6))
        section("HOLD")
        slider(col, "Target level", 20, 100, 1, cfg.targetPct, { "$it%" }) { cfg.targetPct = it; Monitor.changed("target_pct", it, true) }
        slider(col, "Battery capacity", 2000, 7000, 10, cfg.capacityMah.toInt(), { "$it mAh" }) { cfg.capacityMah = it.toFloat(); Monitor.changed("capacity", it, true) }
        col.add(text(11.5f, C_FAINT, txt = String.format(Locale.US, "Rated %.0f mAh (%s). Used for %% → mAh and counting.", Monitor.ratedMah, Monitor.ratedSrc)), dp(2))
        slider(col, "Fallback net target (no mAh)", -200, 500, 25, cfg.targetMw, { "%+d mW".format(it) }) { cfg.targetMw = it; Monitor.changed("target_mW", it) }
        section("BRAKE")
        slider(col, "Aggressiveness", 1, 5, 1, cfg.aggr, { "$it" }) { cfg.aggr = it; Monitor.changed("aggr", it) }
        slider(col, "Max brake", 10, 100, 5, (cfg.maxBurn * 100).toInt(), { "$it%" }) { cfg.maxBurn = it / 100f; Monitor.changed("max_burn", it / 100f) }
        slider(col, "Brake threads", 1, 8, 1, Monitor.threads, { "$it" }) { Monitor.changeThreads(it) }
        section("SAFETY")
        slider(col, "Fast-charger cutoff (10 s avg)", 1000, 20000, 500, cfg.fastCutoffMw, { String.format(Locale.US, "%.1f W", it / 1000f) }) { cfg.fastCutoffMw = it; Monitor.changed("fast_cutoff_mW", it) }
        slider(col, "Temperature cutoff", 350, 550, 5, (cfg.tempCutoffC * 10).toInt(), { String.format(Locale.US, "%.1f °C", it / 10f) }) { cfg.tempCutoffC = it / 10f; Monitor.changed("temp_cutoff", it / 10f) }
        col.add(Switch(this).apply {
            text = "Invert current sign"; setTextColor(C_TXT); textSize = 14f; isChecked = Monitor.invert
            setOnCheckedChangeListener { _, on -> Monitor.invert = on; Monitor.changed("invert", on) }
        }, dp(14))
        batBtn = button("Battery optimisation") { reqBat() }; col.add(batBtn, dp(14))
        section("SENSOR")
        tv = text(12.5f, C_TXT, mono = true); col.add(tv, dp(8))
        col.add(button("Share log (CSV)", 0xFF00838F.toInt()) { share() }, dp(14))
        col.add(button("Reset unit/sign detection") { Monitor.resetDetection(); Toast.makeText(this, "Reset", Toast.LENGTH_SHORT).show() }, dp(10))
        col.add(text(11f, C_FAINT, txt = "v${Monitor.VERSION} · no ads, no internet"), dp(16))
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(C_BG); addView(col) })
    }

    private fun button(label: String, bg: Int = C_CARD, onClick: () -> Unit) = text(15f, C_TXT, txt = label).apply {
        setPadding(dp(16), dp(14), dp(16), dp(14)); background = round(bg, dpf(12f)); setOnClickListener { onClick() }
    }

    private fun updMode() {
        modeRow.removeAllViews()
        listOf(Mode.AUTO, Mode.MW, Mode.PCT).forEachIndexed { k, md ->
            modeRow.addView(chip(Mode.name(md), Monitor.cfg.mode == md) { Monitor.cfg.mode = md; Monitor.changed("mode", Mode.name(md), true); updMode() },
                LinearLayout.LayoutParams(-2, -2).apply { if (k > 0) leftMargin = dp(8) })
        }
    }

    override fun onStart() { super.onStart(); Monitor.acquire(this); Monitor.listeners.add(tick) }
    override fun onStop() { Monitor.listeners.remove(tick); Monitor.release(); super.onStop() }
    override fun onResume() {
        super.onResume()
        val ok = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        batBtn.text = if (ok) "Battery optimisation: exempt ✓" else "Allow running in background (battery optimisation)"
    }

    private fun reqBat() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val i = if (!pm.isIgnoringBatteryOptimizations(packageName)) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(i) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
    }

    private fun refresh() {
        val m = Monitor; val c = m.ctl; val f = m.cfg
        tv.text = String.format(Locale.US, """
            raw CURRENT_NOW   %d
            raw CURRENT_AVG   %d
            unit / sign       %s · flip %b (verified %b) · invert %b
            current           %+.1f mA @ %.3f V
            power             %+.0f mW (3 s avg %+.0f)
            mW reading ok     %b · %.2f Hz · changed %d ms ago
            charge counter    %d (gauge %.1f mAh)
            mAh               %.1f · source %s
            target mAh        %.1f
            level / temp      %d %% · %.1f °C
            plugged / status  %s · %d · health %d
            max chg (extra)   %d µA · %d µV

            state             %s %s
            mode / effective  %s / %s
            hold              %s %d %%
            target power      %+.0f mW
            ema slow / use    %+.0f / %+.0f mW
            brake             %.1f %% x %d threads (~%.0f mW)
            fast 10 s avg     %.0f mW (cutoff %d) retry %d s ignore %b
            sensor period     %.2f s (controller est.)
            log rows          %d
        """.trimIndent(),
            m.raw, m.rawAvg, if (m.unitUa) "µA" else "mA", m.flip, m.signKnown, m.invert, m.mA, m.volt, m.mW, m.avgMw,
            m.mwOk, m.rateHz, SystemClock.elapsedRealtime() - m.lastChange, m.chargeCounter, m.mahT.gaugeMah, m.mah, m.mahT.source,
            c.targetMah, m.pct, m.temp, plugName(m.plugged), m.status, m.health, m.maxChgUa, m.maxChgUv,
            m.state.name, if (c.paused) "(paused)" else "", Mode.name(f.mode), Mode.name(c.effMode), if (f.holdCurrent) "current" else "target", c.hold(),
            c.tgtMw, c.emaSlow, c.emaUse, c.burn * 100, m.burner.threads, m.burnMw, c.fastMw, f.fastCutoffMw, c.fastRetryInS, c.ignoreFast,
            c.sensorPeriodS, m.logRows())
    }

    private fun share() {
        File(cacheDir, LogProvider.NAME).writeText(Monitor.csv())
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, LogProvider.URI); putExtra(Intent.EXTRA_SUBJECT, "Charge Brake log")
            clipData = ClipData.newRawUri(LogProvider.NAME, LogProvider.URI); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share log"))
    }
}

fun plugName(p: Int) = when (p) { 1 -> "AC"; 2 -> "USB"; 4 -> "Wireless"; 8 -> "Dock"; 0 -> "On battery"; else -> "Plugged ($p)" }
