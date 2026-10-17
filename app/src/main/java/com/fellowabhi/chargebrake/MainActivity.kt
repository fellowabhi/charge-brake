package com.fellowabhi.chargebrake

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var big: TextView
    private lateinit var sub: TextView
    private lateinit var rate: TextView
    private lateinit var status: TextView
    private lateinit var chart: Chart
    private lateinit var toggle: android.widget.Button
    private lateinit var batBtn: android.widget.Button
    private val tick: () -> Unit = { refresh() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Monitor.init(this)
        setContentView(build())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onStart() { super.onStart(); Monitor.acquire(this); Monitor.listeners.add(tick); refresh() }
    override fun onStop() { Monitor.listeners.remove(tick); Monitor.release(); super.onStop() }
    override fun onResume() { super.onResume(); updBat() }

    private fun build(): View {
        val cfg = Monitor.cfg
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(32)) }
        col.add(text(13f, C_DIM).apply { text = "CHARGE BRAKE" })
        big = text(44f, C_TXT, bold = true); col.add(big)
        sub = text(14f, C_TXT); col.add(sub)
        rate = text(12f, C_DIM); col.add(rate, dp(2))
        chart = Chart(this, Monitor.hist).apply { background = round(0xFF16181D.toInt(), dp(12).toFloat()); clipToOutline = true }
        col.add(chart, dp(12), dp(250))
        status = text(15f, C_ACC); col.add(status, dp(12))
        toggle = button("Start") { onToggle() }.apply { textSize = 20f }
        col.add(toggle, dp(10), dp(64))

        col.add(text(13f, C_DIM).apply { text = "MODE" }, dp(22))
        seg(col, listOf("Auto", "mW", "%"), cfg.mode) { cfg.mode = it; Monitor.save() }
        col.add(text(13f, C_DIM).apply { text = "HOLD LEVEL" }, dp(14))
        seg(col, listOf("Current %", "Target %"), if (cfg.holdCurrent) 0 else 1) {
            cfg.holdCurrent = it == 0; if (it == 0) Monitor.resetHold(); Monitor.save()
        }
        slider(col, "Target level", 20, 100, 1, cfg.targetPct, { "$it%" }) { cfg.targetPct = it }
        slider(col, "Target net power", -200, 500, 25, cfg.targetMw, { "%+d mW".format(it) }) { cfg.targetMw = it }
        slider(col, "Aggressiveness", 1, 5, 1, cfg.aggr, { "$it" }) { cfg.aggr = it }
        slider(col, "Max burn", 10, 100, 5, (cfg.maxBurn * 100).toInt(), { "$it%" }) { cfg.maxBurn = it / 100f }
        slider(col, "Burn threads", 1, 8, 1, Monitor.threads, { "$it" }) { Monitor.changeThreads(it) }
        col.add(text(13f, C_DIM).apply { text = "SAFETY" }, dp(22))
        slider(col, "Fast-charger cutoff", 500, 10000, 250, cfg.fastCutoffMw, { String.format(Locale.US, "%.2f W", it / 1000f) }) { cfg.fastCutoffMw = it }
        slider(col, "Temperature cutoff", 300, 480, 5, (cfg.tempCutoffC * 10).toInt(), { String.format(Locale.US, "%.1f °C", it / 10f) }) { cfg.tempCutoffC = it / 10f }
        col.add(Switch(this).apply {
            text = "Invert current sign"; setTextColor(C_TXT); textSize = 14f; isChecked = Monitor.invert
            setOnCheckedChangeListener { _, on -> Monitor.invert = on; Monitor.save() }
        }, dp(14))
        batBtn = button("Battery optimisation") { reqBat() }; col.add(batBtn, dp(14), dp(52))
        col.add(button("Debug & log") { startActivity(Intent(this, DebugActivity::class.java)) }, dp(10), dp(52))
        col.add(text(11f, C_DIM).apply { text = "v${BuildConfigLite.VERSION} · no ads, no internet · tap chart for 1/5 min" }, dp(16))
        return ScrollView(this).apply { fitsSystemWindows = true; addView(col) }
    }

    private fun onToggle() {
        if (Monitor.enabled) BrakeService.stop(this) else BrakeService.start(this)
        toggle.postDelayed({ refresh() }, 300)
    }

    private fun updBat() {
        val ok = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        batBtn.text = if (ok) "Battery optimisation: exempt ✓" else "Allow running in background (battery optimisation)"
    }

    private fun reqBat() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val i = if (!pm.isIgnoringBatteryOptimizations(packageName))
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { startActivity(i) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }

    private fun refresh() {
        val m = Monitor; val c = m.ctl
        big.text = if (!m.supported) "n/a" else String.format(Locale.US, "%+,.0f mW", m.mW)
        big.setTextColor(if (m.mW >= 0) C_ACC else 0xFFFF8A65.toInt())
        sub.text = String.format(Locale.US, "%+.0f mA · %.3f V · %.1f °C · %d%% · %s", m.mA, m.volt, m.temp, m.pct, plugName(m.plugged))
        rate.text = String.format(Locale.US, "sensor %.1f Hz · unit %s · sign %s", m.rateHz,
            if (m.unitUa) "µA" else if (m.unitKnown) "mA" else "auto",
            (if (m.signKnown) "auto ✓" else "auto (unplug ~15 s once to verify)") + if (m.invert) " · inverted" else "")
        if (m.enabled) {
            status.text = String.format(Locale.US, "%s\nburn %d%% · hold %d%% · avg %+.0f mW · target %+.0f · %s mode",
                c.state, (c.burn * 100).toInt(), c.hold(), c.emaSlow, c.tgtMw, Mode.name(c.effMode))
            toggle.text = "Stop"; toggle.background = round(C_ON, dp(12).toFloat())
        } else {
            status.text = "Off"
            toggle.text = "Start"; toggle.background = round(0xFF00838F.toInt(), dp(12).toFloat())
        }
        chart.invalidate()
    }
}

fun plugName(p: Int) = when (p) { 1 -> "AC"; 2 -> "USB"; 4 -> "Wireless"; 8 -> "Dock"; 0 -> "On battery"; else -> "Plugged ($p)" }

object BuildConfigLite { const val VERSION = "0.1" }
