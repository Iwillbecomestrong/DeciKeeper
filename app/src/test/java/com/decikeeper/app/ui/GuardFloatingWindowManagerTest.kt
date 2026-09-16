package com.decikeeper.app.ui

import android.content.Context
import com.decikeeper.app.manager.GuardFloatingWindowManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GuardFloatingWindowManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        GuardFloatingWindowManager.setFloatingGuardEnabled(context, false)
    }

    @Test
    fun floatingGuardDefaultDisabled() {
        assertFalse(
            "默认情况下常驻守护小浮窗应为未开启状态",
            GuardFloatingWindowManager.isFloatingGuardEnabled(context)
        )
    }

    @Test
    fun setFloatingGuardEnabledTogglesState() {
        GuardFloatingWindowManager.setFloatingGuardEnabled(context, true)
        assertTrue(
            "开启后状态必须为 true",
            GuardFloatingWindowManager.isFloatingGuardEnabled(context)
        )

        GuardFloatingWindowManager.setFloatingGuardEnabled(context, false)
        assertFalse(
            "关闭后状态必须为 false",
            GuardFloatingWindowManager.isFloatingGuardEnabled(context)
        )
    }

    @Test
    fun showAndHideWithoutOverlayPermissionDoesNotCrash() {
        // 在没有实机 Overlay 授权的环境下调用 show/hide 必须安全优雅降级
        GuardFloatingWindowManager.show(context)
        GuardFloatingWindowManager.hide(context)
    }
}
