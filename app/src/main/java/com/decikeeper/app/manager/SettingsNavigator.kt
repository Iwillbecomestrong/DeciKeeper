/**
 * =================================================================================
 * [Context Header]
 * * @file: SettingsNavigator.kt
 * * @role: Domain Skill
 * * @description: 集中管理"跳设置"逻辑,涉及 3 类目标。
 * * @responsibilities:
 * 1. Android 标准 API 跳转(电池优化 / 精确闹钟 / 全屏意图 / App 详情)
 * 2. Vivo 私有 component(自启动 / 高耗电),按 FuntouchOS/OriginOS 多版本 fallback
 * 3. 统一兜底:跳不到就回 App 详情页
 * * @context:
 * - 上游: MainActivity / HelpActivity / 自检页面
 * - 下游: Android 系统设置 / Vivo iManager
 * * @constraints (必读):
 * 1. 所有跳转都必须 try-catch,不要让组件找不到时崩溃
 * 2. Vivo 私有 component 名会随 OS 版本变化,fallback 链要保留多个候选
 * 3. 工具类(无状态),不要存 Context
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Locale
import com.decikeeper.app.oem.OemManager

object SettingsNavigator {

    private const val TAG = "SettingsNavigator"

    val manufacturer: String = Build.MANUFACTURER.lowercase(Locale.ROOT)
    val brand: String = Build.BRAND.lowercase(Locale.ROOT)

    const val OPPO_BATTERY_KEEP_ALIVE_HINT = "请在「应用详情」中找到「耗电管理/电池」，并勾选「完全允许后台行为」"

    fun isXiaomiOrRedmi(): Boolean =
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || brand.contains("xiaomi") || brand.contains("redmi")

    fun isHuaweiOrHonor(): Boolean =
        manufacturer.contains("huawei") || manufacturer.contains("honor") || brand.contains("huawei") || brand.contains("honor")

    fun isOppoOrOnePlusOrRealme(): Boolean = OemManager.current.brandName == "oppo"

    fun isVivoOrIqoo(): Boolean = OemManager.current.brandName == "vivo"

    fun isChineseRomDevice(): Boolean =
        isXiaomiOrRedmi() || isHuaweiOrHonor() || isOppoOrOnePlusOrRealme() || isVivoOrIqoo() ||
                manufacturer.contains("meizu") || brand.contains("meizu")

    fun isMeizu(): Boolean =
        manufacturer.contains("meizu") || brand.contains("meizu")

    fun isSamsung(): Boolean =
        manufacturer.contains("samsung") || brand.contains("samsung")

    /**
     * 根据当前手机品牌获取针对性的后台保活与防睡眠冻结提示文案
     */
    fun getBrandKeepAliveTips(): String = OemManager.current.getKeepAliveTips()

    /**
     * 获取针对各品牌系统级「睡眠待机优化 / 省电管理」关闭指引的提示文案
     */
    fun getSleepOptimizationTips(): String = OemManager.current.getSleepOptimizationTips()

    /**
     * 获取当前机型对应的后台耗电/行为权限友好展示文案
     */
    fun getBatteryOptimizationLabel(): String = OemManager.current.getBatteryOptimizationLabel()

    /**
     * 获取当前机型对应的自启动权限友好展示文案（含开机启动与关联启动别名）
     */
    fun getAutostartLabel(): String = OemManager.current.getAutostartLabel()

    /**
     * 获取针对 OPPO/ColorOS 闪达小窗保活建议文案
     */
    fun getOppoWindowTips(): String =
        "提示：ColorOS 支持将决定守护设为「小窗/闪达窗口」常驻，小窗模式下可有效免疫多任务一键清理误杀。"

    internal fun startActivitySafely(context: Context, intent: Intent): Boolean {
        return try {
            RecentTasksHideManager.markNavigating()
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            RecentTasksHideManager.clearNavigating()
            false
        }
    }

    // ─── 1. 电池优化白名单(标准 API,所有厂商都支持) ───
    fun openBatteryOptimization(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            if (startActivitySafely(context, intent)) return true
        }
        return false
    }

    // ─── 2. 精确闹钟权限(Android 12+, API 31) ───
    fun openExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            if (startActivitySafely(context, intent)) return true
        }
        return false
    }

    // ─── 3. 全屏意图权限(Android 14+, API 34) ───
    fun openFullScreenIntentPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            if (startActivitySafely(context, intent)) return true
        }
        return false
    }

    // ─── 4. 全品牌自启动管理智能直跳 ───
    fun openAutostartSettings(context: Context): Boolean =
        OemManager.current.openAutostartSettings(context)

    // ─── 4.1 全品牌关联启动智能直跳 ───
    fun openAssociateStartSettings(context: Context): Boolean =
        OemManager.current.openAssociateStartSettings(context)

    // ─── 4.2 系统「设置 → 应用」总入口直跳 ───
    fun openApplicationSettings(context: Context): Boolean {
        val candidates = mutableListOf<Intent>()
        candidates.add(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        candidates.add(Intent("android.settings.APPLICATION_SETTINGS"))
        candidates.add(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$ManageApplicationsActivity")))
        candidates.add(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$AppAndNotificationDashboardActivity")))
        candidates.add(Intent().setComponent(ComponentName("com.oplus.settings", "com.oplus.settings.Settings\$AppAndNotificationDashboardActivity")))
        for (intent in candidates) {
            if (startActivitySafely(context, intent)) return true
        }
        return openAppDetails(context)
    }

    // 兼容旧接口命名
    fun openVivoAutostart(context: Context): Boolean = openAutostartSettings(context)

    // ─── 5. 全品牌电池省电策略 / 后台耗电 / 睡眠优化直跳 ───
    fun openBatteryKeepAliveSettings(context: Context): Boolean =
        OemManager.current.openBatteryKeepAliveSettings(context)

    // 兼容旧接口命名
    fun openVivoHighPowerConsumption(context: Context): Boolean = openBatteryKeepAliveSettings(context)

    // ─── 6. 全品牌系统级「睡眠待机优化 / 省电管理」直跳（对睡眠的优化，区别于单个应用的后台高耗电） ───
    fun openSleepOptimizationSettings(context: Context): Boolean =
        OemManager.current.openSleepOptimizationSettings(context)

    // ─── 7. 兜底:App 详情页(100% 稳妥的核心入口) ───
    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (startActivitySafely(context, intent)) return true

        // 终极降级：若极少数特殊系统阻断应用详情，打开系统设置主页
        val fallback = Intent(Settings.ACTION_SETTINGS)
        return startActivitySafely(context, fallback)
    }

    /**
     * 尝试多个 ComponentName,全部失败回退到 App 详情页。
     */
    private fun tryStartAny(
        context: Context, components: List<ComponentName>, label: String
    ): Boolean {
        for (component in components) {
            val intent = Intent().apply {
                this.component = component
            }
            if (startActivitySafely(context, intent)) {
                Log.i(TAG, "$label: jumped to $component")
                return true
            }
        }
        Log.w(TAG, "$label: no candidate works, fallback to app details")
        return openAppDetails(context)
    }
}
