package com.fellowabhi.chargebrake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Samples the battery every 250 ms, runs the controller when enabled, keeps history + CSV log. */
object Monitor {
    const val TICK_MS = 250L
    val cfg = Cfg()
    val ctl = Controller(cfg)
    val burner = Burner()
    val hist = History(1200)                 // 5 min @ 250 ms
    private val log = ArrayDeque<String>()   // 1 Hz, last 4 h
    private const val LOG_CAP = 14_400
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var enabled = false; private set
    var threads = 4
    var invert = false
    // detection
    var unitUa = false; var unitKnown = false
    var flip = false; var signKnown = false
    private var maxAbs = 0
    private var pos = 0; private var neg = 0

    // live values
    @Volatile var raw = 0; var rawAvg = 0; var supported = true
    @Volatile var mA = 0f; var volt = 0f; var mW = 0f; var temp = 0f
    @Volatile var pct = 0; var plugged = 0; var status = 0; var health = 0
    var chargeCounter = 0; var maxChgUa = -1; var maxChgUv = -1
    @Volatile var rateHz = 0f; var mwOk = false; var lastChange = 0L
    private val changes = ArrayDeque<Long>()
    private var lastLog = 0L
    private var t0 = 0L

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var bm: BatteryManager
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private var users = 0

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = readIntent(i)
    }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        prefs = app.getSharedPreferences("cb", Context.MODE_PRIVATE)
        with(prefs) {
            cfg.mode = getInt("mode", Mode.AUTO); cfg.holdCurrent = getBoolean("holdCur", true)
            cfg.targetPct = getInt("tPct", 60); cfg.targetMw = getInt("tMw", 100)
            cfg.fastCutoffMw = getInt("fast", 2000); cfg.tempCutoffC = getFloat("temp", 38f)
            cfg.aggr = getInt("aggr", 3); cfg.maxBurn = getFloat("maxBurn", 0.8f)
            threads = getInt("threads", 4); invert = getBoolean("invert", false)
            unitUa = getBoolean("ua", false); unitKnown = getBoolean("uaKnown", false)
            flip = getBoolean("flip", false); signKnown = getBoolean("signKnown", false)
            ctl.holdPct = getInt("hold", -1)
        }
        t0 = SystemClock.elapsedRealtime()
    }

    fun save() {
        prefs.edit().putInt("mode", cfg.mode).putBoolean("holdCur", cfg.holdCurrent)
            .putInt("tPct", cfg.targetPct).putInt("tMw", cfg.targetMw).putInt("fast", cfg.fastCutoffMw)
            .putFloat("temp", cfg.tempCutoffC).putInt("aggr", cfg.aggr).putFloat("maxBurn", cfg.maxBurn)
            .putInt("threads", threads).putBoolean("invert", invert)
            .putBoolean("ua", unitUa).putBoolean("uaKnown", unitKnown)
            .putBoolean("flip", flip).putBoolean("signKnown", signKnown)
            .putInt("hold", ctl.holdPct).putBoolean("enabled", enabled).apply()
    }

    fun wasEnabled() = prefs.getBoolean("enabled", false)

    fun resetDetection() {
        unitKnown = false; unitUa = false; signKnown = false; flip = false; maxAbs = 0; pos = 0; neg = 0; save()
    }

    fun acquire(c: Context) {
        init(c)
        if (users++ > 0) return
        val th = HandlerThread("monitor").also { it.start() }
        thread = th
        val hh = Handler(th.looper); h = hh
        app.registerReceiver(rx, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, hh)?.let { readIntent(it) }
        lastChange = SystemClock.elapsedRealtime()
        hh.post(tick)
    }

    fun release() {
        if (users == 0 || --users > 0) return
        runCatching { app.unregisterReceiver(rx) }
        h?.removeCallbacksAndMessages(null)
        thread?.quitSafely(); thread = null; h = null
    }

    fun setEnabled(on: Boolean, resume: Boolean = false) {
        h?.post {
            if (on && !enabled) {
                ctl.arm(pct, keepHold = resume); burner.start(threads); enabled = true
            } else if (!on && enabled) {
                enabled = false; ctl.disarm(); burner.stop()
            }
            save()
        }
    }

    fun changeThreads(n: Int) { threads = n; save(); h?.post { if (enabled) burner.start(n) } }

    fun resetHold() { h?.post { ctl.holdPct = pct; save() } }

    private fun readIntent(i: Intent) {
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (lvl >= 0 && sc > 0) pct = lvl * 100 / sc
        var v = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).toFloat()
        if (v in 1f..100f) v *= 1000f  // some report volts
        volt = v / 1000f
        temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)
        maxChgUa = i.getIntExtra("max_charging_current", -1)
        maxChgUv = i.getIntExtra("max_charging_voltage", -1)
    }

    private val tick = object : Runnable {
        override fun run() {
            val hh = h ?: return
            hh.postDelayed(this, TICK_MS)
            sample()
            main.post { for (l in listeners) l() }
        }
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val r = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        rawAvg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        supported = r != Int.MIN_VALUE && r != Int.MAX_VALUE
        if (r != raw) { changes.addLast(now); lastChange = now }
        raw = r
        while (changes.isNotEmpty() && now - changes.first() > 10_000) changes.removeFirst()
        rateHz = if (changes.size >= 2) (changes.size - 1) * 1000f / (changes.last() - changes.first()).coerceAtLeast(1) else 0f

        // Unit: phones draw >15 mA, so any |raw| > 15000 means microamps.
        if (supported) maxAbs = maxOf(maxAbs, abs(r))
        if (!unitKnown && maxAbs > 15_000) { unitUa = true; unitKnown = true; save() }
        val ua = unitUa || maxAbs > 15_000
        val m = if (ua) r / 1000f else r.toFloat()
        // Sign: on battery the current must be negative (discharging).
        if (supported && plugged == 0 && abs(m) > 20f) {
            if (m > 0) pos++ else neg++
            if (pos + neg >= 40) {
                if (abs(pos - neg) > 20) { val f = pos > neg; if (f != flip || !signKnown) { flip = f; signKnown = true; save() } }
                pos = 0; neg = 0
            }
        }
        mA = if (flip xor invert) -m else m
        mW = mA * volt
        mwOk = supported && r != 0 && volt > 2.5f && now - lastChange < 30_000 && abs(mW) < 30_000

        var b = 0f
        if (enabled) {
            b = ctl.step(Input(now, mW, mwOk, pct, plugged != 0, temp))
            burner.level = b
        }
        hist.add(now, mW, if (enabled) ctl.emaSlow else mW, b, if (enabled) ctl.tgtMw else cfg.targetMw.toFloat(), pct)
        if (now - lastLog >= 1000) { lastLog = now; addLog(now, b) }
    }

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    const val HEADER = "time,elapsed_s,raw_current_now,raw_current_avg,mA,V,mW,ema_mW,ema_fast_mW,target_mW,burn,pct,temp_C,plugged,status,mode,eff_mode,state,enabled,hold_pct,sensor_hz,mw_ok,unit_ua,flip,invert,threads"

    private fun addLog(now: Long, b: Float) {
        val row = String.format(Locale.US, "%s,%.1f,%d,%d,%.1f,%.3f,%.0f,%.0f,%.0f,%.0f,%.3f,%d,%.1f,%d,%d,%s,%s,%s,%b,%d,%.2f,%b,%b,%b,%b,%d",
            df.format(Date()), (now - t0) / 1000f, raw, rawAvg, mA, volt, mW, ctl.emaSlow, ctl.emaFast, ctl.tgtMw, b, pct, temp,
            plugged, status, Mode.name(cfg.mode), Mode.name(ctl.effMode), ctl.state.replace(',', ' '), enabled, ctl.hold(),
            rateHz, mwOk, unitUa || maxAbs > 15_000, flip, invert, burner.threads)
        synchronized(log) { log.addLast(row); if (log.size > LOG_CAP) log.removeFirst() }
    }

    fun csv(): String = synchronized(log) { buildString { append(HEADER).append('\n'); log.forEach { append(it).append('\n') } } }
    fun logRows() = synchronized(log) { log.size }
}
