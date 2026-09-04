/**
 * =================================================================================
 * [Context Header]
 * * @file: PrivacyConsentDecisionTest.kt
 * * @role: Test
 * * @description: PrivacyConsentManager 决策函数与命名契约的单元测试。
 * * @responsibilities:
 * 1. 验证 shouldInitializePostConsentComponents 的纯函数决策。
 * 2. 锁定 PREFS_NAME / KEY_* 命名契约（防止后续误改导致备份 exclude 失效）。
 * 3. 锁定 KEY_* 全部以 "consent_" 开头（备份规则基于此通配 exclude）。
 * * @context:
 * - 上游: Gradle Unit Test
 * * @constraints (必读):
 * 1. 不依赖 Android Context 真实实例（Context 抽象方法过多，参见现有 FastLockLogicTest 同样做法）。
 * 2. 不依赖 SharedPreferences 实现。
 * 3. 涉及 Android Context / SharedPreferences 的集成测试需要真机验证。
 * =================================================================================
 */
package com.zhizhi.app

import com.zhizhi.app.manager.PrivacyConsentManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test



class PrivacyConsentDecisionTest {

    // ── 纯函数决策：post-consent gate ────────────────────────────────

    @Test
    fun postConsentGateFalseWhenNotConsented() {
        // 默认未同意 → 跳过 post-consent 组件（不能"同意前开始收集"）
        assertFalse(PrivacyConsentManager.shouldInitializePostConsentComponents(false))
    }

    @Test
    fun postConsentGateTrueWhenConsented() {
        // 已同意 → 允许 post-consent 组件初始化
        assertTrue(PrivacyConsentManager.shouldInitializePostConsentComponents(true))
    }

    // ── 命名契约：防止后续误改导致备份规则 / 解析失败 ─────────────

    @Test
    fun prefsNameAndKeyContract() {
        // 锁定命名契约：这些名字与 backup_rules.xml / data_extraction_rules.xml 中的
        // <exclude domain="sharedpref" path="privacy_consent_prefs.xml"/> 直接挂钩。
        assertEquals("privacy_consent_prefs", PrivacyConsentManager.PREFS_NAME)
        assertEquals("consent_accepted", PrivacyConsentManager.KEY_CONSENTED)
        assertEquals("consent_accepted_at_ms", PrivacyConsentManager.KEY_CONSENTED_AT_MS)
        assertEquals("consent_accepted_policy_version", PrivacyConsentManager.KEY_CONSENTED_POLICY_VERSION)
    }

    @Test
    fun consentKeysAllStartWithConsentPrefix() {
        // 备份规则基于 "consent_" 前缀做语义辨识；任意 key 改名都应保留前缀。
        val keys = listOf(
            PrivacyConsentManager.KEY_CONSENTED,
            PrivacyConsentManager.KEY_CONSENTED_AT_MS,
            PrivacyConsentManager.KEY_CONSENTED_POLICY_VERSION
        )
        for (k in keys) {
            assertTrue("key '$k' 必须以 consent_ 开头", k.startsWith("consent_"))
        }
    }

    @Test
    fun prefsNameDoesNotContainPathSeparator() {
        // PrivacyConsentManager.PREFS_NAME 应仅是文件名，不含路径分隔符
        // (Android 端 getSharedPreferences 会自动追加 .xml 后缀)。
        assertFalse(
            "PREFS_NAME 不应包含 '/'",
            PrivacyConsentManager.PREFS_NAME.contains("/")
        )
        assertFalse(
            "PREFS_NAME 不应包含 '\\\\'",
            PrivacyConsentManager.PREFS_NAME.contains("\\")
        )
    }

    // ── Round 2: markConsented 同步落盘契约（API 形态锁死） ────
    //
    // 真实 commit 成功 / 失败路径依赖 SharedPreferences + 磁盘 I/O，无法在纯 JVM 单测中
    // 触发（仓库无 Robolectric，参见 build.gradle.kts 的 testImplementation 列表）。
    // 这里只能锁定 API 形态：返回值必须是 Boolean，让调用方能在 commit 失败时
    // 阻止用户进入主页（合规闸门不能形同虚设）。
    //
    // 运行时行为由 PrivacyConsentActivity.onAcceptClicked 在 commit==false 时
    // 弹 Toast 并 return 不跳主页来保证 — 该分支的真机验证属于 MAJOR-3
    // （EMULATOR_TEST_REPORT 当前状态 BLOCKED_NEEDS_REAL_DEVICE）。

    @Test
    fun markConsentedHasBooleanReturnType() {
        // 通过反射锁定 markConsented 的返回类型为 Boolean（防止后续误改成 Unit / apply()）
        val method = PrivacyConsentManager::class.java.getDeclaredMethod(
            "markConsented",
            android.content.Context::class.java,
            String::class.java
        )
        assertEquals(
            "markConsented 必须返回 Boolean（commit 失败时调用方需据此阻止进入主页）",
            java.lang.Boolean.TYPE,
            method.returnType
        )
    }
}
