package com.ironwill.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import com.ironwill.app.R
import com.ironwill.app.manager.AlarmChallengeEmergencyWhitelist
import com.ironwill.app.manager.AlarmScheduler
import com.ironwill.app.manager.AlarmWindowManager

/**
 * Alarm-only foreground guard. While a challenge is active, it exposes up to two
 * emergency app shortcuts and immediately restores the challenge if the user
 * leaves the challenge/allowed apps for any other package.
 */
class AlarmChallengeEmergencyGuardService : Service() {
    companion object {
        private const val TAG = "AlarmEmergencyGuard"
        private const val CHANNEL_ID = "alarm_emergency_guard"
        private const val NOTIFICATION_ID = 2012
        private const val POLL_MS = 300L
        private const val REENTER_THROTTLE_MS = 900L

        fun start(context: Context) {
            val intent = Intent(context, AlarmChallengeEmergencyGuardService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AlarmChallengeEmergencyGuardService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bar: LinearLayout? = null
    private var lastRenderedPackages: List<String> = emptyList()
    private var lastReenterAt = 0L
    private var launchGraceUntil = 0L

    private val poll = object : Runnable {
        override fun run() {
            try {
                tick()
            } finally {
                if (AlarmScheduler.isAlarmActive(this@AlarmChallengeEmergencyGuardService)) {
                    handler.postDelayed(this, POLL_MS)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(poll)
        handler.post(poll)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeBar()
        super.onDestroy()
    }

    private fun tick() {
        if (!AlarmScheduler.isAlarmActive(this)) {
            removeBar()
            stopSelf()
            return
        }

        ensureBar()

        val now = System.currentTimeMillis()
        if (now < launchGraceUntil) return
        val top = detectTopPackage() ?: return
        val allowed = AlarmChallengeEmergencyWhitelist.getPackages(this).toSet()
        if (AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(true, top, packageName, allowed)) {
            return
        }

        if (now - lastReenterAt < REENTER_THROTTLE_MS) return
        val alarmId = AlarmScheduler.getActiveAlarmId(this)
        if (alarmId < 0) return
        lastReenterAt = now
        Log.i(TAG, "non-whitelisted foreground=$top, restoring challenge id=$alarmId")
        AlarmWindowManager.reenterChallenge(applicationContext, alarmId)
    }

    private fun ensureBar() {
        if (!Settings.canDrawOverlays(this)) return
        val packages = AlarmChallengeEmergencyWhitelist.getPackages(this)
        if (packages.isEmpty()) {
            removeBar()
            return
        }
        if (bar != null && packages == lastRenderedPackages) return
        removeBar()

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        }
        packages.take(2).forEach { pkg ->
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            root.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    launchGraceUntil = System.currentTimeMillis() + 1800L
                    if (!AlarmChallengeEmergencyWhitelist.launch(applicationContext, pkg)) {
                        launchGraceUntil = 0L
                    }
                }
            }, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
            })
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        try {
            wm?.addView(root, lp)
            bar = root
            lastRenderedPackages = packages
        } catch (e: Exception) {
            Log.w(TAG, "failed to add emergency app bar", e)
        }
    }

    private fun removeBar() {
        val view = bar ?: return
        try { wm?.removeViewImmediate(view) } catch (_: Exception) { }
        bar = null
        lastRenderedPackages = emptyList()
    }

    private fun detectTopPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        return try {
            val events = usm.queryEvents(now - 2500L, now)
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTs = -1L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if ((event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)) &&
                    event.timeStamp >= latestTs
                ) {
                    latestTs = event.timeStamp
                    latestPkg = event.packageName
                }
            }
            latestPkg
        } catch (e: Exception) {
            Log.d(TAG, "top-package detection unavailable: ${e.message}")
            null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "闹钟挑战应急保护",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setSound(null, null) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("IronWill 闹钟挑战进行中")
        .setContentText("仅允许挑战界面和应急白名单应用")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}
