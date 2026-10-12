/**
 * =================================================================================
 * [Context Header]
 * * @file: PendingChangeTable.kt
 * * @role: Domain Skill
 * * @description: 「延迟生效表」：把勿扰时段、应用时间窗、规则参数、全局黑名单四处分散的待生效修改
 *   统一成逐项列表，并支持逐项撤销。
 * * @responsibilities:
 * 1. 以「待生效值 vs 生效中的值」的差异（而不是改动日志）为唯一依据枚举待生效项，
 *    宽严有别下的放宽项与统一延后（全部次日）下连加严也被延后的项都会出现。
 * 2. 逐项撤销：只把这一项改回生效中的值，其余待生效项原样保留；全部清空后整体收尾。
 * * @context:
 * - 上游: PendingChangesDialog（时间规则三页与黑名单页共用）
 * - 下游: TimeRuleManager, AppWindowManager, BlacklistStore, CreditManager, ChangeLogManager
 * * @constraints (必读):
 * 1. 不引入 UI 依赖。
 * 2. 撤销只会让规则回到「当前生效的值」，不会让任何放宽提前生效。
 * 3. 改动日志只作展示辅助，撤销时同步清掉对应记录，但枚举不依赖它。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.BlacklistStore
import com.decikeeper.app.data.SegmentAdverseAnalysis
import com.decikeeper.app.data.TimeSegment
import java.util.Locale

object PendingChangeTable {

    /** 待生效项所属的页面 / 模块，顺序即弹窗里的分组顺序。 */
    enum class Scope(val title: String) {
        SEGMENT("勿扰时段"),
        APP_WINDOW("应用时间窗"),
        PARAM("规则参数"),
        BLACKLIST("全局黑名单")
    }

    /** 这一项相对生效中的值是加严还是放宽；与时间无关的项（如生效时间）为 [NEUTRAL]。 */
    enum class Direction { STRICTER, LOOSER, NEUTRAL }

    data class Item(
        val scope: Scope,
        /** 同一 [scope] 内稳定的标识，撤销时据此重新定位。 */
        val id: String,
        val title: String,
        val detail: String,
        val effectiveMillis: Long,
        val direction: Direction,
        val revocable: Boolean = true,
        /** 不能单独撤销时的原因，弹窗里替代撤销按钮显示。 */
        val unrevocableHint: String? = null
    )

    fun items(context: Context): List<Item> = Scope.entries.flatMap { items(context, it) }

    fun items(context: Context, scope: Scope): List<Item> = runCatching {
        when (scope) {
            Scope.SEGMENT -> segmentItems(context)
            Scope.APP_WINDOW -> appWindowItems(context)
            Scope.PARAM -> paramItems(context)
            Scope.BLACKLIST -> blacklistItems(context)
        }
    }.getOrDefault(emptyList())

    /** 撤销一项。返回 false = 该项已经不存在（比如刚刚生效或已被撤销）。 */
    fun revoke(context: Context, item: Item): Boolean {
        if (items(context, item.scope).none { it.id == item.id }) return false
        return when (item.scope) {
            Scope.SEGMENT -> revokeSegment(context, item.id)
            Scope.APP_WINDOW -> revokeAppWindow(context, item.id)
            Scope.PARAM -> revokeParam(context, item.id)
            Scope.BLACKLIST -> revokeBlacklist(context, item.id)
        }
    }

    // ==========================================
    // 勿扰时段
    // ==========================================

    private enum class SegmentKind { ADD, MODIFY, DELETE }

    private class SegmentPair(val kind: SegmentKind, val active: TimeSegment?, val pending: TimeSegment?) {
        val id: String
            get() = when (kind) {
                SegmentKind.ADD -> "seg:add:${pending!!.name}"
                SegmentKind.MODIFY -> "seg:mod:${active!!.name}"
                SegmentKind.DELETE -> "seg:del:${active!!.name}"
            }
    }

    private fun sameTimes(a: TimeSegment, b: TimeSegment) =
        a.startHour == b.startHour && a.startMinute == b.startMinute &&
            a.endHour == b.endHour && a.endMinute == b.endMinute

    /** 待生效整表与生效中整表逐段配对：同名优先，其次同起止且生效日有交集（与时段页的标识一致）。 */
    private fun pairSegments(active: List<TimeSegment>, pending: List<TimeSegment>): List<SegmentPair> {
        val claimed = mutableSetOf<Int>()
        val result = mutableListOf<SegmentPair>()
        for (p in pending) {
            val idx = active.indices.firstOrNull { it !in claimed && active[it].name == p.name }
                ?: active.indices.firstOrNull {
                    it !in claimed && sameTimes(active[it], p) &&
                        SegmentAdverseAnalysis.isDaysOverlapping(active[it].daysOfWeek, p.daysOfWeek)
                }
            if (idx == null) {
                result += SegmentPair(SegmentKind.ADD, null, p)
            } else {
                claimed += idx
                val a = active[idx]
                if (!SegmentAdverseAnalysis.isSingleSegmentEquivalent(a, p)) {
                    result += SegmentPair(SegmentKind.MODIFY, a, p)
                }
            }
        }
        active.indices.filter { it !in claimed }.forEach { result += SegmentPair(SegmentKind.DELETE, active[it], null) }
        return result
    }

    /** 与生效中时段对应的基准时段：同名优先，其次同起止且生效日有交集（与 pairSegments 同一套配对）。 */
    private fun baselineSegmentFor(context: Context, active: TimeSegment): TimeSegment? {
        val baseline = TimeRuleManager.getBaselineSegments(context) ?: return null
        return baseline.firstOrNull { it.name == active.name }
            ?: baseline.firstOrNull {
                sameTimes(it, active) && SegmentAdverseAnalysis.isDaysOverlapping(it.daysOfWeek, active.daysOfWeek)
            }
    }

    private fun dailyEffectiveMillis(context: Context): Long {
        TimeRuleManager.healDailyPendingEffectiveTime(context)
        val stored = context.getSharedPreferences(TimeRuleManager.PREFS_PENDING, Context.MODE_PRIVATE)
            .getLong("effective_timestamp", 0L)
        return if (stored > 0L) stored else ConfigPendingManager.calculateNextEffectiveTime(context)
    }

    private fun segmentItems(context: Context): List<Item> {
        val pending = TimeRuleManager.getPendingTimeSegments(context) ?: return emptyList()
        val active = TimeRuleManager.getTimeSegments(context)
        val effective = dailyEffectiveMillis(context)
        val allNextDay = EffectiveActivationPolicyManager.getPolicy(context) ==
            EffectiveActivationPolicyManager.Policy.ALL_NEXT_DAY
        return pairSegments(active, pending).map { pair ->
            when (pair.kind) {
                SegmentKind.ADD -> {
                    val p = pair.pending!!
                    Item(
                        Scope.SEGMENT, pair.id,
                        "新增 · ${p.name} ${p.formatTimeRange()}",
                        "休息 ${p.limitMins} 分钟 · ${TimeRuleManager.formatDays(p.daysOfWeek)}",
                        // 新增时段本身是加严；宽严有别下它只会因为与既有时段重叠才挂 pending，不判宽严
                        effective, if (allNextDay) Direction.STRICTER else Direction.NEUTRAL
                    )
                }
                SegmentKind.DELETE -> {
                    val a = pair.active!!
                    Item(
                        Scope.SEGMENT, pair.id,
                        "删除 · ${a.name} ${a.formatTimeRange()}",
                        "休息 ${a.limitMins} 分钟 · ${TimeRuleManager.formatDays(a.daysOfWeek)}",
                        effective, Direction.LOOSER
                    )
                }
                SegmentKind.MODIFY -> {
                    val a = pair.active!!
                    val p = pair.pending!!
                    // 左边展示当前稳定基准（今天刷新时刻的快照），不展示会随加严立刻变化的生效值
                    val base = baselineSegmentFor(context, a)
                    val diff = SegmentAdverseAnalysis.getCategorizedSegmentDiff(context, a, p, base)
                    // 新鲜时段（当前周期内刚改过）的分析一律判有利；这里要的是真实宽严，所以抹掉新鲜标记再判
                    val adverse = SegmentAdverseAnalysis.isSingleSegmentAdverse(a.copy(lastModified = 0L), p, context)
                    Item(
                        Scope.SEGMENT, pair.id,
                        "修改 · ${a.name} ${a.formatTimeRange()}",
                        diff.joinToString("\n") { it.message.trim().removePrefix("•").trim().let { m -> "• $m" } }
                            .ifBlank { "• 内容有调整" },
                        effective, if (adverse) Direction.LOOSER else Direction.STRICTER
                    )
                }
            }
        }
    }

    private fun segmentLogPrefix(seg: TimeSegment) =
        "seg_${seg.startHour}:${seg.startMinute}_${seg.endHour}:${seg.endMinute}_${seg.name.hashCode()}"

    private fun revokeSegment(context: Context, id: String): Boolean {
        val pending = TimeRuleManager.getPendingTimeSegments(context) ?: return false
        val active = TimeRuleManager.getTimeSegments(context)
        val pair = pairSegments(active, pending).firstOrNull { it.id == id } ?: return false

        val restored = pending.toMutableList()
        when (pair.kind) {
            SegmentKind.ADD -> restored.remove(pair.pending!!)
            SegmentKind.MODIFY -> restored[restored.indexOf(pair.pending!!)] = pair.active!!
            SegmentKind.DELETE -> restored.add(pair.active!!)
        }
        TimeRuleManager.replacePendingSegments(context, restored)

        (pair.pending ?: pair.active)?.let {
            ChangeLogManager.removePendingEntriesByTagPrefix(context, "segments", segmentLogPrefix(it))
        }
        settle(context)
        return true
    }

    // ==========================================
    // 规则参数（日参数 + 每周特权）
    // ==========================================

    private class IntParam(
        val key: String,
        val title: String,
        val logTag: String,
        val higherStricter: Boolean,
        val active: (Context) -> Int,
        val baseline: (Context) -> Int?,
        val format: (Int) -> String
    )

    private val intParams = listOf(
        IntParam("cool_down_rest_seconds", "休息冷却", "cool_down_rest", true,
            TimeRuleManager::getCoolDownRestSeconds, TimeRuleManager::getBaselineCoolDownRestSeconds) { "${it}秒" },
        IntParam("cool_down_gap_seconds", "空档冷却", "cool_down_gap", true,
            TimeRuleManager::getCoolDownGapSeconds, TimeRuleManager::getBaselineCoolDownGapSeconds) { "${it}秒" },
        IntParam("borrow_percentage", "借用比例", "borrow_percentage", false,
            TimeRuleManager::getBorrowPercentage, TimeRuleManager::getBaselineBorrowPercentage) { "${it}%" },
        IntParam(TimeRuleManager.KEY_TEMP_RESTRICTED_ACCESS_MAX_SECONDS, "临时使用受限应用时长上限",
            "temp_restricted_access_max_seconds", false,
            TimeRuleManager::getTemporaryRestrictedAccessMaxSeconds,
            TimeRuleManager::getBaselineTemporaryRestrictedAccessMaxSeconds) {
            String.format(Locale.US, "%.1f分钟", it / 60.0)
        }
    )

    private const val KEY_STRICT_PENALTY_PENDING = "strict_penalty_duration_seconds"
    private const val KEY_REFRESH_PENDING = "config_refresh_time"
    private const val ID_STRICT = "param:strict_penalty"
    private const val ID_EDIT_POLICY = "param:edit_window_policy"
    private const val ID_PRIV_WINDOW = "param:privilege_window"
    private const val ID_REFRESH = "param:refresh_time"
    private const val ID_WEEKLY_COUNT = "param:weekly_count"
    private const val ID_WEEKLY_DURATION = "param:weekly_duration"

    private fun direction(higherStricter: Boolean, pending: Long, active: Long) = when {
        pending == active -> Direction.NEUTRAL
        (pending > active) == higherStricter -> Direction.STRICTER
        else -> Direction.LOOSER
    }

    /**
     * 表里「左边」固定展示当前稳定基准（日参数按逻辑日、每周特权按周更新），不展示生效值：
     * 生效值会因为一次加严立刻变化，基准要过一个周期才变。基准缺失，或基准恰好等于待生效值
     * （此时左右相同、表达不出变化）时，退回生效值，保证待生效项一定看得出变化。
     */
    private fun <T> displayFrom(baseline: T?, active: T, pending: T): T =
        if (baseline != null && baseline != pending) baseline else active

    /** 日参数：只看 pending 里和生效值不同的键（pending 会存整份参数快照，相同的不算待生效）。 */
    private fun dailyParamItems(context: Context): List<Item> {
        if (!TimeRuleManager.hasPendingRules(context)) return emptyList()
        val prefs = context.getSharedPreferences(TimeRuleManager.PREFS_PENDING, Context.MODE_PRIVATE)
        val effective = dailyEffectiveMillis(context)
        val items = mutableListOf<Item>()

        for (spec in intParams) {
            if (!prefs.contains(spec.key)) continue
            val pending = runCatching { prefs.getInt(spec.key, 0) }.getOrNull() ?: continue
            val active = spec.active(context)
            if (pending == active) continue
            val from = displayFrom(spec.baseline(context), active, pending)
            items += Item(
                Scope.PARAM, "param:${spec.key}", spec.title,
                "${spec.format(from)} → ${spec.format(pending)}",
                effective, direction(spec.higherStricter, pending.toLong(), from.toLong())
            )
        }

        if (prefs.contains(KEY_STRICT_PENALTY_PENDING)) {
            val pending = runCatching { prefs.getLong(KEY_STRICT_PENALTY_PENDING, 0L) }.getOrNull()
            val active = TimeRuleManager.getStrictPenaltyDurationSeconds(context)
            if (pending != null && pending != active) {
                val from = displayFrom(TimeRuleManager.getBaselineStrictPenaltyDurationSeconds(context), active, pending)
                items += Item(
                    Scope.PARAM, ID_STRICT, "黑名单惩罚时长", "${from}秒 → ${pending}秒",
                    effective, direction(true, pending, from)
                )
            }
        }

        if (prefs.contains(TimeRuleEditWindowPolicyManager.KEY_POLICY)) {
            val pending = TimeRuleEditWindowPolicyManager.Policy.fromKey(
                prefs.getString(TimeRuleEditWindowPolicyManager.KEY_POLICY, null)
            )
            val active = TimeRuleEditWindowPolicyManager.getPolicy(context)
            if (pending != active) {
                items += Item(
                    Scope.PARAM, ID_EDIT_POLICY, "时间规则可修改时段",
                    "${TimeRuleEditWindowPolicyManager.getPolicyTitle(active)} → " +
                        TimeRuleEditWindowPolicyManager.getPolicyTitle(pending),
                    effective,
                    if (pending == TimeRuleEditWindowPolicyManager.Policy.EFFECTIVE_TIME_TO_MIDNIGHT) Direction.STRICTER
                    else Direction.LOOSER
                )
            }
        }

        val pendingStart = prefs.getString(TimeRuleManager.KEY_PRIVILEGE_WINDOW_START, null)
        val pendingEnd = prefs.getString(TimeRuleManager.KEY_PRIVILEGE_WINDOW_END, null)
        if (pendingStart != null || pendingEnd != null) {
            val activeStart = TimeRuleManager.getPrivilegeWindowStart(context)
            val activeEnd = TimeRuleManager.getPrivilegeWindowEnd(context)
            val newStart = pendingStart ?: activeStart
            val newEnd = pendingEnd ?: activeEnd
            if (newStart != activeStart || newEnd != activeEnd) {
                val baseStart = TimeRuleManager.getBaselinePrivilegeWindowStart(context)
                val baseEnd = TimeRuleManager.getBaselinePrivilegeWindowEnd(context)
                val fromStart = if (baseStart != null && baseEnd != null && (baseStart != newStart || baseEnd != newEnd)) baseStart else activeStart
                val fromEnd = if (baseStart != null && baseEnd != null && (baseStart != newStart || baseEnd != newEnd)) baseEnd else activeEnd
                // 特权可用范围的任何修改一律次日生效，不判宽严
                items += Item(
                    Scope.PARAM, ID_PRIV_WINDOW, "特权可用范围",
                    "$fromStart~$fromEnd → $newStart~$newEnd",
                    effective, Direction.NEUTRAL
                )
            }
        }

        val pendingRefresh = prefs.getString(KEY_REFRESH_PENDING, null)
        if (pendingRefresh != null && pendingRefresh != TimeRuleManager.getRefreshTime(context)) {
            items += Item(
                Scope.PARAM, ID_REFRESH, "生效时间",
                "${TimeRuleManager.getRefreshTime(context)} → $pendingRefresh",
                effective, Direction.NEUTRAL
            )
        }
        return items
    }

    private fun weeklyParamItems(context: Context): List<Item> {
        if (!CreditManager.hasPendingWeeklyPrivilegeLimit(context)) return emptyList()
        val stored = CreditManager.getPendingWeeklyPrivilegeEffectiveTime(context)
        val effective = if (stored > 0L) stored
        else ConfigPendingManager.calculateNextMondayEffectiveTime(context, System.currentTimeMillis())
        val items = mutableListOf<Item>()
        val pendingCount = CreditManager.getPendingWeeklyPrivilegeLimit(context)
        val activeCount = CreditManager.getWeeklyPrivilegeLimit(context)
        if (pendingCount != activeCount) {
            val from = displayFrom(TimeRuleManager.getBaselineWeeklyPrivilegeLimit(context), activeCount, pendingCount)
            items += Item(
                Scope.PARAM, ID_WEEKLY_COUNT, "每周特权次数", "${from}次 → ${pendingCount}次",
                effective, direction(false, pendingCount.toLong(), from.toLong())
            )
        }
        val pendingDuration = CreditManager.getPendingWeeklyPrivilegeDurationLimit(context)
        val activeDuration = CreditManager.getWeeklyPrivilegeDurationLimit(context)
        if (pendingDuration != activeDuration) {
            val from = displayFrom(TimeRuleManager.getBaselineWeeklyPrivilegeDurationLimit(context), activeDuration, pendingDuration)
            items += Item(
                Scope.PARAM, ID_WEEKLY_DURATION, "每周特权总时长", "${from}分钟 → ${pendingDuration}分钟",
                effective, direction(false, pendingDuration.toLong(), from.toLong())
            )
        }
        return items
    }

    private fun paramItems(context: Context): List<Item> = dailyParamItems(context) + weeklyParamItems(context)

    private fun revokeParam(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences(TimeRuleManager.PREFS_PENDING, Context.MODE_PRIVATE)
        when (id) {
            ID_WEEKLY_COUNT, ID_WEEKLY_DURATION -> {
                val newCount = if (id == ID_WEEKLY_COUNT) CreditManager.getWeeklyPrivilegeLimit(context)
                else CreditManager.getPendingWeeklyPrivilegeLimit(context)
                val newDuration = if (id == ID_WEEKLY_DURATION) CreditManager.getWeeklyPrivilegeDurationLimit(context)
                else CreditManager.getPendingWeeklyPrivilegeDurationLimit(context)
                if (newCount == CreditManager.getWeeklyPrivilegeLimit(context) &&
                    newDuration == CreditManager.getWeeklyPrivilegeDurationLimit(context)
                ) {
                    CreditManager.clearPendingWeeklyPrivilegeLimit(context)
                } else {
                    CreditManager.setPendingWeeklyPrivilegeLimit(
                        context, newCount, newDuration, CreditManager.getPendingWeeklyPrivilegeEffectiveTime(context)
                    )
                }
                ChangeLogManager.removePendingEntriesByTag(
                    context, "global_params",
                    if (id == ID_WEEKLY_COUNT) "weekly_privilege_count" else "weekly_privilege_duration"
                )
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "weekly_privilege")
            }
            ID_STRICT -> {
                prefs.edit().remove(KEY_STRICT_PENALTY_PENDING).apply()
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "strict_penalty")
            }
            ID_EDIT_POLICY -> {
                prefs.edit().remove(TimeRuleEditWindowPolicyManager.KEY_POLICY).apply()
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "edit_window_policy")
            }
            ID_PRIV_WINDOW -> {
                prefs.edit()
                    .remove(TimeRuleManager.KEY_PRIVILEGE_WINDOW_START)
                    .remove(TimeRuleManager.KEY_PRIVILEGE_WINDOW_END)
                    .apply()
                ChangeLogManager.removePendingEntriesContaining(context, "global_params", "特权可用范围")
            }
            ID_REFRESH -> {
                prefs.edit().remove(KEY_REFRESH_PENDING).apply()
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "refresh_time")
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", "global_param_refresh_time")
            }
            else -> {
                val spec = intParams.firstOrNull { "param:${it.key}" == id } ?: return false
                prefs.edit().remove(spec.key).apply()
                ChangeLogManager.removePendingEntriesByTag(context, "global_params", spec.logTag)
            }
        }
        settle(context)
        return true
    }

    // ==========================================
    // 应用时间窗
    // ==========================================

    private val weekdayNames = mapOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")

    private fun formatWeekdays(days: Set<Int>) =
        if (days.size == 7) "每天" else days.sorted().joinToString("、") { weekdayNames[it] ?: "" }

    private fun appWindowItems(context: Context): List<Item> {
        if (!AppWindowManager.hasPendingChanges(context)) return emptyList()
        val stored = AppWindowManager.getPendingEffectiveTimestamp(context)
        val effective = if (stored > 0L) stored else ConfigPendingManager.calculateNextEffectiveTime(context)
        val items = mutableListOf<Item>()

        fun bodyOf(description: String) = description.lines().drop(1)
            .joinToString("\n") { "• ${it.trim().removePrefix("-").trim()}" }

        AppWindowManager.getDisplayRules(context)
            .filter { it.status != AppWindowManager.RuleStatus.ACTIVE }
            .forEach { entry ->
                val rule = entry.rule
                val activeRule = entry.activeRule
                when (entry.status) {
                    AppWindowManager.RuleStatus.PENDING_ADDITION -> {
                        val adverse = AppWindowManager.analyzeChange(context, null, rule).isOverallAdverse
                        items += Item(
                            Scope.APP_WINDOW, "aw:${rule.id}", "新增 · ${rule.name}",
                            bodyOf(AppWindowManager.generateChangeDescription(context, null, rule)),
                            effective, if (adverse) Direction.LOOSER else Direction.STRICTER
                        )
                    }
                    AppWindowManager.RuleStatus.PENDING_MODIFICATION -> {
                        val adverse = activeRule != null &&
                            AppWindowManager.analyzeChange(context, activeRule, rule).isOverallAdverse
                        items += Item(
                            Scope.APP_WINDOW, "aw:${rule.id}", "修改 · ${activeRule?.name ?: rule.name}",
                            // 左边展示当前稳定基准；基准缺失或恰好等于待生效内容时退回生效中规则
                            bodyOf(AppWindowManager.generateChangeDescription(
                                context,
                                AppWindowManager.getBaselineRule(context, rule.id)
                                    ?.takeUnless { AppWindowManager.isRuleContentSame(it, rule) } ?: activeRule,
                                rule
                            )),
                            effective, if (adverse) Direction.LOOSER else Direction.STRICTER
                        )
                    }
                    AppWindowManager.RuleStatus.PENDING_DELETION -> {
                        val adverse = AppWindowManager.analyzeChange(context, rule, null).isOverallAdverse
                        items += Item(
                            Scope.APP_WINDOW, "aw:${rule.id}", "删除 · ${rule.name}",
                            "• 时间: ${AppWindowManager.formatTimeRange(rule)}\n• 日期: ${formatWeekdays(rule.daysOfWeek)}",
                            effective, if (adverse) Direction.LOOSER else Direction.STRICTER
                        )
                    }
                    AppWindowManager.RuleStatus.ACTIVE -> Unit
                }
            }

        // 新增规则里「部分应用 × 日期」延后：规则整条已生效，延后的部分被遮住
        val activeRules = AppWindowManager.getRules(context).associateBy { it.id }
        AppWindowManager.getPartialDelayedDays(context).forEach { (ruleId, appDays) ->
            val rule = activeRules[ruleId] ?: return@forEach
            val ruleDays = rule.daysOfWeek.filter { it in 1..7 }.toSet()
            val partially = appDays.filterValues { !it.containsAll(ruleDays) }
            val text = appDays.entries.joinToString("；") { (pkg, days) ->
                "${CreditManager.getAppLabel(context, pkg)}(${formatWeekdays(days)})"
            }
            items += Item(
                Scope.APP_WINDOW, "awp:$ruleId", "新增（部分待生效）· ${rule.name}",
                "• 下一刷新时刻才生效: $text",
                effective, Direction.LOOSER,
                revocable = partially.isEmpty(),
                unrevocableHint = if (partially.isEmpty()) null else "仅部分日期延后，无法单独撤销"
            )
        }
        return items
    }

    private fun revokeAppWindow(context: Context, id: String): Boolean {
        if (id.startsWith("awp:")) {
            val ok = AppWindowManager.revokePartialDelayedRule(context, id.removePrefix("awp:"))
            if (ok) settle(context)
            return ok
        }
        AppWindowManager.undoRuleChange(context, id.removePrefix("aw:"))
        settle(context)
        return true
    }

    // ==========================================
    // 全局黑名单
    // ==========================================

    private fun blacklistItems(context: Context): List<Item> {
        val pending = BlacklistStore.getPendingBlacklistApps(context) ?: return emptyList()
        val active = BlacklistStore.getBlacklistApps(context)
        val stored = BlacklistStore.getPendingEffectiveTimestamp(context)
        val effective = if (stored > 0L) stored else ConfigPendingManager.calculateNextEffectiveTime(context)
        val items = mutableListOf<Item>()
        (active - pending).sorted().forEach { pkg ->
            items += Item(
                Scope.BLACKLIST, "bl:$pkg", "解除屏蔽 · ${CreditManager.getAppLabel(context, pkg)}",
                "", effective, Direction.LOOSER
            )
        }
        (pending - active).sorted().forEach { pkg ->
            items += Item(
                Scope.BLACKLIST, "bl:$pkg", "新增屏蔽 · ${CreditManager.getAppLabel(context, pkg)}",
                "", effective, Direction.STRICTER
            )
        }
        return items
    }

    private fun revokeBlacklist(context: Context, id: String): Boolean =
        BlacklistStore.revokePendingApp(context, id.removePrefix("bl:"))

    // ==========================================
    // 收尾
    // ==========================================

    /** 撤销后统一收尾：勿扰时段与日参数都清空了，就把 pending 总开关和改动日志一并收干净。 */
    private fun settle(context: Context) {
        val hasDaily = segmentItems(context).isNotEmpty() || dailyParamItems(context).isNotEmpty()
        TimeRuleManager.settlePendingRuleFlags(context, hasDaily)
        if (!hasDaily) {
            ChangeLogManager.removePendingEntriesByCategory(context, "segments")
            ChangeLogManager.clearPendingLogsForCategoryExcludingWeekly(context, "global_params")
        }
    }
}
