/**
 * =================================================================================
 * PresenceNotificationListener — 实验性小窗通知监听
 *
 * 目的：观察 Vivo 在小窗/悬浮窗打开时是否发送"正在其他应用上层显示"通知。
 * 如果发送，这个信号比 lastTimeVisible 轮询快得多（毫秒级 vs 秒级）。
 *
 * 约束（红线）：
 * 1. 默认不启用（NOTIF_LISTENER_ENABLED = false），仅打日志观察。
 * 2. 6 小时内最多触发一次锁机动作，防止频繁骚扰。
 * 3. 即使中途被系统杀死/重启，频率限制依然生效（用 SharedPreferences 持久化）。
 * 4. 所有检测和决策打 [PresenceNotif] 日志。
 * =================================================================================
 */
package com.decikeeper.app.service

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.ui.LockWindowManager




class PresenceNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "PresenceNotif"
        // 开关：启用通知监听作为小窗检测快速通道
        const val NOTIF_LISTENER_ENABLED = false
        // 6 小时频率限制（每个黑名单应用独立计时）
        private const val RATE_LIMIT_MS = 6 * 60 * 60 * 1000L // 6 hours
        private const val PREFS_NAME = "presence_notif_state"
        private const val KEY_LAST_LOCK_MS_PREFIX = "last_lock_"
        // 小窗通知特征关键字（Vivo 可能用不同措辞，先广撒网再根据日志收紧）
        private val FLOATING_WINDOW_KEYWORDS = listOf(
            "在其他应用上层显示",
            "悬浮窗",
            "画中画",
            "正在其他应用的上层显示",
            "上层显示",
            "displaying over other apps",
            "picture in picture",
            "画中画模式",
            "浮窗",
            "小窗",
            "迷你窗",
            "悬浮窗口",
            "在其他应用的上层",
            "浮窗播放"
        )
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.w(TAG, "[LIFECYCLE] NotificationListener connected, enabled=$NOTIF_LISTENER_ENABLED")
        // 打印当前所有活跃通知，帮助了解基线状态
        if (activeNotifications.isNotEmpty()) {
            Log.w(TAG, "[LIFECYCLE] ${activeNotifications.size} active notifications on connect:")
            for (sbn in activeNotifications.take(10)) {
                logNotificationBrief(sbn, "existing")
            }
        }
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "[LIFECYCLE] NotificationListener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val title = extractTitle(sbn.notification) ?: ""
        val text = extractText(sbn.notification) ?: ""

        Log.d(TAG, "[posted] pkg=$pkg title=\"$title\" text=\"$text\" " +
            "category=${sbn.notification?.category} " +
            "flags=${sbn.notification?.flags} " +
            "isOngoing=${(sbn.notification?.flags ?: 0) and Notification.FLAG_ONGOING_EVENT != 0}")

        // 检查是否为小窗通知
        val combined = "$title $text"
        val matchedKeyword = FLOATING_WINDOW_KEYWORDS.firstOrNull { keyword ->
            combined.contains(keyword, ignoreCase = true)
        }

        val isOngoing = (sbn.notification?.flags ?: 0) and Notification.FLAG_ONGOING_EVENT != 0

        if (matchedKeyword != null) {
            Log.w(TAG, "[FLOAT_DETECT] pkg=$pkg matched_keyword=\"$matchedKeyword\" title=\"$title\" text=\"$text\" isOngoing=$isOngoing")
            LogExporter.logEvent(TAG, "[FLOAT_DETECT] pkg=$pkg keyword=\"$matchedKeyword\" title=\"${title.take(80)}\" text=\"${text.take(120)}\" isOngoing=$isOngoing")

            // 通知的 packageName 可能是 "android"（系统发通知），真正应用在 title 里
            // 先用通知包名查黑名单，同时把 title/text 打进日志供后续分析
            val isBlacklisted = try {
                CreditManager.shouldBlockApp(this, pkg, "")
            } catch (e: Exception) { false }

            if (isBlacklisted) {
                Log.w(TAG, "[FLOAT_BLACKLIST] pkg=$pkg is blacklisted!")
                LogExporter.logEvent(TAG, "[FLOAT_BLACKLIST] pkg=$pkg keyword=\"$matchedKeyword\"")

                if (NOTIF_LISTENER_ENABLED && canPerformAction(pkg)) {
                    Log.w(TAG, "[FLOAT_LOCK] pkg=$pkg → triggering lock via notification listener")
                    LogExporter.logEvent(TAG, "[FLOAT_LOCK] pkg=$pkg keyword=\"$matchedKeyword\"")
                    val appLabel = try {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: Exception) { pkg }
                    LockWindowManager.showLockScreen(
                        reason = "NotifMiniWin: $pkg",
                        isBlacklist = true,
                        blockedAppLabel = appLabel,
                        blockedPackageName = pkg
                    )
                    recordAction(pkg)
                } else if (NOTIF_LISTENER_ENABLED) {
                    val remainingS = getRemainingCooldownS(pkg)
                    Log.d(TAG, "[FLOAT_RATE_LIMITED] pkg=$pkg cooldownRemain=${remainingS}s")
                } else {
                    Log.d(TAG, "[FLOAT_OBSERVE] pkg=$pkg detected but NOTIF_LISTENER_ENABLED=false")
                }
            } else {
                Log.d(TAG, "[FLOAT_NOT_BLACKLIST] pkg=$pkg not in blacklist, skip")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val title = extractTitle(sbn.notification) ?: ""
        val text = extractText(sbn.notification) ?: ""

        val combined = "$title $text"
        val matchedKeyword = FLOATING_WINDOW_KEYWORDS.firstOrNull { keyword ->
            combined.contains(keyword, ignoreCase = true)
        }

        if (matchedKeyword != null) {
            Log.w(TAG, "[REMOVED] pkg=$pkg keyword=\"$matchedKeyword\" title=\"$title\"")
            LogExporter.logEvent(TAG, "[FLOAT_REMOVED] pkg=$pkg keyword=\"$matchedKeyword\"")

            val isBlacklisted = try {
                CreditManager.shouldBlockApp(this, pkg, "")
            } catch (e: Exception) { false }

            if (isBlacklisted) {
                Log.d(TAG, "[FLOAT_GONE_OBSERVE] pkg=$pkg mini-window notification removed (dismiss bypass, read-only mode)")
            }
        }
    }

    // --- Per-package rate limiting ---

    private fun canPerformAction(pkg: String): Boolean {
        val lastMs = prefs.getLong(KEY_LAST_LOCK_MS_PREFIX + pkg, 0L)
        return if (lastMs <= 0L) true
        else (System.currentTimeMillis() - lastMs) >= RATE_LIMIT_MS
    }

    private fun recordAction(pkg: String) {
        prefs.edit().putLong(KEY_LAST_LOCK_MS_PREFIX + pkg, System.currentTimeMillis()).apply()
    }

    private fun getRemainingCooldownS(pkg: String): Long {
        val lastMs = prefs.getLong(KEY_LAST_LOCK_MS_PREFIX + pkg, 0L)
        if (lastMs <= 0L) return 0
        return (RATE_LIMIT_MS - (System.currentTimeMillis() - lastMs)) / 1000
    }

    private fun extractTitle(notification: Notification?): String? {
        return notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    }

    private fun extractText(notification: Notification?): String? {
        return notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }

    private fun logNotificationBrief(sbn: StatusBarNotification, tag: String) {
        val pkg = sbn.packageName
        val title = extractTitle(sbn.notification) ?: ""
        val text = extractText(sbn.notification) ?: ""
        Log.d(TAG, "[$tag] pkg=$pkg title=\"${title.take(50)}\" text=\"${text.take(80)}\"")
    }
}
