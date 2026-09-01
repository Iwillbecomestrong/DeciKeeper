package com.ironwill.app.ui

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.ironwill.app.R

/**
 * Lightweight auto-init hook that injects the emergency-whitelist card into the
 * existing global settings screen without coupling the large settings Activity
 * to alarm-specific selection logic.
 */
class AlarmEmergencyWhitelistUiProvider : ContentProvider() {
    companion object {
        private const val CARD_TAG = "alarm_emergency_whitelist_settings_card"
    }

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                installOrRefresh(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                installOrRefresh(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        return true
    }

    private fun installOrRefresh(activity: Activity) {
        // 应急名单入口已统一收敛至闹钟主页面右上角「🛡️ 应急应用」，
        // 全局参数页面不再重复注入该卡片；若存在旧视图则清理。
        if (activity !is GlobalParamsActivity) return
        val root = activity.findViewById<NestedScrollView>(R.id.root_global_params) ?: return
        val column = root.getChildAt(0) as? LinearLayout ?: return
        val existing = column.findViewWithTag<AlarmEmergencyWhitelistSettingsCard>(CARD_TAG)
        if (existing != null) {
            column.removeView(existing)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
