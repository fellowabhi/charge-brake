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

class BrakeService : Service() {
    private var wl: PowerManager.WakeLock? = null
    private var running = false
    private var lastNote = 0L
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val onTick: () -> Unit = {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNote > 3000) { lastNote = now; runCatching { nm.notify(ID, note()) } }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        nm.createNotificationChannel(NotificationChannel(CH, "Charge Brake", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, note())
        if (!running) {
            running = true
            Monitor.acquire(this)
            Monitor.setEnabled(true, resume = intent == null) // null = restarted by the system: keep old hold level
            wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chargebrake:brake").apply { setReferenceCounted(false); acquire() }
            Monitor.listeners.add(onTick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) {
            running = false
            Monitor.listeners.remove(onTick)
            Monitor.setEnabled(false)
            Monitor.release()
            wl?.release(); wl = null
        }
        super.onDestroy()
    }

    private fun note(): Notification {
        val m = Monitor; val c = m.ctl
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BrakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(c.state)
            .setContentText(String.format("%+.0f mW · burn %d%% · hold %d%% · %d%%", m.mW, (c.burn * 100).toInt(), c.hold(), m.pct))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ID = 1
        const val CH = "brake"
        const val ACTION_STOP = "stop"
        fun start(c: Context) = c.startForegroundService(Intent(c, BrakeService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, BrakeService::class.java))
    }
}
