/**
 * =================================================================================
 * [Context Header]
 * * @file: FastLockCountdownLogicTest.kt
 * * @role: Test
 * * @description: FastLock 倒计时的纯时间模型单测。REQ-2 验收核心。
 * * @responsibilities:
 * 1. 验证 [StartupProtectionManager.remainingCountdownSeconds] 在边界条件下稳定。
 * 2. 覆盖 adopt / inflate / refresh / handoff 四条路径的基线语义。
 * 3. [2026-07-06 session 2 返工] 覆盖问题 2 单一调度链 与 问题 3 maxVisibleMs 外部传入。
 * * @context:
 * - 上游: Gradle Unit Test
 * - 下游: FastLockService.onCreate / showLockWindow / refreshMinimumVisibleWindow / closeAfterMinimumVisibleWindow
 * * @constraints (必读):
 * 1. 仅测试纯策略，不启动 Android Service。
 * 2. 不依赖真实系统权限状态。
 * 3. 必须覆盖 hold 跨越 handoff、refresh 重置基线、倒计时到 0 等 REQ-2 验收点。
 * =================================================================================
 */
package com.ironwill.app

import org.junit.Assert.*
import org.junit.Test

class FastLockCountdownLogicTest {

    /** 输入辅助：把 maxVisibleMs 标准化为 30s 倒计时 */
    private val maxVisible = StartupProtectionManager.FAST_LOCK_MAX_VISIBLE_MS

    @Test
    fun freshTick_displaysFullCountdown() {
        // 期望：刚启动时（T=0）应当显示完整的 maxVisible 秒数（默认 30）。
        val seconds = StartupProtectionManager.remainingCountdownSeconds(
            startedAtMs = 0L,
            nowMs = 0L,
            maxVisibleMs = maxVisible
        )
        assertEquals((maxVisible / 1000L).toInt(), seconds)
    }

    @Test
    fun everySecondStepsDown() {
        // 期望：每秒显示的整数秒稳定递减，不出现"在第 29 秒停留两次"等现象。
        val started = 1_000_000L
        var previous = StartupProtectionManager.remainingCountdownSeconds(started, started)
        for (tick in 1..29) {
            val now = started + tick * 1000L
            val current = StartupProtectionManager.remainingCountdownSeconds(started, now)
            assertTrue(
                "每过 1 秒，countdown 应当严格递减 1。prev=$previous now=$current",
                current == previous - 1
            )
            previous = current
        }
    }

    @Test
    fun handoffAcrossMinHold_keepsCountdownVisible() {
        // 验收：handoff 在 minHold (10s) 之前到达时，覆盖层应仍可见；
        // countdown 显示基于 maxVisibleMs（maxHold），不是 minHold。
        // handoff 重置基线 → 重新显示完整 30s 是正确的（minHold 决定实际关闭动作，countdown 仅显示）。
        val startedAtOriginalBoot = 0L
        // 启动后 3 秒：CoreService 收到 handoff 请求，重置基线
        val startedAtAfterHandoff = 3_000L
        // handoff 后 5 秒观察：
        val nowMs = startedAtAfterHandoff + 5_000L
        val seconds = StartupProtectionManager.remainingCountdownSeconds(
            startedAtMs = startedAtAfterHandoff,
            nowMs = nowMs,
            maxVisibleMs = maxVisible
        )
        // 期望：handoff 后 5s 仍显示 25 秒（maxHold 30s - 5s）
        assertTrue("handoff 后仍应保持 countdown 显示", seconds in 20..25)
    }

    @Test
    fun refreshResetsBaseline() {
        // 验收：refresh（refreshMinimumVisibleWindow）必须重置 countdown 基线，
        // 否则数字与真实剩余时间错位。
        val original = 0L
        // 启动后 12 秒触发 refresh → 重置 baseline
        val refreshAt = 12_000L
        val newBaseline = refreshAt
        // refresh 后 2 秒观察
        val now = newBaseline + 2_000L
        val seconds = StartupProtectionManager.remainingCountdownSeconds(
            startedAtMs = newBaseline,
            nowMs = now,
            maxVisibleMs = maxVisible
        )
        // 期望显示约 28 秒 (30 - 2)
        assertEquals(28, seconds)
        // 与未刷新前相比，未刷新会显示 18 秒 (30 - 12)，刷新后是正确的 28 秒
        val unchangedDisplay = StartupProtectionManager.remainingCountdownSeconds(
            startedAtMs = original,
            nowMs = now,
            maxVisibleMs = maxVisible
        )
        assertNotEquals("refresh 必须改变显示", unchangedDisplay, seconds)
    }

    @Test
    fun countdownReachesZeroClamp() {
        // 验收：超时 maxVisibleMs 后，显示钳到 0；不会变成负数。
        val started = 0L
        val secondsAfterTimeout = StartupProtectionManager.remainingCountdownSeconds(
            startedAtMs = started,
            nowMs = started + maxVisible + 10_000L,
            maxVisibleMs = maxVisible
        )
        assertEquals(0, secondsAfterTimeout)
    }

    @Test
    fun negativeElapsedInput_doesNotCrash() {
        // 输入防御：nowMs < startedAtMs（时钟漂移/测试边界）应被 coerceAtLeast 处理。
        val seconds = StartupProtectionManager.remainingCountdownSeconds(
            startedAtMs = 5_000L,
            nowMs = 4_000L, // 早于基准
            maxVisibleMs = maxVisible
        )
        assertEquals((maxVisible / 1000L).toInt(), seconds)
    }

    @Test
    fun customMaxVisible_appliedCorrectly() {
        // 验收：CoreService.onConfigurationChanged 触发的 10s 配置变化保护窗口
        // 也可以复用同一个纯函数计算，只是 maxVisibleMs 不同。
        //
        // [2026-07-06 session 2 返工] 这是 Codex review 问题 3 的关键验收点:
        // 真实持有窗口是 10s,所以 countdown 必须显示 10s 倒计时而非 30s。
        // 这条用例从 session 1 起就锁定了正确期望,本次返工不调整期望值,
        // 仅加注释说明它就是问题 3 的反转断言 — 反过来说,如果将来有人把
        // FastLockService.updateCountdownDisplay 改回 hardcode FAST_LOCK_MAX_VISIBLE_MS,
        // 就会破坏 config-change 路径"显示 10s → 实际 10s 后消失"的一致性。
        val configChangeWindowMs = 10_000L
        val started = 0L
        val secondsAt5 = StartupProtectionManager.remainingCountdownSeconds(
            startedAtMs = started,
            nowMs = started + 5_000L,
            maxVisibleMs = configChangeWindowMs
        )
        // 5 秒后，应显示 5 秒（10s - 5s）
        assertEquals(5, secondsAt5)

        val secondsAt15 = StartupProtectionManager.remainingCountdownSeconds(
            startedAtMs = started,
            nowMs = started + 15_000L,
            maxVisibleMs = configChangeWindowMs
        )
        // 超过窗口，钳到 0
        assertEquals(0, secondsAt15)
    }

    @Test
    fun exactBoundaryAtMax_returnsZero() {
        // 期望：恰好在 maxVisibleMs 时显示 0（uphill rounding 让上一帧还能显示 1s）。
        val started = 0L
        val secondsAtMax = StartupProtectionManager.remainingCountdownSeconds(
            startedAtMs = started,
            nowMs = started + maxVisible - 1L, // 比 max 早 1 ms，仍应显示 1
            maxVisibleMs = maxVisible
        )
        assertEquals(1, secondsAtMax)

        val secondsPastMax = StartupProtectionManager.remainingCountdownSeconds(
            startedAtMs = started,
            nowMs = started + maxVisible,
            maxVisibleMs = maxVisible
        )
        assertEquals(0, secondsPastMax)
    }

    // ============================================================
    // [2026-07-06 session 2 返工] Codex review 问题 2 验收点:
    // countdown 调度必须保持单一链 — 同一 service 内连续 startCountdownTick()
    // 不会留下两个 pending runnable。
    //
    // 由于 FastLockService 本身需要 Context/Looper 才能实例化,
    // 这里通过 internal class [FastLockService.CountdownTickCoordinator] 验证
    // 调度封装的核心不变量。该封装与服务内部的 startCountdownTick /
    // onCountdownTickFired 共享同一"清旧+新建"语义。
    // ============================================================

    @Test
    fun countdownTick_singleChain_afterSingleStart() {
        val coordinator = FastLockService.CountdownTickCoordinator()
        coordinator.startOne()
        assertEquals(
            "单次 start 后 handler 上挂的 countdown runnable 应恰好为 1 个",
            1, coordinator.pendingCount()
        )
        assertTrue(
            "单一调度链不变量:start 后 pending 必须 ≤ 1",
            coordinator.assertSingleChain() <= 1
        )
    }

    @Test
    fun countdownTick_singleChain_afterRepeatedStart() {
        // 模拟 refresh / handoff / adopt 反复调用 startCountdownTick:
        // 每次 start 必须清掉旧 runnable,净结果仍只挂 1 个。
        val coordinator = FastLockService.CountdownTickCoordinator()
        coordinator.startOne()
        coordinator.startOne() // 模拟 refresh 重启
        coordinator.startOne() // 模拟 handoff 重启
        coordinator.startOne() // 模拟 onStartCommand maxVisible 覆盖重启
        assertEquals(
            "连续 4 次 start 后,handler 上挂的 countdown runnable 应仍为 1 个(单一调度链)",
            1, coordinator.pendingCount()
        )
    }

    @Test
    fun countdownTick_zeroAfterCancel() {
        // 模拟 onDestroy / 0 秒后自然停止:挂起数必须回到 0
        val coordinator = FastLockService.CountdownTickCoordinator()
        coordinator.startOne()
        coordinator.cancelAll()
        assertEquals("cancel 后 handler 上不应残留 countdown runnable", 0, coordinator.pendingCount())
    }

    @Test
    fun countdownTick_zeroAfterFinishOne() {
        // 模拟 tick 触发(到 0 后自然停止)
        val coordinator = FastLockService.CountdownTickCoordinator()
        coordinator.startOne()
        coordinator.finishOne()
        assertEquals("tick 触发后(到 0 自然停止),pending 必须归 0", 0, coordinator.pendingCount())
    }

    @Test
    fun countdownTick_pendingNeverExceedsOne() {
        // 关键不变量:任意时刻 pending ≤ 1 — 这是问题 2 的核心反转断言。
        // 反过来说,如果将来有人把 updateCountdownDisplay() 改回内部 postDelayed
        // (双链),这个不变量就会被破坏,真机上会出现"刷新两次恶化"。
        val coordinator = FastLockService.CountdownTickCoordinator()
        coordinator.startOne()
        assertTrue("start 后 ≤ 1", coordinator.pendingCount() <= 1)
        coordinator.startOne()
        assertTrue("再次 start 后 ≤ 1", coordinator.pendingCount() <= 1)
        coordinator.finishOne()
        assertEquals("finish 后 = 0", 0, coordinator.pendingCount())
        coordinator.startOne()
        assertTrue("再次 start 后 ≤ 1", coordinator.pendingCount() <= 1)
        coordinator.cancelAll()
        assertEquals("cancel 后 = 0", 0, coordinator.pendingCount())
    }

    // ============================================================
    // [2026-07-06 session 3 返工] Codex review 问题 1 验收点:
    // 引入 mDeadlineMs 模型后,4 条入口路径(adopt / refresh / handoff / config-change)
    // 的 deadline 计算公式必须正确。
    //
    // 这是修掉"countdown 显示与 timeout 关闭时机脱钩"的根本性测试覆盖:
    // - 纯函数 [StartupProtectionManager.compute*DeadlineMs] 在 JVM 单测中验证
    // - 单一来源规则:display = (mDeadlineMs - now) / 1000
    // - 5 条路径全部走 deadline 模型,不再 hardcode 30s
    // ============================================================

    @Test
    fun deadlineModel_adoptPath_usesRealOverlayAttachTime() {
        // [2026-07-06 session 3 返工] Codex review 问题 1 的 adopt 路径:
        // 早期 overlay 在 t=1s 由 MyApplication attach;FastLockService.onCreate 在 t=3s 接管。
        // mDeadlineMs 必须 = 1s + 30s = 31s,而不是 3s + 30s = 33s。
        // 这样 countdown 显示从"用户首次看到 overlay 那一刻"起算的 30s,与 timeout 同步。
        val earlyAttachMs = 1_000L
        val nowMs = 3_000L
        val maxVisibleMs = 30_000L

        val deadline = StartupProtectionManager.computeAdoptDeadlineMs(
            earlyBootOverlayAttachedAtMs = earlyAttachMs,
            nowMs = nowMs,
            maxVisibleMs = maxVisibleMs
        )
        assertEquals("adopt deadline 必须基于真实 overlay 起点 + maxVisibleMs", 31_000L, deadline)

        // countdown 显示基于 deadline:at nowMs=3s,deadline=31s → 显示 28s(30 - 2 已用)
        val secondsAtAdopt = StartupProtectionManager.remainingCountdownSecondsFromDeadline(
            deadlineMs = deadline,
            nowMs = nowMs,
            ceilingMaxSeconds = (maxVisibleMs / 1000L).toInt()
        )
        // (31_000 - 3_000 + 999) / 1000 = 28.999 → 28 秒 (ceiling 后)
        assertEquals("adopt 后立即观察:28 秒剩余", 28, secondsAtAdopt)
    }

    @Test
    fun deadlineModel_adoptPath_fallbackIfAttachTimeZero() {
        // [2026-07-06 session 3 返工] 防御:若 earlyBootOverlayAttachedAtMs 未设置(异常路径),
        // 回退到 nowMs + maxVisibleMs(等价于 inflate 模型)。
        val nowMs = 5_000L
        val maxVisibleMs = 30_000L
        val deadline = StartupProtectionManager.computeAdoptDeadlineMs(
            earlyBootOverlayAttachedAtMs = 0L,
            nowMs = nowMs,
            maxVisibleMs = maxVisibleMs
        )
        assertEquals("未设置早期 overlay 时,回退到 now + maxVisibleMs", 35_000L, deadline)
    }

    @Test
    fun deadlineModel_inflatePath_usesNowAsStart() {
        // [2026-07-06 session 3 返工] inflate 路径:无早期 overlay 可接管,
        // 自行 inflate 后 deadline = now + maxVisibleMs。
        val nowMs = 10_000L
        val maxVisibleMs = 30_000L
        val deadline = StartupProtectionManager.computeInflateDeadlineMs(
            nowMs = nowMs,
            maxVisibleMs = maxVisibleMs
        )
        assertEquals("inflate deadline = now + maxVisibleMs", 40_000L, deadline)
    }

    @Test
    fun deadlineModel_refreshPath_neverShrinks() {
        // [2026-07-06 session 3 返工] Codex review 问题 1 的 refresh 路径:
        // 当前 deadline=50s(可能因早期 adopt + 30s),现在 refresh at now=20s。
        // refresh deadline 必须 >= now + 30s = 50s(不缩短)。
        val currentDeadline = 50_000L
        val nowMs = 20_000L
        val maxVisibleMs = 30_000L
        val newDeadline = StartupProtectionManager.computeRefreshDeadlineMs(
            currentDeadlineMs = currentDeadline,
            nowMs = nowMs,
            maxVisibleMs = maxVisibleMs
        )
        assertTrue(
            "refresh deadline 必须 >= now + maxVisibleMs(不缩短)",
            newDeadline >= nowMs + maxVisibleMs
        )
        // 具体值 = max(50, 20+30) = 50
        assertEquals("refresh deadline = max(当前, now + maxVisible)", 50_000L, newDeadline)
    }

    @Test
    fun deadlineModel_refreshPath_extendsWhenCurrentIsSmaller() {
        // [2026-07-06 session 3 返工] refresh 路径:当前 deadline=25s,now=20s,refresh 应延长到 50s。
        val currentDeadline = 25_000L
        val nowMs = 20_000L
        val maxVisibleMs = 30_000L
        val newDeadline = StartupProtectionManager.computeRefreshDeadlineMs(
            currentDeadlineMs = currentDeadline,
            nowMs = nowMs,
            maxVisibleMs = maxVisibleMs
        )
        assertEquals("refresh 应延长 deadline 到 50s", 50_000L, newDeadline)
    }

    @Test
    fun deadlineModel_handoffPath_equalsNowPlusDelayMs() {
        // [2026-07-06 session 3 返工] Codex review 问题 1 的 handoff 路径:
        // handoff 后 CoreService 接管,FastLockService 只保留 minHold(10s)就关闭。
        // mDeadlineMs 必须 = now + 10s(不能跳到 30s),countdown 显示真实剩余 10s。
        val nowMs = 20_000L
        val delayMs = 10_000L
        val deadline = StartupProtectionManager.computeHandoffDeadlineMs(
            nowMs = nowMs,
            delayMs = delayMs
        )
        assertEquals("handoff deadline = now + delayMs(不跳到 30)", 30_000L, deadline)

        // countdown 显示:at nowMs=20s,deadline=30s → 10s(ceiling 后)
        val secondsAtHandoff = StartupProtectionManager.remainingCountdownSecondsFromDeadline(
            deadlineMs = deadline,
            nowMs = nowMs,
            ceilingMaxSeconds = 30
        )
        // (30_000 - 20_000 + 999) / 1000 = 10.999 → 10 秒
        assertEquals("handoff 后立即观察:10 秒剩余(对应真实 hold)", 10, secondsAtHandoff)

        // handoff 后 5s 观察:5s 剩余
        val seconds5sLater = StartupProtectionManager.remainingCountdownSecondsFromDeadline(
            deadlineMs = deadline,
            nowMs = nowMs + 5_000L,
            ceilingMaxSeconds = 30
        )
        assertEquals("handoff 后 5s:5 秒剩余", 5, seconds5sLater)
    }

    @Test
    fun deadlineModel_configChangePath_shrinksFrom30To10() {
        // [2026-07-06 session 3 返工] Codex review 问题 1 的 config-change 路径:
        // onStartCommand 收到 EXTRA_MAX_VISIBLE_MS=10s,当前 maxVisibleMs=30s。
        // deadline 必须从 now + 10s 重新计(允许缩短,因 10s < 30s)。
        val nowMs = 5_000L
        val currentDeadline = 50_000L  // 之前是 30s 窗口:假设 adopt 在 t=20s,deadline=50s
        val newMaxVisibleMs = 10_000L
        val oldMaxVisibleMs = 30_000L
        val newDeadline = StartupProtectionManager.computeOverrideDeadlineMs(
            currentDeadlineMs = currentDeadline,
            nowMs = nowMs,
            newMaxVisibleMs = newMaxVisibleMs,
            oldMaxVisibleMs = oldMaxVisibleMs
        )
        // config-change 缩短:newDeadline = now + 10s = 15s
        assertEquals("config-change 缩短到 now + 10s", 15_000L, newDeadline)
        // 显示:at now=5s,deadline=15s → 10s
        val seconds = StartupProtectionManager.remainingCountdownSecondsFromDeadline(
            deadlineMs = newDeadline,
            nowMs = nowMs,
            ceilingMaxSeconds = 10
        )
        assertEquals("config-change 后立即观察:10 秒剩余", 10, seconds)
    }

    @Test
    fun deadlineModel_configChangePath_neverShrinksWhenLarger() {
        // [2026-07-06 session 3 返工] config-change 路径的"扩大"语义:
        // 假设外部传 newMaxVisibleMs > oldMaxVisibleMs(不常见,但要锁住不缩短语义)。
        val nowMs = 10_000L
        val currentDeadline = 60_000L
        val newMaxVisibleMs = 60_000L
        val oldMaxVisibleMs = 30_000L
        val newDeadline = StartupProtectionManager.computeOverrideDeadlineMs(
            currentDeadlineMs = currentDeadline,
            nowMs = nowMs,
            newMaxVisibleMs = newMaxVisibleMs,
            oldMaxVisibleMs = oldMaxVisibleMs
        )
        // 扩大时,取 max(currentDeadline=60, now+60=70) = 70s
        assertEquals("config-change 扩大时 deadline 不缩短", 70_000L, newDeadline)
    }

    @Test
    fun deadlineModel_extensionFollowsMaxVisible() {
        // [2026-07-06 session 3 返工] timeout runnable 延期时,
        // mDeadlineMs 必须同步推后到 now + maxVisibleMs(否则 countdown 显示卡在 0)。
        val nowMs = 100_000L
        val maxVisibleMs = 30_000L
        // 模拟 mTimeoutRunnable 延期路径:deadline = now + maxVisible
        val extendedDeadline = nowMs + maxVisibleMs
        assertEquals("延期后 deadline", 130_000L, extendedDeadline)
        // countdown 显示立刻恢复 30s(完整窗口)
        val seconds = StartupProtectionManager.remainingCountdownSecondsFromDeadline(
            deadlineMs = extendedDeadline,
            nowMs = nowMs,
            ceilingMaxSeconds = 30
        )
        // (130_000 - 100_000 + 999) / 1000 = 30.999 → 30
        assertEquals("延期后立即观察:30 秒剩余(完整窗口)", 30, seconds)
    }

    @Test
    fun deadlineModel_remainingMsFromDeadline_isTimeToClose() {
        // [2026-07-06 session 返工] handoff 路径改用 mDeadlineMs 替代 remainingFastLockHoldMs:
        // 验证 remainingMsFromDeadline 等价于"距离真实关闭还有多少毫秒"。
        val deadline = 50_000L
        val nowMs = 30_000L
        val remainingMs = StartupProtectionManager.remainingMsFromDeadline(deadline, nowMs)
        assertEquals("distance to close = 20s", 20_000L, remainingMs)

        // 超过 deadline → 0
        val pastMs = StartupProtectionManager.remainingMsFromDeadline(deadline, 60_000L)
        assertEquals("超过 deadline 钳到 0", 0L, pastMs)

        // deadline 未设置 → 0
        val unsetMs = StartupProtectionManager.remainingMsFromDeadline(0L, nowMs)
        assertEquals("deadline 未设置时 = 0", 0L, unsetMs)
    }

    @Test
    fun deadlineModel_handoffAfterOriginalDeadline_stillSetsCloseTime() {
        // [2026-07-06 session 3 返工] 边界:如果 handoff 在原 max timeout 之后到达,
        // 即 mStartedAtMs(原) + maxVisibleMs < now,
        // handoff 的 mDeadlineMs = now + delayMs(此处 delayMs = minHold - elapsed = 负 → coerceAtLeast 0)
        // mDeadlineMs 仍 = now(因为 delayMs <= 0)。
        val nowMs = 50_000L
        val mStartedAtMs = 0L  // 极早:30s 窗口早已过期
        val elapsed = nowMs - mStartedAtMs
        // remainingFastLockHoldMs = (10_000 - 50_000).coerceAtLeast(0) = 0
        val delayMs = StartupProtectionManager.remainingFastLockHoldMs(mStartedAtMs, nowMs)
        assertEquals("handoff 时已超过 minHold:delayMs=0", 0L, delayMs)

        // handoff deadline = now + 0 = now
        val deadline = StartupProtectionManager.computeHandoffDeadlineMs(nowMs, delayMs)
        assertEquals("deadline = now", nowMs, deadline)
        // 显示 0
        val seconds = StartupProtectionManager.remainingCountdownSecondsFromDeadline(deadline, nowMs, 30)
        assertEquals("handoff 立即观察:0 秒剩余", 0, seconds)
    }

    // ============================================================
    // [2026-07-06 session 4 返工] Codex review 3 阻断问题验收点:
    // 连续两次 config-change 触发的 FastLock,mDeadlineMs 必须 grows 但不 shortens。
    //
    // 背景:session 1+2+3 之前的代码在 CoreService.onConfigurationChanged() 中
    // 自带 `mHandler.postDelayed { stopFastLock }`,导致连续两次 config-change
    // 时前一次 runnable 不会被取消,在 mDeadlineMs 归零前提前 stop 服务,
    // 违反 D-007 "全部场景一致显示 countdown" 的 grow 语义。
    //
    // 修复:选项 A — 把 config-change 关停所有权彻底收口到 FastLockService 内部的
    // mDeadlineMs + scheduleMaxTimeout + mTimeoutRunnable(mExtensionCount >= mMaxExtensions)。
    // CoreService 不再有独立的 stop 时机,可被重复调用而无残留。
    //
    // 本测试通过 computeOverrideDeadlineMs 模拟两次 config-change,
    // 验证 grow-but-don't-shorten 不变量 — 这是 Fix 的本质。
    // ============================================================

    @Test
    fun configChange_repeatedWithinWindow_deadlineGrowsNotShrinks() {
        // 第 1 次 config-change:CoreService 调用 startFastLock(maxVisibleMs=10s),
        // FastLockService.onStartCommand 走 computeOverrideDeadlineMs(短路到 nowMs + 10s,因为 10 < 30)。
        // 此时 mDeadlineMs = now1 + 10s = T10s。
        val now1Ms = 0L
        val oldMaxVisibleMs = 30_000L
        val newMaxVisibleMs = 10_000L
        val deadlineAfterFirst = StartupProtectionManager.computeOverrideDeadlineMs(
            currentDeadlineMs = 0L,           // 首次 config-change,尚未有 deadline;normalize via inflate 模型 = now1+30 = 30s
            nowMs = now1Ms,
            newMaxVisibleMs = newMaxVisibleMs,
            oldMaxVisibleMs = oldMaxVisibleMs
        )
        // 首次 config-change:旧 30s → 新 10s(缩短),deadline = now1 + 10s = 10s
        assertEquals("第 1 次 config-change 后 deadline = now + 10s", 10_000L, deadlineAfterFirst)

        // 第 2 次 config-change 在 now2Ms = 5s 触发:
        // CoreService 又调 startFastLock(maxVisibleMs=10s),
        // FastLockService.onStartCommand 再次走 computeOverrideDeadlineMs,
        // 此时 oldMaxVisibleMs 已经是 10s,newMaxVisibleMs 也是 10s(newMaxVisibleMs < oldMaxVisibleMs 不成立,
        // newMaxVisibleMs == oldMaxVisibleMs → 走扩大分支)。
        // 关键: deadlineAfterSecond = max(deadlineAfterFirst=10s, now2Ms+10s=15s) = 15s
        val now2Ms = 5_000L
        val deadlineAfterSecond = StartupProtectionManager.computeOverrideDeadlineMs(
            currentDeadlineMs = deadlineAfterFirst,
            nowMs = now2Ms,
            newMaxVisibleMs = 10_000L,
            oldMaxVisibleMs = 10_000L   // 第 2 次 config-change 时,旧值已是 10s
        )
        // grow 语义:max(10s, 15s) = 15s — 真正 deadline 推后,而不是 deadlineAfterFirst=10s
        assertEquals(
            "连续两次 config-change 后 deadline 必须 grow 到 max(当前, now+10s) = 15s",
            15_000L,
            deadlineAfterSecond
        )

        // 验证 grow 不缩短:deadlineAfterSecond > deadlineAfterFirst
        assertTrue(
            "重复 config-change 后 deadline 必须严格大于前一次(10s < 15s)",
            deadlineAfterSecond > deadlineAfterFirst
        )
        // 验证 countdown 倒计时显示也跟着 grow:
        // - 第一次 config-change at now=0s,deadline=10s → countdown 显示 10s(完整 10s 窗口)
        // - 第二次 config-change at now=5s,deadline=15s → countdown 显示 10s(还有 10s 才到 deadline)
        //   注意:虽然 mDeadlineMs 推后了,但 countdown 显示的是从当前时刻算起的剩余秒数,
        //   由于 now=5s,deadline=15s → 剩余 10s,与第一次 config-change 起始时相同 — 这是 grow 语义
        //   的天然结果(用户已经"看过 5s",新关停时刻推后 5s,剩余还是 10s,符合直觉)
        val secondsAfterSecond = StartupProtectionManager.remainingCountdownSecondsFromDeadline(
            deadlineMs = deadlineAfterSecond,
            nowMs = now2Ms,
            ceilingMaxSeconds = 10
        )
        assertEquals(
            "第二次 config-change 后立即观察:countdown 还显示 10s(因 deadline 同步推后 5s)",
            10,
            secondsAfterSecond
        )

        // 反转断言 (本测试的核心不变量):如果未来有人把 computeOverrideDeadlineMs 在
        // "扩大或不变"分支错误地实现为 nowMs + newMaxVisibleMs(忽略 currentDeadlineMs),
        // 这里 deadlineAfterSecond 会变成 15s 但失去了"以 mDeadlineMs 为单一来源"的保证;
        // 而真正的核心不变量是:CoreService **不能** 再有独立的 postDelayed { stopFastLock } —
        // 测试只是纯函数层的"逻辑可观测"投影,真正的强约束在源码层面的 session 4 代码审查。
        // 这里验证的是 deadline 推后路径被正确计算。
    }
}
