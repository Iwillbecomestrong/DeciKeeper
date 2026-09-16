package com.decikeeper.app.manager

import com.decikeeper.app.service.CoreService
import com.decikeeper.app.ui.LockWindowManager
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
 *     - A5: 息屏不是解锁出口，任何已显示锁都不得因息屏被主动 dismiss。
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
        val result = CoreService.classifyGenerationForRequest(
            attachedGeneration = g1,
            incomingIsAntiUninstall = false,
            existingIsAntiUninstall = true
        )
        assertEquals("ANTI_UNINSTALL_PRIORITY", result)
    }

    // ─── LockWindowManager 锁请求判定 seam ─────────────────────────────────

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

    // ─── A5: 息屏不是解锁出口 ─────────────────────────────────────────────

    @Test
    fun screenOff_antiUninstallLock_isNotDismissed() {
        val shouldDismiss = LockWindowManager.shouldDismissForScreenOff(
            isAlarmOverlayActive = false,
            isLocked = true,
            isBlacklistLock = false
        )
        assertEquals(false, shouldDismiss)
    }

    @Test
    fun screenOff_blacklistLock_isNotDismissed() {
        val shouldDismiss = LockWindowManager.shouldDismissForScreenOff(
            isAlarmOverlayActive = false,
            isLocked = true,
            isBlacklistLock = true
        )
        assertEquals(false, shouldDismiss)
    }
}
