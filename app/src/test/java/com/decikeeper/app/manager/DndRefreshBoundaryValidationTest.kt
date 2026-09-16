package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.TimeRuleManager
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config





/**
 * TASK WP-01-T02 (v3 RED) — DND refresh time 边界 / 原子拒绝契约。
 *
 * v3 SPEC §3.5 / §3.6 衍生三条 DA 契约：DA-B（业务日归属）/ DA-D1（时段端点合法
 * 性）/ DA-D2（refresh time 切坏现有时段判定）。本测试只发表 RED 契约，不动任
 * 何生产代码——后续 WP-02/03 由生产者发布对应校验实现，本测试转为 GREEN。
 *
 * ─── v3 RED 关键变更（相对于 v2） ───────────────────────────────────────
 *   v2 写过"end <= start / 跨 00:00 段一律拒绝"用例。v3 用户拍板后
 *   (§3.5 A footnote 2026-07-22 + §3.6 footnote 2026-07-22): IronWill 原生
 *   支持跨午夜段(`isCrossDay = endMins <= startMins`,refresh ∈ [06:00, 10:00]
 *   物理上永远不命中跨午夜段内),因此:
 *
 *     - "end <= start" 作为独立 reject case 不写(原 v2 #6 删除)
 *     - 跨午夜段本身视为合法(本测试用 cross-midnight 段做 accept 反例)
 *     - DA-D1 仅在 `start < refresh < end` 严格内含时拒存
 *
 * ─── refresh fixture / 配置 / 回退 三层语义 ──────────────────────────────
 *   - 测试 fixture 锚点:refresh time = 06:00(testRefreshTime)
 *   - 用户配置 authority:07:00(userConfigured, 仅作语义对照证明测试不耦合)
 *   - 代码 fallback:08:00(`TimeRuleManager.kt:3187` 默认值, 仅作语义对照)
 *
 *   本测试绝不依赖具体回退值,所有断言只对测试 fixture 06:00 内部一致
 *   性成立,保证任意时刻用户配置/回退变更不会破坏契约。
 *
 * ─── 4 测试方法集 / 17 cases ─────────────────────────────────────────────
 *   #1 refresh_minus_equal_plus_attribution (DA-B / V1-V3)        — 3 cases
 *   #2 segment_refresh_legality            (DA-D1 / V13)         — 7 cases
 *   #3 refresh_against_existing_segments   (DA-D2 / V14)         — 5 cases
 *   #4 rejected_save_is_atomic            (DA-D1/DA-D2 atomicity) — 2 cases
 *
 * ─── 生产者接口契约期望（WP-03 / WP-04 将发布） ──────────────────────────
 *   `analyzeAndSaveTimeSegments(context, segments)` 对严格跨 refresh 的
 *   时段必须 reject,其他情况接受。
 *   `saveRefreshTime(context, newRefresh)` 对切坏现有 DND 时段的刷新时间
 *   必须 reject。
 *   拒绝路径必须原子:active/pending prefs、refresh time、segments list、
 *   同步开关、change-log 全部不变。
 *
 *   当前 production 既不拒绝也未实现原子性,因此本测试断言全部 RED。
 *
 * ─── 不得接触清单 ───────────────────────────────────────────────────────
 *   - 不动 app/src/main/（生产代码区域）；
 *   - 不动 CreditManager / TimeRuleManager / TimeConfigFragment /
 *     GlobalParamsActivity 生产代码；
 *   - 不写"end <= start"独立 reject case(已被 v3 footnote 整体删除);
 *   - 不写跨业务天段(用 refresh=06:00 在 [06:00, 24:00) 不跨 refresh 的
 *     常规段,跨午夜段作为 accept 反例仅 1 例)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DndRefreshBoundaryValidationTest {

    private lateinit var context: Context

    /** 主 prefs（decikeeper_rules）—— active 段、refresh time。 */
    private val rulesPrefs get() =
        context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

    /** Pending prefs（decikeeper_rules_pending）。 */
    private val pendingPrefs get() =
        context.getSharedPreferences("decikeeper_rules_pending", Context.MODE_PRIVATE)

    /** Baseline prefs（decikeeper_rules_baseline）—— 仅读。 */
    private val baselinePrefs get() =
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)

    /** 测试锚点 refresh time = 06:00；DA-B / DA-D1 / DA-D2 的固定窗口。 */
    private val testRefreshTime = "06:00"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 每个用例从干净 prefs 起步,避免跨用例污染。
        rulesPrefs.edit().clear().commit()
        pendingPrefs.edit().clear().commit()
        baselinePrefs.edit().clear().commit()
        TimeRuleManager.invalidateSegmentsCache()
        TimeRuleManager.saveRefreshTime(context, testRefreshTime)
    }

    // ────────────────────────────────────────────────────────────────
    //  通用 fixture helpers
    // ────────────────────────────────────────────────────────────────

    /**
     * 把 segments 写入 prefs 并 invalidate 缓存。验证 DA-D2 时通常一段
     * 用来作为「现有 DND 时段」前置状态。
     */
    private fun writeSegments(segments: List<TimeSegment>) {
        val json = TimeRuleManager.segmentsToJson(segments)
        rulesPrefs.edit().putString("segments_json", json).commit()
        TimeRuleManager.invalidateSegmentsCache()
    }

    private fun writeBaselineSegments(segments: List<TimeSegment>) {
        val json = TimeRuleManager.segmentsToJson(segments)
        baselinePrefs.edit().putString("segments_json", json).commit()
    }

    /**
     * 段 [startH:startM, endH:endM) 必须 ⊂ [06:00, 24:00) 内(不跨 refresh
     * 06:00、不跨 00:00),且不依赖 endMins > startMins(允许跨午夜段在
     * 此方法外另写)。
     */
    private fun nonCrossRefreshSegment(
        startH: Int, startM: Int, endH: Int, endM: Int,
        limitMins: Int = 30, name: String = "测试段",
        isEnabled: Boolean = true
    ): TimeSegment = TimeSegment(
        startHour = startH, startMinute = startM,
        endHour = endH, endMinute = endM,
        limitMins = limitMins, name = name,
        isEnabled = isEnabled
    )

    private fun refreshMinutes(refresh: String = testRefreshTime): Int {
        val parts = refresh.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    private fun segmentMinutes(seg: TimeSegment): Pair<Int, Int> =
        Pair(seg.startHour * 60 + seg.startMinute, seg.endHour * 60 + seg.endMinute)

    private fun strictCrossesRefresh(seg: TimeSegment, refresh: String = testRefreshTime): Boolean {
        val (startTotal, endTotal) = segmentMinutes(seg)
        val refreshTotal = refreshMinutes(refresh)
        // 仅当端点严格在两侧、且 refresh 物理落入段内 [start, end) 中间
        // (start < refresh < end) 才算跨 refresh。endMins <= startMins
        // 的跨午夜段[start, 24:00) ∪ [00:00, end),refresh 在 [06:00, 10:00]
        // 永远不在段内 → 不构成 strict crossing。
        return startTotal < refreshTotal && endTotal > refreshTotal &&
            endTotal > startTotal
    }

    // ────────────────────────────────────────────────────────────────
    //  #1 DA-B / V1-V3 — refresh 前后 / 当前业务日归属（3 cases）
    // ────────────────────────────────────────────────────────────────

    /**
     * DA-B.refresh_minus：refresh time 之前 last second 仍在 previous 业务日。
     *
     * 锚 refresh = 06:00,fake now = 今天 05:59:59。期望：业务日字符串 ≠ 今
     * 天物理日(应推到前一天)。
     */
    @Test
    @Config(qualifiers = "w421dp-h731dp")
    fun refresh_minus_attribution_returnsPreviousBusinessDate() {
        val refreshTotal = refreshMinutes() // 06:00 = 360
        val fakeNowMs = todayAtMinutes(refreshTotal - 1) // today 05:59
        val biz = CreditManager.getBusinessDateStringAt(context, fakeNowMs)
        val physicalToday = CreditManager.getPhysicalDateStringAt(todayAtMinutes(12 * 60))

        assertNotEquals(
            "DA-B：refresh=06:00 之前(fake 05:59)仍属 previous 业务日，" +
                "应 ≠ 今天物理日。biz=$biz physicalToday=$physicalToday",
            physicalToday, biz
        )
    }

    /**
     * DA-B.refresh_equality：恰好 refresh time (06:00) → 应属于当前业务日。
     */
    @Test
    @Config(qualifiers = "w422dp-h731dp")
    fun refresh_equality_attribution_returnsCurrentBusinessDate() {
        val refreshTotal = refreshMinutes()
        val fakeNowMs = todayAtMinutes(refreshTotal) // today 06:00 exactly
        val biz = CreditManager.getBusinessDateStringAt(context, fakeNowMs)
        val physicalToday = CreditManager.getPhysicalDateStringAt(todayAtMinutes(refreshTotal))

        assertEquals(
            "DA-B：fake now == refresh=06:00 → 当前业务日 == 今天物理日",
            physicalToday, biz
        )
    }

    /**
     * DA-B.refresh_plus：refresh time 之后任意分钟 → 当前业务日。
     */
    @Test
    @Config(qualifiers = "w423dp-h731dp")
    fun refresh_plus_attribution_returnsCurrentBusinessDate() {
        val refreshTotal = refreshMinutes()
        val fakeNowMs = todayAtMinutes(refreshTotal + 60) // today 07:00
        val biz = CreditManager.getBusinessDateStringAt(context, fakeNowMs)
        val physicalToday = CreditManager.getPhysicalDateStringAt(fakeNowMs)

        assertEquals(
            "DA-B：fake now = 07:00 > refresh=06:00 → 当前业务日 == 今天物理日",
            physicalToday, biz
        )
    }

    // ────────────────────────────────────────────────────────────────
    //  #2 DA-D1 / V13 — 时段端点合法性（7 cases）
    //
    //  设计：`analyzeAndSaveTimeSegments` 应只有 `start < refresh < end`
    //  严格内含拒绝,其余场景正常保存。RED 期望:strict-crossing 段被
    //  生产 accept(契约未实现),合法段被生产 accept,跨午夜段 accept。
    // ────────────────────────────────────────────────────────────────

    /**
     * Case 1 — segment 不跨 refresh(段 ⊂ [refresh, 24:00))→ ACCEPT。
     * fixture：08:00-22:00, refresh=06:00 不在段内。
     */
    @Test
    @Config(qualifiers = "w431dp-h731dp")
    fun segment_legality_accept_when_afterRefresh() {
        val seg = nonCrossRefreshSegment(8, 0, 22, 0, limitMins = 60, name = "after-refresh 段")
        val beforeJson = rulesPrefs.getString("segments_json", null)

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(seg))

        val afterJson = rulesPrefs.getString("segments_json", null)
        val active = TimeRuleManager.getTimeSegments(context)
        val accept = active.size == 1 && active[0].startHour == 8 && active[0].endHour == 22

        assertTrue(
            "DA-D1 Case 1：不跨 refresh 段(08:00-22:00 ⊂ [06:00, 24:00))" +
                "应被 accept 并写入 active。active=$active " +
                "beforeJson==afterJson? ${beforeJson == afterJson}",
            accept
        )
        assertNotNull("DA-D1 Case 1：analyzeAndSave 不应返回 null", result)
    }

    /**
     * Case 2 — start 端点 = refresh(段 ⊂ [refresh, 24:00))→ ACCEPT。
     * fixture：06:00-22:00, start 端点 = refresh。
     */
    @Test
    @Config(qualifiers = "w432dp-h731dp")
    fun segment_legality_accept_when_startEqualsRefresh() {
        val seg = nonCrossRefreshSegment(6, 0, 22, 0, limitMins = 45, name = "start==refresh 段")
        val result = TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(seg))

        val active = TimeRuleManager.getTimeSegments(context)
        val accept = active.size == 1 && active[0].startHour == 6 && active[0].endHour == 22

        assertTrue(
            "DA-D1 Case 2：start==refresh=06:00 段(06:00-22:00 ⊂ [refresh, 24:00))" +
                "应被 accept。active=$active",
            accept
        )
        assertNotNull("DA-D1 Case 2：analyzeAndSave 不应返回 null", result)
    }

    /**
     * Case 3 — refresh 落在段中段(典型 06:30-22:00 中的 07:00 之类) →
     *   REJECT。refresh=06:00 时 05:30-22:00 含 refresh → 拒绝。
     */
    @Test
    @Config(qualifiers = "w433dp-h731dp")
    fun segment_legality_reject_when_refreshStrictlyInside() {
        val seg = nonCrossRefreshSegment(5, 30, 22, 0, limitMins = 50, name = "内含 refresh 段")
        // 拍下 prefs / segments 调用前快照,用于断言"拒绝路径 atomic"。
        val activeBefore = TimeRuleManager.getTimeSegments(context).size
        val pendingBeforeKeys = pendingPrefs.all.keys.toSet()
        val refreshBefore = TimeRuleManager.getRefreshTime(context)

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(seg))

        val activeAfter = TimeRuleManager.getTimeSegments(context).size
        val refreshAfter = TimeRuleManager.getRefreshTime(context)

        // RED 期望(校验未实现时)：内含 refresh 的段被错误地 accept。
        // 当前 production 完全不校验,accept 路径直接进 saveTimeSegments,
        // segments_json 写入,active=1。
        // 该 case 的 RED 不是 fail 调用本身,而是 active 数量 + 内含段被误收。
        //
        // 契约断言：DA-D1 内含 refresh 必须拒绝,active 段数不应增加,且
        // refresh time 不应被改写。
        assertEquals(
            "DA-D1 Case 3：strict crossing(05:30-22:00 内含 refresh=06:00)" +
                "应被 REJECT，active 段数保持 $activeBefore 不变。was=$activeAfter",
            activeBefore, activeAfter
        )
        assertEquals(
            "DA-D1 Case 3：reject 后 refresh time 必须保持 $refreshBefore 不变。was=$refreshAfter",
            refreshBefore, refreshAfter
        )
        assertNotNull("DA-D1 Case 3：analyzeAndSave 不应返回 null(result=可能 NoChange 或自定义拒存)", result)
    }

    /**
     * Case 4 — end 端点 = refresh（段 ⊂ [00:00, refresh)）→ ACCEPT。
     * fixture：00:00-06:00, end 端点 = refresh。refresh 物理不在段中。
     */
    @Test
    @Config(qualifiers = "w434dp-h731dp")
    fun segment_legality_accept_when_endEqualsRefresh() {
        // 00:00-06:00 段：end=refresh=06:00, start>=0, refresh 严格不内含。
        val seg = TimeSegment(
            startHour = 0, startMinute = 0,
            endHour = 6, endMinute = 0,
            limitMins = 30, name = "end==refresh 段",
            isEnabled = true
        )

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(seg))
        val active = TimeRuleManager.getTimeSegments(context)
        val accept = active.size == 1 && active[0].startHour == 0 && active[0].endHour == 6

        assertTrue(
            "DA-D1 Case 4：end==refresh=06:00 段(00:00-06:00 ⊂ [00:00, refresh))" +
                "应被 accept。active=$active",
            accept
        )
        assertNotNull("DA-D1 Case 4：analyzeAndSave 不应返回 null", result)
    }

    /**
     * Case 5 — refresh in middle (start < refresh < end, 同 Case 3 但换一组
     * 数据)→ REJECT。fixture：00:00-22:00 内含 refresh=06:00,跨 refresh。
     */
    @Test
    @Config(qualifiers = "w435dp-h731dp")
    fun segment_legality_reject_when_refreshInMiddleOfLongSegment() {
        // 0-22 全天段,内含 refresh=06:00,应被 REJECT。
        val seg = TimeSegment(
            startHour = 0, startMinute = 0,
            endHour = 22, endMinute = 0,
            limitMins = 200, name = "全天段 0-22 内含 refresh",
            isEnabled = true
        )
        // 用一组合法 baseline segment 作 atomic 锚。
        val baseline = nonCrossRefreshSegment(8, 0, 22, 0, name = "baseline 8-22")
        writeSegments(listOf(baseline))
        writeBaselineSegments(listOf(baseline))
        val activeBefore = TimeRuleManager.getTimeSegments(context)
        val refreshBefore = TimeRuleManager.getRefreshTime(context)

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(seg))

        val activeAfter = TimeRuleManager.getTimeSegments(context)

        assertEquals(
            "DA-D1 Case 5：strict crossing(00:00-22:00 内含 refresh=06:00)" +
                "应被 REJECT，active 段配置必须保持。was=$activeAfter",
            activeBefore, activeAfter
        )
        assertEquals(
            "DA-D1 Case 5：reject 后 refresh time 应保持 $refreshBefore",
            refreshBefore, TimeRuleManager.getRefreshTime(context)
        )
        assertNotNull("DA-D1 Case 5：analyzeAndSave 不应返回 null", result)
    }

    /**
     * Case 6 — 跨午夜段本身合法(DA-D1 不拒存跨午夜段)。
     * fixture：22:00-02:00 跨午夜段,refresh=06:00 物理不在段内(06:00
     * > 02:00 且 06:00 < 22:00 → strictCrossesRefresh 应返回 false)。
     * v3 SPEC §3.5 A footnote 拍板:跨午夜段本身合法。
     */
    @Test
    @Config(qualifiers = "w436dp-h731dp")
    fun segment_legality_accept_crossMidnightSegmentItself() {
        val seg = TimeSegment(
            startHour = 22, startMinute = 0,
            endHour = 2, endMinute = 0,  // 跨午夜: endMins <= startMins
            limitMins = 60, name = "跨午夜段 22-02",
            isEnabled = true
        )

        // sanity：strictCrossesRefresh 检查应判 false(因为 refresh 物理不在
        // 段内,跨午夜段表达 [22:00, 24:00) ∪ [00:00, 02:00),refresh=06:00
        // 不在段内)。
        assertTrue(
            "DA-D1 Case 6 前置：跨午夜段 22:00-02:00 不应被 strictCrossesRefresh 误判",
            !strictCrossesRefresh(seg)
        )

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(seg))
        val active = TimeRuleManager.getTimeSegments(context)

        val accept = active.size == 1 && active[0].startHour == 22 && active[0].endHour == 2
        assertTrue(
            "DA-D1 Case 6：跨午夜段 22:00-02:00（refresh=06:00 物理不在段内)" +
                "应被 accept，本身合法。active=$active",
            accept
        )
        assertNotNull("DA-D1 Case 6：analyzeAndSave 不应返回 null", result)
    }

    /**
     * Case 7 — gap refresh(段 [start, end) 在 refresh 之前,refresh 落在
     * gap)→ ACCEPT。fixture：08:00-12:00 一个段,以及 13:00-22:00 一
     * 个段,refresh=06:00 落在 00:00-08:00 的空档里。
     */
    @Test
    @Config(qualifiers = "w437dp-h731dp")
    fun segment_legality_accept_refreshInGapBetweenSegments() {
        val morning = nonCrossRefreshSegment(8, 0, 12, 0, name = "morning 8-12")
        val evening = nonCrossRefreshSegment(13, 0, 22, 0, name = "evening 13-22")
        val gapAccept = !strictCrossesRefresh(morning) && !strictCrossesRefresh(evening)

        assertTrue(
            "DA-D1 Case 7 前置：两段都不 strictCrossesRefresh,refresh=06:00 落在 gap",
            gapAccept
        )

        val result = TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(morning, evening))
        val active = TimeRuleManager.getTimeSegments(context)

        val accept = active.size == 2 &&
            active.any { it.startHour == 8 && it.endHour == 12 } &&
            active.any { it.startHour == 13 && it.endHour == 22 }

        assertTrue(
            "DA-D1 Case 7：refresh 落在 gap(00:00-08:00)的两段应被 accept。" +
                "active=$active",
            accept
        )
        assertNotNull("DA-D1 Case 7：analyzeAndSave 不应返回 null", result)
    }

    // ────────────────────────────────────────────────────────────────
    //  #3 DA-D2 / V14 — refresh time 切坏现有 DND 时段（5 cases）
    //
    //  设计：`saveRefreshTime` 应只在 refresh 切坏现有段(落在某段严格内部
    //  start < newRefresh < end)时拒存,其他场景正常保存。RED:saveRefreshTime
    //  当前不做任何验证,直接写入 prefs,因此 strict-interior 用例会被错误
    //  接受但契约断言会 RED。
    // ────────────────────────────────────────────────────────────────

    /**
     * Case 1 — refresh 落在 gap(没有任何段覆盖)→ ACCEPT。
     * fixture：08:00-12:00 + 13:00-22:00,saveRefreshTime(11:00) 落在
     * 11:00 附近的 12:00-13:00 gap(实际 11:00 在 morning 段内部 → 失败)。
     * 改用：08:00-10:00 + 14:00-22:00,saveRefreshTime(12:00) 落在
     * 10:00-14:00 gap 中。
     */
    @Test
    @Config(qualifiers = "w441dp-h731dp")
    fun refresh_legality_accept_when_inGap() {
        writeSegments(listOf(
            nonCrossRefreshSegment(8, 0, 10, 0, name = "gap-a 8-10"),
            nonCrossRefreshSegment(14, 0, 22, 0, name = "gap-b 14-22")
        ))
        val proposedRefresh = "12:00" // 在 10:00-14:00 gap 中
        val beforeRefresh = TimeRuleManager.getRefreshTime(context) // 06:00

        TimeRuleManager.saveRefreshTime(context, proposedRefresh)

        val afterRefresh = TimeRuleManager.getRefreshTime(context)
        assertEquals(
            "DA-D2 Case 1：refresh=12:00 落在 gap(10:00-14:00)→ 应 accept。" +
                "before=$beforeRefresh after=$afterRefresh",
            proposedRefresh, afterRefresh
        )
    }

    /**
     * Case 2 — refresh 落在段端点(start == newRefresh)→ ACCEPT。
     * fixture：08:00-22:00 段,saveRefreshTime(08:00) 与 start 端点重合,
     * refresh 不切坏段。
     */
    @Test
    @Config(qualifiers = "w442dp-h731dp")
    fun refresh_legality_accept_when_atSegmentStart() {
        writeSegments(listOf(
            nonCrossRefreshSegment(8, 0, 22, 0, name = "single 段 8-22")
        ))
        val proposedRefresh = "08:00" // 与段 start 端点重合

        TimeRuleManager.saveRefreshTime(context, proposedRefresh)

        assertEquals(
            "DA-D2 Case 2：refresh=08:00 == 段 start 端点 → 应 accept。" +
                "refresh=${TimeRuleManager.getRefreshTime(context)}",
            proposedRefresh, TimeRuleManager.getRefreshTime(context)
        )
    }

    /**
     * Case 3 — refresh 落在段 end 端点(end == newRefresh)→ ACCEPT。
     * fixture：08:00-22:00,saveRefreshTime(22:00) 与 end 端点重合。
     */
    @Test
    @Config(qualifiers = "w443dp-h731dp")
    fun refresh_legality_accept_when_atSegmentEnd() {
        writeSegments(listOf(
            nonCrossRefreshSegment(8, 0, 22, 0, name = "single 段 8-22")
        ))
        val proposedRefresh = "22:00" // 与段 end 端点重合

        TimeRuleManager.saveRefreshTime(context, proposedRefresh)

        assertEquals(
            "DA-D2 Case 3：refresh=22:00 == 段 end 端点 → 应 accept。" +
                "refresh=${TimeRuleManager.getRefreshTime(context)}",
            proposedRefresh, TimeRuleManager.getRefreshTime(context)
        )
    }

    /**
     * Case 4 — refresh 严格落在段内(start < newRefresh < end)→ REJECT。
     * fixture：08:00-22:00 段,saveRefreshTime(12:00) 落在段严格内部,
     * 应被 REJECT 且 refresh time 保持原值。
     */
    @Test
    @Config(qualifiers = "w444dp-h731dp")
    fun refresh_legality_reject_when_strictlyInsideSegment() {
        writeSegments(listOf(
            nonCrossRefreshSegment(8, 0, 22, 0, name = "single 段 8-22")
        ))
        val beforeRefresh = TimeRuleManager.getRefreshTime(context) // 06:00
        val activeBefore = TimeRuleManager.getTimeSegments(context)
        val proposedRefresh = "12:00" // start(8) < 12:00 < end(22) 严格内含

        // 调用 saveRefreshTime：当前 production 无验证，会直接写入 12:00。
        // 契约断言：DA-D2 严格内部 refresh 必须拒绝，refresh time 必须保持原值。
        TimeRuleManager.saveRefreshTime(context, proposedRefresh)

        val afterRefresh = TimeRuleManager.getRefreshTime(context)
        val activeAfter = TimeRuleManager.getTimeSegments(context)

        assertEquals(
            "DA-D2 Case 4：proposed refresh=12:00 严格内含段(8:00-22:00)" +
                "应被 REJECT，refresh time 必须保持 $beforeRefresh。" +
                "was=$afterRefresh",
            beforeRefresh, afterRefresh
        )
        assertEquals(
            "DA-D2 Case 4：REJECT 后 active 段配置应保持原状。",
            activeBefore, activeAfter
        )
    }

    /**
     * Case 5 — refresh 落在跨午夜段 wrap 内,refresh=06:00 在 [06:00, 10:00]
     * 永远不可能落在跨午夜段内 → 视为 accept 反例。
     * fixture：22:00-02:00 跨午夜段,saveRefreshTime(06:00) 严格不在段内。
     */
    @Test
    @Config(qualifiers = "w445dp-h731dp")
    fun refresh_legality_accept_when_inCrossMidnightWrap() {
        // 跨午夜段 22:00-02:00,表达 [22:00, 24:00) ∪ [00:00, 02:00)。
        // refresh=06:00 物理不在段内（06:00 ∈ [02:00, 22:00) gap）。
        writeSegments(listOf(
            TimeSegment(
                startHour = 22, startMinute = 0,
                endHour = 2, endMinute = 0,
                limitMins = 60, name = "跨午夜段",
                isEnabled = true
            )
        ))
        val proposedRefresh = "06:00" // 与之前 fixture 一致，落在 gap

        TimeRuleManager.saveRefreshTime(context, proposedRefresh)

        assertEquals(
            "DA-D2 Case 5：refresh=06:00 落在跨午夜段外 gap → 应 accept。" +
                "refresh=${TimeRuleManager.getRefreshTime(context)}",
            proposedRefresh, TimeRuleManager.getRefreshTime(context)
        )
    }

    // ────────────────────────────────────────────────────────────────
    //  #4 — REJECTED SAVE IS ATOMIC（DA-D1/DA-D2 原子性,2 cases）
    //
    //  设计：拒存路径必须原子:active/pending prefs、refresh time、segments
    //  list、同步开关、change-log 全部不变。
    // ────────────────────────────────────────────────────────────────

    /**
     * Case 1 — DA-D1 strict-crossing 拒存后,active/pending/refresh 全部不变。
     */
    @Test
    @Config(qualifiers = "w451dp-h731dp")
    fun rejected_save_atomic_segmentStrictCrossing() {
        // 准备一段合法 baseline segment + 一段合法 refresh time = 06:00。
        val baseline = nonCrossRefreshSegment(9, 0, 11, 0, name = "baseline 9-11", limitMins = 60)
        writeSegments(listOf(baseline))
        writeBaselineSegments(listOf(baseline))

        // 拍 active / pending / refresh / 同步开关 快照。
        val activeBefore = rulesPrefs.getString("segments_json", null)
        val pendingBeforeJson = pendingPrefs.getString("segments_json", null)
        val pendingBeforeMarker = pendingPrefs.getBoolean("has_pending_segments", false)
        val pendingBeforeTimestamp = pendingPrefs.getLong("effective_timestamp", 0L)
        val refreshBefore = TimeRuleManager.getRefreshTime(context)
        val syncBefore = TimeRuleManager.isRefreshTimeSynced(context)

        // 调用 production：当前 strictCrossing 段被错误 accept，契约断言原子性。
        // invalid: 05:30 - 22:00,内含 refresh=06:00。
        val invalid = nonCrossRefreshSegment(5, 30, 22, 0, name = "strict-crossing")
        val beforeJsonSnapshot = rulesPrefs.getString("segments_json", null)
        val beforePendingKeys = pendingPrefs.all.keys.toSet()

        TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(invalid))

        val activeAfter = rulesPrefs.getString("segments_json", null)
        val pendingAfterJson = pendingPrefs.getString("segments_json", null)
        val pendingAfterMarker = pendingPrefs.getBoolean("has_pending_segments", false)
        val pendingAfterTimestamp = pendingPrefs.getLong("effective_timestamp", 0L)
        val refreshAfter = TimeRuleManager.getRefreshTime(context)
        val syncAfter = TimeRuleManager.isRefreshTimeSynced(context)
        val afterJsonSnapshot = rulesPrefs.getString("segments_json", null)

        // 原子性契约断言：
        assertEquals(
            "DA-D1 atomic[active]：拒存后 active segments_json 应保持不变。" +
                "before=$activeBefore after=$activeAfter",
            activeBefore, activeAfter
        )
        assertEquals(
            "DA-D1 atomic[pending.json]：拒存后 pending segments_json 应保持(原本 null)。",
            pendingBeforeJson, pendingAfterJson
        )
        assertEquals(
            "DA-D1 atomic[pending.marker]：拒存后 has_pending_segments 应保持 $pendingBeforeMarker",
            pendingBeforeMarker, pendingAfterMarker
        )
        assertEquals(
            "DA-D1 atomic[pending.timestamp]：拒存后 effective_timestamp 应保持 $pendingBeforeTimestamp",
            pendingBeforeTimestamp, pendingAfterTimestamp
        )
        assertEquals(
            "DA-D1 atomic[refresh]：拒存后 refresh time 应保持 $refreshBefore",
            refreshBefore, refreshAfter
        )
        assertEquals(
            "DA-D1 atomic[sync]：拒存后 isRefreshTimeSynced 开关应保持 $syncBefore",
            syncBefore, syncAfter
        )

        // 不变量补充：原 prefs keys 集合不增不减(防止生产者写入陌生键)。
        val beforeKeys = beforeJsonSnapshot?.let { setOf("segments_json") } ?: emptySet()
        val afterKeys = afterJsonSnapshot?.let { setOf("segments_json") } ?: emptySet()
        assertEquals(
            "DA-D1 atomic[keys]：active prefs keys 不应被 invalid save 改动。" +
                "before=$beforeKeys after=$afterKeys",
            beforeKeys, afterKeys
        )

        // DA-D1 sanity：invalid 段被错误写入 ↓ actriveAfter ≠ activeBefore 时
        // 整体就 RED。
        if (activeAfter != activeBefore) {
            fail(
                "DA-D1 atomic RED 触发：strict-crossing 段被错误写入。" +
                    "expected segments_json=$activeBefore, got=$activeAfter。" +
                    "WP-03 发布 producer 后应修复此 reject。"
            )
        }
    }

    /**
     * Case 2 — DA-D2 strict-interior 拒存后,active/pending/refresh 全部不变。
     */
    @Test
    @Config(qualifiers = "w452dp-h731dp")
    fun rejected_save_atomic_refreshStrictInside() {
        // 准备一段合法段。
        val baseline = nonCrossRefreshSegment(9, 0, 17, 0, name = "baseline 9-17")
        writeSegments(listOf(baseline))
        writeBaselineSegments(listOf(baseline))

        val activeBefore = rulesPrefs.getString("segments_json", null)
        val pendingBeforeJson = pendingPrefs.getString("segments_json", null)
        val pendingBeforeMarker = pendingPrefs.getBoolean("has_pending_segments", false)
        val refreshBefore = TimeRuleManager.getRefreshTime(context) // 06:00

        // 拍 pending prefs keys 集合,防止 reject 路径改动。
        val beforePendingKeys = pendingPrefs.all.keys.toSet()

        // 调用 invalid saveRefreshTime(12:00) 在 9:00-17:00 段严格内含。
        TimeRuleManager.saveRefreshTime(context, "12:00")

        val activeAfter = rulesPrefs.getString("segments_json", null)
        val pendingAfterJson = pendingPrefs.getString("segments_json", null)
        val pendingAfterMarker = pendingPrefs.getBoolean("has_pending_segments", false)
        val refreshAfter = TimeRuleManager.getRefreshTime(context)
        val afterPendingKeys = pendingPrefs.all.keys.toSet()

        assertEquals(
            "DA-D2 atomic[active]：reject 后 active segments_json 必须保持不变",
            activeBefore, activeAfter
        )
        assertEquals(
            "DA-D2 atomic[pending.json]：reject 后 pending segments_json 必须保持",
            pendingBeforeJson, pendingAfterJson
        )
        assertEquals(
            "DA-D2 atomic[pending.marker]：reject 后 has_pending_segments 必须保持 $pendingBeforeMarker",
            pendingBeforeMarker, pendingAfterMarker
        )
        assertEquals(
            "DA-D2 atomic[refresh]：strict-interior 拒存后 refresh 必须保持 $refreshBefore",
            refreshBefore, refreshAfter
        )
        assertEquals(
            "DA-D2 atomic[pending.keys]：reject 后 pending prefs keys 不增不减。" +
                "before=$beforePendingKeys after=$afterPendingKeys",
            beforePendingKeys, afterPendingKeys
        )

        // RED sanity：当前 production 无拒存,refresh time 被错误改写到 12:00。
        if (refreshAfter != refreshBefore) {
            fail(
                "DA-D2 atomic RED 触发：strict-interior refresh=12:00 被错误写入。" +
                    "expected=$refreshBefore, got=$refreshAfter。" +
                    "WP-03 发布 producer 后应修复此 reject。"
            )
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  #4 续 — atomicity 覆盖 6 项 invariant (Luna REVISE 修补)
    //
    //  完整 6 项 atomic invariant:
    //    (1) refresh  — refresh time 不变
    //    (2) active   — active segments_json + key set 不变
    //    (3) pending  — pending segments_json + marker + key set 不变
    //    (4) sync     — isRefreshTimeSynced 开关不变
    //    (5) timestamps — pending effective_timestamp + any created-at
    //                     时间戳不变 (本测试聚焦 pending effective_timestamp)
    //    (6) change-log — decikeeper_changelog entries list 在 reject 路径
    //                     上不应被 append (无新写入)
    //
    //  上面 #4 已有 2 cases 覆盖 (1)(2)(3)(4);下面 4 cases 各自聚焦补 (5)(6)
    //  两项。WP-03 发布拒存实现后,本组 case 与 #4 同步转 GREEN。
    // ────────────────────────────────────────────────────────────────

    /** 读 change-log raw JSON(测试私有 helper,绕开 ChangeLogManager.getAllEntries
     *  是 private 的事实)。 */
    private fun readChangeLogJson(): String? {
        val prefs = context.getSharedPreferences(
            "decikeeper_changelog", Context.MODE_PRIVATE
        )
        return prefs.getString("changelog_entries", null)
    }

    /**
     * Case 3 — DA-D1 strict-crossing 拒存后,pending effective_timestamp 不变。
     */
    @Test
    @Config(qualifiers = "w453dp-h731dp")
    fun rejected_save_atomic_segment_timestampUnchanged() {
        val baseline = nonCrossRefreshSegment(9, 0, 11, 0, name = "baseline 9-11", limitMins = 60)
        writeSegments(listOf(baseline))
        writeBaselineSegments(listOf(baseline))

        // 准备一段「与 invalid 无关」的 pending 状态(effective_timestamp 非 0),
        // 这样断言拒绝路径不会改这个值时信号清晰。
        pendingPrefs.edit()
            .putBoolean("has_pending_changes", true)
            .putLong("effective_timestamp", 1_700_000_000_000L)
            .apply()

        val pendingTsBefore = pendingPrefs.getLong("effective_timestamp", 0L)
        val pendingMarkerBefore = pendingPrefs.getBoolean("has_pending_changes", false)
        val refreshBefore = TimeRuleManager.getRefreshTime(context)
        val activeBefore = rulesPrefs.getString("segments_json", null)

        // invalid: 05:30 - 22:00,内含 refresh=06:00。
        val invalid = nonCrossRefreshSegment(5, 30, 22, 0, name = "strict-crossing-ts")

        TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(invalid))

        val pendingTsAfter = pendingPrefs.getLong("effective_timestamp", 0L)
        val pendingMarkerAfter = pendingPrefs.getBoolean("has_pending_changes", false)
        val refreshAfter = TimeRuleManager.getRefreshTime(context)
        val activeAfter = rulesPrefs.getString("segments_json", null)

        assertEquals(
            "DA-D1 atomicity[timestamps]：strict-crossing 拒存后 pending " +
                "effective_timestamp 必须保持 $pendingTsBefore 不变。was=$pendingTsAfter",
            pendingTsBefore, pendingTsAfter
        )
        assertEquals(
            "DA-D1 atomicity[timestamps.marker]：拒存后 pending has_pending_changes " +
                "marker 必须保持 $pendingMarkerBefore",
            pendingMarkerBefore, pendingMarkerAfter
        )
        // 配套断言:refresh / active 也必须不变(保证 6 项 invariant 整体一致)。
        assertEquals(
            "DA-D1 atomicity[refresh] (伴随)：拒存后 refresh 必须保持 $refreshBefore",
            refreshBefore, refreshAfter
        )
        assertEquals(
            "DA-D1 atomicity[active] (伴随)：拒存后 active segments_json 必须保持",
            activeBefore, activeAfter
        )

        // RED sanity：当前 production accept strict-crossing,可能改写 active 或
        // 重写 pending effective_timestamp → 触发 RED。
        if (pendingTsAfter != pendingTsBefore) {
            fail(
                "DA-D1 atomicity[timestamps] RED 触发:拒存后 pending " +
                    "effective_timestamp 被改写 $pendingTsBefore → $pendingTsAfter。" +
                    "WP-03 发布 producer 后应保证原子性。"
            )
        }
    }

    /**
     * Case 4 — DA-D1 strict-crossing 拒存后,change-log 不被 append。
     */
    @Test
    @Config(qualifiers = "w454dp-h731dp")
    fun rejected_save_atomic_segment_changeLogUnchanged() {
        val baseline = nonCrossRefreshSegment(9, 0, 11, 0, name = "baseline 9-11", limitMins = 60)
        writeSegments(listOf(baseline))
        writeBaselineSegments(listOf(baseline))

        val changeLogBefore = readChangeLogJson()
        val refreshBefore = TimeRuleManager.getRefreshTime(context)
        val activeBefore = rulesPrefs.getString("segments_json", null)

        // invalid: 05:30 - 22:00,内含 refresh=06:00。
        val invalid = nonCrossRefreshSegment(5, 30, 22, 0, name = "strict-crossing-changelog")

        TimeRuleManager.analyzeAndSaveTimeSegments(context, listOf(invalid))

        val changeLogAfter = readChangeLogJson()
        val refreshAfter = TimeRuleManager.getRefreshTime(context)
        val activeAfter = rulesPrefs.getString("segments_json", null)

        assertEquals(
            "DA-D1 atomicity[change-log]：strict-crossing 拒存后 change-log " +
                "JSON 字节序列必须完全一致。before=${changeLogBefore?.length ?: "null"} " +
                "after=${changeLogAfter?.length ?: "null"}",
            changeLogBefore, changeLogAfter
        )
        // 配套断言
        assertEquals(
            "DA-D1 atomicity[refresh] (伴随)：拒存后 refresh 必须保持 $refreshBefore",
            refreshBefore, refreshAfter
        )
        assertEquals(
            "DA-D1 atomicity[active] (伴随)：拒存后 active segments_json 必须保持",
            activeBefore, activeAfter
        )

        // RED sanity
        if (changeLogBefore != changeLogAfter) {
            fail(
                "DA-D1 atomicity[change-log] RED 触发:拒存后 change-log JSON 被改写。" +
                    "WP-03 发布 producer 后应保证 change-log 不被 append。"
            )
        }
    }

    /**
     * Case 5 — DA-D2 strict-interior 拒存后,pending effective_timestamp 不变。
     */
    @Test
    @Config(qualifiers = "w455dp-h731dp")
    fun rejected_save_atomic_refresh_timestampUnchanged() {
        val baseline = nonCrossRefreshSegment(9, 0, 17, 0, name = "baseline 9-17")
        writeSegments(listOf(baseline))
        writeBaselineSegments(listOf(baseline))

        // 准备与 invalid save 无关的 pending 状态。
        pendingPrefs.edit()
            .putBoolean("has_pending_changes", true)
            .putLong("effective_timestamp", 1_700_000_000_000L)
            .apply()

        val pendingTsBefore = pendingPrefs.getLong("effective_timestamp", 0L)
        val refreshBefore = TimeRuleManager.getRefreshTime(context)
        val activeBefore = rulesPrefs.getString("segments_json", null)

        // strict-interior refresh=12:00 在 9:00-17:00 段内,应被 REJECT。
        TimeRuleManager.saveRefreshTime(context, "12:00")

        val pendingTsAfter = pendingPrefs.getLong("effective_timestamp", 0L)
        val refreshAfter = TimeRuleManager.getRefreshTime(context)
        val activeAfter = rulesPrefs.getString("segments_json", null)

        assertEquals(
            "DA-D2 atomicity[timestamps]：strict-interior 拒存后 pending " +
                "effective_timestamp 必须保持 $pendingTsBefore 不变。was=$pendingTsAfter",
            pendingTsBefore, pendingTsAfter
        )
        // 配套断言
        assertEquals(
            "DA-D2 atomicity[refresh] (伴随)：拒存后 refresh 必须保持 $refreshBefore",
            refreshBefore, refreshAfter
        )
        assertEquals(
            "DA-D2 atomicity[active] (伴随)：拒存后 active segments_json 必须保持",
            activeBefore, activeAfter
        )

        // RED sanity
        if (pendingTsAfter != pendingTsBefore) {
            fail(
                "DA-D2 atomicity[timestamps] RED 触发:拒存后 pending " +
                    "effective_timestamp 被改写 $pendingTsBefore → $pendingTsAfter。" +
                    "WP-03 发布 producer 后应保证原子性。"
            )
        }
    }

    /**
     * Case 6 — DA-D2 strict-interior 拒存后,change-log 不被 append。
     */
    @Test
    @Config(qualifiers = "w456dp-h731dp")
    fun rejected_save_atomic_refresh_changeLogUnchanged() {
        val baseline = nonCrossRefreshSegment(9, 0, 17, 0, name = "baseline 9-17")
        writeSegments(listOf(baseline))
        writeBaselineSegments(listOf(baseline))

        val changeLogBefore = readChangeLogJson()
        val refreshBefore = TimeRuleManager.getRefreshTime(context)
        val activeBefore = rulesPrefs.getString("segments_json", null)

        TimeRuleManager.saveRefreshTime(context, "12:00")

        val changeLogAfter = readChangeLogJson()
        val refreshAfter = TimeRuleManager.getRefreshTime(context)
        val activeAfter = rulesPrefs.getString("segments_json", null)

        assertEquals(
            "DA-D2 atomicity[change-log]：strict-interior 拒存后 change-log " +
                "JSON 字节序列必须完全一致。before=${changeLogBefore?.length ?: "null"} " +
                "after=${changeLogAfter?.length ?: "null"}",
            changeLogBefore, changeLogAfter
        )
        // 配套断言
        assertEquals(
            "DA-D2 atomicity[refresh] (伴随)：拒存后 refresh 必须保持 $refreshBefore",
            refreshBefore, refreshAfter
        )
        assertEquals(
            "DA-D2 atomicity[active] (伴随)：拒存后 active segments_json 必须保持",
            activeBefore, activeAfter
        )

        // RED sanity
        if (changeLogBefore != changeLogAfter) {
            fail(
                "DA-D2 atomicity[change-log] RED 触发:拒存后 change-log JSON 被改写。" +
                    "WP-03 发布 producer 后应保证 change-log 不被 append。"
            )
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  shared helpers
    // ────────────────────────────────────────────────────────────────

    /**
     * 用今天日期构造 fake now ms,小时数=clockMinutes。
     *
     * ShadowSystemClock 在 Robolectric 不会改 `Calendar.getInstance()` /
     * `System.currentTimeMillis()`,所以这里直接用真实墙钟的"今天 + 目标小时"
     * 构造 Calendar,避免绕开 Calendar 内部读取。
     */
    private fun todayAtMinutes(minutes: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, minutes / 60)
        cal.set(Calendar.MINUTE, minutes % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
