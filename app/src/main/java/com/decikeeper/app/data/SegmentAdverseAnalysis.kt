package com.decikeeper.app.data

import android.content.Context
import com.decikeeper.app.manager.TimeRuleManager
import java.util.Calendar


data class DiffItem(
    val label: String,
    val message: String,
    val isAdverse: Boolean,
    val isRestored: Boolean
)

data class SegmentAnalysis(
    val isRangeAdverse: Boolean = false,
    val isStatusAdverse: Boolean = false,
    val isDaysAdverse: Boolean = false,
    val isLimitAdverse: Boolean = false,
    // 🆕
    val isCountLimitAdverse: Boolean = false,
    // 🆕 名单变化不利（减少/清空/恢复跟随全局/混合增删）
    val isListAdverse: Boolean = false
) {
    fun isAnyAdverse(): Boolean = isRangeAdverse || isStatusAdverse || isDaysAdverse || isLimitAdverse || isCountLimitAdverse || isListAdverse
}

object SegmentAdverseAnalysis {

    /**
     * 判断一组时段变更是否包含“不利”变动 (Adverse / Relaxing)
     * 核心逻辑：覆盖范围检查 (Coverage Check)
     * 只要 新时段 (New) 覆盖了 旧时段 (Old) 的所有时间点，即视为有利/中性 (Benign)。
     * 如果 新时段 遗漏了 旧时段 的任何一分钟，即视为不利 (Adverse)。
     */
    fun isAdverseSegments(context: Context, newSegments: List<TimeSegment>): Boolean {
        val oldSegments = TimeRuleManager.getTimeSegments(context)
        return isAdverseSegments(oldSegments, newSegments, context)
    }

    fun isAdverseSegments(oldSegments: List<TimeSegment>, newSegments: List<TimeSegment>): Boolean =
        isAdverseSegments(oldSegments, newSegments, null)

    internal fun isAdverseSegments(oldSegments: List<TimeSegment>, newSegments: List<TimeSegment>, context: Context?): Boolean {
        // Build per-day coverage [1..7][0..1439], aligned with daysOfWeek semantics.
        val oldCoverage = Array(8) { BooleanArray(1440) }
        val newCoverage = Array(8) { BooleanArray(1440) }

        fun fillCoverage(segments: List<TimeSegment>, coverage: Array<BooleanArray>) {
            for (seg in segments) {
                if (!seg.isEnabled) continue

                val activeDays = TimeRuleManager.normalizeDaysSet(seg.daysOfWeek)
                val start = seg.startHour * 60 + seg.startMinute
                val end = seg.endHour * 60 + seg.endMinute

                for (day in activeDays) {
                    val dayCoverage = coverage[day]
                    if (start < end) {
                        for (i in start until end) {
                            dayCoverage[i] = true
                        }
                    } else {
                        for (i in start until 1440) dayCoverage[i] = true
                        for (i in 0 until end) dayCoverage[i] = true
                    }
                }
            }
        }

        // 🚀 [Rule 1.0.3.1] Fresh Segment Exemption (Regret Period)
        // Only established segments (NOT fresh) contribute to the "Baseline Coverage".
        // If a segment is fresh (created today), losing its coverage is allowed (Undo).
        val establishedOldSegments = oldSegments.filter { !isSegmentFresh(it) }
        fillCoverage(establishedOldSegments, oldCoverage)
        
        fillCoverage(newSegments, newCoverage)

        // If any minute previously covered on a specific day is no longer covered, it's adverse.
        for (day in 1..7) {
            for (minute in 0 until 1440) {
                if (oldCoverage[day][minute] && !newCoverage[day][minute]) {
                    return true
                }
            }
        }

        // Parameter relaxation check only applies to day-overlapping matches.
        for (newSeg in newSegments) {
            val oldSeg = findMatchingOldSegment(newSeg, oldSegments)
            if (oldSeg != null && isDaysOverlapping(oldSeg.daysOfWeek, newSeg.daysOfWeek)) {
                if (isSingleSegmentAdverse(oldSeg, newSeg, context)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * 🚀 [Hybrid] Per-Segment Adverse Check
     * Checks if a single segment modification is adverse compared to its "base" version.
     * Returns true if the new segment is "relaxing restrictions" compared to old.
     * If oldSeg is null (Addition), always return false (Benign).
     *
     * 生效日期改动规则：
     * - 原先生效的改成不生效 → 不利改动
     * - 原来星期一改到星期二 → 不利改动
     * - 在原有基础上添加天 → 有利改动
     * - 删掉某几天，再补上某几天 → 不利改动
     */
    /**
     * 🚀 [NEW] Granular Per-Segment Adverse Check
     * Implements PRD v2.9 rules: Favorable parts apply immediately, Adverse delay.
     * 🚀 [NEW] PRD v3.2 Regret Period Exemption:
     * If a rule was created/modified within the current cycle (Since 08:00 AM),
     * any subsequent modification is considered "Undo" (Benign), not Adverse.
     */
    fun analyzeSingleSegmentChange(oldSeg: TimeSegment?, newSeg: TimeSegment, context: Context? = null, listReferenceSeg: TimeSegment? = null): SegmentAnalysis {
        // Addition = New Segment = All Favorable
        if (oldSeg == null) return SegmentAnalysis()

        // 🚀 [Rule 1.0.3.1] Regret Period Exemption
        // If the old segment is "fresh" (created in current cycle), we allow ANY change immediately.
        if (isSegmentFresh(oldSeg)) {
            // android.util.Log.i("TimeRuleManager", "Skipping Adverse Check for Fresh Segment: ${oldSeg.name}")
            return SegmentAnalysis() // All false = Benign
        }

        // 1. Status: Enable is Favorable, Disable is Adverse
        val statusAdv = oldSeg.isEnabled && !newSeg.isEnabled

        // 2. Days: Increase is Favorable, Decrease/Replace is Adverse
        val oldDays = oldSeg.daysOfWeek?.toSet() ?: setOf(1, 2, 3, 4, 5, 6, 7)
        val newDays = newSeg.daysOfWeek?.toSet() ?: setOf(1, 2, 3, 4, 5, 6, 7)
        val daysAdv = (oldDays - newDays).isNotEmpty()

        // 3. Limits: Decrease is Favorable, Increase is Adverse
        val limitAdv = newSeg.limitMins > oldSeg.limitMins

        // 🆕 3b. UsableCount: Decrease is Favorable (fewer unlocks = stricter), Increase is Adverse
        // -1 → -1: both auto, no change
        // -1 → N: depends (N > auto → adverse; N <= auto → favorable)
        // N → -1: depends (auto > N → adverse; auto <= N → favorable)
        val oldEffectiveCount = oldSeg.getEffectiveUsableCountLimitHalfUnits()
        val newEffectiveCount = newSeg.getEffectiveUsableCountLimitHalfUnits()
        val countLimitAdv = newEffectiveCount > oldEffectiveCount

        // 4. Range: Fully Wrap is Favorable, otherwise Adverse
        val rangeAdv = !isRangeWrapping(oldSeg, newSeg)

        // 🆕 5. listApps: 名单增加=有利(立即)，减少/清空/恢复 null=不利(次日)
        val listAdv = classifyListAppsAdverse(context, listReferenceSeg ?: oldSeg, newSeg)

        return SegmentAnalysis(rangeAdv, statusAdv, daysAdv, limitAdv, countLimitAdv, listAdv)
    }

    /**
     * 🆕 [TASK-04] 名单变化的不利分类（SPEC §2.8 / §6 S8）。
     *
     * 集合语义（忽略顺序与重复）；null 与空列表严格区分：
     *   - null = 跟随全局黑名单；empty = 脱钩后不锁任何应用。
     *   - 新名单 ⊇ 旧名单（只增不删）→ 有利(false)；
     *   - 新名单 ⊂ 旧名单（有删）→ 不利(true)；
     *   - 混合增删 → 不利(true)；
     *   - 旧 null → 新非 null：以 baseline 段有效名单(全局黑名单−硬剔除)为基准，
     *     新名单 ⊇ 基准 → 有利，否则不利（无 context 时保守判不利）；
     *   - 新 null（恢复跟随全局）：旧名单非空 → 不利；旧名单为空 → 有利(加严)。
     */
    private fun classifyListAppsAdverse(context: Context?, oldSeg: TimeSegment?, newSeg: TimeSegment): Boolean {
        val oldEffective = if (context != null && oldSeg != null && oldSeg.listApps == null) {
            SegmentBlacklist.effectiveList(context, oldSeg)
        } else null // null = 无法计算基准（context 缺失时仅"脱钩"分支受影响）
        return classifyListAppsAdverseCore(oldSeg, newSeg, oldEffective) { oldEff ->
            if (context == null) {
                oldEff // 无 context：不额外过滤（保持原"双非 null 无需 context"语义）
            } else {
                // [FIX 2026-08-14 用户报告] 名单页只展示有启动入口的应用；全局黑名单里
                // 无入口/已卸载的包无法被勾选（全选/反选后必然丢失），不能作为"被移除"
                // 的基准——否则在全局基础上添加应用会被误判为放松→延迟生效。
                oldEff.filter { pkg ->
                    runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }
                        .getOrDefault(false)
                }.toSet()
            }
        }
    }

    /**
     * [FIX 2026-08-14] 与 classifyListAppsAdverse 相同的判定核心，但"有效基准名单"与
     * "可展示子集"由调用方注入（生产=全局黑名单−硬剔除 + PackageManager.hasLaunchIntent；
     * 测试=显式集合与 resolver，避免 Robolectric 无法解析 launch intent 导致的假绿）。
     *
     * oldEffective == null 表示无法计算基准（context 缺失）：仅"旧 null→新非 null"
     * 分支受影响并保守判不利（与原语义一致）；其余分支（null→null / 非null→null /
     * 双非null）不需要 context，行为与旧代码完全一致。
     */
    internal fun classifyListAppsAdverseCore(
        oldSeg: TimeSegment?,
        newSeg: TimeSegment,
        oldEffective: Set<String>?,
        selectable: (Set<String>) -> Set<String>
    ): Boolean {
        val oldList = oldSeg?.listApps
        val newList = newSeg.listApps

        if (oldList == null && newList == null) return false

        if (oldList != null && newList == null) {
            // 恢复为跟随全局：旧名单非空 = 放松（不利）；旧名单为空 = 加严（有利）。
            return oldList.isNotEmpty()
        }

        if (oldList == null) {
            // 首次脱钩（旧 null → 新非 null）：newList 必非 null。
            val n = newList ?: return true
            if (oldEffective == null) return true // 无法判定 → 保守视为混合/不利（原语义）
            val selectableOld = selectable(oldEffective)
            return !n.toSet().containsAll(selectableOld)
        }

        // 两者都非 null：集合语义比较（前三分支已排除所有含 null 的情形）。
        val oldSet = oldList.toSet()
        val newSet = newList!!.toSet()
        return !newSet.containsAll(oldSet) // 有删或混合 → 不利
    }

    private fun isRangeWrapping(old: TimeSegment, new: TimeSegment): Boolean {
        // Use minute-by-minute coverage: Every minute in old must be in new
        val oldStart = old.startHour * 60 + old.startMinute
        val oldDur = old.getDurationMinutes()
        
        for (i in 0 until oldDur) {
            val minute = (oldStart + i) % 1440
            if (!new.isInTimeRange(minute / 60, minute % 60)) return false
        }
        return true
    }

    /**
     * 🚀 [Helper] Check if a segment sits within the "Current Cycle"
     * Cycle starts at 08:00 AM daily.
     */
    internal fun isSegmentFresh(seg: TimeSegment): Boolean {
        // If no timestamp, assume old (Standard Baseline)
        if (seg.lastModified <= 0) return false

        val now = Calendar.getInstance()
        val cycleStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If now is before 8:00 AM, the cycle started yesterday at 8:00 AM
        if (now.before(cycleStart)) {
            cycleStart.add(Calendar.DAY_OF_YEAR, -1)
        }

        return seg.lastModified >= cycleStart.timeInMillis
    }

    fun isSingleSegmentAdverse(oldSeg: TimeSegment?, newSeg: TimeSegment, context: Context? = null, listReferenceSeg: TimeSegment? = null): Boolean {
        return analyzeSingleSegmentChange(oldSeg, newSeg, context, listReferenceSeg).isAnyAdverse()
    }

    /**
     * 🚀 [NEW] Create a Hybrid Segment for "Today"
     * Applies favorable changes immediately, withholds adverse changes.
     */
    internal fun createHybridSegment(old: TimeSegment, new: TimeSegment, ana: SegmentAnalysis): TimeSegment {
        // 1. Range: If adverse, use Union. If favorable, use New.
        var targetStart = new.startHour * 60 + new.startMinute
        var targetEnd = new.endHour * 60 + new.endMinute
        
        if (ana.isRangeAdverse) {
            val oldStart = old.startHour * 60 + old.startMinute
            val oldEnd = old.endHour * 60 + old.endMinute
            
            // Union math (Handles cross-midnight by taking extremes)
            // Simpler: Just check if new covers old. If not, expand.
            val oldDur = old.getDurationMinutes()
            val newTotalRange = BooleanArray(1440)
            
            // Fill new
            val nStart = new.startHour * 60 + new.startMinute
            val nEnd = new.endHour * 60 + new.endMinute
            if (nStart < nEnd) for (i in nStart until nEnd) newTotalRange[i] = true
            else { for (i in nStart until 1440) newTotalRange[i] = true; for (i in 0 until nEnd) newTotalRange[i] = true }
            
            // Add old
            if (oldStart < oldEnd) for (i in oldStart until oldEnd) newTotalRange[i] = true
            else { for (i in oldStart until 1440) newTotalRange[i] = true; for (i in 0 until oldEnd) newTotalRange[i] = true }
            
            // Find new Start/End for the unioned continuous block
            // This is complex for disjoint segments, but here it's 1 segment.
            // Let's stick to the principle: If not wrap, use old range for today to avoid shrinkage?
            // No, extension should apply. So Union is best.
            // Simplified Union for Single Segment:
            val uStart = if (ana.isRangeAdverse) {
                // If we don't wrap, it means one of the bounds is moving inward.
                // We take the "outer" bounds.
                val s1 = old.startHour * 60 + old.startMinute
                val s2 = new.startHour * 60 + new.startMinute
                // This is tricky with cross-midnight. 
                // Let's use a simpler heuristic: If it doesn't wrap, today stay with OLD range to be safe.
                oldStart
            } else targetStart
            
            val uEnd = if (ana.isRangeAdverse) oldEnd else targetEnd
            
            targetStart = uStart
            targetEnd = uEnd
        }

        // 2. Status: Today stay Enabled if either is Enabled
        val targetEnabled = old.isEnabled || new.isEnabled

        // 3. Days: Union for Today
        val oldDaysSet = old.daysOfWeek?.toSet() ?: setOf(1,2,3,4,5,6,7)
        val newDaysSet = new.daysOfWeek?.toSet() ?: setOf(1,2,3,4,5,6,7)
        val unionDays = (oldDaysSet + newDaysSet).toList().let { if (it.size == 7) null else it }

        // 4. Limits: Min for Today
        val targetLimit = kotlin.math.min(old.limitMins, new.limitMins)

        // 🆕 4b. UsableCount: Min for Today (fewer = stricter = favorable immediate)
        val targetUsableCount = if (old.usableCountLimitHalfUnits == -1 && new.usableCountLimitHalfUnits == -1) {
            -1 // Both auto → stay auto
        } else {
            val oldEff = old.getEffectiveUsableCountLimitHalfUnits()
            val newEff = new.getEffectiveUsableCountLimitHalfUnits()
            // Use user-set value if possible, otherwise use min effective
            if (new.usableCountLimitHalfUnits >= 0 && newEff <= oldEff) new.usableCountLimitHalfUnits
            else if (old.usableCountLimitHalfUnits >= 0 && oldEff <= newEff) old.usableCountLimitHalfUnits
            else kotlin.math.min(oldEff, newEff)
        }

        // 🆕 4c. listApps: 不利(放松)时今日保留加严旧名单，新名单进 pending 次日生效；
        // 有利(加严)时立即用新名单。与其余字段的 Hybrid 语义一致。
        val targetListApps = if (ana.isListAdverse) old.listApps else new.listApps
        // 🆕 4d. listMode: 模式跟随对应名单（不利时保留旧模式以匹配今日旧名单，否则取新模式）。
        val targetListMode = if (ana.isListAdverse) old.listMode else new.listMode

        return TimeSegment(
            targetStart / 60, targetStart % 60,
            targetEnd / 60, targetEnd % 60,
            targetLimit, new.name, true, unionDays, targetEnabled,
            usableCountLimitHalfUnits = targetUsableCount,
            listMode = targetListMode,
            listApps = targetListApps
        )
    }

    /**
     * Helper to check if two segments are functionally identical (Ignoring name)
     */
    fun isSingleSegmentEqual(s1: TimeSegment, s2: TimeSegment): Boolean {
         val days1 = TimeRuleManager.normalizeDaysSet(s1.daysOfWeek)
         val days2 = TimeRuleManager.normalizeDaysSet(s2.daysOfWeek)
        return s1.startHour == s2.startHour && s1.startMinute == s2.startMinute &&
               s1.endHour == s2.endHour && s1.endMinute == s2.endMinute &&
               s1.limitMins == s2.limitMins &&
               s1.usableCountLimitHalfUnits == s2.usableCountLimitHalfUnits && // 🆕
             days1 == days2 && // 🚀 [Fix] Normalize Days equality
               s1.isEnabled == s2.isEnabled && // 🚀 [Fix] Check Enabled equality
               s1.listApps == s2.listApps && // 🆕 [TASK-04] 名单精确相等（null ≠ emptyList）
               s1.listMode == s2.listMode // 🆕 [黑/白名单] 模式也参与相等判定（仅改模式也要能保存）
    }

    /**
     * 🚀 [Hybrid] Find matching old segment for a new segment by Start Time.
     */
    /**
     * 🚀 [NEW] Calculate overlap minutes between two segments
     */
    fun calculateOverlapMinutes(s1: TimeSegment, s2: TimeSegment): Int {
        val ranges1 = mutableListOf<Pair<Int, Int>>()
        val start1 = s1.startHour * 60 + s1.startMinute
        val end1 = s1.endHour * 60 + s1.endMinute
        if (start1 < end1) ranges1.add(start1 to end1) else { ranges1.add(start1 to 1440); ranges1.add(0 to end1) }

        val ranges2 = mutableListOf<Pair<Int, Int>>()
        val start2 = s2.startHour * 60 + s2.startMinute
        val end2 = s2.endHour * 60 + s2.endMinute
        if (start2 < end2) ranges2.add(start2 to end2) else { ranges2.add(start2 to 1440); ranges2.add(0 to end2) }

        var totalOverlap = 0
        for (r1 in ranges1) {
            for (r2 in ranges2) {
                val intersectStart = kotlin.math.max(r1.first, r2.first)
                val intersectEnd = kotlin.math.min(r1.second, r2.second)
                if (intersectStart < intersectEnd) {
                    totalOverlap += (intersectEnd - intersectStart)
                }
            }
        }
        return totalOverlap
    }

    internal fun findMatchingOldSegment(newSeg: TimeSegment, oldSegments: List<TimeSegment>): TimeSegment? {
        // 1) Prefer exact time match with day overlap.
        val timeMatches = oldSegments.filter {
            it.startHour == newSeg.startHour &&
                it.startMinute == newSeg.startMinute &&
                it.endHour == newSeg.endHour &&
                it.endMinute == newSeg.endMinute
        }
        val timeOverlapMatch = timeMatches.firstOrNull {
            isDaysOverlapping(it.daysOfWeek, newSeg.daysOfWeek)
        }
        if (timeOverlapMatch != null) return timeOverlapMatch
        if (timeMatches.isNotEmpty()) return timeMatches.first()

        // 2) Fallback to name match, still preferring day overlap.
        if (newSeg.name.isNotBlank()) {
            val nameMatches = oldSegments.filter { it.name == newSeg.name }
            val nameOverlapMatch = nameMatches.firstOrNull {
                isDaysOverlapping(it.daysOfWeek, newSeg.daysOfWeek)
            }
            if (nameOverlapMatch != null) return nameOverlapMatch
            if (nameMatches.isNotEmpty()) return nameMatches.first()
        }

        return null
    }

    fun isDaysOverlapping(daysA: List<Int>?, daysB: List<Int>?): Boolean {
        val setA = TimeRuleManager.normalizeDaysSet(daysA)
        val setB = TimeRuleManager.normalizeDaysSet(daysB)
        return setA.any { it in setB }
    }

fun getCategorizedSegmentDiff(
        context: Context,
        old: TimeSegment?,
        match: TimeSegment,
        base: TimeSegment? = null
    ): List<DiffItem> {
        val diffItems = mutableListOf<DiffItem>()
        
        // 🚀 [Rule] Adverse is relative to ACTIVE, but Restoration overrides it.
        val activeAnalysis = analyzeSingleSegmentChange(old, match, context)

        fun add(label: String, oldV: Any?, matchV: Any, baseV: Any?, isAdverseItem: Boolean) {
            // If old is null (New Segment), only show if not default/empty, or just show value.
            // User requested: "You directly write what this is set to". i.e. "Set to X".
            
            if (oldV == null) {
                // Formatting for New Segment
                val msg = "  • $label: $matchV"
                // For new segments, everything is Favorable usually, unless conflict (handled externally).
                // But wait, user said "You directly write what this is set to".
                // We mark it as NOT adverse by default here.
                diffItems.add(DiffItem(label, msg, false, false))
            } else if (oldV != matchV) {
                val isRestored = baseV != null && matchV == baseV
                val suffix = if (isRestored) " [恢复]" else ""
                
                // 🚀 [Fix] Display Diff against BASELINE if available (True Change from Baseline)
                // Exception: If Restored, we show Active -> Baseline (e.g. 3 -> 4) to indicate the action.
                // If Adverse/New, we show Baseline -> New (e.g. 4 -> 5) to show net effect.
                val startVal = if (baseV != null && !isRestored) baseV else oldV
                
                val msg = "  • $label: $startVal -> $matchV$suffix"
                
                // If it's a restoration, it's never adverse.
                val finalAdverse = if (isRestored) false else isAdverseItem
                diffItems.add(DiffItem(label, msg, finalAdverse, isRestored))
            }
        }

        val oldRange = old?.formatTimeRange()
        val oldLimit = old?.limitMins
        val oldDays = old?.let { TimeRuleManager.formatDays(it.daysOfWeek) }
        val oldStatus = old?.let { TimeRuleManager.formatEnabled(it.isEnabled) }

        if (old == null) {
             // For new segment, only show key properties
             add("时间范围", null, match.formatTimeRange(), null, false)
             add("休息配额", null, "${match.limitMins}分钟", null, false)
             add("生效", null, TimeRuleManager.formatDays(match.daysOfWeek), null, false)
             // Status is usually ON for new, so maybe skip status unless OFF?
        } else {
             add("时间调整", oldRange, match.formatTimeRange(), base?.formatTimeRange(), activeAnalysis.isRangeAdverse)
             add("休息时长", oldLimit, match.limitMins, base?.limitMins, activeAnalysis.isLimitAdverse)
             add("生效日期", oldDays, TimeRuleManager.formatDays(match.daysOfWeek), base?.let { TimeRuleManager.formatDays(it.daysOfWeek) }, activeAnalysis.isDaysAdverse)
             add("状态", oldStatus, TimeRuleManager.formatEnabled(match.isEnabled), base?.let { TimeRuleManager.formatEnabled(it.isEnabled) }, activeAnalysis.isStatusAdverse)
             // 次数上限也要列出来：混合保存按这份 diff 决定每个时段「今天生效什么」，
             // 缺了它，只改次数的时段会被当成没变化，加严的次数也不会立即生效。
             fun countText(seg: TimeSegment) = formatUsableCountLimit(seg.getEffectiveUsableCountLimitHalfUnits())
             add("次数上限", countText(old), countText(match), base?.let { countText(it) }, activeAnalysis.isCountLimitAdverse)
        }

        // 🆕 [TASK-04] listApps 变化文案（仅在有变化时出现；null ≠ 空列表）。
        if (old != null && old.listApps != match.listApps) {
            val listMsg = buildListAppsDiffMessage(old.listApps, match.listApps)
            if (listMsg != null) {
                val isRestoredList = base != null && match.listApps == base.listApps
                val isListAdv = if (isRestoredList) false else classifyListAppsAdverse(context, old, match)
                diffItems.add(DiffItem("本时段名单", listMsg, isListAdv, isRestoredList))
            }
        }

        // 🆕 [黑/白名单] 纯模式切换（拦截集合不变）时补一条非不利项。
        if (old != null && old.listApps == match.listApps && old.listMode != match.listMode) {
            val oldModeLabel = if (old.listMode == SegmentBlacklist.LIST_MODE_WHITELIST) "白名单" else "黑名单"
            val newModeLabel = if (match.listMode == SegmentBlacklist.LIST_MODE_WHITELIST) "白名单" else "黑名单"
            val modeMsg = "  • 名单模式: $oldModeLabel → $newModeLabel（仅显示方式，拦截范围不变）"
            diffItems.add(DiffItem("名单模式", modeMsg, false, false))
        }

        return diffItems
    }

    /** half-unit（0.5 次 = 1）显示成「次」：整数不带小数点。 */
    internal fun formatUsableCountLimit(halfUnits: Int): String {
        val times = halfUnits / 2.0
        return if (times % 1.0 == 0.0) "${times.toInt()}次" else "${times}次"
    }

    /**
     * 🆕 [TASK-04] 生成 listApps 变化的差异文案（风格对齐现有 diff 项）。
     * 返回 null 表示集合相同（仅顺序/重复变化）→ 不产生文案。
     */
    private fun buildListAppsDiffMessage(oldList: List<String>?, newList: List<String>?): String? {
        if (oldList == null && newList == null) return null
        if (oldList == null) {
            val n = newList ?: return null
            return "  • 本时段名单: 已与全局黑/白名单脱钩（独立管理 ${n.size} 应用）"
        }
        if (newList == null) {
            return "  • 本时段名单: 已恢复为跟随全局黑/白名单"
        }
        val oldSet = oldList.toSet()
        val newSet = newList.toSet()
        if (newSet == oldSet) return null
        if (newList.isEmpty()) {
            return "  • 本时段名单: 已清空本时段名单（不锁任何应用）"
        }
        if (newSet.containsAll(oldSet)) {
            return "  • 本时段名单 +${newSet.size - oldSet.size} 应用"
        }
        if (oldSet.containsAll(newSet)) {
            return "  • 本时段名单 −${oldSet.size - newSet.size} 应用"
        }
        val added = (newSet - oldSet).size
        val removed = (oldSet - newSet).size
        return "  • 本时段名单: 名单调整（增 $added、减 $removed）"
    }

    /**
     * 生成待生效规则的差异详情文本
     */
    fun getPendingDiffString(
        context: Context, 
        oldSegments: List<TimeSegment>, 
        newSegments: List<TimeSegment>, 
        baseSegments: List<TimeSegment>? = null, 
        pendingSegments: List<TimeSegment>? = null
    ): List<String> {
        val diffList = mutableListOf<String>()
        
        for (old in oldSegments) {
            val match = findMatchingOldSegment(old, newSegments)
            val base = if (baseSegments != null) findMatchingOldSegment(old, baseSegments) else null
            val pending = if (pendingSegments != null) findMatchingOldSegment(old, pendingSegments) else null

            if (match != null) {
                val items = getCategorizedSegmentDiff(context, old, match, base)
                if (items.isNotEmpty()) {
                    val header = "• [${old.formatTimeRange()}] ${old.name}"
                    val sb = StringBuilder()
                    sb.append("$header\n")
                    items.forEach { sb.append("${it.message}\n") }
                    diffList.add(sb.toString().trim())
                }
            } else {
                // Deletion
                diffList.add("❌ Deleted: [${old.formatTimeRange()}] ${old.name}")
            }
        }

        // Additions
        for (new in newSegments) {
            val exists = oldSegments.any {
                val timeMatch = it.startHour == new.startHour && it.startMinute == new.startMinute &&
                    it.endHour == new.endHour && it.endMinute == new.endMinute
                val nameMatch = it.name.isNotBlank() && it.name == new.name
                val dayOverlap = isDaysOverlapping(it.daysOfWeek, new.daysOfWeek)
                (timeMatch || nameMatch) && dayOverlap
            }
            if (!exists) {
                diffList.add("➕ New: [${new.formatTimeRange()}] ${new.name}")
            }
        }

        return diffList
    }

    fun isCycleReduction(context: Context, newSegments: List<TimeSegment>): Boolean {
        val oldSegments = TimeRuleManager.getTimeSegments(context)
        if (oldSegments.size != newSegments.size) return false // Structural change not specific to cycle
        
        for (i in oldSegments.indices) {
            val old = oldSegments[i]
            val new = newSegments[i]
            
            val oldDays = old.daysOfWeek?.size ?: 7
            val newDays = new.daysOfWeek?.size ?: 7
            
            if (newDays < oldDays) return true
        }
        return false
    }

    fun isAdverseRelativeToBaseline(context: Context, newSegments: List<TimeSegment>): Boolean {
        val basSegments = TimeRuleManager.getBaselineSegments(context) ?: TimeRuleManager.getTimeSegments(context)
        val activeSegments = TimeRuleManager.getTimeSegments(context)
        
        // 🚀 [Fix] Check for internal overlaps in new segments
        if (TimeRuleManager.checkSegmentOverlap(newSegments)) return true

        // 🚀 Check if ANY single segment modification is adverse vs Baseline
        for (newSeg in newSegments) {
            val baseSegRaw = findMatchingOldSegment(newSeg, basSegments)
            // 🚀 [Fix] Skip adverse check if base segment is "Fresh" (Regret Period)
            if (baseSegRaw != null && isSegmentFresh(baseSegRaw)) continue

            val baseSeg = if (baseSegRaw != null && isDaysOverlapping(baseSegRaw.daysOfWeek, newSeg.daysOfWeek)) {
                baseSegRaw
            } else {
                null
            }
            if (baseSeg == null) {
                // New segment added. Check for conflict with Baseline.
                if (basSegments.any {
                        // 🚀 [Fix] Skip conflict check if existing baseline segment is "Fresh"
                        !isSegmentFresh(it) &&
                        isDaysOverlapping(it.daysOfWeek, newSeg.daysOfWeek) &&
                            calculateOverlapMinutes(newSeg, it) > 0
                    }) {
                    return true // Conflict with baseline is adverse (Delayed)
                }
            } else {
                if (!isDaysOverlapping(baseSeg.daysOfWeek, newSeg.daysOfWeek)) {
                    continue
                }
                // 🆕 [黑/白名单] 名单不利对 active 判定：纯模式切换(拦截集合不变)不应判不利。
                val activeRaw = findMatchingOldSegment(newSeg, activeSegments)
                val activeRef = if (activeRaw != null && isDaysOverlapping(activeRaw.daysOfWeek, newSeg.daysOfWeek)) activeRaw else null
                if (isSingleSegmentAdverse(baseSeg, newSeg, context, activeRef)) return true
            }
        }
        
        // 🚀 Check for deletions (Baseline had it, New does not)
        for (baseSeg in basSegments) {
            // 🚀 [Fix] Skip deletion check if base segment is "Fresh" (Regret Period)
            if (isSegmentFresh(baseSeg)) continue

            val match = findMatchingOldSegment(baseSeg, newSegments)
            val hasOverlappingDayMatch = match != null &&
                isDaysOverlapping(match.daysOfWeek, baseSeg.daysOfWeek)
            if (!hasOverlappingDayMatch) return true
        }
        
        return false
    }

}
