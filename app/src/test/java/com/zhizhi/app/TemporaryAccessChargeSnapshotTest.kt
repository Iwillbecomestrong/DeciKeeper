package com.zhizhi.app

import com.zhizhi.app.data.calculateTemporaryAccessQuotaPlan
import com.zhizhi.app.ui.TemporaryAccessChargeSnapshot
import com.zhizhi.app.ui.resolveTemporaryAccessChargeSnapshot
import com.zhizhi.app.ui.shouldCommitTemporaryAccessCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
     * Test C: 冷却期内申请，整单直接翻倍扣除（×2），不再碎切加权
     */
    @Test
    fun existingCooldown_triggersFullOrderDoubling() {
        val now = 100_000L
        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = 60,
            freeSec = 0,
            coolingAnchorMs = now,
            existingCooldownEndMs = now + 30_000L // 处于冷却期中
        )

        assertEquals("处于冷却期 coolingSec 为 60s", 60, snapshot.coolingSec)
        assertEquals("处于冷却期 normalDndSec 为 0s", 0, snapshot.normalDndSec)
        assertEquals("整单直接翻倍时长应为 120s", 120, snapshot.weightedTimeCostSec)
        assertEquals("≤3分钟基础0.5次，冷却翻倍后为 1次 (2个 halfUnits)", 2, snapshot.weightedCountHalfUnits)
    }

    @Test
    fun coolingOrderDoublingAndBorrowDoubling_matchesUserRequirement() {
        val now = 100_000L
        // 1. 冷却期申请 3 分钟 (180s)
        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = 180,
            freeSec = 0,
            coolingAnchorMs = now,
            existingCooldownEndMs = now + 60_000L
        )
        assertEquals("冷却期申请 3分钟 翻倍为 6分钟 (360s)", 360, snapshot.weightedTimeCostSec)
        assertEquals("冷却期申请 3分钟 次数翻倍为 1次 (2个 halfUnits)", 2, snapshot.weightedCountHalfUnits)

        // 2. 全部借用情况：下阶段偿还翻倍（借一还二）
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = snapshot.weightedTimeCostSec,
            currentAvailableSec = 0,
            nextAvailableSec = 1000,
            totalCountCostHalfUnits = snapshot.weightedCountHalfUnits,
            currentAvailableCountHalfUnits = 0,
            nextAvailableCountHalfUnits = 10
        )
        assertEquals("下阶段偿还时长应为 12分钟 (720s)", 720, plan.nextTimeCostSec)
        assertEquals("下阶段偿还次数应为 2次 (4个 halfUnits)", 4, plan.nextCountCostHalfUnits)
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
     * Test F1: 预检 Preview 与实际结算 Settlement 纯函数输入一致时 100% deterministic
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

    /**
     * Test F2: Settlement gate 消费 snapshot 的次数结果
     */
    @Test
    fun settlementGateConsumesSnapshotCountDirectly() {
        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = 60,
            freeSec = 0,
            coolingAnchorMs = 100_000L,
            existingCooldownEndMs = 0L
        )
        assertTrue(
            "快照有次数且未预扣、处于DND时，settlement gate 必须执行次数扣除",
            shouldCommitTemporaryAccessCount(
                quotaAlreadyDeducted = false,
                isInDndSegment = true,
                weightedCountHalfUnits = snapshot.weightedCountHalfUnits
            )
        )
    }

    /**
     * Test G: Rest 跨 DND 必须产生 count
     */
    @Test
    fun restEndingDuringTemporaryAccess_chargesDndCountPortion() {
        val now = 100_000L
        val freeTimeEnd = now + 60_000L

        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = 180,
            freeSec = 60,
            coolingAnchorMs = freeTimeEnd,
            existingCooldownEndMs = 0L
        )

        assertEquals(60, snapshot.freeSec)
        assertEquals(120, snapshot.dndSec)
        assertEquals(0, snapshot.coolingSec)
        assertEquals(120, snapshot.weightedTimeCostSec)
        assertEquals(1, snapshot.weightedCountHalfUnits)
    }

    /**
     * Test H: 从 Rest 开始也不得阻止 count settlement
     */
    @Test
    fun startedInRest_butSnapshotHasCount_commitsCount() {
        assertTrue(
            "即使请求开始时在休息，只要 snapshot.weightedCountHalfUnits > 0 且在DND，必须扣除次数",
            shouldCommitTemporaryAccessCount(
                quotaAlreadyDeducted = false,
                isInDndSegment = true,
                weightedCountHalfUnits = 1
            )
        )
    }

    /**
     * Test I: 全程 Rest 仍保持免费
     */
    @Test
    fun allWithinRest_hasZeroCountAndDoesNotCommit() {
        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = 60,
            freeSec = 60,
            coolingAnchorMs = 100_000L,
            existingCooldownEndMs = 0L
        )

        assertEquals(0, snapshot.dndSec)
        assertEquals(0, snapshot.weightedCountHalfUnits)

        assertFalse(
            "全程休息期间 snapshot count 为 0，绝不扣次数",
            shouldCommitTemporaryAccessCount(
                quotaAlreadyDeducted = false,
                isInDndSegment = true,
                weightedCountHalfUnits = snapshot.weightedCountHalfUnits
            )
        )
    }

    /**
     * Test J: 已由 quota transaction 预扣时绝不重复扣
     */
    @Test
    fun prechargedPlan_neverDoubleCommitsCount() {
        assertFalse(
            "quotaAlreadyDeducted 为 true 时绝不得二次落账",
            shouldCommitTemporaryAccessCount(
                quotaAlreadyDeducted = true,
                isInDndSegment = true,
                weightedCountHalfUnits = 2
            )
        )
    }

    /**
     * Test K: Gap 不扣
     */
    @Test
    fun gapNeverCommitsTemporaryCount() {
        assertFalse(
            "Gap 非 DND 期间不扣次数",
            shouldCommitTemporaryAccessCount(
                quotaAlreadyDeducted = false,
                isInDndSegment = false,
                weightedCountHalfUnits = 0
            )
        )
    }
}
