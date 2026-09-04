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
package com.zhizhi.app.manager

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

object SettingsNavigator {

    private const val TAG = "SettingsNavigator"

    // ─── 1. 电池优化白名单(标准 API,所有厂商都支持) ───
    fun openBatteryOptimization(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            true
        } else false
    } catch (e: Exception) {
        Log.w(TAG, "openBatteryOptimization failed", e); false
    }

    // ─── 2. 精确闹钟权限(Android 12+, API 31) ───
    fun openExactAlarmPermission(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            true
        } else false
    } catch (e: Exception) {
        Log.w(TAG, "openExactAlarmPermission failed", e); false
    }

    // ─── 3. 全屏意图权限(Android 14+, API 34) ───
    fun openFullScreenIntentPermission(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            true
        } else false
    } catch (e: Exception) {
        Log.w(TAG, "openFullScreenIntentPermission failed", e); false
    }

    // ─── 4. Vivo 自启动(Vivo 私有 component,多版本 fallback) ───
    fun openVivoAutostart(context: Context): Boolean = tryStartAny(
        context,
        listOf(
            // OriginOS 3+ / 新版 FuntouchOS
            ComponentName("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            // 老版 FuntouchOS
            ComponentName("com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.MainActivity")
        ),
        "Vivo autostart"
    )

    // ─── 5. Vivo 高耗电(Vivo 私有 component) ───
    fun openVivoHighPowerConsumption(context: Context): Boolean = tryStartAny(
        context,
        listOf(
            ComponentName("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.HighPowerConsumptionActivity"),
            ComponentName("com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.HighPowerConsumptionActivity")
        ),
        "Vivo high power"
    )

    // ─── 6. 兜底:App 详情页(用户可手动找) ───
    fun openAppDetails(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "openAppDetails failed", e); false
    }

    /**
     * 尝试多个 ComponentName,全部失败回退到 App 详情页。
     * Vivo 不同 OS 版本(FuntouchOS/OriginOS)对应不同的 component,
     * 顺序: 新版 → 旧版 → 兜底
     */
    private fun tryStartAny(
        context: Context, components: List<ComponentName>, label: String
    ): Boolean {
        for (component in components) {
            try {
                context.startActivity(Intent().apply {
                    this.component = component
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                Log.i(TAG, "$label: jumped to $component")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "$label: $component not found, trying next")
            } catch (e: Exception) {
                Log.w(TAG, "$label: $component failed", e)
            }
        }
        Log.w(TAG, "$label: no candidate works, fallback to app details")
        return openAppDetails(context)
    }
}
