package com.decikeeper.app

import com.decikeeper.app.data.calculateTemporaryAccessQuotaPlan
import com.decikeeper.app.manager.LockDecisionPolicy
import com.decikeeper.app.manager.LockOverlayRestrictionMode
import com.decikeeper.app.manager.resolveAutomaticRestrictionRelease
import com.decikeeper.app.manager.resolveEmergencyActionPresentation
import com.decikeeper.app.manager.resolveLockOverlayRestrictionMode
import com.decikeeper.app.manager.shouldRouteEmergencyToTempAccess
import com.decikeeper.app.manager.shouldShowStandaloneTemporaryAccess
import com.decikeeper.app.ui.resolveTemporaryAccessChargeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决定守护 (DeciKeeper) 双锁系统与临时解锁机制 Spec v1.0 全量验收测试矩阵
 * 严格覆盖 focus.md 第 11 节与第 12 节定义的 8 大核心测试用例 (Case 1 ~ Case 8)
 */
class DualLockSystemContractTest {

    /**
     * Case 1: NORMAL 状态下发起临时解锁扣费
     * - 扣除实际申请时长
     * - 基础扣除 0.5 次 (1 half-unit)
     * - 冷却整单翻倍 (时长×2, 次数×2)
     * - 借用本金翻倍 (借一还二)
     */
    @Test
    fun case1_normalState_temporaryAccessChargesQuotaWithDoublingRules() {
        val now = 100_000L
        val requestedSec = 180 // 3 分钟

        // 1. 无冷却单倍情况
        val normalSnapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = requestedSec,
            freeSec = 0,
            coolingAnchorMs = now,
            existingCooldownEndMs = 0L
        )
        assertEquals("实际申请 180s 扣除 180s", 180, normalSnapshot.weightedTimeCostSec)
        assertEquals("基础次数为 0.5 次 (1 half-unit)", 1, normalSnapshot.weightedCountHalfUnits)

        // 2. 处于冷却期整单翻倍
        val coolingSnapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = requestedSec,
            freeSec = 0,
            coolingAnchorMs = now,
            existingCooldownEndMs = now + 60_000L
        )
        assertEquals("冷却期时长整单翻倍为 360s", 360, coolingSnapshot.weightedTimeCostSec)
        assertEquals("冷却期次数整单翻倍为 1.0 次 (2 half-units)", 2, coolingSnapshot.weightedCountHalfUnits)

        // 3. 额度不足时借用本金翻倍
        val borrowPlan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = normalSnapshot.weightedTimeCostSec, // 180s
            currentAvailableSec = 60, // 当前段仅剩 60s, 需借 120s
            nextAvailableSec = 1000,
            totalCountCostHalfUnits = normalSnapshot.weightedCountHalfUnits, // 1 half-unit
            currentAvailableCountHalfUnits = 0, // 当前段无次数可用, 需借 1 half-unit
            nextAvailableCountHalfUnits = 10
        )
        assertTrue(borrowPlan.needsNextStage)
        assertEquals("借用时间本金 120s", 120, borrowPlan.borrowedTimePrincipalSec)
        assertEquals("下阶段翻倍偿还时间 240s", 240, borrowPlan.nextTimeCostSec)
        assertEquals("借用次数本金 1 half-unit", 1, borrowPlan.borrowedCountPrincipalHalfUnits)
        assertEquals("下阶段翻倍偿还次数 2 half-units (1.0 次)", 2, borrowPlan.nextCountCostHalfUnits)
    }

    /**
     * Case 2: FREE_TIME (空档期) 自由状态发起临时解锁完全免费
     * - 时长扣除 0
     * - 次数扣除 0
     * - 即使存在残留冷却也不受惩罚
     */
    @Test
    fun case2_freeTime_temporaryAccessIsFree() {
        val now = 100_000L
        val requestedSec = 180

        // 处于空档期 (freeSec == requestedSec)，且就算有残留冷却
        val freeTimeSnapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = requestedSec,
            freeSec = requestedSec,
            coolingAnchorMs = now,
            existingCooldownEndMs = now + 60_000L
        )
        assertEquals("空档期自由状态时长扣除必须为 0", 0, freeTimeSnapshot.weightedTimeCostSec)
        assertEquals("空档期自由状态次数扣除必须为 0", 0, freeTimeSnapshot.weightedCountHalfUnits)
        assertEquals("自由状态下不受冷却影响，coolingSec 为 0", 0, freeTimeSnapshot.coolingSec)
    }

    /**
     * Case 3: REST (休息状态) 自由状态发起临时解锁完全免费
     * - 时长扣除 0
     * - 次数扣除 0
     * - 不重复收费
     */
    @Test
    fun case3_restState_temporaryAccessIsFree() {
        val now = 100_000L
        val requestedSec = 120

        // 已经处于休息中 (freeSec == requestedSec)
        val restSnapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = requestedSec,
            freeSec = requestedSec,
            coolingAnchorMs = now,
            existingCooldownEndMs = 0L
        )
        assertEquals("REST 状态时长费用为 0", 0, restSnapshot.weightedTimeCostSec)
        assertEquals("REST 状态次数费用为 0", 0, restSnapshot.weightedCountHalfUnits)
    }

    /**
     * Case 4: 处于勿扰时段但应用未加入勿扰名单 (仅命中时间窗锁)
     * - 因当前时间处于勿扰时段且未休息，用户状态为 NORMAL
     * - 临时解锁扣除当前勿扰时段的额度
     */
    @Test
    fun case4_dndSegmentNonBlacklistApp_underAppWindowLock_chargesQuota() {
        val now = 100_000L
        val requestedSec = 60

        // 处于勿扰时段且未休息，freeSec 为 0
        val snapshot = resolveTemporaryAccessChargeSnapshot(
            requestedSec = requestedSec,
            freeSec = 0,
            coolingAnchorMs = now,
            existingCooldownEndMs = 0L
        )
        assertEquals("NORMAL 状态扣除实际时长 60s", 60, snapshot.weightedTimeCostSec)
        assertEquals("NORMAL 状态扣除 0.5 次 (1 half-unit)", 1, snapshot.weightedCountHalfUnits)
    }

    /**
     * Case 5: PRIVILEGE (特权状态) 双锁解除
     * - 双锁均已真正解除，应用直接放行
     * - 绝不进入临时解锁流程
     */
    @Test
    fun case5_privilegeState_releasesBothLocksAndNeverEntersTemporaryAccess() {
        // 双锁均处于阻断配置下：isBlacklisted=true, isDndLocked=true, isAppWindowBlocked=true
        val blocked = LockDecisionPolicy.shouldBlock(
            isBlacklisted = true,
            isSafe = false,
            isPrivilege = true, // 特权开启
            isDndLocked = true,
            isAppWindowBlocked = true,
            segmentDecoupled = false,
            isManaged = { true },
            effectivePackageName = "com.example.game",
            isTemporaryAccess = false
        )
        assertFalse("特权开启时两把锁均解除，判定绝不拦截", blocked)
    }

    /**
     * Case 6: 纯勿扰锁屏出口呈现正交化
     * - 仅展示「申请休息」与「每周特权」
     * - 彻底移除「临时解锁」
     */
    @Test
    fun case6_pureDndLockScreen_exitPresentationIsOrthogonal() {
        val mode = resolveLockOverlayRestrictionMode(
            isAppWindowBlocked = false,
            isWithinDndSegment = true
        )
        assertEquals(LockOverlayRestrictionMode.DND, mode)

        // 1. 主操作为申请休息
        val primaryAction = resolveEmergencyActionPresentation(
            mode = mode,
            privilegeUnavailableReason = null,
            restExhausted = false,
            inAnyCooling = false
        )
        assertEquals("🌱 申请休息", primaryAction.label)
        assertFalse("主操作绝不路由到临时解锁", shouldRouteEmergencyToTempAccess(mode))

        // 2. 独立临时解锁按钮在纯勿扰下彻底隐藏
        assertFalse(
            "纯勿扰锁屏上必须彻底移除临时解锁入口",
            shouldShowStandaloneTemporaryAccess(mode)
        )
    }

    /**
     * Case 7: 双锁并存锁屏出口呈现正交化
     * - 优先展示应用时间窗限制
     * - 主操作为「临时解锁」，保留「每周特权」
     * - 不提供「申请休息」
     */
    @Test
    fun case7_dualLockScreen_exitPresentationPrioritizesAppWindowAndExcludesRest() {
        val mode = resolveLockOverlayRestrictionMode(
            isAppWindowBlocked = true,
            isWithinDndSegment = true
        )
        // 双锁并存时优先呈现为 APP_WINDOW 模式
        assertEquals(LockOverlayRestrictionMode.APP_WINDOW, mode)

        // 1. 主操作为临时解锁
        val primaryAction = resolveEmergencyActionPresentation(
            mode = mode,
            privilegeUnavailableReason = null,
            restExhausted = false,
            inAnyCooling = false
        )
        assertEquals("⚡ 临时解锁", primaryAction.label)
        assertTrue("双锁主操作必须路由到临时解锁", shouldRouteEmergencyToTempAccess(mode))

        // 2. 不提供申请休息 (因为休息无法解时间窗)
        assertFalse("主操作不是申请休息", primaryAction.label.contains("申请休息"))
    }

    /**
     * Case 8: 时间进入允许区间与桌面状态
     * - 当系统时间到达放行时刻 (双锁解除)，锁屏自动关闭放行
     * - 返回桌面不是解锁，再次进入同一应用重新拦截
     */
    @Test
    fun case8_naturalTimeEntryAndDesktopState() {
        // 1. 自然跨界到达允许时刻：勿扰结束且时间窗开启
        val autoRelease = resolveAutomaticRestrictionRelease(
            isDndBlocked = false,
            isAppWindowBlocked = false,
            hasActiveRest = false,
            hasActiveTemporaryAccess = false,
            hasBlacklistOverlay = true // 锁屏正处于展示中
        )
        assertTrue("双锁自然解除时，必须自动解除黑名单锁屏覆盖层", autoRelease.dismissBlacklistOverlay)

        // 2. 若仍有任一锁生效，绝不自动解除
        val stillBlockedDnd = resolveAutomaticRestrictionRelease(
            isDndBlocked = true,
            isAppWindowBlocked = false,
            hasActiveRest = false,
            hasActiveTemporaryAccess = false,
            hasBlacklistOverlay = true
        )
        assertFalse("勿扰锁依然有效时，绝不自动解除", stillBlockedDnd.dismissBlacklistOverlay)

        val stillBlockedAppWindow = resolveAutomaticRestrictionRelease(
            isDndBlocked = false,
            isAppWindowBlocked = true,
            hasActiveRest = false,
            hasActiveTemporaryAccess = false,
            hasBlacklistOverlay = true
        )
        assertFalse("时间窗锁依然有效时，绝不自动解除", stillBlockedAppWindow.dismissBlacklistOverlay)

        // 3. 回到桌面验证：前台不是黑名单应用时不拦截，但黑名单本身规则依然存在
        val shouldBlockGame = LockDecisionPolicy.shouldBlock(
            isBlacklisted = true,
            isSafe = false,
            isPrivilege = false,
            isDndLocked = true,
            isAppWindowBlocked = false,
            segmentDecoupled = false,
            isManaged = { true },
            effectivePackageName = "com.example.game"
        )
        assertTrue("返回桌面后再次打开黑名单游戏，依然重新执行拦截", shouldBlockGame)
    }
}
