package com.zhizhi.app

import com.zhizhi.app.service.ForegroundSessionTracker
import com.zhizhi.app.service.RuleBoundaryLockDecisionInput
import com.zhizhi.app.service.RuleBoundaryLockGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForegroundSessionTrackerTest {

    private lateinit var tracker: ForegroundSessionTracker

    @Before
    fun setUp() {
        tracker = ForegroundSessionTracker()
    }

    @Test
    fun `case 1 - continuous foreground across rule boundary allows session lock`() {
        // 22:59 微信 FOREGROUND
        val t2259 = 1_000_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t2259)

        // 23:00 BLOCK 生效 (1分钟后，超过 8 秒)，无新的 FG 事件，无 BG 事件
        val t2300 = t2259 + 60_000L

        // 应确认微信 Session 仍处于未关闭的活跃前台状态
        assertTrue(tracker.isSessionActiveFor("com.tencent.mm"))
        assertEquals("com.tencent.mm", tracker.currentSession?.packageName)
        assertFalse(tracker.currentSession?.closed ?: true)
    }

    @Test
    fun `case 2 - returned to launcher before boundary does not lock target app`() {
        // 22:59 微信 FOREGROUND
        val t2259 = 1_000_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t2259)

        // 22:59:30 微信 BACKGROUND + Launcher FOREGROUND
        val t225930 = t2259 + 30_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_BG, t225930)
        tracker.onUsageEvent("com.android.launcher3", ForegroundSessionTracker.MOVE_TO_FG, t225930)

        // 23:00 BLOCK 生效
        assertFalse("微信已退到后台且切到桌面，不应有活跃 Session", tracker.isSessionActiveFor("com.tencent.mm"))
        assertTrue("当前活跃 Session 应当是桌面 Launcher", tracker.isSessionActiveFor("com.android.launcher3"))
    }

    @Test
    fun `case 3 - stale cached package with closed session does not lock`() {
        // 历史缓存存在微信，但微信 Session 之前已被 PAUSED/STOPPED 关闭
        val t0 = 1_000_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t0)
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.ACTIVITY_STOPPED, t0 + 10_000L)

        // 假设上层还残留着历史 lastPkg = "com.tencent.mm"
        val cachedLastPkg = "com.tencent.mm"

        // 23:00 BLOCK 生效
        assertFalse("虽然历史包名是微信，但前台 Session 已关闭，不能凭旧缓存误锁", tracker.isSessionActiveFor(cachedLastPkg))
    }

    @Test
    fun `case 4 - watching video for 10 minutes without lifecycle events keeps session active`() {
        // 微信进入前台
        val tStart = 1_000_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, tStart)

        // 持续 10 分钟没有生命周期事件，期间没有任何离开反证
        val t10MinLater = tStart + 10 * 60 * 1_000L

        // 规则在第 10 分钟开始生效，应判定微信仍处于当前前台 Session
        assertTrue("10分钟无新事件但无反证，Session 应继续保持活跃", tracker.isSessionActiveFor("com.tencent.mm"))
    }

    @Test
    fun `case 5 - background audio app refreshing usage stats has no foreground session`() {
        // 用户在前台使用其它应用或桌面
        val t0 = 1_000_000L
        tracker.onUsageEvent("com.android.launcher3", ForegroundSessionTracker.MOVE_TO_FG, t0)

        // 音乐 App (com.luna.music) 仅在后台播放，从未发生 MOVE_TO_FG / ACTIVITY_RESUMED
        // 即便其在系统 UsageStats 中不断刷新，SessionTracker 也绝对没有它的未关闭 Session
        assertFalse("后台音频播放 App 不能建立前台 Session", tracker.isSessionActiveFor("com.luna.music"))
    }

    @Test
    fun `case 6 - service reboot with stale persistent lastPkg cannot lock directly`() {
        // MonitorService 重启，生成全新的 Tracker 实例
        val freshTracker = ForegroundSessionTracker()

        // 只有旧持久化 lastPkg = "com.tencent.mm"
        val persistentLastPkg = "com.tencent.mm"

        // 本次进程尚未通过 UsageEvents 收到任何前台事件
        assertNull("重启后无任何 Session", freshTracker.currentSession)
        assertFalse("服务重启且未获得本次进程的前台事件时，不允许直接锁", freshTracker.isSessionActiveFor(persistentLastPkg))
    }

    @Test
    fun `noise packages like systemui and ime do not kill active session`() {
        val t0 = 1_000_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t0)

        // 下拉通知栏 (SystemUI) 产生事件
        tracker.onUsageEvent("com.android.systemui", ForegroundSessionTracker.MOVE_TO_FG, t0 + 2_000L)

        // 弹出输入法产生事件
        tracker.onUsageEvent("com.sohu.inputmethod.sogou", ForegroundSessionTracker.MOVE_TO_FG, t0 + 4_000L)

        // 微信会话应依然保持活跃
        assertTrue("系统UI与输入法噪声不应破坏当前前台应用 Session", tracker.isSessionActiveFor("com.tencent.mm"))
    }

    @Test
    fun `screen off closes active session`() {
        val t0 = 1_000_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t0)
        assertTrue(tracker.isSessionActiveFor("com.tencent.mm"))

        tracker.onScreenOff(t0 + 5_000L)
        assertFalse("屏幕熄灭后，Session 应标记为关闭", tracker.isSessionActiveFor("com.tencent.mm"))
    }

    @Test
    fun `keyguard locked closes active session`() {
        val t0 = 1_000_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t0)
        assertTrue(tracker.isSessionActiveFor("com.tencent.mm"))

        tracker.onKeyguardLocked(t0 + 5_000L)
        assertFalse("锁屏后，Session 应标记为关闭", tracker.isSessionActiveFor("com.tencent.mm"))
    }

    // ── RuleBoundaryLockGate 单元测试 ──

    @Test
    fun `rule boundary lock eligible on transition from allow to block`() {
        val input = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            wasPreviouslyBlocked = false, // 之前未阻塞 -> 刚进入阻塞，发生边界跃迁
            wasGap = false,
            wasRest = false,
            forceBoundaryCheck = false,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertTrue("规则发生 ALLOW -> BLOCK 跃迁且有活跃 Session 时应允许锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(input))
    }

    @Test
    fun `steady state block does not retrigger boundary lock`() {
        val input = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            wasPreviouslyBlocked = true, // 之前已阻塞 -> 处于稳态，无边界跃迁
            wasGap = false,
            wasRest = false,
            forceBoundaryCheck = false,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse("处于持续 BLOCK 稳态时，禁止重复触发边界锁定，防止无限重锁", RuleBoundaryLockGate.shouldAllowBoundaryLock(input))
    }

    @Test
    fun `mode transition after gap or rest retriggers boundary lock`() {
        val inputFromGap = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            wasPreviouslyBlocked = true,
            wasGap = true, // 刚刚处于 Gap 模式，现在跨越边界
            wasRest = false,
            forceBoundaryCheck = false,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertTrue("Gap 结束跨入 Block 边界应允许触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(inputFromGap))

        val inputFromRest = inputFromGap.copy(wasGap = false, wasRest = true)
        assertTrue("Rest 休息结束跨入 Block 边界应允许触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(inputFromRest))
    }

    @Test
    fun `screen off or keyguard locked blocks boundary lock`() {
        val base = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            wasPreviouslyBlocked = false,
            wasGap = false,
            wasRest = false,
            forceBoundaryCheck = false,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )

        val keyguardLockedInput = base.copy(isKeyguardLocked = true)
        assertFalse("Keyguard 处于锁屏状态时不应触发边界锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(keyguardLockedInput))

        val screenOffInput = base.copy(isScreenInteractive = false)
        assertFalse("屏幕息屏（非交互状态）时不应触发边界锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(screenOffInput))
    }

    @Test
    fun `missing active session blocks boundary lock`() {
        val input = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            wasPreviouslyBlocked = false,
            wasGap = false,
            wasRest = false,
            forceBoundaryCheck = false,
            hasActiveForegroundSession = false, // 无活跃 Session（已切出或关闭）
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse("无活跃前台 Session 时不应触发边界锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(input))
    }

    @Test
    fun `not blocked now blocks boundary lock`() {
        val input = RuleBoundaryLockDecisionInput(
            isBlockedNow = false, // 当前并不在黑名单/勿扰模式
            wasPreviouslyBlocked = false,
            wasGap = false,
            wasRest = false,
            forceBoundaryCheck = false,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse("当前应用不属于受限状态时不应触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(input))
    }

    @Test
    fun `force boundary check flag forces boundary lock`() {
        val input = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            wasPreviouslyBlocked = true, // 即便先前已阻断
            wasGap = false,
            wasRest = false,
            forceBoundaryCheck = true, // 显式触发了强制边界检查
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertTrue("设置了强制边界检查标记时应允许触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(input))
    }
}
