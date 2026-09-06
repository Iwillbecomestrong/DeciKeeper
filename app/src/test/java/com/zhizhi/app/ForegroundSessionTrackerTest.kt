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
            isBusinessAllowToBlock = true, // 明确发生 ALLOW -> BLOCK 业务边沿跃迁
            leftGap = false,
            endedRest = false,
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
            isBusinessAllowToBlock = false, // 处于稳态 BLOCK，无 ALLOW->BLOCK 跃迁
            leftGap = false,
            endedRest = false,
            forceBoundaryCheck = false,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse("处于持续 BLOCK 稳态时，禁止重复触发边界锁定，防止无限重锁", RuleBoundaryLockGate.shouldAllowBoundaryLock(input))
    }

    @Test
    fun `steady state gap or rest does not trigger boundary lock`() {
        // 稳态处于 Gap 自由时段中，应用已被阻塞但没有发生边沿跃迁
        val steadyGap = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            isBusinessAllowToBlock = false,
            leftGap = false, // 处于稳态 Gap，并未发生离开 Gap 跃迁
            endedRest = false,
            forceBoundaryCheck = false,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse("稳态 Gap 期间不应误把 level 当 edge 触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(steadyGap))

        val steadyRest = steadyGap.copy(endedRest = false)
        assertFalse("稳态 Rest 期间不应触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(steadyRest))
    }

    @Test
    fun `mode transition after gap or rest retriggers boundary lock`() {
        val inputFromGap = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            isBusinessAllowToBlock = false,
            leftGap = true, // 刚离开 Gap 模式进入时段，边沿跃迁
            endedRest = false,
            forceBoundaryCheck = false,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertTrue("Gap 结束跨入 Block 边界应允许触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(inputFromGap))

        val inputFromRest = inputFromGap.copy(leftGap = false, endedRest = true)
        assertTrue("Rest 休息结束跨入 Block 边界应允许触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(inputFromRest))
    }

    @Test
    fun `screen off or keyguard locked blocks boundary lock`() {
        val base = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            isBusinessAllowToBlock = true,
            leftGap = false,
            endedRest = false,
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
            isBusinessAllowToBlock = true,
            leftGap = false,
            endedRest = false,
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
            isBusinessAllowToBlock = false,
            leftGap = false,
            endedRest = false,
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
            isBusinessAllowToBlock = false,
            leftGap = false,
            endedRest = false,
            forceBoundaryCheck = true, // 显式触发了强制边界检查
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertTrue("设置了强制边界检查标记时应允许触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(input))
    }

    @Test
    fun `app window closing during gap triggers rule boundary lock`() {
        val pkg = "com.bilibili.app"
        val t0 = 1_000_000L

        // 用户在 Gap 期间、时间窗尚开放时进入该应用
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.MOVE_TO_FG, t0)
        assertTrue(tracker.isSessionActiveFor(pkg))

        // 持续使用超过 8s 后时间窗关闭（发生 ALLOW -> BLOCK 跃迁）
        // 模拟 LockDecisionPolicy 判定从 false -> true，isBusinessAllowToBlock 变为 true
        val blockedNow = true
        val isBusinessAllowToBlock = true // 之前时间窗开放为 ALLOW，现在关闭为 BLOCK

        val boundaryDecision = RuleBoundaryLockGate.shouldAllowBoundaryLock(
            RuleBoundaryLockDecisionInput(
                isBlockedNow = blockedNow,
                isBusinessAllowToBlock = isBusinessAllowToBlock,
                leftGap = false,
                endedRest = false,
                forceBoundaryCheck = false,
                hasActiveForegroundSession = tracker.isSessionActiveFor(pkg),
                isKeyguardLocked = false,
                isScreenInteractive = true
            )
        )
        assertTrue("应用时间窗在 Gap 期关闭时，未关闭的活跃前台 Session 必须触发边界即时锁定", boundaryDecision)
    }

    @Test
    fun `keyguard lock closes session permanently so unlock without new event cannot lock`() {
        val pkg = "com.tencent.mm"
        val t0 = 1_000_000L

        // 进入前台
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.MOVE_TO_FG, t0)
        assertTrue(tracker.isSessionActiveFor(pkg))

        // 用户按电源键锁屏（Keyguard locked）
        tracker.onKeyguardLocked(t0 + 5_000L)
        assertFalse("锁屏后 Session 必须立即失效关闭", tracker.isSessionActiveFor(pkg))

        // 用户解锁屏幕（此时若没有新的 MOVE_TO_FG 事件），旧 Session 依然保持关闭
        assertFalse("解锁后若无新前台事件，旧 Session 依然必须无效", tracker.isSessionActiveFor(pkg))

        // 此时规则发生任何变化都不应因旧 Session 误触发
        val boundaryDecision = RuleBoundaryLockGate.shouldAllowBoundaryLock(
            RuleBoundaryLockDecisionInput(
                isBlockedNow = true,
                isBusinessAllowToBlock = false,
                leftGap = false,
                endedRest = false,
                forceBoundaryCheck = true,
                hasActiveForegroundSession = tracker.isSessionActiveFor(pkg),
                isKeyguardLocked = false,
                isScreenInteractive = true
            )
        )
        assertFalse("无活跃 Session 时绝对不能触发边界锁定", boundaryDecision)
    }

    @Test
    fun `vivo systemui and upslide noise packages do not kill session but launcher does`() {
        val t0 = 1_000_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t0)
        assertTrue(tracker.isSessionActiveFor("com.tencent.mm"))

        // Vivo 状态栏/通知栏插件
        tracker.onUsageEvent("com.vivo.systemuiplugin", ForegroundSessionTracker.MOVE_TO_FG, t0 + 1_000L)
        assertTrue("com.vivo.systemuiplugin 不应破坏前台 Session", tracker.isSessionActiveFor("com.tencent.mm"))

        // Vivo 系统导航手势
        tracker.onUsageEvent("com.vivo.upslide", ForegroundSessionTracker.MOVE_TO_FG, t0 + 2_000L)
        assertTrue("com.vivo.upslide 不应破坏前台 Session", tracker.isSessionActiveFor("com.tencent.mm"))

        // 系统桌面 Launcher 切入
        tracker.onUsageEvent("com.bbk.launcher2", ForegroundSessionTracker.MOVE_TO_FG, t0 + 3_000L)
        assertFalse("桌面 Launcher 切入必须替换/关闭原前台 Session", tracker.isSessionActiveFor("com.tencent.mm"))
    }

    @Test
    fun `same millisecond resume and paused events close session correctly`() {
        val t0 = 1_000_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t0)
        assertTrue(tracker.isSessionActiveFor("com.tencent.mm"))

        // 在同一毫秒内先后收到 RESUMED 与 PAUSED
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.ACTIVITY_PAUSED, t0)
        assertFalse("同一毫秒内的离开反证事件必须正常生效并关闭 Session", tracker.isSessionActiveFor("com.tencent.mm"))
    }

    @Test
    fun `activity transition A paused then B resumed then A stopped preserves active session`() {
        val pkg = "com.tencent.mm"
        val classA = "com.tencent.mm.ui.LauncherUI"
        val classB = "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI"
        val t0 = 1_000_000L

        // 1. Activity A 进入前台
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0, classA)
        assertTrue(tracker.isSessionActiveFor(pkg))

        // 2. Activity A 暂停准备打开 Activity B (同毫秒或紧随其后)
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_PAUSED, t0 + 10L, classA)

        // 3. Activity B 恢复在前台，Session 重新激活/维持
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0 + 20L, classB)
        assertTrue("B Resumed 后 Session 保持活跃", tracker.isSessionActiveFor(pkg))

        // 4. 迟到的 Activity A Stopped 事件到达 (关键修复：不得误关闭整个 package Session)
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_STOPPED, t0 + 30L, classA)
        assertTrue("A Stopped 到达时因为 B 仍处于活跃前台，Session 绝不能被关闭", tracker.isSessionActiveFor(pkg))

        // 5. 最终用户退出 B (B Stopped)
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_STOPPED, t0 + 1000L, classB)
        assertFalse("当最后一个 Activity 也 Stopped 时，Session 必须正确关闭", tracker.isSessionActiveFor(pkg))
    }

    @Test
    fun `activity transition in the same millisecond preserves active session`() {
        val pkg = "com.tencent.mm"
        val classA = "com.tencent.mm.ui.LauncherUI"
        val classB = "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI"
        val t0 = 1_000_000L

        // 同一毫秒内发生的事件序列：A Resumed -> A Paused -> B Resumed -> A Stopped
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0, classA)
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_PAUSED, t0, classA)
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0, classB)
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_STOPPED, t0, classA)

        assertTrue("同一毫秒内的 A->B 切换后 Session 必须保持活跃", tracker.isSessionActiveFor(pkg))

        // 随后 B 也 Stopped，关闭 Session
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_STOPPED, t0 + 50L, classB)
        assertFalse("B Stopped 后 Session 关闭", tracker.isSessionActiveFor(pkg))
    }

    @Test
    fun `force boundary check token is consumed and does not leak to subsequent steady state`() {
        // 轮次 1: 规则边界发生，设置了 forceBoundaryCheck，但此时并没有活跃前台 Session (如未进入 App)
        var forceToken = true
        val forceThisCycle = forceToken
        forceToken = false // 立即消费清零

        val decisionCycle1 = RuleBoundaryLockGate.shouldAllowBoundaryLock(
            RuleBoundaryLockDecisionInput(
                isBlockedNow = true,
                isBusinessAllowToBlock = false,
                leftGap = false,
                endedRest = false,
                forceBoundaryCheck = forceThisCycle,
                hasActiveForegroundSession = false, // 无活跃 Session
                isKeyguardLocked = false,
                isScreenInteractive = true
            )
        )
        assertFalse("无活跃 Session 时边界检测必须返回 false", decisionCycle1)

        // 轮次 2: 稳态运行，用户此时建立了 Session，但已处于稳态 BLOCK
        // 因 forceToken 已经消费，本轮 forceBoundaryCheck 必须为 false
        val decisionCycle2 = RuleBoundaryLockGate.shouldAllowBoundaryLock(
            RuleBoundaryLockDecisionInput(
                isBlockedNow = true,
                isBusinessAllowToBlock = false,
                leftGap = false,
                endedRest = false,
                forceBoundaryCheck = forceToken, // 已消费清零 (false)
                hasActiveForegroundSession = true, // 有了 Session
                isKeyguardLocked = false,
                isScreenInteractive = true
            )
        )
        assertFalse("旧 forceToken 必须已被消费，不得在下一稳态轮次泄漏导致误锁", decisionCycle2)
    }

    @Test
    fun `explicit isBusinessAllowToBlock triggers boundary lock but steady state does not`() {
        // 显式业务边沿跃迁 (ALLOW -> BLOCK，解耦前台守卫执行状态)
        val edgeInput = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            isBusinessAllowToBlock = true,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertTrue("业务边沿 ALLOW->BLOCK 必须触发边界锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(edgeInput))

        // 随后进入稳态 BLOCK (isBusinessAllowToBlock = false)
        val steadyInput = edgeInput.copy(
            isBusinessAllowToBlock = false
        )
        assertFalse("稳态 BLOCK 期间禁止重复触发边界锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(steadyInput))
    }

    @Test
    fun `activity A resumed then paused without subsequent B resumed becomes inactive`() {
        val pkg = "com.tencent.mm"
        val classA = "com.tencent.mm.ui.LauncherUI"
        val t0 = 1_000_000L

        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0, classA)
        assertTrue(tracker.isSessionActiveFor(pkg))

        // Activity A 暂停，且没有后续 Activity B resumed
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_PAUSED, t0 + 100L, classA)
        assertFalse("Activity A 暂停且没有后续页面恢复时，Session 必须正确标记为关闭", tracker.isSessionActiveFor(pkg))
    }

    @Test
    fun `stale events occurring at or before screen off cannot reopen session`() {
        val pkg = "com.tencent.mm"
        val t0 = 1_000_000L

        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0)
        assertTrue(tracker.isSessionActiveFor(pkg))

        // 用户息屏 at T = t0 + 5000L
        tracker.onScreenOff(t0 + 5_000L)
        assertFalse(tracker.isSessionActiveFor(pkg))

        // 后台 30s UsageEvents 重复扫描，吐出发生在息屏前 (T-1) 或息屏时 (T) 的旧 RESUMED 事件
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0 + 4_999L)
        assertFalse("息屏时刻之前的旧事件绝对不能重新开启 Session", tracker.isSessionActiveFor(pkg))

        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0 + 5_000L)
        assertFalse("息屏时刻当下的旧事件绝对不能重新开启 Session", tracker.isSessionActiveFor(pkg))

        // 只有屏幕重新点亮、真正发生在息屏之后的全新事件才允许开启 Session
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0 + 5_001L)
        assertTrue("息屏之后发生的全新真实前台事件方可开启 Session", tracker.isSessionActiveFor(pkg))
    }

    @Test
    fun `stale events occurring at or before keyguard lock cannot reopen session`() {
        val pkg = "com.tencent.mm"
        val t0 = 1_000_000L

        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0)
        assertTrue(tracker.isSessionActiveFor(pkg))

        // 锁屏 at T = t0 + 5000L
        tracker.onKeyguardLocked(t0 + 5_000L)
        assertFalse(tracker.isSessionActiveFor(pkg))

        // 旧事件重放
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0 + 4_500L)
        assertFalse("锁屏时间戳之前的事件不得恢复 Session", tracker.isSessionActiveFor(pkg))

        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0 + 5_000L)
        assertFalse("锁屏时间戳当下的事件不得恢复 Session", tracker.isSessionActiveFor(pkg))

        // 解锁后若无新事件，依然保持 inactive
        assertFalse(tracker.isSessionActiveFor(pkg))

        // 锁屏之后的有效新事件
        tracker.onUsageEvent(pkg, ForegroundSessionTracker.ACTIVITY_RESUMED, t0 + 6_000L)
        assertTrue(tracker.isSessionActiveFor(pkg))
    }

    @Test
    fun `unknown previous business state does not trigger boundary lock`() {
        // 首次观测到应用处于 BLOCK 状态（previousBusinessBlocked == null -> isBusinessAllowToBlock == false）
        val firstObservationInput = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            isBusinessAllowToBlock = false, // UNKNOWN 不算 ALLOW->BLOCK 边沿
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse("首次观测到受阻应用（无先前明确 ALLOW 记录）绝不能被误当成 ALLOW->BLOCK 边沿锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(firstObservationInput))
    }
}


