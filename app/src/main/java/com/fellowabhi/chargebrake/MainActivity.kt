package com.fellowabhi.chargebrake

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.exp

class MainActivity : Activity(), Choreographer.FrameCallback {
    // views (rebuilt on fold/unfold)
    private lateinit var root: View
    private lateinit var num: TextView
    private lateinit var raw: TextView
    private lateinit var banner: GlowFrame
    private lateinit var bIcon: TextView
    private lateinit var bTitle: TextView
    private lateinit var bSub: TextView
    private lateinit var bIgnore: TextView
    private lateinit var batV: TextView; private lateinit var batS: TextView; private lateinit var batSrc: TextView
    private lateinit var brkTile: GlowFrame; private lateinit var brkV: TextView; private lateinit var brkBar: BarView; private lateinit var brkS: TextView
    private lateinit var surV: TextView; private lateinit var surS: TextView
    private lateinit var tmpV: TextView; private lateinit var tmpBar: BarView; private lateinit var tmpS: TextView
    private lateinit var gCard: GlowFrame; private lateinit var gTitle: TextView
    private lateinit var chart: ChartView; private lateinit var strip: StripView; private lateinit var stripNow: TextView
    private lateinit var brakeCard: GlowFrame; private lateinit var brakeTitle: TextView; private lateinit var screenOn: TextView
    private lateinit var pill: PillSwitch
    private lateinit var chipsRow: LinearLayout
    private val dots = ArrayList<View>()
    private var wide = false

    // eased display values (survive rebuilds)
    private var dNum = 0f; private var dNumCol = C_GREEN; private var dGlow = C_GREY
    private var dBurn = 0f; private var dTemp = 0f; private var dSur = 0f
    private var lastNs = 0L; private var running = false
    private var bgColor = 0; private var keepOn = false
    private var chipState = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Monitor.init(this)
        dNum = Monitor.avgMw; dTemp = Monitor.temp
        rebuild()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); rebuild() }

    override fun onStart() { super.onStart(); Monitor.acquire(this); running = true; lastNs = 0; Choreographer.getInstance().postFrameCallback(this) }
    override fun onStop() { running = false; Choreographer.getInstance().removeFrameCallback(this); Monitor.release(); setKeepOn(false); super.onStop() }

    private fun rebuild() {
        wide = resources.configuration.screenWidthDp >= 600
        dots.clear(); bgColor = 0; chipState = ""
        root = if (wide) buildInner() else buildCover()
        setContentView(root)
    }

    // ---------- pieces ----------
    private fun titleRow(): View {
        val live = row(PulseDot(this).also { dots += it }.apply { layoutParams = ViewGroup.LayoutParams(dp(10), dp(10)) },
            text(11f, 0xFFFF6B6B.toInt(), bold = true, txt = "LIVE"), gap = 4)
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(text(13f, C_DIM, txt = "CHARGE BRAKE").apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(live)
        // the PulseDot needs a fixed size inside the row
        (live as LinearLayout).getChildAt(0).layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        return r.apply { setPadding(dp(4), 0, dp(4), 0) }
    }

    private fun hero(big: Float): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        col.add(text(13f, C_DIM, txt = "Net into battery · 3 s average"))
        num = text(big, C_GREEN, bold = true, txt = "+0")
        val unit = text(big * 0.46f, C_GREEN, bold = true, txt = " mW")
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        r.addView(num); r.addView(unit, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
        unitView = unit
        col.add(r, dp(2))
        raw = text(12.5f, C_DIM); col.add(raw, dp(2))
        return col
    }
    private var unitView: TextView? = null

    private fun bannerView(): View {
        banner = GlowFrame(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(dp(12), dp(9), dp(12), dp(9)) }
        bIcon = text(17f, C_ORANGE, bold = true, txt = "‖").apply { gravity = Gravity.CENTER }
        banner.addView(bIcon, LinearLayout.LayoutParams(dp(32), dp(32)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bTitle = text(16f, C_ORANGE, bold = true).apply { letterSpacing = 0.02f }
        bSub = text(12.5f, 0xFFD9DCE1.toInt())
        t.add(bTitle); t.add(bSub, dp(2))
        banner.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        bIgnore = chip("Ignore", false) { Monitor.ignoreFast() }.apply { visibility = View.GONE }
        banner.addView(bIgnore)
        return banner
    }

    private fun tileFrame(): GlowFrame = GlowFrame(this).apply { pad(dp(10), dp(8), dp(10), dp(8)) }
    private fun key(label: String, color: Int) = row(View(this).apply { background = round(color, dpf(2f)); layoutParams = ViewGroup.LayoutParams(dp(8), dp(8)) },
        text(12f, C_DIM, txt = label), gap = 6).also { (it.getChildAt(0)).layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)) }

    /** Builds the 4 tiles. wide = one column, value left + details right. */
    private fun tiles(wide: Boolean): List<GlowFrame> {
        fun tile(label: String, c: Int, v: TextView, vararg sub: View): GlowFrame {
            val f = tileFrame()
            f.add(key(label, c))
            if (!wide) { f.add(v, dp(3)); sub.forEach { f.add(it, dp(2)) } }
            else {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(v, LinearLayout.LayoutParams(-2, -2))
                val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
                sub.forEach { s.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }); (it as? TextView)?.gravity = Gravity.END }
                r.addView(s, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
                f.add(r, dp(4)); f.gravity = Gravity.CENTER_VERTICAL
            }
            return f
        }
        val vs = if (wide) 26f else 22f
        batV = text(vs, C_TXT, bold = true); batS = text(11.5f, C_DIM).apply { maxLines = 1 }; batSrc = text(11.5f, C_FAINT)
        brkV = text(vs, C_TXT, bold = true); brkBar = BarView(this).apply { color = C_ORANGE; layoutParams = ViewGroup.LayoutParams(-1, dp(5)) }; brkS = text(11.5f, C_DIM)
        surV = text(vs, C_TXT, bold = true); surS = text(11.5f, C_DIM, txt = "net + brake power")
        tmpV = text(vs, C_TXT, bold = true); tmpBar = BarView(this).apply { gradient = true; layoutParams = ViewGroup.LayoutParams(-1, dp(5)) }; tmpS = text(11.5f, C_DIM)
        fun barBox(b: BarView) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(b, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(3) }) }
        val t1 = tile("Battery", C_BLUE, batV, batS, batSrc)
        brkTile = tile("Brake level", C_ORANGE, brkV, barBox(brkBar), brkS)
        val t3 = tile("Charger surplus", C_AMBER, surV, surS)
        val t4 = tile("Temperature", C_GREY, tmpV, barBox(tmpBar), tmpS)
        return listOf(t1, brkTile, t3, t4)
    }

    private fun legendItem(color: Int, label: String, kind: Int): View {
        val m = View(this).apply {
            background = when (kind) { 1 -> round(withAlpha(C_ORANGE, 0.55f), dpf(2f)); 2 -> round(0x2E4CD07D, dpf(2f), 0xB24CD07D.toInt(), dp(1)); else -> round(color, dpf(2f)) }
        }
        val r = row(m, text(10.5f, C_DIM, txt = label), gap = 5)
        r.getChildAt(0).layoutParams = LinearLayout.LayoutParams(dp(14), if (kind == 0) dp(3) else dp(9))
        return r
    }

    private fun graphCard(chartH: Int, explain: Boolean): GlowFrame {
        gCard = GlowFrame(this).apply { pad(dp(10), dp(10), dp(10), dp(6)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        gTitle = text(14f, C_TXT, bold = true, txt = "Power · last 2 min")
        head.addView(gTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val live = row(PulseDot(this).also { dots += it }, text(11f, 0xFFFF6B6B.toInt(), bold = true, txt = "LIVE"), gap = 4)
        live.getChildAt(0).layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        head.addView(live)
        gCard.add(head)
        val leg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val leg2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val items = listOf(legendItem(0xFFFFFFFF.toInt(), "net mW (left)", 0), legendItem(0x66FFFFFF, "raw", 0),
            legendItem(0, "brake % (right)", 1), legendItem(0, "target ±100 mW", 2))
        items.forEachIndexed { k, v -> val target = if (wide || k < 3) leg else leg2
            target.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) }) }
        gCard.add(leg, dp(6)); if (leg2.childCount > 0) gCard.add(leg2, dp(4))
        chart = ChartView(this, Monitor.hist)
        if (chartH > 0) gCard.add(chart, dp(6), dp(chartH)) else gCard.add(chart, dp(6), 0, weight = 1f)
        if (explain) gCard.add(text(11f, C_FAINT, txt = "Above the band = battery charging, so the brake rises. Below = phone uses more than the charger gives, so the brake backs off."), dp(4))
        return gCard
    }

    private fun stripCard(h: Int): GlowFrame {
        val f = GlowFrame(this).apply { pad(dp(10), dp(6), dp(10), dp(4)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(text(11f, C_DIM, txt = "Charge (mAh)"), LinearLayout.LayoutParams(0, -2, 1f))
        stripNow = text(11f, C_DIM); head.addView(stripNow)
        f.add(head)
        strip = StripView(this, Monitor.hist) { chart.windowMs }
        f.add(strip, dp(2), dp(h))
        return f
    }

    private fun controls(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brakeCard = GlowFrame(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(dp(14), dp(10), dp(14), dp(10)) }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brakeTitle = text(18f, C_TXT, bold = true, txt = "Brake OFF")
        screenOn = text(12f, C_GREEN, txt = "☀ Screen stays on while plugged")
        t.add(brakeTitle); t.add(screenOn, dp(3))
        brakeCard.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        pill = PillSwitch(this); brakeCard.addView(pill, LinearLayout.LayoutParams(dp(64), dp(36)))
        brakeCard.setOnClickListener { onToggle() }
        col.add(brakeCard)
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(5), 0, 0, 0) }
        col.add(chipsRow, dp(4))
        val det = GlowFrame(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(dp(14), dp(10), dp(14), dp(10)) }
        det.addView(text(14f, C_TXT, txt = "Details").apply { }, LinearLayout.LayoutParams(-2, -2))
        det.addView(text(14f, C_DIM, txt = " · settings, sensor, log"), LinearLayout.LayoutParams(0, -2, 1f))
        det.addView(text(14f, C_DIM, txt = "›"))
        det.setOnClickListener { startActivity(Intent(this, DetailsActivity::class.java)) }
        col.add(det, dp(4))
        return col
    }

    private fun updChips() {
        val cfg = Monitor.cfg
        val key = "${cfg.holdCurrent}/${Monitor.ctl.hold()}/${cfg.targetPct}/${Monitor.pct}"
        if (key == chipState) return
        chipState = key
        chipsRow.removeAllViews()
        val holdLbl = "● Hold current (${if (Monitor.enabled && cfg.holdCurrent) Monitor.ctl.hold() else Monitor.pct}%)"
        chipsRow.addView(chip(holdLbl, cfg.holdCurrent) { if (!cfg.holdCurrent) { cfg.holdCurrent = true; Monitor.changed("hold_mode", "current", true) } })
        chipsRow.addView(chip("Target ${cfg.targetPct}%", !cfg.holdCurrent) { if (cfg.holdCurrent) { cfg.holdCurrent = false; Monitor.changed("hold_mode", "target", true) } },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
    }

    private fun buildCover(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), dp(6), dp(11), dp(16)) }
        col.add(titleRow()); col.add(hero(50f), dp(6)); col.add(bannerView(), dp(4))
        val ts = tiles(false)
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r1.addView(ts[0], LinearLayout.LayoutParams(0, -1, 1f)); r1.addView(ts[1], LinearLayout.LayoutParams(0, -1, 1f))
        r2.addView(ts[2], LinearLayout.LayoutParams(0, -1, 1f)); r2.addView(ts[3], LinearLayout.LayoutParams(0, -1, 1f))
        col.add(r1); col.add(r2)
        col.add(graphCard(160, false)); col.add(stripCard(50)); col.add(controls())
        updChips()
        return ScrollView(this).apply { fitsSystemWindows = true; isVerticalScrollBarEnabled = false; addView(col) }
    }

    private fun buildInner(): View {
        val rootL = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setPadding(dp(13), dp(6), dp(13), dp(10)) }
        rootL.add(titleRow())
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(graphCard(0, true), LinearLayout.LayoutParams(0, -1, 65f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.add(hero(54f), dp(4)); panel.add(bannerView(), dp(6))
        tiles(true).forEach { panel.add(it, 0, 0, weight = 1f) }
        top.addView(panel, LinearLayout.LayoutParams(0, -1, 35f).apply { leftMargin = dp(4) })
        rootL.add(top, dp(4), 0, weight = 1f)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(stripCard(96), LinearLayout.LayoutParams(0, -2, 1.15f))
        bottom.addView(controls(), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        rootL.add(bottom, dp(2))
        updChips()
        return rootL
    }

    private fun onToggle() {
        if (Monitor.enabled) BrakeService.stop(this) else BrakeService.start(this)
    }

    private fun setKeepOn(on: Boolean) {
        if (on == keepOn) return
        keepOn = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------- per-frame animation ----------
    override fun doFrame(ns: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val dt = if (lastNs == 0L) 0.016f else ((ns - lastNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastNs = ns
        val m = Monitor; val c = m.ctl; val st = m.state
        val k = 1f - exp(-dt / 0.35f); val k2 = 1f - exp(-dt / 0.6f)
        dGlow = lerpColor(dGlow, Look.color(st), k2)
        dNum += (m.avgMw - dNum) * k
        dNumCol = lerpColor(dNumCol, numColor(dNum), k2)
        dBurn += (c.burn - dBurn) * k
        dTemp += (m.temp - dTemp) * k
        val sur = m.avgMw + m.burnMw; dSur += (sur - dSur) * k * 0.6f

        num.text = fmtSigned(dNum); num.setTextColor(dNumCol); num.setShadowLayer(dpf(16f), 0f, 0f, withAlpha(dNumCol, 0.55f))
        unitView?.setTextColor(dNumCol)
        raw.text = String.format(Locale.US, "raw %s mW · %s mA · %.3f V · sensor %.1f Hz", fmtSigned(m.mW), fmtSigned(m.mA), m.volt, m.rateHz)

        bTitle.text = Look.title(st, m); bSub.text = Look.sub(st, m); bIcon.text = Look.glyph(st)
        bTitle.setTextColor(dGlow); bTitle.setShadowLayer(dpf(8f), 0f, 0f, withAlpha(dGlow, 0.6f)); bIcon.setTextColor(dGlow)
        val pulse = 0.5f + 0.5f * kotlin.math.sin(System.nanoTime() / 1e9 * 2 * Math.PI).toFloat()
        bIcon.background = round(withAlpha(dGlow, 0.14f + 0.12f * pulse), dpf(16f))
        bIgnore.visibility = if (st == St.FAST) View.VISIBLE else View.GONE
        banner.glowColor = dGlow; banner.glow = 0.7f + 0.3f * pulse; banner.tint = 1.4f; banner.stroke = 1f; banner.invalidate()

        batV.text = "${m.pct}%"
        batS.text = if (m.mah.isNaN()) "— mAh" else fmtInt(m.mah) + " mAh" + (if (m.enabled && !c.targetMah.isNaN()) " · target " + fmtInt(c.targetMah) else "")
        batSrc.text = "source: " + m.mahT.source
        brkV.text = "${(dBurn * 100).toInt()}%"; brkBar.frac = dBurn; brkBar.invalidate()
        brkS.text = "${m.threads} threads · low priority"
        brkTile.glowColor = C_ORANGE; brkTile.glow = (dBurn / 0.8f).coerceIn(0f, 1f) * 0.8f; brkTile.stroke = brkTile.glow; brkTile.invalidate()
        surV.text = if (m.plugged == 0) "n/a" else "~" + fmtSigned(dSur) + " mW"
        surS.text = if (m.plugged == 0) "not plugged in" else "net + brake power"
        tmpV.text = String.format(Locale.US, "%.1f °C", dTemp)
        tmpBar.frac = ((dTemp - 25f) / (m.cfg.tempCutoffC - 25f)); tmpBar.invalidate()
        tmpS.text = String.format(Locale.US, "cutoff %.0f °C", m.cfg.tempCutoffC)

        gCard.glowColor = dGlow; gCard.glow = 0.55f; gCard.tint = 0.6f; gCard.stroke = 0.8f; gCard.invalidate()
        gTitle.text = "Power · last ${chart.label()}"
        chart.glowColor = dGlow; chart.headColor = dNumCol; chart.invalidate(); strip.invalidate()
        stripNow.text = if (m.mah.isNaN()) "${m.pct}%" else "now ${fmtInt(m.mah)} mAh · ${m.pct}%"

        pill.pos += ((if (m.enabled) 1f else 0f) - pill.pos) * (1f - exp(-dt / 0.12f)); pill.invalidate()
        brakeTitle.text = if (m.enabled) "Brake ON" else "Brake OFF"
        brakeCard.glowColor = dGlow; brakeCard.glow = if (m.enabled) 0.6f else 0f; brakeCard.tint = if (m.enabled) 0.8f else 0f; brakeCard.stroke = brakeCard.glow; brakeCard.invalidate()
        val plugged = m.plugged != 0
        setKeepOn(plugged)
        screenOn.text = if (plugged) "☀ Screen stays on while plugged" else "Screen on: only while plugged in"
        screenOn.setTextColor(if (plugged) C_GREEN else C_DIM)
        updChips()
        dots.forEach { it.invalidate() }

        if (bgColor == 0 || dGlow != bgColor) {
            bgColor = dGlow
            root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(dGlow, 0.10f), C_BG, C_BG)).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = root.width.coerceAtLeast(1) * 0.9f; setGradientCenter(0.75f, 0f)
                colors = intArrayOf(withAlpha(dGlow, 0.11f), C_BG)
            }
        }
    }
}
