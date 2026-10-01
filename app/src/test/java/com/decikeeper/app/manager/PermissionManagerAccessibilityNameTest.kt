package com.decikeeper.app.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 系统可能以短格式保存无障碍服务名（vivo），判定必须按组件归一化。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PermissionManagerAccessibilityNameTest {
    private val pkg = "com.decikeeper.app"
    private val cls = "com.decikeeper.app.service.CoreService"

    @Test
    fun `short and long component names are both recognized`() {
        assertTrue(PermissionManager.isComponentListed("$pkg/.service.CoreService", pkg, cls))
        assertTrue(PermissionManager.isComponentListed("$pkg/$cls", pkg, cls))
        assertTrue(PermissionManager.isComponentListed("other.pkg/.Svc:$pkg/.service.CoreService", pkg, cls))
    }

    @Test
    fun `other services or empty do not match`() {
        assertFalse(PermissionManager.isComponentListed("other.pkg/.Svc", pkg, cls))
        assertFalse(PermissionManager.isComponentListed("", pkg, cls))
        assertFalse(PermissionManager.isComponentListed(null, pkg, cls))
    }
}
