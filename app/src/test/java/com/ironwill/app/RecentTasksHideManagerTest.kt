package com.ironwill.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecentTasksHideManagerTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 清掉 prefs + cache
        RecentTasksHideManager.unregister()
        context.getSharedPreferences("recent_tasks_hide_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun tearDown() {
        RecentTasksHideManager.unregister()
    }

    @Test fun 默认false() {
        // prefs 刚清空后 isHideEnabled 必须 false
        assertFalse(RecentTasksHideManager.isHideEnabled(context))
    }

    @Test fun setHideEnabledTrue可读出() {
        RecentTasksHideManager.setHideEnabled(context, true)
        val prefs = context.getSharedPreferences("recent_tasks_hide_prefs", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("hide_in_recent_tasks_enabled", false))
        assertTrue(RecentTasksHideManager.isHideEnabled(context))
    }

    @Test fun setHideEnabledFalse可读出() {
        RecentTasksHideManager.setHideEnabled(context, true)  // 先 true
        RecentTasksHideManager.setHideEnabled(context, false) // 后 false
        assertFalse(RecentTasksHideManager.isHideEnabled(context))
    }

    @Test fun register可多次重复调不抛() {
        // repeat 3 次 register,不应抛异常
        repeat(3) { RecentTasksHideManager.register(context) }
        // 不抛即成功
    }

    @Test fun unregister后register可重新挂() {
        RecentTasksHideManager.register(context)
        RecentTasksHideManager.unregister()
        RecentTasksHideManager.register(context)  // 再挂不应抛
    }
}
