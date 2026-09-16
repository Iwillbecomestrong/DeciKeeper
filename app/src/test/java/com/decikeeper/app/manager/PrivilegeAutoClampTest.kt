package com.decikeeper.app.manager

import com.decikeeper.app.ui.HomeFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test



/**
 * 特权时长自动截断 (CAT-B) 单元测试。
 *
 * 目标：CHANGELOG v0.2.0 — 15:02 申请 59h 应当被自动 clamp 到 478min
 * （窗口 7:00-23:00 + 24:00 边界）并 Toast 提示，而不是弹错误让用户重输。
 *
 * 这里只测纯函数（companion 中的 clampRequestedToEffectiveMinutes、
 * buildPrivilegeAutoClampToast、buildPrivilegeBoundaryLabel），
 * 避免依赖 SharedPreferences / System.currentTimeMillis()。
 */
class PrivilegeAutoClampTest {

    // =================== clampRequestedToEffectiveMinutes ===================

    @Test
    fun requestedExceedsWindowBoundary_clampedToEffective() {
        // 15:02, window 7:00-23:00, remaining 480 min
        // requested = 3540 (59h), effective = 478 (边界)
        val result = HomeFragment.clampRequestedToEffectiveMinutes(
            requested = 3540,
            effective = 478
        )
        assertTrue("应被截断", result.didClamp)
        assertEquals("clamp 到 effective", 478, result.actualMinutes)
        assertEquals("保留原始请求值", 3540, result.originalRequested)
    }

    @Test
    fun requestedWithinWindow_notClamped() {
        // 15:02, window 7:00-23:00
        // requested = 30, effective = 478
        val result = HomeFragment.clampRequestedToEffectiveMinutes(
            requested = 30,
            effective = 478
        )
        assertFalse("不应被截断", result.didClamp)
        assertEquals("保持原值", 30, result.actualMinutes)
        assertEquals("originalRequested 一致", 30, result.originalRequested)
    }

    @Test
    fun requestedEqualsEffective_notClamped() {
        // requested = 478, effective = 478
        val result = HomeFragment.clampRequestedToEffectiveMinutes(
            requested = 478,
            effective = 478
        )
        assertFalse("等值时不应标记为截断", result.didClamp)
        assertEquals(478, result.actualMinutes)
    }

    @Test
    fun requestedZeroOrNegative_safeHandled() {
        // 防御：调用方漏判时也不应崩
        val resultZero = HomeFragment.clampRequestedToEffectiveMinutes(
            requested = 0,
            effective = 100
        )
        assertEquals(0, resultZero.actualMinutes)
        assertFalse(resultZero.didClamp)

        val resultNegative = HomeFragment.clampRequestedToEffectiveMinutes(
            requested = -5,
            effective = 100
        )
        // 负数被 coerceAtLeast(0) 视为 0，不触发截断
        assertEquals(0, resultNegative.actualMinutes)
        assertFalse(resultNegative.didClamp)
    }

    @Test
    fun effectiveZeroOrNegative_treatsAsZeroUpperBound() {
        // 窗口外 / 配额耗尽 — effective = 0 时 clamp 到 0（调用方应已拦截）
        val result = HomeFragment.clampRequestedToEffectiveMinutes(
            requested = 30,
            effective = 0
        )
        assertTrue("任何 requested > 0 在 effective=0 时都被截断", result.didClamp)
        assertEquals(0, result.actualMinutes)
    }

    // =================== buildPrivilegeAutoClampToast ===================

    @Test
    fun toastFormat_includesMinutesAndBoundary() {
        val toast = HomeFragment.buildPrivilegeAutoClampToast(
            clampedMinutes = 478,
            boundaryLabel = "到 23:00 边界"
        )
        assertEquals("已自动调整为 478 分钟 (到 23:00 边界)", toast)
    }

    @Test
    fun toastFormat_withMidnightBoundary() {
        val toast = HomeFragment.buildPrivilegeAutoClampToast(
            clampedMinutes = 60,
            boundaryLabel = "到 24:00 边界"
        )
        assertEquals("已自动调整为 60 分钟 (到 24:00 边界)", toast)
    }

    // =================== buildPrivilegeBoundaryLabel ===================

    @Test
    fun boundaryLabel_defaultWindowEnd() {
        assertEquals("到 23:00 边界", HomeFragment.buildPrivilegeBoundaryLabel("23:00"))
        assertEquals("到 24:00 边界", HomeFragment.buildPrivilegeBoundaryLabel("24:00"))
        assertEquals("到 08:00 边界", HomeFragment.buildPrivilegeBoundaryLabel("08:00"))
    }

    // =================== 端到端场景 (15:02 申请 59h) ===================

    @Test
    fun endToEnd_1502_apply59h_clampsTo478() {
        // 复现用户报告的 15:02 场景：
        //  - 7:00-23:00 窗口
        //  - 24:00 午夜边界
        //  - 15:02 → 到 23:00 还有 7h58min = 478min
        //  - 用户输入 3540min (59h) → 应被 clamp 到 478min
        val effective = 478  // 由 CreditManager.calculateWeeklyPrivilegeEffectiveMinutes 计算
        val requested = 3540  // 用户输入 59h
        val boundary = "到 23:00 边界"  // windowEnd=23:00

        val clamp = HomeFragment.clampRequestedToEffectiveMinutes(requested, effective)
        assertTrue(clamp.didClamp)
        assertEquals(478, clamp.actualMinutes)

        val toast = HomeFragment.buildPrivilegeAutoClampToast(clamp.actualMinutes, boundary)
        assertEquals("已自动调整为 478 分钟 (到 23:00 边界)", toast)
    }
}
