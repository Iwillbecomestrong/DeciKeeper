package com.decikeeper.app

import android.content.Context
import com.decikeeper.app.data.AnalysisResult
import com.decikeeper.app.data.SegmentAdverseAnalysis
import com.decikeeper.app.data.SegmentBlacklist
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config








/**
 * TASK-04 (segment-blacklist) RED — listApps 纳入延迟生效体系（AdverseAnalysis）。
 *
 * SPEC §2.8 / §6 S8 / §3 不变量：
 *   - 有效名单增加应用 = 加严 = 立即生效；
 *   - 有效名单减少 / 清空 / 恢复为全局(null) = 放松 = 次日生效（红线 3）。
 *
 * 本测试只对 TimeRuleManager 的名单 diff 分类、isSingleSegmentEqual、
 * pending 快照往返、baseline 快照保留 发表契约，不动 MonitorService /
 * SegmentBlacklist 判定与 billing（那是 TASK-02/03）。
 *
 * 全部用例仅使用既有公开 API（analyzeAndSaveTimeSegments / isSingleSegmentEqual /
 * saveBaselineSnapshot / getBaselineSegments / segmentsToJson /
 * parseSegmentsFromJson），保证在实现前 RED（断言失败）而非编译失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SegmentListAdverseTest {

    private lateinit var context: Context

    private val rulesPrefs get() =
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE)

    private val pendingPrefs get() =
        context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)

    private val baselinePrefs get() =
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)

    private val ledgerPrefs get() =
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rulesPrefs.edit().clear().commit()
        pendingPrefs.edit().clear().commit()
        baselinePrefs.edit().clear().commit()
        ledgerPrefs.edit().clear().commit()
        CreditManager.invalidateCache()
        TimeRuleManager.invalidateSegmentsCache()
        // 结束适应期（非适应期才走 AdverseAnalysis 的有利立即/不利次日分流）。
        ledgerPrefs.edit().putBoolean("has_manually_ended_adaptation", true).commit()
        // refresh=06:00，测试段落在 [07:00, 24:00) 内，不跨 refresh、不跨 00:00。
        TimeRuleManager.saveRefreshTime(context, "06:00")
    }

    // ────────────────────────────────────────────────────────────────
    //  fixture helpers
    // ────────────────────────────────────────────────────────────────

    private fun makeSegment(listApps: List<String>?): TimeSegment {
        return TimeSegment(
            startHour = 9, startMinute = 0,
            endHour = 12, endMinute = 0,
            limitMins = 30,
            name = "上午",
            daysOfWeek = null,
            isEnabled = true,
            listApps = listApps
        )
    }

    private fun writeSegments(segments: List<TimeSegment>) {
        rulesPrefs.edit().putString("segments_json", TimeRuleManager.segmentsToJson(segments)).commit()
        TimeRuleManager.invalidateSegmentsCache()
    }

    private fun writeBaselineSegments(segments: List<TimeSegment>) {
        baselinePrefs.edit().putString("segments_json", TimeRuleManager.segmentsToJson(segments)).commit()
    }

    private fun seedBaselineAndActive(seg: TimeSegment) {
        writeSegments(listOf(seg))
        writeBaselineSegments(listOf(seg))
    }

    private fun assertDelayed(result: AnalysisResult) {
        assertTrue("expected Mixed (delayed), got $result", result is AnalysisResult.Mixed)
        val mixed = result as AnalysisResult.Mixed
        assertTrue("delayedChanges should be non-empty", mixed.delayedChanges.isNotEmpty())
    }

    // ────────────────────────────────────────────────────────────────
    //  S8 生效分类：加严立即 / 放松次日
    // ────────────────────────────────────────────────────────────────

    @Test
    fun onlyIncrease_isFavorable_immediate() {
        seedBaselineAndActive(makeSegment(listOf("a", "b")))

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(
            context, listOf(makeSegment(listOf("a", "b", "c")))
        )

        assertTrue("名单增加应立即生效（有利）", result is AnalysisResult.AllImmediate)
        TimeRuleManager.invalidateSegmentsCache()
        assertEquals(setOf("a", "b", "c"), TimeRuleManager.getTimeSegments(context)[0].listApps!!.toSet())
    }

    @Test
    fun onlyDecrease_isAdverse_delayed() {
        seedBaselineAndActive(makeSegment(listOf("a", "b")))

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(
            context, listOf(makeSegment(listOf("a")))
        )

        assertDelayed(result)
        // 放松必须延迟：active 仍保持加严名单。
        TimeRuleManager.invalidateSegmentsCache()
        assertEquals(setOf("a", "b"), TimeRuleManager.getTimeSegments(context)[0].listApps!!.toSet())
    }

    @Test
    fun clearList_isAdverse_delayed() {
        seedBaselineAndActive(makeSegment(listOf("a", "b")))

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(
            context, listOf(makeSegment(emptyList()))
        )

        assertDelayed(result)
        TimeRuleManager.invalidateSegmentsCache()
        assertEquals(setOf("a", "b"), TimeRuleManager.getTimeSegments(context)[0].listApps!!.toSet())
    }

    @Test
    fun restoreToNull_isAdverse_delayed() {
        seedBaselineAndActive(makeSegment(listOf("a", "b")))

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(
            context, listOf(makeSegment(null))
        )

        assertDelayed(result)
        TimeRuleManager.invalidateSegmentsCache()
        assertEquals(setOf("a", "b"), TimeRuleManager.getTimeSegments(context)[0].listApps!!.toSet())
    }

    @Test
    fun mixedAddRemove_isAdverse_delayed() {
        // 旧 ["a","b"] → 新 ["b","c"]：删 "a" 增 "c" → 混合 → 不利（延迟）。
        seedBaselineAndActive(makeSegment(listOf("a", "b")))

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(
            context, listOf(makeSegment(listOf("b", "c")))
        )

        assertDelayed(result)
        TimeRuleManager.invalidateSegmentsCache()
        assertEquals(setOf("a", "b"), TimeRuleManager.getTimeSegments(context)[0].listApps!!.toSet())
    }

    // ────────────────────────────────────────────────────────────────
    //  isSingleSegmentEqual：仅名单变化 = 段变化
    // ────────────────────────────────────────────────────────────────

    @Test
    fun equal_listAppsDifferent_false() {
        val s1 = makeSegment(listOf("a"))
        val s2 = makeSegment(listOf("a", "b"))
        assertFalse("listApps 不同应判定为不相等", SegmentAdverseAnalysis.isSingleSegmentEqual(s1, s2))
    }

    @Test
    fun equal_listAppsSame_true() {
        val s1 = makeSegment(listOf("a", "b"))
        val s2 = makeSegment(listOf("a", "b"))
        assertTrue("listApps 相同应判定为相等", SegmentAdverseAnalysis.isSingleSegmentEqual(s1, s2))
    }

    @Test
    fun equal_nullVsEmpty_false() {
        val s1 = makeSegment(null)
        val s2 = makeSegment(emptyList())
        assertFalse("null（跟随全局）与空列表（不锁任何）语义不同，不得相等", SegmentAdverseAnalysis.isSingleSegmentEqual(s1, s2))
    }

    // ────────────────────────────────────────────────────────────────
    //  pending / baseline 快照
    // ────────────────────────────────────────────────────────────────

    @Test
    fun adverseDecrease_pendingJson_containsListApps_roundTrip() {
        seedBaselineAndActive(makeSegment(listOf("a", "b")))

        TimeRuleManager.analyzeAndSaveTimeSegments(
            context, listOf(makeSegment(listOf("a")))
        )

        val pendingJson = pendingPrefs.getString("segments_json", null)
        assertNotNull("不利变更应写入 pending segments_json", pendingJson)
        assertTrue("pending JSON 应含 listApps 字段", pendingJson!!.contains("listApps"))

        val parsed = TimeRuleManager.parseSegmentsFromJson(pendingJson)
        assertEquals(1, parsed.size)
        assertEquals(listOf("a"), parsed[0].listApps)
    }

    @Test
    fun baselineSnapshot_preserves_listApps() {
        writeSegments(listOf(makeSegment(listOf("a", "b"))))

        TimeRuleManager.saveBaselineSnapshot(context)

        val baseline = TimeRuleManager.getBaselineSegments(context)
        assertNotNull(baseline)
        assertEquals(1, baseline!!.size)
        assertEquals(listOf("a", "b"), baseline[0].listApps)
    }

    // ────────────────────────────────────────────────────────────────
    //  脱钩首次保存（旧 null → 新非 null）：以全局黑名单为基准
    // ────────────────────────────────────────────────────────────────

    // 注：脱钩判定通过 classifyListAppsAdverseCore 显式注入 oldEffective/selectable 测试
    // （生产包装器用 PackageManager.hasLaunchIntent 过滤；Robolectric 无法解析 launch
    // intent，故不在此走 E2E，避免假绿）。

    @Test
    fun detachFromGlobal_superset_isFavorable_immediate() {
        val adverse = SegmentAdverseAnalysis.classifyListAppsAdverseCore(
            makeSegment(null),
            makeSegment(listOf("com.example.alpha", "com.example.beta", "com.example.gamma")),
            oldEffective = setOf("com.example.alpha", "com.example.beta"),
            selectable = { it } // 全部可展示
        )
        assertTrue("新名单 ⊇ 全局黑名单 → 有利立即", !adverse)
    }

    @Test
    fun detachFromGlobal_subset_isAdverse_delayed() {
        val adverse = SegmentAdverseAnalysis.classifyListAppsAdverseCore(
            makeSegment(null),
            makeSegment(listOf("com.example.alpha")),
            oldEffective = setOf("com.example.alpha", "com.example.beta"),
            selectable = { it } // 全部可展示
        )
        assertTrue("新名单 ⊂ 全局黑名单（主动移除 beta）→ 放松 → 不利延迟", adverse)
    }

    /**
     * [FIX 2026-08-14 用户报告] 全局黑名单含无法展示的包（未安装/无启动入口）时，
     * 在全局基础上添加应用 = 加严 = 立即生效；不可展示包不算"被移除"。
     */
    @Test
    fun detachFromGlobal_staleHiddenApp_notCountedAsRemoval() {
        val adverse = SegmentAdverseAnalysis.classifyListAppsAdverseCore(
            makeSegment(null),
            makeSegment(listOf("com.example.alpha", "com.example.beta", "com.example.gamma")),
            oldEffective = setOf("com.example.alpha", "com.example.beta", "com.removed.stale"),
            selectable = { eff -> eff.filter { it.startsWith("com.example.") }.toSet() } // stale 不可展示
        )
        assertTrue("stale 包不可展示不能算被移除；添加 gamma → 加严 → 有利立即", !adverse)
    }

    @Test
    fun restoreToGlobal_fromNonEmptyList_isAdverse() {
        val adverse = SegmentAdverseAnalysis.classifyListAppsAdverseCore(
            makeSegment(listOf("com.example.alpha")),
            makeSegment(null),
            oldEffective = emptySet(),
            selectable = { it }
        )
        assertTrue("恢复跟随全局（旧名单非空）= 放松 → 不利延迟", adverse)
    }

    @Test
    fun modeOnlySwitch_isNotAdverse() {
        val old = TimeSegment(startHour = 8, startMinute = 0, endHour = 12, endMinute = 0, limitMins = 30,
            name = "x", listApps = listOf("com.a", "com.b"), listMode = SegmentBlacklist.LIST_MODE_BLACKLIST)
        val new = old.copy(listMode = SegmentBlacklist.LIST_MODE_WHITELIST)
        assertFalse("纯模式切换（拦截集合不变）不得判为不利",
            SegmentAdverseAnalysis.analyzeSingleSegmentChange(old, new, null).isAnyAdverse())
    }

    @Test
    fun isSingleSegmentEqual_modeDifference_isNotEqual() {
        val a = TimeSegment(startHour = 8, startMinute = 0, endHour = 12, endMinute = 0, limitMins = 30,
            name = "x", listApps = listOf("com.a"), listMode = SegmentBlacklist.LIST_MODE_BLACKLIST)
        val b = a.copy(listMode = SegmentBlacklist.LIST_MODE_WHITELIST)
        assertFalse("listMode 不同应判定为不相等", SegmentAdverseAnalysis.isSingleSegmentEqual(a, b))
    }
}
