package com.kindleidle.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the server alive while the phone's screen is off.
 *
 * Three things are needed for that, and all three are easy to miss:
 *
 *  - a foreground service, or Android stops the process minutes after the
 *    screen goes off;
 *  - a Wi-Fi lock, or the radio drops to a power-saving mode that quietly
 *    stops answering the Kindle;
 *  - a partial wake lock, because a long-poll that is holding for 25 seconds
 *    has no work scheduled to keep the CPU awake.
 *
 * The Kindle checks in every 25 seconds and may sit untouched for weeks, so
 * "mostly reachable" is the same as broken here.
 */
class ServerService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        ServerController.ensureInit(this)

        // Foreground first: the notification has to be up before any slow
        // work, or Android kills the service for taking too long to show it.
        startForegroundNotification(getString(R.string.app_name))

        val started = ServerController.startServer(this)
        if (!started) {
            // The port is taken. The UI shows why; there is nothing to hold
            // the CPU awake for.
            stopEverything()
            return START_NOT_STICKY
        }

        acquireLocks()
        updateNotification()

        // START_STICKY: if Android reclaims the process overnight, the server
        // comes back on its own rather than leaving the Kindle showing a
        // stale screen until someone notices.
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        ServerController.stopServer()
        super.onDestroy()
    }

    private fun stopEverything() {
        releaseLocks()
        ServerController.stopServer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /* ---------------------------------------------------------------------
       locks
    --------------------------------------------------------------------- */

    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // HIGH_PERF, not LOW_LATENCY. LOW_LATENCY looks like the better
            // mode and is the newer constant, but it is only active while the
            // app is in the foreground -- so it stops holding the radio at
            // exactly the moment this server needs it, when the phone has been
            // put down and the Kindle is the only thing still asking. HIGH_PERF
            // is deprecated and keeps working in the background, which is the
            // property that matters here.
            @Suppress("DEPRECATION")
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "KindleIdle:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KindleIdle:cpu").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { /* already gone */ }
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { /* already gone */ }
        wifiLock = null
        wakeLock = null
    }

    /* ---------------------------------------------------------------------
       notification
    --------------------------------------------------------------------- */

    private fun channel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        getString(R.string.channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = getString(R.string.channel_desc)
                        setShowBadge(false)
                    }
                )
            }
        }
        return CHANNEL
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channel())
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun startForegroundNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val urls = ServerController.baseUrls(this)
        val text = urls.firstOrNull()?.plus("/") ?: "Running, but not on a network yet"
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.kindleidle.host.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ServerService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
