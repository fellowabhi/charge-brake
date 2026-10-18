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
    private lateinit var costTv: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var drainRow: FlowRow
    private lateinit var batBtn: TextView
    private lateinit var powRow: FlowRow
    private lateinit var unitRow: FlowRow
    private lateinit var mahRow: LinearLayout
    private lateinit var tgtTv: TextView
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
        slider(col, "Target level", 20, 100, 1, cfg.targetPct, { "$it%" }) { Monitor.setTargetPct(it); updTgt() }
        col.add(text(14f, C_DIM, txt = "Enter the target as"), dp(12))
        unitRow = FlowRow(this, dp(6), dp(6)); col.add(unitRow, dp(8))
        val mahIn = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "target mAh"; setTextColor(C_TXT); setHintTextColor(C_FAINT); textSize = 15f
            if (!cfg.targetMahSet.isNaN()) setText(cfg.targetMahSet.toInt().toString())
        }
        mahRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        mahRow.addView(mahIn, LinearLayout.LayoutParams(0, -2, 1f))
        mahRow.addView(button("Set mAh") {
            val v = mahIn.text.toString().toFloatOrNull()
            if (v == null || v < 100f || v > cfg.capacityMah * 1.3f) Toast.makeText(this, "Enter an mAh value up to ~${cfg.capacityMah.toInt()}", Toast.LENGTH_SHORT).show()
            else { Monitor.setTargetUnit(true); Monitor.setTargetMah(v); updTgt() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        col.add(mahRow, dp(6))
        tgtTv = text(11.5f, C_FAINT); col.add(tgtTv, dp(4)); updTgt()
        slider(col, "Battery capacity", 2000, 7000, 10, cfg.capacityMah.toInt(), { "$it mAh" }) { cfg.capacityMah = it.toFloat(); Monitor.changed("capacity", it, true) }
        col.add(text(11.5f, C_FAINT, txt = String.format(Locale.US, "Rated %.0f mAh (%s). Used for %% → mAh and counting.", Monitor.ratedMah, Monitor.ratedSrc)), dp(2))
        col.add(text(14f, C_DIM, txt = "Drain speed (when the target is below the current level)"), dp(14))
        drainRow = FlowRow(this, dp(6), dp(6)); col.add(drainRow, dp(8)); updDrain()
        col.add(text(11.5f, C_FAINT, txt = "Gentle −150 mW · Normal −400 mW · Fast = max brake (still paused at the temperature cutoff). How fast it really drains depends on the charger: on a 5 W charger even max brake may only reach about −300 mW."), dp(6))
        slider(col, "Fallback net target (no mAh)", -200, 500, 25, cfg.targetMw, { "%+d mW".format(it) }) { cfg.targetMw = it; Monitor.changed("target_mW", it) }
        section("BRAKE")
        slider(col, "Aggressiveness", 1, 5, 1, cfg.aggr, { "$it" }) { cfg.aggr = it; Monitor.changed("aggr", it) }
        col.add(text(14f, C_DIM, txt = "Max brake power"), dp(12))
        powRow = FlowRow(this, dp(6), dp(6)); col.add(powRow, dp(8)); updPow()
        col.add(text(11.5f, C_FAINT, txt = "Normal = the Max brake % below on the chosen threads (default 80 %, 4 threads). High = all ${Monitor.cores} cores at 100 %. Max = all cores at 100 % with normal-priority threads (can slow the phone). Higher levels get the phone warm; the temperature cutoff (${cfg.tempCutoffC.toInt()} °C) always applies. In Live view the app's own drawing (higher frame rate, glows) is used first, CPU threads only for the rest."), dp(6))
        slider(col, "Max brake (Normal)", 10, 100, 5, (Monitor.userMaxBurn * 100).toInt(), { "$it%" }) { Monitor.changeMaxBurn(it / 100f) }
        slider(col, "Brake threads (Normal)", 1, 8, 1, Monitor.threads, { "$it" }) { Monitor.changeThreads(it) }
        section("SAFETY")
        slider(col, "Fast-charger cutoff (10 s avg)", 1000, 20000, 500, cfg.fastCutoffMw, { String.format(Locale.US, "%.1f W", it / 1000f) }) { cfg.fastCutoffMw = it; Monitor.changed("fast_cutoff_mW", it) }
        slider(col, "Temperature cutoff", 350, 550, 5, (cfg.tempCutoffC * 10).toInt(), { String.format(Locale.US, "%.1f °C", it / 10f) }) { cfg.tempCutoffC = it / 10f; Monitor.changed("temp_cutoff", it / 10f) }
        col.add(Switch(this).apply {
            text = "Invert current sign"; setTextColor(C_TXT); textSize = 14f; isChecked = Monitor.invert
            setOnCheckedChangeListener { _, on -> Monitor.invert = on; Monitor.changed("invert", on) }
        }, dp(14))
        batBtn = button("Battery optimisation") { reqBat() }; col.add(batBtn, dp(14))
        section("APP'S OWN POWER (ESTIMATE)")
        costTv = text(13f, C_TXT); col.add(costTv, dp(8))
        col.add(text(11.5f, C_FAINT, txt = "Estimate: the 10 min average of net + brake power with this app's main screen in front (Eco or Live) minus the same with the app in the background, screen on. Includes the screen content drawing, not the display itself; charger and phone-use changes add noise."), dp(4))
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

    private fun updPow() {
        powRow.removeAllViews()
        listOf(Monitor.P_NORMAL, Monitor.P_HIGH, Monitor.P_MAX).forEach { p ->
            powRow.addView(chip(Monitor.powerName(p), Monitor.power == p) { Monitor.setPower(p); updPow() })
        }
    }
    private fun updTgt() {
        unitRow.removeAllViews()
        unitRow.addView(chip("%", !Monitor.targetByMah) { Monitor.setTargetUnit(false); updTgt() })
        unitRow.addView(chip("mAh", Monitor.targetByMah) { Monitor.setTargetUnit(true); updTgt() })
        mahRow.visibility = if (Monitor.targetByMah) android.view.View.VISIBLE else android.view.View.GONE
        val c = Monitor.cfg
        tgtTv.text = if (c.targetMahSet.isNaN()) "The % is converted once to an exact mAh target when there is a charge reading."
            else "Target ${Monitor.mahLabel(c.targetMahSet)} · converted once, held exactly (no % rounding)." + if (c.holdCurrent) " (Hold current is selected now.)" else ""
    }

    private fun updDrain() {
        drainRow.removeAllViews()
        listOf(Drain.GENTLE, Drain.NORMAL, Drain.FAST).forEach { d ->
            drainRow.addView(chip("${Drain.name(d)} (${Drain.desc(d)})", Monitor.cfg.drainSpeed == d) { Monitor.cfg.drainSpeed = d; Monitor.changed("drain_speed", Drain.name(d)); updDrain() })
        }
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
        val pl = m.plugged != 0
        fun one(mode: Int, name: String): String {
            val v = m.cost.cost(mode, pl)
            val mins = m.cost.seconds(mode, pl) / 60f
            return if (v.isNaN()) String.format(Locale.US, "%s: measuring (%.0f of 1 min)", name, minOf(mins, 1f) * 1f) else String.format(Locale.US, "%s: ~%s mW (%.0f min of data)", name, fmtInt(v), mins)
        }
        costTv.text = (if (pl) "Plugged in" else "On battery") + String.format(Locale.US, " · background (screen on): %.0f min of data\n", m.cost.seconds(Monitor.UI_NONE, pl) / 60f) +
            one(Monitor.UI_ECO, "Eco view") + "\n" + one(Monitor.UI_LIVE, "Live view")
        tv.text = String.format(Locale.US, """
            raw CURRENT_NOW   %d
            raw CURRENT_AVG   %d
            unit / sign       %s · flip %b (verified %b) · invert %b
            current           %+.1f mA @ %.3f V
            power             %+.0f mW (1.5 s avg %+.0f)
            phone use (est.)  %.0f mW · charger est. %.0f mW
            hold quality      drift %.0f mW (±%.0f) · %.0f min per 1 %% %s
            use on battery    %.0f mW (calibration)
            mW reading ok     %b · %.2f Hz · changed %d ms ago
            charge counter    %d (gauge %.1f mAh)
            mAh               %.1f · source %s
            target mAh        %.1f
            level / temp      %d %% · %.1f °C
            plugged / status  %s · %d · health %d
            max chg (extra)   %d µA · %d µV

            state             %s %s
            mode / effective  %s / %s
            hold              %s %d %% · drain %s (draining %b)
            target power      %+.0f mW
            ema slow / use    %+.0f / %+.0f mW
            brake             %.1f %% x %d threads (~%.0f mW) · power %s
            brake split       visual %.0f mW (level %.2f, render %s) · CPU %.0f mW
            target mAh set    %.1f (≈%d %%) · hold mAh %.1f
            fast 10 s avg     %.0f mW (cutoff %d) retry %d s ignore %b
            sensor period     %.2f s (controller est.)
            log rows          %d
        """.trimIndent(),
            m.raw, m.rawAvg, if (m.unitUa) "µA" else "mA", m.flip, m.signKnown, m.invert, m.mA, m.volt, m.mW, m.avgMw, m.phoneUse, m.chargerEst, m.driftMw, m.driftNoise, m.minPerPct, m.driftReason, m.useBase,
            m.mwOk, m.rateHz, SystemClock.elapsedRealtime() - m.lastChange, m.chargeCounter, m.mahT.gaugeMah, m.mah, m.mahT.source,
            c.targetMah, m.pct, m.temp, plugName(m.plugged), m.status, m.health, m.maxChgUa, m.maxChgUv,
            m.state.name, if (c.paused) "(paused)" else "", Mode.name(f.mode), Mode.name(c.effMode), if (f.holdCurrent) "current" else "target", c.hold(), Drain.name(f.drainSpeed), c.draining,
            c.tgtMw, c.emaSlow, c.emaUse, c.burn * 100, m.burner.threads, m.burnMw, m.powerName(), m.visMw, m.visLevel, if (m.renderOk) "on" else "off", m.cpuMw,
            f.targetMahSet, c.approxPct(f.targetMahSet, m.pct, m.mah), c.holdMah, c.fastMw, f.fastCutoffMw, c.fastRetryInS, c.ignoreFast,
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
