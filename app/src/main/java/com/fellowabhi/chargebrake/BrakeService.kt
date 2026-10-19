package com.fellowabhi.chargebrake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale

class BrakeService : Service() {
    private var wl: PowerManager.WakeLock? = null
    private var running = false
    private var lastNote = 0L
    private var lastKey = ""
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private var bubble: BubbleOverlay? = null
    private val onTick: () -> Unit = {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNote >= 1500) { lastNote = now; post() }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Bubble setting or app visibility changed (any thread): re-check on the main thread. */
    private val onBubbleChange: () -> Unit = { main.post { if (running) { syncBubble(); lastNote = 0; post() } } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { if (!running) { stopSelf(); return START_NOT_STICKY }; Monitor.setPaused(!Monitor.ctl.paused); lastNote = 0; post(); return START_STICKY }
            ACTION_BUBBLE -> { Monitor.init(this); Monitor.setBubble(!Monitor.bubble); if (running) { syncBubble(); post(); return START_STICKY }; stopSelf(); return START_NOT_STICKY }
            ACTION_SYNC -> { if (running) { syncBubble(); post(); return START_STICKY }; stopSelf(); return START_NOT_STICKY }
        }
        nm.createNotificationChannel(NotificationChannel(CH, "Charge Brake", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        // Android 14: a sticky restart may not be allowed to go foreground (or Monitor may not be ready yet): stop cleanly instead of crashing
        Monitor.init(this)
        val fg = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, note())
        }
        if (fg.isFailure) { if (!running) { stopSelf(); return START_NOT_STICKY } }
        if (!running) {
            running = true
            Monitor.acquire(this)
            Monitor.setEnabled(true, resume = intent == null)
            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chargebrake:brake").apply { setReferenceCounted(false); acquire() }
            Monitor.listeners.add(onTick)
            Monitor.bubbleListeners.add(onBubbleChange)
        }
        syncBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) {
            running = false
            Monitor.listeners.remove(onTick)
            Monitor.bubbleListeners.remove(onBubbleChange)
            main.removeCallbacksAndMessages(null)
            bubble?.hide(); bubble = null
            Monitor.setEnabled(false)
            Monitor.release()
            runCatching { if (wl?.isHeld == true) wl?.release() }; wl = null
        }
        super.onDestroy()
    }

    /** Show / hide the floating bubble to match the setting (needs the overlay permission); hidden while the app is on screen. */
    private fun syncBubble() {
        val want = Monitor.bubble && !Monitor.appVisible && android.provider.Settings.canDrawOverlays(this)
        if (want) { if (bubble == null) bubble = BubbleOverlay(this); bubble?.show() } else { bubble?.hide(); bubble = null }
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c); bubble?.place()       // fold / unfold: back onto the edge
    }

    private fun post() {
        val n = note()
        runCatching { nm.notify(ID, n) }
    }

    private fun note(): Notification {
        val m = Monitor; val c = m.ctl; val st = m.state
        val pi = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), pi)
        val stop = PendingIntent.getService(this, 1, Intent(this, BrakeService::class.java).setAction(ACTION_STOP), pi)
        val pause = PendingIntent.getService(this, 2, Intent(this, BrakeService::class.java).setAction(ACTION_PAUSE), pi)
        val brake = (c.burn * 100).toInt()
        val title = Look.title(st, m) + when (st) { St.BRAKING, St.HOLDING, St.IDLE, St.IN_USE, St.DRAINING -> " · brake $brake%"; else -> "" }
        val sur = if (m.plugged == 0) "n/a" else "~" + fmtSigned(m.avgMw + m.burnMw) + " mW"
        val text = String.format(Locale.US, "%s mW net · surplus %s · %.1f °C · %d%%", fmtSigned(m.avgMw), sur, m.temp, m.pct)
        return Notification.Builder(this, CH)
            .setSmallIcon(Look.icon(st))
            .setColor(Look.color(st))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(Look.sub(st, m))
            .setProgress(100, brake, false)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, if (c.paused) "Resume" else "Pause", pause).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .addAction(Notification.Action.Builder(null, if (Monitor.bubble) "Hide bubble" else "Bubble", bubblePi()).build())
            .build()
    }

    /** Bubble action: toggles in the service; without the overlay permission it opens the app to ask for it. */
    private fun bubblePi(): PendingIntent {
        val pi = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (android.provider.Settings.canDrawOverlays(this)) PendingIntent.getService(this, 3, Intent(this, BrakeService::class.java).setAction(ACTION_BUBBLE), pi)
        else PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_BUBBLE, true), pi)
    }

    companion object {
        const val ID = 1
        const val CH = "brake"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "pause"
        const val ACTION_BUBBLE = "bubble"
        const val ACTION_SYNC = "sync"
        fun sync(c: Context) { if (Monitor.enabled) runCatching { c.startService(Intent(c, BrakeService::class.java).setAction(ACTION_SYNC)) } }
        fun start(c: Context) = c.startForegroundService(Intent(c, BrakeService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, BrakeService::class.java))
    }
}
