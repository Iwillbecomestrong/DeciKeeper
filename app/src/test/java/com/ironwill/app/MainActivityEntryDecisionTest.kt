/**
 * =================================================================================
 * [Context Header]
 * * @file: MainActivityEntryDecisionTest.kt
 * * @role: Test
 * * @description: MainActivityEntry.decideMainActivityEntry() 纯函数决策的单测。
 * * @responsibilities:
 * 1. 验证同意状态 + 调用来源 → EntryAction 的三段映射：
 *    - 已同意 → PROCEED（无论来源）
 *    - 未同意 + NORMAL → REDIRECT_TO_CONSENT（让用户先看协议）
 *    - 未同意 + SYSTEM  → DENY（系统级唤醒不应强行弹 UI）
 * 2. 锁死 Enum 命名契约（CallSource.NORMAL / SYSTEM，EntryAction.PROCEED / REDIRECT / DENY），
 *    防止后续误改导致旁路入口失效。
 * 3. 覆盖 enum 全量穷举（isConsented × CallSource = 2 × 2 = 4 条用例）。
 * * @context:
 * - 上游: Gradle Unit Test
 * - 下游: MainActivity, MainActivityEntry, PrivacyConsentManager, PrivacyConsentActivity
 * * @constraints (必读):
 * 1. 仅测试纯函数（不依赖 Android Context / SharedPreferences）。
 * 2. 真正的运行时契约（redirectToConsent / startFromSystem 的 startActivity 分支）由
 *    MainActivity.onCreate 的 isConsented 防线 + 真机验证覆盖（参见 EMULATOR_TEST_REPORT
 *    MAJOR-3 状态 BLOCKED_NEEDS_REAL_DEVICE）。
 * =================================================================================
 */
package com.ironwill.app

import com.ironwill.app.ui.MainActivityEntry.CallSource
import com.ironwill.app.ui.MainActivityEntry.EntryAction
import com.ironwill.app.ui.MainActivityEntry.decideMainActivityEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MainActivityEntryDecisionTest {

    // ── 主路径 ─────────────────────────────────────────────────

    @Test
    fun consentedNormalProceeds() {
        // 已同意 + 普通跳转 → 正常启动 MainActivity
        assertEquals(
            EntryAction.PROCEED,
            decideMainActivityEntry(isConsented = true, callSource = CallSource.NORMAL)
        )
    }

    @Test
    fun consentedSystemProceeds() {
        // 已同意 + 系统级唤醒 → 也正常启动 MainActivity
        // （系统级唤醒来源在已同意用户上不需特殊处理）
        assertEquals(
            EntryAction.PROCEED,
            decideMainActivityEntry(isConsented = true, callSource = CallSource.SYSTEM)
        )
    }

    @Test
    fun notConsentedNormalRedirectsToConsent() {
        // 未同意 + 普通跳转（通知点击 / 应用内跳转）→ 改投 PrivacyConsentActivity
        assertEquals(
            EntryAction.REDIRECT_TO_CONSENT,
            decideMainActivityEntry(isConsented = false, callSource = CallSource.NORMAL)
        )
    }

    @Test
    fun notConsentedSystemDenies() {
        // 未同意 + 系统级唤醒（BootReceiver 开机 / Direct Boot）→ 静默拒绝
        // 理由：LOCKED_BOOT_COMPLETED 时设备未解锁，弹同意页既不可见又会产生副作用
        assertEquals(
            EntryAction.DENY,
            decideMainActivityEntry(isConsented = false, callSource = CallSource.SYSTEM)
        )
    }

    // ── 契约锁死 ─────────────────────────────────────────────

    @Test
    fun entryActionEnumHasExactlyThreeValues() {
        // 三值枚举锁死：PROCEED / REDIRECT_TO_CONSENT / DENY
        // 新增 / 删除 / 改名任何一项都会破坏 MainActivity.start() 的 when 分支。
        assertEquals(3, EntryAction.values().size)
    }

    @Test
    fun callSourceEnumHasExactlyTwoValues() {
        // 二值枚举锁死：NORMAL / SYSTEM
        // 新增 / 删除 / 改名任何一项都会破坏调用方传参与 decideMainActivityEntry 分支。
        assertEquals(2, CallSource.values().size)
    }

    @Test
    fun systemCallSourceMustNotRedirect() {
        // 关键安全契约：SYSTEM 来源**永远不能**返回 REDIRECT_TO_CONSENT
        // （系统级路径强弹 UI 会带来 DIRECT_BOOT 可见性 / Activity stack 污染等风险）
        val actions = CallSource.values().map { src ->
            decideMainActivityEntry(isConsented = false, callSource = src)
        }
        assertNotEquals(
            "SYSTEM 来源不应触发 REDIRECT_TO_CONSENT",
            EntryAction.REDIRECT_TO_CONSENT,
            actions[CallSource.SYSTEM.ordinal]
        )
    }

    @Test
    fun normalCallSourceMustNotDenySilently() {
        // 关键安全契约：NORMAL 来源**永远不能**返回 DENY
        // （业务方调用入口被静默拒绝会导致通知点击 / 跳转失效，用户无任何反馈）
        val action = decideMainActivityEntry(isConsented = false, callSource = CallSource.NORMAL)
        assertNotEquals(
            "NORMAL 来源不应触发 DENY",
            EntryAction.DENY,
            action
        )
    }
}