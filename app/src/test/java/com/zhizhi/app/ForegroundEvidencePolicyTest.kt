package com.zhizhi.app

import com.zhizhi.app.manager.ForegroundEvidence
import com.zhizhi.app.manager.ForegroundEvidenceDecision
import com.zhizhi.app.manager.ForegroundEvidencePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundEvidencePolicyTest {

    @Test
    fun douyinCaseWith2037msDelayIsConfirmed() {
        // 复现抖音漏拦场景：FG 事件在 1000ms，lastVisible 很快在 1050ms 刷新，检查时刻为 3037ms
        val evidence = ForegroundEvidence(
            packageName = "com.ss.android.ugc.aweme",
            foregroundEventAtMs = 1000L,
            backgroundEventAtMs = 0L,
            lastTimeVisibleMs = 1050L,
            checkedAtMs = 3037L
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)
        assertEquals(ForegroundEvidenceDecision.CONFIRMED, decision)
        assertTrue(ForegroundEvidencePolicy.isGuardPassed(decision))
    }

    @Test
    fun longCheckDelayWithCorrelatedVisibleIsConfirmed() {
        // 前台事件发生 4 秒后才处理，只要 lastVisible 能因果对应且无 BG 事件，依然确认为真前台
        val evidence = ForegroundEvidence(
            packageName = "com.ss.android.ugc.aweme",
            foregroundEventAtMs = 1000L,
            backgroundEventAtMs = 0L,
            lastTimeVisibleMs = 1200L,
            checkedAtMs = 5000L
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)
        assertEquals(ForegroundEvidenceDecision.CONFIRMED, decision)
        assertTrue(ForegroundEvidencePolicy.isGuardPassed(decision))
    }

    @Test
    fun foregroundEventBeyondMaxAgeIsRejected() {
        // FG 事件发生在 1000ms，检查时刻为 10000ms（相差 9000ms > 8000ms），即使 lastVisible 曾经对齐，也因过期而拒绝
        val evidence = ForegroundEvidence(
            packageName = "com.ss.android.ugc.aweme",
            foregroundEventAtMs = 1000L,
            backgroundEventAtMs = 0L,
            lastTimeVisibleMs = 1050L,
            checkedAtMs = 10000L
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)
        assertEquals(ForegroundEvidenceDecision.VISIBLE_NOT_CORRELATED, decision)
        assertFalse(ForegroundEvidencePolicy.isGuardPassed(decision))
    }

    @Test
    fun pendingVisibleDataWithinGracePeriodRequestsFastRecheck() {
        // 首帧扫描时 UsageEvents 已到（2000ms），但系统 UsageStats 尚未落盘刷新（lastVisible 仍为 500ms），
        // 检查时刻 2200ms 处于 1500ms Pending 宽限期内
        val evidence = ForegroundEvidence(
            packageName = "com.ss.android.ugc.aweme",
            foregroundEventAtMs = 2000L,
            backgroundEventAtMs = 0L,
            lastTimeVisibleMs = 500L,
            checkedAtMs = 2200L
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)
        assertEquals(ForegroundEvidenceDecision.VISIBLE_DATA_PENDING, decision)
        assertFalse(ForegroundEvidencePolicy.isGuardPassed(decision))
        assertTrue(ForegroundEvidencePolicy.shouldRequestFastRecheck(decision))
    }

    @Test
    fun pendingVisibleDataExpiredBeyondGracePeriodIsRejected() {
        // FG 事件在 2000ms，lastVisible 仍为 500ms，但在 4000ms（超过 1500ms 宽限期）仍然未刷新
        val evidence = ForegroundEvidence(
            packageName = "com.ss.android.ugc.aweme",
            foregroundEventAtMs = 2000L,
            backgroundEventAtMs = 0L,
            lastTimeVisibleMs = 500L,
            checkedAtMs = 4000L
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)
        assertEquals(ForegroundEvidenceDecision.VISIBLE_NOT_CORRELATED, decision)
        assertFalse(ForegroundEvidencePolicy.isGuardPassed(decision))
        assertFalse(ForegroundEvidencePolicy.shouldRequestFastRecheck(decision))
    }

    @Test
    fun newerBackgroundEventRejectsForeground() {
        // FG 在 1000ms，但 1500ms 时切到了后台，即使 1200ms 的 lastVisible 曾经对齐，也必须否决
        val evidence = ForegroundEvidence(
            packageName = "com.ss.android.ugc.aweme",
            foregroundEventAtMs = 1000L,
            backgroundEventAtMs = 1500L,
            lastTimeVisibleMs = 1200L,
            checkedAtMs = 2000L
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)
        assertEquals(ForegroundEvidenceDecision.BACKGROUND_EVENT_NEWER, decision)
        assertFalse(ForegroundEvidencePolicy.isGuardPassed(decision))
    }

    @Test
    fun equalBackgroundEventRejectsForeground() {
        // FG 和 BG 时间戳相同，或者 BG 事件紧随发生
        val evidence = ForegroundEvidence(
            packageName = "com.ss.android.ugc.aweme",
            foregroundEventAtMs = 1000L,
            backgroundEventAtMs = 1000L,
            lastTimeVisibleMs = 1000L,
            checkedAtMs = 2000L
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)
        assertEquals(ForegroundEvidenceDecision.BACKGROUND_EVENT_NEWER, decision)
        assertFalse(ForegroundEvidencePolicy.isGuardPassed(decision))
    }

    @Test
    fun noForegroundEventIsRejected() {
        // 无前台事件（fgEventAt <= 0）
        val evidence = ForegroundEvidence(
            packageName = "com.ss.android.ugc.aweme",
            foregroundEventAtMs = 0L,
            backgroundEventAtMs = 0L,
            lastTimeVisibleMs = 1000L,
            checkedAtMs = 2000L
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)
        assertEquals(ForegroundEvidenceDecision.NO_FOREGROUND_EVENT, decision)
        assertFalse(ForegroundEvidencePolicy.isGuardPassed(decision))
    }

    @Test
    fun visibleSlightlyPrecedesForegroundWithinPreEventToleranceIsConfirmed() {
        // 系统时钟或写入微小前置偏差（例如 lastVisible 950ms，FG 1000ms，在 1000ms 容差内）
        val evidence = ForegroundEvidence(
            packageName = "com.ss.android.ugc.aweme",
            foregroundEventAtMs = 1000L,
            backgroundEventAtMs = 0L,
            lastTimeVisibleMs = 950L,
            checkedAtMs = 2000L
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)
        assertEquals(ForegroundEvidenceDecision.CONFIRMED, decision)
        assertTrue(ForegroundEvidencePolicy.isGuardPassed(decision))
    }

    @Test
    fun futureForegroundEventBeyondClockSkewIsRejected() {
        // FG 发生在未来（FG 5000ms，当前检查时间 2000ms）
        val evidence = ForegroundEvidence(
            packageName = "com.ss.android.ugc.aweme",
            foregroundEventAtMs = 5000L,
            backgroundEventAtMs = 0L,
            lastTimeVisibleMs = 5000L,
            checkedAtMs = 2000L
        )
        val decision = ForegroundEvidencePolicy.evaluate(evidence)
        assertEquals(ForegroundEvidenceDecision.VISIBLE_NOT_CORRELATED, decision)
        assertFalse(ForegroundEvidencePolicy.isGuardPassed(decision))
    }
}
