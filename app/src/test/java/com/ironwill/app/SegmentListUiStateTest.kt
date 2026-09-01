package com.ironwill.app

import android.content.Context
import com.ironwill.app.ui.AppSelectionActivity
import com.ironwill.app.ui.TimeConfigFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config




/**
 * TASK-05 (segment-blacklist) RED — 名单页 UI 状态机与集合逻辑。
 *
 * 覆盖（SPEC §2.2-2.6 / §6 S9）：
 *   - 全选 / 反选集合逻辑（invertSelection 往返、selectAll 全集、空集合边界）；
 *   - initialSelectionFor：null→全局黑名单；非 null→名单本身；空列表→空集合；
 *   - 硬剔除过滤（Robolectric）：launcher/输入法/电话/短信/设置/IronWill 被过滤，普通应用保留；
 *   - follow/decoupled 状态转换纯逻辑（resolveListAppsFromResult：恢复跟随→null，脱钩→名单）；
 *   - 摘要文案（segmentListSummary：null/空/非空三态）。
 *
 * 这些纯逻辑集中在 AppSelectionActivity 伴生对象与 TimeConfigFragment 伴生对象，
 * 均为 Set<String>/List<String> 级运算（filterHardExempt 需要 Context，用 Robolectric）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SegmentListUiStateTest {

    private lateinit var context: Context

    private val launcher = "com.android.launcher"
    private val settings = "com.android.settings"
    private val ime = "com.sohu.inputmethod.sogou"
    private val phone = "com.android.phone"
    private val contacts = "com.android.contacts"
    private val messaging = "com.google.android.apps.messaging"

    // 普通应用包名：刻意避开 isLauncher("home"/"launcher") 与
    // isInputMethod("input"/"ime"/"key"/"sogou"/"baidu"/"iflytek"/"wetype") 的保守匹配。
    private val normalGame = "com.example.game"
    private val qq = "com.tencent.mobileqq"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    // ────────────────────────────────────────────────────────────
    //  selectAll：全集 / 空集合边界
    // ────────────────────────────────────────────────────────────

    @Test
    fun selectAll_returnsFullUniverse() {
        val universe = setOf(qq, normalGame, "com.example.extra")
        assertEquals("全选应返回全集", universe, AppSelectionActivity.selectAll(universe))
    }

    @Test
    fun selectAll_emptyUniverse_returnsEmpty() {
        assertEquals("空全集全选仍为空", emptySet<String>(), AppSelectionActivity.selectAll(emptySet()))
    }

    // ────────────────────────────────────────────────────────────
    //  invertSelection：往返、空/满集合边界、忽略全集外项
    // ────────────────────────────────────────────────────────────

    @Test
    fun invertSelection_roundTrip_returnsOriginal() {
        val universe = setOf(qq, normalGame, "com.example.a", "com.example.b")
        val current = setOf(qq, "com.example.a")

        val inverted = AppSelectionActivity.invertSelection(current, universe)
        assertEquals("反选后应选中未勾选项", setOf(normalGame, "com.example.b"), inverted)
        assertEquals(
            "反选两次回到原勾选",
            current,
            AppSelectionActivity.invertSelection(inverted, universe)
        )
    }

    @Test
    fun invertSelection_emptyCurrent_selectsWholeUniverse() {
        val universe = setOf(qq, normalGame)
        assertEquals("空勾选反选 = 全集", universe, AppSelectionActivity.invertSelection(emptySet(), universe))
    }

    @Test
    fun invertSelection_fullCurrent_empties() {
        val universe = setOf(qq, normalGame)
        assertEquals("全集勾选反选 = 空", emptySet<String>(), AppSelectionActivity.invertSelection(universe, universe))
    }

    @Test
    fun invertSelection_ignoresItemsOutsideUniverse() {
        val universe = setOf(qq, normalGame)
        val current = setOf(qq, "com.example.notinstalled")
        assertEquals(
            "全集外的勾选项不应被反选结果包含",
            setOf(normalGame),
            AppSelectionActivity.invertSelection(current, universe)
        )
    }

    // ────────────────────────────────────────────────────────────
    //  initialSelectionFor：null→全局；非 null→名单本身；空列表→空集合
    // ────────────────────────────────────────────────────────────

    @Test
    fun initialSelectionFor_null_followsGlobal() {
        val global = setOf(qq, normalGame)
        assertEquals("null（跟随全局）应返回全局黑名单", global, AppSelectionActivity.initialSelectionFor(null, global))
    }

    @Test
    fun initialSelectionFor_nonNull_usesListItself() {
        val global = setOf(qq)
        assertEquals(
            "非 null 名单应使用名单本身（忽略全局）",
            setOf(normalGame),
            AppSelectionActivity.initialSelectionFor(listOf(normalGame), global)
        )
    }

    @Test
    fun initialSelectionFor_emptyList_returnsEmpty() {
        assertEquals(
            "空列表（脱钩后清空）应返回空集合而非全局",
            emptySet<String>(),
            AppSelectionActivity.initialSelectionFor(emptyList(), setOf(qq))
        )
    }

    // ────────────────────────────────────────────────────────────
    //  硬剔除过滤（Robolectric）：硬剔除项被过滤，普通应用保留
    // ────────────────────────────────────────────────────────────

    @Test
    fun filterHardExempt_removesSelfLauncherSettingsImePhoneContactsSms() {
        val input = setOf(
            normalGame, qq,
            launcher, settings, ime, phone, contacts, messaging, context.packageName
        )
        val result = AppSelectionActivity.filterHardExempt(context, input)

        assertEquals("硬剔除过滤后仅剩普通应用", setOf(normalGame, qq), result)
        assertFalse("IronWill 自身必须被过滤", result.contains(context.packageName))
    }

    @Test
    fun filterHardExempt_keepsNormalApps() {
        val result = AppSelectionActivity.filterHardExempt(context, setOf(normalGame, qq))
        assertEquals("普通应用必须保留", setOf(normalGame, qq), result)
    }

    // ────────────────────────────────────────────────────────────
    //  follow/decoupled 状态转换：恢复跟随→null，脱钩→名单
    // ────────────────────────────────────────────────────────────

    @Test
    fun resolveListAppsFromResult_followGlobal_returnsNull() {
        assertNull("恢复跟随全局 → listApps 置 null", TimeConfigFragment.resolveListAppsFromResult(true, listOf(qq)))
    }

    @Test
    fun resolveListAppsFromResult_decoupled_returnsSelection() {
        assertEquals(
            "脱钩 → 使用选择列表",
            listOf(qq),
            TimeConfigFragment.resolveListAppsFromResult(false, listOf(qq))
        )
    }

    @Test
    fun resolveListAppsFromResult_decoupledEmpty_returnsEmptyList() {
        assertEquals(
            "脱钩后清空 → 空列表（区别于 null 跟随）",
            emptyList<String>(),
            TimeConfigFragment.resolveListAppsFromResult(false, emptyList())
        )
    }

    // ────────────────────────────────────────────────────────────
    //  segmentListSummary：三态文案
    // ────────────────────────────────────────────────────────────

    @Test
    fun segmentListSummary_null_followGlobal() {
        assertEquals("跟随全局黑/白名单", TimeConfigFragment.segmentListSummary(null))
    }

    @Test
    fun segmentListSummary_empty_noneLocked() {
        assertEquals("本时段不锁任何应用", TimeConfigFragment.segmentListSummary(emptyList()))
    }

    @Test
    fun segmentListSummary_nonEmpty_counts() {
        assertEquals("独立管理 2 个应用", TimeConfigFragment.segmentListSummary(listOf(qq, normalGame)))
    }

    // ────────────────────────────────────────────────────────────
    //  [FIX TASK-05-review] resolveFinalFollowGlobal：修改保存即脱钩
    // ────────────────────────────────────────────────────────────

    @Test
    fun finalFollow_followEntered_noChanges_keepsFollowing() {
        // 跟随态进入，未改任何勾选 → 保持跟随
        assertTrue(
            "跟随态进入且未修改应保持跟随",
            AppSelectionActivity.resolveFinalFollowGlobal(
                enteredFollowGlobal = true,
                restoreRequested = false,
                selection = setOf(qq, normalGame),
                originalSelection = setOf(qq, normalGame)
            )
        )
    }

    @Test
    fun finalFollow_followEntered_withEdits_decouples() {
        // 跟随态进入，改了勾选 → 必须脱钩（否则修改被丢弃，违反核心需求）
        assertFalse(
            "跟随态进入但勾选变化应脱钩",
            AppSelectionActivity.resolveFinalFollowGlobal(
                enteredFollowGlobal = true,
                restoreRequested = false,
                selection = setOf(qq),
                originalSelection = setOf(qq, normalGame)
            )
        )
    }

    @Test
    fun finalFollow_decoupledEntered_noChanges_staysDecoupled() {
        assertFalse(
            "脱钩态进入且未修改应保持脱钩",
            AppSelectionActivity.resolveFinalFollowGlobal(
                enteredFollowGlobal = false,
                restoreRequested = false,
                selection = setOf(qq),
                originalSelection = setOf(qq)
            )
        )
    }

    @Test
    fun finalFollow_restoreRequested_alwaysFollows() {
        // 显式点了"恢复为全局黑名单" → 无条件重新跟随（即使勾选被清空）
        assertTrue(
            "显式恢复应无条件跟随全局",
            AppSelectionActivity.resolveFinalFollowGlobal(
                enteredFollowGlobal = false,
                restoreRequested = true,
                selection = emptySet(),
                originalSelection = setOf(qq, normalGame)
            )
        )
    }
}
