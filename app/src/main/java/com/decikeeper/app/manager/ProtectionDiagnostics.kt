package com.decikeeper.app.manager

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.decikeeper.app.BuildConfig
import com.decikeeper.app.service.CoreService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 诊断日志与生命周期状态追踪工具。
 * 用于覆盖安装/更新 APK 时取证无障碍与各项长期保护状态，严格不修改任何业务行为。
 */
object ProtectionDiagnostics {

    private const val TAG = "ProtectionDiagnostics"

    val processInstanceId: String = UUID.randomUUID().toString().take(8)
    private val unbindCounter = AtomicInteger(0)

    @Volatile
    var currentTestRunId: String? = null

    @Volatile
    var lastUnbindSequenceId: String? = null

    @Volatile
    var lastUnbindUptimeMs: Long = 0L

    fun setTestRunId(runId: String?) {
        currentTestRunId = runId
        if (runId != null) {
            val log = "[TEST_BEGIN] runId=$runId uptime=${SystemClock.uptimeMillis()}"
            Log.w(TAG, log)
            Log.w("DeciKeeper", log)
        }
    }

    fun endTestRun(runId: String? = currentTestRunId) {
        val id = runId ?: currentTestRunId ?: "UNKNOWN"
        val log = "[TEST_END] runId=$id uptime=${SystemClock.uptimeMillis()}"
        Log.w(TAG, log)
        Log.w("DeciKeeper", log)
        if (currentTestRunId == runId || runId == null) {
            currentTestRunId = null
        }
    }

    fun nextUnbindSequenceId(): String {
        val num = unbindCounter.incrementAndGet()
        val seqId = String.format(Locale.US, "A11Y-UNBIND-%04d", num)
        lastUnbindSequenceId = seqId
        lastUnbindUptimeMs = SystemClock.uptimeMillis()
        return seqId
    }

    fun dumpSnapshot(context: Context, trigger: String) {
        val appContext = context.applicationContext ?: context
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val userManager = appContext.getSystemService(android.os.UserManager::class.java)
            if (userManager != null && !userManager.isUserUnlocked) {
                Log.w(TAG, "[SNAPSHOT_SKIPPED] Device in Direct Boot (user locked). Trigger: $trigger")
                return
            }
        }
        val now = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(now))
        val uptimeMs = SystemClock.uptimeMillis()

        val pInfo = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        } catch (_: Exception) {
            null
        }
        val lastUpdateTime = pInfo?.lastUpdateTime ?: 0L
        val lastUpdateStr = if (lastUpdateTime > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastUpdateTime))
        } else "?"

        val sysA11y = PermissionManager.isAccessibilityEnabled(appContext)
        val coreActive = CoreService.isServiceActive
        val recentSignal = CoreService.hasRecentAccessibilitySignal(now, 15_000L)
        val lastSignalTime = CoreService.lastAccessibilitySignalTime
        val recentSignalAgeMs = if (lastSignalTime > 0L) now - lastSignalTime else -1L

        // AccessibilityControlManager
        val a11yCtrlState = AccessibilityControlManager.getState(appContext)
        val a11yCtrlDesired = appContext.getSharedPreferences("accessibility_control_prefs", Context.MODE_PRIVATE)
            .getBoolean("accessibility_desired_enabled", false)
        val a11yCtrlPending = AccessibilityControlManager.getDisableEffectiveTime(appContext)

        // AntiUninstallManager
        val antiState = AntiUninstallManager.getProtectionState(appContext)
        val antiPersisted = appContext.getSharedPreferences("anti_uninstall_prefs", Context.MODE_PRIVATE)
            .getBoolean("is_protection_enabled", false)
        val antiPending = AntiUninstallManager.getUnlockTime(appContext)
        val antiEnforcing = antiState != AntiUninstallManager.State.DISABLED

        // PrivacyProtectionManager
        val privacyState = PrivacyProtectionManager.getProtectionState(appContext)
        val privacyPersisted = appContext.getSharedPreferences(PrivacyProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PrivacyProtectionManager.KEY_IS_ENABLED, false)
        val privacyPending = PrivacyProtectionManager.getPendingDisableTime(appContext)
        val privacyEnforcing = PrivacyProtectionManager.isEnforcing(appContext)

        // FloatingWindowProtectionManager
        val floatProtState = FloatingWindowProtectionManager.getProtectionState(appContext)
        val floatProtPersisted = appContext.getSharedPreferences(FloatingWindowProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(FloatingWindowProtectionManager.KEY_IS_ENABLED, false)
        val floatProtPending = FloatingWindowProtectionManager.getPendingDisableTime(appContext)
        val floatProtEnforcing = FloatingWindowProtectionManager.isEnforcing(appContext)

        // DeveloperOptionsGuardManager
        val devGuardState = DeveloperOptionsGuardManager.getProtectionState(appContext)
        val devGuardPending = DeveloperOptionsGuardManager.getPendingDisableTime(appContext)
        val devGuardEnforcing = DeveloperOptionsGuardManager.isEnforcing(appContext)

        // InstallGuardManager
        val installGuardState = InstallGuardManager.getProtectionState(appContext)
        val installGuardPending = InstallGuardManager.getPendingDisableTime(appContext)
        val installGuardEnforcing = InstallGuardManager.isEnforcing(appContext)

        // AlarmPowerOffProtectionManager
        val alarmPowerOffEnabled = AlarmPowerOffProtectionManager.isEnabled(appContext)

        // GuardFloatingWindowManager
        val guardPrefs = appContext.getSharedPreferences("guard_floating_window_prefs", Context.MODE_PRIVATE)
        val guardState = GuardFloatingWindowManager.getProtectionState(appContext)
        val guardDesired = guardPrefs.getBoolean("floating_guard_desired_enabled", guardPrefs.getBoolean("floating_guard_enabled", false))
        val guardLegacy = guardPrefs.getBoolean("floating_guard_enabled", false)
        val guardPending = ConfigPendingManager.getEffectiveTime(appContext, "floating_guard_pending_disable")
        val guardEnforcing = GuardFloatingWindowManager.isEnforcing(appContext)
        val guardOverlayPerm = GuardFloatingWindowManager.canDrawOverlays(appContext)

        val runIdTag = currentTestRunId?.let { "runId=$it " } ?: ""

        val snapshotMsg = buildString {
            appendLine("========== [PROTECTION_SNAPSHOT] ${runIdTag}trigger=$trigger ==========")
            appendLine("timestamp=$dateStr ($now)")
            appendLine("processInstanceId=$processInstanceId pid=${Process.myPid()}")
            appendLine("appVersionName=${BuildConfig.VERSION_NAME}")
            appendLine("appVersionCode=${BuildConfig.VERSION_CODE}")
            appendLine("git/build fingerprint=${BuildConfig.BUILD_TYPE}_${BuildConfig.VERSION_CODE}")
            appendLine("package lastUpdateTime=$lastUpdateStr ($lastUpdateTime)")
            appendLine("process uptimeMs=$uptimeMs")
            appendLine("systemA11yEnabled=$sysA11y")
            appendLine("coreServiceActive=$coreActive")
            appendLine("recentAccessibilitySignal=$recentSignal")
            appendLine("recentSignalAgeMs=$recentSignalAgeMs")
            appendLine("AccessibilityControlManager:")
            appendLine("    state=$a11yCtrlState")
            appendLine("    desiredEnabled=$a11yCtrlDesired")
            appendLine("    pendingEffectiveTime=$a11yCtrlPending")
            appendLine("AntiUninstallManager:")
            appendLine("    state=$antiState")
            appendLine("    persistedEnabled=$antiPersisted")
            appendLine("    pendingEffectiveTime=$antiPending")
            appendLine("    isEnforcing=$antiEnforcing")
            appendLine("PrivacyProtectionManager:")
            appendLine("    state=$privacyState")
            appendLine("    persistedEnabled=$privacyPersisted")
            appendLine("    pendingEffectiveTime=$privacyPending")
            appendLine("    isEnforcing=$privacyEnforcing")
            appendLine("FloatingWindowProtectionManager:")
            appendLine("    state=$floatProtState")
            appendLine("    persistedEnabled=$floatProtPersisted")
            appendLine("    pendingEffectiveTime=$floatProtPending")
            appendLine("    isEnforcing=$floatProtEnforcing")
            appendLine("DeveloperOptionsGuardManager:")
            appendLine("    state=$devGuardState")
            appendLine("    pendingEffectiveTime=$devGuardPending")
            appendLine("    isEnforcing=$devGuardEnforcing")
            appendLine("InstallGuardManager:")
            appendLine("    state=$installGuardState")
            appendLine("    pendingEffectiveTime=$installGuardPending")
            appendLine("    isEnforcing=$installGuardEnforcing")
            appendLine("AlarmPowerOffProtectionManager:")
            appendLine("    enabled=$alarmPowerOffEnabled")
            appendLine("GuardFloatingWindowManager:")
            appendLine("    state=$guardState")
            appendLine("    desiredEnabled=$guardDesired")
            appendLine("    legacyEnabled=$guardLegacy")
            appendLine("    pendingEffectiveTime=$guardPending")
            appendLine("    isEnforcing=$guardEnforcing")
            appendLine("    overlayPermission=$guardOverlayPerm")
            append("================================================================")
        }

        Log.w(TAG, snapshotMsg)
        Log.w("DeciKeeper", snapshotMsg)
    }
}
