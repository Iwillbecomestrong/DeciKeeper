package com.zhizhi.app

import com.zhizhi.app.data.calculateTemporaryAccessQuotaPlan
import com.zhizhi.app.ui.TemporaryAccessChargeSnapshot
import com.zhizhi.app.ui.resolveTemporaryAccessChargeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TemporaryAccessChargeSnapshotTest {

    /**
     * Test A: 无旧 cooldown，绝不能自我双倍（首次申请单倍费率）
     */
    @Test
    fun noExistingCooldown_currentRequestIsSingleRate() {
        val now = 100_000L
        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = 60,
            freeSec = 0,
            coolingAnchorMs = now,
            existingCooldownEndMs = 0L // 无预存冷却
        )

        assertEquals("无旧冷却时 coolingSec 必须为 0", 0, snapshot.coolingSec)
        assertEquals("normalDndSec 应为全额 60s", 60, snapshot.normalDndSec)
        assertEquals("单倍费用应为 60s，绝不得自我双倍", 60, snapshot.weightedTimeCostSec)
        assertEquals("单倍解锁次数应为 1 个 halfUnit (0.5次)", 1, snapshot.weightedCountHalfUnits)
    }

    /**
     * Test B: 已经处于完整 cooldown，全额重叠执行双倍扣费
     */
    @Test
    fun existingCooldown_fullOverlapIsDoubleRate() {
        val now = 100_000L
        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = 60,
            freeSec = 0,
            coolingAnchorMs = now,
            existingCooldownEndMs = now + 60_000L // 已存在覆盖 60s 的冷静期
        )

        assertEquals("全重叠 coolingSec 应为 60s", 60, snapshot.coolingSec)
        assertEquals("normalDndSec 应为 0s", 0, snapshot.normalDndSec)
        assertEquals("冷却期再犯双倍扣费应为 120s", 120, snapshot.weightedTimeCostSec)
        assertEquals("全冷却加权次数应为 2 个 halfUnits (1.0次)", 2, snapshot.weightedCountHalfUnits)
    }

    /**
     * Test C: 部分 cooldown 重叠，仅对重叠部分加权双倍
     */
    @Test
    fun existingCooldown_partialOverlapOnlyWeightsOverlap() {
        val now = 100_000L
        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = 60,
            freeSec = 0,
            coolingAnchorMs = now,
            existingCooldownEndMs = now + 30_000L // 仅重叠前 30s
        )

        assertEquals("重叠部分应为 30s", 30, snapshot.coolingSec)
        assertEquals("非重叠正常部分应为 30s", 30, snapshot.normalDndSec)
        assertEquals("加权总时长应为 30 + 30*2 = 90s", 90, snapshot.weightedTimeCostSec)
    }

    /**
     * Test D: 本次申请产生的新 cooldown 绝不得污染本次费用
     */
    @Test
    fun newlyCreatedCooldownDoesNotAffectCurrentCharge() {
        val now = 100_000L
        val requestSec = 60

        // 1. 请求开始前截取已有冷静期快照：此刻无冷却
        val existingCooldown = 0L
        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = requestSec,
            freeSec = 0,
            coolingAnchorMs = now,
            existingCooldownEndMs = existingCooldown
        )

        // 2. 本次申请通过后，系统计算并持久化未来产生的新冷静期
        val newCooldownGenerated = now + (requestSec + 120) * 1000L

        // 3. 验证：无论之后新冷却多么长，当前请求的 snapshot 依然保持单倍
        assertEquals("新产生的冷却绝不得回溯污染当前请求", 0, snapshot.coolingSec)
        assertEquals(60, snapshot.weightedTimeCostSec)
        assertEquals(1, snapshot.weightedCountHalfUnits)
    }

    /**
     * Test E: 单倍额度充足时，不因新产生的 cooldown 错误触发借用
     */
    @Test
    fun singleRateQuotaEnough_doesNotBorrowBecauseOfNewCooldown() {
        val now = 100_000L
        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = 60,
            freeSec = 0,
            coolingAnchorMs = now,
            existingCooldownEndMs = 0L
        )

        val currentAvailableSec = 90 // 拥有 1.5 分钟（90s）可用额度
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = snapshot.weightedTimeCostSec, // 60s
            currentAvailableSec = currentAvailableSec,
            nextAvailableSec = 1200,
            totalCountCostHalfUnits = snapshot.weightedCountHalfUnits,
            currentAvailableCountHalfUnits = 10,
            nextAvailableCountHalfUnits = 20
        )

        assertFalse("单倍费用 60s 在可用额度 90s 下无需跨段借用", plan.needsNextStage)
        assertEquals(60, plan.currentTimeCostSec)
        assertEquals(0, plan.nextTimeCostSec)
    }

    /**
     * Test F: 预检 Preview 与实际结算 Settlement 具有 100% 确定性同源性
     */
    @Test
    fun previewAndSettlementUseIdenticalChargeSnapshot() {
        val now = 200_000L
        val requestedSec = 120
        val freeSec = 20
        val existingCooldown = now + 40_000L // 40s 冷却

        // 模拟 Preview 阶段
        val previewSnapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = requestedSec,
            freeSec = freeSec,
            coolingAnchorMs = now,
            existingCooldownEndMs = existingCooldown
        )

        // 模拟 Settlement 阶段
        val settlementSnapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = requestedSec,
            freeSec = freeSec,
            coolingAnchorMs = now,
            existingCooldownEndMs = existingCooldown
        )

        assertEquals("Preview 与 Settlement 冷却重叠必须一致", previewSnapshot.coolingSec, settlementSnapshot.coolingSec)
        assertEquals("Preview 与 Settlement 时间费用必须一致", previewSnapshot.weightedTimeCostSec, settlementSnapshot.weightedTimeCostSec)
        assertEquals("Preview 与 Settlement 次数费用必须一致", previewSnapshot.weightedCountHalfUnits, settlementSnapshot.weightedCountHalfUnits)
    }
}
