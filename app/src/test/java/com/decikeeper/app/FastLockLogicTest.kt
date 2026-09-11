/**
 * =================================================================================
 * [Context Header]
 * * @file: FastLockLogicTest.kt
 * * @role: Test
 * * @description: 系统初始化快速锁屏策略的单元测试。
 * * @responsibilities:
 * 1. 验证重启/进程恢复期间 FastLockService 的启动条件。
 * 2. 验证主程序接管时最短持有与最长兜底规则。
 * * @context:
 * - 上游: Gradle Unit Test
 * - 下游: MyApplication, StartupProtectionManager
 * * @constraints (必读):
 * 1. 仅测试纯策略，不启动 Android Service。
 * 2. 不依赖真实系统权限状态。
 * 3. 必须覆盖无障碍已开启但尚未连接的重启空窗。
 * =================================================================================
 */
package com.decikeeper.app

import com.decikeeper.app.manager.StartupProtectionManager
import org.junit.Assert.*
import org.junit.Test



class FastLockLogicTest {

    @Test
    fun testShouldStartFastLock() {
        // 1. Scene: Process Restart (Language Change) with Service Not Active
        // Expected Input: A11y is enabled, but CoreService has not connected after reboot yet.
        // Expected Output: TRUE, because FastLockService must cover the startup anti-uninstall gap.
        val shouldLock_Restart = MyApplication.shouldStartFastLock(
            isInSegment = true,
            isAdaptation = false,
            hasOverlay = true,
            isA11yEnabled = true,
            isA11yActive = false // Key Verification Point: Service NOT active yet
        )
        
        assertTrue("A11y enabled but not connected should still trigger fast lock", shouldLock_Restart)

        // 2. Scene: Service Disabled by User
        val shouldLock_Disabled = MyApplication.shouldStartFastLock(
            isInSegment = true,
            isAdaptation = false,
            hasOverlay = true,
            isA11yEnabled = false, // User explicitly disabled it
            isA11yActive = false
        )
        assertFalse("Disabled service should NOT trigger fast lock", shouldLock_Disabled)
        
        // 3. Scene: Adaptation Period (User setup time)
        val shouldLock_Adapt = MyApplication.shouldStartFastLock(
            isInSegment = true,
            isAdaptation = true,
            hasOverlay = true,
            isA11yEnabled = true,
            isA11yActive = true
        )
        assertFalse("Adaptation period should NOT trigger fast lock", shouldLock_Adapt)
    }

    @Test
    fun testFastLockHandoffHoldWindow() {
        // Expected Input: CoreService requests handoff after 1.5s.
        // Expected Output: FastLockService must remain for another 8.5s.
        val earlyRemain = StartupProtectionManager.remainingFastLockHoldMs(
            startedAtMs = 0L,
            requestAtMs = StartupProtectionManager.CORE_HANDOFF_REQUEST_DELAY_MS
        )
        assertEquals(6_000L, earlyRemain)

        // Expected Input: CoreService requests handoff after 10s.
        // Expected Output: FastLockService may close immediately.
        val readyRemain = StartupProtectionManager.remainingFastLockHoldMs(
            startedAtMs = 0L,
            requestAtMs = StartupProtectionManager.FAST_LOCK_MIN_VISIBLE_MS
        )
        assertEquals(0L, readyRemain)

        assertEquals(10_000L, StartupProtectionManager.FAST_LOCK_MAX_VISIBLE_MS)
    }

    @Test
    fun testEarlyBootFastLockWindow() {
        assertTrue(
            StartupProtectionManager.shouldStartEarlyBootFastLock(
                uptimeMs = 30_000L,
                isA11yEnabled = true
            )
        )

        assertFalse(
            StartupProtectionManager.shouldStartEarlyBootFastLock(
                uptimeMs = 180_000L,
                isA11yEnabled = true
            )
        )

        assertFalse(
            StartupProtectionManager.shouldStartEarlyBootFastLock(
                uptimeMs = 30_000L,
                isA11yEnabled = false
            )
        )
    }
}
