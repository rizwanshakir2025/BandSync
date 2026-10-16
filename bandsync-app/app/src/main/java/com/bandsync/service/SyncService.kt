package com.bandsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.bandsync.session.SessionManager

/**
 * Foreground service: keeps the session (and the Wi-Fi radio) alive when
 * the screen is locked. Holds a partial wake lock + high-perf Wi-Fi lock;
 * [SessionManager] owns the actual session so Activities can come and go.
 */
class SyncService : Service() {

    companion object {
        const val ACTION_HOST = "com.bandsync.action.HOST"
        const val ACTION_JOIN = "com.bandsync.action.JOIN"
        const val ACTION_STOP = "com.bandsync.action.STOP"

        fun start(ctx: Context, action: String) {
            val i = Intent(ctx, SyncService::class.java).setAction(action)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SyncService::class.java).setAction(ACTION_STOP))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("bandsync", "BandSync session", NotificationManager.IMPORTANCE_LOW)
        )
        val notif: Notification = Notification.Builder(this, "bandsync")
            .setContentTitle("BandSync session active")
            .setContentText("Keeping every phone on the same click")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            @Suppress("DEPRECATION")
            startForeground(1, notif)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BandSync:playback").apply {
            acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BandSync:wifi").apply {
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SessionManager.shutdown()
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        SessionManager.shutdown()
        try { wifiLock?.release() } catch (_: Exception) { }
        try { wakeLock?.release() } catch (_: Exception) { }
        wifiLock = null
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
