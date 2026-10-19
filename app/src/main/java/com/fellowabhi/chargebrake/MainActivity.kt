package com.fellowabhi.chargebrake

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    companion object {
        const val EXTRA_BUBBLE = "bubble"
        const val MIN_FPS = 3f            // visual level 0: ~3 fps, no glows, no pulses, simple graph
        const val TOUCH_LEVEL = 0.45f     // while touching / scrolling (2.5 s): smooth enough to use
    }
    // views (rebuilt on fold/unfold)
    private lateinit var root: ScrollView
    private lateinit var frameV: android.widget.FrameLayout
    private lateinit var load: RenderLoad
    private var maxModeId = -1; private var lowModeId = -1; private var maxHz = 60f; private var refreshWant = 0
    private lateinit var mainCard: GlowFrame
    private lateinit var big: BigNumber
    private lateinit var table: RawTable
    private lateinit var bnr: BannerViews
    private lateinit var visMeter: VisualMeter
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
    private lateinit var bubPill: PillSwitch; private lateinit var bubSub: TextView
    private var pendingBubble = false
    private val tickH = android.os.Handler(android.os.Looper.getMainLooper())
    private val slowTick = Runnable { scheduled = false; onTick(android.os.SystemClock.uptimeMillis() * 1_000_000L) }
    private var scheduled = false
    private var wide = false
    private var lastTouch = 0L

    // eased display values (survive rebuilds)
    private var dNum = 0f; private var dNumCol = C_GREEN; private var dGlow = C_GREY
    private var dBurn = 0f; private var dTemp = 0f; private var dSur = 0f; private var dUse = Float.NaN
    private var dLevel = Monitor.IDLE_LEVEL
    private var lastNs = 0L; private var running = false
    private var bgColor = 0; private var keepOn = false
    private var targetKey = ""
    private var lastH = 0
    private var level = 0f; private var fps = 0f

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Monitor.init(this)
        dNum = Monitor.avgMw; dTemp = Monitor.temp
        rebuild()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (intent?.getBooleanExtra(EXTRA_BUBBLE, false) == true) toggleBubble()
    }

    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); if (i?.getBooleanExtra(EXTRA_BUBBLE, false) == true) toggleBubble() }

    override fun onResume() {
        super.onResume()
        if (pendingBubble) {
            pendingBubble = false
            if (Settings.canDrawOverlays(this)) { Monitor.setBubble(true); BrakeService.sync(this) }
            else android.widget.Toast.makeText(this, "Bubble needs \"Display over other apps\"", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Floating bubble on/off. First time: ask for "Display over other apps", then turn it on when we come back. */
    private fun toggleBubble() {
        if (Monitor.bubble) { Monitor.setBubble(false); BrakeService.sync(this); return }
        if (Settings.canDrawOverlays(this)) { Monitor.setBubble(true); BrakeService.sync(this); return }
        pendingBubble = true
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
            .onFailure { pendingBubble = false }
    }

    /**
     * One adaptive mode: the screen updates at a rate set by the visual level (part of the brake): ~3 fps at 0
     * (handler ticks), up to the display max at 1 (vsync). Touching boosts it briefly so the app stays usable.
     */
    private fun schedule() {
        if (!running || scheduled) return
        scheduled = true
        if (fps >= 20f) Choreographer.getInstance().postFrameCallback(this)
        else tickH.postDelayed(slowTick, (1000f / fps.coerceAtLeast(MIN_FPS)).toLong())
    }
    private fun unschedule() { Choreographer.getInstance().removeFrameCallback(this); tickH.removeCallbacks(slowTick); scheduled = false }
    /** Re-plan now (a touch, or a big level change): don't wait out a slow tick. */
    private fun kick() { unschedule(); lastNs = 0; schedule() }

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        val boost = android.os.SystemClock.uptimeMillis() - lastTouch > 2500
        lastTouch = android.os.SystemClock.uptimeMillis()
        if (boost && fps < 20f) { fps = 30f; kick() }
        return super.dispatchTouchEvent(e)
    }

    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); rebuild() }

    override fun onStart() { super.onStart(); Monitor.acquire(this); Monitor.setAppVisible(true); running = true; lastNs = 0; fps = 10f; schedule() }
    override fun onStop() {
        running = false; unschedule(); applyRefresh(-1f); Monitor.setAppVisible(false); Monitor.release(); setKeepOn(false); super.onStop()
    }

    private fun rebuild() {
        val cfg = resources.configuration
        wide = cfg.screenWidthDp >= 600
        bgColor = 0; targetKey = ""; lastH = 0
        val content = if (wide) buildInner() else buildCover()
        root = ScrollView(this).apply { fitsSystemWindows = true; isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }
        load = RenderLoad(this)
        frameV = android.widget.FrameLayout(this).apply { addView(load, -1, -1); addView(root, -1, -1) }
        // size the graphs from the real viewport height (known after the first layout)
        root.addOnLayoutChangeListener { v, _, t, _, b, _, _, _, _ -> val h = b - t - v.paddingTop - v.paddingBottom; if (h > 0 && h != lastH) { lastH = h; v.post { sizeGraphs(h) } } }
        sizeGraphs(dp(cfg.screenHeightDp - 30))
        setContentView(frameV)
    }

    /** Display refresh follows the visual level: lowest mode when low, the max mode when high (hysteresis), default when gone. */
    private fun applyRefresh(v: Float) {
        if (maxModeId < 0) runCatching {
            @Suppress("DEPRECATION") val d = windowManager.defaultDisplay; val cur = d.mode
            val same = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            same.maxByOrNull { it.refreshRate }?.let { maxModeId = it.modeId; maxHz = it.refreshRate }
            lowModeId = same.minByOrNull { it.refreshRate }?.modeId ?: 0
        }.onFailure { maxModeId = 0; lowModeId = 0 }
        val want = when { v < 0f -> 0; v >= 0.7f -> 2; v <= 0.45f -> 1; else -> refreshWant.coerceAtLeast(1) }
        if (want == refreshWant) return
        refreshWant = want
        window.attributes = window.attributes.apply { preferredDisplayModeId = when (want) { 2 -> maxModeId; 1 -> lowModeId; else -> 0 } }
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
    private fun titleRow(): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        r.addView(text(13f, C_DIM, txt = "CHARGE BRAKE").apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(text(11f, C_FAINT, txt = "v${Monitor.VERSION}"), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        visMeter = VisualMeter(this).apply { setOnClickListener { startActivity(Intent(this@MainActivity, DetailsActivity::class.java)) } }
        r.addView(visMeter)
        return r
    }

    /** Main value card: thin state-coloured ring; big net number with a small fixed-width table of raw values beside it. */
    private fun hero(big0: Float): View {
        mainCard = GlowFrame(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(dp(10), dp(8), dp(10), dp(8)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.add(text(11.5f, C_DIM, txt = "Net into battery · 1.5 s avg"))
        big = BigNumber(this, big0); col.add(big, dp(4))
        mainCard.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        table = RawTable(this, listOf("raw", "current", "voltage", "sensor", "phone use", "charger"), "−18,888 mW")
        mainCard.addView(table, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        return mainCard
    }

    private fun bannerView(): View {
        bnr = BannerViews(this)
        bnr.action.setOnClickListener {
            when (Look.action(Monitor.state)) { "Start" -> BrakeService.start(this@MainActivity); "Resume" -> Monitor.setPaused(false); "Ignore" -> Monitor.ignoreFast() }
        }
        return bnr.root
    }

    /** Hold-current / Target chips plus a − / + stepper for the target. */
    private fun targetControls(): View {
        targetRow = FlowRow(this, dp(8), dp(6)).apply { setPadding(dp(4), 0, dp(4), 0) }
        return targetRow
    }
    private fun updTarget() {
        val cfg = Monitor.cfg
        val m = Monitor
        val key = "${cfg.holdCurrent}/${m.ctl.holdMah}/${cfg.targetMahSet}/${cfg.targetPct}/${m.pct}/${m.enabled}/${m.targetByMah}/${if (m.enabled) 0 else (m.mah / 5).toInt()}"
        if (key == targetKey) return
        targetKey = key
        targetRow.removeAllViews()
        // locked value while the brake is on; tapping it (again) re-locks at the current level
        val lbl = if (m.enabled && cfg.holdCurrent) "Hold ${m.mahLabel(m.ctl.holdMah)}"
            else "Hold current (" + (if (m.mah.isNaN()) "${m.pct}%" else "${fmtInt(m.mah)} mAh") + ")"
        targetRow.addView(chip(lbl, cfg.holdCurrent) {
            if (Monitor.enabled) Monitor.relockHold() else if (!cfg.holdCurrent) { cfg.holdCurrent = true; Monitor.changed("hold_mode", "current") }
            targetKey = ""
        }.apply { if (m.enabled && cfg.holdCurrent) iconEnd(this, R.drawable.ic_refresh, 0xFF7FE3EE.toInt()) })
        val step = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = if (!cfg.holdCurrent) round(0xFF123A40.toInt(), dpf(99f), 0xFF1F6E77.toInt(), dp(1)) else round(0, dpf(99f), C_LINE, dp(1)) }
        fun btn(res: Int, d: Int) = android.widget.ImageView(this).apply {
            setImageResource(res); setColorFilter(C_TXT); setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener { setTarget(d) }
        }
        step.addView(btn(R.drawable.ic_minus, -1), LinearLayout.LayoutParams(dp(36), dp(34)))
        val tTxt = if (m.targetByMah && !cfg.targetMahSet.isNaN()) "Target ${fmtInt(cfg.targetMahSet)} mAh"
            else if (!cfg.holdCurrent && !cfg.targetMahSet.isNaN()) "Target ${cfg.targetPct}% · ${fmtInt(cfg.targetMahSet)} mAh" else "Target ${cfg.targetPct}%"
        step.addView(text(13f, if (!cfg.holdCurrent) 0xFF7FE3EE.toInt() else C_DIM, txt = tTxt).apply {
            setPadding(dp(4), dp(7), dp(4), dp(7))
            setOnClickListener { if (cfg.holdCurrent) { cfg.holdCurrent = false; Monitor.changed("hold_mode", "target", true); targetKey = "" } }
        })
        step.addView(btn(R.drawable.ic_plus, +1), LinearLayout.LayoutParams(dp(36), dp(34)))
        targetRow.addView(step)
    }
    /** Stepper: ±1 % (converted once to mAh) or ±10 mAh when the target is entered in mAh. */
    private fun setTarget(d: Int) {
        val m = Monitor; val cfg = m.cfg
        if (m.targetByMah && !cfg.targetMahSet.isNaN()) m.setTargetMah(cfg.targetMahSet + 10f * d)
        else m.setTargetPct((if (cfg.holdCurrent) cfg.targetPct else cfg.targetPct + d).coerceIn(20, 100))
        targetKey = ""
    }

    private fun key(label: String, color: Int) = row(View(this).apply { background = round(color, dpf(2f)) }, text(12f, C_DIM, txt = label), gap = 6)
        .also { it.getChildAt(0).layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)) }

    /**
     * Single-line value with a FIXED size: sized once (per layout width) so that the widest expected text
     * (`template`) fits. Tabular digits, so changing values never resize or shift anything.
     */
    private fun value(maxSp: Float, template: String) = text(maxSp, C_TXT, bold = true).apply {
        maxLines = 1
        addOnLayoutChangeListener { v, l, _, r, _, ol, _, or0, _ ->
            val w = r - l
            if (w > 0 && w != or0 - ol) {
                val tv = v as TextView
                val p = android.graphics.Paint(tv.paint); p.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSp, resources.displayMetrics)
                val avail = w - tv.paddingLeft - tv.paddingRight
                val tw = p.measureText(template)
                val sp = if (tw > avail) maxSp * avail / tw else maxSp
                tv.post { tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxOf(10f, sp)) }
            }
        }
    }

    /** The 4 tiles as a 2×2 grid. */
    private fun tilesGrid(vs: Float): View {
        fun tile(label: String, c: Int, v: TextView, vararg sub: View): GlowFrame {
            val f = GlowFrame(this).apply { pad(dp(10), dp(8), dp(10), dp(9)) }
            f.add(key(label, c)); f.add(v, dp(4), dp((vs * 1.25f).roundToInt()))
            sub.forEach { f.add(it, dp(2)) }
            return f
        }
        batV = value(vs, "100%"); batS = text(11.5f, C_DIM); batSrc = text(11f, C_FAINT)
        brkV = value(vs, "100%"); brkBar = BarView(this).apply { color = C_ORANGE }; brkS = text(11f, C_DIM)
        surV = value(vs, "~+18,888 mW"); surS = text(11f, C_DIM, txt = "net + brake power")
        tmpV = value(vs, "88.8 °C"); tmpBar = BarView(this).apply { gradient = true }; tmpS = text(11f, C_DIM)
        fun barBox(b: BarView) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(b, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(3); bottomMargin = dp(2) }) }
        val t1 = tile("Battery", C_BLUE, batV, batS, batSrc)
        brkTile = tile("Brake level", C_ORANGE, brkV, barBox(brkBar), brkS)
        val t3 = tile("Charger surplus", C_AMBER, surV, surS)
        val t4 = tile("Temperature", C_GREY, tmpV, barBox(tmpBar), tmpS)
        // hold quality: full-width compact tile, only while holding
        qV = value(vs * 0.8f, "~88 h 88 m per 1%"); qS = text(11f, C_DIM)
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
        gTitle = text(14f, C_TXT, bold = true, txt = "Power · last ${Monitor.spanLabel()}")
        gCard.add(cardHead(gTitle, android.widget.Space(this)))
        val leg = FlowRow(this, dp(12), dp(4))
        listOf(legendItem(0xFFFFFFFF.toInt(), "net mW, 1.5 s avg (left)", 0), legendItem(0x66FFFFFF, "raw", 0),
            legendItem(0, "brake % (right)", 1), legendItem(0, "net-zero band ±100 mW (when holding)", 2)).forEach { leg.addView(it) }
        gCard.add(leg, dp(6))
        chart = ChartView(this, Monitor.hist).apply { onSpan = { spanChanged() } }
        gCard.add(chart, dp(6), 0, weight = 1f)
        if (explain) gCard.add(text(11f, C_FAINT, txt = "Above the band = battery charging, so the brake rises. Below = phone uses more than the charger gives, so the brake backs off. Tap to change the time span."), dp(4))
        return gCard
    }

    private fun chargeCard(): GlowFrame {
        cCard = GlowFrame(this).apply { pad(dp(10), dp(10), dp(10), dp(6)) }
        cTitle = text(14f, C_TXT, bold = true, txt = "Charge · last ${Monitor.spanLabel()}")
        cNow = text(11.5f, C_DIM)
        cCard.add(cardHead(cTitle, cNow))
        val leg = FlowRow(this, dp(12), dp(4))
        listOf(legendItem(C_AMBER, "gaining", 0), legendItem(C_GREEN, "steady ±100 mW", 0), legendItem(C_BLUE, "draining", 0),
            legendItem(C_GREEN, "hold / target", 3)).forEach { leg.addView(it) }
        cCard.add(leg, dp(6))
        charge = ChargeView(this, Monitor.lhist).apply { onSpan = { spanChanged() } }
        cCard.add(charge, dp(4), 0, weight = 1f)
        return cCard
    }

    private fun controls(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brakeCard = GlowFrame(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(dp(14), dp(9), dp(14), dp(9)) }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brakeTitle = text(17f, C_TXT, bold = true, txt = "Brake OFF")
        screenOn = text(12f, C_GREEN, txt = "Screen stays on while plugged").apply { iconStart(this, R.drawable.ic_sun, C_GREEN) }
        t.add(brakeTitle); t.add(screenOn, dp(3))
        brakeCard.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        pill = PillSwitch(this); brakeCard.addView(pill, LinearLayout.LayoutParams(dp(60), dp(34)).apply { leftMargin = dp(8) })
        brakeCard.setOnClickListener { if (Monitor.enabled) BrakeService.stop(this) else BrakeService.start(this) }
        col.add(brakeCard)
        val det = GlowFrame(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(dp(14), dp(9), dp(14), dp(9)) }
        det.addView(text(14f, C_TXT, txt = "Details"), LinearLayout.LayoutParams(-2, -2))
        det.addView(text(13f, C_DIM, txt = " · settings, sensor, log"), LinearLayout.LayoutParams(0, -2, 1f))
        det.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(C_DIM) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        det.setOnClickListener { startActivity(Intent(this, DetailsActivity::class.java)) }
        col.add(det)
        val bub = GlowFrame(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; pad(dp(14), dp(8), dp(14), dp(8)) }
        val bt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bt.add(text(14f, C_TXT, txt = "Floating bubble"))
        bubSub = text(11.5f, C_DIM); bt.add(bubSub, dp(2))
        bub.addView(bt, LinearLayout.LayoutParams(0, -2, 1f))
        bubPill = PillSwitch(this).apply { color = C_TEAL }; bub.addView(bubPill, LinearLayout.LayoutParams(dp(46), dp(26)).apply { leftMargin = dp(8) })
        bub.setOnClickListener { toggleBubble() }
        col.add(bub)
        return col
    }
    private fun spanChanged() { chart.invalidate(); charge.invalidate(); gTitle.text = "Power · last ${Monitor.spanLabel()}"; cTitle.text = "Charge · last ${Monitor.spanLabel()}" }

    private fun buildCover(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), dp(6), dp(11), dp(16)) }
        val w = resources.configuration.screenWidthDp
        col.add(titleRow()); col.add(hero((w / 8.2f).coerceIn(30f, 46f)), dp(6)); col.add(bannerView(), dp(2))
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
        panel.add(hero((panelW / 7.4f).coerceIn(30f, 50f)), dp(4)); panel.add(bannerView(), dp(2))
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
        if ((0 until targetRow.childCount).any { targetRow.getChildAt(it).width == 0 }) return   // chips just rebuilt: wait for their layout
        lastSnap = now
        if (!java.io.File(cacheDir, "snap").exists() || root.width == 0) return
        runCatching {
            val dir = getExternalFilesDir(null) ?: return
            val c = root.getChildAt(0)
            fun save(v: View, w: Int, h: Int, name: String) {
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp); cv.drawColor(C_BG); frameV.background?.let { it.setBounds(0, 0, w, h); it.draw(cv) } ?: cv.drawColor(C_BG)
                if (load.level > 0.01f) { load.draw(cv) }
                v.draw(cv)
                java.io.FileOutputStream(java.io.File(dir, "$name.tmp")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                java.io.File(dir, "$name.tmp").renameTo(java.io.File(dir, name))
            }
            val tag = "${resources.configuration.screenWidthDp}x${resources.configuration.screenHeightDp}"
            save(c, c.width, maxOf(c.height, root.height), "snap-$tag-full.png")
            val sb = StringBuilder()
            fun dump(v: View, d: Int) { sb.append("  ".repeat(d)).append(v.javaClass.simpleName).append(' ').append("${v.left},${v.top} ${v.width}x${v.height} vis=${v.visibility} a=${v.alpha}")
                if (v is TextView) sb.append(" '").append(v.text.take(30)).append('\'')
                sb.append('\n'); if (v is android.view.ViewGroup) for (i in 0 until v.childCount) dump(v.getChildAt(i), d + 1) }
            dump(root, 0); java.io.File(dir, "tree-$tag.txt").writeText(sb.toString())
            // every banner state at cover / inner / narrow widths (real values for the numbers)
            for (wDp in intArrayOf(338, 317, 280)) {
                val states = St.values()
                val views = states.map { st -> BannerViews(this).also { it.bind(st, Monitor, Look.color(st), 0.5f, 1f) } }
                val w = dp(wDp); var hTot = 0
                for (bv in views) { bv.root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); hTot += bv.root.measuredHeight }
                val bmp = android.graphics.Bitmap.createBitmap(w, hTot, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp); cv.drawColor(C_BG)
                for (bv in views) { bv.root.layout(0, 0, w, bv.root.measuredHeight); bv.root.draw(cv); cv.translate(0f, bv.root.measuredHeight.toFloat()) }
                java.io.FileOutputStream(java.io.File(dir, "banners-${wDp}dp.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            // the floating bubble, rendered offscreen with its real drawing code: live data + fixed examples
            fun bub(name: String, data: BubbleData, exp: Boolean, mirror: Boolean = false) {
                val bv = BubbleView(this); bv.setExpanded(exp, animate = false); bv.mirror = mirror
                for (k in 0 until 30) bv.push(data.copy(net = data.net + 60f * kotlin.math.sin(k * 0.7f)), animate = false)
                bv.push(data, animate = false)
                val w = bv.wantW().toInt(); val h = bv.wantH().toInt()
                bv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); bv.layout(0, 0, w, h)
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp); cv.drawColor(0xFF000000.toInt()); bv.draw(cv)
                java.io.FileOutputStream(java.io.File(dir, name)).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            val live = BubbleData.from(Monitor)
            bub("bub-live-small.png", live, false); bub("bub-live-exp.png", live, true)
            val hold = BubbleData(35f, 0.62f, St.HOLDING, 60, 60, 2916f, 2918f, 37.2f, Float.POSITIVE_INFINITY, false)
            bub("bub-hold-small.png", hold, false); bub("bub-hold-exp.png", hold, true)
            bub("bub-brake-small.png", hold.copy(net = 1450f, brake = 0.97f, st = St.BRAKING, minPerPct = 22f), false)
            bub("bub-brake-exp.png", hold.copy(net = 1450f, brake = 0.97f, st = St.BRAKING, minPerPct = 22f), true, mirror = true)
            bub("bub-up-exp.png", BubbleData(2350f, 0f, St.CHARGING_UP, 54, 56, 2722f, 2625f, 35.4f, Float.NaN, false), true)
            bub("bub-up-small.png", BubbleData(4350f, 0f, St.CHARGING_UP, 54, 56, 2722f, 2625f, 35.4f, Float.NaN, false), false)
            bub("bub-drain-small.png", BubbleData(-420f, 0.38f, St.DRAINING, 61, 60, 2916f, 2958f, 39.1f, Float.NaN, false), false)
            bub("bub-drain-exp.png", BubbleData(-420f, 0.38f, St.DRAINING, 61, 60, 2916f, 2958f, 39.1f, Float.NaN, false), true)
            bub("bub-wide-small.png", hold.copy(net = -888f, brake = 0.12f, st = St.IN_USE), false)
        }
    }

    // ---------- adaptive frame loop ----------
    override fun doFrame(ns: Long) { scheduled = false; onTick(ns) }

    private fun onTick(ns: Long) {
        if (!running) return
        val minGap = (1e9f / fps.coerceAtLeast(MIN_FPS) - 4e6f).toLong()
        if (lastNs != 0L && ns - lastNs < minGap && fps >= 20f) { schedule(); return }   // vsync faster than the wanted rate: skip
        val dt = if (lastNs == 0L) 0.05f else ((ns - lastNs) / 1e9f).coerceIn(0.001f, 0.5f)
        lastNs = ns
        frame(dt)
        schedule()
    }

    private fun frame(dt: Float) {
        val m = Monitor; val c = m.ctl; val st = m.state
        // visual level: what the brake asks for (or the idle level), raised briefly while touching
        val touching = android.os.SystemClock.uptimeMillis() - lastTouch < 2500
        val want = maxOf(m.visLevel, if (touching) TOUCH_LEVEL else 0f)
        dLevel += (want - dLevel) * (1f - exp(-dt / 0.4f))
        level = dLevel.coerceIn(0f, 1f)
        fps = MIN_FPS + (maxHz - MIN_FPS) * level
        val glowK = ((level - 0.15f) / 0.3f).coerceIn(0f, 1f)          // glows / pulses fade in from 15 % to 45 %
        val anim = level > 0.3f
        val k = 1f - exp(-dt / 0.25f); val k2 = 1f - exp(-dt / 0.6f)
        dGlow = lerpColor(dGlow, Look.color(st), k2)
        dNum += (m.avgMw - dNum) * k
        dNumCol = lerpColor(dNumCol, numColor(dNum), k2)
        dBurn += (c.burn - dBurn) * k
        dTemp += (m.temp - dTemp) * k
        val sur = m.avgMw + m.burnMw; dSur += (sur - dSur) * k * 0.6f
        val pu = m.phoneUse.let { if (it.isNaN()) it else it.coerceAtLeast(0f) }; dUse = if (pu.isNaN()) Float.NaN else if (dUse.isNaN()) pu else dUse + (pu - dUse) * k * 0.5f
        val pulse = if (!anim) 0.5f else 0.5f + 0.5f * kotlin.math.sin(System.nanoTime() / 1e9 * 2 * Math.PI).toFloat()

        // main value card
        big.text = fmtSigned(dNum); big.color = dNumCol; big.glow = dpf(12f) * glowK; big.invalidate()
        mainCard.glowColor = dNumCol; mainCard.glow = 0.45f * glowK; mainCard.stroke = 1.3f; mainCard.tint = 0.5f; mainCard.invalidate()
        table.set(0, fmtSigned(m.mW) + " mW"); table.set(1, fmtSigned(m.mA) + " mA")
        table.set(2, String.format(Locale.US, "%.3f V", m.volt)); table.set(3, String.format(Locale.US, "%.1f Hz", m.rateHz))
        table.set(4, if (dUse.isNaN()) "unplug once" else "~" + fmtInt(dUse) + " mW")
        table.set(5, if (m.plugged == 0) "on battery" else if (m.chargerEst.isNaN()) "—" else "~" + fmtInt(m.chargerEst) + " mW")

        bnr.bind(st, m, dGlow, pulse, glowK)
        visMeter.level = level; visMeter.fps = fps; visMeter.invalidate()
        // the visual stage: drifting glow layers above 60 %, max refresh rate when high, lowest when low
        load.color = dGlow; val lv = ((level - 0.6f) / 0.4f).coerceIn(0f, 1f)
        if (lv > 0.005f || load.level > 0f) { load.level = lv; load.invalidate() }
        applyRefresh(level)

        batV.text = "${m.pct}%"
        batS.text = if (m.mah.isNaN()) "— mAh" else fmtInt(m.mah) + " mAh" + (if (m.enabled && !c.targetMah.isNaN()) " · target " + fmtInt(c.targetMah) else "")
        batSrc.text = "source: " + m.mahT.source
        // brake = % of the most it can do now (CPU max + visual stage); bar: teal = visual, orange = CPU
        val mo = c.maxOut.coerceAtLeast(0.01f)
        brkV.text = "${(dBurn / mo * 100).safeRound().coerceIn(0, 100)}%"; brkBar.frac = dBurn / mo; brkBar.visFrac = m.split.vis / mo; brkBar.invalidate()
        brkS.text = if (m.renderOk && m.enabled) "visual ~${fmtInt(m.visMw)} · CPU ~${fmtInt(m.cpuMw)} mW"
            else "${m.effThreads} threads${if (m.power == Monitor.P_MAX) " (normal prio)" else ""} · ~${fmtInt(m.burnMw)} mW"
        brkTile.glowColor = C_ORANGE; brkTile.glow = (dBurn / mo).coerceIn(0f, 1f) * 0.8f * glowK; brkTile.stroke = (dBurn / mo).coerceIn(0f, 1f) * 0.8f; brkTile.invalidate()
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
            qTile.glowColor = qc; qTile.glow = (if (m.minPerPct < 10f) 0.6f else 0.25f) * glowK; qTile.stroke = if (m.minPerPct < 10f) 0.6f else 0.25f; qTile.invalidate()
        } else if (qTile.visibility != View.GONE) qTile.visibility = View.GONE

        gCard.glowColor = dGlow; gCard.glow = 0.55f * glowK; gCard.tint = 0.6f; gCard.stroke = 0.8f; gCard.invalidate()
        gTitle.text = "Power · last ${m.spanLabel()}"
        chart.animate = anim; chart.simple = level < 0.12f
        chart.glowColor = dGlow; chart.headColor = dNumCol; chart.invalidate()
        cTitle.text = "Charge · last ${m.spanLabel()}"
        cNow.text = if (m.mah.isNaN()) "${m.pct}%" else "${fmtInt(m.mah)} mAh · ${m.pct}% · ${m.mahT.source}"
        charge.animate = anim; charge.invalidate()

        pill.pos += ((if (m.enabled) 1f else 0f) - pill.pos) * (1f - exp(-dt / 0.12f)); pill.invalidate()
        brakeTitle.text = if (m.enabled) "Brake ON" else "Brake OFF"
        brakeCard.glowColor = dGlow; brakeCard.glow = (if (m.enabled) 0.6f else 0f) * glowK; brakeCard.tint = if (m.enabled) 0.8f else 0f; brakeCard.stroke = if (m.enabled) 0.6f else 0f; brakeCard.invalidate()
        val plugged = m.plugged != 0
        setKeepOn(plugged)
        val so = if (plugged) "Screen stays on while plugged" else "Screen on: only while plugged in"
        if (screenOn.text != so) { screenOn.text = so; screenOn.setTextColor(if (plugged) C_GREEN else C_DIM); iconStart(screenOn, R.drawable.ic_sun, if (plugged) C_GREEN else C_DIM) }
        val bOn = m.bubble && Settings.canDrawOverlays(this)
        bubPill.pos += ((if (bOn) 1f else 0f) - bubPill.pos) * (1f - exp(-dt / 0.12f)); bubPill.invalidate()
        bubSub.text = when { !bOn -> "over other apps · tap to turn on"; m.enabled -> "on · hidden while this screen is open"; else -> "on · shows while Brake is on" }
        updTarget()
        maybeSnap()

        if (bgColor == 0 || dGlow != bgColor) {
            bgColor = dGlow
            frameV.background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = root.width.coerceAtLeast(1) * 0.9f; setGradientCenter(0.75f, 0f)
                colors = intArrayOf(withAlpha(dGlow, 0.11f), C_BG)
            }
        }
    }

    private fun iconStart(t: TextView, res: Int, col: Int) {
        val d = getDrawable(res)!!.mutate(); d.setTint(col); val s = dp(14); d.setBounds(0, 0, s, s)
        t.setCompoundDrawables(d, null, null, null); t.compoundDrawablePadding = dp(5)
    }
    private fun iconEnd(t: TextView, res: Int, col: Int) {
        val d = getDrawable(res)!!.mutate(); d.setTint(col); val s = dp(15); d.setBounds(0, 0, s, s)
        t.setCompoundDrawables(null, null, d, null); t.compoundDrawablePadding = dp(6)
    }
}
