package com.fellowabhi.chargebrake

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
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
import kotlin.math.roundToInt

class MainActivity : Activity(), Choreographer.FrameCallback {
    // views (rebuilt on fold/unfold)
    private lateinit var root: ScrollView
    private lateinit var num: TextView
    private lateinit var raw: TextView
    private lateinit var useLine: TextView
    private lateinit var banner: GlowFrame
    private lateinit var bIcon: TextView
    private lateinit var bTitle: TextView
    private lateinit var bSub: TextView
    private lateinit var bAction: TextView
    private lateinit var batV: TextView; private lateinit var batS: TextView; private lateinit var batSrc: TextView
    private lateinit var brkTile: GlowFrame; private lateinit var brkV: TextView; private lateinit var brkBar: BarView; private lateinit var brkS: TextView
    private lateinit var surV: TextView; private lateinit var surS: TextView
    private lateinit var qTile: GlowFrame; private lateinit var qKey: View; private lateinit var qV: TextView; private lateinit var qS: TextView
    private lateinit var tmpV: TextView; private lateinit var tmpBar: BarView; private lateinit var tmpS: TextView
    private lateinit var gCard: GlowFrame; private lateinit var gTitle: TextView; private lateinit var chart: ChartView
    private lateinit var cCard: GlowFrame; private lateinit var cTitle: TextView; private lateinit var cNow: TextView; private lateinit var charge: ChargeView
    private lateinit var brakeCard: GlowFrame; private lateinit var brakeTitle: TextView; private lateinit var screenOn: TextView
    private lateinit var pill: PillSwitch
    private lateinit var targetRow: FlowRow
    private val dots = ArrayList<View>()
    private var wide = false
    private var unitView: TextView? = null

    // eased display values (survive rebuilds)
    private var dNum = 0f; private var dNumCol = C_GREEN; private var dGlow = C_GREY
    private var dBurn = 0f; private var dTemp = 0f; private var dSur = 0f; private var dUse = Float.NaN
    private var lastNs = 0L; private var running = false
    private var bgColor = 0; private var keepOn = false
    private var targetKey = ""
    private var lastH = 0

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
        val cfg = resources.configuration
        wide = cfg.screenWidthDp >= 600
        dots.clear(); bgColor = 0; targetKey = ""; lastH = 0
        val content = if (wide) buildInner() else buildCover()
        root = ScrollView(this).apply { fitsSystemWindows = true; isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }
        // size the graphs from the real viewport height (known after the first layout)
        root.addOnLayoutChangeListener { v, _, t, _, b, _, _, _, _ -> val h = b - t - v.paddingTop - v.paddingBottom; if (h > 0 && h != lastH) { lastH = h; v.post { sizeGraphs(h) } } }
        sizeGraphs(dp(cfg.screenHeightDp - 30))
        setContentView(root)
    }

    /** Main graph ≈ 50 % of the viewport height (never below a readable minimum); the charge graph takes the rest. */
    private fun sizeGraphs(hPx: Int) {
        val gH: Int; val cH: Int
        if (wide) {
            gH = maxOf((hPx * 0.5f).toInt(), dp(250))
            cH = maxOf(hPx - gH - dp(34), dp(190))
        } else {
            gH = (hPx * 0.42f).toInt().coerceIn(dp(250), maxOf(dp(250), hPx / 2))
            cH = (hPx * 0.30f).toInt().coerceIn(dp(190), dp(280))
        }
        setH(gCard, gH); setH(cCard, cH)
        root.postDelayed({ checkClipping() }, 600)
    }
    private fun setH(v: View, h: Int) { val lp = v.layoutParams ?: return; if (lp.height != h) { lp.height = h; v.layoutParams = lp } }

    // ---------- pieces ----------
    private fun liveTag(): View {
        val r = row(PulseDot(this).also { dots += it }, text(11f, 0xFFFF6B6B.toInt(), bold = true, txt = "LIVE"), gap = 4)
        r.getChildAt(0).layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        return r
    }

    private fun titleRow(): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        r.addView(text(13f, C_DIM, txt = "CHARGE BRAKE").apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(text(11f, C_FAINT, txt = "v${Monitor.VERSION}   "))
        r.addView(liveTag())
        return r
    }

    private fun hero(big: Float): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        col.add(text(13f, C_DIM, txt = "Net into battery · 1.5 s average"))
        num = text(big, C_GREEN, bold = true, txt = "+0").apply { maxLines = 1 }
        val unit = text(big * 0.46f, C_GREEN, bold = true, txt = " mW").apply { maxLines = 1 }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        r.addView(num); r.addView(unit, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
        unitView = unit
        col.add(r, dp(2))
        raw = text(12f, C_DIM); col.add(raw, dp(2))
        useLine = text(12f, C_DIM); col.add(useLine, dp(2))
        return col
    }

    private fun bannerView(): View {
        banner = GlowFrame(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(dp(12), dp(10), dp(12), dp(10)) }
        bIcon = text(17f, C_ORANGE, bold = true, txt = "×").apply { gravity = Gravity.CENTER }
        banner.addView(bIcon, LinearLayout.LayoutParams(dp(32), dp(32)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bTitle = text(16f, C_ORANGE, bold = true)
        bSub = text(12.5f, 0xFFD9DCE1.toInt())
        t.add(bTitle); t.add(bSub, dp(3))
        banner.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        bAction = text(17f, 0xFF1A0E08.toInt(), bold = true, txt = "Start").apply {
            gravity = Gravity.CENTER; setPadding(dp(22), dp(12), dp(22), dp(12)); minWidth = dp(96)
            setOnClickListener {
                when (Look.action(Monitor.state)) { "Start" -> BrakeService.start(this@MainActivity); "Resume" -> Monitor.setPaused(false); "Ignore" -> Monitor.ignoreFast() }
            }
        }
        banner.addView(bAction, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        return banner
    }

    /** Hold-current / Target chips plus a − / + stepper for the target. */
    private fun targetControls(): View {
        targetRow = FlowRow(this, dp(8), dp(6)).apply { setPadding(dp(4), 0, dp(4), 0) }
        return targetRow
    }
    private fun updTarget() {
        val cfg = Monitor.cfg
        val key = "${cfg.holdCurrent}/${Monitor.ctl.holdPct}/${Monitor.ctl.hold()}/${cfg.targetPct}/${Monitor.pct}/${Monitor.enabled}"
        if (key == targetKey) return
        targetKey = key
        targetRow.removeAllViews()
        // locked value while the brake is on; tapping it (again) re-locks at the current level
        val lbl = if (Monitor.enabled && cfg.holdCurrent) "Hold ${Monitor.ctl.holdPct}%  ↻" else "Hold current (${Monitor.pct}%)"
        targetRow.addView(chip(lbl, cfg.holdCurrent) {
            if (Monitor.enabled) Monitor.relockHold() else if (!cfg.holdCurrent) { cfg.holdCurrent = true; Monitor.changed("hold_mode", "current") }
            targetKey = ""
        })
        val step = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = if (!cfg.holdCurrent) round(0xFF123A40.toInt(), dpf(99f), 0xFF1F6E77.toInt(), dp(1)) else round(0, dpf(99f), C_LINE, dp(1)) }
        fun btn(s: String, d: Int) = text(18f, C_TXT, bold = true, txt = s).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(4), dp(14), dp(6))
            setOnClickListener { setTarget(cfg.targetPct + d) }
        }
        step.addView(btn("−", -1))
        step.addView(text(13f, if (!cfg.holdCurrent) 0xFF7FE3EE.toInt() else C_DIM, txt = "Target ${cfg.targetPct}%").apply {
            setPadding(dp(4), dp(7), dp(4), dp(7))
            setOnClickListener { if (cfg.holdCurrent) { cfg.holdCurrent = false; Monitor.changed("hold_mode", "target", true); targetKey = "" } }
        })
        step.addView(btn("+", +1))
        targetRow.addView(step)
    }
    private fun setTarget(v: Int) {
        val cfg = Monitor.cfg
        cfg.targetPct = v.coerceIn(20, 100)
        val modeChanged = cfg.holdCurrent
        cfg.holdCurrent = false
        if (modeChanged) Monitor.changed("hold_mode", "target") 
        Monitor.changed("target_pct", cfg.targetPct, true); targetKey = ""
    }

    private fun key(label: String, color: Int) = row(View(this).apply { background = round(color, dpf(2f)) }, text(12f, C_DIM, txt = label), gap = 6)
        .also { it.getChildAt(0).layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)) }

    /** Auto-sizing single-line value: shrinks instead of clipping. */
    private fun value(maxSp: Float) = text(maxSp, C_TXT, bold = true).apply {
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(11, maxSp.roundToInt(), 1, TypedValue.COMPLEX_UNIT_SP)
    }

    /** The 4 tiles as a 2×2 grid. */
    private fun tilesGrid(vs: Float): View {
        fun tile(label: String, c: Int, v: TextView, vararg sub: View): GlowFrame {
            val f = GlowFrame(this).apply { pad(dp(10), dp(8), dp(10), dp(9)) }
            f.add(key(label, c)); f.add(v, dp(4), dp((vs * 1.25f).roundToInt()))
            sub.forEach { f.add(it, dp(2)) }
            return f
        }
        batV = value(vs); batS = text(11.5f, C_DIM); batSrc = text(11f, C_FAINT)
        brkV = value(vs); brkBar = BarView(this).apply { color = C_ORANGE }; brkS = text(11f, C_DIM)
        surV = value(vs); surS = text(11f, C_DIM, txt = "net + brake power")
        tmpV = value(vs); tmpBar = BarView(this).apply { gradient = true }; tmpS = text(11f, C_DIM)
        fun barBox(b: BarView) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(b, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(3); bottomMargin = dp(2) }) }
        val t1 = tile("Battery", C_BLUE, batV, batS, batSrc)
        brkTile = tile("Brake level", C_ORANGE, brkV, barBox(brkBar), brkS)
        val t3 = tile("Charger surplus", C_AMBER, surV, surS)
        val t4 = tile("Temperature", C_GREY, tmpV, barBox(tmpBar), tmpS)
        // hold quality: full-width compact tile, only while holding
        qV = value(vs * 0.8f); qS = text(11f, C_DIM)
        qTile = GlowFrame(this).apply { pad(dp(10), dp(7), dp(10), dp(8)) }
        val qHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        qKey = View(this).apply { background = round(C_GREEN, dpf(2f)) }
        qHead.addView(qKey, LinearLayout.LayoutParams(dp(8), dp(8)))
        qHead.addView(text(12f, C_DIM, txt = "Hold quality"), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6); rightMargin = dp(10) })
        qHead.addView(qV, LinearLayout.LayoutParams(0, dp((vs * 0.8f * 1.25f).roundToInt()), 1f))
        qTile.add(qHead); qTile.add(qS, dp(2))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((a, b) in listOf(t1 to brkTile, t3 to t4)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(a, LinearLayout.LayoutParams(0, -1, 1f)); r.addView(b, LinearLayout.LayoutParams(0, -1, 1f))
            col.add(r)
        }
        col.add(qTile)
        return col
    }

    private fun legendItem(color: Int, label: String, kind: Int): View {
        val m = View(this).apply {
            background = when (kind) {
                1 -> round(withAlpha(C_ORANGE, 0.55f), dpf(2f))
                2 -> round(0x2E4CD07D, dpf(2f), 0xB24CD07D.toInt(), dp(1))
                3 -> round(0, dpf(1f), color, dp(1))          // dashed-ish target line
                else -> round(color, dpf(2f))
            }
        }
        val r = row(m, text(10.5f, C_DIM, txt = label), gap = 5)
        r.getChildAt(0).layoutParams = LinearLayout.LayoutParams(dp(14), when (kind) { 0 -> dp(3); 3 -> dp(2); else -> dp(9) })
        return r
    }

    private fun cardHead(title: TextView, right: View): View {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(right)
        return head
    }

    private fun graphCard(explain: Boolean): GlowFrame {
        gCard = GlowFrame(this).apply { pad(dp(10), dp(10), dp(10), dp(6)) }
        gTitle = text(14f, C_TXT, bold = true, txt = "Power · last 2 min")
        gCard.add(cardHead(gTitle, liveTag()))
        val leg = FlowRow(this, dp(12), dp(4))
        listOf(legendItem(0xFFFFFFFF.toInt(), "net mW, 1.5 s avg (left)", 0), legendItem(0x66FFFFFF, "raw", 0),
            legendItem(0, "brake % (right)", 1), legendItem(0, "net-zero band ±100 mW (when holding)", 2)).forEach { leg.addView(it) }
        gCard.add(leg, dp(6))
        chart = ChartView(this, Monitor.hist)
        gCard.add(chart, dp(6), 0, weight = 1f)
        if (explain) gCard.add(text(11f, C_FAINT, txt = "Above the band = battery charging, so the brake rises. Below = phone uses more than the charger gives, so the brake backs off. Tap to change the time span."), dp(4))
        return gCard
    }

    private fun chargeCard(): GlowFrame {
        cCard = GlowFrame(this).apply { pad(dp(10), dp(10), dp(10), dp(6)) }
        cTitle = text(14f, C_TXT, bold = true, txt = "Charge · last 1 h")
        cNow = text(11.5f, C_DIM)
        cCard.add(cardHead(cTitle, cNow))
        val leg = FlowRow(this, dp(12), dp(4))
        listOf(legendItem(C_AMBER, "charging up", 0), legendItem(C_GREEN, "holding", 0), legendItem(C_PURPLE, "draining", 0), legendItem(C_BLUE, "brake off", 0),
            legendItem(C_GREEN, "hold / target", 3)).forEach { leg.addView(it) }
        cCard.add(leg, dp(6))
        charge = ChargeView(this, Monitor.lhist)
        cCard.add(charge, dp(4), 0, weight = 1f)
        return cCard
    }

    private fun controls(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brakeCard = GlowFrame(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(dp(14), dp(9), dp(14), dp(9)) }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brakeTitle = text(17f, C_TXT, bold = true, txt = "Brake OFF")
        screenOn = text(12f, C_GREEN, txt = "☀ Screen stays on while plugged")
        t.add(brakeTitle); t.add(screenOn, dp(3))
        brakeCard.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        pill = PillSwitch(this); brakeCard.addView(pill, LinearLayout.LayoutParams(dp(60), dp(34)).apply { leftMargin = dp(8) })
        brakeCard.setOnClickListener { if (Monitor.enabled) BrakeService.stop(this) else BrakeService.start(this) }
        col.add(brakeCard)
        val det = GlowFrame(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(dp(14), dp(9), dp(14), dp(9)) }
        det.addView(text(14f, C_TXT, txt = "Details"), LinearLayout.LayoutParams(-2, -2))
        det.addView(text(13f, C_DIM, txt = " · settings, sensor, log"), LinearLayout.LayoutParams(0, -2, 1f))
        det.addView(text(14f, C_DIM, txt = "›"))
        det.setOnClickListener { startActivity(Intent(this, DetailsActivity::class.java)) }
        col.add(det)
        return col
    }

    private fun buildCover(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), dp(6), dp(11), dp(16)) }
        val w = resources.configuration.screenWidthDp
        col.add(titleRow()); col.add(hero((w / 7.2f).coerceIn(34f, 50f)), dp(6)); col.add(bannerView(), dp(4))
        col.add(targetControls(), dp(2)); col.add(tilesGrid(22f), dp(4))
        col.add(graphCard(false), 0, dp(260)); col.add(chargeCard(), 0, dp(200)); col.add(controls())
        updTarget()
        return col
    }

    private fun buildInner(): View {
        val w = resources.configuration.screenWidthDp
        val rootL = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(6), dp(13), dp(10)) }
        rootL.add(titleRow())
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.add(graphCard(true), 0, dp(300)); left.add(chargeCard(), 0, dp(200))
        top.addView(left, LinearLayout.LayoutParams(0, -2, 62f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val panelW = (w - 26) * 0.38f
        panel.add(hero((panelW / 5.8f).coerceIn(34f, 54f)), dp(4)); panel.add(bannerView(), dp(6))
        panel.add(targetControls(), dp(2)); panel.add(tilesGrid(24f), dp(4)); panel.add(controls())
        top.addView(panel, LinearLayout.LayoutParams(0, -2, 38f).apply { leftMargin = dp(4) })
        rootL.add(top, dp(4))
        updTarget()
        return rootL
    }

    private fun setKeepOn(on: Boolean) {
        if (on == keepOn) return
        keepOn = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Debug aid: logs any text that does not fit its view (tag CBClip). */
    private fun checkClipping() {
        var bad = 0
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE && v.width > 0) {
                val l = v.layout
                if (l != null) {
                    val availW = v.width - v.totalPaddingLeft - v.totalPaddingRight
                    val availH = v.height - v.totalPaddingTop - v.totalPaddingBottom
                    var maxLine = 0f; for (i in 0 until l.lineCount) maxLine = maxOf(maxLine, l.getLineWidth(i))
                    val ell = (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
                    if (maxLine > availW + 1 || l.height > availH + 1 || ell) { bad++; Log.w("CBClip", "clipped '${v.text}' text ${maxLine.toInt()}x${l.height} in ${availW}x$availH") }
                }
            }
            if (v is ViewGroup && v.visibility == View.VISIBLE) for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c.visibility == View.VISIBLE && v !is ScrollView && (c.right > v.width + 1 || c.bottom > v.height + 1)) { bad++; Log.w("CBClip", "child ${c.javaClass.simpleName} ${c.right}x${c.bottom} outside ${v.javaClass.simpleName} ${v.width}x${v.height}") }
                walk(c)
            }
        }
        walk(root)
        val cfg = resources.configuration
        Log.i("CBClip", "check ${cfg.screenWidthDp}x${cfg.screenHeightDp} dp wide=$wide viewport=${(lastH / resources.displayMetrics.density).toInt()} dp: $bad problems")
    }

    /** Dev aid: if <cache>/snap exists (root only), save the rendered UI every 10 s to the app's external files dir. */
    private var lastSnap = 0L
    private fun maybeSnap() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSnap < 10_000) return
        lastSnap = now
        if (!java.io.File(cacheDir, "snap").exists() || root.width == 0) return
        runCatching {
            val dir = getExternalFilesDir(null) ?: return
            val c = root.getChildAt(0)
            fun save(v: View, w: Int, h: Int, name: String) {
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp); cv.drawColor(C_BG); root.background?.let { it.setBounds(0, 0, w, h); it.draw(cv) } ?: cv.drawColor(C_BG)
                v.draw(cv)
                java.io.FileOutputStream(java.io.File(dir, name)).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            val tag = "${resources.configuration.screenWidthDp}x${resources.configuration.screenHeightDp}"
            save(c, c.width, maxOf(c.height, root.height), "snap-$tag-full.png")
        }
    }

    // ---------- per-frame animation ----------
    override fun doFrame(ns: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val dt = if (lastNs == 0L) 0.016f else ((ns - lastNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastNs = ns
        val m = Monitor; val c = m.ctl; val st = m.state
        val k = 1f - exp(-dt / 0.25f); val k2 = 1f - exp(-dt / 0.6f)
        dGlow = lerpColor(dGlow, Look.color(st), k2)
        dNum += (m.avgMw - dNum) * k
        dNumCol = lerpColor(dNumCol, numColor(dNum), k2)
        dBurn += (c.burn - dBurn) * k
        dTemp += (m.temp - dTemp) * k
        val sur = m.avgMw + m.burnMw; dSur += (sur - dSur) * k * 0.6f
        val pu = m.phoneUse.let { if (it.isNaN()) it else it.coerceAtLeast(0f) }; dUse = if (pu.isNaN()) Float.NaN else if (dUse.isNaN()) pu else dUse + (pu - dUse) * k * 0.5f

        num.text = fmtSigned(dNum); num.setTextColor(dNumCol); num.setShadowLayer(dpf(16f), 0f, 0f, withAlpha(dNumCol, 0.55f))
        unitView?.setTextColor(dNumCol)
        raw.text = String.format(Locale.US, "raw %s mW · %s mA · %.3f V · %.1f Hz", fmtSigned(m.mW), fmtSigned(m.mA), m.volt, m.rateHz)
        useLine.text = when {
            !dUse.isNaN() && m.plugged == 0 -> "Phone use (est.) ~${fmtInt(dUse)} mW · on battery"
            !dUse.isNaN() -> "Phone use (est.) ~${fmtInt(dUse)} mW · charger ~${fmtInt(m.chargerEst)} mW (est.)"
            else -> "Phone use (est.): unplug once to calibrate"
        }

        bTitle.text = Look.title(st, m); bSub.text = Look.sub(st, m); bIcon.text = Look.glyph(st)
        bTitle.setTextColor(dGlow); bTitle.setShadowLayer(dpf(8f), 0f, 0f, withAlpha(dGlow, 0.6f)); bIcon.setTextColor(dGlow)
        val pulse = 0.5f + 0.5f * kotlin.math.sin(System.nanoTime() / 1e9 * 2 * Math.PI).toFloat()
        bIcon.background = round(withAlpha(dGlow, 0.14f + 0.12f * pulse), dpf(16f))
        val act = Look.action(st)
        if (act == null) bAction.visibility = View.GONE else {
            bAction.visibility = View.VISIBLE
            if (bAction.text != act) bAction.text = act
            val bc = if (act == "Ignore") C_GREY else C_ORANGE
            bAction.background = round(lerpColor(bc, 0xFFFFFFFF.toInt(), 0.10f * pulse), dpf(99f))
        }
        banner.glowColor = dGlow; banner.glow = 0.7f + 0.3f * pulse; banner.tint = 1.4f; banner.stroke = 1f; banner.invalidate()

        batV.text = "${m.pct}%"
        batS.text = if (m.mah.isNaN()) "— mAh" else fmtInt(m.mah) + " mAh" + (if (m.enabled && !c.targetMah.isNaN()) " · target " + fmtInt(c.targetMah) else "")
        batSrc.text = "source: " + m.mahT.source
        brkV.text = "${(dBurn * 100).toInt()}%"; brkBar.frac = dBurn; brkBar.invalidate()
        brkS.text = "${m.threads} threads · ~${fmtInt(m.burnMw)} mW"
        brkTile.glowColor = C_ORANGE; brkTile.glow = (dBurn / 0.8f).coerceIn(0f, 1f) * 0.8f; brkTile.stroke = brkTile.glow; brkTile.invalidate()
        surV.text = if (m.plugged == 0) "n/a" else "~" + fmtSigned(dSur) + " mW"
        surS.text = if (m.plugged == 0) "not plugged in" else "net + brake power"
        tmpV.text = String.format(Locale.US, "%.1f °C", dTemp)
        tmpBar.frac = ((dTemp - 25f) / (m.cfg.tempCutoffC - 25f)); tmpBar.invalidate()
        tmpS.text = String.format(Locale.US, "cutoff %.0f °C", m.cfg.tempCutoffC)

        if (m.holdingNow) {
            qTile.visibility = View.VISIBLE
            val qc = Quality.color(m.minPerPct)
            qV.text = Quality.value(m); qV.setTextColor(qc); qS.text = Quality.sub(m)
            qKey.background = round(qc, dpf(2f))
            qTile.glowColor = qc; qTile.glow = if (m.minPerPct < 10f) 0.6f else 0.25f; qTile.stroke = qTile.glow; qTile.invalidate()
        } else if (qTile.visibility != View.GONE) qTile.visibility = View.GONE

        gCard.glowColor = dGlow; gCard.glow = 0.55f; gCard.tint = 0.6f; gCard.stroke = 0.8f; gCard.invalidate()
        gTitle.text = "Power · last ${chart.label()}"
        chart.glowColor = dGlow; chart.headColor = dNumCol; chart.invalidate()
        cTitle.text = "Charge · last ${charge.label()}"
        cNow.text = if (m.mah.isNaN()) "${m.pct}%" else "${fmtInt(m.mah)} mAh · ${m.pct}% · ${m.mahT.source}"
        charge.invalidate()

        pill.pos += ((if (m.enabled) 1f else 0f) - pill.pos) * (1f - exp(-dt / 0.12f)); pill.invalidate()
        brakeTitle.text = if (m.enabled) "Brake ON" else "Brake OFF"
        brakeCard.glowColor = dGlow; brakeCard.glow = if (m.enabled) 0.6f else 0f; brakeCard.tint = if (m.enabled) 0.8f else 0f; brakeCard.stroke = brakeCard.glow; brakeCard.invalidate()
        val plugged = m.plugged != 0
        setKeepOn(plugged)
        screenOn.text = if (plugged) "☀ Screen stays on while plugged" else "Screen on: only while plugged in"
        screenOn.setTextColor(if (plugged) C_GREEN else C_DIM)
        updTarget()
        dots.forEach { it.invalidate() }
        maybeSnap()

        if (bgColor == 0 || dGlow != bgColor) {
            bgColor = dGlow
            root.background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = root.width.coerceAtLeast(1) * 0.9f; setGradientCenter(0.75f, 0f)
                colors = intArrayOf(withAlpha(dGlow, 0.11f), C_BG)
            }
        }
    }
}
