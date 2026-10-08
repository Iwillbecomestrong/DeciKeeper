package com.decikeeper.app.manager

import com.decikeeper.app.ui.LockWindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统一冷却时长与提前结束全额退未用部分的契约：
 * - 休息冷却设置是休息 / 临时解锁 / 特权共用的唯一冷却时长，0 表示无冷却；
 * - 提前结束退款比例恒为 100%（未用部分全退）。
 */
class UnifiedCooldownAndRefundTest {

    @Test
    fun unifiedCooldownZero_meansNoCooldown() {
        assertEquals(0L, LockWindowManager.calculateTemporaryReuseCooldownMs(0))
    }

    @Test
    fun unifiedCooldownPositiveIsUsedAsIs_negativeClampedToZero() {
        assertEquals(120_000L, LockWindowManager.calculateTemporaryReuseCooldownMs(120))
        assertEquals(0L, LockWindowManager.calculateTemporaryReuseCooldownMs(-5))
    }

    @Test
    fun earlyEndRefund_returnsAllUnusedWithoutFloor() {
        // 已用 100% 也不再有 75% 保底折扣：未用 10 → 退 10
        assertEquals(10.0, CreditManager.calculateProportionalRefund(10.0, 1.0), 0.0001)
        assertEquals(10.0, CreditManager.calculateProportionalRefund(10.0, 0.5), 0.0001)
        assertEquals(10.0, CreditManager.calculateProportionalRefund(10.0, 0.0), 0.0001)
    }
}
