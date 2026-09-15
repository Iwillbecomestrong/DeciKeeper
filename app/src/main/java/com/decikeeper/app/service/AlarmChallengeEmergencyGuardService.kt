package com.decikeeper.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import androidx.core.app.NotificationCompat
import com.decikeeper.app.R
import com.decikeeper.app.ui.ThemeManager
import com.decikeeper.app.manager.AlarmChallengeEmergencyWhitelist
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.AlarmWindowManager
import com.decikeeper.app.ui.LockWindowManager

/**
 * Alarm-only foreground guard. While a challenge is active, it exposes up to two
 * emergency app shortcuts inside the existing challenge UI and immediately
 * restores that same challenge if the user leaves the approved apps.
 */
class AlarmChallengeEmergencyGuardService : Service() {
    companion object {
        private const val TAG = "AlarmEmergencyGuard"
        private const val CHANNEL_ID = "alarm_emergency_guard"
        private const val NOTIFICATION_ID = 2012
        private const val POLL_MS = 300L
        private const val REENTER_THROTTLE_MS = 900L
        private const val LAUNCH_GRACE_MS = 1800L

        @Volatile
        private var emergencyAccessInProgress = false

        @Volatile
        private var permissionRequestInProgress = false
        private var permissionRequestTimeoutAt = 0L

        fun isEmergencyAccessInProgress(): Boolean = emergencyAccessInProgress

        fun isPermissionRequestInProgress(): Boolean {
            if (permissionRequestInProgress) {
                if (System.currentTimeMillis() > permissionRequestTimeoutAt) {
                    permissionRequestInProgress = false
                    return false
                }
                return true
            }
            return false
        }

        fun setPermissionRequestInProgress(inProgress: Boolean, timeoutMs: Long = 30_000L) {
            permissionRequestInProgress = inProgress
            permissionRequestTimeoutAt = if (inProgress) System.currentTimeMillis() + timeoutMs else 0L
        }

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
            emergencyAccessInProgress = false
            permissionRequestInProgress = false
            try {
                context.stopService(Intent(context, AlarmChallengeEmergencyGuardService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var emergencySection: LinearLayout? = null
    private var lastRenderedPackages: List<String> = emptyList()
    private var lastReenterAt = 0L
    private var launchGraceUntil = 0L
    private var observedLockedChallengeState = false
    private var restrictionReleased = false

    private val poll = object : Runnable {
        override fun run() {
            try {
                tick()
            } finally {
                val isRealActive = AlarmScheduler.isAlarmActive(this@AlarmChallengeEmergencyGuardService)
                val isTestActive = LockWindowManager.isOverlayActive
                if (!restrictionReleased && (isRealActive || isTestActive)) {
                    handler.postDelayed(this, POLL_MS)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        restrictionReleased = false
        observedLockedChallengeState = false
        emergencyAccessInProgress = false
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!restrictionReleased) {
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        emergencyAccessInProgress = false
        launchGraceUntil = 0L
        removeChallengeSection()
        super.onDestroy()
    }

    private fun tick() {
        val isRealActive = AlarmScheduler.isAlarmActive(this)
        val isTestActive = LockWindowManager.isOverlayActive
        if (!isRealActive && !isTestActive) {
            emergencyAccessInProgress = false
            removeChallengeSection()
            stopSelf()
            return
        }

        ensureChallengeSection()
        if (hasChallengeJustCompleted()) {
            releaseChallengeRestriction()
            return
        }

        val now = System.currentTimeMillis()
        if (now < launchGraceUntil) return
        val top = detectTopPackage() ?: return
        val allowed = AlarmChallengeEmergencyWhitelist.getPackages(this).toSet()

        if (top == packageName) {
            if (emergencyAccessInProgress) {
                emergencyAccessInProgress = false
                launchGraceUntil = 0L
                LockWindowManager.showAlarmOverlayIfHidden()
                Log.i(TAG, "returned from emergency app to IronWill; challenge view restored")
            }
            return
        }

        if (AlarmChallengeEmergencyWhitelist.isPackageAllowedDuringChallenge(
                alarmActive = true,
                foregroundPackage = top,
                selfPackage = packageName,
                allowedPackages = allowed,
                isPermissionRequestInProgress = isPermissionRequestInProgress()
            )
        ) {
            return
        }

        if (now - lastReenterAt < REENTER_THROTTLE_MS) return
        val alarmId = if (isRealActive) AlarmScheduler.getActiveAlarmId(this) else AlarmScheduler.TEST_ALARM_ID
        if (isRealActive && alarmId < 0) return
        lastReenterAt = now
        emergencyAccessInProgress = false
        launchGraceUntil = 0L
        Log.i(TAG, "non-whitelisted foreground=$top, restoring challenge id=$alarmId")

        LockWindowManager.showAlarmOverlayIfHidden()
        if (isRealActive) {
            AlarmWindowManager.reenterChallenge(applicationContext, alarmId)
        } else {
            val ctx = applicationContext
            val intent = Intent(ctx, com.decikeeper.app.ui.AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, AlarmScheduler.TEST_ALARM_ID)
            }
            ctx.startActivity(intent)
        }
    }

    /**
     * Adds the emergency shortcuts immediately above the challenge action panel.
     * This works for both WindowManager and lock-screen inline hosting because it
     * operates on the one shared alarm View instead of creating another overlay.
     */
    private fun ensureChallengeSection() {
        val alarmView = LockWindowManager.getAlarmView() ?: return
        val actionPanel = alarmView.findViewById<View>(R.id.layout_alarm_actions) ?: return
        val host = actionPanel.parent as? LinearLayout ?: return
        val packages = AlarmChallengeEmergencyWhitelist.getPackages(this).take(AlarmChallengeEmergencyWhitelist.MAX_APPS)

        if (packages.isEmpty()) {
            removeChallengeSection()
            return
        }

        val existing = emergencySection
        if (existing != null && existing.parent === host && packages == lastRenderedPackages) {
            return
        }
        removeChallengeSection()

        val uiContext = alarmView.context
        val density = uiContext.resources.displayMetrics.density
        val section = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            tag = "alarm_emergency_use_section"

            addView(TextView(uiContext).apply {
                text = "应急使用"
                textSize = 13f
                setTextColor(Color.parseColor("#B0BEC5"))
                setPadding((4 * density).toInt(), 0, 0, (4 * density).toInt())
            })

            val row = LinearLayout(uiContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            packages.forEach { pkg ->
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
                row.addView(Button(uiContext).apply {
                    text = label
                    isAllCaps = false
                    setBackgroundResource(R.drawable.bg_zz_pill)
                    backgroundTintList = ColorStateList.valueOf(ThemeManager.getColorAttr(uiContext, R.attr.zzSurfaceStrong))
                    setTextColor(ThemeManager.getColorAttr(uiContext, R.attr.zzText))
                    textSize = 13f
                    try {
                        val icon = packageManager.getApplicationIcon(pkg)
                        val size = (20 * density).toInt()
                        icon.setBounds(0, 0, size, size)
                        setCompoundDrawables(icon, null, null, null)
                        compoundDrawablePadding = (6 * density).toInt()
                    } catch (_: Exception) {
                    }
                    setOnClickListener { beginEmergencyAccess(pkg) }
                }, LinearLayout.LayoutParams(0, (46 * density).toInt(), 1f).apply {
                    marginStart = (4 * density).toInt()
                    marginEnd = (4 * density).toInt()
                })
            }
            addView(row)
        }

        val index = host.indexOfChild(actionPanel).takeIf { it >= 0 } ?: host.childCount
        host.addView(
            section,
            index,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
        )
        emergencySection = section
        lastRenderedPackages = packages
    }

    private fun beginEmergencyAccess(packageName: String) {
        if (!AlarmScheduler.isAlarmActive(this)) return
        if (!AlarmChallengeEmergencyWhitelist.getPackages(this).contains(packageName)) return

        emergencyAccessInProgress = true
        launchGraceUntil = System.currentTimeMillis() + LAUNCH_GRACE_MS
        val inline = LockWindowManager.isCurrentOverlayInline()

        // WindowManager overlays would otherwise cover the emergency app. Detaching the
        // root keeps the same View object and all challenge state in memory for restoration.
        if (!inline) {
            LockWindowManager.hideAlarmOverlayForPermission()
        }

        if (!AlarmChallengeEmergencyWhitelist.launch(applicationContext, packageName)) {
            emergencyAccessInProgress = false
            launchGraceUntil = 0L
            LockWindowManager.showAlarmOverlayIfHidden()
            Toast.makeText(applicationContext, "无法打开该应急应用", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "failed to launch emergency package=$packageName")
            return
        }

        Log.i(TAG, "emergency package launched=$packageName inline=$inline")
    }

    /**
     * The action panel is hidden throughout an unfinished challenge and becomes
     * visible only after final verification. Requiring that we first observed the
     * hidden state avoids treating the brief pre-init XML state as completion.
     */
    private fun hasChallengeJustCompleted(): Boolean {
        val alarmView = LockWindowManager.getAlarmView() ?: return false
        val btnClose = alarmView.findViewById<Button>(R.id.btn_alarm_close) ?: return false
        if (btnClose.visibility != View.VISIBLE) {
            observedLockedChallengeState = true
            return false
        }
        return observedLockedChallengeState
    }

    private fun releaseChallengeRestriction() {
        restrictionReleased = true
        emergencyAccessInProgress = false
        launchGraceUntil = 0L
        removeChallengeSection()
        Log.i(TAG, "challenge completed; emergency-only foreground restriction released")
        stopSelf()
    }

    private fun removeChallengeSection() {
        val section = emergencySection
        if (section != null) {
            try {
                (section.parent as? ViewGroup)?.removeView(section)
            } catch (_: Exception) {
            }
        }
        emergencySection = null
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
        .setContentTitle("决定守护 闹钟挑战进行中")
        .setContentText("仅允许挑战界面和应急白名单应用")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}
