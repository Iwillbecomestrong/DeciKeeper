package com.decikeeper.app.manager

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

object RecentTasksHideManager {
    private const val PREFS_NAME = "recent_tasks_hide_prefs"
    private const val KEY_HIDE_ENABLED = "hide_in_recent_tasks_enabled"
    private const val TAG = "RecentTasksHideManager"

    private val isHideEnabledCached = AtomicReference<Boolean?>(null)
    private var registeredApplication: Application? = null
    private val callback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) {
            // 双向:ON 设 true, OFF 显式设 false
            // 否则 toggle 关掉后,之前 ON 设过的 Activity 永远是 true
            applyExcludeFromRecents(activity, isHideEnabled(activity))
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /**
     * 把当前 Activity 所在 task 按 hide boolean 排出/放回最近任务列表。
     * `Activity` 无 `setExcludeFromRecents`；正确路径是 `ActivityManager.AppTask.setExcludeFromRecents`。
     * 必须双向设置:`hide=true` → setExcludeFromRecents(true); `hide=false` → setExcludeFromRecents(false)。
     * 兼容 Android 10+ 的 taskId 与旧版 id。
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


    fun isHideEnabled(context: Context): Boolean {
        isHideEnabledCached.get()?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val v = prefs.getBoolean(KEY_HIDE_ENABLED, false)
        isHideEnabledCached.set(v)
        return v
    }

    fun setHideEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HIDE_ENABLED, enabled).apply()
        isHideEnabledCached.set(enabled)
        // toggle 切换后立刻 sweep 当前进程所有 task,避免"开关改完回桌面不生效"
        applyToAllRunningTasks(enabled)
    }

    /**
     * Sweep 当前进程所有 running task 立即应用 hide 值。
     * 没有 task 时(用户从未启动 IronWill)跳过;这种情况下次启动 IronWill 自然应用。
     */
    private fun applyToAllRunningTasks(hide: Boolean) {
        val app = registeredApplication ?: return
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        try {
            for (task in am.appTasks) {
                task.setExcludeFromRecents(hide)
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyToAllRunningTasks failed", e)
        }
    }

    fun register(context: Context) {
        val app = context.applicationContext as? Application ?: return
        // 防重复挂载
        if (registeredApplication === app) return
        // 反注册旧 callback(若有)
        registeredApplication?.unregisterActivityLifecycleCallbacks(callback)
        // 预热 cache
        isHideEnabledCached.set(null)
        isHideEnabled(app)
        app.registerActivityLifecycleCallbacks(callback)
        registeredApplication = app
    }

    fun unregister() {
        registeredApplication?.unregisterActivityLifecycleCallbacks(callback)
        registeredApplication = null
        isHideEnabledCached.set(null)
    }
}
