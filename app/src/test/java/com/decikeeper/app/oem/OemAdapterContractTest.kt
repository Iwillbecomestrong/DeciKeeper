package com.decikeeper.app.oem

import com.decikeeper.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 OEM 差异化策略契约与适配器的行为正确性。
 * 确保各机型物理隔离且特性显隐符合设计：
 * - vivo: 隐私空间专属开启，设备管理器防卸载默认隐藏，专属帮助提示生效
 * - oppo: 设备管理器防卸载开启，隐私空间默认隐藏，专属帮助提示生效
 * - default: 未深入验证品牌全部兜底隐藏，防范误导与不可用特性平铺
 */
class OemAdapterContractTest {

    @Test
    fun testVivoOemAdapterContract() {
        val adapter = VivoOemAdapter()
        assertEquals("vivo", adapter.brandName)
        assertFalse("vivo 机型默认不展示设备管理器防卸载锁", adapter.isDeviceAdminRecommended())
        assertTrue("vivo 机型支持并展示原子隐私系统保护", adapter.isPrivacySpaceGuardSupported())
        assertEquals(R.string.settings_protection_help_vivo_note, adapter.getProtectionHelpNoteRes())
    }

    @Test
    fun testOppoOemAdapterContract() {
        val adapter = OppoOemAdapter()
        assertEquals("oppo", adapter.brandName)
        assertTrue("OPPO 机型支持并展示设备管理器防卸载锁", adapter.isDeviceAdminRecommended())
        assertFalse("OPPO 机型不展示隐私空间保护", adapter.isPrivacySpaceGuardSupported())
        assertEquals(R.string.settings_protection_help_oppo_note, adapter.getProtectionHelpNoteRes())
    }

    @Test
    fun testDefaultOemAdapterContract() {
        val adapter = DefaultOemAdapter()
        assertEquals("default", adapter.brandName)
        assertFalse("通用/兜底机型默认不展示设备管理器", adapter.isDeviceAdminRecommended())
        assertFalse("通用/兜底机型默认不展示隐私空间", adapter.isPrivacySpaceGuardSupported())
        assertNull("通用/兜底机型无专属说明追加", adapter.getProtectionHelpNoteRes())
    }

    @Test
    fun testOemManagerResolution() {
        assertTrue(OemManager.resolveAdapter("vivo", "vivo") is VivoOemAdapter)
        assertTrue(OemManager.resolveAdapter("iQOO", "iqoo") is VivoOemAdapter)
        assertTrue(OemManager.resolveAdapter("oppo", "oppo") is OppoOemAdapter)
        assertTrue(OemManager.resolveAdapter("OnePlus", "OnePlus") is OppoOemAdapter)
        assertTrue(OemManager.resolveAdapter("realme", "realme") is OppoOemAdapter)
        assertTrue(OemManager.resolveAdapter("Xiaomi", "Redmi") is DefaultOemAdapter)
        assertTrue(OemManager.resolveAdapter("Huawei", "HONOR") is DefaultOemAdapter)
        assertTrue(OemManager.resolveAdapter(null, null) is DefaultOemAdapter)
    }

    @Test
    fun testOemManagerMockSwitching() {
        val defaultAdapter = DefaultOemAdapter()
        val vivoAdapter = VivoOemAdapter()
        val oppoAdapter = OppoOemAdapter()

        try {
            OemManager.setMockAdapterForTesting(vivoAdapter)
            assertEquals("vivo", OemManager.current.brandName)
            assertTrue(OemManager.current.isPrivacySpaceGuardSupported())

            OemManager.setMockAdapterForTesting(oppoAdapter)
            assertEquals("oppo", OemManager.current.brandName)
            assertTrue(OemManager.current.isDeviceAdminRecommended())

            OemManager.setMockAdapterForTesting(defaultAdapter)
            assertEquals("default", OemManager.current.brandName)
            assertFalse(OemManager.current.isDeviceAdminRecommended())
            assertFalse(OemManager.current.isPrivacySpaceGuardSupported())
        } finally {
            OemManager.resetForTesting()
        }
    }
}
