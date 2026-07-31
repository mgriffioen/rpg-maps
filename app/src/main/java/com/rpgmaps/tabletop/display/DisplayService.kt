package com.rpgmaps.tabletop.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rpgmaps.tabletop.MainActivity
import com.rpgmaps.tabletop.R
import com.rpgmaps.tabletop.RpgMapsApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the player view alive while the DM is doing something else.
 *
 * Nothing in this app ever stopped the LAN server or the Cast session, and yet
 * the TV would freeze the moment the DM switched to a dice roller or a rules
 * PDF. That is Android, not the app: a process with no foreground component is
 * a candidate for the cached-app freezer, and a frozen process cannot service
 * a socket. A foreground service is the documented way to say "this work is
 * still wanted", and the ongoing notification is the price of it.
 *
 * ### Why `connectedDevice`
 *
 * Android 14 requires every foreground service to declare a type, and the two
 * plausible ones here behave very differently. `dataSync` is capped at six
 * hours in any 24 on Android 15, which a long session can genuinely reach, and
 * the system stops the service when the budget runs out -- exactly the failure
 * this class exists to prevent, just later in the evening. `connectedDevice`
 * has no such budget, and it is the honest description: the app is driving an
 * external display. Its prerequisite is one of a short list of permissions,
 * satisfied here by CHANGE_WIFI_MULTICAST_STATE, which the app already holds
 * for Chromecast discovery.
 */
class DisplayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val app: RpgMapsApplication
        get() = application as RpgMapsApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Blank the players' screen rather than leaving the last frame up:
            // stopping deliberately means the session is over.
            app.displayHub.clearMap()
            stopSelf()
            return START_NOT_STICKY
        }

        enterForeground(buildNotification(detail = null))
        observeStatus()

        // START_STICKY would have Android restart this with a null intent
        // after a low-memory kill, at which point the hub has lost the map and
        // there is nothing to serve. Better to stay down and let opening a map
        // start it again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        scope.cancel()
        releaseWifiLock()
        super.onDestroy()
    }

    /** Mirrors the sink status into the notification, so it is worth pulling down. */
    private fun observeStatus() {
        if (statusJob?.isActive == true) return
        statusJob = scope.launch {
            app.displayHub.statuses.collectLatest { statuses ->
                val connected = statuses.filter { it.connected }
                val detail = when {
                    connected.isNotEmpty() -> connected.joinToString(", ") { it.detail }
                    else -> statuses.firstOrNull { it.detail.isNotBlank() }?.detail
                }
                notificationManager()?.notify(NOTIFICATION_ID, buildNotification(detail))
            }
        }
    }

    private fun enterForeground(notification: Notification) {
        try {
            // ServiceCompat ignores the type below API 29, where it did not
            // exist; from 34 it is mandatory and must match the manifest.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // Android 12+ refuses a background start, and Android 13+ may have
            // no notification permission. Neither is worth crashing over: the
            // app still works, it just will not survive being backgrounded.
            Log.e(TAG, "could not enter the foreground", e)
            stopSelf()
        }
    }

    private fun buildNotification(detail: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DisplayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.display_notification_title))
            .setContentText(detail ?: getString(R.string.display_notification_idle))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .addAction(0, getString(R.string.display_notification_stop), stop)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.display_notification_channel),
            // Low: this is a status line the DM can pull down for the address,
            // not something that should make a noise mid-encounter.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.display_notification_channel_description)
            setShowBadge(false)
        }
        notificationManager()?.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /**
     * Stops Wi-Fi power saving from parking the radio between fog updates.
     * A foreground service keeps the CPU scheduled, but says nothing about the
     * Wi-Fi chip, and the traffic here is bursty enough to look idle.
     */
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        wifiLock = runCatching {
            manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    companion object {
        private const val TAG = "DisplayService"
        private const val CHANNEL_ID = "player_display"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.rpgmaps.tabletop.STOP_DISPLAY"

        /**
         * Must be called while the app is in the foreground. Android 12+ throws
         * ForegroundServiceStartNotAllowedException otherwise, which is why
         * this is driven by opening a map rather than by a receiver connecting.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, DisplayService::class.java))
            }.onFailure { Log.e(TAG, "could not start the display service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DisplayService::class.java))
        }
    }
}
