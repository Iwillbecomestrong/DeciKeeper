package com.zhizhi.app.ui

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.zhizhi.app.R

object ThemeManager {
    private const val PREFS = "zhizhi_ui_theme"
    private const val KEY = "theme"
    private val keys = arrayOf("spring", "summer", "autumn", "winter")
    val labels = arrayOf("春 · 苔樱", "夏 · 潮汐", "秋 · 茶陶", "冬 · 雪岚")

    fun currentKey(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "spring") ?: "spring"
    fun currentLabel(context: Context): String = labels[keys.indexOf(currentKey(context)).coerceAtLeast(0)]

    fun themeRes(context: Context): Int = when (currentKey(context)) {
        "summer" -> R.style.Theme_ZhiZhi_Summer
        "autumn" -> R.style.Theme_ZhiZhi_Autumn
        "winter" -> R.style.Theme_ZhiZhi_Winter
        else -> R.style.Theme_ZhiZhi_Spring
    }

    fun apply(activity: Activity) = activity.setTheme(themeRes(activity))

    fun showPicker(activity: Activity) {
        val current = keys.indexOf(currentKey(activity)).coerceAtLeast(0)
        var selected = current
        AlertDialog.Builder(activity)
            .setTitle("选择主题")
            .setSingleChoiceItems(labels, current) { _, which -> selected = which }
            .setNegativeButton("取消", null)
            .setPositiveButton("应用") { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, keys[selected]).apply()
                activity.recreate()
            }
            .show()
    }

    fun getColorAttr(context: Context, attrRes: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.data
    }
}
