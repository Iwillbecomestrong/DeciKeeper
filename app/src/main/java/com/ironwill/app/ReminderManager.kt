/**
 * =================================================================================
 * [Context Header]
 * * @file: ReminderManager.kt
 * * @role: Domain Skill
 * * @description: 提醒与通知调度管理。
 * * @responsibilities:
 * 1. 计算并调度提醒时间。
 * 2. 触发通知与回调入口。
 * * @context:
 * - 上游: TimeRuleManager
 * - 下游: 系统通知服务
 * * @constraints (必读):
 * 1. 不阻塞主线程。
 * 2. 处理系统版本差异。
 * 3. 空值安全。
 * =================================================================================
 */
package com.ironwill.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import java.util.Calendar

/**
 * Manages Dual Reminder System for upcoming Monitored Segments.
 *
 * Logic:
 * - Checks every minute (via CoreService).
 * - Gets the NEXT segment.
 * - Calculates time difference.
 * - Triggers alerts:
 *   1. Far Reminder: 20 mins before (Dialog only).
 *   2. Near Reminder: 5 mins before (Dialog + Continuous Vibration).
 *
 * 🚀 [Adaptive Alerts] 解决心跳暂停导致预警错过或延迟：
 * - 不再使用固定精确分钟窗口（如 20~21），改用 ≤ 阈值
 * - 基于实际剩余时间自适应显示预警文案
 * - 按段追踪已展示的预警等级，避免重复弹窗
 * - 时段已开始则静默清除，不残留过期预警
 */
object ReminderManager {

    private const val TAG = "ReminderManager"

    // Alert Levels
    private const val ALERT_NONE = 0
    private const val ALERT_FAR = 1  // 20 min
    private const val ALERT_NEAR = 2 // 5 min

    // Time Thresholds (Minutes) — 改为 ≤ 阈值，不再用精确窗口
    private val FAR_THRESHOLD = 20
    private val NEAR_THRESHOLD = 5

    // SharedPreferences for tracking shown alerts
    private const val PREFS_ALERT = "reminder_alerts"
    private const val KEY_SHOWN_SEG = "shown_segment_key"   // 当前已展示预警的时段标识
    private const val KEY_SHOWN_LEVEL = "shown_alert_level"  // 已展示的最高预警等级

    /**
     * Called periodically (e.g., every minute) by CoreService.
     */
    fun checkReminders(context: Context) {
        val nextSeg = TimeRuleManager.getNextSegment(context) ?: return

        // Calculate start time of next segment
        val nextStartTime = calculateSegmentStartTime(nextSeg)
        val now = System.currentTimeMillis()
        val diffMillis = nextStartTime - now

        val prefs = context.getSharedPreferences(PREFS_ALERT, Context.MODE_PRIVATE)
        val todayKey = dateKey()
        val segKey = segmentId(nextSeg) + "_" + todayKey

        if (diffMillis <= 0) {
            // 🚀 [Fix] 时段已开始 → 清除预警标记，关闭残留弹窗
            prefs.edit().clear().apply()
            LockWindowManager.dismissReminderDialog()
            return
        }

        val diffMinutes = diffMillis / (1000 * 60)

        Log.d(TAG, "Next Segment: ${nextSeg.name} (${nextSeg.startHour}:${nextSeg.startMinute}), Diff: ${diffMinutes}m")

        // 获取已展示状态
        val lastShownSeg = prefs.getString(KEY_SHOWN_SEG, "") ?: ""
        var shownLevel = prefs.getInt(KEY_SHOWN_LEVEL, ALERT_NONE)

        // 如果换了时段（或跨天），重置已展示记录
        if (lastShownSeg != segKey) {
            shownLevel = ALERT_NONE
            prefs.edit().putString(KEY_SHOWN_SEG, segKey).putInt(KEY_SHOWN_LEVEL, ALERT_NONE).apply()
        }

        if (diffMinutes <= NEAR_THRESHOLD && shownLevel < ALERT_NEAR) {
            // ⏰ 5分钟内 → 紧急预警（自适应剩余时间）
            triggerAdaptiveNearReminder(context, nextSeg, diffMinutes)
            prefs.edit().putInt(KEY_SHOWN_LEVEL, ALERT_NEAR).apply()
            // 清除远预警弹窗（如果有）
            LockWindowManager.dismissReminderDialog()
        } else if (diffMinutes <= FAR_THRESHOLD && shownLevel < ALERT_FAR) {
            // ⏰ 5-20分钟 → 远预警（自适应剩余时间）
            triggerAdaptiveFarReminder(context, nextSeg, diffMinutes)
            prefs.edit().putInt(KEY_SHOWN_LEVEL, ALERT_FAR).apply()
        }
        // 超过20分钟 → 不触发任何预警
    }

    /** 当期日期标识（用于区分不同天的同一时段） */
    private fun dateKey(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    /** 时段唯一标识 */
    private fun segmentId(seg: TimeSegment): String {
        return "${seg.name}_${seg.startHour}_${seg.startMinute}"
    }

    private fun calculateSegmentStartTime(seg: TimeSegment): Long {
        val cal = Calendar.getInstance()
        val now = System.currentTimeMillis()
        cal.timeInMillis = now
        
        val currentTotal = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val segStartTotal = seg.startHour * 60 + seg.startMinute
        
        cal.set(Calendar.HOUR_OF_DAY, seg.startHour)
        cal.set(Calendar.MINUTE, seg.startMinute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // If segment start is earlier than current time, it must be tomorrow
        // (Assuming getNextSegment returns the strictly next one)
        // Wait, getNextSegment logic in TimeRuleManager check:
        // If found today (start > current), it returns it.
        // If not found today, it returns earliest tomorrow.
        
        if (cal.timeInMillis < now) {
             cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        return cal.timeInMillis
    }

    /** 自适应远预警 — 根据实际剩余时间显示动态文案 */
    private fun triggerAdaptiveFarReminder(context: Context, seg: TimeSegment, remainingMin: Long) {
        val actualMin = if (remainingMin < 1) 1 else remainingMin
        Log.i(TAG, "Triggering adaptive FAR Reminder: ${actualMin}m remaining")
        showDialog(
            context,
            "即将进入监管时段",
            "距离 [${seg.name}] 开始还有 ${actualMin} 分钟。\n请合理安排剩余时间。",
            false
        )
    }

    /** 自适应近预警 — 根据实际剩余时间显示动态文案 + 震动 */
    private fun triggerAdaptiveNearReminder(context: Context, seg: TimeSegment, remainingMin: Long) {
        val msg = if (remainingMin <= 0) {
            "距离 [${seg.name}] 即将开始！\n马上进入严格监管模式。"
        } else {
            "距离 [${seg.name}] 开始仅剩 ${remainingMin} 分钟！\n马上进入严格监管模式。"
        }
        Log.i(TAG, "Triggering adaptive NEAR Reminder: ${remainingMin}m remaining")
        performVibration(context)
        showDialog(context, "最后通牒", msg, true)
    }

    private fun performVibration(context: Context) {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        // Pattern: Vibrate 500ms, Sleep 500ms, Repeat 3 times
        val pattern = longArrayOf(0, 500, 500, 500, 500, 500) 
        v?.vibrate(pattern, -1)
    }

    private fun showDialog(context: Context, title: String, message: String, isUrgent: Boolean) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "$title\n$message", Toast.LENGTH_LONG).show()
            if (isUrgent) {
                performVibration(context)
            }
        }
    }
}
