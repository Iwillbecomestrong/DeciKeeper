package com.decikeeper.app.protection

import android.content.Context
import com.decikeeper.app.manager.RecentTasksHideManager
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

    @Test fun 隐藏功能已下线始终为false() {
        assertFalse(RecentTasksHideManager.isHideEnabled(context))
        RecentTasksHideManager.setHideEnabled(context, true)
        assertFalse(RecentTasksHideManager.isHideEnabled(context))
        RecentTasksHideManager.setHideEnabled(context, false)
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

    @Test fun 用户切离恒定不应抹除() {
        RecentTasksHideManager.setHideEnabled(context, true)
        RecentTasksHideManager.clearNavigating()
        assertFalse(RecentTasksHideManager.shouldDismissOnUserLeave(context))
    }

    @Test fun 导航状态跟踪正常() {
        RecentTasksHideManager.markNavigating()
        assertTrue(RecentTasksHideManager.isNavigating())
        RecentTasksHideManager.clearNavigating()
        assertFalse(RecentTasksHideManager.isNavigating())
    }
}
