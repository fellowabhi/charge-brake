package com.fellowabhi.chargebrake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
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
    const val VERSION = "0.3"
    const val VERSION_CODE = 3
    const val TICK_MS = 250L
    val cfg = Cfg()
    val ctl = Controller(cfg)
    val burner = Burner()
    val mahT = MahTracker()
    val hist = History(1200)                 // 5 min @ 250 ms
    val lhist = ChargeHistory(5400)          // 3 h @ 2 s (charge graph)
    const val AVG_S = 1.5f                   // display smoothing of the big number / white line
    private val log = ArrayDeque<String>()   // 1 Hz rows + events, last ~4 h
    private const val LOG_CAP = 15_000
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var enabled = false; private set
    var threads = 4
    var invert = false
    var ratedMah = 4860f; var ratedSrc = "spec"
    private var holdSrc = ""; private var savedHoldMah = Float.NaN
    // detection
    var unitUa = false; var unitKnown = false
    var flip = false; var signKnown = false
    private var maxAbs = 0
    private var pos = 0; private var neg = 0
    private val zf = ZeroFilter()

    // live values
    @Volatile var raw = 0; var rawAvg = 0; var supported = true
    @Volatile var mA = 0f; var volt = 0f; var mW = 0f; var temp = 0f
    @Volatile var avgMw = 0f                 // 1.5 s average for the big number
    // phone-use estimate
    @Volatile var phoneUse = Float.NaN; @Volatile var chargerEst = Float.NaN
    var useBase = Float.NaN                  // phone use measured while unplugged (persisted)
    private var surSlow = Float.NaN; private var lastPlugged = -1; private var lastL = 0L
    @Volatile var pct = 0; var plugged = 0; var status = 0; var health = 0
    var chargeCounter = 0; var maxChgUa = -1; var maxChgUv = -1
    @Volatile var rateHz = 0f; var mwOk = false; var lastChange = 0L
    private val changes = ArrayDeque<Long>()
    private var lastLog = 0L
    private var t0 = 0L
    private var lastTick = 0L

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

    val state: St get() = if (!enabled) St.OFF else ctl.st
    val burnMw: Float get() = ctl.burn * cfg.burnMwFull
    val mah: Float get() = mahT.mah

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        prefs = app.getSharedPreferences("cb", Context.MODE_PRIVATE)
        powerProfileMah(app)?.let { ratedMah = it; ratedSrc = "PowerProfile" }
        with(prefs) {
            cfg.mode = getInt("mode", Mode.AUTO); cfg.holdCurrent = getBoolean("holdCur", true)
            cfg.targetPct = getInt("tPct", 60); cfg.targetMw = getInt("tMw", 100)
            cfg.fastCutoffMw = getInt("fast2", 6000); cfg.tempCutoffC = getFloat("temp2", 45f)
            cfg.aggr = getInt("aggr", 3); cfg.maxBurn = getFloat("maxBurn", 0.8f); cfg.drainSpeed = getInt("drain", Drain.NORMAL)
            cfg.capacityMah = getFloat("cap", ratedMah)
            threads = getInt("threads", 4); invert = getBoolean("invert", false)
            unitUa = getBoolean("ua", false); unitKnown = getBoolean("uaKnown", false)
            flip = getBoolean("flip", false); signKnown = getBoolean("signKnown", false)
            ctl.holdPct = getInt("hold", -1); ctl.holdMah = getFloat("holdMah", Float.NaN); holdSrc = getString("holdSrc", "") ?: ""; useBase = getFloat("useBase", Float.NaN)
        }
        cfg.burnMwFull = 1500f * threads / 4f
        t0 = SystemClock.elapsedRealtime()
    }

    /** Rated capacity from the hidden PowerProfile (may be blocked; falls back to the spec value). */
    private fun powerProfileMah(c: Context): Float? = runCatching {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val pp = cls.getConstructor(Context::class.java).newInstance(c)
        (cls.getMethod("getBatteryCapacity").invoke(pp) as Double).toFloat()
    }.getOrNull()?.takeIf { it in 1000f..20000f && it != 1000f }

    fun save() {
        prefs.edit().putInt("mode", cfg.mode).putBoolean("holdCur", cfg.holdCurrent)
            .putInt("tPct", cfg.targetPct).putInt("tMw", cfg.targetMw).putInt("fast2", cfg.fastCutoffMw)
            .putFloat("temp2", cfg.tempCutoffC).putInt("aggr", cfg.aggr).putFloat("maxBurn", cfg.maxBurn).putInt("drain", cfg.drainSpeed)
            .putFloat("cap", cfg.capacityMah)
            .putInt("threads", threads).putBoolean("invert", invert)
            .putBoolean("ua", unitUa).putBoolean("uaKnown", unitKnown)
            .putBoolean("flip", flip).putBoolean("signKnown", signKnown)
            .putInt("hold", ctl.holdPct).putFloat("holdMah", ctl.holdMah).putString("holdSrc", holdSrc).putBoolean("enabled", enabled).apply()
    }

    /** A user setting changed: save, log an event row, and re-target if it affects the hold. */
    fun changed(name: String, value: Any, retarget: Boolean = false) {
        save(); event("set $name=$value")
        if (retarget) h?.post { ctl.retarget(pct, mahT.mah) }
    }

    fun resetDetection() {
        unitKnown = false; unitUa = false; signKnown = false; flip = false; maxAbs = 0; pos = 0; neg = 0; save(); event("reset detection")
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
                ctl.paused = false
                if (resume && holdSrc != mahT.source) ctl.holdMah = Float.NaN   // saved mAh is on another scale
                ctl.arm(pct, mahT.mah, keepHold = resume); holdSrc = mahT.source; savedHoldMah = ctl.holdMah
                burner.start(threads); enabled = true
                event(if (resume) "brake on (resumed, hold ${ctl.holdPct}%)" else "brake on (hold locked ${ctl.hold()}%)")
            } else if (!on && enabled) {
                enabled = false; ctl.disarm(); burner.stop(); event("brake off")
            }
            save()
        }
    }

    /** User tapped Hold current: lock the hold at the level now (the only other time is enabling the brake). */
    fun relockHold() {
        cfg.holdCurrent = true
        h?.post {
            ctl.lockHold(pct, mahT.mah); holdSrc = mahT.source; ctl.retarget(pct, mahT.mah); savedHoldMah = ctl.holdMah
            save(); event(String.format(Locale.US, "hold locked %d%% / %.1f mAh", ctl.holdPct, ctl.holdMah))
        }
    }

    fun setPaused(p: Boolean) { h?.post { ctl.paused = p; event(if (p) "pause" else "resume") } }
    fun ignoreFast() { h?.post { ctl.ignoreFast = true; event("ignore fast charger") } }

    fun changeThreads(n: Int) {
        threads = n; cfg.burnMwFull = 1500f * n / 4f; changed("threads", n)
        h?.post { if (enabled) burner.start(n) }
    }

    private fun readIntent(i: Intent) {
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (lvl >= 0 && sc > 0) pct = lvl * 100 / sc
        var v = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).toFloat()
        if (v in 1f..100f) v *= 1000f
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
        val dt = if (lastTick == 0L) 0.25f else ((now - lastTick) / 1000f).coerceIn(0.05f, 2f)
        lastTick = now
        val r0 = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        rawAvg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        supported = r0 != Int.MIN_VALUE && r0 != Int.MAX_VALUE
        val r = if (supported) zf.filter(r0) else r0
        if (r != raw) { changes.addLast(now); lastChange = now }
        raw = r
        while (changes.isNotEmpty() && now - changes.first() > 10_000) changes.removeFirst()
        rateHz = if (changes.size >= 2) (changes.size - 1) * 1000f / (changes.last() - changes.first()).coerceAtLeast(1) else 0f

        if (supported) maxAbs = maxOf(maxAbs, abs(r))
        if (!unitKnown && maxAbs > 15_000) { unitUa = true; unitKnown = true; save() }
        val ua = unitUa || maxAbs > 15_000
        val m = if (ua) r / 1000f else r.toFloat()
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
        avgMw += (mW - avgMw) * dt / (AVG_S + dt)
        estimateUse(dt)

        if (mahT.update(now, chargeCounter, mA, mwOk, pct, cfg.capacityMah) && enabled) {
            ctl.rebase(pct, mahT.mah); event("mAh source -> ${mahT.source}")
        }

        var b = 0f
        if (enabled) {
            b = ctl.step(Input(now, mW, mwOk, pct, plugged != 0, temp, mahT.mah))
            burner.level = b
            if (ctl.holdMah.toBits() != savedHoldMah.toBits()) { savedHoldMah = ctl.holdMah; holdSrc = mahT.source; save() }
        }
        hist.add(now, mW, avgMw, b, if (enabled) ctl.tgtMw else 0f, pct, mahT.mah, if (enabled) ctl.targetMah else Float.NaN)
        if (now - lastL >= 2000) { lastL = now; lhist.add(now, mahT.mah, if (enabled) ctl.targetMah else Float.NaN, pct, state) }
        if (now - lastLog >= 1000) { lastLog = now; updateDrift(); addLog(now, b, "") }
    }

    /** Phone use (est.): unplugged = −net. Plugged = charger estimate − net − brake, where the charger estimate is
     *  the slow (3 min) average surplus plus the phone use last measured on battery. */
    private fun estimateUse(dt: Float) {
        if (plugged != lastPlugged) {
            if (lastPlugged == 0 && plugged != 0 && !useBase.isNaN()) prefs.edit().putFloat("useBase", useBase).apply()
            lastPlugged = plugged; surSlow = Float.NaN
        }
        if (!mwOk) return
        if (plugged == 0) {
            useBase = if (useBase.isNaN()) -mW else useBase + (-mW - useBase) * dt / (30f + dt)
            phoneUse = -avgMw; chargerEst = Float.NaN
        } else {
            val sur = mW + burnMw
            surSlow = if (surSlow.isNaN()) sur else surSlow + (sur - surSlow) * dt / (180f + dt)
            if (useBase.isNaN()) { phoneUse = Float.NaN; chargerEst = Float.NaN; return }
            chargerEst = surSlow + useBase.coerceAtLeast(0f)
            phoneUse = chargerEst - avgMw - burnMw
        }
    }

    // ---- hold quality: 5 min mean of the net power while holding -> time to drift 1 % ----
    private val drift = DriftMeter()
    val driftMw get() = drift.mw
    val driftNoise get() = drift.noise
    val minPerPct get() = drift.minPerPct
    val driftReason get() = drift.reason
    val holdingNow get() = enabled && !ctl.paused && when (ctl.st) { St.HOLDING, St.BRAKING, St.IN_USE, St.IDLE -> true; else -> false }
    private fun updateDrift() { if (!holdingNow) drift.reset() else if (mwOk) drift.add(mW, volt, cfg.capacityMah, ctl.burn, cfg.maxBurn) }

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private const val COLS = "time,elapsed_s,event,raw_current_now,raw_current_avg,mA,V,mW,avg_mW,phone_use_est_mW,ema_mW,ema_use_mW,target_mW,burn,pct,mAh,target_mAh,mAh_source,charge_counter,temp_C,plugged,status,state,enabled,paused,hold_pct,mode,eff_mode,hold_mode,target_pct,temp_cutoff,fast_cutoff_mW,fast_avg_mW,aggr,max_burn,threads,capacity,sensor_hz,mw_ok,unit_ua,flip,invert,drain_speed,hold_drift_mW,hold_min_per_pct"

    fun event(e: String) { h?.post { addLog(SystemClock.elapsedRealtime(), ctl.burn, e) } ?: addLog(SystemClock.elapsedRealtime(), ctl.burn, e) }

    private fun addLog(now: Long, b: Float, ev: String) {
        val row = String.format(Locale.US,
            "%s,%.1f,%s,%d,%d,%.1f,%.3f,%.0f,%.0f,%.0f,%.0f,%.0f,%.0f,%.3f,%d,%.1f,%.1f,%s,%d,%.1f,%d,%d,%s,%b,%b,%d,%s,%s,%s,%d,%.1f,%d,%.0f,%d,%.2f,%d,%.0f,%.2f,%b,%b,%b,%b,%s,%.0f,%.0f",
            df.format(Date()), (now - t0) / 1000f, ev.replace(',', ';'), raw, rawAvg, mA, volt, mW, avgMw, phoneUse, ctl.emaSlow, ctl.emaUse, ctl.tgtMw, b, pct,
            mahT.mah, ctl.targetMah, mahT.source, chargeCounter, temp, plugged, status, state.name, enabled, ctl.paused, ctl.hold(),
            Mode.name(cfg.mode), Mode.name(ctl.effMode), if (cfg.holdCurrent) "current" else "target", cfg.targetPct, cfg.tempCutoffC,
            cfg.fastCutoffMw, ctl.fastMw, cfg.aggr, cfg.maxBurn, burner.threads, cfg.capacityMah, rateHz, mwOk,
            unitUa || maxAbs > 15_000, flip, invert, Drain.name(cfg.drainSpeed), driftMw, minPerPct)
        synchronized(log) { log.addLast(row); if (log.size > LOG_CAP) log.removeFirst() }
    }

    fun csv(): String = synchronized(log) {
        buildString {
            append("# Charge Brake v$VERSION (code $VERSION_CODE) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
            append(String.format(Locale.US, "# capacity %.0f mAh (rated %.0f from %s) · temp cutoff %.1f C · fast cutoff %d mW (10 s avg, 2 min retry) · avg_mW = %.1f s average · mode %s · hold %s %d%% · aggr %d · max burn %.2f · threads %d · drain %s\n",
                cfg.capacityMah, ratedMah, ratedSrc, cfg.tempCutoffC, cfg.fastCutoffMw, AVG_S, Mode.name(cfg.mode),
                if (cfg.holdCurrent) "current" else "target", ctl.hold(), cfg.aggr, cfg.maxBurn, threads, Drain.name(cfg.drainSpeed)))
            append(COLS).append('\n'); log.forEach { append(it).append('\n') }
        }
    }
    fun logRows() = synchronized(log) { log.size }
}
