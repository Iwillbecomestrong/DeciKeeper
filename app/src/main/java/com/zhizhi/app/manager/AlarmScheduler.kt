/**
 * =================================================================================
 * [Context Header]
 * @file: AlarmScheduler.kt
 * @role: Domain Skill
 * @description: Persistent daily alarm scheduling and storage.
 * @responsibilities:
 * 1. Save alarm time/state.
 * 2. Schedule/cancel alarm with AlarmManager.
 * @context:
 * - 上游: AlarmFragment
 * - 下游: AlarmReceiver
 * @constraints (必读):
 * 1. 不包含 UI 逻辑。
 * 2. 需处理不同系统版本。
 * =================================================================================
 */
package com.zhizhi.app.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.zhizhi.app.data.AndroidLogcatNormalEventSink
import com.zhizhi.app.data.DefaultLogDebugConfig
import com.zhizhi.app.data.DefaultNormalEventRenderer
import com.zhizhi.app.data.LogExporter
import com.zhizhi.app.data.NormalEventLogger
import com.zhizhi.app.data.SystemEventClock
import com.zhizhi.app.manager.statistics.AlarmWakeStatsRecorder
import com.zhizhi.app.receiver.AlarmReceiver
import com.zhizhi.app.service.AlarmGuardianService
import com.zhizhi.app.service.AlarmWatchdogService
import com.zhizhi.app.ui.AlarmActivity
import com.zhizhi.app.worker.AlarmHeartbeatWorker
import com.zhizhi.app.worker.AlarmWorker
import java.util.Calendar
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject













object AlarmScheduler {

    // Event-driven logging bridge (task-6). The scheduler has no authority over the
    // bridge decision — it just records the completed schedule outcome for export.
    private val alarmProtectionNormalEventLogger by lazy {
        NormalEventLogger(
            renderer = DefaultNormalEventRenderer,
            sinks = listOf(LogExporter.normalEventSink(), AndroidLogcatNormalEventSink())
        )
    }
    private val alarmProtectionEventBridge by lazy {
        AlarmProtectionEventBridge(
            clock = SystemEventClock,
            emit = alarmProtectionNormalEventLogger::emit,
            debugConfig = DefaultLogDebugConfig
        )
    }

    private const val PREFS_NAME = "alarm_prefs"
    private const val KEY_ALARMS_JSON = "alarms_json"
    private const val KEY_NEXT_ID = "alarm_next_id"
    private const val KEY_SNOOZE_MINUTES = "snooze_minutes"
    private const val KEY_MAX_SNOOZE_COUNT = "max_snooze_count"
    private const val KEY_VIBRATION_INTENSITY = "vibration_intensity"
    private const val KEY_SNOOZE_USED_PREFIX = "snooze_used_"
    private const val KEY_ALARM_ACTIVE = "alarm_active"
    private const val KEY_ACTIVE_ALARM_ID = "active_alarm_id"
    private const val KEY_ACTIVE_FIRST_STAGE_PASSED = "active_first_stage_passed"
    private const val KEY_FIRST_STAGE_PASSED_AT = "first_stage_passed_at"
    private const val KEY_VIBRATION_LOCKED_INTENSITY = "vibration_locked_intensity"
    private const val KEY_VIBRATION_START_TIME = "vibration_start_time"
    private const val KEY_ACTIVE_SCHEDULE_TOKEN = "active_schedule_token"
    private const val PREF_RECOVERY_PREFIX = "alarm_recovery_count_"
    const val MAX_RECOVERY_COUNT = 40   // 安全兜底：每 30 秒一次，最多 20 分钟（避免 OEM 反滥用标记）
    private const val DEFAULT_SNOOZE_MINUTES = 10
    private const val DEFAULT_MAX_SNOOZE_COUNT = 2
    private const val SNOOZE_REQUEST_OFFSET = 1_000_000
    private const val SHOW_REQUEST_OFFSET = 2_000_000
    private const val RECOVERY_REQUEST_OFFSET = 3_000_000
    private const val PRE_WARM_REQUEST_OFFSET = 4_000_000
    private const val BACKUP_REQUEST_OFFSET = 5_000_000
    private const val BACKUP2_REQUEST_OFFSET = 6_000_000
    private const val REBOOT_SAFETY_NET_OFFSET = 7_000_000

    const val ACTION_ALARM_TRIGGER = "com.zhizhi.app.ACTION_ALARM_TRIGGER"
    const val ACTION_ALARM_SNOOZE = "com.zhizhi.app.ACTION_ALARM_SNOOZE"
    const val ACTION_ALARM_RECOVERY = "com.zhizhi.app.ACTION_ALARM_RECOVERY"
    const val ACTION_ALARM_PRE_WARM = "com.zhizhi.app.ACTION_ALARM_PRE_WARM"
    const val ACTION_ALARM_BACKUP = "com.zhizhi.app.ACTION_ALARM_BACKUP"
    const val ACTION_ALARM_BACKUP2 = "com.zhizhi.app.ACTION_ALARM_BACKUP2"
    const val ACTION_ALARM_REBOOT_SAFETY_NET = "com.zhizhi.app.ACTION_ALARM_REBOOT_SAFETY_NET"
    const val EXTRA_ALARM_ID = "alarm_id"
    // scheduleToken: 每次调度时生成的唯一令牌，用于 Receiver 校验广播是否过期
    const val EXTRA_SCHEDULE_TOKEN = "alarm_schedule_token"
    // scheduledAtMillis: 调度时计算的目标触发时间，用于 Receiver 校验时间是否匹配
    const val EXTRA_SCHEDULED_AT_MILLIS = "alarm_scheduled_at_millis"
    // intentKind: 标识 PendingIntent 种类（MAIN / PREWARM / RECOVERY / BACKUP）
    const val EXTRA_INTENT_KIND = "alarm_intent_kind"

    /** PendingIntent 种类枚举，用于区分不同用途的闹钟 Intent */
    enum class AlarmIntentKind(val dataPath: String, val requestCodeOffset: Int) {
        MAIN("main", 0),
        SNOOZE("snooze", SNOOZE_REQUEST_OFFSET),
        PREWARM("prewarm", PRE_WARM_REQUEST_OFFSET),
        BACKUP("backup", BACKUP_REQUEST_OFFSET),
        BACKUP2("backup2", BACKUP2_REQUEST_OFFSET),
        RECOVERY("recovery", RECOVERY_REQUEST_OFFSET),
        REBOOT_SAFETY_NET("safetynet", REBOOT_SAFETY_NET_OFFSET)
    }

    /**
     * 测试闹钟专用 id。独立于真实闹钟 id 命名空间(真实闹钟重排后 1..N),
     * 用 0 标识"这是测试触发的,不是真实闹钟",在 cancelAll 范围(0 until nextId)
     * 和 Recovery/SafetyNet 取消范围(0..999)内都能被清理。
     */
    const val TEST_ALARM_ID = 0

    // 恢复闹钟间隔：每 30 秒检查一次，原 2 分钟太长了
    // vivo/OPPO 等系统可能对 setAlarmClock 做批量延迟投递，
    // 缩短 Recovery 间隔可确保在系统"想起来"投递时尽快恢复震动
    private const val RECOVERY_INTERVAL_MS = 30 * 1000L

    // 预热闹钟：在主闹钟前 1 分钟唤醒进程（不宜过早，否则手机可能再次进入深度休眠）
    private const val PRE_WARM_ADVANCE_MS = 3 * 60 * 1000L
    // 并行兜底闹钟：和主闹钟同一时刻，但使用 setExactAndAllowWhileIdle 机制
    // 如果 vivo 仅延迟了 setAlarmClock，setExactAndAllowWhileIdle 可能正常到达
    // 兜底闹钟2：主闹钟后 30 秒检查
    private const val BACKUP_DELAY_MS = 30 * 1000L

    enum class VibrationMode {
        FIXED,
        PROGRESSIVE
    }

    enum class UnlockMode {
        SINGLE,
        TWO_STAGE
    }

    enum class AlarmMode {
        VIBRATE_AND_RING,
        VIBRATE_ONLY,
        RING_ONLY
    }

    data class AlarmEntry(
        val id: Int,
        val hour: Int,
        val minute: Int,
        val enabled: Boolean,
        val days: BooleanArray,
        val useQr: Boolean = true,
        val useMath: Boolean = false,
        val usePoem: Boolean = false,
        val useWord: Boolean = false,
        val mathCount: Int = 2,
        val poemCount: Int = 3,
        val wordCount: Int = 10,
        val clozeCount: Int = 5,
        val customCount: Int = 3,
        val vibrationIntensity: Int = 7,
        val vibrationMode: VibrationMode = VibrationMode.PROGRESSIVE,
        val unlockMode: UnlockMode = UnlockMode.SINGLE,
        val stage1Types: Set<String> = setOf("qr", "math"),
        val stage2Types: Set<String> = setOf("math", "poem", "word"),
        val stageCooldownSeconds: Int = 0,
        val ringtoneUri: String? = null,
        val alarmMode: AlarmMode = AlarmMode.VIBRATE_AND_RING,
        val ringVolume: Int = 7,
        val progressiveMinIntensity: Int = 1,
        val progressiveMaxIntensity: Int = 10,
        val progressiveMaxRingVolume: Int = 10,
        val autoEscalate: Boolean = false,  // 5分钟后突破最大值继续渐进至10
        val scheduleToken: String = "",
        val stage1CustomBanks: Map<String, Int> = emptyMap(),
        val stage2CustomBanks: Map<String, Int> = emptyMap()
    )

    /** 生成一个不可复用的调度令牌 */
    internal fun generateScheduleToken(): String = UUID.randomUUID().toString()

    fun getAlarms(context: Context): MutableList<AlarmEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ALARMS_JSON, "[]") ?: "[]"
        val list = mutableListOf<AlarmEntry>()
        val json = JSONArray(raw)
        for (i in 0 until json.length()) {
            val obj = json.optJSONObject(i) ?: continue
            list.add(fromJson(obj))
        }
        return list
    }

    fun saveAlarms(context: Context, alarms: List<AlarmEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. 取消所有旧 PendingIntent(包括 0~nextId-1 的孤儿,以及 id=0 的测试残留)
        // 范围从 0 开始(原来是 1),确保 id=0 也能被清理
        val oldNextId = prefs.getInt(KEY_NEXT_ID, 1)
        for (id in 0 until oldNextId) {
            cancelAlarm(context, id)
        }

        // 2. 重排 id(保持列表顺序,只改 id 编号为 1..N)
        val renumbered = renumberAlarms(alarms)

        // 3. 同步重置依赖旧 id 的 prefs(recovery_count, snooze_used 等)
        // id 重排后,旧 id 已无意义,这些统计数据应该清零
        resetIdDependents(context)

        // 4. 保存新 JSON + 新的 nextId
        val json = JSONArray()
        renumbered.forEach { json.put(toJson(it)) }
        prefs.edit()
            .putString(KEY_ALARMS_JSON, json.toString())
            .putInt(KEY_NEXT_ID, renumbered.size + 1)
            .apply()

        // 5. 重新调度(clean 状态,scheduleAll 内的 cancelAll 不会误删)
        scheduleAll(context)
    }

    /**
     * 将闹钟列表重排为 1..N 连续 id。保持原列表顺序(顺序即 UI 显示顺序),
     * 只重新分配 id 编号。删除闹钟后,剩余闹钟的 id 自动压缩,新 id 从空位补。
     */
    fun renumberAlarms(alarms: List<AlarmEntry>): List<AlarmEntry> {
        return alarms.mapIndexed { index, alarm -> alarm.copy(id = index + 1) }
    }

    /**
     * 重置依赖 alarmId 的 SharedPreferences key:
     * - alarm_recovery_count_X
     * - snooze_used_X
     * id 重排后这些 key 对应的 id 已失效,继续保留会造成历史脏数据。
     */
    private fun resetIdDependents(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var cleared = 0
        for (key in prefs.all.keys) {
            if (key.startsWith(PREF_RECOVERY_PREFIX) || key.startsWith(KEY_SNOOZE_USED_PREFIX)) {
                editor.remove(key)
                cleared++
            }
        }
        if (cleared > 0) {
            editor.apply()
            Log.i("AlarmScheduler", "renumber: reset $cleared id-dependent prefs (recovery_count + snooze_used)")
        }
    }

    /**
     * App 启动时调用,清理历史脏数据:
     * - 取消 id=0 的孤儿 PendingIntent(测试对话框残留的 RebootSafetyNet)
     * - 清理 alarm_recovery_count_0
     * - 如果发现 id 不连续(含 0 或有空洞),触发完整 renumber
     * 幂等操作:数据干净时不做任何事。
     */
    fun cleanupAndRenumberOnBoot(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentAlarms = getAlarms(context)

        val ids = currentAlarms.map { it.id }.toSet()
        val isContiguous = ids.isEmpty() || ids == (1..ids.size).toSet()
        val hasZero = ids.contains(0)

        if (isContiguous && !hasZero) {
            // 数据干净,只做基本清理(id=0 残留 + recovery_count_0)
            cancelAlarm(context, TEST_ALARM_ID)
            prefs.edit().remove("${PREF_RECOVERY_PREFIX}$TEST_ALARM_ID").apply()
            Log.d("AlarmScheduler", "boot cleanup: data clean, only cleared id=0 residue")
            return
        }

        Log.i("AlarmScheduler", "boot cleanup: data dirty (contiguous=$isContiguous, hasZero=$hasZero, size=${currentAlarms.size}), full renumber")
        saveAlarms(context, currentAlarms)
    }

    fun createAlarm(
        context: Context,
        hour: Int,
        minute: Int,
        enabled: Boolean,
        days: BooleanArray,
        useQr: Boolean = true,
        useMath: Boolean = false,
        usePoem: Boolean = false,
        useWord: Boolean = false,
        mathCount: Int = 2,
        poemCount: Int = 3,
        wordCount: Int = 10,
        clozeCount: Int = 5,
        customCount: Int = 3,
        vibrationIntensity: Int = 7,
        vibrationMode: VibrationMode = VibrationMode.PROGRESSIVE,
        unlockMode: UnlockMode = UnlockMode.SINGLE,
        stage1Types: Set<String> = setOf("qr", "math"),
        stage2Types: Set<String> = setOf("math", "poem", "word"),
        stageCooldownSeconds: Int = 0,
        ringtoneUri: String? = null,
        alarmMode: AlarmMode = AlarmMode.VIBRATE_AND_RING,
        ringVolume: Int = 7,
        progressiveMinIntensity: Int = 1,
        progressiveMaxIntensity: Int = 10,
        progressiveMaxRingVolume: Int = 10,
        autoEscalate: Boolean = false,
        stage1CustomBanks: Map<String, Int> = emptyMap(),
        stage2CustomBanks: Map<String, Int> = emptyMap()
    ): AlarmEntry {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nextId = prefs.getInt(KEY_NEXT_ID, 1)
        prefs.edit().putInt(KEY_NEXT_ID, nextId + 1).apply()
        return AlarmEntry(
            nextId, hour, minute, enabled, days,
            useQr, useMath, usePoem, useWord,
            mathCount, poemCount, wordCount, clozeCount, customCount,
            vibrationIntensity, vibrationMode, unlockMode,
            stage1Types, stage2Types, stageCooldownSeconds,
            ringtoneUri, alarmMode, ringVolume,
            progressiveMinIntensity, progressiveMaxIntensity, progressiveMaxRingVolume,
            autoEscalate,
            stage1CustomBanks = stage1CustomBanks,
            stage2CustomBanks = stage2CustomBanks
        )
    }

    fun getSnoozeMinutes(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SNOOZE_MINUTES, DEFAULT_SNOOZE_MINUTES)
    }

    fun setSnoozeMinutes(context: Context, minutes: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SNOOZE_MINUTES, minutes).apply()
    }

    fun getMaxSnoozeCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_SNOOZE_COUNT, DEFAULT_MAX_SNOOZE_COUNT)
    }

    fun setMaxSnoozeCount(context: Context, count: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_MAX_SNOOZE_COUNT, count).apply()
    }

    // ----- 默认震动强度（适用于新建闹钟的全局默认值）-----
    private const val KEY_DEFAULT_VIBRATION_INTENSITY = "default_vibration_intensity"
    private const val DEFAULT_VIBRATION_INTENSITY = 7

    // ----- 渐进式最小/最大强度 -----
    private const val KEY_DEFAULT_PROGRESSIVE_MIN = "default_progressive_min_intensity"
    private const val KEY_DEFAULT_PROGRESSIVE_MAX = "default_progressive_max_intensity"
    private const val DEFAULT_PROGRESSIVE_MIN_INTENSITY = 1
    private const val DEFAULT_PROGRESSIVE_MAX_INTENSITY = 10

    private const val KEY_DEFAULT_MATH_COUNT = "default_math_count"
    private const val KEY_DEFAULT_POEM_COUNT = "default_poem_count"
    private const val KEY_DEFAULT_WORD_COUNT = "default_word_count"
    private const val KEY_DEFAULT_CLOZE_COUNT = "default_cloze_count"
    private const val DEFAULT_MATH_COUNT = 2
    private const val DEFAULT_POEM_COUNT = 3
    private const val DEFAULT_WORD_COUNT = 10
    private const val DEFAULT_CLOZE_COUNT = 5

    private const val KEY_STAGE_COOLDOWN_SECONDS = "stage_cooldown_seconds"
    private const val KEY_DEFAULT_STAGE_COOLDOWN_SECONDS = "default_stage_cooldown_seconds"
    private const val DEFAULT_STAGE_COOLDOWN_SECONDS = 0

    fun getDefaultVibrationIntensity(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DEFAULT_VIBRATION_INTENSITY, DEFAULT_VIBRATION_INTENSITY).coerceIn(1, 10)
    }

    fun setDefaultVibrationIntensity(context: Context, intensity: Int) {
        val value = intensity.coerceIn(1, 10)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DEFAULT_VIBRATION_INTENSITY, value).apply()
    }

    fun getDefaultProgressiveMinIntensity(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DEFAULT_PROGRESSIVE_MIN, DEFAULT_PROGRESSIVE_MIN_INTENSITY).coerceIn(1, 10)
    }
    fun setDefaultProgressiveMinIntensity(context: Context, value: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DEFAULT_PROGRESSIVE_MIN, value.coerceIn(1, 10)).apply()
    }

    fun getDefaultProgressiveMaxIntensity(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DEFAULT_PROGRESSIVE_MAX, DEFAULT_PROGRESSIVE_MAX_INTENSITY).coerceIn(1, 10)
    }
    fun setDefaultProgressiveMaxIntensity(context: Context, value: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DEFAULT_PROGRESSIVE_MAX, value.coerceIn(1, 10)).apply()
    }

    // ----- 渐进式铃声音量最大强度 -----
    private const val KEY_DEFAULT_PROGRESSIVE_MAX_RING = "default_progressive_max_ring"
    private const val DEFAULT_PROGRESSIVE_MAX_RING = 10
    fun getDefaultProgressiveMaxRingVolume(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DEFAULT_PROGRESSIVE_MAX_RING, DEFAULT_PROGRESSIVE_MAX_RING).coerceIn(1, 10)
    }
    fun setDefaultProgressiveMaxRingVolume(context: Context, value: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DEFAULT_PROGRESSIVE_MAX_RING, value.coerceIn(1, 10)).apply()
    }

    // ----- 5分钟后自动升级到最大（适用于所有震动模式）-----
    private const val KEY_DEFAULT_AUTO_ESCALATE = "default_auto_escalate"
    private const val DEFAULT_AUTO_ESCALATE = false

    fun getDefaultAutoEscalate(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEFAULT_AUTO_ESCALATE, DEFAULT_AUTO_ESCALATE)
    }

    fun setDefaultAutoEscalate(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEFAULT_AUTO_ESCALATE, enabled).apply()
    }

    // ----- 默认挑战题目数量（适用于新建闹钟的全局默认值）-----

    fun getDefaultMathCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DEFAULT_MATH_COUNT, DEFAULT_MATH_COUNT).coerceIn(1, 100)
    }

    fun setDefaultMathCount(context: Context, count: Int) {
        val value = count.coerceIn(1, 100)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DEFAULT_MATH_COUNT, value).apply()
    }

    fun getDefaultPoemCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DEFAULT_POEM_COUNT, DEFAULT_POEM_COUNT).coerceIn(1, 100)
    }

    fun setDefaultPoemCount(context: Context, count: Int) {
        val value = count.coerceIn(1, 100)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DEFAULT_POEM_COUNT, value).apply()
    }

    fun getDefaultWordCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DEFAULT_WORD_COUNT, DEFAULT_WORD_COUNT).coerceIn(1, 100)
    }

    fun setDefaultWordCount(context: Context, count: Int) {
        val value = count.coerceIn(1, 100)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DEFAULT_WORD_COUNT, value).apply()
    }

    // ----- 选词填空默认数量 -----
    fun getDefaultClozeCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DEFAULT_CLOZE_COUNT, DEFAULT_CLOZE_COUNT).coerceIn(1, 100)
    }

    fun setDefaultClozeCount(context: Context, count: Int) {
        val value = count.coerceIn(1, 100)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DEFAULT_CLOZE_COUNT, value).apply()
    }

    fun getDefaultStageCooldownSeconds(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DEFAULT_STAGE_COOLDOWN_SECONDS, DEFAULT_STAGE_COOLDOWN_SECONDS).coerceIn(0, 3600)
    }

    fun setDefaultStageCooldownSeconds(context: Context, seconds: Int) {
        val value = seconds.coerceIn(0, 3600)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DEFAULT_STAGE_COOLDOWN_SECONDS, value).apply()
    }

    private const val KEY_DEFAULT_RINGTONE_URI = "default_ringtone_uri"
    private const val KEY_DEFAULT_ALARM_MODE = "default_alarm_mode"

    fun getDefaultRingtoneUri(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEFAULT_RINGTONE_URI, null)
    }

    fun setDefaultRingtoneUri(context: Context, uri: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (uri != null) prefs.edit().putString(KEY_DEFAULT_RINGTONE_URI, uri).apply()
        else prefs.edit().remove(KEY_DEFAULT_RINGTONE_URI).apply()
    }

    fun getDefaultAlarmMode(context: Context): AlarmMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeStr = prefs.getString(KEY_DEFAULT_ALARM_MODE, null) ?: return AlarmMode.VIBRATE_AND_RING
        return try { AlarmMode.valueOf(modeStr) } catch (e: Exception) { AlarmMode.VIBRATE_AND_RING }
    }

    fun setDefaultAlarmMode(context: Context, mode: AlarmMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEFAULT_ALARM_MODE, mode.name).apply()
    }

    private const val KEY_DEFAULT_RING_VOLUME = "default_ring_volume"
    private const val DEFAULT_RING_VOLUME = 7

    fun getDefaultRingVolume(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DEFAULT_RING_VOLUME, DEFAULT_RING_VOLUME).coerceIn(1, 10)
    }

    fun setDefaultRingVolume(context: Context, volume: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DEFAULT_RING_VOLUME, volume.coerceIn(1, 10)).apply()
    }

    private const val KEY_DEFAULT_VIBRATION_MODE = "default_vibration_mode"

    fun getDefaultVibrationMode(context: Context): VibrationMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeStr = prefs.getString(KEY_DEFAULT_VIBRATION_MODE, null) ?: return VibrationMode.PROGRESSIVE
        return try { VibrationMode.valueOf(modeStr) } catch (e: Exception) { VibrationMode.PROGRESSIVE }
    }

    fun setDefaultVibrationMode(context: Context, mode: VibrationMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEFAULT_VIBRATION_MODE, mode.name).apply()
    }

    // ----- 默认解锁模式 & 挑战类型（适用于新建闹钟的全局默认值）-----
    private const val KEY_DEFAULT_UNLOCK_MODE = "default_unlock_mode"
    private const val KEY_DEFAULT_STAGE1_TYPES = "default_stage1_types"
    private const val KEY_DEFAULT_STAGE2_TYPES = "default_stage2_types"

    fun getDefaultUnlockMode(context: Context): UnlockMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeStr = prefs.getString(KEY_DEFAULT_UNLOCK_MODE, null) ?: return UnlockMode.SINGLE
        return try { UnlockMode.valueOf(modeStr) } catch (e: Exception) { UnlockMode.SINGLE }
    }

    fun setDefaultUnlockMode(context: Context, mode: UnlockMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEFAULT_UNLOCK_MODE, mode.name).apply()
    }

    fun getDefaultStage1Types(context: Context): Set<String> {
        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) { null }
        val stored = prefs?.getStringSet(KEY_DEFAULT_STAGE1_TYPES, null)
        if (stored != null && stored.isNotEmpty()) return stored
        val hasCamera = try {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        return if (hasCamera) setOf("qr", "math") else setOf("math")
    }

    fun setDefaultStage1Types(context: Context, types: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_DEFAULT_STAGE1_TYPES, types).apply()
    }

    fun getDefaultStage2Types(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_DEFAULT_STAGE2_TYPES, null)
        if (stored != null && stored.isNotEmpty()) return stored
        return setOf("math", "poem", "word")  // fallback: math + poem + word
    }

    fun setDefaultStage2Types(context: Context, types: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_DEFAULT_STAGE2_TYPES, types).apply()
    }

    fun getStageCooldownSeconds(context: Context, alarmId: Int): Int {
        val alarm = getAlarms(context).find { it.id == alarmId }
        return alarm?.stageCooldownSeconds ?: getDefaultStageCooldownSeconds(context)
    }

    fun setStageCooldownSeconds(context: Context, alarmId: Int, seconds: Int) {
        val alarms = getAlarms(context)
        val idx = alarms.indexOfFirst { it.id == alarmId }
        if (idx >= 0) {
            alarms[idx] = alarms[idx].copy(stageCooldownSeconds = seconds.coerceIn(0, 3600))
            saveAlarms(context, alarms)
        }
    }

    // 兼容旧的全局震动键（如需要）
    fun getVibrationIntensity(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_VIBRATION_INTENSITY, DEFAULT_VIBRATION_INTENSITY).coerceIn(1, 10)
    }

    fun setVibrationIntensity(context: Context, intensity: Int) {
        val safeIntensity = intensity.coerceIn(1, 10)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_VIBRATION_INTENSITY, safeIntensity).apply()
    }

    fun getSnoozeUsedCount(context: Context, alarmId: Int): Int {
        if (alarmId <= 0) return 0
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SNOOZE_USED_PREFIX + alarmId, 0)
    }

    fun getRemainingSnoozeCount(context: Context, alarmId: Int): Int {
        if (alarmId <= 0) return 0
        return (getMaxSnoozeCount(context) - getSnoozeUsedCount(context, alarmId)).coerceAtLeast(0)
    }

    fun resetSnoozeCount(context: Context, alarmId: Int) {
        if (alarmId <= 0) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SNOOZE_USED_PREFIX + alarmId, 0).apply()
    }

    fun recordSnoozeUsed(context: Context, alarmId: Int): Boolean {
        if (alarmId <= 0) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val used = getSnoozeUsedCount(context, alarmId)
        val max = getMaxSnoozeCount(context)
        if (used >= max) return false
        prefs.edit().putInt(KEY_SNOOZE_USED_PREFIX + alarmId, used + 1).apply()
        return true
    }

    fun scheduleAll(context: Context) {
        cancelAll(context)
        val alarms = getAlarms(context)
        alarms.filter { it.enabled }.forEach { scheduleNext(context, it) }

        // 启动/刷新 AlarmGuardian 前台守护（替代 AlarmManager 精准触发）
        AlarmGuardianService.startIfNeeded(context)
    }

    fun scheduleNext(context: Context, alarm: AlarmEntry) {
        // 生成新的调度令牌，写入 alarm record
        val token = generateScheduleToken()
        val updatedAlarm = alarm.copy(scheduleToken = token)
        // 持久化 token 到存储
        val alarms = getAlarms(context).toMutableList()
        val idx = alarms.indexOfFirst { it.id == alarm.id }
        if (idx >= 0) {
            alarms[idx] = updatedAlarm
            val json = JSONArray()
            alarms.forEach { json.put(toJson(it)) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ALARMS_JSON, json.toString()).apply()
        }

        // [OEM兼容] 检查电池优化状态，提醒用户关闭
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    Log.w("AlarmScheduler", "BATTERY OPTIMIZATION IS ON — this may cause alarm delays on OEM ROMs like vivo/OPPO/Xiaomi!")
                    Log.w("AlarmScheduler", "Recommend: Settings → Apps → IronWill → Battery → Unrestricted")
                    LogExporter.logEvent("AlarmScheduler", "BATTERY_OPT_NOT_WHITELISTED — alarm delays likely on vivo/OPPO/Xiaomi")
                }
            } catch (e: Exception) {
                Log.w("AlarmScheduler", "Battery optimization check failed", e)
            }
        }

        try {
            val triggerAt = calculateNextTrigger(alarm)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // [Android 12+] 运行时验证精确闹钟权限是否仍然有效
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!alarmManager.canScheduleExactAlarms()) {
                    Log.w("AlarmScheduler", "scheduleNext: canScheduleExactAlarms=false, falling back!")
                    LogExporter.logEvent("AlarmScheduler", "SCHEDULE_EXACT_ALARM_REVOKED — alarm precision degraded")
                    scheduleNextFallback(context, updatedAlarm)
                    return
                }
            }

            val operation = buildPendingIntent(context, alarm.id, ACTION_ALARM_TRIGGER, AlarmIntentKind.MAIN, token, triggerAt)
            val showIntent = buildShowIntent(context, alarm.id)
            val alarmInfo = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
            alarmManager.setAlarmClock(alarmInfo, operation)
            val triggerDate = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(triggerAt))
            Log.i("AlarmScheduler", "scheduleNext: alarmId=${alarm.id} at $triggerAt ($triggerDate) - setAlarmClock (showIntent=${showIntent != null}) [token=${token.take(8)}]")
            LogExporter.logEvent("AlarmScheduler", "Alarm scheduled: id=${alarm.id} at $triggerDate, showIntent_valid=${showIntent != null}")

            // Event-driven logging: one ALARM_SCHEDULE draft after the AlarmManager accepted the
            // schedule. Failure paths (fallback / SecurityException / generic Exception) must NOT
            // be promoted to a success event below.
            alarmProtectionEventBridge.recordAlarmSchedule(
                alarmId = alarm.id.toString(),
                scheduledAtMs = triggerAt,
                ruleId = ruleIdForSchedule(alarm),
                result = "SCHEDULED"
            )

            // 📅 调度 INFO 事件
            val nextDate = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(triggerAt))
            val batteryOptStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (pm.isIgnoringBatteryOptimizations(context.packageName)) "关" else "开"
                } catch (e: Exception) { "?" }
            } else "N/A"
            LogExporter.i("Alarm", "📅 调度 | id=${alarm.id} | next=$nextDate | showIntent=${if (showIntent != null) "有效" else "无效"} | batteryOpt=$batteryOptStr | token=${token.take(8)}")

            // 分层备份策略（4层）
            schedulePreWarm(context, alarm, triggerAt)
            scheduleBackup(context, alarm, triggerAt)
            scheduleWorkManagerBackup(context, alarm, triggerAt)

            // 💓 远距心跳保活
            AlarmHeartbeatWorker.schedule(context, alarm.id, triggerAt)

            // 刷新 Guardian
            AlarmGuardianService.startIfNeeded(context)
        } catch (e: SecurityException) {
            Log.w("AlarmScheduler", "scheduleNext: setAlarmClock failed (no SCHEDULE_EXACT_ALARM): ${e.message}")
            Log.w("AlarmScheduler", "WARN: Falling back to setExactAndAllowWhileIdle due to missing SCHEDULE_EXACT_ALARM permission")
            scheduleNextFallback(context, updatedAlarm)
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "scheduleNext failed: ${e.message}")
        }
    }

    /**
     * 预热闹钟：在主闹钟前 3 分钟触发，用 setExactAndAllowWhileIdle(RTC) 提前唤醒 CPU。
     */
    private fun schedulePreWarm(context: Context, alarm: AlarmEntry, mainTriggerAt: Long) {
        try {
            val preWarmAt = mainTriggerAt - PRE_WARM_ADVANCE_MS
            if (preWarmAt <= System.currentTimeMillis()) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val operation = buildPendingIntent(context, alarm.id, ACTION_ALARM_PRE_WARM, AlarmIntentKind.PREWARM, alarm.scheduleToken, preWarmAt)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preWarmAt, operation)
            val date = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(preWarmAt))
            Log.i("AlarmScheduler", "schedulePreWarm: alarmId=${alarm.id} at $preWarmAt ($date)")
            LogExporter.logEvent("AlarmScheduler", "PreWarm scheduled: id=${alarm.id} at $date")
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "schedulePreWarm failed: ${e.message}")
        }
    }

    private fun scheduleSameTimeBackup(context: Context, alarm: AlarmEntry, mainTriggerAt: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val operation = buildPendingIntent(context, alarm.id, ACTION_ALARM_BACKUP, AlarmIntentKind.BACKUP, alarm.scheduleToken, mainTriggerAt)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, mainTriggerAt, operation)
            val date = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(mainTriggerAt))
            Log.i("AlarmScheduler", "scheduleSameTimeBackup: alarmId=${alarm.id} at $mainTriggerAt ($date)")
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "scheduleSameTimeBackup failed: ${e.message}")
        }
    }

    private fun scheduleBackup(context: Context, alarm: AlarmEntry, mainTriggerAt: Long) {
        try {
            val delayFromNow = mainTriggerAt - System.currentTimeMillis() + BACKUP_DELAY_MS
            if (delayFromNow <= 0) return
            val backupAt = android.os.SystemClock.elapsedRealtime() + delayFromNow
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val operation = buildPendingIntent(context, alarm.id, ACTION_ALARM_BACKUP2, AlarmIntentKind.BACKUP2, alarm.scheduleToken, System.currentTimeMillis() + delayFromNow)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, backupAt, operation)
            val date = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(System.currentTimeMillis() + delayFromNow))
            Log.i("AlarmScheduler", "scheduleBackup: alarmId=${alarm.id} at $backupAt ($date) [ELAPSED_REALTIME]")
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "scheduleBackup failed: ${e.message}")
        }
    }

    /**
     * [防御性代码] 当 setAlarmClock 因缺少权限失败时的降级方案。
     */
    private fun scheduleNextFallback(context: Context, alarm: AlarmEntry) {
        try {
            val triggerAt = calculateNextTrigger(alarm)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val token = alarm.scheduleToken.ifBlank { generateScheduleToken() }
            val operation = buildPendingIntent(context, alarm.id, ACTION_ALARM_TRIGGER, AlarmIntentKind.MAIN, token, triggerAt)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
            val triggerDate = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(triggerAt))
            Log.i("AlarmScheduler", "scheduleNextFallback: alarmId=${alarm.id} at $triggerAt ($triggerDate) - fallback method with reduced precision")
            scheduleBackup(context, alarm, triggerAt)
            scheduleWorkManagerBackup(context, alarm, triggerAt)
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "scheduleNextFallback failed: ${e.message}")
        }
    }

    fun scheduleSnooze(context: Context, alarmId: Int, minutes: Int) {
        if (!recordSnoozeUsed(context, alarmId)) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        val operation = buildPendingIntent(context, alarmId, ACTION_ALARM_SNOOZE, AlarmIntentKind.SNOOZE, "", triggerAt)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
    }

    fun cancelAll(context: Context) {
        val alarms = getAlarms(context)
        val existingIds = alarms.map { it.id }.toSet()
        alarms.forEach { cancelAlarm(context, it.id) }

        val nextId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_NEXT_ID, 1)
        for (id in 0 until nextId) {
            if (id !in existingIds) {
                cancelAlarm(context, id)
            }
        }

        AlarmGuardianService.stopIfNoAlarms(context)
    }

    fun cancelAlarm(context: Context, alarmId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(
            Pair(ACTION_ALARM_TRIGGER, AlarmIntentKind.MAIN),
            Pair(ACTION_ALARM_SNOOZE, AlarmIntentKind.SNOOZE),
            Pair(ACTION_ALARM_PRE_WARM, AlarmIntentKind.PREWARM),
            Pair(ACTION_ALARM_BACKUP, AlarmIntentKind.BACKUP),
            Pair(ACTION_ALARM_BACKUP2, AlarmIntentKind.BACKUP2)
        ).forEach { (action, _) ->
            // 取消时使用旧的 buildPendingIntent（不传 token，因为取消只依赖 identity）
            val operation = buildPendingIntent(context, alarmId, action, null, "", null)
            alarmManager.cancel(operation)
            operation.cancel()
        }
        AlarmHeartbeatScheduler.cancel(context, alarmId)
        AlarmHeartbeatWorker.cancel(context, alarmId)
    }

    fun findAlarm(context: Context, alarmId: Int): AlarmEntry? {
        return getAlarms(context).firstOrNull { it.id == alarmId }
    }

    // ----- 闹钟活动状态（用于重启后恢复震动）-----

    private fun getAlarmPrefs(context: Context): android.content.SharedPreferences {
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setAlarmActive(context: Context, alarmId: Int) {
        val devicePrefs = getAlarmPrefs(context)
        val wasSameActiveAlarm = devicePrefs.getBoolean(KEY_ALARM_ACTIVE, false) &&
            devicePrefs.getInt(KEY_ACTIVE_ALARM_ID, -1) == alarmId
        val firstStagePassed = if (wasSameActiveAlarm) {
            devicePrefs.getBoolean(KEY_ACTIVE_FIRST_STAGE_PASSED, false)
        } else {
            false
        }

        // 获取当前 alarm 的 scheduleToken
        val alarm = getAlarms(context).firstOrNull { it.id == alarmId }
        val currentToken = alarm?.scheduleToken ?: ""

        // A real wake session starts when the alarm becomes active. Pre-warm does
        // not call setAlarmActive, and recovery of the same session must not reset it.
        if (!wasSameActiveAlarm && alarmId > 0 && alarm != null) {
            AlarmWakeStatsRecorder.startSession(
                context = context,
                alarmId = alarmId,
                plannedMinuteOfDay = alarm.hour * 60 + alarm.minute
            )
        }

        try {
            val normalPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            normalPrefs.edit()
                .putBoolean(KEY_ALARM_ACTIVE, true)
                .putInt(KEY_ACTIVE_ALARM_ID, alarmId)
                .putBoolean(KEY_ACTIVE_FIRST_STAGE_PASSED, firstStagePassed)
                .putString(KEY_ACTIVE_SCHEDULE_TOKEN, currentToken)
                .commit()
        } catch (e: IllegalStateException) {
            android.util.Log.w("AlarmScheduler", "setAlarmActive: CE storage unavailable (Direct Boot), writing DPC only")
        }

        devicePrefs.edit()
            .putBoolean(KEY_ALARM_ACTIVE, true)
            .putInt(KEY_ACTIVE_ALARM_ID, alarmId)
            .putBoolean(KEY_ACTIVE_FIRST_STAGE_PASSED, firstStagePassed)
            .putString(KEY_ACTIVE_SCHEDULE_TOKEN, currentToken)
            .commit()

        if (!wasSameActiveAlarm || getRecoveryCountSafe(context, alarmId) == 0) {
            scheduleRecoveryAlarm(context, alarmId)
            scheduleRebootSafetyNetAlarm(context, alarmId)
        }

        android.util.Log.i("AlarmScheduler", "setAlarmActive: alarmId=$alarmId (sameActive=$wasSameActiveAlarm, firstStagePassed=$firstStagePassed, token=${currentToken.take(8)})")
    }

    /** 读取 active 状态的 scheduleToken（用于 Receiver 校验） */
    fun getActiveScheduleToken(context: Context): String {
        val prefs = getAlarmPrefs(context)
        return prefs.getString(KEY_ACTIVE_SCHEDULE_TOKEN, "") ?: ""
    }

    fun markFirstStagePassed(context: Context, passed: Boolean) {
        if (passed) {
            val now = System.currentTimeMillis()
            AlarmWakeStatsRecorder.markFirstStagePassed(context, getActiveAlarmId(context), now)
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putLong(KEY_FIRST_STAGE_PASSED_AT, now).commit()
            } catch (e: IllegalStateException) {
                android.util.Log.w("AlarmScheduler", "markFirstStagePassed: CE unavailable")
            }
            getAlarmPrefs(context).edit().putLong(KEY_FIRST_STAGE_PASSED_AT, now).commit()
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ACTIVE_FIRST_STAGE_PASSED, passed).commit()
        } catch (e: IllegalStateException) {
            android.util.Log.w("AlarmScheduler", "markFirstStagePassed: CE unavailable for flag write")
        }
        getAlarmPrefs(context).edit().putBoolean(KEY_ACTIVE_FIRST_STAGE_PASSED, passed).commit()
    }

    fun isFirstStagePassed(context: Context): Boolean {
        val prefs = getAlarmPrefs(context)
        return prefs.getBoolean(KEY_ACTIVE_FIRST_STAGE_PASSED, false)
    }

    fun getFirstStagePassedAt(context: Context): Long {
        return getAlarmPrefs(context).getLong(KEY_FIRST_STAGE_PASSED_AT, 0L)
    }

    fun saveVibrationLockedIntensity(context: Context, intensity: Int) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_VIBRATION_LOCKED_INTENSITY, intensity).apply()
        } catch (e: IllegalStateException) {
            android.util.Log.w("AlarmScheduler", "saveVibrationLockedIntensity: CE unavailable, DPC only")
        }
        getAlarmPrefs(context)
            .edit().putInt(KEY_VIBRATION_LOCKED_INTENSITY, intensity).apply()
    }

    fun getVibrationLockedIntensity(context: Context): Int {
        return getAlarmPrefs(context).getInt(KEY_VIBRATION_LOCKED_INTENSITY, 0)
    }

    fun saveVibrationStartTime(context: Context, time: Long) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_VIBRATION_START_TIME, time).apply()
        } catch (e: IllegalStateException) {
            android.util.Log.w("AlarmScheduler", "saveVibrationStartTime: CE unavailable, DPC only")
        }
        getAlarmPrefs(context)
            .edit().putLong(KEY_VIBRATION_START_TIME, time).apply()
    }

    fun getVibrationStartTime(context: Context): Long {
        return getAlarmPrefs(context).getLong(KEY_VIBRATION_START_TIME, 0L)
    }

    fun clearAlarmActive(context: Context) {
        try {
            val normalPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            normalPrefs.edit()
                .putBoolean(KEY_ALARM_ACTIVE, false)
                .remove(KEY_ACTIVE_ALARM_ID)
                .remove(KEY_ACTIVE_FIRST_STAGE_PASSED)
                .remove(KEY_VIBRATION_LOCKED_INTENSITY)
                .remove(KEY_VIBRATION_START_TIME)
                .remove(KEY_ACTIVE_SCHEDULE_TOKEN)
                .commit()
        } catch (e: IllegalStateException) {
            android.util.Log.w("AlarmScheduler", "clearAlarmActive: CE storage unavailable, clearing DPC only")
        }

        val devicePrefs = getAlarmPrefs(context)
        devicePrefs.edit()
            .putBoolean(KEY_ALARM_ACTIVE, false)
            .remove(KEY_ACTIVE_ALARM_ID)
            .remove(KEY_ACTIVE_FIRST_STAGE_PASSED)
            .remove(KEY_VIBRATION_LOCKED_INTENSITY)
            .remove(KEY_VIBRATION_START_TIME)
            .remove(KEY_ACTIVE_SCHEDULE_TOKEN)
            .commit()

        cancelRecoveryAlarm(context)
        cancelRebootSafetyNetAlarm(context)

        android.util.Log.i("AlarmScheduler", "clearAlarmActive (dual-cleared + recovery cancelled + safety net cancelled)")

        AlarmGuardianService.onAlarmDismissed()
    }

    fun getRecoveryCount(context: Context, alarmId: Int): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("${PREF_RECOVERY_PREFIX}$alarmId", 0)
    }

    private fun getRecoveryCountSafe(context: Context, alarmId: Int): Int {
        return try {
            getRecoveryCount(context, alarmId)
        } catch (e: IllegalStateException) {
            getAlarmPrefs(context).getInt("${PREF_RECOVERY_PREFIX}$alarmId", 0)
        }
    }

    fun incrementRecoveryCount(context: Context, alarmId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = getRecoveryCount(context, alarmId) + 1
        prefs.edit().putInt("${PREF_RECOVERY_PREFIX}$alarmId", count).apply()
    }

    fun resetRecoveryCount(context: Context, alarmId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove("${PREF_RECOVERY_PREFIX}$alarmId").apply()
    }

    fun scheduleRecoveryAlarm(context: Context, alarmId: Int) {
        incrementRecoveryCount(context, alarmId)
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val operation = PendingIntent.getBroadcast(
                context,
                alarmId + RECOVERY_REQUEST_OFFSET,
                Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_ALARM_RECOVERY
                    putExtra(EXTRA_ALARM_ID, alarmId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(operation)
            val triggerAtElapsed = android.os.SystemClock.elapsedRealtime() + RECOVERY_INTERVAL_MS
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtElapsed,
                operation
            )
            android.util.Log.i("AlarmScheduler", "Recovery alarm scheduled (ELAPSED): alarmId=$alarmId, count=${getRecoveryCount(context, alarmId)}")
        } catch (e: Exception) {
            android.util.Log.w("AlarmScheduler", "Recovery alarm failed: ${e.message}")
        }
    }

    fun scheduleRebootSafetyNetAlarm(context: Context, alarmId: Int) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val operation = PendingIntent.getBroadcast(
                context,
                alarmId + REBOOT_SAFETY_NET_OFFSET,
                Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_ALARM_REBOOT_SAFETY_NET
                    putExtra(EXTRA_ALARM_ID, alarmId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(operation)
            val triggerAt = System.currentTimeMillis() + 60_000L
            val showIntent = PendingIntent.getActivity(
                context,
                alarmId + REBOOT_SAFETY_NET_OFFSET + SHOW_REQUEST_OFFSET,
                Intent(context, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(EXTRA_ALARM_ID, alarmId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmInfo = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
            alarmManager.setAlarmClock(alarmInfo, operation)
            val triggerDate = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(triggerAt))
            Log.i("AlarmScheduler", "RebootSafetyNet scheduled: alarmId=$alarmId at $triggerAt ($triggerDate) - setAlarmClock")
            LogExporter.logEvent("AlarmRecovery", "RebootSafetyNet scheduled (id=$alarmId)")
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "RebootSafetyNet failed: ${e.message}")
        }
    }

    fun cancelRebootSafetyNetAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            for (id in 0..999) {
                val operation = PendingIntent.getBroadcast(
                    context,
                    id + REBOOT_SAFETY_NET_OFFSET,
                    Intent(context, AlarmReceiver::class.java).apply {
                        action = ACTION_ALARM_REBOOT_SAFETY_NET
                        putExtra(EXTRA_ALARM_ID, id)
                    },
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (operation != null) {
                    alarmManager.cancel(operation)
                    operation.cancel()
                }
            }
            Log.i("AlarmScheduler", "RebootSafetyNet cancelled")
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "Failed to cancel RebootSafetyNet: ${e.message}")
        }
    }

    fun cancelRecoveryAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            for (id in 0..999) {
                val operation = PendingIntent.getBroadcast(
                    context,
                    id + RECOVERY_REQUEST_OFFSET,
                    Intent(context, AlarmReceiver::class.java).apply {
                        action = ACTION_ALARM_RECOVERY
                        putExtra(EXTRA_ALARM_ID, id)
                    },
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (operation != null) {
                    alarmManager.cancel(operation)
                    operation.cancel()
                }
            }
            android.util.Log.i("AlarmScheduler", "Recovery alarms cancelled")
        } catch (e: Exception) {
            android.util.Log.w("AlarmScheduler", "Failed to cancel recovery alarms: ${e.message}")
        }
    }

    fun isAlarmActive(context: Context): Boolean {
        val prefs = getAlarmPrefs(context)
        val active = prefs.getBoolean(KEY_ALARM_ACTIVE, false)
        android.util.Log.i("AlarmScheduler", "isAlarmActive=$active (from device-protected storage)")
        return active
    }

    fun getActiveAlarmId(context: Context): Int {
        val prefs = getAlarmPrefs(context)
        val id = prefs.getInt(KEY_ACTIVE_ALARM_ID, -1)
        android.util.Log.i("AlarmScheduler", "getActiveAlarmId=$id (from device-protected storage)")
        return id
    }

    /**
     * 统一 PendingIntent 工厂方法。
     * 通过 kind + data URI + requestCode 确保 MAIN / PREWARM / RECOVERY / BACKUP 的 identity 不碰撞。
     * 创建和取消必须使用同一工厂，禁止各处手工拼接 Intent。
     *
     * @param kind 为 null 时使用旧行为（仅靠 action + requestCode），用于取消操作兼容。
     * @param token 为 null 时不写入 extras（用于取消操作）。
     * @param scheduledAtMillis 为 null 时不写入 extras（用于取消操作）。
     */
    internal fun buildPendingIntent(
        context: Context,
        alarmId: Int,
        action: String,
        kind: AlarmIntentKind?,
        token: String?,
        scheduledAtMillis: Long?
    ): PendingIntent {
        val requestCode = if (kind != null) {
            alarmId + kind.requestCodeOffset
        } else {
            when (action) {
                ACTION_ALARM_SNOOZE -> alarmId + SNOOZE_REQUEST_OFFSET
                ACTION_ALARM_PRE_WARM -> alarmId + PRE_WARM_REQUEST_OFFSET
                ACTION_ALARM_BACKUP -> alarmId + BACKUP_REQUEST_OFFSET
                ACTION_ALARM_BACKUP2 -> alarmId + BACKUP2_REQUEST_OFFSET
                else -> alarmId
            }
        }
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ALARM_ID, alarmId)
            if (token != null) {
                putExtra(EXTRA_SCHEDULE_TOKEN, token)
            }
            if (scheduledAtMillis != null) {
                putExtra(EXTRA_SCHEDULED_AT_MILLIS, scheduledAtMillis)
            }
            if (kind != null) {
                putExtra(EXTRA_INTENT_KIND, kind.name)
                // data URI 确保不同 kind 的 PendingIntent 不冲突
                data = Uri.parse("ironwill://alarm/${kind.dataPath}/$alarmId")
            }
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildShowIntent(context: Context, alarmId: Int): PendingIntent {
        return PendingIntent.getActivity(
            context,
            alarmId + SHOW_REQUEST_OFFSET,
            Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_ALARM_ID, alarmId)
                data = Uri.parse("ironwill://alarm/show/$alarmId")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun calculateNextTrigger(alarm: AlarmEntry): Long {
        val now = Calendar.getInstance()
        val activeDays = normalizeDays(alarm.days)
        for (offset in 0..6) {
            val candidate = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayIndex = calendarDayToIndex(candidate.get(Calendar.DAY_OF_WEEK))
            if (!activeDays[dayIndex]) continue
            if (offset == 0 && candidate.timeInMillis <= now.timeInMillis) continue
            return candidate.timeInMillis
        }

        val fallback = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return fallback.timeInMillis
    }

    fun getNextTriggerTime(alarm: AlarmEntry): Long {
        return calculateNextTrigger(alarm)
    }

    fun calculateNextTriggerForLogging(alarm: AlarmEntry): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun normalizeDays(days: BooleanArray): BooleanArray {
        if (days.size != 7) return BooleanArray(7) { true }
        if (days.none { it }) return BooleanArray(7) { true }
        return days
    }

    private fun calendarDayToIndex(day: Int): Int {
        return when (day) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }
    }

    private fun scheduleWorkManagerBackup(context: Context, alarm: AlarmEntry, triggerAt: Long) {
        try {
            val delay = triggerAt - System.currentTimeMillis()
            if (delay <= 0) return
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<AlarmWorker>()
                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(androidx.work.workDataOf(AlarmWorker.KEY_ALARM_ID to alarm.id))
                .addTag("alarm_backup_${alarm.id}")
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "alarm_work_${alarm.id}",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            val triggerDate = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(triggerAt))
            Log.i("AlarmScheduler", "scheduleWorkManagerBackup: alarmId=${alarm.id} at $triggerAt ($triggerDate)")
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "scheduleWorkManagerBackup failed: ${e.message}")
        }
    }

    fun triggerAlarmFromWorkManager(context: Context, alarmId: Int) {
        Log.w("AlarmScheduler", "triggerAlarmFromWorkManager: manually triggering alarm $alarmId")
        try {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_ALARM_TRIGGER
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "triggerAlarmFromWorkManager failed: ${e.message}")
        }
    }

    private fun scheduleWatchdogService(context: Context, alarm: AlarmEntry, triggerAt: Long) {
        try {
            val WATCHDOG_ADVANCE_MS = 2 * 60 * 1000L
            val startAt = triggerAt - WATCHDOG_ADVANCE_MS
            if (startAt <= System.currentTimeMillis()) {
                AlarmWatchdogService.start(context, alarm.id, triggerAt)
                Log.i("AlarmScheduler", "scheduleWatchdogService: starting immediately for alarmId=${alarm.id}")
                return
            }
            val delay = startAt - System.currentTimeMillis()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                AlarmWatchdogService.start(context, alarm.id, triggerAt)
                val triggerDate = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(triggerAt))
                Log.i("AlarmScheduler", "scheduleWatchdogService: started for alarmId=${alarm.id}, triggerAt=$triggerDate")
            }, delay)
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "scheduleWatchdogService failed: ${e.message}")
        }
    }

    internal fun toJson(alarm: AlarmEntry): JSONObject {
        val obj = JSONObject()
        obj.put("id", alarm.id)
        obj.put("hour", alarm.hour)
        obj.put("minute", alarm.minute)
        obj.put("enabled", alarm.enabled)
        val days = JSONArray()
        alarm.days.forEach { days.put(it) }
        obj.put("days", days)
        obj.put("useQr", alarm.useQr)
        obj.put("useMath", alarm.useMath)
        obj.put("usePoem", alarm.usePoem)
        obj.put("useWord", alarm.useWord)
        obj.put("mathCount", alarm.mathCount)
        obj.put("poemCount", alarm.poemCount)
        obj.put("wordCount", alarm.wordCount)
        obj.put("clozeCount", alarm.clozeCount)
        obj.put("customCount", alarm.customCount)
        obj.put("vibrationIntensity", alarm.vibrationIntensity)
        obj.put("vibrationMode", alarm.vibrationMode.name)
        obj.put("unlockMode", alarm.unlockMode.name)
        obj.put("stage1", JSONArray(alarm.stage1Types.toList()))
        obj.put("stage2", JSONArray(alarm.stage2Types.toList()))
        obj.put("stageCooldownSeconds", alarm.stageCooldownSeconds)
        obj.put("ringtoneUri", alarm.ringtoneUri ?: "")
        obj.put("alarmMode", alarm.alarmMode.name)
        obj.put("ringVolume", alarm.ringVolume)
        obj.put("progressiveMinIntensity", alarm.progressiveMinIntensity)
        obj.put("progressiveMaxIntensity", alarm.progressiveMaxIntensity)
        obj.put("progressiveMaxRingVolume", alarm.progressiveMaxRingVolume)
        obj.put("autoEscalate", alarm.autoEscalate)
        obj.put("scheduleToken", alarm.scheduleToken)
        val s1BanksObj = JSONObject()
        alarm.stage1CustomBanks.forEach { (k, v) -> s1BanksObj.put(k, v) }
        obj.put("stage1CustomBanks", s1BanksObj)
        val s2BanksObj = JSONObject()
        alarm.stage2CustomBanks.forEach { (k, v) -> s2BanksObj.put(k, v) }
        obj.put("stage2CustomBanks", s2BanksObj)
        return obj
    }

    internal fun fromJson(obj: JSONObject): AlarmEntry {
        val id = obj.optInt("id", 0)
        val hour = obj.optInt("hour", 7)
        val minute = obj.optInt("minute", 0)
        val enabled = obj.optBoolean("enabled", false)
        val daysJson = obj.optJSONArray("days")
        val days = BooleanArray(7) { true }
        if (daysJson != null && daysJson.length() == 7) {
            for (i in 0 until 7) {
                days[i] = daysJson.optBoolean(i, true)
            }
        }
        val useQr = obj.optBoolean("useQr", true)
        val useMath = obj.optBoolean("useMath", true)
        val usePoem = obj.optBoolean("usePoem", false)
        val useWord = obj.optBoolean("useWord", false)
        val mathCount = obj.optInt("mathCount", 2)
        val poemCount = obj.optInt("poemCount", 3)
        val wordCount = obj.optInt("wordCount", 10)
        val clozeCount = obj.optInt("clozeCount", 5)
        val customCount = obj.optInt("customCount", 3)
        val vibrationIntensity = obj.optInt("vibrationIntensity", 7)
        val vibrationMode = runCatching {
            VibrationMode.valueOf(obj.optString("vibrationMode", VibrationMode.PROGRESSIVE.name))
        }.getOrElse { VibrationMode.PROGRESSIVE }
        val stageCooldownSeconds = obj.optInt("stageCooldownSeconds", 0)
        val ringtoneUri = obj.optString("ringtoneUri", "").ifBlank { null }
        val alarmMode = runCatching {
            AlarmMode.valueOf(obj.optString("alarmMode", AlarmMode.VIBRATE_AND_RING.name))
        }.getOrElse { AlarmMode.VIBRATE_AND_RING }
        val ringVolume = obj.optInt("ringVolume", DEFAULT_RING_VOLUME).coerceIn(1, 10)
        val scheduleToken = obj.optString("scheduleToken", "")

        val unlockMode = runCatching {
            UnlockMode.valueOf(obj.optString("unlockMode", UnlockMode.SINGLE.name))
        }.getOrElse { UnlockMode.SINGLE }

        val s1Arr = obj.optJSONArray("stage1")
        val s2Arr = obj.optJSONArray("stage2")
        val stage1Types = if (s1Arr != null) {
            (0 until s1Arr.length()).map { s1Arr.getString(it) }.toSet()
        } else {
            val set = mutableSetOf<String>()
            if (useQr) set.add("qr")
            if (useMath) set.add("math")
            set
        }
        val stage2Types = if (s2Arr != null) {
            (0 until s2Arr.length()).map { s2Arr.getString(it) }.toSet()
        } else {
            val set = mutableSetOf<String>()
            if (useMath) set.add("math")
            if (usePoem) set.add("poem")
            if (useWord) set.add("word")
            set
        }

        val s1Banks = mutableMapOf<String, Int>()
        obj.optJSONObject("stage1CustomBanks")?.let { bObj ->
            bObj.keys().forEach { k -> s1Banks[k] = bObj.optInt(k, 3) }
        }
        val s2Banks = mutableMapOf<String, Int>()
        obj.optJSONObject("stage2CustomBanks")?.let { bObj ->
            bObj.keys().forEach { k -> s2Banks[k] = bObj.optInt(k, 3) }
        }

        val progressiveMinIntensity = obj.optInt("progressiveMinIntensity", 1).coerceIn(1, 10)
        val progressiveMaxIntensity = obj.optInt("progressiveMaxIntensity", 10).coerceIn(1, 10)
        val progressiveMaxRingVolume = obj.optInt("progressiveMaxRingVolume", 10).coerceIn(1, 10)
        val autoEscalate = obj.optBoolean("autoEscalate", false)

        return AlarmEntry(
            id, hour, minute, enabled, days,
            useQr, useMath, usePoem, useWord,
            mathCount, poemCount, wordCount, clozeCount, customCount,
            vibrationIntensity, vibrationMode, unlockMode,
            stage1Types, stage2Types, stageCooldownSeconds,
            ringtoneUri, alarmMode,
            ringVolume,
            progressiveMinIntensity, progressiveMaxIntensity, progressiveMaxRingVolume,
            autoEscalate,
            scheduleToken,
            stage1CustomBanks = s1Banks,
            stage2CustomBanks = s2Banks
        )
    }

    fun getDefaultCustomCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("default_custom_count", 3)
    }

    fun setDefaultCustomCount(context: Context, count: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("default_custom_count", count).apply()
    }

    /**
     * Event-driven logging helper. Returns a stable ruleId summarising the alarm's
     * weekday mask; used solely as a correlation key on the ALARM_SCHEDULE event.
     * Never alters scheduling or business decisions.
     */
    private fun ruleIdForSchedule(alarm: AlarmEntry): String {
        val activeDayIndexes = mutableListOf<Int>()
        for (i in 0 until 7) if (i < alarm.days.size && alarm.days[i]) activeDayIndexes += i
        return if (activeDayIndexes.isEmpty()) "DAILY" else activeDayIndexes.joinToString(",")
    }
}