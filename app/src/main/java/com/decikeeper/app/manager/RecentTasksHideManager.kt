package com.decikeeper.app.manager

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 最近任务卡片管理。
 * 注：原“隐藏在多任务界面”功能已彻底下线（详见 archive/recent-tasks-hide 标签与 commit 记录）。
 * 现代国产 ROM（OPPO ColorOS / Vivo OriginOS）上，多任务卡片保留有利于用户进行“下拉加锁”与“小窗挂起”以达到稳定保活。
 * 此处保留导航保护标记与确保卡片不被错误排出的兼容桩。
 */
object RecentTasksHideManager {
    private const val TAG = "RecentTasksHideManager"

    private var registeredApplication: Application? = null

    @Volatile
    private var navigatingTimestamp: Long = 0L

    @Volatile
    private var isNavigatingExternal: Boolean = false

    /**
     * 标记当前正在进行主动外部/子页面导航（如打开文件选择器导出/导入配置、跳系统权限设置等）。
     */
    fun markNavigating() {
        navigatingTimestamp = android.os.SystemClock.uptimeMillis()
        isNavigatingExternal = true
    }

    /**
     * 恢复就绪态（通常在 Activity 的 onResume 中调用）。
     */
    fun clearNavigating() {
        isNavigatingExternal = false
        navigatingTimestamp = 0L
    }

    /**
     * 判断当前是否处于主动导航状态或在 3000ms 导航保护期内。
     */
    fun isNavigating(): Boolean {
        if (isNavigatingExternal) return true
        if (navigatingTimestamp <= 0L) return false
        val elapsed = android.os.SystemClock.uptimeMillis() - navigatingTimestamp
        return elapsed in 0..3000
    }

    /**
     * 已下线：多任务抹除功能彻底关闭，始终返回 false。
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldDismissOnUserLeave(context: Context): Boolean = false

    private val callback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) {
            // 确保任务卡片正常显示在多任务列表（保证用户可下拉加锁保活）
            applyExcludeFromRecents(activity, false)
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /**
     * 确保 Activity 所在 task 未被排出最近任务列表。
     */
    private fun applyExcludeFromRecents(activity: Activity, hide: Boolean) {
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val currentTaskId = activity.taskId
            for (task in am.appTasks) {
                val info = task.taskInfo
                val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.id
                if (id == currentTaskId) {
                    task.setExcludeFromRecents(hide)
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyExcludeFromRecents failed", e)
        }
    }

    /**
     * 功能已下线：恒定返回 false。
     */
    @Suppress("UNUSED_PARAMETER")
    fun isHideEnabled(context: Context): Boolean = false

    /**
     * 功能已下线：空实现桩。
     */
    @Suppress("UNUSED_PARAMETER")
    fun setHideEnabled(context: Context, enabled: Boolean) {
        // no-op, 功能已下线
    }

    fun register(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (registeredApplication === app) return
        registeredApplication?.unregisterActivityLifecycleCallbacks(callback)
        app.registerActivityLifecycleCallbacks(callback)
        registeredApplication = app
    }

    fun unregister() {
        registeredApplication?.unregisterActivityLifecycleCallbacks(callback)
        registeredApplication = null
        clearNavigating()
    }
}
