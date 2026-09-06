package com.zhizhi.app

import com.zhizhi.app.service.BoundaryCandidateCoordinator
import com.zhizhi.app.service.BoundaryCandidateInput
import com.zhizhi.app.service.BoundaryCandidateResult
import com.zhizhi.app.service.BusinessBlockState
import com.zhizhi.app.service.ForegroundGuardFallbackGate
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
    fun `custom injected IME package does not kill active session`() {
        val customImePackage = "com.custom.keyboard.typing"
        val customTracker = ForegroundSessionTracker { pkg ->
            ForegroundSessionTracker.defaultIsNoisePackage(pkg) || pkg == customImePackage
        }

        val t0 = 1_000_000L
        customTracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t0)
        assertTrue(customTracker.isSessionActiveFor("com.tencent.mm"))

        // 弹出自定义包名输入法产生前台事件
        customTracker.onUsageEvent(customImePackage, ForegroundSessionTracker.MOVE_TO_FG, t0 + 2_000L)

        // 微信会话依然保持活跃
        assertTrue("注入的动态输入法白名单应用不应破坏当前前台应用 Session", customTracker.isSessionActiveFor("com.tencent.mm"))
        assertEquals("com.tencent.mm", customTracker.currentSession?.packageName)
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
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse("稳态 Gap 期间不应误把 level 当 edge 触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(steadyGap))

        val steadyRest = steadyGap.copy(isBusinessAllowToBlock = false)
        assertFalse("稳态 Rest 期间不应触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(steadyRest))
    }

    @Test
    fun `mode transition after gap or rest requires isBusinessAllowToBlock`() {
        // 1. 若业务未发生边沿跃迁（例如此前在 Gap 期间已被 AppWindow 阻断，即 isBusinessAllowToBlock == false）
        val inputBlockedDuringGap = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            isBusinessAllowToBlock = false,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse("此前已受阻（isBusinessAllowToBlock=false）时，不得误放行锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(inputBlockedDuringGap))

        // 2. 若 Gap 结束时业务状态确实从 ALLOW 跃迁至 BLOCK (isBusinessAllowToBlock == true)，则允许锁定
        val inputTransitionFromGap = inputBlockedDuringGap.copy(isBusinessAllowToBlock = true)
        assertTrue("Gap 结束且业务状态从 ALLOW->BLOCK 跃迁时必须允许触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(inputTransitionFromGap))
    }

    @Test
    fun `screen off or keyguard locked blocks boundary lock`() {
        val base = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            isBusinessAllowToBlock = true,
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
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse("当前应用不属于受限状态时不应触发锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(input))
    }

    @Test
    fun `lack of business edge transition does not trigger boundary lock`() {
        // 当业务状态尚未跃迁（isBusinessAllowToBlock == false，例如持续稳态 BLOCK 或 UNKNOWN 首次观测）
        val inputWithoutEdge = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            isBusinessAllowToBlock = false,
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse("无业务 ALLOW->BLOCK 跃迁时，绝不能凭空放行锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(inputWithoutEdge))

        // 当存在业务边沿跃迁时，允许触发锁定
        val inputWithEdge = inputWithoutEdge.copy(isBusinessAllowToBlock = true)
        assertTrue("存在业务边沿跃迁时允许触发边界锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(inputWithEdge))
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
    fun `boundary check requires active session and business edge`() {
        val decisionCycle1 = RuleBoundaryLockGate.shouldAllowBoundaryLock(
            RuleBoundaryLockDecisionInput(
                isBlockedNow = true,
                isBusinessAllowToBlock = false,
                hasActiveForegroundSession = false, // 无活跃 Session
                isKeyguardLocked = false,
                isScreenInteractive = true
            )
        )
        assertFalse("无活跃 Session 时边界检测必须返回 false", decisionCycle1)

        val decisionCycle2 = RuleBoundaryLockGate.shouldAllowBoundaryLock(
            RuleBoundaryLockDecisionInput(
                isBlockedNow = true,
                isBusinessAllowToBlock = false,
                hasActiveForegroundSession = true, // 有了 Session 但无边沿跃迁
                isKeyguardLocked = false,
                isScreenInteractive = true
            )
        )
        assertFalse("稳态 BLOCK 阶段无边沿跃迁不得触发锁定", decisionCycle2)
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

    @Test
    fun `boundary transition allows lock for active session even if conventional resolver returned launcher or null`() {
        // 用户在 22:50 进入微信，并持续停留在微信
        val t2250 = 1_000_000L
        tracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t2250)
        assertTrue(tracker.isSessionActiveFor("com.tencent.mm"))

        // 23:00 时规则从 ALLOW -> BLOCK 发生边沿跃迁
        // 此时系统常规 resolver 因无新操作可能返回 null 或 vivo launcher
        val activeSession = tracker.currentSession
        assertNotNull(activeSession)
        assertEquals("com.tencent.mm", activeSession?.packageName)
        assertFalse(activeSession?.closed ?: true)

        // 边界门禁判定：以活跃 Session 包名为候选进行边界门禁评估
        val boundaryInput = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            isBusinessAllowToBlock = true, // 23:00 规则跃迁
            hasActiveForegroundSession = tracker.isSessionActiveFor(activeSession!!.packageName),
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertTrue("规则边沿跃迁且存在活跃 Session 时必须允许边界锁定", RuleBoundaryLockGate.shouldAllowBoundaryLock(boundaryInput))
    }

    // ── onSessionClosed 生命周期清理单元测试 ──

    @Test
    fun `onSessionClosed callback is invoked when session closes or is replaced`() {
        val closedPackages = mutableListOf<String>()
        val trackerWithCallback = ForegroundSessionTracker().apply {
            onSessionClosed = { closedPackages.add(it) }
        }

        val t0 = 1_000_000L
        trackerWithCallback.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t0)
        assertTrue(closedPackages.isEmpty())

        // 切换到另一个应用，旧应用应触发 onSessionClosed
        trackerWithCallback.onUsageEvent("com.example.other", ForegroundSessionTracker.MOVE_TO_FG, t0 + 2_000L)
        assertEquals(listOf("com.tencent.mm"), closedPackages)

        // 后台事件触发 onSessionClosed
        trackerWithCallback.onUsageEvent("com.example.other", ForegroundSessionTracker.MOVE_TO_BG, t0 + 4_000L)
        assertEquals(listOf("com.tencent.mm", "com.example.other"), closedPackages)

        // 重新开启应用后息屏
        trackerWithCallback.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, t0 + 6_000L)
        trackerWithCallback.onScreenOff(t0 + 8_000L)
        assertEquals(listOf("com.tencent.mm", "com.example.other", "com.tencent.mm"), closedPackages)
    }

    // ── BoundaryCandidateCoordinator 端到端决策测试 ──

    @Test
    fun `boundary coordinator - null resolver in ALLOW records false, transition to BLOCK locks, steady state rejects`() {
        val sessionPkg = "com.tencent.mm"

        // 1. 周期 1 (22:50)：静止状态 resolver 得到 null，当前属于 ALLOW 状态
        val cycle1Input = BoundaryCandidateInput(
            resolvedPackageName = null,
            isCarrierPackage = false,
            isRecentAppsOrLauncher = false,
            isLockShowing = false,
            isInDesktopReturnGrace = false,
            homeClickPendingPackage = null,
            activeSessionPackage = sessionPkg,
            isSessionClosed = false,
            isSessionNoiseOrLauncherOrSelf = false,
            isBusinessBlockedNow = false, // ALLOW
            previousBusinessBlockState = null,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        val cycle1Result = BoundaryCandidateCoordinator.evaluate(cycle1Input)
        assertFalse("ALLOW 状态下不得锁定候选会话", cycle1Result.shouldLockCandidate)
        assertEquals(BusinessBlockState.ALLOWED, cycle1Result.updatedBusinessBlockState)

        // 2. 周期 2 (23:00)：DND 生效，跃迁至 BLOCK，resolver 依然为 null
        val cycle2Input = BoundaryCandidateInput(
            resolvedPackageName = null,
            isCarrierPackage = false,
            isRecentAppsOrLauncher = false,
            isLockShowing = false,
            isInDesktopReturnGrace = false,
            homeClickPendingPackage = null,
            activeSessionPackage = sessionPkg,
            isSessionClosed = false,
            isSessionNoiseOrLauncherOrSelf = false,
            isBusinessBlockedNow = true, // 跃迁至 BLOCK
            previousBusinessBlockState = cycle1Result.updatedBusinessBlockState, // ALLOWED
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        val cycle2Result = BoundaryCandidateCoordinator.evaluate(cycle2Input)
        assertTrue("规则边沿跃迁且 resolver 为 null 时，必须锁定活跃候选 Session", cycle2Result.shouldLockCandidate)
        assertEquals(sessionPkg, cycle2Result.candidatePackage)
        assertEquals(BusinessBlockState.BLOCK_PENDING, cycle2Result.updatedBusinessBlockState)

        // 3. 周期 3 (23:01)：稳态 BLOCK，已经交付锁屏 (BLOCK_CONSUMED)，resolver 依然为 null
        val cycle3Input = BoundaryCandidateInput(
            resolvedPackageName = null,
            isCarrierPackage = false,
            isRecentAppsOrLauncher = false,
            isLockShowing = false,
            isInDesktopReturnGrace = false,
            homeClickPendingPackage = null,
            activeSessionPackage = sessionPkg,
            isSessionClosed = false,
            isSessionNoiseOrLauncherOrSelf = false,
            isBusinessBlockedNow = true, // 持续稳态 BLOCK
            previousBusinessBlockState = BusinessBlockState.BLOCK_CONSUMED,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        val cycle3Result = BoundaryCandidateCoordinator.evaluate(cycle3Input)
        assertFalse("处于持续 BLOCK 稳态时，禁止重复锁定候选会话", cycle3Result.shouldLockCandidate)
        assertEquals(BusinessBlockState.BLOCK_CONSUMED, cycle3Result.updatedBusinessBlockState)
    }

    @Test
    fun `boundary coordinator - vivo carrier upslide triggers boundary candidate lock on transition`() {
        val sessionPkg = "com.tencent.mm"

        // Vivo 误报 carrier: com.vivo.upslide，规则边沿跃迁
        val input = BoundaryCandidateInput(
            resolvedPackageName = "com.vivo.upslide",
            isCarrierPackage = true, // com.vivo.upslide 是 Carrier
            isRecentAppsOrLauncher = true,
            isLockShowing = false,
            isInDesktopReturnGrace = false,
            homeClickPendingPackage = null,
            activeSessionPackage = sessionPkg,
            isSessionClosed = false,
            isSessionNoiseOrLauncherOrSelf = false,
            isBusinessBlockedNow = true,
            previousBusinessBlockState = BusinessBlockState.ALLOWED, // 之前在 ALLOW
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        val result = BoundaryCandidateCoordinator.evaluate(input)
        assertTrue("Vivo Carrier (com.vivo.upslide) 误报期间边沿跃迁必须主动拉取 Session 锁定", result.shouldLockCandidate)
        assertEquals(sessionPkg, result.candidatePackage)
    }

    @Test
    fun `boundary coordinator - app window only transition without dnd change triggers boundary lock`() {
        val sessionPkg = "com.tencent.mm"

        // 应用时间窗从 ALLOW 跨入 BLOCK（非 DND 时段切换，纯 AppWindow 闭合）
        val input = BoundaryCandidateInput(
            resolvedPackageName = "com.bbk.launcher2",
            isCarrierPackage = true,
            isRecentAppsOrLauncher = true,
            isLockShowing = false,
            isInDesktopReturnGrace = false,
            homeClickPendingPackage = null,
            activeSessionPackage = sessionPkg,
            isSessionClosed = false,
            isSessionNoiseOrLauncherOrSelf = false,
            isBusinessBlockedNow = true, // AppWindow 闭合变为受阻
            previousBusinessBlockState = BusinessBlockState.ALLOWED,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        val result = BoundaryCandidateCoordinator.evaluate(input)
        assertTrue("应用时间窗仅凭 ALLOW->BLOCK 跃迁必须触发候选锁定", result.shouldLockCandidate)
        assertEquals(sessionPkg, result.candidatePackage)
    }

    @Test
    fun `boundary coordinator - home click pending suppresses candidate lock`() {
        val sessionPkg = "com.tencent.mm"

        val input = BoundaryCandidateInput(
            resolvedPackageName = "com.bbk.launcher2",
            isCarrierPackage = true,
            isRecentAppsOrLauncher = true,
            isLockShowing = false,
            isInDesktopReturnGrace = false,
            homeClickPendingPackage = sessionPkg, // 用户显式点击回到桌面
            activeSessionPackage = sessionPkg,
            isSessionClosed = false,
            isSessionNoiseOrLauncherOrSelf = false,
            isBusinessBlockedNow = true,
            previousBusinessBlockState = BusinessBlockState.ALLOWED,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        val result = BoundaryCandidateCoordinator.evaluate(input)
        assertFalse("用户显式点击回到桌面产生的 homeClickPending 必须抑制候选锁定", result.shouldLockCandidate)
    }

    // ── Finding 2: FallbackGate 跨 Session 计时继承隔离测试 ──

    @Test
    fun `fallback gate resets on session closed preventing cross-session time accumulation`() {
        val fallbackGate = ForegroundGuardFallbackGate(timeoutMs = 60_000L)
        val trackerWithReset = ForegroundSessionTracker().apply {
            onSessionClosed = { _ ->
                fallbackGate.reset()
            }
        }
        val pkg = "com.tencent.mm"

        // 1. Session 1: 用户进入微信，在 t = 0 建立 Session
        val t0 = 1_000_000L
        trackerWithReset.onUsageEvent(pkg, ForegroundSessionTracker.MOVE_TO_FG, t0)
        assertTrue(trackerWithReset.isSessionActiveFor(pkg))

        // t = 0 首次检测：初始化 pending
        assertFalse(fallbackGate.shouldForce(pkg, t0, eligible = true, guardPassed = false))

        // t = 50s：持续在微信，尚未达到 60s 超时
        assertFalse(fallbackGate.shouldForce(pkg, t0 + 50_000L, eligible = true, guardPassed = false))

        // 2. t = 52s：用户退后台（MOVE_TO_BG），Session 关闭，必须触发 fallbackGate.reset()
        trackerWithReset.onUsageEvent(pkg, ForegroundSessionTracker.MOVE_TO_BG, t0 + 52_000L)
        assertFalse(trackerWithReset.isSessionActiveFor(pkg))

        // 3. t = 54s：用户重新进入微信，开启 Session 2
        val tNewSession = t0 + 54_000L
        trackerWithReset.onUsageEvent(pkg, ForegroundSessionTracker.MOVE_TO_FG, tNewSession)
        assertTrue(trackerWithReset.isSessionActiveFor(pkg))

        // 首次检测新 Session
        assertFalse(fallbackGate.shouldForce(pkg, tNewSession, eligible = true, guardPassed = false))

        // 4. t = 64s (新 Session 仅持续了 10s)：
        // 关键断言：若未发生跨 Session 泄漏，10s 绝对不能触发 60s 保底锁！
        assertFalse(
            "新 Session 仅运行 10s，绝不能继承旧 Session 积累的 50s 计时导致误锁",
            fallbackGate.shouldForce(pkg, tNewSession + 10_000L, eligible = true, guardPassed = false)
        )

        // 5. t = 114s (新 Session 真正持续满 60s)：达到超时，允许触发
        assertTrue(
            "新 Session 自身独立持续满 60s 时应正常触发保底锁",
            fallbackGate.shouldForce(pkg, tNewSession + 60_000L, eligible = true, guardPassed = false)
        )
    }

    // ── Finding 3: 误报 Launcher 时在建立 Grace 前抢占候选锁定测试 ──

    @Test
    fun `boundary coordinator - resolver reporting launcher preempts candidate lock before grace is established`() {
        val sessionPkg = "com.tencent.mm"

        // 当 resolver 误报 Launcher (com.bbk.launcher2)，在尚未开启桌面 Grace 前评估候选
        val preGraceInput = BoundaryCandidateInput(
            resolvedPackageName = "com.bbk.launcher2",
            isCarrierPackage = false,
            isRecentAppsOrLauncher = true, // 识别为桌面
            isLockShowing = false,
            isInDesktopReturnGrace = false, // 尚未建立桌面 Grace
            homeClickPendingPackage = null,
            activeSessionPackage = sessionPkg,
            isSessionClosed = false,
            isSessionNoiseOrLauncherOrSelf = false,
            isBusinessBlockedNow = true, // 23:00 规则边沿跃迁
            previousBusinessBlockState = BusinessBlockState.ALLOWED, // 先前为 ALLOW
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        val result = BoundaryCandidateCoordinator.evaluate(preGraceInput)
        assertTrue("在更新桌面 Grace 之前执行候选抢占必须成功触发锁定", result.shouldLockCandidate)
        assertEquals(sessionPkg, result.candidatePackage)
        assertEquals("BOUNDARY_LOCK_TRIGGERED", result.reason)
    }

    // ── Finding 4 & 5: 稳态 BLOCK 严格门禁与瞬时拒绝边沿保留测试 ──

    @Test
    fun `rule boundary gate rejects steady state block`() {
        val steadyBlockWithAllTokens = RuleBoundaryLockDecisionInput(
            isBlockedNow = true,
            isBusinessAllowToBlock = false, // 稳态 BLOCK，无 ALLOW->BLOCK 跃迁
            hasActiveForegroundSession = true,
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        assertFalse(
            "稳态 BLOCK（isBusinessAllowToBlock=false）必须坚决拒绝锁定",
            RuleBoundaryLockGate.shouldAllowBoundaryLock(steadyBlockWithAllTokens)
        )
    }

    @Test
    fun `transient lock screen suppression preserves BLOCK_PENDING for subsequent retry until CONSUMED`() {
        val sessionPkg = "com.tencent.mm"
        val businessStateMap = mutableMapOf<String, BusinessBlockState>()

        // 初始时刻在 ALLOW 记录为 ALLOWED
        businessStateMap[sessionPkg] = BusinessBlockState.ALLOWED

        // 1. 23:00 边沿跃迁，Coordinator 命中 shouldLockCandidate = true
        val edgeInput = BoundaryCandidateInput(
            resolvedPackageName = null,
            isCarrierPackage = false,
            isRecentAppsOrLauncher = false,
            isLockShowing = false,
            isInDesktopReturnGrace = false,
            homeClickPendingPackage = null,
            activeSessionPackage = sessionPkg,
            isSessionClosed = false,
            isSessionNoiseOrLauncherOrSelf = false,
            isBusinessBlockedNow = true,
            previousBusinessBlockState = businessStateMap[sessionPkg], // ALLOWED
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        val edgeDecision = BoundaryCandidateCoordinator.evaluate(edgeInput)
        assertTrue(edgeDecision.shouldLockCandidate)
        assertEquals(BusinessBlockState.BLOCK_PENDING, edgeDecision.updatedBusinessBlockState)

        // 模拟瞬时抑制（如动作宽限 mDismissUntil 导致锁屏未真正展示）
        // 关键契约：未成功展示锁屏时，状态记录为 BLOCK_PENDING，绝不能置为 BLOCK_CONSUMED
        businessStateMap[sessionPkg] = BusinessBlockState.BLOCK_PENDING

        // 2. 下一检测周期（500ms 后宽限结束）：
        // 因为状态保持 BLOCK_PENDING，本轮仍被认定为有效边沿跃迁，可立即重试！
        val retryInput = edgeInput.copy(
            previousBusinessBlockState = businessStateMap[sessionPkg] // BLOCK_PENDING
        )
        val retryDecision = BoundaryCandidateCoordinator.evaluate(retryInput)
        assertTrue("次轮重试时保持 BLOCK_PENDING 仍应触发候选锁定", retryDecision.shouldLockCandidate)

        // 本轮宽限已过，锁屏真正交付成功，状态流转为 BLOCK_CONSUMED
        businessStateMap[sessionPkg] = BusinessBlockState.BLOCK_CONSUMED

        // 3. 第 3 周期（稳态）：已消费为 BLOCK_CONSUMED，必须拒绝重复锁定
        val steadyInput = edgeInput.copy(
            previousBusinessBlockState = businessStateMap[sessionPkg] // BLOCK_CONSUMED
        )
        val steadyDecision = BoundaryCandidateCoordinator.evaluate(steadyInput)
        assertFalse("边沿消费后进入稳态 BLOCK_CONSUMED，必须拒绝重复锁定", steadyDecision.shouldLockCandidate)
        assertEquals(BusinessBlockState.BLOCK_CONSUMED, steadyDecision.updatedBusinessBlockState)
    }

    // ── Finding 2 (Round 8): 残留桌面 Grace 穿透测试 ──

    @Test
    fun `boundary coordinator - active session edge transition bypasses pre-existing desktop grace`() {
        val sessionPkg = "com.tencent.mm"

        // 22:59:58 resolver 误报 Launcher 留下了 3s 桌面 Grace (isInDesktopReturnGrace == true)
        // 23:00:00 发生 ALLOW -> BLOCK 跃迁，Session 保持活跃未关闭且非用户点击回桌面
        val input = BoundaryCandidateInput(
            resolvedPackageName = null,
            isCarrierPackage = false,
            isRecentAppsOrLauncher = false,
            isLockShowing = false,
            isInDesktopReturnGrace = true, // 残留的 Desktop Grace
            homeClickPendingPackage = null, // 非用户主动 Home 回桌面
            activeSessionPackage = sessionPkg,
            isSessionClosed = false,
            isSessionNoiseOrLauncherOrSelf = false,
            isBusinessBlockedNow = true,
            previousBusinessBlockState = BusinessBlockState.ALLOWED, // ALLOW -> BLOCK
            isKeyguardLocked = false,
            isScreenInteractive = true
        )
        val result = BoundaryCandidateCoordinator.evaluate(input)
        assertTrue(
            "活跃第三方 Session 处于 ALLOW->BLOCK 边沿时，残留的 Desktop Grace 绝不能拦截锁定",
            result.shouldLockCandidate
        )
        assertEquals(sessionPkg, result.candidatePackage)
        assertEquals("BOUNDARY_LOCK_TRIGGERED", result.reason)

        // 对比：若已处于稳态 BLOCK_CONSUMED，则 Desktop Grace 正常拦截
        val steadyWithGrace = input.copy(previousBusinessBlockState = BusinessBlockState.BLOCK_CONSUMED)
        val steadyResult = BoundaryCandidateCoordinator.evaluate(steadyWithGrace)
        assertFalse("稳态 BLOCK_CONSUMED 下 Desktop Grace 正常生效", steadyResult.shouldLockCandidate)
        assertEquals("DESKTOP_RETURN_GRACE", steadyResult.reason)
    }

    // ── Finding 3 (Round 8): 多线程并发安全测试 ──

    @Test
    fun `concurrent session updates and reads are thread safe`() {
        val testTracker = ForegroundSessionTracker()
        val testGate = ForegroundGuardFallbackGate(timeoutMs = 1000L)
        val threads = mutableListOf<Thread>()
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        for (i in 0 until 8) {
            val t = Thread {
                try {
                    for (j in 0 until 100) {
                        val ts = 1_000_000L + j * 10
                        if (j % 2 == 0) {
                            testTracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_FG, ts)
                            testGate.shouldForce("com.tencent.mm", ts, eligible = true, guardPassed = false)
                        } else {
                            testTracker.currentSessionSnapshot()
                            testTracker.onUsageEvent("com.tencent.mm", ForegroundSessionTracker.MOVE_TO_BG, ts)
                            testGate.reset()
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
            threads.add(t)
            t.start()
        }

        threads.forEach { it.join(5000) }
        assertTrue("并发多线程访问不应抛出任何异常: $errors", errors.isEmpty())
    }
}


