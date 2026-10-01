package com.decikeeper.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.appcompat.app.AlertDialog
import com.decikeeper.app.R
import com.decikeeper.app.util.DirectBootState

object ThemeManager {
    private const val PREFS = "decikeeper_ui_theme"
    private const val KEY = "theme"
    private val keys = arrayOf("spring", "summer", "autumn", "winter")
    val labels = arrayOf("春 · 苔樱", "夏 · 潮汐", "秋 · 茶陶", "冬 · 雪岚")

    // Direct Boot 红线：用户解锁前 CE 存储不可用，getSharedPreferences 会抛 IllegalStateException
    // 并使 FastLockService 等开机早期组件崩溃。未解锁时一律返回默认主题，不触碰 CE。
    fun currentKey(context: Context): String {
        if (!DirectBootState.isUserUnlocked(context)) return "summer"
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "summer") ?: "summer"
        } catch (_: IllegalStateException) {
            "summer"
        }
    }
    fun currentLabel(context: Context): String = labels[keys.indexOf(currentKey(context)).coerceAtLeast(0)]

    fun themeRes(context: Context): Int {
        if (!DirectBootState.isUserUnlocked(context)) return R.style.Theme_DeciKeeper
        return when (currentKey(context)) {
            "spring" -> R.style.Theme_DeciKeeper_Spring
            "autumn" -> R.style.Theme_DeciKeeper_Autumn
            "winter" -> R.style.Theme_DeciKeeper_Winter
            else -> R.style.Theme_DeciKeeper_Summer
        }
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
        val typedValue = TypedValue()
        // 1. 先尝试直接从传入 context 的 theme 解析
        if (context.theme != null && context.theme.resolveAttribute(attrRes, typedValue, true) && typedValue.data != 0) {
            return typedValue.data
        }

        // 2. 若传入的是 ApplicationContext 或未注入主题的 Context，使用当季主题 Wrapper 解析
        val seasonRes = themeRes(context)
        val wrappedContext = if (context is android.view.ContextThemeWrapper || context is androidx.appcompat.view.ContextThemeWrapper) {
            context
        } else {
            android.view.ContextThemeWrapper(context, seasonRes)
        }
        if (wrappedContext.theme != null && wrappedContext.theme.resolveAttribute(attrRes, typedValue, true) && typedValue.data != 0) {
            return typedValue.data
        }

        // 3. 终极兜底：查表返回当季固化色值，绝对禁止返回 0 (即透明色)
        return getSeasonColorFallback(currentKey(context), attrRes)
    }

    private fun getSeasonColorFallback(season: String, attrRes: Int): Int {
        return when (season) {
            "spring" -> when (attrRes) {
                R.attr.zzPageBg -> Color.parseColor("#F7F8F2")
                R.attr.zzSurface -> Color.parseColor("#FFFDF8")
                R.attr.zzSurface2 -> Color.parseColor("#F0F4E9")
                R.attr.zzSurface3 -> Color.parseColor("#F7ECEE")
                R.attr.zzSurfaceStrong -> Color.parseColor("#E7EDE0")
                R.attr.zzText -> Color.parseColor("#29352C")
                R.attr.zzMuted -> Color.parseColor("#6B776D")
                R.attr.zzSubtle -> Color.parseColor("#8B958D")
                R.attr.zzLine -> Color.parseColor("#DBE2D6")
                R.attr.zzAccent -> Color.parseColor("#67816C")
                R.attr.zzAccentStrong -> Color.parseColor("#4F6655")
                R.attr.zzWarning -> Color.parseColor("#7D8436")
                R.attr.zzDanger -> Color.parseColor("#B2455A")
                R.attr.zzSuccess -> Color.parseColor("#607B63")
                else -> Color.parseColor("#F7F8F2")
            }
            "autumn" -> when (attrRes) {
                R.attr.zzPageBg -> Color.parseColor("#F6F1E8")
                R.attr.zzSurface -> Color.parseColor("#FFF9EF")
                R.attr.zzSurface2 -> Color.parseColor("#EFE7D9")
                R.attr.zzSurface3 -> Color.parseColor("#F4E4DD")
                R.attr.zzSurfaceStrong -> Color.parseColor("#E6D9C8")
                R.attr.zzText -> Color.parseColor("#3A3229")
                R.attr.zzMuted -> Color.parseColor("#776D61")
                R.attr.zzSubtle -> Color.parseColor("#958A7D")
                R.attr.zzLine -> Color.parseColor("#E2D7C6")
                R.attr.zzAccent -> Color.parseColor("#806D55")
                R.attr.zzAccentStrong -> Color.parseColor("#65543F")
                R.attr.zzWarning -> Color.parseColor("#A86725")
                R.attr.zzDanger -> Color.parseColor("#B24C38")
                R.attr.zzSuccess -> Color.parseColor("#6D7957")
                else -> Color.parseColor("#F6F1E8")
            }
            "winter" -> when (attrRes) {
                R.attr.zzPageBg -> Color.parseColor("#F6F8F9")
                R.attr.zzSurface -> Color.parseColor("#FFFFFF")
                R.attr.zzSurface2 -> Color.parseColor("#EEF3F5")
                R.attr.zzSurface3 -> Color.parseColor("#F0F3F7")
                R.attr.zzSurfaceStrong -> Color.parseColor("#E2E8EE")
                R.attr.zzText -> Color.parseColor("#293238")
                R.attr.zzMuted -> Color.parseColor("#69747B")
                R.attr.zzSubtle -> Color.parseColor("#8C959B")
                R.attr.zzLine -> Color.parseColor("#DCE3E7")
                R.attr.zzAccent -> Color.parseColor("#647983")
                R.attr.zzAccentStrong -> Color.parseColor("#4B5F69")
                R.attr.zzWarning -> Color.parseColor("#657788")
                R.attr.zzDanger -> Color.parseColor("#944F69")
                R.attr.zzSuccess -> Color.parseColor("#667B72")
                else -> Color.parseColor("#F6F8F9")
            }
            else -> when (attrRes) { // summer (default)
                R.attr.zzPageBg -> Color.parseColor("#F2F8F8")
                R.attr.zzSurface -> Color.parseColor("#FBFFFF")
                R.attr.zzSurface2 -> Color.parseColor("#E8F3F2")
                R.attr.zzSurface3 -> Color.parseColor("#EAF2F6")
                R.attr.zzSurfaceStrong -> Color.parseColor("#DDEBEA")
                R.attr.zzText -> Color.parseColor("#24383A")
                R.attr.zzMuted -> Color.parseColor("#65787A")
                R.attr.zzSubtle -> Color.parseColor("#879799")
                R.attr.zzLine -> Color.parseColor("#D2E1E1")
                R.attr.zzAccent -> Color.parseColor("#4C7C7D")
                R.attr.zzAccentStrong -> Color.parseColor("#365F61")
                R.attr.zzWarning -> Color.parseColor("#3D868F")
                R.attr.zzDanger -> Color.parseColor("#AD4848")
                R.attr.zzSuccess -> Color.parseColor("#537C70")
                else -> Color.parseColor("#F2F8F8")
            }
        }
    }
}
