/**
 * =================================================================================
 * [Context Header]
 * * @file: PendingMainActivityExtrasContractTest.kt
 * * @role: Test
 * * @description: 锁死 PendingMainActivityExtras 的契约 — 不依赖 Android Context。
 * * @responsibilities:
 * 1. 锁死 PREFS_NAME 常量，防止后续改名导致 backup_rules.xml 排除路径失效。
 * 2. 锁死 save/consume/clear 方法签名（JVM 单测只能锁形态，真机验证持久化路径）。
 * 3. 锁死 PrivacyConsentActivity.EXTRA_HAS_PENDING_EXTRAS 常量。
 * * @constraints (必读):
 * 1. 不依赖 Android Context 真实实例（仓库无 Robolectric）。
 * 2. 真机的 extras 回放路径由 emulator-tester 验证。
 * =================================================================================
 */
package com.ironwill.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMainActivityExtrasContractTest {

    @Test
    fun prefsNameContract() {
        // 与 backup_rules.xml 不直接挂钩（pending 不参与备份），但仍是契约：
        // 独立 prefs 文件，避免和 consent prefs 耦合。
        assertEquals("pending_main_activity_extras", PendingMainActivityExtras.PREFS_NAME)
    }

    @Test
    fun hasPendingExtrasExtraConstant() {
        // PrivacyConsentActivity.EXTRA_HAS_PENDING_EXTRAS 必须存在且非空，
        // 后续如果改名字会导致 MainActivityEntry.redirectToConsent 与
        // PrivacyConsentActivity 之间的协议断裂。
        assertEquals(
            "ironwill.consent.has_pending_extras",
            PrivacyConsentActivity.EXTRA_HAS_PENDING_EXTRAS
        )
        assertTrue(
            "EXTRA_HAS_PENDING_EXTRAS 必须非空",
            PrivacyConsentActivity.EXTRA_HAS_PENDING_EXTRAS.isNotEmpty()
        )
    }

    @Test
    fun saveAndConsumeArePresent() {
        // 通过反射锁方法签名，避免后续被改成 Unit / private。
        val save = PendingMainActivityExtras::class.java.getDeclaredMethod(
            "save",
            android.content.Context::class.java,
            android.content.Intent::class.java
        )
        assertEquals(java.lang.Void.TYPE, save.returnType)

        val consume = PendingMainActivityExtras::class.java.getDeclaredMethod(
            "consume",
            android.content.Context::class.java
        )
        assertEquals(
            "consume 必须返回 android.content.Intent? 让调用方决定是否回放",
            android.content.Intent::class.java,
            consume.returnType
        )

        val hasPending = PendingMainActivityExtras::class.java.getDeclaredMethod(
            "hasPending",
            android.content.Context::class.java
        )
        assertEquals(java.lang.Boolean.TYPE, hasPending.returnType)

        val clear = PendingMainActivityExtras::class.java.getDeclaredMethod(
            "clear",
            android.content.Context::class.java
        )
        assertEquals(java.lang.Void.TYPE, clear.returnType)
    }

    @Test
    fun privacyConsentActivityGoToMainAndFinishIsPresent() {
        // 锁死 PrivacyConsentActivity.goToMainAndFinish 存在且返回 Unit。
        // 该方法在本轮被改造为"回放 pending extras"，必须仍私有且 void 返回。
        val method = PrivacyConsentActivity::class.java.getDeclaredMethod("goToMainAndFinish")
        assertTrue("goToMainAndFinish 必须是私有方法", java.lang.reflect.Modifier.isPrivate(method.modifiers))
        assertEquals(java.lang.Void.TYPE, method.returnType)
    }

    @Test
    fun pendingMainActivityExtrasDoesNotUseObjectOutputStream() {
        // REQ-5 Round 4 Fix #1 防回归:之前 round 3 用 ObjectOutputStream 序列化
        // Bundle,运行时抛 NotSerializableException(Bundle 不是 Serializable)。
        // 本轮重写为 JSONObject 白名单,源代码中不能再出现 ObjectOutputStream
        // / ObjectInputStream / writeObject(bundle) / Serializable 等关键字。
        val candidates = listOf(
            java.io.File("src/main/java/com/ironwill/app/PendingMainActivityExtras.kt"),
            java.io.File("app/src/main/java/com/ironwill/app/PendingMainActivityExtras.kt")
        )
        var src: String? = null
        for (f in candidates) {
            if (f.exists()) {
                src = f.readText(Charsets.UTF_8)
                break
            }
        }
        checkNotNull(src) {
            "PendingMainActivityExtras.kt not found in any of: " +
                candidates.joinToString { it.absolutePath }
        }
        assertTrue(
            "PendingMainActivityExtras.kt 已禁止再用 ObjectOutputStream 序列化 Bundle",
            !src.contains("ObjectOutputStream") &&
                !src.contains("ObjectInputStream") &&
                !src.contains("writeObject(bundle)")
        )
        assertTrue(
            "PendingMainActivityExtras.kt 必须改用 JSONObject 白名单路径",
            src.contains("JSONObject") && src.contains("bundleToWhitelistJson")
        )
    }

    @Test
    fun pendingMainActivityExtrasHasWhitelistOnly() {
        // 防回归:白名单常量必须存在且恰好包含 String/Bool/Int/Long 四类。
        val candidates = listOf(
            java.io.File("src/main/java/com/ironwill/app/PendingMainActivityExtras.kt"),
            java.io.File("app/src/main/java/com/ironwill/app/PendingMainActivityExtras.kt")
        )
        var src: String? = null
        for (f in candidates) {
            if (f.exists()) {
                src = f.readText(Charsets.UTF_8)
                break
            }
        }
        checkNotNull(src)
        // 必须显式 import 这些基础类型标记
        val requiredMarkers = listOf(
            "String::class.java",
            "Boolean::class.java",
            "Integer::class.java",
            "Long::class.java",
            "SUPPORTED_TYPES"
        )
        for (m in requiredMarkers) {
            assertTrue(
                "PendingMainActivityExtras.kt 必须包含白名单标记 $m",
                src.contains(m)
            )
        }
    }
}