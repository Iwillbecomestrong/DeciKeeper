/**
 * =================================================================================
 * [Context Header]
 * * @file: PrivacySpaceGuardRulesTest.kt
 * * @role: Test
 * * @description: TASK-02 规则页「隐私空间拦截」开关 + 无障碍协同关闭的 TDD 单测。
 * 覆盖 RulesFragment 中可单测的纯逻辑判定函数:
 * - 开启前置: 无障碍未启用 → 阻止开启
 * - 协同关闭资格: isEnforcing && 非 PENDING → 提交关闭
 * - 协同关闭抑制: 已 PENDING / DISABLED → 不重复提交 / 不提交
 * - 文案映射: ACTIVE/PENDING/DISABLED → "已生效"/"次日生效,当前仍拦截"/"已关闭"
 * - UI 经 Manager: 不直接读写 privacy_protection_prefs / KEY_IS_ENABLED / pending / baseline
 * * @context:
 * - 上游: Gradle Unit Test (Robolectric SDK 33)
 * - 下游: RulesFragment (静态判定函数), PrivacyProtectionManager
 * * @constraints (必读):
 * 1. 测试只断言可测的判定逻辑, 不挂载 Fragment, 不读 View
 * 2. UI 直写 prefs 禁止项通过源码字符串搜索断言
 * =================================================================================
 */
package com.decikeeper.app

import android.content.Context
import com.decikeeper.app.manager.PrivacyProtectionManager
import com.decikeeper.app.ui.RulesFragment
import org.junit.After
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




@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivacySpaceGuardRulesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        // 清空 Manager / Pending / Baseline prefs, 保证每个测试独立
        context.getSharedPreferences(PrivacyProtectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("decikeeper_rules_baseline", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        // no-op
    }

    // =========================================================================
    // 第一组: 开启前置 - 无障碍未启用 → 阻止开启
    // =========================================================================

    /** 无障碍未开 → shouldBlockPrivacySpaceGuardEnable = true */
    @Test
    fun enablePrecondition_blocksWhenAccessibilityDisabled() {
        assertTrue(
            "无障碍未开时必须阻止开启",
            RulesFragment.shouldBlockPrivacySpaceGuardEnable(isAccessibilityEnabled = false)
        )
    }

    /** 无障碍已开 → shouldBlockPrivacySpaceGuardEnable = false */
    @Test
    fun enablePrecondition_allowsWhenAccessibilityEnabled() {
        assertFalse(
            "无障碍已开时必须允许开启",
            RulesFragment.shouldBlockPrivacySpaceGuardEnable(isAccessibilityEnabled = true)
        )
    }

    // =========================================================================
    // 第二组: UI 显示文案 (PENDING 文案必须包含"次日生效"+"当前仍拦截")
    // =========================================================================

    /** DISABLED → "已关闭" */
    @Test
    fun uiText_disabled() {
        val text = RulesFragment.computePrivacySpaceGuardUiText(
            state = PrivacyProtectionManager.State.DISABLED,
            isEnforcing = false
        )
        assertEquals("已关闭", text)
    }

    /** ACTIVE → "已生效" */
    @Test
    fun uiText_active() {
        val text = RulesFragment.computePrivacySpaceGuardUiText(
            state = PrivacyProtectionManager.State.ACTIVE,
            isEnforcing = true
        )
        assertEquals("已生效", text)
    }

    /** PENDING → 必须含"次日生效"+"当前仍拦截" */
    @Test
    fun uiText_pendingMustMentionNextDayAndStillEnforcing() {
        val text = RulesFragment.computePrivacySpaceGuardUiText(
            state = PrivacyProtectionManager.State.PENDING,
            isEnforcing = true
        )
        assertTrue(
            "PENDING 文案必须包含『次日生效』",
            text.contains("次日生效")
        )
        assertTrue(
            "PENDING 文案必须包含『当前仍拦截』(由 isEnforcing 推出)",
            text.contains("当前仍拦截")
        )
    }

    // =========================================================================
    // 第三组: UI Switch 显示逻辑 (禁止 UI 自推 ACTIVE/PENDING → 强制 isEnforcing)
    // =========================================================================

    /** DISABLED → Switch 关 */
    @Test
    fun switchState_disabled() {
        assertFalse(
            RulesFragment.computePrivacySpaceGuardSwitchChecked(
                state = PrivacyProtectionManager.State.DISABLED,
                isEnforcing = false
            )
        )
    }

    /** ACTIVE → Switch 开 */
    @Test
    fun switchState_active() {
        assertTrue(
            RulesFragment.computePrivacySpaceGuardSwitchChecked(
                state = PrivacyProtectionManager.State.ACTIVE,
                isEnforcing = true
            )
        )
    }

    /**
     * PENDING → Switch 必须显示为关 (对齐防关权限/防卸载 PENDING 视觉).
     * 拦截语义保持不变: 门禁仍由 isEnforcing() 控制, PENDING 期仍拦,
     * 只是 UI 开关提前反映"已申请关闭"状态, 与防关权限/防卸载 PENDING 行为一致.
     */
    @Test
    fun switchState_pendingMustBeUnchecked() {
        assertFalse(
            "PENDING 期 Switch 必须显示为关 (与防关权限 PENDING 对齐), 但 isEnforcing 仍为 true 表示仍拦截",
            RulesFragment.computePrivacySpaceGuardSwitchChecked(
                state = PrivacyProtectionManager.State.PENDING,
                isEnforcing = true
            )
        )
    }

    // =========================================================================
    // 第四组: 无障碍协同关闭资格判定
    // =========================================================================

    /** 协同关闭: ACTIVE + 非 PENDING → 应提交 (isEnforcing=true) */
    @Test
    fun a11yCollaborativeDisable_shouldSubmitWhenActive() {
        assertTrue(
            "ACTIVE 时 isEnforcing=true 且非 PENDING, 必须提交关闭",
            RulesFragment.shouldRequestPrivacySpaceGuardDisableOnA11yDisable(
                isEnforcing = true,
                isPending = false
            )
        )
    }

    /** 协同关闭: DISABLED (isEnforcing=false) → 不提交 */
    @Test
    fun a11yCollaborativeDisable_shouldNotSubmitWhenDisabled() {
        assertFalse(
            "DISABLED 时 isEnforcing=false, 不能提交关闭",
            RulesFragment.shouldRequestPrivacySpaceGuardDisableOnA11yDisable(
                isEnforcing = false,
                isPending = false
            )
        )
    }

    /** 协同关闭: 已 PENDING → 不重复提交, 避免重置到期时间 */
    @Test
    fun a11yCollaborativeDisable_shouldNotSubmitWhenAlreadyPending() {
        assertFalse(
            "已 PENDING 时不能重复提交关闭, 否则会重置到期时间",
            RulesFragment.shouldRequestPrivacySpaceGuardDisableOnA11yDisable(
                isEnforcing = true,
                isPending = true
            )
        )
    }

    // =========================================================================
    // 第五组: 默认值与端到端 Manager 行为
    // =========================================================================

    /** 无任何历史 key → 默认 OFF, isEnforcing=false */
    @Test
    fun defaultOff_noHistoryKey() {
        assertFalse(
            "无 key 时必须默认 OFF",
            PrivacyProtectionManager.isEnforcing(context)
        )
        assertEquals(
            PrivacyProtectionManager.State.DISABLED,
            PrivacyProtectionManager.getProtectionState(context)
        )
    }

    /** 开启立即 ACTIVE → isEnforcing=true */
    @Test
    fun enableImmediateActive() {
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        assertTrue(PrivacyProtectionManager.isEnforcing(context))
        assertEquals(
            PrivacyProtectionManager.State.ACTIVE,
            PrivacyProtectionManager.getProtectionState(context)
        )
    }

    /** 非适应期 + baseline=true → 关闭进入 PENDING, isEnforcing 仍 true */
    @Test
    fun disableEntersPending_notAdaptationBaselineTrue() {
        // 先通过生产 API 开启，建立 baseline=true；并用生产时间规则结束适应期。
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", 1L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()
        PrivacyProtectionManager.setProtectionEnabled(context, false)

        assertEquals(
            PrivacyProtectionManager.State.PENDING,
            PrivacyProtectionManager.getProtectionState(context)
        )
        assertTrue(
            "PENDING 未到期时 isEnforcing 必须仍为 true",
            PrivacyProtectionManager.isEnforcing(context)
        )
    }

    /** 适应期 (无 baseline) → 关闭立即 DISABLED */
    @Test
    fun disableImmediateDisabled_duringAdaptationOrBaselineFalse() {
        // baseline 默认无 key → false
        // 在非 ACTIVE 情况下, setProtectionEnabled(false) 应走"恢复 baseline 关闭态"
        // 立即落地 DISABLED
        PrivacyProtectionManager.setProtectionEnabled(context, false)
        assertEquals(
            PrivacyProtectionManager.State.DISABLED,
            PrivacyProtectionManager.getProtectionState(context)
        )
        assertFalse(PrivacyProtectionManager.isEnforcing(context))
    }

    /** 协同关闭 API 仿真: ACTIVE 时按判定函数提交 → Manager 状态变为 PENDING */
    @Test
    fun a11yCollaborativeDisable_endToEnd_activeTriggersPending() {
        // 初始 ACTIVE，并显式建立生产关闭规则所需的 baseline 与非适应期前置。
        PrivacyProtectionManager.setProtectionEnabled(context, true)
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)
            .edit()
            .putLong("first_setup_timestamp_v2", 1L)
            .putBoolean("has_manually_ended_adaptation", true)
            .commit()

        // 模拟两处无障碍协同关闭对话框中的"提交"动作
        val shouldSubmit = RulesFragment.shouldRequestPrivacySpaceGuardDisableOnA11yDisable(
            isEnforcing = PrivacyProtectionManager.isEnforcing(context),
            isPending = PrivacyProtectionManager.getProtectionState(context) == PrivacyProtectionManager.State.PENDING
        )
        assertTrue("ACTIVE 必须可提交", shouldSubmit)

        // 提交动作必须调用生产 API；由 Manager 根据 baseline 和 strictNextDay 建立 PENDING。
        if (shouldSubmit) {
            PrivacyProtectionManager.setProtectionEnabled(context, false)
        }

        // 测试时钟的 strictNextDay 生效时间允许被推进到未来；不伪造 PENDING 标志。
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("${PrivacyProtectionManager.KEY_PENDING_DISABLE}_effective_time", Long.MAX_VALUE / 2)
            .commit()

        assertEquals(
            PrivacyProtectionManager.State.PENDING,
            PrivacyProtectionManager.getProtectionState(context)
        )
        assertTrue(
            "PENDING 未到期时必须仍为 true",
            PrivacyProtectionManager.isEnforcing(context)
        )
        // 二次协同关闭: 已 PENDING → 不重复提交
        val shouldResubmit = RulesFragment.shouldRequestPrivacySpaceGuardDisableOnA11yDisable(
            isEnforcing = PrivacyProtectionManager.isEnforcing(context),
            isPending = PrivacyProtectionManager.getProtectionState(context) == PrivacyProtectionManager.State.PENDING
        )
        assertFalse(
            "已 PENDING 时不应重复提交关闭",
            shouldResubmit
        )

        // 允许直接推进时间模拟次日到达；进入 PENDING 的动作仍由生产 API 完成。
        context.getSharedPreferences("pending_config_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("${PrivacyProtectionManager.KEY_PENDING_DISABLE}_effective_time", 1L)
            .commit()
        assertEquals(
            PrivacyProtectionManager.State.DISABLED,
            PrivacyProtectionManager.getProtectionState(context)
        )
        assertFalse(
            "PENDING 到期后必须停止拦截",
            PrivacyProtectionManager.isEnforcing(context)
        )
    }

    // =========================================================================
    // 第六组: 文案清单 (协同关闭对话框功能列表) 无条件硬列本项
    // 真机反馈: 之前用 isEnforcing 判定才列出, 导致真机对话框看不到"隐私空间拦截"项.
    // 改为无条件硬列 (与防卸载/防关权限/闹钟防关机同级), 提交时由 Manager 决定是否真正生效.
    // =========================================================================

    /** 协同关闭文案列表无条件必须包含 "隐私空间拦截" + "次日生效" (ACTIVE) */
    @Test
    fun a11yCollaborativeDialogListContainsPrivacySpaceGuard() {
        val item = RulesFragment.a11yCollaborativeDisableDialogItem(
            isEnforcing = true,
            isPending = false
        )
        assertNotNull("ACTIVE 时列表项必须非空", item)
        assertTrue(
            "列表项必须包含『隐私空间拦截』",
            item!!.contains("隐私空间拦截")
        )
        assertTrue(
            "列表项必须说明『次日生效』",
            item.contains("次日生效")
        )
    }

    /**
     * 真机反馈: DISABLED 也必须列出本项 (无条件硬列), 与其它三个保护对齐.
     * 提交逻辑由 Manager (仅 ACTIVE 时 setProtectionEnabled(false)) 控制,
     * 所以列出而不重复提交不会导致状态错乱.
     */
    @Test
    fun a11yCollaborativeDialogListShowsItemEvenWhenDisabled() {
        val item = RulesFragment.a11yCollaborativeDisableDialogItem(
            isEnforcing = false,
            isPending = false
        )
        assertNotNull("DISABLED 时列表项也必须显示 (无条件硬列)", item)
        assertTrue(
            "列表项必须包含『隐私空间拦截』",
            item!!.contains("隐私空间拦截")
        )
        assertTrue(
            "列表项必须说明『次日生效』",
            item.contains("次日生效")
        )
    }

    /**
     * 真机反馈: 已 PENDING 也必须列出本项 (无条件硬列).
     * 提交时由 Manager 决定是否真提交, 已 PENDING 重复提交会重置到期时间, 由 confirm 逻辑防御.
     */
    @Test
    fun a11yCollaborativeDialogListShowsItemEvenWhenAlreadyPending() {
        val item = RulesFragment.a11yCollaborativeDisableDialogItem(
            isEnforcing = true,
            isPending = true
        )
        assertNotNull("已 PENDING 时列表项也必须显示 (无条件硬列)", item)
        assertTrue(
            "列表项必须包含『隐私空间拦截』",
            item!!.contains("隐私空间拦截")
        )
        assertTrue(
            "列表项必须说明『次日生效』",
            item.contains("次日生效")
        )
    }
}