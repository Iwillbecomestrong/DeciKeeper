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
            override fun onActivityResumed(activity: Activity) {
                if (activity !is GlobalParamsActivity) return
                val root = activity.findViewById<NestedScrollView>(R.id.root_global_params) ?: return
                val column = root.getChildAt(0) as? LinearLayout ?: return
                val existing = column.findViewWithTag<AlarmEmergencyWhitelistSettingsCard>(CARD_TAG)
                if (existing != null) {
                    existing.refresh()
                    return
                }
                val card = AlarmEmergencyWhitelistSettingsCard(activity).apply {
                    tag = CARD_TAG
                }
                // Keep it near the top of global settings, after the header/pending area.
                val index = minOf(3, column.childCount)
                column.addView(
                    card,
                    index,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        val m = (16 * activity.resources.displayMetrics.density).toInt()
                        bottomMargin = m
                    }
                )
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
