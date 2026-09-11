package com.decikeeper.app

import com.decikeeper.app.manager.CreditManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 借用惩罚公式契约。
 *
 * **口径（2026-09-11 产品决定）**：借用不再按秒分区加权，改为平坦倍率——
 * - 借入区间**触及冷却期** → 整单 ×4（借用惩罚 ×2 × 冷却 ×2）
 * - 未触及冷却 → 整单 ×2（「借一还二」）
 * - 计费基数只含 冷却秒 + 勿扰秒，**空档期借用不收惩罚**（沿用旧公式 gap×0）
 *
 * 旧实现 `(冷却秒×2 + 勿扰秒×1 + 空档秒×0) × 2` 是分区加权：同样"碰了冷却"，
 * 只有落进冷却的那几秒按 ×4，其余按 ×2/×0。该口径已废弃。
 *
 * 此前函数无任何测试覆盖，本文件补齐。
 */
class BorrowPenaltyFormulaTest {

    @Test
    fun borrowingDuringCoolingIsChargedQuadruple() {
        // 借 8 分钟、整段落在冷却期 → 惩罚 32 分钟
        assertEquals(480 * 4, CreditManager.calculateWeightedBorrowSeconds(480, 0, 0))
    }

    @Test
    fun borrowingOutsideCoolingIsChargedDouble() {
        // 借 8 分钟、整段在勿扰非冷却 → 借一还二 → 惩罚 16 分钟
        assertEquals(480 * 2, CreditManager.calculateWeightedBorrowSeconds(0, 480, 0))
    }

    @Test
    fun touchingCoolingMakesTheWholeBorrowQuadruple() {
        // ★ 关键：只要触及冷却，整单 ×4，不再只有重叠的那几秒加倍。
        // 旧口径：(120×2 + 360×1) × 2 = 1200；新口径：480 × 4 = 1920
        assertEquals(480 * 4, CreditManager.calculateWeightedBorrowSeconds(120, 360, 0))
    }

    @Test
    fun gapSecondsAreNotChargeable() {
        // 空档期借用不收惩罚（沿用旧公式 gap×0）。三处生产调用点均传 0，
        // 该分支目前不影响真实行为，仅锁定语义。
        assertEquals(0, CreditManager.calculateWeightedBorrowSeconds(0, 0, 480))
        // 空档 + 勿扰：只有勿扰部分计费
        assertEquals(240 * 2, CreditManager.calculateWeightedBorrowSeconds(0, 240, 240))
    }

    @Test
    fun zeroAndNegativeInputsAreSafe() {
        assertEquals(0, CreditManager.calculateWeightedBorrowSeconds(0, 0, 0))
        assertEquals(0, CreditManager.calculateWeightedBorrowSeconds(-10, -10, -10))
    }
}
