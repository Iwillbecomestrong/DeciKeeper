/**
 * 文件路径: app/src/main/java/com/ironwill/app/manager/TimeRuleManager.kt
 * 文件说明: 时间规则管理器。负责时间段规则的计算、查询和持久化。
 * 主要功能:
 * - 存储和读取时间段配置 (JSON 格式)。
 * - 计算当前时间处于哪个时段 (getCurrentSegment)。
 * - 计算下一个时段的信息。
 * - 处理跨天的时间逻辑。
 * - 判定当前是否为“Gap”时间 (即未被任何勿扰时段覆盖的自由时间)。
 *
 * 主要函数:
 * - getCurrentSegment: 根据当前时间戳，返回对应的 TimeSegment 对象。
 * - getNextSegment: 获取当前时间之后的下一个时间段，用于预告。
 * - isFreeTimeNow: 判断当前是否在自由时间 (Gap)。
 * - saveConfig / loadConfig: 序列化/反序列化配置到 SharedPreferences。
 * - applyPendingConfig: 将暂存的明日配置应用到正式配置 (每日0点触发)。
 */
/**
 * =================================================================================
 * [Context Header]
 * * @file: TimeRuleManager.kt
 * * @role: Domain Skill
 * * @description: 勿扰时段规则与全局参数判定核心。
 * * @responsibilities:
 * 1. 管理时段规则存储与判定。
 * 2. 提供刷新时间与特权窗口逻辑。
 * * @context:
 * - 上游: TimeConfigFragment, MonitorService
 * - 下游: SharedPreferences
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 时间计算需稳定一致。
 * 3. 空值安全。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import com.decikeeper.app.data.AnalysisResult
import com.decikeeper.app.data.AndroidLogcatNormalEventSink
import com.decikeeper.app.data.DefaultLogDebugConfig
import com.decikeeper.app.data.DefaultNormalEventRenderer
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.data.PrivilegeWindow
import com.decikeeper.app.data.NormalEventLogger
import com.decikeeper.app.data.SegmentAdverseAnalysis
import com.decikeeper.app.data.SegmentAnalysis
import com.decikeeper.app.data.SystemEventClock
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.data.TimeSegmentJson
import com.decikeeper.app.service.MonitorService
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject












/**
 * WP-03-T01 typed minute-domain boundary result.
 *
 * Pure typed outcome for segment/refresh boundary validation. Carries a stable
 * reason string that downstream UI/log/save paths can surface without re-deriving
 * arithmetic. Empty-list segments are legal; malformed/empty results from the
 * caller are the caller's concern, not this type's.
 *
 * v3 SPEC §3.5 / §3.6: cross-midnight segments (endMins <= startMins) are
 * themselves legal and are NOT rejected here.
 */
sealed class BoundaryResult {
    /** Candidate relationship is legal; downstream may proceed. */
    object Legal : BoundaryResult()
    /** Candidate relationship is rejected; [reason] is stable, lowercase, ASCII. */
    data class Rejected(val reason: String) : BoundaryResult()
}

/**
 * WP-03-T01 typed minute-domain boundary validators.
 *
 * C-2 guard-rail: this object is pure candidate-interval arithmetic. It MUST
 * NOT read WP-02 runtime anchor priority, MUST NOT import CreditManager, and
 * MUST stay a pure function of its inputs. WP-03-T02 will gate the segment
 * save / refresh save paths on these results.
 *
 * Semantics (v3 SPEC §3.5/§3.6 + REVISE):
 *   - malformed inputs (out-of-range minute values, zero-length or inverted
 *     segments) → Rejected(Malformed*) — REVISE adds this guard rail.
 *   - segment invalid iff `start < refresh && refresh < end` (strict open
 *     interval) — existing behavior.
 *   - refresh invalid iff `start < candidate && candidate < end` for any
 *     non-cross-midnight segment that strictly contains it — existing
 *     behavior.
 *   - equality at endpoints (`start == refresh` / `end == refresh`) is legal.
 *   - cross-midnight segments (`end < start`) are themselves legal in the
 *     legacy data path, but per REVISE any new segment with `start >= end`
 *     is treated as Malformed (zero-length / inverted). The validator
 *     therefore requires `start < end` for new segments.
 *   - gap / empty segments are legal.
 */
object MinuteBoundary {

    /** Reject reason constants (stable, lowercase ASCII). */
    const val REASON_REFRESH_STRICTLY_INSIDE_SEGMENT =
        "refresh_time_strictly_inside_segment"
    const val REASON_CANDIDATE_STRICTLY_INSIDE_SEGMENT =
        "candidate_refresh_strictly_inside_segment"
    /** REVISE: input values outside the [0, 1440] minute-of-day domain. */
    const val REASON_MALFORMED_OUT_OF_RANGE = "malformed_out_of_range"
    /** REVISE: zero-length (start == end) or inverted (start > end) segment. */
    const val REASON_MALFORMED_ZERO_LENGTH_SEGMENT = "malformed_zero_length_segment"

    /** Closed interval of legal minute-of-day values. */
    private const val MINUTES_PER_DAY = 24 * 60 // 1440

    /**
     * Validate a candidate segment against the configured refresh time.
     *
     * Pure function. Inputs are minute-of-day values in [0, 1440].
     *
     * Order of checks (REVISE):
     *   1. out-of-range gate: start ∈ [0, 1440], refresh ∈ [0, 1440],
     *      end ∈ [0, 1440] — otherwise Rejected(MalformedOutOfRange).
     *   2. zero-length / inverted gate: start >= end — Rejected
     *      (MalformedZeroLengthSegment). Note: cross-midnight (end < start)
     *      falls into this branch and is rejected at write-time per REVISE.
     *   3. strict-crossing check: `start < refresh && refresh < end` —
     *      Rejected(RefreshStrictlyInsideSegment).
     *   4. otherwise Legal.
     */
    fun isSegmentLegal(start: Int, end: Int, refresh: Int): BoundaryResult {
        if (start < 0 || start > MINUTES_PER_DAY ||
            end < 0 || end > MINUTES_PER_DAY ||
            refresh < 0 || refresh > MINUTES_PER_DAY
        ) {
            return BoundaryResult.Rejected(REASON_MALFORMED_OUT_OF_RANGE)
        }
        if (start >= end) {
            // 0-length (start == end) or inverted (start > end, including
            // cross-midnight attempt) → Malformed.
            return BoundaryResult.Rejected(REASON_MALFORMED_ZERO_LENGTH_SEGMENT)
        }
        if (start < refresh && refresh < end) {
            return BoundaryResult.Rejected(REASON_REFRESH_STRICTLY_INSIDE_SEGMENT)
        }
        return BoundaryResult.Legal
    }

    /**
     * Validate a candidate refresh time against the currently configured
     * segments.
     *
     * Pure function. [currentSegments] is read-only.
     *
     * Order of checks (REVISE):
     *   1. candidate out-of-range gate: candidate ∈ [0, 1440] — otherwise
     *      Rejected(MalformedOutOfRange).
     *   2. each segment out-of-range gate: any segment with start<0 or
     *      end<0 or start>1440 or end>1440 → Rejected(MalformedOutOfRange).
     *   3. containment test: refresh is rejected iff `start < candidate &&
     *      candidate < end` for ANY non-cross-midnight segment that
     *      strictly contains it. Cross-midnight segments (end < start) are
     *      tested against the candidate's physical position: refresh is
     *      rejected iff it is strictly inside either the `[start, 24:00)`
     *      arm or the `[00:00, end)` arm.
     *   4. endpoint equality and empty segments remain Legal.
     */
    fun isRefreshLegal(
        candidate: Int,
        currentSegments: List<TimeSegment>
    ): BoundaryResult {
        if (candidate < 0 || candidate > MINUTES_PER_DAY) {
            return BoundaryResult.Rejected(REASON_MALFORMED_OUT_OF_RANGE)
        }
        if (currentSegments.isEmpty()) return BoundaryResult.Legal
        for (seg in currentSegments) {
            val start = seg.startHour * 60 + seg.startMinute
            val end = seg.endHour * 60 + seg.endMinute
            // Range gate first.
            if (start < 0 || start > MINUTES_PER_DAY ||
                end < 0 || end > MINUTES_PER_DAY
            ) {
                return BoundaryResult.Rejected(REASON_MALFORMED_OUT_OF_RANGE)
            }
            if (end > start) {
                // Non-cross-midnight: strict open interval containment.
                if (start < candidate && candidate < end) {
                    return BoundaryResult.Rejected(
                        REASON_CANDIDATE_STRICTLY_INSIDE_SEGMENT
                    )
                }
            } else if (end < start) {
                // Cross-midnight: two physical arms.
                // Arm 1: [start, 24:00)  → strict interior is (start, 24:00)
                if (start < candidate && candidate < MINUTES_PER_DAY) {
                    return BoundaryResult.Rejected(
                        REASON_CANDIDATE_STRICTLY_INSIDE_SEGMENT
                    )
                }
                // Arm 2: [00:00, end) → strict interior is (00:00, end)
                if (0 < candidate && candidate < end) {
                    return BoundaryResult.Rejected(
                        REASON_CANDIDATE_STRICTLY_INSIDE_SEGMENT
                    )
                }
            }
            // start == end is a zero-length segment; no strict interior
            // exists, so refresh cannot fall inside it. Legal here.
        }
        return BoundaryResult.Legal
    }
}

object TimeRuleManager {

    private val quotaConfigNormalEventLogger by lazy {
        NormalEventLogger(
            renderer = DefaultNormalEventRenderer,
            sinks = listOf(LogExporter.normalEventSink(), AndroidLogcatNormalEventSink())
        )
    }
    private val quotaConfigEventBridge by lazy {
        QuotaConfigEventBridge(
            clock = SystemEventClock,
            emit = quotaConfigNormalEventLogger::emit,
            debugConfig = DefaultLogDebugConfig
        )
    }
    private val quotaConfigIdSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private fun nextQuotaConfigId(prefix: String): String =
        "${prefix}-${quotaConfigIdSequence.incrementAndGet()}"

    private const val PREFS_NAME = "decikeeper_rules"
    private const val PREFS_PENDING = "decikeeper_rules_pending"
    private const val PREFS_BASELINE = "decikeeper_rules_baseline"
    private const val PREFS_LOG = "decikeeper_rules_log"
    // Legacy promotion prefs are kept only to absorb old pre-update state.
    private const val PREFS_PROMOTION = "decikeeper_promotion"
    private const val KEY_LAST_FAVORABLE_TS = "last_favorable_ts"

    // 这里不再独立维护标记，完全代理给 CreditManager
    private const val KEY_SEGMENTS_JSON = "segments_json"
    private const val KEY_CHANGE_LOG = "change_log_json"
    const val KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS = "temp_restricted_access_max_seconds"
    const val DEFAULT_TEMP_RESTRICTED_ACCESS_MAX_SECONDS = 180
    const val MAX_TEMP_RESTRICTED_ACCESS_MAX_SECONDS = 180

    // 🚀 [Logic] Adaptation Period Check
    // 统一委托给 CreditManager，避免 install_time 和 first_setup_timestamp_v2 双源不一致。
    // CreditManager 版本额外处理了 has_manually_ended_adaptation 标记。
    fun isInAdaptationPeriod(context: Context): Boolean {
        return CreditManager.isInAdaptationPeriod(context)
    }


    // Promote any legacy favorable Active state immediately.
    fun promoteMatureBaseline(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_PROMOTION, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_LAST_FAVORABLE_TS, 0L)
        
        if (ts > 0) {
            android.util.Log.i("TimeRuleManager", "Cleared legacy favorable baseline promotion marker.")
            prefs.edit().remove(KEY_LAST_FAVORABLE_TS).apply()
        }
    }

    private fun updateBaselineAfterImmediateChange(context: Context) {
        context.getSharedPreferences(PREFS_PROMOTION, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_FAVORABLE_TS).apply()
    }

    // --- Helper Methods ---
    @Volatile
    private var cachedSegments: List<TimeSegment>? = null

    fun invalidateSegmentsCache() {
        cachedSegments = null
    }

    fun getTimeSegments(context: Context): List<TimeSegment> {
        if (cachedSegments != null) return cachedSegments!!
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SEGMENTS_JSON, null)
        cachedSegments = if (json != null) parseSegmentsFromJson(json) else emptyList()
        return cachedSegments!!
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    internal fun getDeviceProtectedPrefs(context: Context): SharedPreferences? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } else null
    }

    fun segmentsToJson(segments: List<TimeSegment>): String = TimeSegmentJson.segmentsToJson(segments)

    fun parseSegmentsFromJson(json: String): List<TimeSegment> = TimeSegmentJson.parseSegmentsFromJson(json)


    // ==========================================
    // 🟡 保存
    // ==========================================
    /**
     * @param syncRefreshFromSegments 是否在保存时按"本次传入的时段"重新推导并覆盖刷新时刻。
     *   `analyzeAndSaveTimeSegments` 的混合（部分延迟）路径传 false：它已按「即将生效的新时段」
     *   同步过刷新时刻；而此处传入的是保守的 hybrid 时段（仍保留旧的、更严的结束时间），
     *   再推导一次会把刚同步好的 07:15 又改回 07:30。
     */
    fun saveTimeSegments(context: Context, segments: List<TimeSegment>, syncRefreshFromSegments: Boolean = true) {
        // 时段改动会让当前结余随之变化：前后各取一次结余，有变化记一条「额度重算」流水
        com.decikeeper.app.manager.statistics.QuotaRecalcRecorder.around(context) {
            saveTimeSegmentsInternal(context, segments, syncRefreshFromSegments)
        }
    }

    private fun saveTimeSegmentsInternal(context: Context, segments: List<TimeSegment>, syncRefreshFromSegments: Boolean) {
        // WP-03-T02 REVISE DA-D1 entry guard: refuse to save if any candidate
        // segment strictly contains the configured refresh time (`start <
        // refresh < end`) or is malformed/out-of-range. Cross-midnight segments
        // (`end <= start`) are themselves legal per v3 SPEC §3.5 A footnote and
        // are forwarded to the existing production path. Atomicity contract:
        // on REJECT, this method MUST NOT touch any SharedPreferences (active
        // segments, pending segments, refresh time, sync state, change-log
        // entries, baseline snapshots, or timestamps). Mirrors the guard
        // already enforced at `analyzeAndSaveTimeSegments` so callers hitting
        // the lower-level `saveTimeSegments` directly cannot bypass DA-D1.
        val curRefreshText = getRefreshTime(context)
        val curRefreshMinutes = parseHHMMToMinutes(curRefreshText)
        if (curRefreshMinutes != null) {
            for ((idx, seg) in segments.withIndex()) {
                val startMin = seg.startHour * 60 + seg.startMinute
                val endMin = seg.endHour * 60 + seg.endMinute
                // Skip cross-midnight segments (end <= start); v3 SPEC
                // §3.5 A footnote declares them legal.
                val isCrossMidnightOrZeroLength = endMin <= startMin
                if (isCrossMidnightOrZeroLength) continue
                val verdict = MinuteBoundary.isSegmentLegal(startMin, endMin, curRefreshMinutes)
                if (verdict is BoundaryResult.Rejected) {
                    android.util.Log.w(
                        "TimeRuleManager",
                        "saveTimeSegments rejected: " +
                            "segment[$idx]=[${seg.startHour}:${seg.startMinute}-" +
                            "${seg.endHour}:${seg.endMinute}] " +
                            "name='${seg.name}' reason=${verdict.reason}"
                    )
                    return
                }
            }
        }

        // 🚀 [Evolution] Check for Promotion BEFORE comparing
        promoteMatureBaseline(context)

        val currentActive = getTimeSegments(context)
        val json = segmentsToJson(segments)
        // 🚀 Adaptation Period: Always Immediate & Update Baseline
        if (isInAdaptationPeriod(context)) {
            // ... (Existing Adaptation Logic) ...
            // Save Active
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SEGMENTS_JSON, json).apply()
            getDeviceProtectedPrefs(context)?.edit()?.putString(KEY_SEGMENTS_JSON, json)?.apply()
            
            checkAndSyncRefreshTime(context, segments, syncRefreshFromSegments)
            saveBaselineSnapshot(context)
            invalidateSegmentsCache()
            AutoBackupManager.triggerDebouncedBackup(context)
            
            android.util.Log.i("TimeRuleManager", "Adaptation Save: Immediate + Baseline Updated.")
            return
        }

        // 🚀 Normal Mode: Compare vs Baseline (Auto-Evolved)
        val baselineSegments = getBaselineSegments(context) ?: currentActive // Fallback
        
        // 1. Check for ACTUAL change vs Active
        val activeJson = segmentsToJson(currentActive)
        if (activeJson == json) {
            android.util.Log.i("TimeRuleManager", "Save skipped: No changes vs Active.")
            return
        }

        // 2. Compare New vs Baseline
        // If New matches Baseline -> Restored (Benign)
        // If New is Favorable -> Immediate + Reset Timer
        // If New is Adverse -> Pending

        // We use isAdverseSegments to check if New relaxes Baseline.
        // if isAdverse == false, it means New is Stronger or Equal to Baseline -> Favorable/Restored.
        val isAdverse = SegmentAdverseAnalysis.isAdverseSegments(baselineSegments, segments, context)

        // 🚀 [Fix] Sync refresh time BEFORE calculating effective timestamps,
        // so that both adverse and favorable paths use the up-to-date config_refresh_time.
        // Also propagates to all other pending files (app_window, blacklist, etc.)
        checkAndSyncRefreshTime(context, segments, syncRefreshFromSegments)

        if (isAdverse) {
            // ❌ ADVERSE -> Pending
            val nextTime = ConfigPendingManager.calculateNextEffectiveTime(context, System.currentTimeMillis())
            context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SEGMENTS_JSON, json)
                .putLong("effective_time", nextTime)
                .putBoolean("has_pending_segments", true)
                .apply()

            android.util.Log.i("TimeRuleManager", "Save Deferred: Adverse Change vs Baseline. Effective: $nextTime")

        } else {
            // ✅ FAVORABLE / RESTORED -> Immediate
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SEGMENTS_JSON, json).apply()
            getDeviceProtectedPrefs(context)?.edit()?.putString(KEY_SEGMENTS_JSON, json)?.apply()

            // Clear Pending
            context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
                .edit().remove(KEY_SEGMENTS_JSON).remove("effective_time").putBoolean("has_pending_segments", false).apply()

            invalidateSegmentsCache()

            updateBaselineAfterImmediateChange(context)
            AutoBackupManager.triggerDebouncedBackup(context)

            android.util.Log.i("TimeRuleManager", "Save Immediate: Favorable/Restored vs Baseline. Timer Reset.")
        }
    }

    private fun checkAndSyncRefreshTime(
        context: Context,
        segments: List<TimeSegment>,
        syncRefresh: Boolean = true
    ) {
        if (syncRefresh && isRefreshTimeSynced(context)) {
            val oldRefresh = getRefreshTime(context)
            val newRefreshTime = findRefreshTimeFromSegments(segments)
            if (newRefreshTime != oldRefresh) {
                saveGlobalParamsRefreshTime(context, newRefreshTime)
                // 🚀 [Fix] Propagate new effective timestamp to ALL pending files
                // so that app_window, blacklist, and rules pending all reflect the new refresh time
                propagateRefreshTimeToPendingFiles(context)
            }
        }
    }

    /**
     * 当 config_refresh_time 变更时，刷新所有待生效文件的 effective_timestamp，
     * 确保应用时间窗、黑名单等延迟生效表与勿扰时段显示统一的生效时间。
     */
    private fun propagateRefreshTimeToPendingFiles(context: Context) {
        val newEffectiveTime = getEffectiveTimestamp(context)

        // AppWindow pending
        val awPending = context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE)
        if (awPending.getBoolean("has_pending_changes", false)) {
            awPending.edit().putLong("effective_timestamp", newEffectiveTime).apply()
        }

        // Blacklist apps pending
        val cmPending = context.getSharedPreferences("decikeeper_pending_apps", Context.MODE_PRIVATE)
        if (cmPending.getBoolean("has_pending_apps", false)) {
            cmPending.edit().putLong("effective_timestamp", newEffectiveTime).apply()
        }

        // TimeRule pending (decikeeper_rules_pending) — uses "effective_time" key
        val trPending = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        if (trPending.getBoolean("has_pending_changes", false) || trPending.getBoolean("has_pending_segments", false)) {
            trPending.edit().putLong("effective_timestamp", newEffectiveTime).apply()
            // Also update "effective_time" if segments are pending
            if (trPending.getBoolean("has_pending_segments", false)) {
                trPending.edit().putLong("effective_time", newEffectiveTime).apply()
            }
        }
    }

    /**
     * 从 DND 时段中自动推导每日生效时间。
     * 规则：扫描所有启用时段，找结束时间落在 04:00-12:00 窗口内的时段，
     * 取最早结束时间；若无匹配时段则默认 08:00。
     */
    fun findRefreshTimeFromSegments(segments: List<TimeSegment>): String {
        val windowStart = 4 * 60  // 04:00
        val windowEnd = 12 * 60   // 12:00

        val candidates = segments
            .filter { it.isEnabled }
            .map { it to (it.endHour * 60 + it.endMinute) }
            .filter { (_, endMin) -> endMin in windowStart..windowEnd }
            .sortedBy { it.second }

        return if (candidates.isNotEmpty()) {
            val earliestSeg = candidates.first().first
            String.format("%02d:%02d", earliestSeg.endHour, earliestSeg.endMinute)
        } else {
            "08:00"
        }
    }

    internal fun isRefreshTimeSyncAllowed(hour: Int, minute: Int): Boolean {
        val total = hour * 60 + minute
        return total in (7 * 60)..(23 * 60 + 59)
    }
    
    // 🚀 [NEW] Explicit Baseline Snapshot Save (Called at Adaptation End / 06:00)
    fun saveBaselineSnapshot(context: Context) {
        val currentSegments = getTimeSegments(context)
        val json = segmentsToJson(currentSegments)
        
        // Save Segments
        val prefs = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SEGMENTS_JSON, json).apply()
        
        // Save Global Params Snapshot
        prefs.edit().apply {
            putInt("cool_down_rest_seconds", getCoolDownRestSeconds(context))
            putInt("cool_down_gap_seconds", getCoolDownGapSeconds(context))
            putInt("borrow_percentage", getBorrowPercentage(context))
            putInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, getTemporaryRestrictedAccessMaxSeconds(context))
            putInt("notify_advance_minutes", getNotifyAdvanceMinutes(context))
            putInt("notify_entry_near_minutes", getNotifyEntryNearMinutes(context))
            putString("config_refresh_time", getRefreshTime(context))
            putLong("strict_penalty_duration", getStrictPenaltyDurationSeconds(context) * 1000)
            putInt("weekly_privilege_limit", CreditManager.getWeeklyPrivilegeLimit(context))
            putInt("weekly_privilege_duration_limit", CreditManager.getWeeklyPrivilegeDurationLimit(context))
            putString("privilege_window_start", getPrivilegeWindowStart(context))
            putString("privilege_window_end", getPrivilegeWindowEnd(context))
        }.apply()
        
        android.util.Log.i("TimeRuleManager", "Baseline Snapshot (Segments + Global Params) Saved to PREFS_BASELINE.")
    }
    
    // 🚀 [NEW] Retrieve Baseline Segments
    fun getBaselineSegments(context: Context): List<TimeSegment>? {
        val json = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).getString(KEY_SEGMENTS_JSON, null) ?: return null
        return parseSegmentsFromJson(json)
    }

    /**
     * 将指定的应用包名集合同步加入所有脱钩的独立时段名单（listApps != null）。
     * 仅当应用尚未存在于 listApps 且非硬豁免（Hard Exempt）时追加。
     * 支持同时更新 Active 列表、Baseline 以及 Pending 规则。
     * @return 更新的时段数量
     */
    fun appendAppsToDecoupledSegments(context: Context, packages: Set<String>): Int {
        if (packages.isEmpty()) return 0

        var updatedCount = 0

        // 1. 更新 Active Segments
        val activeSegments = getTimeSegments(context)
        var activeModified = false
        val newActiveSegments = activeSegments.map { seg ->
            if (seg.listApps != null) {
                val toAdd = packages.filter { pkg ->
                    !seg.listApps.contains(pkg) && !com.decikeeper.app.data.SegmentBlacklist.isHardExempt(context, pkg)
                }
                if (toAdd.isNotEmpty()) {
                    activeModified = true
                    updatedCount++
                    seg.copy(listApps = seg.listApps + toAdd, lastModified = System.currentTimeMillis())
                } else seg
            } else seg
        }

        if (activeModified) {
            val json = segmentsToJson(newActiveSegments)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SEGMENTS_JSON, json).apply()
            getDeviceProtectedPrefs(context)?.edit()?.putString(KEY_SEGMENTS_JSON, json)?.apply()
            invalidateSegmentsCache()

            // 同步更新 Baseline（若存在脱钩时段，保持 baseline 对齐）
            val baselineSegments = getBaselineSegments(context)
            if (baselineSegments != null) {
                var baselineModified = false
                val newBaselineSegments = baselineSegments.map { seg ->
                    if (seg.listApps != null) {
                        val toAdd = packages.filter { pkg ->
                            !seg.listApps.contains(pkg) && !com.decikeeper.app.data.SegmentBlacklist.isHardExempt(context, pkg)
                        }
                        if (toAdd.isNotEmpty()) {
                            baselineModified = true
                            seg.copy(listApps = seg.listApps + toAdd, lastModified = System.currentTimeMillis())
                        } else seg
                    } else seg
                }
                if (baselineModified) {
                    val baseJson = segmentsToJson(newBaselineSegments)
                    context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
                        .edit().putString(KEY_SEGMENTS_JSON, baseJson).apply()
                }
            }

            AutoBackupManager.triggerDebouncedBackup(context)
        }

        // 2. 更新 Pending Segments（若存在）
        if (hasPendingRules(context)) {
            val pendingSegments = getPendingTimeSegments(context)
            if (pendingSegments != null) {
                var pendingModified = false
                val newPendingSegments = pendingSegments.map { seg ->
                    if (seg.listApps != null) {
                        val toAdd = packages.filter { pkg ->
                            !seg.listApps.contains(pkg) && !com.decikeeper.app.data.SegmentBlacklist.isHardExempt(context, pkg)
                        }
                        if (toAdd.isNotEmpty()) {
                            pendingModified = true
                            seg.copy(listApps = seg.listApps + toAdd, lastModified = System.currentTimeMillis())
                        } else seg
                    } else seg
                }
                if (pendingModified) {
                    val pendingJson = segmentsToJson(newPendingSegments)
                    context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
                        .edit().putString(KEY_SEGMENTS_JSON, pendingJson).apply()
                }
            }
        }

        return updatedCount
    }

    fun getCoolDownSeconds(context: Context): Int {
        val currentSeg = getCurrentSegment(context)
        return if (currentSeg != null) {
            // 在时段内：使用休息期冷却
            getCoolDownRestSeconds(context)
        } else {
            // 在空档期：使用空档期冷却
            getCoolDownGapSeconds(context)
        }
    }
    fun getCoolDownRestSeconds(context: Context): Int = getPrefs(context).getInt("cool_down_rest_seconds", getPrefs(context).getInt("cool_down_seconds", 120))
    fun getCoolDownGapSeconds(context: Context): Int = getPrefs(context).getInt("cool_down_gap_seconds", 120) // Gap Default 120s
    fun getNotifyAdvanceMinutes(context: Context): Int = getPrefs(context).getInt("notify_advance_minutes", 20) // 🚀 Map to Far
    // 🚀 [NEW] Entry Warning: Far (Alias for advance)
    fun getNotifyEntryFarMinutes(context: Context): Int = getNotifyAdvanceMinutes(context)
    // 🚀 [NEW] Entry Warning: Near (近期预警, 默认 5 分钟)
    fun getNotifyEntryNearMinutes(context: Context): Int = getPrefs(context).getInt("notify_entry_near_minutes", 5)
    fun getBorrowPercentage(context: Context): Int = getPrefs(context).getInt("borrow_percentage", 50) // 🚀 Borrow Default 50%
    fun getTemporaryRestrictedAccessMaxSeconds(context: Context): Int {
        val raw = getPrefs(context).getInt(
            KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS,
            DEFAULT_TEMP_RESTRICTED_ACCESS_MAX_SECONDS
        )
        return raw.coerceIn(1, MAX_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)
    }
    
    // fun getProtectionLevel removed.
    
    // 🚀 [NEW] Strict Penalty Duration (Default 15s)
    fun getStrictPenaltyDurationSeconds(context: Context): Long {
        // 🚀 [Fix] Enforce Floor (Min 10s) to avoid 0s bug, per user request (>10s)
        // 🚀 [Safety] Enforce Cap (Max 5 mins) to prevent accidental bricking/soft-lock (User reported 8h lock)
        val raw = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("strict_penalty_duration", 15000L)
        return raw.coerceAtLeast(10000L).coerceAtMost(300000L) / 1000 // Saved in MS, return in Seconds
    }
    
    // 🚀 [NEW] Baseline Getter for Strict Penalty
    fun getBaselineStrictPenaltyDurationSeconds(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
        if (!prefs.contains("strict_penalty_duration")) return null
        return prefs.getLong("strict_penalty_duration", 15000L).coerceAtLeast(10000L) / 1000
    }
    
    // 🚀 [NEW] Baseline Getters for Other params (if needed explicit, or generic access via PREFS_BASELINE)
    fun getBaselineCoolDownRestSeconds(context: Context): Int? = if (context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).contains("cool_down_rest_seconds")) context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).getInt("cool_down_rest_seconds", 60) else null
    fun getBaselineCoolDownGapSeconds(context: Context): Int? = if (context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).contains("cool_down_gap_seconds")) context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).getInt("cool_down_gap_seconds", 60) else null
    fun getBaselineBorrowPercentage(context: Context): Int? = if (context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).contains("borrow_percentage")) context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).getInt("borrow_percentage", 50) else null
    fun getBaselineTemporaryRestrictedAccessMaxSeconds(context: Context): Int? = if (context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).contains(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)) context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).getInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, DEFAULT_TEMP_RESTRICTED_ACCESS_MAX_SECONDS) else null
    // Note: Parameter names in PREFS_BASELINE rely on `saveBaselineSnapshot` keys.
    fun getBaselineWeeklyPrivilegeLimit(context: Context): Int? = if (context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).contains("weekly_privilege_limit")) context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).getInt("weekly_privilege_limit", 1) else null
    fun getBaselineWeeklyPrivilegeDurationLimit(context: Context): Int? = if (context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).contains("weekly_privilege_duration_limit")) context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).getInt("weekly_privilege_duration_limit", 480) else null
    fun getBaselinePrivilegeWindowStart(context: Context): String? = if (context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).contains(KEY_PRIVILEGE_WINDOW_START)) context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).getString(KEY_PRIVILEGE_WINDOW_START, null) else null
    fun getBaselinePrivilegeWindowEnd(context: Context): String? = if (context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).contains(KEY_PRIVILEGE_WINDOW_END)) context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).getString(KEY_PRIVILEGE_WINDOW_END, null) else null
    
    fun getBaselineAntiUninstallState(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
        return prefs.getBoolean("is_anti_uninstall_enabled", prefs.getBoolean("is_anti_uninstall_active", false))
    }

    fun getBaselineA11yServiceState(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
            .getBoolean("a11y_service_enabled", false)
    }

    fun repairBaselineA11yServiceState(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
        if (prefs.contains("snapshot_time")) {
            prefs.edit().putBoolean("a11y_service_enabled", enabled).apply()
        }
    }

    fun getBaselineFloatingGuardState(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
            .getBoolean("floating_guard_enabled", false)
    }

    fun getBaselinePrivacyProtectionState(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
            .getBoolean("is_privacy_protection_enabled", false)
    }

    fun getBaselineFloatingWindowProtectionState(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
            .getBoolean("is_floating_window_protection_enabled", false)
    }

    fun getBaselineInstallGuardState(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
            .getBoolean(InstallGuardManager.KEY_IS_ENABLED, false)
    }

    fun getBaselineDeveloperOptionsGuardState(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
            .getBoolean(DeveloperOptionsGuardManager.KEY_IS_ENABLED, false)
    }

    /** 仅供一次性修复使用（见 DailyBaselineManager），不要作为常规写入口。 */
    internal fun repairBaselineDeveloperOptionsGuardState(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
            .edit().putBoolean(DeveloperOptionsGuardManager.KEY_IS_ENABLED, enabled).apply()
    }


    fun getMaxExitsPerSegment(context: Context): Int = 100
    fun getMinutesLimitForSegment(context: Context, segment: TimeSegment): Int = segment.limitMins

    /**
     * [STRICT MODE] 判定是否为“不利行为” (Adverse Behavior)
     * 不利行为定义：
     * 1. 减少勿扰时长 (newLimit > oldLimit, 因为 limit 是休息时间，rest time increased = DND decreased) -> Wait, limit is REST time?
     *    Actually: limitMins is "Rest Limit". Increasing Rest Limit = More Rest = Less DND = Adverse.
     *    Wait, check definition: "limitMins" in TimeSegment.
     *    If limitMins is "Max Rest Minutes", then Increasing it is Adverse.
     * 2. 增加退出次数 (newExits > oldExits) -> Adverse.
     * 3. 减少时段范围 (Duration decreased) -> Adverse. (Handled in segment comparison)
     */
    fun isAdverseModification(oldLim: Int, newLim: Int): Boolean {
        // 增加休息时长 -> 视为不利 (Relaxing restrictions)
        return newLim > oldLim
    }












    fun formatDaysVerbose(days: List<Int>?): String {
        return formatDaysCompact(days)
    }

    internal fun formatDays(days: List<Int>?): String {
        return formatDaysCompact(days)
    }

    private fun formatDaysCompact(days: List<Int>?): String {
        val orderedWeek = listOf(2, 3, 4, 5, 6, 7, 1) // 周一 -> 周日
        val dayToken = mapOf(1 to "日", 2 to "一", 3 to "二", 4 to "三", 5 to "四", 6 to "五", 7 to "六")

        val set = normalizeDaysSet(days)
        val labels = orderedWeek
            .filter { set.contains(it) }
            .mapNotNull { dayToken[it] }

        if (labels.isEmpty() || labels.size == 7) return "每天"
        if (labels.size == 1) return "周${labels.first()}"
        return "周${labels.first()}、" + labels.drop(1).joinToString("、")
    }

    internal fun normalizeDaysSet(days: List<Int>?): Set<Int> {
        return TimeSegmentJson.normalizeDaysList(days)?.toSet() ?: setOf(1, 2, 3, 4, 5, 6, 7)
    }


    private fun toDaysListOrNull(days: Set<Int>): List<Int>? {
        val cleaned = days.filter { it in 1..7 }.toSortedSet()
        return if (cleaned.isEmpty() || cleaned.size == 7) null else cleaned.toList()
    }

    private fun collectSegmentConflictDays(newSeg: TimeSegment, existingSegments: List<TimeSegment>): Set<Int> {
        val targetDays = normalizeDaysSet(newSeg.daysOfWeek)
        if (targetDays.isEmpty()) return emptySet()

        return targetDays.filter { day ->
            existingSegments.any { old ->
                normalizeDaysSet(old.daysOfWeek).contains(day) &&
                    SegmentAdverseAnalysis.calculateOverlapMinutes(newSeg, old) > 0
            }
        }.toSet()
    }

    /**
     * 🚀 [Per-Day Mixed Activation]
     * Split one DND segment into immediate days and delayed days based on overlap.
     * - immediateDays: days with no overlap against existing segments
     * - delayedDays: days with overlap against existing segments
     */
    fun splitSegmentDaysForMixedActivation(newSeg: TimeSegment, existingSegments: List<TimeSegment>): Pair<Set<Int>, Set<Int>> {
        val allDays = normalizeDaysSet(newSeg.daysOfWeek)
        val delayedDays = collectSegmentConflictDays(newSeg, existingSegments)
        val immediateDays = allDays - delayedDays
        return immediateDays to delayedDays
    }

    internal fun formatEnabled(enabled: Boolean) = if (enabled) "开启" else "关闭"







    /**
     * 🚀 [New] 检查时段重叠
     * 返回 true 如果存在重叠，false 如果正常。
     * 支持跨天时段检测 (例如 23:00-02:00 会被视为 [23:00-24:00] 和 [00:00-02:00])
     */
    fun checkSegmentOverlap(segments: List<TimeSegment>): Boolean {
        fun toRanges(seg: TimeSegment): List<Pair<Int, Int>> {
            val start = seg.startHour * 60 + seg.startMinute
            val end = seg.endHour * 60 + seg.endMinute
            return if (start < end) {
                listOf(start to end)
            } else {
                listOf(start to 1440, 0 to end)
            }
        }

        for (i in segments.indices) {
            val segA = segments[i]
            if (!segA.isEnabled) continue
            val rangesA = toRanges(segA)

            for (j in i + 1 until segments.size) {
                val segB = segments[j]
                if (!segB.isEnabled) continue
                if (!SegmentAdverseAnalysis.isDaysOverlapping(segA.daysOfWeek, segB.daysOfWeek)) continue

                val rangesB = toRanges(segB)
                for (r1 in rangesA) {
                    for (r2 in rangesB) {
                        val intersectStart = kotlin.math.max(r1.first, r2.first)
                        val intersectEnd = kotlin.math.min(r1.second, r2.second)
                        if (intersectStart < intersectEnd) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    fun calculateDuration(seg: TimeSegment): Int {
        val start = seg.startHour * 60 + seg.startMinute
        val end = seg.endHour * 60 + seg.endMinute
        return if (end >= start) end - start else (end + 1440 - start)
    }

    /**
     * 计算生效时间戳：次日固定时间 (06:00)
     * Standardized to avoid confusion with "Business Day" vs "Calendar Day".
     */
    fun getEffectiveTimestamp(context: Context): Long {
        return ConfigPendingManager.calculateNextEffectiveTime(context, System.currentTimeMillis())
    }

    fun saveGlobalParams(context: Context, coolDown: Int, notifyFar: Int, borrowPercent: Int, strictPenaltySecs: Long = 15, notifyNear: Int = 5, tempRestrictedAccessMaxSeconds: Int = DEFAULT_TEMP_RESTRICTED_ACCESS_MAX_SECONDS) {
    // 1. Save to Standard (TimeRuleManager prefs)
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
        putInt("cool_down_seconds", coolDown)
        putInt("cool_down_rest_seconds", coolDown) // Sync with old if needed
        putInt("notify_advance_minutes", notifyFar) // Far
        putInt("notify_entry_near_minutes", notifyNear) // Near
        putInt("borrow_percentage", borrowPercent)
        putInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, tempRestrictedAccessMaxSeconds.coerceIn(1, MAX_TEMP_RESTRICTED_ACCESS_MAX_SECONDS))
        remove("protection_level")
        remove("is_super_strict_mode")
    }.apply()
    
    // 2. Save to CreditManager prefs (for strict_penalty_duration)
    context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
        putLong("strict_penalty_duration", strictPenaltySecs * 1000)
    }.apply()
    
    // 3. Sync to Device Protected
    getDeviceProtectedPrefs(context)?.edit()?.apply {
        putInt("cool_down_seconds", coolDown)
        putInt("cool_down_rest_seconds", coolDown)
        putInt("notify_advance_minutes", notifyFar)
        putInt("notify_entry_near_minutes", notifyNear)
        putInt("borrow_percentage", borrowPercent)
        putInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, tempRestrictedAccessMaxSeconds.coerceIn(1, MAX_TEMP_RESTRICTED_ACCESS_MAX_SECONDS))
        putLong("strict_penalty_duration", strictPenaltySecs * 1000)
    }?.apply()

    quotaConfigEventBridge.recordConfigChange(
        configId = nextQuotaConfigId("GLOBAL-PARAM"),
        scope = "GLOBAL_PARAM",
        action = "UPDATE",
        oldValue = "previous-snapshot",
        newValue = "coolDown=${coolDown}s;borrowPercent=${borrowPercent}%",
        effectiveAt = 0L,
        apply = "IMMEDIATE"
    )
}

    fun saveDifferentiatedCoolDowns(context: Context, restCd: Int, gapCd: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt("cool_down_rest_seconds", restCd)
            putInt("cool_down_gap_seconds", gapCd)
        }.apply()
        getDeviceProtectedPrefs(context)?.edit()?.apply {
            putInt("cool_down_rest_seconds", restCd)
            putInt("cool_down_gap_seconds", gapCd)
        }?.apply()
    }

    // 🚀 [NEW] Refresh Time Sync Logic
    private const val KEY_REFRESH_TIME_SYNCED = "is_refresh_time_synced"
    
    fun isRefreshTimeSynced(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REFRESH_TIME_SYNCED, false)
    }
    
    fun setRefreshTimeSynced(context: Context, isSynced: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REFRESH_TIME_SYNCED, isSynced).apply()
        getDeviceProtectedPrefs(context)?.edit()?.putBoolean(KEY_REFRESH_TIME_SYNCED, isSynced)?.apply()
    }

    // 🚀 [NEW] Privilege Usage Time Window Logic
    private const val KEY_PRIVILEGE_WINDOW_START = "privilege_window_start"
    private const val KEY_PRIVILEGE_WINDOW_END = "privilege_window_end"

    /**
     * 当前生效的特权可用范围（开始, 结束）。
     * 范围必须落在同一个业务日内：开始不早于业务日开始（生效时间），结束晚于开始、最晚 24:00。
     * 早于业务日开始的开始值被收窄到业务日开始；仍不合法（如旧版跨午夜范围）时回落到默认范围。
     */
    private fun resolvePrivilegeWindow(context: Context): Pair<String, String> {
        val prefs = getPrefs(context)
        return PrivilegeWindow.resolve(
            prefs.getString(KEY_PRIVILEGE_WINDOW_START, null),
            prefs.getString(KEY_PRIVILEGE_WINDOW_END, null),
            getRefreshTime(context)
        )
    }

    /** 获取特权可用窗口开始时间 (HH:MM 格式)，默认 max("07:00", 业务日开始) */
    fun getPrivilegeWindowStart(context: Context): String = resolvePrivilegeWindow(context).first

    /** 获取特权可用窗口结束时间 (HH:MM 格式)，默认 "23:00" */
    fun getPrivilegeWindowEnd(context: Context): String = resolvePrivilegeWindow(context).second

    /** 特权可用范围的结束时刻（毫秒）：取 [referenceMillis] 所在实际日的范围结束，24:00 即次日 00:00。 */
    fun getPrivilegeWindowEndMillis(context: Context, referenceMillis: Long): Long {
        val endMins = PrivilegeWindow.parseMinutes(getPrivilegeWindowEnd(context))
            ?: PrivilegeWindow.parseMinutes(PrivilegeWindow.DEFAULT_END)!!
        return java.util.Calendar.getInstance().run {
            timeInMillis = referenceMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.MINUTE, endMins)
            timeInMillis
        }
    }

    fun setPrivilegeWindow(context: Context, start: String, end: String) {
        getPrefs(context).edit()
            .putString(KEY_PRIVILEGE_WINDOW_START, start)
            .putString(KEY_PRIVILEGE_WINDOW_END, end)
            .apply()
        getDeviceProtectedPrefs(context)?.edit()
            ?.putString(KEY_PRIVILEGE_WINDOW_START, start)
            ?.putString(KEY_PRIVILEGE_WINDOW_END, end)
            ?.apply()
    }

    /**
     * 获取 Pending 中的特权窗口值 (若有)
     */
    fun getPendingPrivilegeWindowStart(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PRIVILEGE_WINDOW_START, null)
    }

    fun getPendingPrivilegeWindowEnd(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PRIVILEGE_WINDOW_END, null)
    }

    /**
     * 判断当前时刻是否在特权可用窗口内（同一实际日内，包含两端）
     */
    fun isInPrivilegeWindow(context: Context, nowMinutes: Int = -1): Boolean {
        val startMins = PrivilegeWindow.parseMinutes(getPrivilegeWindowStart(context)) ?: return false
        val endMins = PrivilegeWindow.parseMinutes(getPrivilegeWindowEnd(context)) ?: return false

        val currentMins = if (nowMinutes >= 0) nowMinutes else {
            val cal = java.util.Calendar.getInstance()
            cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        }

        return currentMins in startMins..endMins
    }

    /**
     * 🚀 [New] Global Params Analysis & Save (Partial Apply)
     */
    fun analyzeAndSaveGlobalParams(
        context: Context,
        newRest: Int, newGap: Int, newLockDuration: Long,
        newBorrow: Int,
        newNotifyFar: Int, newNotifyNear: Int,
        newWeeklyCount: Int, newWeeklyDuration: Int, newRefreshTime: String,
        newTempRestrictedAccessMaxSeconds: Int = getTemporaryRestrictedAccessMaxSeconds(context),
        newPrivilegeWindowStart: String = getPrivilegeWindowStart(context),
        newPrivilegeWindowEnd: String = getPrivilegeWindowEnd(context),
        newEditWindowPolicy: String = TimeRuleEditWindowPolicyManager.getPolicy(context).key,
        newEffectiveActivationPolicy: EffectiveActivationPolicyManager.Policy? = null
    ): AnalysisResult {
        val curEffectivePolicy = EffectiveActivationPolicyManager.getPolicy(context)
        val effectivePolicyToApply = newEffectiveActivationPolicy ?: curEffectivePolicy
        val isEffectivePolicyChanged = (newEffectiveActivationPolicy != null && newEffectiveActivationPolicy != curEffectivePolicy)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val creditPrefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

        val pendingPrefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)

        // 🚀 [Adaptation Period] During Adaptation, ALL changes apply immediately. Skip partial-apply logic.
        if (isInAdaptationPeriod(context)) {
            // Directly write ALL new values to Active prefs
            prefs.edit().apply {
                putInt("cool_down_rest_seconds", newRest)
                putInt("cool_down_gap_seconds", newGap)
                putInt("borrow_percentage", newBorrow)
                putInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, newTempRestrictedAccessMaxSeconds.coerceIn(1, MAX_TEMP_RESTRICTED_ACCESS_MAX_SECONDS))
                putInt("notify_advance_minutes", newNotifyFar)
                putInt("notify_entry_near_minutes", newNotifyNear)
                putString(KEY_PRIVILEGE_WINDOW_START, newPrivilegeWindowStart)
                putString(KEY_PRIVILEGE_WINDOW_END, newPrivilegeWindowEnd)
            }.apply()
            
            TimeRuleEditWindowPolicyManager.setPolicy(
                context,
                TimeRuleEditWindowPolicyManager.Policy.fromKey(newEditWindowPolicy)
            )
            if (isEffectivePolicyChanged) {
                EffectiveActivationPolicyManager.setPolicy(context, effectivePolicyToApply)
            }
            
            creditPrefs.edit().apply {
                putLong("strict_penalty_duration", newLockDuration * 1000)
            }.apply()
            // WP-03-T03: route refresh time through the public saveRefreshTime
            // guard so adaptation-period writes do not bypass the DA-D2 policy.
            // saveRefreshTime is atomic; on REJECT the active value is left
            // unchanged, which keeps adaptation's "apply immediately" semantics
            // where they are still well-defined (other params still apply).
            saveRefreshTime(context, newRefreshTime)
            
            // Weekly Privilege: Direct write to active, clear any pending
            CreditManager.setWeeklyPrivilegeLimit(context, newWeeklyCount, newWeeklyDuration)
            CreditManager.clearPendingWeeklyPrivilegeLimit(context)
            
            // Clear any pending config entirely since we applied everything? NO.
            // Only clear Global Params? Or Clean all?
            // Adaptation applies ALL to Active. So Pending is irrelevant for Global.
            // Segments? If segments pending exist, applied? Adaptation applies Global. Segments handled separately.
            // This function handles Global.
            // Safe: Check pending segments.
            val pEdit = pendingPrefs.edit()
            pEdit.remove("cool_down_rest_seconds")
            pEdit.remove("cool_down_gap_seconds")
            pEdit.remove("borrow_percentage")
            pEdit.remove(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)
            pEdit.remove("notify_advance_minutes")
            pEdit.remove("notify_entry_near_minutes")
            pEdit.remove("strict_penalty_duration_seconds")
            pEdit.remove("config_refresh_time")
            pEdit.remove(KEY_PRIVILEGE_WINDOW_START)
            pEdit.remove(KEY_PRIVILEGE_WINDOW_END)
            pEdit.remove(TimeRuleEditWindowPolicyManager.KEY_POLICY)
            
            if (!pendingPrefs.contains(KEY_SEGMENTS_JSON)) {
                 pEdit.clear() // Safe clear if no segments
            } else {
                 pEdit.apply() // Apply removals
            }
            
            // 🚀 [Fix] Clear pending logs in adaptation mode
            ChangeLogManager.clearPendingLogsForCategory(context, "global_params")
            
            return AnalysisResult.AllImmediate()
        }

        // 🚀 [Baseline] Snapshot at the start of the day (for correction logic)
        val basPrefs = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
        val basRest = basPrefs.getInt("cool_down_rest_seconds", 60)
        val basGap = basPrefs.getInt("cool_down_gap_seconds", 60)
        val basBorrow = basPrefs.getInt("borrow_percentage", 50)
        val basTempRestrictedAccess = basPrefs.getInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, DEFAULT_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)
        // 🚀 [Fix] Use getter to enforce 15s Floor!
        val basLock = getBaselineStrictPenaltyDurationSeconds(context) ?: 15L
        val basWeekly = basPrefs.getInt("weekly_privilege_limit", 1)
        val basWeeklyDuration = basPrefs.getInt("weekly_privilege_duration_limit", 480)
        val basRefresh = basPrefs.getString("config_refresh_time", "08:00") ?: "08:00"
        val basNotifyFar = basPrefs.getInt("notify_advance_minutes", 20)
        val basNotifyNear = basPrefs.getInt("notify_entry_near_minutes", 5)
        val basPrivWindowStart = basPrefs.getString(KEY_PRIVILEGE_WINDOW_START, null) ?: getPrivilegeWindowStart(context)
        val basPrivWindowEnd = basPrefs.getString(KEY_PRIVILEGE_WINDOW_END, null) ?: getPrivilegeWindowEnd(context)
        val basEditWindowPolicy = basPrefs.getString(
            TimeRuleEditWindowPolicyManager.KEY_POLICY,
            TimeRuleEditWindowPolicyManager.Policy.ALL_DAY.key
        ) ?: TimeRuleEditWindowPolicyManager.Policy.ALL_DAY.key

        // 1. Current Values (Baseline/Active for Strictness Check)
        val curRest = getCoolDownRestSeconds(context)
        val curGap = getCoolDownGapSeconds(context)
        val curLock = getStrictPenaltyDurationSeconds(context) // Seconds
        val curBorrow = getBorrowPercentage(context)
        val curTempRestrictedAccess = getTemporaryRestrictedAccessMaxSeconds(context)
        val curWeekly = CreditManager.getWeeklyPrivilegeLimit(context)
        val curWeeklyDuration = CreditManager.getWeeklyPrivilegeDurationLimit(context)
        val curRefresh = getRefreshTime(context)
        val curNotifyFar = getNotifyAdvanceMinutes(context)
        val curNotifyNear = getNotifyEntryNearMinutes(context)
        val curPrivWindowStart = getPrivilegeWindowStart(context)
        val curPrivWindowEnd = getPrivilegeWindowEnd(context)
        val curEditWindowPolicy = TimeRuleEditWindowPolicyManager.getPolicy(context).key
        
        // 🚀 [Diff Base] Always compare against BASELINE (Snapshot) values for specific Parameter changes
        // User Request: Comparison should be "Baseline -> New" to define Adverse/Benign relative to day start.
        val diffBaseRest = basRest
        val diffBaseGap = basGap
        val diffBaseBorrow = basBorrow
        val diffBaseTempRestrictedAccess = basTempRestrictedAccess
        val diffBaseLock = basLock
        val diffBaseWeekly = basWeekly
        val diffBaseWeeklyDuration = basWeeklyDuration
        val diffBaseRefresh = basRefresh
        val diffBaseEditWindowPolicy = basEditWindowPolicy
        
        // 2. Identify Changes
        val immList = mutableListOf<String>()
        val resList = mutableListOf<String>() // 🚀 [NEW] Restored to Baseline
        val delList = mutableListOf<String>()
        val nextWeekList = mutableListOf<String>()
        
        var anyAdverseRemaining = false
        var hasNewAdverse = false

        // Fetch Pending Values using helper (defined locally or utilize existing context)
        val prefsPending = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val hasPending = prefsPending.getBoolean("has_pending_changes", false)
        
        fun getPending(key: String, def: Int): Int {
            return if (hasPending && prefsPending.contains(key)) prefsPending.getInt(key, def) else def
        }
        fun getPendingLong(key: String, def: Long): Long {
             return if (hasPending && prefsPending.contains(key)) prefsPending.getLong(key, def) else def
        }
        fun getPendingStr(key: String, def: String): String {
             return if (hasPending && prefsPending.contains(key)) prefsPending.getString(key, def) ?: def else def
        }

        val pendRest = getPending("cool_down_rest_seconds", curRest)
        val pendGap = getPending("cool_down_gap_seconds", curGap)
        val pendBorrow = getPending("borrow_percentage", curBorrow)
        val pendTempRestrictedAccess = getPending(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, curTempRestrictedAccess)
        val pendLock = getPendingLong("strict_penalty_duration_seconds", curLock) // stored as seconds in pending
        // Pending Weekly Limit is special, fetched from CreditManager pending
        val pendWeekly = if (CreditManager.hasPendingWeeklyPrivilegeLimit(context)) CreditManager.getPendingWeeklyPrivilegeLimit(context) else curWeekly
        val pendWeeklyDuration = if (CreditManager.hasPendingWeeklyPrivilegeLimit(context)) CreditManager.getPendingWeeklyPrivilegeDurationLimit(context) else curWeeklyDuration
        val pendRefresh = getPendingStr("config_refresh_time", curRefresh)
        val pendNotifyFar = getPending("notify_advance_minutes", curNotifyFar)
        val pendNotifyNear = getPending("notify_entry_near_minutes", curNotifyNear)
        val pendPrivWindowStart = getPendingStr(KEY_PRIVILEGE_WINDOW_START, curPrivWindowStart)
        val pendPrivWindowEnd = getPendingStr(KEY_PRIVILEGE_WINDOW_END, curPrivWindowEnd)
        val pendEditWindowPolicy = getPendingStr(TimeRuleEditWindowPolicyManager.KEY_POLICY, curEditWindowPolicy)
        
        // ...

        val isAdaptation = isInAdaptationPeriod(context)
        val forceAllNextDay = !isAdaptation && (effectivePolicyToApply == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY)

        // Helper for Number comparison
        fun checkParam(name: String, current: Int, baseline: Int, new: Int, diffBase: Int, isHigherStricter: Boolean, tag: String, pendingVal: Int, isWeek: Boolean = false) {
            val isRestored = (new == baseline)
            val isBenign = if (isHigherStricter) new > baseline else new < baseline
            
            // 🚀 [Fix] Skip if New == Pending (User didn't change the input value)
            if (new == pendingVal) {
                 // Ensure we trigger Adverse retention if needed
                 if (!isRestored && !isBenign) {
                     anyAdverseRemaining = true
                 } else if (forceAllNextDay && new != current) {
                     anyAdverseRemaining = true
                 }
                 return
            }

            if (forceAllNextDay && !isWeek) {
                if (new != current) {
                    anyAdverseRemaining = true
                    hasNewAdverse = true
                    if (diffBase != new) {
                        delList.add("$name: $diffBase -> $new (次日生效)")
                    }
                } else if (pendingVal != current) {
                    resList.add("$name: $diffBase -> $new [恢复]")
                    ChangeLogManager.removePendingEntriesByTag(context, "global_params", tag)
                }
                return
            }

            if (isRestored) {
                // 🚀 [Fix] Detect restoration event: Value matches baseline BUT is different from 'current' (active/old state)
                // e.g. Baseline=3, Current=4. User sets 3. -> Restored.
                // e.g. Baseline=3, Current=3. User sets 3. -> No Change.
                if (new != current) {
                    resList.add("$name: $current -> $new [恢复]")
                } else if (diffBase != new) {
                    // Fallback (Logic should cover new!=current, but keeping safe check against diffBase if they differ)
                    resList.add("$name: $diffBase -> $new [恢复]")
                }
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", tag)
            } else if (isBenign) {
                // 🚀 [Fix] Benign (Stricter than Baseline) -> Immediate
                if (diffBase != new) immList.add("$name: $diffBase -> $new (有利)")
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", tag)
                
                // Clear any legacy delayed-baseline marker for this favorable change.
                ProbationManager.addProbationaryChange(context, tag, new, baseline)
            } else {
                // Adverse
                anyAdverseRemaining = true
                if (diffBase != new) {
                    if (isWeek) nextWeekList.add("$name: $diffBase -> $new (下周生效)")
                    else delList.add("$name: $diffBase -> $new (不利)")
                    hasNewAdverse = true
                }
            }
        }
        
        // Check Params
        checkParam("休息冷却", curRest, basRest, newRest, diffBaseRest, true, "cool_down_rest", pendRest)
        checkParam("空档冷却", curGap, basGap, newGap, diffBaseGap, true, "cool_down_gap", pendGap)
        
        // Lock Duration (Long)
        val isLockRestored = (newLockDuration == basLock)
        val isLockBenign = (newLockDuration > basLock) // Higher is stricter
        
        if (newLockDuration == pendLock) {
             if (!isLockRestored && !isLockBenign) anyAdverseRemaining = true
             else if (forceAllNextDay && newLockDuration != curLock) anyAdverseRemaining = true
        } else if (forceAllNextDay) {
            if (newLockDuration != curLock) {
                anyAdverseRemaining = true
                hasNewAdverse = true
                if (diffBaseLock != newLockDuration) {
                    delList.add("黑名单惩罚时长: ${diffBaseLock}s -> ${newLockDuration}s (次日生效)")
                }
            } else if (pendLock != curLock) {
                resList.add("黑名单惩罚时长: ${diffBaseLock}s -> ${newLockDuration}s [恢复]")
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "strict_penalty")
            }
        } else {
            if (isLockRestored) {
                if (diffBaseLock != newLockDuration) resList.add("黑名单惩罚时长: ${diffBaseLock}s -> ${newLockDuration}s [恢复]")
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "strict_penalty")
            } else if (isLockBenign) {
                if (diffBaseLock != newLockDuration) immList.add("黑名单惩罚时长: ${diffBaseLock}s -> ${newLockDuration}s (有利)")
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "strict_penalty")
                
                // Clear any legacy delayed-baseline marker for this favorable change.
                ProbationManager.addProbationaryChange(context, "strict_penalty_duration_seconds", newLockDuration, basLock)
            } else {
                // Adverse
                anyAdverseRemaining = true
                if (diffBaseLock != newLockDuration) {
                    delList.add("黑名单惩罚时长: ${diffBaseLock}s -> ${newLockDuration}s (不利)")
                    hasNewAdverse = true
                }
            }
        }
        
        checkParam("借用比例", curBorrow, basBorrow, newBorrow, diffBaseBorrow, false, "borrow_percentage", pendBorrow)
        checkParam(
            "临时使用受限应用时长上限",
            curTempRestrictedAccess,
            basTempRestrictedAccess,
            newTempRestrictedAccessMaxSeconds.coerceIn(1, MAX_TEMP_RESTRICTED_ACCESS_MAX_SECONDS),
            diffBaseTempRestrictedAccess,
            false,
            "temp_restricted_access_max_seconds",
            pendTempRestrictedAccess
        )

        // 时间规则可修改时段
        val isEditPolicyRestored = (newEditWindowPolicy == basEditWindowPolicy)
        val isEditPolicyBenign = (newEditWindowPolicy == TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT.key &&
                basEditWindowPolicy == TimeRuleEditWindowPolicyManager.Policy.ALL_DAY.key)

        if (newEditWindowPolicy == pendEditWindowPolicy) {
            if (!isEditPolicyRestored && !isEditPolicyBenign) anyAdverseRemaining = true
        } else {
            if (isEditPolicyRestored) {
                if (curEditWindowPolicy != newEditWindowPolicy || diffBaseEditWindowPolicy != newEditWindowPolicy) {
                    resList.add("时间规则可修改时段: ${TimeRuleEditWindowPolicyManager.getPolicyTitle(TimeRuleEditWindowPolicyManager.Policy.fromKey(curEditWindowPolicy))} -> ${TimeRuleEditWindowPolicyManager.getPolicyTitle(TimeRuleEditWindowPolicyManager.Policy.fromKey(newEditWindowPolicy))} [恢复]")
                }
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "edit_window_policy")
            } else if (isEditPolicyBenign) {
                if (diffBaseEditWindowPolicy != newEditWindowPolicy) {
                    immList.add("时间规则可修改时段: 全天可修改 -> 仅生效时间至 24:00 可修改 (有利)")
                }
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "edit_window_policy")
            } else {
                anyAdverseRemaining = true
                if (diffBaseEditWindowPolicy != newEditWindowPolicy) {
                    delList.add("时间规则可修改时段: 仅生效时间至 24:00 可修改 -> 全天可修改 (不利)")
                    hasNewAdverse = true
                }
            }
        }

        if (isEffectivePolicyChanged) {
            val oldTitle = EffectiveActivationPolicyManager.getPolicyTitle(curEffectivePolicy)
            val newTitle = EffectiveActivationPolicyManager.getPolicyTitle(effectivePolicyToApply)
            immList.add("规则生效策略: $oldTitle -> $newTitle (有利)")
        }
        
        // Warnings & Refresh (Always Benign/Immediate - BUT check pending to see if we should skip logging)
        if (newNotifyFar != curNotifyFar && newNotifyFar != pendNotifyFar) {
            if (newNotifyFar == basNotifyFar) resList.add("远期预警: $curNotifyFar -> $newNotifyFar 分 [恢复]")
            else {
                immList.add("远期预警: $curNotifyFar -> $newNotifyFar 分")
                // Clear any legacy delayed-baseline marker for this favorable change.
                ProbationManager.addProbationaryChange(context, "notify_advance_minutes", newNotifyFar, basNotifyFar)
            }
        }
        if (newNotifyNear != curNotifyNear && newNotifyNear != pendNotifyNear) {
            if (newNotifyNear == basNotifyNear) resList.add("近期预警: $curNotifyNear -> $newNotifyNear 分 [恢复]")
            else {
                immList.add("近期预警: $curNotifyNear -> $newNotifyNear 分")
                // Clear any legacy delayed-baseline marker for this favorable change.
                ProbationManager.addProbationaryChange(context, "notify_entry_near_minutes", newNotifyNear, basNotifyNear)
            }
        }
        
        // 🚀 [Fix] Refresh Time: Always Immediate if Valid
        // As per user rule: "As long as it is within 6:00-12:00, it is beneficial."
        // We assume input is validated by Activity (which it is, 06:00-23:59).
        // So any change here is treated as Benign/Immediate.
        val isRefreshRestored = (newRefreshTime == basRefresh)
        val isRefreshBenign = true // Always beneficial/neutral enough to be immediate
        
        if (newRefreshTime == pendRefresh) {
             // If matches pending, we might be confirming it.
        } else {
             if (newRefreshTime != curRefresh) {
                 val isSynced = isRefreshTimeSynced(context)
                 val isRestoredLocal = (newRefreshTime == basRefresh) // Renamed to avoid outer conflict if any
                 val msg = if (isRestoredLocal) "生效时间: $curRefresh -> $newRefreshTime [恢复]" else "生效时间: $curRefresh -> $newRefreshTime (有利)"
                 
                 // 🚀 [Log] Always log history
                 ChangeLogManager.addEntry(context, "global_params", msg, false, "refresh_time")
                 
	                 // Clear any legacy delayed-baseline marker for this favorable change.
                 ProbationManager.addProbationaryChange(context, "config_refresh_time", newRefreshTime, basRefresh)

                 // 🚀 [UI Rule] Suppress Dialog if Synced (Silent Update)
                 if (!isSynced) {
                     if (isRestoredLocal) resList.add(msg)
                     else immList.add(msg)
                 }
                 
                 ChangeLogManager.removePendingEntriesByTag(context, "global_params", "refresh_time")
             }
        }
 
        // Weekly Privilege
        checkParam("每周特权次数", curWeekly, basWeekly, newWeeklyCount, diffBaseWeekly, false, "weekly_privilege_count", pendWeekly, true) // Lower Stricter
        checkParam("每周特权总时长", curWeeklyDuration, basWeeklyDuration, newWeeklyDuration, diffBaseWeeklyDuration, false, "weekly_privilege_duration", pendWeeklyDuration, true) // Lower Stricter

        // 🚀 [NEW] Privilege Usage Time Window — ALL changes are ALWAYS DELAYED (次日生效)
        val privWindowChanged = (newPrivilegeWindowStart != curPrivWindowStart || newPrivilegeWindowEnd != curPrivWindowEnd)
        val privWindowMatchesPending = (newPrivilegeWindowStart == pendPrivWindowStart && newPrivilegeWindowEnd == pendPrivWindowEnd)
        if (privWindowChanged && !privWindowMatchesPending) {
            anyAdverseRemaining = true
            hasNewAdverse = true
            delList.add("特权可用范围: $curPrivWindowStart~$curPrivWindowEnd -> $newPrivilegeWindowStart~$newPrivilegeWindowEnd (次日生效)")
        } else if (privWindowChanged && privWindowMatchesPending) {
            // Value same as pending, still adverse but not new
            anyAdverseRemaining = true
        }

        // 🚀 [Fix] IMPORTANT: Do NOT return early here.
        // Even if lists are empty (New == Active), we might still need to CLEAR Pending changes!
        // For example: Active=3, Pending=5. User Inputs 3.
        // New(3) == Active(3) -> No messages added.
        // But we MUST proceed to execution block to clear the Pending(5).
        // if (immList.isEmpty() && delList.isEmpty() && nextWeekList.isEmpty() && resList.isEmpty()) return AnalysisResult.NoChange

        // 3. Execution: Partial Apply
        // Strategy: Active Prefs holds Stricter Value (Old or New). Pending Prefs holds Target Value (New).
        
        // Active Prefs Update (Commit Stricter Values)
        val activeEdit = prefs.edit()
        val creditEdit = creditPrefs.edit()
        val dpEdit = getDeviceProtectedPrefs(context)?.edit()
        
        fun resolveActive(cur: Int, bas: Int, new: Int, isHigherStricter: Boolean): Int {
            if (forceAllNextDay) return cur
            val isStricterThanCur = if (isHigherStricter) new >= cur else new <= cur
            val isStricterThanBas = if (isHigherStricter) new >= bas else new <= bas
            
            return if (isStricterThanCur || isStricterThanBas) new else cur
        }
        
        val actRest = resolveActive(curRest, basRest, newRest, true)
        val actGap = resolveActive(curGap, basGap, newGap, true)
        val actBorrow = resolveActive(curBorrow, basBorrow, newBorrow, false)
        val actTempRestrictedAccess = resolveActive(
            curTempRestrictedAccess,
            basTempRestrictedAccess,
            newTempRestrictedAccessMaxSeconds.coerceIn(1, MAX_TEMP_RESTRICTED_ACCESS_MAX_SECONDS),
            false
        )
        val actNotifyFar = newNotifyFar
        val actNotifyNear = newNotifyNear

        activeEdit.putInt("cool_down_rest_seconds", actRest)
        activeEdit.putInt("cool_down_gap_seconds", actGap)
        activeEdit.putInt("borrow_percentage", actBorrow)
        activeEdit.putInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, actTempRestrictedAccess)
        
        // Warnings: Always New
        activeEdit.putInt("notify_advance_minutes", actNotifyFar)
        activeEdit.putInt("notify_entry_near_minutes", actNotifyNear)
        val activeEditPolicy = if (isEditPolicyBenign || isEditPolicyRestored) newEditWindowPolicy else curEditWindowPolicy
        activeEdit.putString(TimeRuleEditWindowPolicyManager.KEY_POLICY, activeEditPolicy)
        
        // 🚀 [Fix] Sync to Device Protected Prefs
        dpEdit?.apply {
            putInt("cool_down_rest_seconds", actRest)
            putInt("cool_down_gap_seconds", actGap)
            putInt("borrow_percentage", actBorrow)
            putInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, actTempRestrictedAccess)
            putInt("notify_advance_minutes", actNotifyFar)
            putInt("notify_entry_near_minutes", actNotifyNear)
            putString(TimeRuleEditWindowPolicyManager.KEY_POLICY, activeEditPolicy)
        }

        // 🚀 [Fix] Refresh Time: Always Update Immediate
        // WP-03-T03: this path previously wrote `config_refresh_time` directly
        // (bypassing the DA-D2 guard in `saveRefreshTime`). Apply an inline
        // guard via `saveRefreshTime` so the DA-D2 policy cannot be bypassed;
        // on REJECT the active value is preserved and we still mirror to
        // Device-Protected Storage.
        if (newRefreshTime != curRefresh) {
            val beforeActive = getRefreshTime(context)
            saveRefreshTime(context, newRefreshTime)
            val afterActive = getRefreshTime(context)
            if (afterActive == newRefreshTime) {
                dpEdit?.putString("config_refresh_time", newRefreshTime)
            } else {
                android.util.Log.w(
                    "TimeRuleManager",
                    "analyzeAndSaveGlobalParams skipped refresh time update " +
                        "($beforeActive → attempted $newRefreshTime): guard rejected"
                )
            }
        }
        
        // Lock Duration
        val activeLock = if (!forceAllNextDay && newLockDuration >= basLock) newLockDuration else curLock
        creditEdit.putLong("strict_penalty_duration", activeLock * 1000)
        
        // 🚀 [Fix] Sync Strict Penalty to Device Protected
        // Note: GlobalParamsActivity/TimeRuleManager usually save Strict Penalty to CreditManager's Prefs.
        // But DP prefs might consolidate? 
        // Let's check saveGlobalParams (Line 1043). It saves "strict_penalty_duration" to PREFS_NAME (Device Protected).
        // So yes, we put it into `dpEdit`.
        dpEdit?.putLong("strict_penalty_duration", activeLock * 1000)
        
        // Weekly Limit (Save to creditEdit = decikeeper_ledger, where CreditManager reads from)
        // 🚀 [Fix] Logic: If New is Lower (Stricter) or Restored -> Apply Immediately.
        // If New is Higher (Looser) -> Keep Old (Cur) in Active.
        // But wait, 'isHigherStricter' for Weekly Limit is FALSE (Lower is Stricter).
        // resolveActive uses: if (isHigherStricter) new >= bas else new <= bas.
        // So for Weekly: if (false) ... else new <= bas. Correct.
        val activeWeekly = resolveActive(curWeekly, basWeekly, newWeeklyCount, false)
        val activeWeeklyDuration = resolveActive(curWeeklyDuration, basWeeklyDuration, newWeeklyDuration, false)

        activeEdit.apply()
        creditEdit.apply()
        dpEdit?.apply()

        // 🚀 [Fix] Persist weekly privilege using CreditManager canonical keys
        CreditManager.setWeeklyPrivilegeLimit(context, activeWeekly, activeWeeklyDuration)
        if (isEffectivePolicyChanged) {
            EffectiveActivationPolicyManager.setPolicy(context, effectivePolicyToApply)
        }

        // 4. Pending Calculation
        // Pending State = Target (New) State
        val effectiveTime = getEffectiveTimestamp(context)
        val existingWeeklyPending = CreditManager.hasPendingWeeklyPrivilegeLimit(context)
        val existingPendingWeeklyCount = if (existingWeeklyPending) CreditManager.getPendingWeeklyPrivilegeLimit(context) else null
        val existingPendingWeeklyDuration = if (existingWeeklyPending) CreditManager.getPendingWeeklyPrivilegeDurationLimit(context) else null
        val existingPendingWeeklyEffective = CreditManager.getPendingWeeklyPrivilegeEffectiveTime(context)
        val weeklyPendingRequested = newWeeklyCount > basWeekly || newWeeklyDuration > basWeeklyDuration
        val weeklyPendingEffectiveTime = if (weeklyPendingRequested) {
            if (existingPendingWeeklyCount == newWeeklyCount &&
                existingPendingWeeklyDuration == newWeeklyDuration &&
                existingPendingWeeklyEffective > 0L
            ) {
                existingPendingWeeklyEffective
            } else {
                ConfigPendingManager.calculateNextMondayEffectiveTime(context, System.currentTimeMillis())
            }
        } else {
            0L
        }
        
        // 🚀 [Fix] Use anyAdverseRemaining to determine if we should WRITE to pending prefs.
        // If TRUE: We overwrite pending prefs with ALL current values (preserving the pending state).
        // If FALSE: It means everything is benign/restored to baseline, so we can clean up.
        if (anyAdverseRemaining) {
             val pendingEdit = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit()
             pendingEdit.putInt("cool_down_rest_seconds", newRest)
             pendingEdit.putInt("cool_down_gap_seconds", newGap)
             pendingEdit.putLong("strict_penalty_duration_seconds", newLockDuration) // Save seconds
             pendingEdit.putInt("borrow_percentage", newBorrow)
             pendingEdit.putInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, newTempRestrictedAccessMaxSeconds.coerceIn(1, MAX_TEMP_RESTRICTED_ACCESS_MAX_SECONDS))
             
             // 🚀 [Fix] Refresh Time: Do NOT save to Pending (It's Immediate)
             // But if we remove it, and other things are pending, it's fine. Pending implies "Different from Active".
             // Since we updated Active, Pending should be removed.
             pendingEdit.remove("config_refresh_time")
             
             // 🚀 [NEW] Privilege Window: Always save to Pending (changes always delayed)
             if (privWindowChanged) {
                 pendingEdit.putString(KEY_PRIVILEGE_WINDOW_START, newPrivilegeWindowStart)
                 pendingEdit.putString(KEY_PRIVILEGE_WINDOW_END, newPrivilegeWindowEnd)
             }

             if (!isEditPolicyBenign && !isEditPolicyRestored) {
                 pendingEdit.putString(TimeRuleEditWindowPolicyManager.KEY_POLICY, newEditWindowPolicy)
             } else {
                 pendingEdit.remove(TimeRuleEditWindowPolicyManager.KEY_POLICY)
             }

             // 🚀 [Fix] Weekly Privilege Pending Storage
             // Resolve: Stricter = Immediate. Looser = Pending.
             // If resolveActive took the NEW value (meaning it was Stricter), then NO pending is needed.
             // If resolveActive took the OLD value (meaning New was Looser), then we need Pending.
             // resolveActive logic for Weekly: if (false) ... else new <= bas.
             // So if new <= bas, Active = new. Pending = irrelevant.
             // If new > bas, Active = cur. Pending = new.
             if (weeklyPendingRequested) {
                  CreditManager.setPendingWeeklyPrivilegeLimit(
                      context,
                      newWeeklyCount,
                      newWeeklyDuration,
                      weeklyPendingEffectiveTime
                  )
              } else {
                  CreditManager.clearPendingWeeklyPrivilegeLimit(context)
              }

             
             // Misc
             pendingEdit.putInt("notify_advance_minutes", newNotifyFar)
             pendingEdit.putInt("notify_entry_near_minutes", newNotifyNear)
             
             // Segments (Must preserve existing segments json in pending if we are not editing segments here?
             // GlobalParamsActivity doesn't edit segments. But `TimeRuleManager.stagePendingRules` might adhere.
             // We must copy current segments to pending if not present, to ensure valid state?
             // Or can we leave segments null? `getPendingTimeSegments` returns null.
             // If we leave null, `applyPendingConfig` won't update segments, which is correct.
             
             // 🚀 [Fix] Use appropriate effective time based on what's pending
             // If only weekly privilege is pending (no daily adverse changes), keep next Monday's time
             // Otherwise use tomorrow's time
             val hasDailyAdverse = delList.isNotEmpty()
             val finalEffectiveTime = if (weeklyPendingRequested && !hasDailyAdverse) {
                 weeklyPendingEffectiveTime
             } else {
                 effectiveTime
             }

             pendingEdit.putBoolean("has_pending_changes", true)
             pendingEdit.putLong("effective_timestamp", finalEffectiveTime)
             pendingEdit.apply()
             
             // [Fix] Refresh AppWindowManager & CreditManager effective_timestamp
             // when config_refresh_time changes
             if (newRefreshTime != curRefresh) {
                 val awPending = context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE)
                 if (awPending.getBoolean("has_pending_changes", false)) {
                     awPending.edit().putLong("effective_timestamp", getEffectiveTimestamp(context)).apply()
                 }
                 val cmPending = context.getSharedPreferences("decikeeper_pending_apps", Context.MODE_PRIVATE)
                 if (cmPending.getBoolean("has_pending_apps", false)) {
                     cmPending.edit().putLong("effective_timestamp", getEffectiveTimestamp(context)).apply()
                 }
             }
             
             if (weeklyPendingRequested) {
                 android.util.Log.i(
                     "TimeRuleManager",
                     "🔒 Persisting Pending Weekly Limit: $newWeeklyCount times, $newWeeklyDuration mins (Adverse)"
                 )
             } else {
                 android.util.Log.i("TimeRuleManager", "🔓 Clearing Pending Weekly Limit (Benign/Revert)")
             }
         } else {
              // Benign Update (Immediate)
              val pendingEdit = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit()
              pendingEdit.remove("cool_down_rest_seconds")
              pendingEdit.remove("cool_down_gap_seconds")
              pendingEdit.remove("borrow_percentage")
              pendingEdit.remove(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)
              pendingEdit.remove("strict_penalty_duration_seconds")
              pendingEdit.remove("config_refresh_time")
              pendingEdit.remove("notify_advance_minutes")
              pendingEdit.remove("notify_entry_near_minutes")
              pendingEdit.remove(KEY_PRIVILEGE_WINDOW_START)
              pendingEdit.remove(KEY_PRIVILEGE_WINDOW_END)
              pendingEdit.remove(TimeRuleEditWindowPolicyManager.KEY_POLICY)
              
              // Check if Segments or Weekly Privilege exist in Pending
              val hasPendingSegments = pendingPrefs.contains(KEY_SEGMENTS_JSON)
              // 🚀 [Fix] Check if Weekly Privilege is Pending
              val hasPendingWeekly = CreditManager.hasPendingWeeklyPrivilegeLimit(context)
              
              if (!hasPendingSegments && !hasPendingWeekly) {
                   pendingEdit.putBoolean("has_pending_changes", false)
                   pendingEdit.remove("effective_timestamp")
              }
              pendingEdit.apply()

              // [Fix] Recalculate effective_timestamp when config_refresh_time changes
              if (newRefreshTime != curRefresh) {
                  // TimeRuleManager: update if segments are pending
                  if (hasPendingSegments) {
                      val trPending = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
                      trPending.edit().putLong("effective_timestamp", getEffectiveTimestamp(context)).apply()
                  }
                  // AppWindowManager: update if pending exists
                  val awPending = context.getSharedPreferences("app_window_pending", Context.MODE_PRIVATE)
                  if (awPending.getBoolean("has_pending_changes", false)) {
                      awPending.edit().putLong("effective_timestamp", getEffectiveTimestamp(context)).apply()
                  }
                  // CreditManager: update if pending blacklist apps exist
                  val cmPending = context.getSharedPreferences("decikeeper_pending_apps", Context.MODE_PRIVATE)
                  if (cmPending.getBoolean("has_pending_apps", false)) {
                      cmPending.edit().putLong("effective_timestamp", getEffectiveTimestamp(context)).apply()
                  }
              }

              context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove("weekly_privilege_limit_pending").apply()
              
              // 🚀 [Fix] Clear Pending Weekly Limit ONLY if we are overwriting it? 
              // Wait, if we are in Benign block, it means `newWeeklyLimit` <= `basWeekly`.
              // But what if `newWeeklyLimit` == `active` (unchanged by user in this edit)?
              // If unchanged, `newWeekly` matches bas/active.
              // If we blindly clear, we kill the pending `4`.
              // Correct Logic: Check if `weekly_privilege` was part of the *change*.
              // Since we don't track change set here easily, we rely on value comparison.
              // If `newWeeklyLimit` == `basWeekly` (or matches current active), AND we have a pending...
              // We should only clear IF user explicitly set it back to favorable value.
              // BUT: `saveGlobalParams` is called with FULL set of params from UI.
              // If user leaves input as "3" (Active), and saves. `new=3`.
              // `3 <= 3`. Benign.
              // Pending is "4".
              // System sees "New=3". Pending="4".
              // Should we keep "4"? YES. User didn't change this field.
              // How to distinguish "User didn't change" vs "User reverted to 3"?
              // Ideally UI should show "Pending: 4". If user changes to 3, it's a revert.
              // If user keeps "Pending: 4" in UI, `new` would be 4?
              // The UI `showGlobalParamsDialog` loads `pending` if available.
              // So if Pending=4, UI shows 4. `new` will be 4.
              // `4 > 3`. This enters ADVERSE block.
              // So if we are in BENIGN block, it means `new` <= `base`.
              // This implies user explicitly changed it to favorable (or it was already favorable/baseline).
              // If Pending was 4. UI loaded 4. User CHANGED to 3. `new=3`. Benign. -> Clear Pending 4. Correct.
              // If Pending was 0? (No pending). UI loaded 3. User kept 3. `new=3`. Benign. -> Clear nothing. Correct.
              
              // WAIT. If Pending was 4 (Adverse). 
              // `showGlobalParamsDialog` logic: `val pendingPrivilege = ... ?: current`.
              // So UI indeed shows 4.
              // User hits Save. `newWeeklyLimit` = 4.
              // `basWeekly` = 3.
              // `new (4) > bas (3)`. This should go to ADVERSE block!
              // Why did user say it disappeared?
              // Maybe `basWeekly` logic is wrong? 
              // Or unrelated benign change triggered this?
              // SCENARIO:
              // 1. Pending 4 exists. UI shows 4.
              // 2. User changes "Rest Time" 60->50 (Favorable).
              // 3. `saveGlobalParams` called with `newWeekly=4`, `newRest=50`.
              // 4. `analyzeGlobalParamsChange` called...
              //    - Weekly: 4 vs 3 (Base). Differs! -> `delList` adds "Weekly Privilege".
              //    - Rest: 50 vs 60 (Base). Differs! -> `immList` adds "Rest Time".
              //    - Result: Mixed!
              // 5. `saveGlobalParams`: `result` is Mixed.
              // 6. It enters the `if (result is AnalysisResult.Delayed || result is AnalysisResult.Mixed)` block.
              //    -> NOT the else block!
              
              // So why did it disappear?
              // Check the `if` block.
              // It calls `CreditManager.setPendingWeeklyPrivilegeLimit`.
              // It sets `has_pending_changes = true`.
              // It sets `effective_timestamp`.
              
              // The BUG the user reported:
              // "Global effective weekly privilege... disappear... when next day adverse save... or favorable save".
              // Wait, if I favorably save Rest:
              // `newWeekly=4`. `bas=3`. 
              // `analyze` sees Weekly Adverse.
              // Result Mixed.
              // Code executes `if` block.
              // Keeps pending.
              
              // What if I DON'T touch Weekly? I kept it as valid pending?
              // UI `showGlobalParamsDialog` loads pending.
              // So `new` = 4. 
              
              // Maybe the issue is when `new` matches `pending`?
              // `checkParam` logic?
              // `checkParam`: `new=4`. `base=3`. `pending=4`.
              // `change = 4 != 3`. `isAdverse = true`.
              // `pendingChg = 4 != 4` -> False?
              // `processChange`:
              // `pendingChg` is false (new == pending).
              // `hasPending` is true.
              // It adds to `delList` ONLY if `pendingChg` is true OR `!hasPending`?
              // Let's check `checkParam` in `TimeRuleManager`:
              /*
              if (pendingChanged) {
                 // add to list
              } else if (hasPending) {
                 // Values matches pending. 
                 // We don't add to "Change List" (delList) because it's not a *new* change?
                 // But we MUST treat it as a valid delayed state.
              }
              */
              // If `delList` is empty (because new == pending), AND `immList` is present (new favorable Rest)...
              // `analyze` returns `Immediate` (if delList empty)?
              // NO. `analyze` must account for "Holding Pending" state.
              
              // Let's verify `checkParam` logic. I need to view `TimeRuleManager.kt` more.
              
              // But assuming I need to edit `else` block regardless for safety:
              // If `newWeeklyLimit` matches `base`, AND we have pending. 
              // This implies implicit Revert?
              // If UI loaded 4. User changed to 3.
              // 3 == 3. `checkParam` says No Change.
              // Result: Immediate/NoChange.
              // Enters `else` block.
              // Clears Pending 4.
              // This acts as a Revert. This seems correct for *that specific param*.
              
              // BUT: What if `newWeekly` was NOT revert?
              // Case: Pending 4. User sets Rest 50.
              // UI sends `newWeekly=4`.
              // `checkParam` should see `4 != 3`.
              
              // HYPOTHESIS: `checkParam` logic flaw.
              // If `new == pending`, it doesn't add to `delList`.
              // So `delList` is empty.
              // If `immList` has item.
              // Result returns `Immediate` (containing immList).
              // Caller sees `Immediate`.
              // Falls into `else` block.
              // `else` block clears ALL pending! including Weekly!
              
              // FIX: The `else` block MUST NOT clear pending if we didn't explicitly revert it.
              // OR `analyze` must return Mixed if we are holding a pending state?
              
              // Changing `else` block is safer execution for now. 
              // In `else` block:
              // We are treating it as "Benign/Immediate".
              // Changes are applied immediately.
              // But we must NOT kill unrelated pending rules.
              
              // If `newWeeklyLimit` (passed in) != `base`. (e.g. 4 vs 3).
              // But `analyze` returned Immediate because `4 == pending`.
              // Then `else` block runs.
              // `newWeeklyLimit` (4) > `basWeekly` (3).
              // We should NOT clear pending!
              // In fact, we should `setPending` to satisfy the state!
              
              // 🚀 [Fix] Updated Logic for `else` block:
                if (weeklyPendingRequested) {
                    // Even if "Benign" update (e.g. valid immediate changes elsewhere),
                    // if Weekly Limit > Base, we MUST persist pending state for it.
                    CreditManager.setPendingWeeklyPrivilegeLimit(
                       context,
                       newWeeklyCount,
                       newWeeklyDuration,
                       weeklyPendingEffectiveTime
                    )
                } else {
                   // Only clear if new <= base (Revert or Original)
                   CreditManager.clearPendingWeeklyPrivilegeLimit(context)
                   ChangeLogManager.removePendingEntriesByTag(context, "global_params", "weekly_privilege_count")
                   ChangeLogManager.removePendingEntriesByTag(context, "global_params", "weekly_privilege_duration")
              }
               
               // 🚀 [ChangeLog] Log favorable changes (only pure additions/strengthening)
               if (immList.isNotEmpty()) {
                   val items = immList.map { Triple(it, false, null as String?) }
                   ChangeLogManager.addEntries(context, "global_params", items)
               }
               
               if (immList.isEmpty() && resList.isEmpty()) {
                   return AnalysisResult.NoChange
               }

               return AnalysisResult.AllImmediate(immList)
         }

         // 🚀 [ChangeLog] Log all changes with deduplication tags
         if (immList.isNotEmpty()) {
             // Favorable changes (Immediate) -> Tag is less critical as they disappear, but good practice.
             // We need to map back which message belongs to which param.
             // Since immList is just strings, we can't easily map back without parsing or changing logic.
             // However, FAVORABLE changes are hidden immediately anyway per new logic.
             // So we just pass null tag or generic.
             val items = immList.map { Triple(it, false, null as String?) }
             ChangeLogManager.addEntries(context, "global_params", items)
         }
         
         if (delList.isNotEmpty()) {
             // Adverse changes (Delayed) -> NEED TAGS for deduplication.
             // We need to know which param generated which message.
             // Refactor: We should generate the list of (Msg, Tag) tuples earlier.
             // Re-map strings to tags based on content (Fragile but easy) or Refactor logical flow?
             // Content mapping: "每周特权次数" -> "weekly_privilege"
             
             val items = delList.map { msg -> Triple(msg, true, globalParamLogTag(msg)) }
             ChangeLogManager.addEntries(context, "global_params", items)
         }
         
         if (resList.isNotEmpty()) {
             // Restored changes are immediate, not pending
             val items = resList.map { msg -> Triple(msg, false, globalParamLogTag(msg)) }
             ChangeLogManager.addEntries(context, "global_params", items)
         }
         
         if (nextWeekList.isNotEmpty()) {
              // Weekly Privilege Cycle Increase：次数 / 总时长各用各的标签，互不顶替
              val items = nextWeekList.map { Triple(it, true, globalParamLogTag(it) ?: "weekly_privilege") }
              ChangeLogManager.addEntries(context, "global_params", items)
          }

          if (immList.isEmpty() && delList.isEmpty() && nextWeekList.isEmpty() && resList.isEmpty()) {
               return AnalysisResult.NoChange
           }

           // 🚀 [Fix] Calculate next Monday effective time for weekly privilege changes
           val nextWeekEffectiveTime = if (nextWeekList.isNotEmpty()) {
               ConfigPendingManager.calculateNextMondayEffectiveTime(context, System.currentTimeMillis())
           } else 0L

           return AnalysisResult.Mixed(immList, delList, resList, nextWeekList, effectiveTime, nextWeekEffectiveTime)
    }

    /**
     * 规则参数修改记录的去重标签：一个参数一条记录，后改的只顶掉同一个参数的旧记录。
     * 每周特权的次数和总时长是两个参数，必须各用各的标签（`weekly_privilege_` 前缀，
     * `ChangeLogManager` 按前缀识别每周特权），否则后改的会把先改的那条顶掉。
     */
    internal fun globalParamLogTag(message: String): String? = when {
        message.contains("每周特权次数") -> "weekly_privilege_count"
        message.contains("每周特权总时长") -> "weekly_privilege_duration"
        message.contains("每周特权") -> "weekly_privilege"
        message.contains("休息冷却") -> "cool_down_rest"
        message.contains("空档冷却") -> "cool_down_gap"
        message.contains("借用比例") -> "borrow_percentage"
        message.contains("临时使用受限应用时长上限") -> "temp_restricted_access_max_seconds"
        message.contains("黑名单惩罚时长") -> "strict_penalty"
        message.contains("生效时间") -> "refresh_time"
        message.contains("远期预警") -> "notify_far"
        message.contains("近期预警") -> "notify_near"
        else -> null
    }

    fun getLogicalFirstSegment(context: Context, segments: List<TimeSegment>? = null): TimeSegment? {
        val list = segments ?: getTimeSegments(context)
        if (list.isEmpty()) return null
        return list.minByOrNull { it.startHour * 60 + it.startMinute }
    }
    


    // ==========================================
    // 🔵 待生效规则
    // ==========================================
    fun hasPendingRules(context: Context): Boolean = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).getBoolean("has_pending_changes", false)

    fun getPendingTimeSegments(context: Context): List<TimeSegment>? {
        if (!hasPendingRules(context)) return null
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SEGMENTS_JSON, null) ?: return null
        return parseSegmentsFromJson(json)
    }

    fun recalculatePendingAfterPolicySwitch(
        context: Context,
        policy: EffectiveActivationPolicyManager.Policy
    ) {
        if (isInAdaptationPeriod(context)) return

        // 🟢 [Fix] Ensure fresh data — tests write directly to prefs without invalidating cache.
        invalidateSegmentsCache()

        // Absorb any legacy favorable state before reconciling policy.
        promoteMatureBaseline(context)

        val baselineSegments = getBaselineSegments(context) ?: return
        val activeSegments = getTimeSegments(context)
        val baselineJson = segmentsToJson(baselineSegments)
        val activeJson = segmentsToJson(activeSegments)
        val pendingPrefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)

        when (policy) {
            EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY -> {
                // [Fix: data loss on strategy switch] 切换前先检查已有 Pending 数据。
                // 若用户从 FAVORABLE_IMMEDIATE 切过来且有不有利的修改已在 Pending 中，
                // active 和 baseline 一致导致早期 return，Pending 数据被丢弃。
                val existingPending = getPendingTimeSegments(context)
                val hasExistingPending = existingPending != null && segmentsToJson(existingPending) != baselineJson
                if (!hasExistingPending && baselineJson == activeJson) return

                if (!hasExistingPending) {
                    // Switch to strict policy: revert current active to baseline and stage the current active as pending.
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_SEGMENTS_JSON, baselineJson)
                        .apply()
                    getDeviceProtectedPrefs(context)?.edit()?.putString(KEY_SEGMENTS_JSON, baselineJson)?.apply()
                    invalidateSegmentsCache()

                    val effectiveTime = getEffectiveTimestamp(context)
                    pendingPrefs.edit()
                        .putString(KEY_SEGMENTS_JSON, activeJson)
                        .putBoolean("has_pending_changes", true)
                        .putLong("effective_timestamp", effectiveTime)
                        .apply()
                } else {
                    // 🟢 [Fix] 已有 Pending 但 active 可能 ≠ baseline（例如从 FAVORABLE_IMMEDIATE 切换过来，
                    // 有利修改已在 active 中生效），需要将 active 回滚到 baseline，
                    // 否则 active 中的有利修改不会显示"待生效"标签。
                    if (activeJson != baselineJson) {
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_SEGMENTS_JSON, baselineJson)
                            .apply()
                        getDeviceProtectedPrefs(context)?.edit()?.putString(KEY_SEGMENTS_JSON, baselineJson)?.apply()
                        invalidateSegmentsCache()
                    }
                }

                ChangeLogManager.removePendingEntriesByCategory(context, "segments")
                val diffTarget = if (hasExistingPending) existingPending!! else activeSegments
                val delayedMessages = SegmentAdverseAnalysis.getPendingDiffString(context, baselineSegments, diffTarget, baselineSegments)
                    .ifEmpty { listOf("策略切换：检测到当前修改，已转入次日生效。") }
                ChangeLogManager.addEntries(
                    context,
                    "segments",
                    delayedMessages.mapIndexed { index, msg ->
                        Triple(msg, true, "policy_switch_recalc_$index")
                    }
                )
            }

            EffectiveActivationPolicyManager.Policy.FAVORABLE_IMMEDIATE -> {
                val pendingSegments = getPendingTimeSegments(context) ?: return
                val pendingJson = segmentsToJson(pendingSegments)

                if (pendingJson == activeJson) {
                    val hasOtherPending = pendingPrefs.all.keys.any {
                        it != KEY_SEGMENTS_JSON &&
                            it != "has_pending_changes" &&
                            it != "effective_timestamp"
                    } || AppWindowManager.hasPendingChanges(context)
                    val edit = pendingPrefs.edit().remove(KEY_SEGMENTS_JSON)
                    if (!hasOtherPending) {
                        edit.clear()
                    }
                    // else: keep has_pending_changes and effective_timestamp for other pending categories
                    edit.apply()
                    ChangeLogManager.removePendingEntriesByCategory(context, "segments")
                    return
                }

                val isPendingAdverse = SegmentAdverseAnalysis.isAdverseRelativeToBaseline(context, pendingSegments)
                if (isPendingAdverse) {
                    // 🟢 [Fix] 混合修改：有利立即生效，不利保留待生效
                    // 之前 isAdverseRelativeToBaseline 返回 true 就直接 return，
                    // 导致有利修改也留在 pending 中，UI 上"待生效"标签无法去除。
                    // 改进：逐个段判断，有利的写入 active，不利的保留在 pending。
                    val newActiveSegments = mutableListOf<TimeSegment>()
                    val baselineMatchedIndices = mutableSetOf<Int>()

                    for (seg in pendingSegments) {
                        val baseSeg = SegmentAdverseAnalysis.findMatchingOldSegment(seg, baselineSegments)
                        if (baseSeg != null) {
                            val baseIdx = baselineSegments.indexOf(baseSeg)
                            if (baseIdx >= 0) baselineMatchedIndices.add(baseIdx)

                            // 🟢 [Fix] 新鲜段（当前周期刚修改的）不走有利/不利判断，直接应用
                            if (SegmentAdverseAnalysis.isSegmentFresh(baseSeg)) {
                                newActiveSegments.add(seg.copy())
                                continue
                            }

                            if (SegmentAdverseAnalysis.isDaysOverlapping(baseSeg.daysOfWeek, seg.daysOfWeek)) {
                                if (SegmentAdverseAnalysis.isSingleSegmentAdverse(baseSeg, seg, context)) {
                                    // 不利：active 保留 baseline 版本
                                    newActiveSegments.add(baseSeg.copy())
                                } else {
                                    // 有利/恢复：立即生效
                                    newActiveSegments.add(seg.copy())
                                }
                            } else {
                                // 日期不重叠：保留新版本
                                newActiveSegments.add(seg.copy())
                            }
                        } else {
                            // 新增段：无冲突则立即加入
                            val hasConflict = baselineSegments.any { bs ->
                                !SegmentAdverseAnalysis.isSegmentFresh(bs) &&
                                SegmentAdverseAnalysis.isDaysOverlapping(bs.daysOfWeek, seg.daysOfWeek) &&
                                SegmentAdverseAnalysis.calculateOverlapMinutes(seg, bs) > 0
                            }
                            if (!hasConflict) {
                                newActiveSegments.add(seg.copy())
                            }
                            // 有冲突（不利）→ 不加到 active，等 pending 生效
                        }
                    }

                    // 保留 baseline 中未被 pending 覆盖的段（待删除 → 延迟生效）
                    for (i in baselineSegments.indices) {
                        if (i !in baselineMatchedIndices && !SegmentAdverseAnalysis.isSegmentFresh(baselineSegments[i])) {
                            newActiveSegments.add(baselineSegments[i].copy())
                        }
                    }

                    val sortedActive = newActiveSegments.sortedBy { it.startHour * 60 + it.startMinute }
                    val sortedActiveJson = segmentsToJson(sortedActive)

                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_SEGMENTS_JSON, sortedActiveJson).apply()
                    getDeviceProtectedPrefs(context)?.edit()?.putString(KEY_SEGMENTS_JSON, sortedActiveJson)?.apply()
                    invalidateSegmentsCache()
                    // 不更新 baseline（仅有利改动已生效，不利还在 pending 中）
                    // pending 维持原样（包含全部修改），UI 中有利段因 active==pending 不再显示"待生效"

                    ChangeLogManager.removePendingEntriesByCategory(context, "segments")
                    val remainingDiff = SegmentAdverseAnalysis.getPendingDiffString(context, sortedActive, pendingSegments, baselineSegments)
                    val changelogMsgs = if (remainingDiff.isNotEmpty()) {
                        remainingDiff.map { Triple(it, true, "policy_switch_mixed_kept") }
                    } else {
                        listOf(Triple("策略切换：有利修改已立即生效，不利修改仍保留待生效。", false, "policy_switch_mixed_clear"))
                    }
                    ChangeLogManager.addEntries(context, "segments", changelogMsgs)
                    return
                }

                // Switch back to default policy: favorable/restored pending changes should apply immediately.
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SEGMENTS_JSON, pendingJson)
                    .apply()
                getDeviceProtectedPrefs(context)?.edit()?.putString(KEY_SEGMENTS_JSON, pendingJson)?.apply()
                invalidateSegmentsCache()
                updateBaselineAfterImmediateChange(context)

                val hasOtherPending = pendingPrefs.all.keys.any {
                    it != KEY_SEGMENTS_JSON &&
                        it != "has_pending_changes" &&
                        it != "effective_timestamp"
                } || AppWindowManager.hasPendingChanges(context)
                val edit = pendingPrefs.edit().remove(KEY_SEGMENTS_JSON)
                if (!hasOtherPending) {
                    edit.clear()
                }
                // else: keep has_pending_changes and effective_timestamp for other pending categories
                edit.apply()

                ChangeLogManager.removePendingEntriesByCategory(context, "segments")
                val immediateMessages = SegmentAdverseAnalysis.getPendingDiffString(context, activeSegments, pendingSegments, baselineSegments)
                    .ifEmpty { listOf("策略切换：待生效的有利/恢复修改已立即生效。") }
                ChangeLogManager.addEntries(
                    context,
                    "segments",
                    immediateMessages.mapIndexed { index, msg ->
                        Triple(msg, false, "policy_switch_recalc_$index")
                    }
                )
            }
        }
    }

    fun getPendingCoolDownSeconds(context: Context): Int? {
        if (!hasPendingRules(context)) return null
        val currentSeg = getCurrentSegment(context) // Note: This uses logic for *current* time
        val pendingRest = getPendingCoolDownRestSeconds(context)
        val pendingGap = getPendingCoolDownGapSeconds(context)
        
        return if (currentSeg != null) {
            pendingRest ?: getPendingCoolDownRestSeconds(context)
        } else {
            pendingGap ?: getPendingCoolDownGapSeconds(context)
        }
    }
    fun getPendingCoolDownRestSeconds(context: Context): Int? {
        if (!hasPendingRules(context)) return null
        return context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).getInt("cool_down_rest_seconds", 60)
    }
    fun getPendingCoolDownGapSeconds(context: Context): Int? {
        if (!hasPendingRules(context)) return null
        return context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).getInt("cool_down_gap_seconds", 60)
    }
    fun getPendingNotifyAdvanceMinutes(context: Context): Int? {
        if (!hasPendingRules(context)) return null
        return context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).getInt("notify_advance_minutes", 20)
    }
    fun getPendingNotifyEntryNearMinutes(context: Context): Int? {
        if (!hasPendingRules(context)) return null
        return context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).getInt("notify_entry_near_minutes", 5)
    }
    fun getPendingBorrowPercentage(context: Context): Int? {
        if (!hasPendingRules(context)) return null
        return context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).getInt("borrow_percentage", 50)
    }
    fun getPendingTemporaryRestrictedAccessMaxSeconds(context: Context): Int? {
        if (!hasPendingRules(context)) return null
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)) return null
        return prefs.getInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, DEFAULT_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)
    }
    // 🚀 [Fix] Add getters for new pending params
    fun getPendingStrictPenaltyDurationSeconds(context: Context): Long? {
         if (!hasPendingRules(context)) return null
         val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
         if (!prefs.contains("strict_penalty_duration_seconds")) return null
         return prefs.getLong("strict_penalty_duration_seconds", 15)
    }
    fun getPendingRefreshTime(context: Context): String? {
         if (!hasPendingRules(context)) return null
         return context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).getString("config_refresh_time", null)
    }
    
    // ==========================================
    // 🔵 基本参数快照 (Baseline Snapshot)
    // ==========================================
    fun saveBaselineParams(context: Context) {
        val activePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val creditPrefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        
        val baselinePrefs = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
        baselinePrefs.edit().apply {
            putInt("cool_down_rest_seconds", activePrefs.getInt("cool_down_rest_seconds", 60))
            putInt("cool_down_gap_seconds", activePrefs.getInt("cool_down_gap_seconds", 60))
            putInt("borrow_percentage", activePrefs.getInt("borrow_percentage", 50))
            putInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, activePrefs.getInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, DEFAULT_TEMP_RESTRICTED_ACCESS_MAX_SECONDS))
            
            // 🚀 [Fix] Enforce Floor for Baseline (Min 15s) to avoid 0s bug
            val curStrict = creditPrefs.getLong("strict_penalty_duration", 15000L)
            putLong("strict_penalty_duration", curStrict.coerceAtLeast(15000L))
            
            // 🚀 [Fix] Add missing baseline params — use CreditManager getters for correct v2 keys
            putInt("weekly_privilege_limit", CreditManager.getWeeklyPrivilegeLimit(context))
            putInt("weekly_privilege_duration_limit", CreditManager.getWeeklyPrivilegeDurationLimit(context))
            putString("config_refresh_time", creditPrefs.getString("config_refresh_time", "08:00"))
            
            // 🚀 [Fix] Save Anti-Uninstall State Baseline
            val isAntiUninstallActive = AntiUninstallManager.getProtectionState(context) == AntiUninstallManager.State.ACTIVE
            putBoolean("is_anti_uninstall_enabled", isAntiUninstallActive)
            putBoolean("is_anti_uninstall_active", isAntiUninstallActive)

            // 🚀 [Baseline] Save Accessibility & Floating Guard State Baseline
            val sysA11yEnabled = com.decikeeper.app.manager.PermissionManager.isAccessibilityEnabled(context)
            val a11yState = AccessibilityControlManager.getState(context)
            val isA11yActive = if (sysA11yEnabled) {
                if (a11yState == AccessibilityControlManager.State.DISABLED) {
                    AccessibilityControlManager.setEnabled(context, true)
                }
                a11yState != AccessibilityControlManager.State.PENDING
            } else {
                false
            }
            putBoolean("a11y_service_enabled", isA11yActive)

            val isFloatingGuardActive = GuardFloatingWindowManager.getProtectionState(context) == GuardFloatingWindowManager.State.ACTIVE
            putBoolean("floating_guard_enabled", isFloatingGuardActive)

            putBoolean(
                "is_privacy_protection_enabled",
                PrivacyProtectionManager.getProtectionState(context) == PrivacyProtectionManager.State.ACTIVE
            )
            putBoolean(
                "is_floating_window_protection_enabled",
                FloatingWindowProtectionManager.getProtectionState(context) == FloatingWindowProtectionManager.State.ACTIVE
            )
            putBoolean(
                DeveloperOptionsGuardManager.KEY_IS_ENABLED,
                DeveloperOptionsGuardManager.getProtectionState(context) == DeveloperOptionsGuardManager.State.ACTIVE
            )
            putBoolean(
                InstallGuardManager.KEY_IS_ENABLED,
                InstallGuardManager.getProtectionState(context) == InstallGuardManager.State.ACTIVE
            )

            val segJson = activePrefs.getString(KEY_SEGMENTS_JSON, null)
            if (segJson != null) putString(KEY_SEGMENTS_JSON, segJson)
            
            putLong("snapshot_time", System.currentTimeMillis())
        }.apply()
        
        // 🚀 [Sync] Save Baseline Blacklist as well
        CreditManager.saveBaselineBlacklist(context)
    }
    
    fun hasBaseline(context: Context): Boolean = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE).contains("snapshot_time")
    


    // getPendingProtectionLevel removed

    // Helper: Get Start of Current Business Day (at Refresh Time)
    internal fun getStartOfBusinessDay(context: Context, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        
        val refreshTime = getRefreshTime(context)
        val parts = refreshTime.split(":")
        val refreshH = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val refreshM = parts.getOrNull(1)?.toIntOrNull() ?: 0
        
        val currentH = cal.get(Calendar.HOUR_OF_DAY)
        val currentM = cal.get(Calendar.MINUTE)
        
        val currentTotal = currentH * 60 + currentM
        val refreshTotal = refreshH * 60 + refreshM

        // If before Refresh Time, it belongs to previous day's cycle
        if (currentTotal < refreshTotal) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        
        cal.set(Calendar.HOUR_OF_DAY, refreshH)
        cal.set(Calendar.MINUTE, refreshM)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun stagePendingRules(context: Context, segments: List<TimeSegment>, coolDown: Int, restCd: Int, gapCd: Int, notifyFar: Int, borrowPercent: Int, strictPenaltySecs: Long = 15, notifyNear: Int = 5): Long {
        val isAdaptation = isInAdaptationPeriod(context)
        if (isAdaptation) {
            saveTimeSegments(context, segments)
            saveGlobalParams(context, coolDown, notifyFar, borrowPercent, strictPenaltySecs, notifyNear)
            saveDifferentiatedCoolDowns(context, restCd, gapCd)
            context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit().clear().apply()
            
            val intent = android.content.Intent(context, MonitorService::class.java)
            intent.action = MonitorService.ACTION_REFRESH_RULES
            context.startService(intent)
            return 0L
        }

        val activePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingPrefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val pendingEditor = pendingPrefs.edit()
        val creditPrefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val immediateEditor = activePrefs.edit()
        val immediateCreditEditor = creditPrefs.edit()

        // 1. Retrieve Current Values
        val curRestCd = getCoolDownRestSeconds(context)
        val curGapCd = getCoolDownGapSeconds(context)
        val curBorrow = getBorrowPercentage(context)
        val curStrict = getStrictPenaltyDurationSeconds(context)
        val curSegments = getTimeSegments(context)

        // 2. Retrieve Baseline Values (Snapshot at 06:00)
        val basRestCd = getBaselineCoolDownRestSeconds(context)
        val basGapCd = getBaselineCoolDownGapSeconds(context)
        val basBorrow = getBaselineBorrowPercentage(context)
        val basStrict = getBaselineStrictPenaltyDurationSeconds(context)
        val basSegments = getBaselineSegments(context)

        var hasPending = false

        val forceAllNextDay = !isAdaptation && (EffectiveActivationPolicyManager.getPolicy(context) == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY)

        // Logic Helper:
        // isBenign(New, Ref) -> TRUE if New is Stricter/Equal to Ref.
        // If New is Benign vs Baseline OR New is Benign vs Current -> Immediate.
        // Otherwise -> Pending.
        
        // --- 1. Rest CoolDown ---
        // Stricter: Higher val
        fun isRestCdBenign(newVal: Int, refVal: Int): Boolean = newVal >= refVal
        val benignRest = !forceAllNextDay && ((basRestCd != null && isRestCdBenign(restCd, basRestCd)) || isRestCdBenign(restCd, curRestCd))
        
        if (benignRest) {
            immediateEditor.putInt("cool_down_rest_seconds", restCd)
            immediateEditor.putInt("cool_down_seconds", restCd)
            if (pendingPrefs.contains("cool_down_rest_seconds")) pendingEditor.remove("cool_down_rest_seconds")
            if (pendingPrefs.contains("cool_down_seconds")) pendingEditor.remove("cool_down_seconds")
        } else {
            pendingEditor.putInt("cool_down_rest_seconds", restCd)
            pendingEditor.putInt("cool_down_seconds", restCd)
            hasPending = true
        }

        // --- 2. Gap CoolDown ---
        // Stricter: Higher val
        fun isGapCdBenign(newVal: Int, refVal: Int): Boolean = newVal >= refVal
        val benignGap = !forceAllNextDay && ((basGapCd != null && isGapCdBenign(gapCd, basGapCd)) || isGapCdBenign(gapCd, curGapCd))
        
        if (benignGap) {
            immediateEditor.putInt("cool_down_gap_seconds", gapCd)
            if (pendingPrefs.contains("cool_down_gap_seconds")) pendingEditor.remove("cool_down_gap_seconds")
        } else {
            pendingEditor.putInt("cool_down_gap_seconds", gapCd)
            hasPending = true
        }

        // --- 3. Borrow Percent ---
        // Stricter: Lower val
        fun isBorrowBenign(newVal: Int, refVal: Int): Boolean = newVal <= refVal
        val benignBorrow = !forceAllNextDay && ((basBorrow != null && isBorrowBenign(borrowPercent, basBorrow)) || isBorrowBenign(borrowPercent, curBorrow))

        if (benignBorrow) {
            immediateEditor.putInt("borrow_percentage", borrowPercent)
            if (pendingPrefs.contains("borrow_percentage")) pendingEditor.remove("borrow_percentage")
        } else {
            pendingEditor.putInt("borrow_percentage", borrowPercent)
            hasPending = true
        }

        // --- 5. Strict Penalty ---
        // Stricter: Higher val
        fun isStrictBenign(newVal: Long, refVal: Long): Boolean = newVal >= refVal
        val benignStrict = !forceAllNextDay && ((basStrict != null && isStrictBenign(strictPenaltySecs, basStrict)) || isStrictBenign(strictPenaltySecs, curStrict))
        
        if (benignStrict) {
            immediateCreditEditor.putLong("strict_penalty_duration", strictPenaltySecs * 1000)
            if (pendingPrefs.contains("strict_penalty_duration_seconds")) pendingEditor.remove("strict_penalty_duration_seconds")
        } else {
            pendingEditor.putLong("strict_penalty_duration_seconds", strictPenaltySecs)
            hasPending = true
        }

        // --- 6. Notify (Always Immediate) ---
        immediateEditor.putInt("notify_advance_minutes", notifyFar)
        immediateEditor.putInt("notify_entry_near_minutes", notifyNear)
        if (pendingPrefs.contains("notify_advance_minutes")) pendingEditor.remove("notify_advance_minutes")
        if (pendingPrefs.contains("notify_entry_near_minutes")) pendingEditor.remove("notify_entry_near_minutes")

        // --- 7. Segments (Hybrid Logic) ---
        // 🚀 [Hybrid] Per-segment evaluation: Benign -> Immediate, Adverse -> Pending
        val immediateSegments = mutableListOf<TimeSegment>()
        val pendingSegments = mutableListOf<TimeSegment>()
        
        // Track old segments to detect deletions
        val processedOldStarts = mutableSetOf<Pair<Int, Int>>()
        
        for (newSeg in segments) {
            val oldSeg = SegmentAdverseAnalysis.findMatchingOldSegment(newSeg, curSegments)
            val baseOldSeg = if (basSegments != null) SegmentAdverseAnalysis.findMatchingOldSegment(newSeg, basSegments) else null
            
            // Compare against Baseline first, then Current
            val adverseVsBase = SegmentAdverseAnalysis.isSingleSegmentAdverse(baseOldSeg, newSeg, context)
            val adverseVsCur = SegmentAdverseAnalysis.isSingleSegmentAdverse(oldSeg, newSeg, context)
            
            val benign = !adverseVsBase || !adverseVsCur
            
            if (benign) {
                immediateSegments.add(newSeg)
            } else {
                // Keep old segment in immediate (if exists), schedule new for pending
                if (oldSeg != null) {
                    immediateSegments.add(oldSeg) // Keep stricter old version
                }
                pendingSegments.add(newSeg)
            }
            
            // Mark this start time as processed
            if (oldSeg != null) processedOldStarts.add(Pair(oldSeg.startHour, oldSeg.startMinute))
        }
        
        // Check for deleted segments (in Old but not in New)
        for (oldSeg in curSegments) {
            val stillExists = segments.any { it.startHour == oldSeg.startHour && it.startMinute == oldSeg.startMinute }
            if (!stillExists) {
                // Deletion Check:
                // If it was in Baseline -> Removing it is Adverse (Delayed).
                // If it was NOT in Baseline (Added today) -> Removing it is Benign (Immediate Undo).
                
                val wasInBaseline = basSegments?.any { it.startHour == oldSeg.startHour && it.startMinute == oldSeg.startMinute } ?: false
                
                if (wasInBaseline) {
                    // Adverse Deletion
                    immediateSegments.add(oldSeg)
                    hasPending = true
                } else {
                    // Benign Deletion (Undo today's addition)
                    // Do not add to immediateSegments -> Effectively deleted
                }
            }
        }
        
        // Save Immediate Segments
        val immediateJson = segmentsToJson(immediateSegments)
        immediateEditor.putString(KEY_SEGMENTS_JSON, immediateJson)
        getDeviceProtectedPrefs(context)?.edit()?.putString(KEY_SEGMENTS_JSON, immediateJson)?.apply()
        
        // 🚀 [Fix] Invalidate cache after saving new segments
        invalidateSegmentsCache()
        
        // Save Pending Segments (Full intended list, not just adverse ones)
        if (pendingSegments.isNotEmpty() || hasPending) {
            pendingEditor.putString(KEY_SEGMENTS_JSON, segmentsToJson(segments))
            hasPending = true
        } else {
            if (pendingPrefs.contains(KEY_SEGMENTS_JSON)) pendingEditor.remove(KEY_SEGMENTS_JSON)
        }

        // Apply Immediate
        immediateEditor.remove("protection_level").remove("is_super_strict_mode").apply()
        immediateCreditEditor.apply()
        
        getDeviceProtectedPrefs(context)?.edit()?.apply {
            if (benignRest) { putInt("cool_down_rest_seconds", restCd); putInt("cool_down_seconds", restCd) }
            if (benignGap) putInt("cool_down_gap_seconds", gapCd)
            if (benignBorrow) putInt("borrow_percentage", borrowPercent)
            if (benignStrict) putLong("strict_penalty_duration", strictPenaltySecs * 1000)
            putInt("notify_advance_minutes", notifyFar)
            putInt("notify_entry_near_minutes", notifyNear)
        }?.apply()

        if (hasPending) {
             // 🚀 [Fix] Remove isCycleReduction -> Next Monday. All segment delays use Tomorrow 06:00.
             // Only Weekly Privilege (in CreditManager) uses Next Monday.
             val newEffectiveTime = ConfigPendingManager.calculateNextEffectiveTime(context, System.currentTimeMillis())
             
             pendingEditor.putBoolean("has_pending_changes", true)
             pendingEditor.putLong("effective_timestamp", newEffectiveTime)
             pendingEditor.apply()
             
             val intent = android.content.Intent(context, MonitorService::class.java)
             intent.action = MonitorService.ACTION_REFRESH_RULES
             context.startService(intent)
             return newEffectiveTime
        } else {
             pendingEditor.putBoolean("has_pending_changes", false).apply()
             val intent = android.content.Intent(context, MonitorService::class.java)
             intent.action = MonitorService.ACTION_REFRESH_RULES
             context.startService(intent)
             return 0L
        }
    }

    fun applyPendingToActive(context: Context, force: Boolean = false) {
        val pendingPrefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        if (!pendingPrefs.getBoolean("has_pending_changes", false)) return

        // 🟢 Check Effective Time
        val effectiveTime = pendingPrefs.getLong("effective_timestamp", 0L)
        val now = System.currentTimeMillis()

        // If effective time hasn't passed, do not apply.
        if (!force && now < effectiveTime) return

        val activePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = activePrefs.edit()

        pendingPrefs.getString(KEY_SEGMENTS_JSON, null)?.let {
            editor.putString(KEY_SEGMENTS_JSON, it)
        }
        if (pendingPrefs.contains("cool_down_seconds")) {
            editor.putInt("cool_down_seconds", pendingPrefs.getInt("cool_down_seconds", 60))
        }
        if (pendingPrefs.contains("cool_down_rest_seconds")) {
            editor.putInt("cool_down_rest_seconds", pendingPrefs.getInt("cool_down_rest_seconds", 60))
        }
        if (pendingPrefs.contains("cool_down_gap_seconds")) {
            editor.putInt("cool_down_gap_seconds", pendingPrefs.getInt("cool_down_gap_seconds", 60))
        }
        if (pendingPrefs.contains("notify_advance_minutes")) {
            editor.putInt("notify_advance_minutes", pendingPrefs.getInt("notify_advance_minutes", 20))
        }
        if (pendingPrefs.contains("notify_entry_near_minutes")) {
            editor.putInt("notify_entry_near_minutes", pendingPrefs.getInt("notify_entry_near_minutes", 5))
        }
        if (pendingPrefs.contains("borrow_percentage")) {
        editor.putInt("borrow_percentage", pendingPrefs.getInt("borrow_percentage", 50))
    }
    if (pendingPrefs.contains(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)) {
        editor.putInt(
            KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS,
            pendingPrefs.getInt(KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, DEFAULT_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)
                .coerceIn(1, MAX_TEMP_RESTRICTED_ACCESS_MAX_SECONDS)
        )
    }
    
    if (pendingPrefs.contains("strict_penalty_duration_seconds")) {
        val secs = pendingPrefs.getLong("strict_penalty_duration_seconds", 15)
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("strict_penalty_duration", secs * 1000)
            .apply()
    }
    
    if (pendingPrefs.contains("config_refresh_time")) {
        val refreshTime = pendingPrefs.getString("config_refresh_time", "08:00")
        // WP-03-T03: route through the public saveRefreshTime guard so a
        // pending refresh value that no longer respects DA-D2 (because
        // segments moved in the meantime) cannot be promoted to active
        // without re-validation. saveRefreshTime is atomic — on REJECT the
        // active refresh time is left untouched.
        saveRefreshTime(context, refreshTime ?: "08:00")
    }

    // 🚀 [NEW] Apply Pending Privilege Window
    if (pendingPrefs.contains(KEY_PRIVILEGE_WINDOW_START)) {
        val pwStart = pendingPrefs.getString(KEY_PRIVILEGE_WINDOW_START, "08:00") ?: "08:00"
        val pwEnd = pendingPrefs.getString(KEY_PRIVILEGE_WINDOW_END, "23:59") ?: "23:59"
        editor.putString(KEY_PRIVILEGE_WINDOW_START, pwStart)
        editor.putString(KEY_PRIVILEGE_WINDOW_END, pwEnd)
    }

    if (pendingPrefs.contains(TimeRuleEditWindowPolicyManager.KEY_POLICY)) {
        val policyStr = pendingPrefs.getString(
            TimeRuleEditWindowPolicyManager.KEY_POLICY,
            TimeRuleEditWindowPolicyManager.Policy.ALL_DAY.key
        ) ?: TimeRuleEditWindowPolicyManager.Policy.ALL_DAY.key
        editor.putString(TimeRuleEditWindowPolicyManager.KEY_POLICY, policyStr)
        getDeviceProtectedPrefs(context)?.edit()
            ?.putString(TimeRuleEditWindowPolicyManager.KEY_POLICY, policyStr)
            ?.apply()
    }

	    editor.apply()
	    
	    // 🚀 [Fix] Mark ChangeLogs as Applied so they disappear from "Pending" UI
    // IMPORTANT: Skip weekly_privilege entries — they have their own effective time (next Monday)
    ChangeLogManager.markCategoryAsAppliedExcludingTag(context, "global_params", "weekly_privilege")
    ChangeLogManager.markCategoryAsApplied(context, "segments")

    // 🚀 [Fix] Apply pending weekly privilege if its effective time has also passed
    CreditManager.checkAndApplyPendingWeeklyLimit(context)

    // 🚀 [Fix] Check if weekly privilege still pending after clearing daily pendings
    val hasWeeklyPending = CreditManager.hasPendingWeeklyPrivilegeLimit(context)

    // 🚀 [Fix] Clear pending data keys after application
    pendingPrefs.edit().clear().apply()

    // 🚀 [Fix] If weekly privilege is still pending, restore has_pending_changes with its effective time
    if (hasWeeklyPending) {
        val weeklyEffTime = CreditManager.getPendingWeeklyPrivilegeEffectiveTime(context)
        pendingPrefs.edit()
            .putBoolean("has_pending_changes", true)
            .putLong("effective_timestamp", weeklyEffTime)
            .apply()
        android.util.Log.i("TimeRuleManager", "✅ Applied daily pending rules. Weekly privilege still pending (effective: $weeklyEffTime).")
    }
    invalidateSegmentsCache()
    android.util.Log.i("TimeRuleManager", "✅ Applied all Pending Rules (Time & Global).")
    // 📝 规则变更 INFO
    val segmentsJson = activePrefs.getString(KEY_SEGMENTS_JSON, null)
    val segmentCount = if (segmentsJson != null) {
        try { org.json.JSONArray(segmentsJson).length() } catch (e: Exception) { 0 }
    } else 0
    val segNames = if (segmentsJson != null) {
        try {
            val arr = org.json.JSONArray(segmentsJson)
            (0 until arr.length()).map { arr.getJSONObject(it).optString("name", "?") }.joinToString("/")
        } catch (e: Exception) { "?" }
    } else "?"
    val coolDown = activePrefs.getInt("cool_down_seconds", 60)
    val coolDownRest = activePrefs.getInt("cool_down_rest_seconds", 60)
    val coolDownGap = activePrefs.getInt("cool_down_gap_seconds", 60)
    LogExporter.i("Config", "📝 规则变更 | type=勿扰时段 | segments=${segmentCount}段($segNames) | coolDown=${coolDown}s | coolDownRest=${coolDownRest}s | coolDownGap=${coolDownGap}s | effective=立即")
    }

    // ==========================================
    // 💡 必须的辅助函数 (CoreService 依赖这些)
    // ==========================================

    fun getCurrentSegment(context: Context): TimeSegment? {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val segments = getTimeSegments(context)
        val segment = segments.find { it.isInSegment(h, m) }
        
        // 🚀 [Weekly Privilege] If current segment is privileged, treat as "Free Phase"
        if (segment != null && CreditManager.isCurrentSegmentPrivileged(context)) {
            return null
        }
        
        // 🚀 [Unlimited = Free Phase] Only when: quota > segment duration
        // Note: quota > duration*50% is a DIFFERENT rule (no surplus carry-over, but still monitored)
        if (segment != null) {
            val durationMins = segment.getDurationMinutes()
            if (segment.limitMins > durationMins) {
                return null // Free phase, no monitoring
            }
        }
        
        return segment
    }

    fun getCurrentSegmentIndex(context: Context): Int {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val segments = getTimeSegments(context)
        return segments.indexOfFirst { it.isInSegment(h, m) }
    }

    // ⭐ 补全: CoreService 依赖的方法
    fun getNextSegment(context: Context): TimeSegment? {
        val segments = getTimeSegments(context)
        if (segments.isEmpty()) return null
        
        val cal = Calendar.getInstance()
        val currentH = cal.get(Calendar.HOUR_OF_DAY)
        val currentM = cal.get(Calendar.MINUTE)
        val currentTotal = currentH * 60 + currentM
        
        var nextSeg: TimeSegment? = null
        var minDiff = Int.MAX_VALUE
        
        // Find closest future segment today
        for (seg in segments) {
            if (!seg.isActiveToday()) continue

            val startTotal = seg.startHour * 60 + seg.startMinute
            if (startTotal > currentTotal) {
                val diff = startTotal - currentTotal
                if (diff < minDiff) {
                    minDiff = diff
                    nextSeg = seg
                }
            }
        }
        
        // If found one today, return it
        if (nextSeg != null) return nextSeg
        
        // If not found today, return the earliest ACTIVE segment of the day (Effective Tomorrow)
        // Find active segment with minimal start time
        var firstSeg: TimeSegment? = null
        var minStart = Int.MAX_VALUE
        for (seg in segments) {
            // Note: Strictly we should check "isActiveTomorrow", but assuming schedule is consistent/daily for now.
            // If we really want to be precise, skipping check here is safer (assume it appears tomorrow).
            // But if it repeats "Wed only" and today is Wed, tomorrow it's gone.
            // For now, let's assume if it is in the list, it repeats or we just show it.
            // But to fix the "Inactive showing up" bug, let's respect isActiveToday or just pick first.
            // Let's check isActiveToday for consistency with current day view.
             if (!seg.isActiveToday()) continue

            val start = seg.startHour * 60 + seg.startMinute
            if (start < minStart) {
                minStart = start
                firstSeg = seg
            }
        }
        return firstSeg
    }

    /**
     * 🚀 [New] Helper to get index of Next Segment
     */
    fun getNextSegmentIndex(context: Context): Int {
        val nextSeg = getNextSegment(context) ?: return -1
        val segments = getTimeSegments(context)
        // Match by Start Time & End Time to enable precise targeting even if names duplicate
        return segments.indexOfFirst { 
            it.startHour == nextSeg.startHour && it.startMinute == nextSeg.startMinute &&
            it.endHour == nextSeg.endHour && it.endMinute == nextSeg.endMinute
        }
    }

    fun getPreviousSegment(context: Context): TimeSegment? {
        val segments = getTimeSegments(context)
        if (segments.isEmpty()) return null
        
        val cal = Calendar.getInstance()
        val currentH = cal.get(Calendar.HOUR_OF_DAY)
        val currentM = cal.get(Calendar.MINUTE)
        val currentTotal = currentH * 60 + currentM
        
        var prevSeg: TimeSegment? = null
        var maxEnd = -1
        
        for (seg in segments) {
            if (!seg.isActiveToday()) continue

            var endTotal = seg.endHour * 60 + seg.endMinute
            // Handle cross-midnight interpretation for 'previous'?
            // If segment is 23:00-02:00. End is 02:00 (120).
            // If current is 03:00.
            // EndTotal 120 <= 180. Match.
            // If EndTotal < StartTotal (Cross Day), actual End Time is +24h.
            // But "Effective Date" logic usually splits them. 
            // Here `endTotal` is raw.
            // If 23:00-02:00. End=2. Start=23.
            // If we are at 03:00.
            // logic: End(2) <= Current(3). Found.
            
            // What if we are at 23:30?
            // End(2) <= 23:30? Yes.
            // But this segment STARTED at 23:00. It is CURRENT.
            // getCurrentSegment would catch it.
            // getPreviousSegment is for GAP.
            // If we are in Gap, currentSeg is null.
            
            if (endTotal <= currentTotal) {
                if (endTotal > maxEnd) {
                    maxEnd = endTotal
                    prevSeg = seg
                }
            }
        }
        return prevSeg
    }




    /**
     * 🚀 [Helper] 根据时间戳获取对应的时段
     * 用于 MonitorService 计算早退奖励
     */
    fun getSegmentAt(context: Context, timestamp: Long): TimeSegment? {
        val segments = getTimeSegments(context)
        if (segments.isEmpty()) return null
        
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        // Map to 1-7 = Monday-Sunday
        val dayIdx = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1
        
        return segments.find { seg ->
            seg.isEnabled &&
            seg.isInTimeRange(h, m) &&
            (seg.daysOfWeek?.contains(dayIdx) != false)
        }
    }

    /**
     * 🚀 [Helper] 标记首次设置完成（适应期结束）
     * 用于 HomeFragment 结束适应期
     */
    fun markFirstSetupDone(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 将 install_time 设置为超过24小时前，结束适应期
        prefs.edit().putLong("install_time", System.currentTimeMillis() - 25 * 3600 * 1000L).apply()
        
        // 保存基准快照
        saveBaselineSnapshot(context)
        saveBaselineParams(context)
        
        android.util.Log.i("TimeRuleManager", "First Setup Done. Baseline Snapshot Saved.")
    }

    /**
     * 🚀 [Helper] 清空缓存（规则变更后调用）
     */
    fun invalidateCache() {
        invalidateSegmentsCache()
    }



    /**
     * 获取逻辑上的第一个时段索引
     * 逻辑第一个时段 = 业务日开始的时段（即开始时间最早的时段）
     *
     * @param context 上下文
     * @return 逻辑第一个时段的索引
     */
    /**
     * 判断指定索引是否为逻辑上的最后一个时段（即业务日结束前的最后一个时段）
     * 逻辑最后一个时段 = 逻辑第一个时段索引 - 1（处理循环）
     */
    fun isLastLogicalSegment(context: Context, index: Int): Boolean {
        val segments = getTimeSegments(context)
        if (segments.isEmpty()) return false
        if (index < 0 || index >= segments.size) return false

        val firstLogicalIndex = getLogicalFirstSegmentIndex(context)
        // 逻辑最后一个时段索引 = (逻辑第一个时段索引 - 1 + size) % size
        val lastLogicalIndex = (firstLogicalIndex - 1 + segments.size) % segments.size
        return index == lastLogicalIndex
    }

    /**
     * 获取逻辑上的第一个时段索引
     * 逻辑第一个时段 = 业务日开始的时段（即开始时间最早的时段）
     */
    fun getLogicalFirstSegmentIndex(context: Context): Int {
        val segments = getTimeSegments(context)
        if (segments.isEmpty()) return 0

        // 🚀 [Rule] Logical First = Earliest Start Time
        // Morning segments come first; cross-midnight segments naturally sort to the end.
        var minStartDetails = Int.MAX_VALUE
        var minIndex = 0
        
        for (i in segments.indices) {
            val seg = segments[i]
            val startTotal = seg.startHour * 60 + seg.startMinute
            if (startTotal < minStartDetails) {
                minStartDetails = startTotal
                minIndex = i
            }
        }
        return minIndex


    }

    /**
     * 🚀 [New] Calculate total segment capacity (duration) for a specific day.
     * Takes into account which segments are active on that day of the week.
     */
    fun getDailyCapacity(context: Context, calendar: java.util.Calendar, segments: List<TimeSegment>): Int {
        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        return segments.filter { it.isActiveToday(checkDayInput = dayOfWeek) }.sumBy { calculateDuration(it) }
    }

    /**
     * 获取第一个时段的开始小时数
     *
     * @param context 上下文
     * @return 第一个时段的开始小时数（0-23）
     */
    fun getFirstSegmentStartHour(context: Context): Int {
        val segments = getTimeSegments(context)
        if (segments.isEmpty()) return 0

        // 找到开始时间最早的时段的开始小时
        var minStartTotal = Int.MAX_VALUE
        var startHour = 0

        for (seg in segments) {
            val startTotal = seg.startHour * 60 + seg.startMinute
            if (startTotal < minStartTotal) {
                minStartTotal = startTotal
                startHour = seg.startHour
            }
        }

        return startHour
    }

    fun getCurrentSegmentEndTimeInMillis(context: Context): Long {
        val segment = getCurrentSegment(context) ?: return 0L
        val cal = Calendar.getInstance()
        val currentH = cal.get(Calendar.HOUR_OF_DAY)

        val endCal = Calendar.getInstance()
        endCal.set(Calendar.HOUR_OF_DAY, segment.endHour)
        endCal.set(Calendar.MINUTE, segment.endMinute)
        endCal.set(Calendar.SECOND, 0)
        endCal.set(Calendar.MILLISECOND, 0)

        if (segment.startHour > segment.endHour) {
            if (currentH >= segment.startHour) {
                endCal.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            if (endCal.timeInMillis < System.currentTimeMillis()
                - 60000) {
                endCal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return endCal.timeInMillis
    }

    // 🚀 [NEW] Centralized Refresh Time Access
    fun getRefreshTime(context: Context): String {
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("config_refresh_time", "08:00") ?: "08:00"
    }

    /**
     * WP-03-T02: Parse "HH:MM" to total minutes-of-day.
     *
     * Returns null if the input does not match the "HH:MM" format with
     * HH ∈ [0, 23] and MM ∈ [0, 59] (24:00 = 1440 is also accepted as a
     * minute-of-day upper bound for compatibility with `MinuteBoundary`).
     */
    internal fun parseHHMMToMinutes(time: String?): Int? {
        if (time.isNullOrBlank()) return null
        val parts = time.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h < 0 || h > 24) return null
        if (m < 0 || m > 59) return null
        val total = h * 60 + m
        if (total > 24 * 60) return null
        return total
    }

    fun saveRefreshTime(context: Context, time: String) {
        saveRefreshTimeValidated(context, time, null)
    }

    /**
     * 与 [saveRefreshTime] 相同，但可指定"用哪套时段做 DA-D2 合法性校验"，并返回是否真正写入。
     *
     * 勿扰时段保存时，同步推导出的新刷新时刻必须对照**即将生效的新时段**校验：
     * 此刻生效中的旧时段还在，新刷新时刻（如 07:15）会落在旧时段（…–07:30）内部，
     * 对照旧时段会被静默拒绝，而新时段要等到该刷新时刻才会生效——形成循环，
     * 表现为「界面提示已同步成 07:15，实际与待生效表仍是 07:30」。
     *
     * @return true = 已写入（或与原值相同）；false = 被入口守卫拒绝，未改动任何状态。
     */
    internal fun saveRefreshTimeValidated(
        context: Context,
        time: String,
        guardSegments: List<TimeSegment>?
    ): Boolean {
        // WP-03-T02 DA-D2 entry guard: refuse to write if the candidate
        // refresh time is malformed, or strictly inside any currently
        // configured DND segment. Atomicity contract: on REJECT, this
        // method MUST NOT touch any SharedPreferences key (refresh time,
        // pending pref sets, change-log entries, sync switches, or
        // timestamps). The caller can read `getRefreshTime` to discover
        // that no update was applied.
        val candidateMinutes = parseHHMMToMinutes(time)
        if (candidateMinutes == null) {
            android.util.Log.w(
                "TimeRuleManager",
                "saveRefreshTime rejected: malformed candidate time='$time'"
            )
            return false
        }
        val currentSegments = guardSegments ?: getActiveSegmentsForGuard(context)
        val verdict = MinuteBoundary.isRefreshLegal(candidateMinutes, currentSegments)
        if (verdict is BoundaryResult.Rejected) {
            android.util.Log.w(
                "TimeRuleManager",
                "saveRefreshTime rejected: candidate=${time} " +
                    "(${candidateMinutes}m) reason=${verdict.reason}"
            )
            return false
        }
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("config_refresh_time", time).apply()
        return true
    }

    /**
     * WP-03-T02: read the currently active time segments for entry-guard
     * validation. Consults BOTH `decikeeper_rules` (production write target)
     * and `CreditManager.PREFS_NAME` (legacy/GlobalParams sidecar that also
     * hosts segment data in some configurations) so the guard sees what
     * the caller sees regardless of which pref set the caller wrote to.
     */
    private fun getActiveSegmentsForGuard(context: Context): List<TimeSegment> {
        val fromRules = getTimeSegments(context)
        if (fromRules.isNotEmpty()) return fromRules
        val fromLedger = context.getSharedPreferences(
            CreditManager.PREFS_NAME, Context.MODE_PRIVATE
        ).getString(KEY_SEGMENTS_JSON, null)?.let {
            parseSegmentsFromJson(it)
        } ?: emptyList()
        return fromLedger
    }

    /**
     * 🚀 [NEW] Settings Lockout Period Check
     * Settings are no longer locked at night; this is kept for compatibility.
     */
    fun isInSettingsLockoutPeriod(context: Context): Boolean {
        return false
    }

    /**
     * 判定当前时间是否处于时间规则与参数禁止修改时段
     */
    fun isTimeRuleEditLocked(context: Context, nowCalendar: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        return TimeRuleEditWindowPolicyManager.isTimeRuleEditLocked(context, nowCalendar)
    }

    /**
     * 🚀 [New] Time Segments Analysis & Save
     */
    internal fun markNewSegments(
        inputSegments: List<TimeSegment>,
        beforeSegments: List<TimeSegment>,
        changeTimestamp: Long
    ): List<TimeSegment> {
        return inputSegments
            .sortedBy { it.startHour * 60 + it.startMinute }
            .map { candidate ->
                val oldMatch = SegmentAdverseAnalysis.findMatchingOldSegment(candidate, beforeSegments)
                if (oldMatch == null) {
                    candidate.copy(lastModified = if (candidate.lastModified > 0L) candidate.lastModified else changeTimestamp)
                } else if (candidate.lastModified <= 0L) {
                    candidate.copy(lastModified = oldMatch.lastModified)
                } else {
                    candidate
                }
            }
    }

    fun analyzeTimeSegments(context: Context, inputSegments: List<TimeSegment>): AnalysisResult =
        analyzeAndSaveTimeSegments(context, inputSegments, dryRun = true)

    fun analyzeAndSaveTimeSegments(
        context: Context,
        inputSegments: List<TimeSegment>,
        dryRun: Boolean = false
    ): AnalysisResult {
        // WP-03-T02 DA-D1 entry guard: refuse to save if any candidate
        // segment strictly contains the configured refresh time (`start <
        // refresh < end`). Cross-midnight segments (`end <= start`) are
        // themselves legal per v3 SPEC §3.5 A footnote and are forwarded
        // to the existing production path. Malformed out-of-range values
        // also reject. Atomicity contract: on REJECT, this method MUST
        // NOT touch any SharedPreferences (active segments, pending
        // segments, refresh time, sync state, change-log entries, or
        // timestamps). Returns `AnalysisResult.NoChange` so callers do
        // not show a spurious confirmation dialog when validation
        // refuses the save.
        val curRefreshText = getRefreshTime(context)
        val curRefreshMinutes = parseHHMMToMinutes(curRefreshText)
        if (curRefreshMinutes != null) {
            for (seg in inputSegments) {
                val startMin = seg.startHour * 60 + seg.startMinute
                val endMin = seg.endHour * 60 + seg.endMinute
                // Skip cross-midnight segments (end <= start); v3 SPEC
                // §3.5 A footnote declares them legal.
                val isCrossMidnightOrZeroLength = endMin <= startMin
                if (isCrossMidnightOrZeroLength) continue
                val verdict = MinuteBoundary.isSegmentLegal(startMin, endMin, curRefreshMinutes)
                if (verdict is BoundaryResult.Rejected) {
                    android.util.Log.w(
                        "TimeRuleManager",
                        "analyzeAndSaveTimeSegments rejected: " +
                            "segment=[${seg.startHour}:${seg.startMinute}-" +
                            "${seg.endHour}:${seg.endMinute}] " +
                            "name='${seg.name}' reason=${verdict.reason}"
                    )
                    return AnalysisResult.NoChange
                }
            }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Change Log Prep
        val beforeSegments = getTimeSegments(context)
        val segmentChangeTimestamp = System.currentTimeMillis()

        // A newly created/imported segment must not retroactively generate surplus
        // for an occurrence that had already started before it existed.
        val newSegments = markNewSegments(inputSegments, beforeSegments, segmentChangeTimestamp)
        val coolDown = getCoolDownSeconds(context)
        val notify = getNotifyAdvanceMinutes(context)
        val borrow = getBorrowPercentage(context)
        val beforeParams = FiveParams(coolDown, notify, borrow, 0)
        // Params don't change here
        val afterParams = beforeParams

        // 🚀 [Fix] Get Baseline Segments for Adverse Check
        // 🚀 [Fix] Get Baseline Segments for Adverse Check
        // 🚀 [Fix] Get Baseline Segments for Adverse Check
        val basSegments = getBaselineSegments(context)
        
        // 🚀 [Auto Sync] Refresh Time Logic
        // 规则：扫描启用时段，取结束时间在 04:00-12:00 的最早者；无匹配则默认 08:00
        val curRefresh = getRefreshTime(context)
        val basPrefs = context.getSharedPreferences(PREFS_BASELINE, Context.MODE_PRIVATE)
        val basRefresh = basPrefs.getString("config_refresh_time", "08:00") ?: "08:00"

        var targetRefresh = curRefresh
        var isRefreshSynced = isRefreshTimeSynced(context)

        if (isRefreshSynced) {
            val derivedRefresh = findRefreshTimeFromSegments(newSegments)
            val oldDerivedRefresh = findRefreshTimeFromSegments(beforeSegments)

            if (derivedRefresh != oldDerivedRefresh) {
                targetRefresh = derivedRefresh
            }
        }
        
        val isRefreshChange = targetRefresh != curRefresh
        // 🚀 [Fix] Refresh Time Logic: Always Benign/Immediate (User Rule)
        // User requested: "生效时间怎么改都是有利改动".
        // So we decouple it from "Adverse vs Baseline" logic for Segments.
        // It is handled as an independent Immediate update.
        val isFirstSegAdverse = false // Ignore this linkage
        val isRefreshAdverse = false  // Always False
        
        // Check for Restoration (Cosmetic log difference)
        val isRefreshRestored = isRefreshChange && (targetRefresh == basRefresh)
        val isRefreshBenign = isRefreshChange && !isRefreshRestored
        
        // 🚀 Adaptation Period: Always Immediate
        if (isInAdaptationPeriod(context)) {
            if (dryRun) return AnalysisResult.AllImmediate()
            saveTimeSegments(context, newSegments)
            if (isRefreshChange) saveRefreshTime(context, targetRefresh)
            
            context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit().clear().apply()
            
            // Log
            addChangeLog(context, "immediate", beforeSegments, newSegments, beforeParams, afterParams)
            if (isRefreshChange) {
                 val changeType = if (isRefreshAdverse) " (不利/适应期)" else " (有利)"
                 val msg = "生效时间: $curRefresh -> $targetRefresh$changeType"
                 ChangeLogManager.addEntries(context, "global_params", listOf(Triple(msg, false, null)))
            }
            
            // Sync Service
            val intent = android.content.Intent(context, MonitorService::class.java)
            intent.action = MonitorService.ACTION_REFRESH_RULES
            context.startService(intent)
            
            return AnalysisResult.AllImmediate()
        }

        val forceAllNextDay =
            EffectiveActivationPolicyManager.getPolicy(context) == EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY

        if (forceAllNextDay) {
            val pendingSegs = getPendingTimeSegments(context)
            val activeJson = segmentsToJson(beforeSegments)
            val newJson = segmentsToJson(newSegments)
            val pendingJson = pendingSegs?.let { segmentsToJson(it) }

            // If user clicks save without any edits while pending already matches draft,
            // treat it as true no-op and avoid replaying historical diff messages.
            if (pendingJson != null && pendingJson == newJson) {
                return AnalysisResult.NoChange
            }

            if (activeJson == newJson) {
                if (pendingJson != null && pendingJson != newJson) {
                    if (dryRun) return AnalysisResult.AllImmediate(listOf("✅ 已撤销待生效的勿扰时段变更。"))
                    ChangeLogManager.removePendingEntriesByCategory(context, "segments")
                    val pendingPrefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
                    val editor = pendingPrefs.edit()
                    editor.remove(KEY_SEGMENTS_JSON)
                    editor.remove("config_refresh_time")
                    editor.remove(KEY_PRIVILEGE_WINDOW_START)
                    editor.remove(KEY_PRIVILEGE_WINDOW_END)
                    val hasOtherPending = pendingPrefs.all.keys.any {
                        it != KEY_SEGMENTS_JSON &&
                            it != "config_refresh_time" &&
                            it != KEY_PRIVILEGE_WINDOW_START &&
                            it != KEY_PRIVILEGE_WINDOW_END &&
                            it != "has_pending_changes" &&
                            it != "effective_timestamp"
                    }
                    if (!hasOtherPending) editor.clear()
                    else editor.putBoolean("has_pending_changes", false).remove("effective_timestamp")
                    editor.apply()
                    return AnalysisResult.AllImmediate(listOf("✅ 已撤销待生效的勿扰时段变更。"))
                }
                return AnalysisResult.NoChange
            }

            // In ALL_NEXT_DAY mode, dialog should show only this save's delta.
            // If pending exists, compare against pending; otherwise compare against active.
            val diffBase = pendingSegs ?: beforeSegments
            val delayedMessages = SegmentAdverseAnalysis.getPendingDiffString(context, diffBase, newSegments, basSegments).toMutableList()
            if (delayedMessages.isEmpty()) {
                delayedMessages.add("勿扰时段已更新（按当前策略统一次日生效）")
            }

            val effectiveTime = getEffectiveTimestamp(context)
            if (isRefreshChange) {
                delayedMessages.add("生效时间: $curRefresh -> $targetRefresh (统一次日生效)")
            }

            if (dryRun) {
                return AnalysisResult.Mixed(
                    immediateChanges = emptyList(),
                    delayedChanges = delayedMessages,
                    restoredChanges = emptyList(),
                    nextWeekChanges = emptyList(),
                    effectiveTime = effectiveTime,
                    nextWeekEffectiveTime = 0L
                )
            }

            val pendingEdit = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit()
            pendingEdit.putString(KEY_SEGMENTS_JSON, newJson)
            pendingEdit.putBoolean("has_pending_changes", true)
            pendingEdit.putLong("effective_timestamp", effectiveTime)
            if (isRefreshChange) {
                pendingEdit.putString("config_refresh_time", targetRefresh)
            }
            pendingEdit.apply()

            ChangeLogManager.removePendingEntriesByCategory(context, "segments")
            val logItems = delayedMessages.mapIndexed { index, msg ->
                Triple(msg, true, "policy_all_next_day_$index")
            }
            ChangeLogManager.addEntries(context, "segments", logItems)

            return AnalysisResult.Mixed(
                immediateChanges = emptyList(),
                delayedChanges = delayedMessages,
                restoredChanges = emptyList(),
                nextWeekChanges = emptyList(),
                effectiveTime = effectiveTime,
                nextWeekEffectiveTime = 0L
            )
        }

        // 1. Check for Adverse Changes (Strictly vs Baseline)
        // 🚀 [Fix] User Requirement: "Adverse" works strictly relative to Baseline.
        // Comparison relative to "Current Active" (beforeSegments) is only to detect IF there is a change.
        // But the "Nature" of the change (Good/Bad) is solely determined by Baseline.
        val isAdverse = SegmentAdverseAnalysis.isAdverseRelativeToBaseline(context, newSegments) || isRefreshAdverse

        if (!isAdverse) {
            // Generate message for All Immediate
            val immList = mutableListOf<String>()
            // 🚀 [Fix] Use Baseline for comparison in Immediate Dialog too
            val diff = SegmentAdverseAnalysis.getPendingDiffString(context, beforeSegments, newSegments, basSegments)
            diff.forEach { immList.add(it) }

            // 🚀 [Fix] "Restored from Pending" Logic
            // If No Diff (Active == New) BUT Pending Segments exist, it is a Restoration (Cancelling Pending).
            if (diff.isEmpty()) {
                val pendingSegs = getPendingTimeSegments(context)
                if (pendingSegs != null && pendingSegs.isNotEmpty()) {
                    // Check if pending was actually different from Active
                    val pendingDiff = SegmentAdverseAnalysis.getPendingDiffString(context, beforeSegments, pendingSegs)
                    if (pendingDiff.isNotEmpty()) {
                         immList.add("✅ 已撤销待生效的变更，恢复为当前配置。")
                    }
                }
            }

            // 🚀 [Auto Sync] Handle Immediate Refresh Change
            if (isRefreshChange) {
                val suffix = if (isRefreshRestored) " [恢复]" else " (有利)"
                immList.add("生效时间: $curRefresh -> $targetRefresh$suffix")
            }
            

            if (dryRun) {
                return AnalysisResult.AllImmediate(immList)
            }

            // ✅ All Immediate Path (Benign or Restoration)
            saveTimeSegments(context, newSegments)

            if (isRefreshChange) {
                saveRefreshTime(context, targetRefresh)
                // Cleanup Pending
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "global_param_refresh_time")
                context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit().remove("config_refresh_time").apply()
            }
                // 🚀 [Fix] Partial Revert Support (Benign Path)
                newSegments.forEach { current ->
                    val base = basSegments?.find { baseSeg ->
                        (baseSeg.startHour == current.startHour && baseSeg.startMinute == current.startMinute) ||
                        (baseSeg.endHour == current.endHour && baseSeg.endMinute == current.endMinute)
                    }
                    if (base != null && SegmentAdverseAnalysis.isSingleSegmentEqual(current, base)) {
                        val baseTag = "seg_${current.startHour}:${current.startMinute}_${current.endHour}:${current.endMinute}_${current.name.hashCode()}"
                        ChangeLogManager.removePendingEntriesByTag(context, "segments", "${baseTag}_del")
                    }
                }
            
            // 🚀 [ChangeLog] Clean history for deleted segments (Benign Delete)
            val deletedSegments = beforeSegments.filter { old -> 
                newSegments.none { (it.startHour == old.startHour && it.startMinute == old.startMinute) || (it.endHour == old.endHour && it.endMinute == old.endMinute) }
            }
            deletedSegments.forEach { seg ->
                val rangeStr = "[${String.format("%02d:%02d", seg.startHour, seg.startMinute)}-${String.format("%02d:%02d", seg.endHour, seg.endMinute)}]"
                ChangeLogManager.removeEntriesContaining(context, "segments", rangeStr)
            }

            // 1. Clear Segment keys from pending (preserving Global Params)
            // 🚀 [Fix] Restoration: Also clear entries from Change Log to remove "Zombies"
            ChangeLogManager.removePendingEntriesByCategory(context, "segments")

            val pendingPrefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
            val editor = pendingPrefs.edit()
            editor.remove(KEY_SEGMENTS_JSON)
            
            val allKeys = pendingPrefs.all.keys
            val hasOtherPending = allKeys.any { it != KEY_SEGMENTS_JSON && it != "has_pending_changes" && it != "effective_timestamp" }
            if (!hasOtherPending) editor.clear()
            editor.apply()
            
            // Sync Service
            val intent = android.content.Intent(context, MonitorService::class.java)
            intent.action = MonitorService.ACTION_REFRESH_RULES
            context.startService(intent)
            
            // Log Favorable
            if (immList.isNotEmpty()) {
                val items = immList.map { Triple(it, false, null as String?) }
                ChangeLogManager.addEntries(context, "segments", items)
            }
            
            return AnalysisResult.AllImmediate(immList)
        } else {
            // Adverse (Delayed Mixed)
            
            // 🚀 [Incremental Diff] Get previous pending state to filter redundant messages
            val prevPendingSegments = getPendingTimeSegments(context) ?: beforeSegments

            val hybridSegments = mutableListOf<TimeSegment>()
            val immList = mutableListOf<String>() 
            val resList = mutableListOf<String>() 
            val delList = mutableListOf<String>() 
            val logItems = mutableListOf<Triple<String, Boolean, String?>>() // 🚀 Items with Tags
            
            // 🚀 [Fix] Merge Logic: Clear ALL existing pending segment logs before regenerating
            // Since we are rewriting the full pending segments JSON, the new logs will represent the COMPLETE pending state.
            if (!dryRun) {
                ChangeLogManager.removePendingEntriesByCategory(context, "segments")
            }
            
            val unmatchedOld = beforeSegments.toMutableList()
            
            for (newSeg in newSegments) {
                 val oldSegRaw = SegmentAdverseAnalysis.findMatchingOldSegment(newSeg, beforeSegments)
                 val oldSeg = if (oldSegRaw != null && SegmentAdverseAnalysis.isDaysOverlapping(oldSegRaw.daysOfWeek, newSeg.daysOfWeek)) {
                     oldSegRaw
                 } else {
                     null
                 }
                 val baseSegRaw = if (basSegments != null) SegmentAdverseAnalysis.findMatchingOldSegment(newSeg, basSegments) else null
                 val baseSeg = if (baseSegRaw != null && SegmentAdverseAnalysis.isDaysOverlapping(baseSegRaw.daysOfWeek, newSeg.daysOfWeek)) {
                     baseSegRaw
                 } else {
                     null
                 }
                 
                 if (oldSeg != null) unmatchedOld.remove(oldSeg)

                 // 🚀 [Optimization] Idempotency Check: If New == Active, skip checks (No Change)
                 if (oldSeg != null && SegmentAdverseAnalysis.isSingleSegmentEqual(oldSeg, newSeg)) {
                     hybridSegments.add(oldSeg)
                     
                     // 🚀 [Fix] Cleanup Logs on Restore: Remove pending logs if back to active state
                     val baseTag = "seg_${newSeg.startHour}:${newSeg.startMinute}_${newSeg.endHour}:${newSeg.endMinute}_${newSeg.name.hashCode()}"
                     ChangeLogManager.removePendingEntriesByTag(context, "segments", "${baseTag}_del")
                     ChangeLogManager.removePendingEntriesByTag(context, "segments", "${baseTag}_imm")
                     ChangeLogManager.removePendingEntriesByTag(context, "segments", "${baseTag}_new")
                     
                     // 🚀 [Fix] Restoration Dialog: If it WAS pending different, show "Restored" msg
                     val pendingMatch = SegmentAdverseAnalysis.findMatchingOldSegment(newSeg, prevPendingSegments)
                     if (pendingMatch != null && !SegmentAdverseAnalysis.isSingleSegmentEqual(pendingMatch, newSeg)) {
                         val msg = "恢复: [${newSeg.formatTimeRange()}] ${newSeg.name}"
                         resList.add(msg)
                     } else if (pendingMatch == null) {
                         // Was deleted in Pending (assuming prevPendingSegments is valid list and not just beforeSegments)
                         // If it defaulted to beforeSegments, match would be found. So null means it was missing from Pending list.
                         val msg = "恢复: [${newSeg.formatTimeRange()}] ${newSeg.name}"
                         resList.add(msg)
                     }
                     
                     continue
                 }
                 
                 // 🚀 [Fix v2] Restoration to Baseline Check: If New == Baseline (but != Active), treat as Restore
                 // 场景:用户先改严格(5→4),然后又改回来(4→5)
                 // 此时 Active=4, Baseline=5, New=5
                 // newSeg ≠ Active 但 newSeg == Baseline,应该视为"恢复"
                 if (baseSeg != null && SegmentAdverseAnalysis.isSingleSegmentEqual(baseSeg, newSeg)) {
                     // 恢复到法定基准 → 立即更新 Active,清除日志
                     hybridSegments.add(newSeg) // 使用新值(= Baseline)
                     
                     val baseTag = "seg_${newSeg.startHour}:${newSeg.startMinute}_${newSeg.endHour}:${newSeg.endMinute}_${newSeg.name.hashCode()}"
                     ChangeLogManager.removePendingEntriesByTag(context, "segments", "${baseTag}_del")
                     ChangeLogManager.removePendingEntriesByTag(context, "segments", "${baseTag}_imm")
                     ChangeLogManager.removePendingEntriesByTag(context, "segments", "${baseTag}_new")
                     
                     // 显示恢复信息
                     val msg = "恢复: [${newSeg.formatTimeRange()}] ${newSeg.name} (恢复到基准值)"
                     resList.add(msg)
                     
                     continue
                 }

                 // 🚀 [Rule] If segment is NEW, any change relative to "non-existence" is Favorable.
                 val analysis = if (baseSeg == null) SegmentAnalysis() else SegmentAdverseAnalysis.analyzeSingleSegmentChange(baseSeg, newSeg, context)
                 
                 val effectiveBas = basSegments ?: beforeSegments
                 val (immediateDaysForNewSeg, delayedDaysForNewSeg) = if (baseSeg == null) {
                     splitSegmentDaysForMixedActivation(newSeg, effectiveBas)
                 } else {
                     emptySet<Int>() to emptySet()
                 }
                 val isConflict = (baseSeg == null) && delayedDaysForNewSeg.isNotEmpty()
                 val hasMixedDaysActivation = isConflict && immediateDaysForNewSeg.isNotEmpty()
                 // [Fix] Composite Tag to prevent collision
                 val baseTag = "seg_${newSeg.startHour}:${newSeg.startMinute}_${newSeg.endHour}:${newSeg.endMinute}_${newSeg.name.hashCode()}"

                  // 🚀 [Granular Refactor] Analyze each diff item individually
                                                      val items = SegmentAdverseAnalysis.getCategorizedSegmentDiff(context, oldSeg, newSeg, baseSeg)
                  
                  if (items.isNotEmpty()) {
                      val op = if (oldSeg == null) "新增" else "修改"
                      val header = "[$op] [${newSeg.name}] [${newSeg.formatTimeRange()}]"
                      
                      // 🚀 [Optimization] Incremental Check: Is this change identical to what we already Pending-ed?
                      var isIncrementalNoChange = false
                      val pendingMatchRaw = if (prevPendingSegments != null) SegmentAdverseAnalysis.findMatchingOldSegment(newSeg, prevPendingSegments) else null
                      val pendingMatch = if (pendingMatchRaw != null && SegmentAdverseAnalysis.isDaysOverlapping(pendingMatchRaw.daysOfWeek, newSeg.daysOfWeek)) {
                          pendingMatchRaw
                      } else {
                          null
                      }
                      if (pendingMatch != null && SegmentAdverseAnalysis.isSingleSegmentEqual(pendingMatch, newSeg)) {
                          isIncrementalNoChange = true
                      }

                      if (hasMixedDaysActivation) {
                          val immediateSegment = newSeg.copy(daysOfWeek = toDaysListOrNull(immediateDaysForNewSeg))
                          val immediateDaysText = formatDaysVerbose(immediateSegment.daysOfWeek)
                          val delayedDaysText = formatDaysVerbose(toDaysListOrNull(delayedDaysForNewSeg))

                          val immMsg = "$header (部分日期立即生效)\n  • 生效日期: $immediateDaysText"
                          val delMsg = "$header (重叠日期延迟生效)\n  • 生效日期: $delayedDaysText"

                          if (!isIncrementalNoChange) {
                              immList.add(immMsg)
                              delList.add(delMsg)
                          }

                          logItems.add(Triple(immMsg, false, "${baseTag}_imm_days"))
                          logItems.add(Triple(delMsg, true, "${baseTag}_del_days"))

                          // Immediate path keeps only non-overlapping days active today.
                          hybridSegments.add(immediateSegment)
                          continue
                      }
                      
                      // 🚀 [Fix] Conflict Handling: If Conflict, ALL items are Adverse/Delayed
                      val immItems = if (isConflict) emptyList() else items.filter { !it.isAdverse }
                      val delItems = if (isConflict) items else items.filter { it.isAdverse }
                      
                      if (immItems.isNotEmpty()) {
                          // Clean message with details
                          val msg = "$header\n" + immItems.joinToString("\n") { it.message }
                          // 🚀 Dialog Filter: Only add if NEW change
                          if (!isIncrementalNoChange) {
                              if (immItems.all { it.isRestored }) resList.add(msg) else immList.add(msg)
                          }
                          // 🚀 [Fix] Change Log Consolidation: Add one entry with full details (Header + Items)
                          logItems.add(Triple(msg, false, "${baseTag}_imm"))
                      }
                      
                      if (delItems.isNotEmpty()) {
                          // For Conflict: Add specific tag
                          val suffix = if (isConflict) " (重叠 - 延迟生效)" else ""
                          
                          // 🚀 [Critial Fix] Ensure details are attached and NO phantom suffix "(不利)" is manually added here.
                          val msg = "$header$suffix\n" + delItems.joinToString("\n") { it.message }
                          
                          // 🚀 Dialog Filter: Only add if NEW change
                          if (!isIncrementalNoChange) {
                              delList.add(msg)
                          }
                          val tagSuffix = if (isConflict) "_new" else "_del"
                          // 🚀 [Fix] Change Log Consolidation: Add one entry with full details
                          logItems.add(Triple(msg, true, "${baseTag}$tagSuffix"))
                      }
                      
                      // 🚀 [Fix] Conflict Leak: If Conflict, DO NOT add to Active list (hybridSegments)
                      // Only add if NO CONFLICT.
                      if (!isConflict) {
                          val hybrid = if (baseSeg != null) SegmentAdverseAnalysis.createHybridSegment(baseSeg, newSeg, analysis) else newSeg
                          hybridSegments.add(hybrid)
                      } else {
                          // If conflict, we default to OLD segment (if exists) or nothing (if new).
                          // If oldSeg exists, we keep it active.
                          if (oldSeg != null) hybridSegments.add(oldSeg)
                      }
                  } else {
                      // No items = No Change relative to Active/Baseline
                      hybridSegments.add(oldSeg ?: newSeg)
                  }
            }
            
            for (oldSeg in unmatchedOld) {
                // 🚀 [Incremental Check] Was it already deleted pending?
                // It is "deleted" if it does NOT exist in prevPendingSegments?
                // Match by ID (Start Time + Active Time Range).
                val wasInPending = prevPendingSegments.any {
                    it.startHour == oldSeg.startHour && it.startMinute == oldSeg.startMinute &&
                        it.endHour == oldSeg.endHour && it.endMinute == oldSeg.endMinute &&
                        SegmentAdverseAnalysis.isDaysOverlapping(it.daysOfWeek, oldSeg.daysOfWeek)
                }
                val alreadyDeleted = !wasInPending
                
                hybridSegments.add(oldSeg) // Keep restriction
                
                if (!alreadyDeleted) {
                    val msg = "[删除] [${oldSeg.name}] [${oldSeg.formatTimeRange()}] (不利)"
                    delList.add(msg)
                    val baseTag = "seg_${oldSeg.startHour}:${oldSeg.startMinute}_${oldSeg.endHour}:${oldSeg.endMinute}_${oldSeg.name.hashCode()}"
                    logItems.add(Triple(msg, true, "${baseTag}_del"))
                }
            }
            
            // 🚀 [Auto Sync] Handle Mixed Refresh Change
            if (isRefreshChange) {
                val refreshTag = "global_param_refresh_time"
                if (isRefreshAdverse) {
                     val msg = "生效时间: $curRefresh -> $targetRefresh (不利)"
                     delList.add(msg)
                     logItems.add(Triple(msg, true, refreshTag))
                } else if (!dryRun && saveRefreshTimeValidated(context, targetRefresh, newSegments)) {
                     // 校验对照"即将生效的新时段"，避免被仍在生效的旧时段静默拒绝（详见 saveRefreshTimeValidated）。
                     // 同步刷新所有待生效项的生效时间戳，保证延迟生效表与实际生效时刻一致。
                     propagateRefreshTimeToPendingFiles(context)
                     val suffix = if (isRefreshRestored) " [恢复]" else " (有利)"
                     val msg = "生效时间: $curRefresh -> $targetRefresh$suffix"
                     if (isRefreshRestored) resList.add(msg)
                     else immList.add(msg)
                     logItems.add(Triple(msg, false, refreshTag))
                     
                     // Cleanup Pending
                     ChangeLogManager.removePendingEntriesByTag(context, "global_params", refreshTag)
                     context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit().remove("config_refresh_time").apply()
                } else if (dryRun) {
                     val suffix = if (isRefreshRestored) " [恢复]" else " (有利)"
                     val msg = "生效时间: $curRefresh -> $targetRefresh$suffix"
                     if (isRefreshRestored) resList.add(msg)
                     else immList.add(msg)
                } else {
                     android.util.Log.w("TimeRuleManager", "Mixed path: refresh sync $curRefresh -> $targetRefresh rejected by entry guard, keep $curRefresh")
                }
            }
            
            val effectiveTime = getEffectiveTimestamp(context)
            if (dryRun) {
                return AnalysisResult.Mixed(immList, delList, resList, emptyList(), effectiveTime, 0L)
            }

            saveTimeSegments(context, hybridSegments, syncRefreshFromSegments = false)

            // Stage Pending
            val pendingPrefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
            val pendingEdit = pendingPrefs.edit()
            
            pendingEdit.putString(KEY_SEGMENTS_JSON, segmentsToJson(newSegments))
            pendingEdit.putBoolean("has_pending_changes", true)
            pendingEdit.putLong("effective_timestamp", effectiveTime)
            
            if (isRefreshChange && isRefreshAdverse) {
                pendingEdit.putString("config_refresh_time", targetRefresh)
            }
            
            pendingEdit.apply()
                
            // Save Logs with Tags
            if (logItems.isNotEmpty()) ChangeLogManager.addEntries(context, "segments", logItems)
            
            return AnalysisResult.Mixed(immList, delList, resList, emptyList(), effectiveTime, 0L)
        }
    }

    /**
     * 🚀 [Helper] Check if we are past the daily refresh time (e.g. 06:00 or custom)
     * Used for Baseline Snapshot triggers.
     */
    fun isAfterRefreshTime(context: Context): Boolean {
        // Read Custom Refresh Time from CreditManager preferences (where it is stored)
        val prefs = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
        val refreshTimeStr = prefs.getString("config_refresh_time", "08:00") ?: "08:00"
        
        val parts = refreshTimeStr.split(":")
        val targetH = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val targetM = parts.getOrNull(1)?.toIntOrNull() ?: 0
        
        val cal = java.util.Calendar.getInstance()
        val now = System.currentTimeMillis()
        cal.timeInMillis = now
        
        val nowH = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val nowM = cal.get(java.util.Calendar.MINUTE)
        
        val nowTotal = nowH * 60 + nowM
        val targetTotal = targetH * 60 + targetM
        
        return nowTotal >= targetTotal
    }

    /**
     * 🚀 [Helper] Check and apply pending rules from ConfigPendingManager if they are due.
     * This bridges MonitorService -> ConfigPendingManager call.
     */
    fun checkAndApplyPendingRules(context: Context) {
        // 1. Time Segments
        if (ConfigPendingManager.isPendingEffective(context, KEY_SEGMENTS_JSON)) {
            val pendingJson = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
                .getString(KEY_SEGMENTS_JSON, null)
                
            if (pendingJson != null) {
                // Apply to Active
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_SEGMENTS_JSON, pendingJson).apply()
                getDeviceProtectedPrefs(context)?.edit()?.putString(KEY_SEGMENTS_JSON, pendingJson)?.apply()
                
                // Clear Pending
                ConfigPendingManager.clearPending(context, KEY_SEGMENTS_JSON)
                context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_SEGMENTS_JSON)
                    .remove("effective_time")
                    .putBoolean("has_pending_changes", false) // 🚀 Fix key name
                    .apply()
                    
                invalidateSegmentsCache()
                
                // 🚀 [Fix] Mark ChangeLogs as Applied so they disappear from "Pending" UI
                ChangeLogManager.markCategoryAsApplied(context, "segments")
                
                android.util.Log.i("TimeRuleManager", "✅ Applied Pending Time Segments.")
            }
        }
        
        // 2. Global Params (Refresh Time, etc.) - If stored in Pending
        // Example: "config_refresh_time"
        if (ConfigPendingManager.isPendingEffective(context, "config_refresh_time")) {
             val pendingVal = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).getString("config_refresh_time", null)
             if (pendingVal != null) {
                 saveGlobalParamsRefreshTime(context, pendingVal)
                 ConfigPendingManager.clearPending(context, "config_refresh_time")
                 context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit().remove("config_refresh_time").apply()
                 android.util.Log.i("TimeRuleManager", "✅ Applied Pending Refresh Time: $pendingVal")
             }
        }
    }
    
    // Helper to save refresh time
    //
    // WP-03-T03: route through the public `saveRefreshTime` entry guard so the
    // DA-D2 (refresh 切坏现有 DND 时段) policy cannot be bypassed by internal
    // callers that previously wrote `config_refresh_time` directly. On REJECT
    // the value is silently kept at its current active state (matching the
    // public entry's atomicity contract).
    private fun saveGlobalParamsRefreshTime(context: Context, timeStr: String) {
        saveRefreshTime(context, timeStr)
    }
    



    fun revokePendingChanges(context: Context) {
        val pendingPrefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        pendingPrefs.edit().clear().apply()
        android.util.Log.i("TimeRuleManager", "Revoked all pending global parameter changes.")
    }

    // --- Missing Types & Helpers ---
    
    data class FiveParams(
        val coolDown: Int,
        val notify: Int,
        val borrow: Int,
        val reserved: Int
    )

    private fun addChangeLog(
        context: Context,
        tag: String,
        oldSegs: List<TimeSegment>,
        newSegs: List<TimeSegment>,
        p1: FiveParams,
        p2: FiveParams
    ) {
         // Re-use diff logic
         // Note: p1/p2 (Global Params) logic is handled separately by ChangeLogManager calls usually.
         // This function mainly logs Segment changes for immediate/deferred events if not covered.
         // For "immediate" tag used in analyzeAndSave, it expects segment diffs.
         
         val diffs = SegmentAdverseAnalysis.getPendingDiffString(context, oldSegs, newSegs, null)
         if (diffs.isNotEmpty()) {
             // Convert string list to Triple
             val entries = diffs.map { Triple(it, false, null as String?) } // Not adverse, no tag
             ChangeLogManager.addEntries(context, "time_rules", entries)
         }
    }
}
