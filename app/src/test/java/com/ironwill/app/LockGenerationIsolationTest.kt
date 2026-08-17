package com.ironwill.app

import com.ironwill.app.service.CoreService
import com.ironwill.app.ui.LockWindowManager
import org.junit.Assert.assertEquals
import org.junit.Test




/**
 * =================================================================================
 * [Context Header]
 * * @file: LockGenerationIsolationTest.kt
 * * @role: Test (WP-1 / Bug 1)
 * * @description: 锁死"无障碍代际失效隔离 + 防卸载锁身份保护"的纯状态判定契约。
 * * @responsibilities:
 *     - A2: G1 普通黑名单锁在 G2 请求下必须重建（STALE_REBUILD），
 *           不得返回同代重复（不得 LOCK_ALREADY_ATTACHED）。
 *     - A3: G1 防卸载锁在 G2 请求下必须保留身份（ANTI_UNINSTALL_PRESERVE），
 *           不得被入站黑名单请求重置/降级。
 *     - 同代重复：普通锁在同一代重复请求 → SAME_GENERATION_DUPLICATE。
 *     - A5: 息屏时防卸载锁跳过守卫保持（非黑名单锁不被息屏 dismiss）。
 * =================================================================================
 *
 * 全部使用纯状态判定 seam（CoreService.classifyGenerationForRequest +
 * LockWindowManager.classifyLockGenerationRequest），JVM 单测不需要真实 WindowManager。
 */
class LockGenerationIsolationTest {

    // ─── CoreService 代际判定 seam ──────────────────────────────────────────

    @Test
    fun coreService_sameGeneration_classifiesSame() {
        val current = CoreService.advanceAccessibilityGenerationForServiceConnected()
        val result = CoreService.classifyGenerationForRequest(
            attachedGeneration = current,
            incomingIsAntiUninstall = false,
            existingIsAntiUninstall = false
        )
        assertEquals("SAME", result)
    }

    @Test
    fun coreService_olderGeneration_classifiesStale() {
        val g1 = CoreService.advanceAccessibilityGenerationForServiceConnected()
        // Simulate a11y reconnect → new generation.
        CoreService.advanceAccessibilityGenerationForServiceConnected()
        val result = CoreService.classifyGenerationForRequest(
            attachedGeneration = g1,
            incomingIsAntiUninstall = false,
            existingIsAntiUninstall = false
        )
        assertEquals("STALE", result)
    }

    @Test
    fun coreService_existingAntiUninstall_outranksAcrossGenerations() {
        val g1 = CoreService.advanceAccessibilityGenerationForServiceConnected()
        CoreService.advanceAccessibilityGenerationForServiceConnected()
        // Even though attached generation is stale, an existing anti-uninstall lock wins.
        val result = CoreService.classifyGenerationForRequest(
            attachedGeneration = g1,
            incomingIsAntiUninstall = false,
            existingIsAntiUninstall = true
        )
        assertEquals("ANTI_UNINSTALL_PRIORITY", result)
    }

    // ─── LockWindowManager 锁请求判定 seam ─────────────────────────────────

    /** A2: G1 普通锁 → G2 请求 → 必须重建（不返回同代重复）。 */
    @Test
    fun lockManager_g1OrdinaryLock_g2Request_rebuilds() {
        val decision = LockWindowManager.classifyLockGenerationRequest(
            isLocked = true,
            isAttached = true,
            attachedGeneration = 1,
            currentGeneration = 2,
            existingIsAntiUninstall = false,
            incomingIsAntiUninstall = false
        )
        assertEquals("STALE_REBUILD", decision)
    }

    /** A3: G1 防卸载锁 → G2 黑名单请求 → 必须保留身份。 */
    @Test
    fun lockManager_g1AntiUninstall_g2BlacklistRequest_preservesIdentity() {
        val decision = LockWindowManager.classifyLockGenerationRequest(
            isLocked = true,
            isAttached = true,
            attachedGeneration = 1,
            currentGeneration = 2,
            existingIsAntiUninstall = true,
            incomingIsAntiUninstall = false
        )
        assertEquals("ANTI_UNINSTALL_PRESERVE", decision)
    }

    /** 同代普通锁重复请求 → 视为同代重复（保持既有去重语义）。 */
    @Test
    fun lockManager_sameGenerationOrdinaryLock_isDuplicate() {
        val decision = LockWindowManager.classifyLockGenerationRequest(
            isLocked = true,
            isAttached = true,
            attachedGeneration = 2,
            currentGeneration = 2,
            existingIsAntiUninstall = false,
            incomingIsAntiUninstall = false
        )
        assertEquals("SAME_GENERATION_DUPLICATE", decision)
    }

    /** 同代防卸载锁重复请求 → 仍优先保留身份，而非被降级。 */
    @Test
    fun lockManager_sameGenerationAntiUninstall_preservesIdentity() {
        val decision = LockWindowManager.classifyLockGenerationRequest(
            isLocked = true,
            isAttached = true,
            attachedGeneration = 2,
            currentGeneration = 2,
            existingIsAntiUninstall = true,
            incomingIsAntiUninstall = false
        )
        assertEquals("ANTI_UNINSTALL_PRESERVE", decision)
    }

    /** 未锁 → 不做代际判定，交由正常创建路径。 */
    @Test
    fun lockManager_notLocked_isNotLocked() {
        val decision = LockWindowManager.classifyLockGenerationRequest(
            isLocked = false,
            isAttached = false,
            attachedGeneration = -1,
            currentGeneration = 2,
            existingIsAntiUninstall = false,
            incomingIsAntiUninstall = false
        )
        assertEquals("NOT_LOCKED", decision)
    }

    /** isLocked=true 但 view 未附着 → 判定为陈旧需重建（幽灵锁）。 */
    @Test
    fun lockManager_lockedButDetached_rebuilds() {
        val decision = LockWindowManager.classifyLockGenerationRequest(
            isLocked = true,
            isAttached = false,
            attachedGeneration = 2,
            currentGeneration = 2,
            existingIsAntiUninstall = false,
            incomingIsAntiUninstall = false
        )
        assertEquals("STALE_REBUILD", decision)
    }

    // ─── A5: 息屏防卸载锁跳过守卫 ──────────────────────────────────────────

    /** 防卸载锁（非黑名单锁）不被息屏 dismiss。 */
    @Test
    fun screenOff_antiUninstallLock_isNotDismissed() {
        // isAntiUninstall 锁的 isBlacklistLock=false → 息屏守卫应跳过 dismiss。
        val shouldDismiss = LockWindowManager.shouldDismissForScreenOff(
            isAlarmOverlayActive = false,
            isLocked = true,
            isBlacklistLock = false
        )
        assertEquals(false, shouldDismiss)
    }

    /** 黑名单锁息屏时正常 dismiss。 */
    @Test
    fun screenOff_blacklistLock_isDismissed() {
        val shouldDismiss = LockWindowManager.shouldDismissForScreenOff(
            isAlarmOverlayActive = false,
            isLocked = true,
            isBlacklistLock = true
        )
        assertEquals(true, shouldDismiss)
    }
}
