package com.decikeeper.app.manager

import com.decikeeper.app.data.calculateTemporaryAccessQuotaPlan
import com.decikeeper.app.ui.filterOneDecimal
import com.decikeeper.app.ui.formatAvailableMinutesForLockImpl
import com.decikeeper.app.ui.formatQuotaAvailableMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaAtomicPrecisionTest {

    @Test
    fun availableMinutesFormattingFloorsInsteadOfRounding() {
        // 143 秒 = 2.3833 分钟，保守取整应为 2.3 分钟，绝不能四舍五入虚标成 2.4
        assertEquals("2.3", formatAvailableMinutesForLockImpl(143.0 / 60.0))
        // 144 秒 = 2.4000 分钟，足额时展示 2.4
        assertEquals("2.4", formatAvailableMinutesForLockImpl(144.0 / 60.0))
        // 整数格式化无尾随零
        assertEquals("0", formatAvailableMinutesForLockImpl(0.0))
        assertEquals("2", formatAvailableMinutesForLockImpl(2.0))
    }

    @Test
    fun quotaAvailableSecondsFormattingFloorsToTenthMinute() {
        // 143 秒 -> 2.3 分钟
        assertEquals("2.3", formatQuotaAvailableMinutes(143))
        // 144 秒 -> 2.4 分钟
        assertEquals("2.4", formatQuotaAvailableMinutes(144))
        // 不足 6 秒 (0.1 分钟) -> 0.0
        assertEquals("0.0", formatQuotaAvailableMinutes(5))
        assertEquals("0.1", formatQuotaAvailableMinutes(6))
    }

    @Test
    fun oneDecimalInputFilterEnforcesMaxOneDecimalDigit() {
        // 1. 允许输入整数
        assertNull(filterOneDecimal("2", 0, 1, "", 0, 0))

        // 2. 允许输入单个小数点
        assertNull(filterOneDecimal(".", 0, 1, "2", 1, 1))

        // 3. 允许输入第 1 位小数
        assertNull(filterOneDecimal("4", 0, 1, "2.", 2, 2))

        // 4. 严禁输入第 2 位小数 (如 "2.4" 后面追加 "5" 变成 "2.45")
        assertEquals("", filterOneDecimal("5", 0, 1, "2.4", 3, 3))

        // 5. 严禁粘贴多位小数 (如在 "2." 后粘贴 "45")
        assertEquals("", filterOneDecimal("45", 0, 2, "2.", 2, 2))

        // 6. 严禁输入第二个小数点
        assertEquals("", filterOneDecimal(".", 0, 1, "2.4", 3, 3))
    }

    @Test
    fun ghostBorrowIsCompletelyEliminatedWhenDeficitIsUnderAtomicUnit() {
        // 用户申请 2.4 分钟 (144 秒)，底层实有 143 秒 (差 1 秒)
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 144,
            currentAvailableSec = 143,
            nextAvailableSec = 300,
            totalCountCostHalfUnits = 1,
            currentAvailableCountHalfUnits = 2,
            nextAvailableCountHalfUnits = 2
        )

        // 1. 绝不立案借贷
        assertFalse("不应进入借用阶段", plan.needsNextStage)
        assertEquals("借入本金必须为 0", 0, plan.borrowedTimePrincipalSec)
        assertEquals("下一阶段扣除必须为 0", 0, plan.nextTimeCostSec)

        // 2. 当前阶段完全吸收
        assertEquals(144, plan.currentTimeCostSec)

        // 3. UI 承诺与展示一致性
        // 此时 UI 扣费来源展示为 2.3 分钟
        assertEquals("2.3", formatQuotaAvailableMinutes(plan.currentAvailableSec))
    }
}
