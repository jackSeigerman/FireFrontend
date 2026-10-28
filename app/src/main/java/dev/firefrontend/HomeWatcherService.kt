package dev.firefrontend

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process

/**
 * Fallback Home redirect. Fire OS always routes the Home button to Amazon's launcher, and some
 * firmware withholds accessibility events from [HomeRedirectService]. This service polls recent usage
 * events instead and brings FireFrontend up whenever Amazon's home activity comes to the front.
 *
 * Needs usage access, granted once over adb:
 *   adb shell appops set dev.firefrontend GET_USAGE_STATS allow
 *
 * Kept separate from the launcher itself: if a firmware update breaks this, the launcher still works.
 * See [HomeRedirect] for how it shares the job with the accessibility service.
 */
class HomeWatcherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var usage: UsageStatsManager
    private lateinit var power: PowerManager
    private var lastHandled = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (power.isInteractive) checkForeground()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        lastHandled = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkForeground() {
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var amazonHomeAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND || event.timeStamp <= lastHandled) continue
            amazonHomeAt = if (HomeRedirect.isAmazonHome(event.packageName, event.className)) event.timeStamp else 0L
        }
        // Only redirect if Amazon's home is the most recent thing to come to the front.
        if (amazonHomeAt == 0L) return
        lastHandled = amazonHomeAt
        // The accessibility service usually gets there first; don't launch twice.
        if (HomeRedirect.claim()) HomeRedirect.launch(this)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.watcher_channel), NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return builder
            .setSmallIcon(R.drawable.banner)
            .setContentTitle(getString(R.string.watcher_channel))
            .build()
    }

    /** Starts the watcher at boot, so Home works without opening FireFrontend first. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) start(context)
        }
    }

    companion object {
        // Fire OS 7's hold is often shorter than its 5 s maximum, so noticing Home sooner matters.
        private const val POLL_MS = 250L
        private const val LOOKBACK_MS = 3_000L
        private const val CHANNEL_ID = "home_watcher"
        private const val NOTIFICATION_ID = 1

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun start(context: Context) {
            if (!hasUsageAccess(context)) return
            val intent = Intent(context, HomeWatcherService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
