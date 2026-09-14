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

object SettingsNavigator {

    private const val TAG = "SettingsNavigator"

    val manufacturer: String = Build.MANUFACTURER.lowercase(Locale.ROOT)
    val brand: String = Build.BRAND.lowercase(Locale.ROOT)

    fun isXiaomiOrRedmi(): Boolean =
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || brand.contains("xiaomi") || brand.contains("redmi")

    fun isHuaweiOrHonor(): Boolean =
        manufacturer.contains("huawei") || manufacturer.contains("honor") || brand.contains("huawei") || brand.contains("honor")

    fun isOppoOrOnePlusOrRealme(): Boolean =
        manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") ||
                brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme")

    fun isVivoOrIqoo(): Boolean =
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") || brand.contains("vivo") || brand.contains("iqoo")

    fun isMeizu(): Boolean =
        manufacturer.contains("meizu") || brand.contains("meizu")

    fun isSamsung(): Boolean =
        manufacturer.contains("samsung") || brand.contains("samsung")

    /**
     * 根据当前手机品牌获取针对性的后台保活与防睡眠冻结提示文案
     */
    fun getBrandKeepAliveTips(): String {
        return when {
            isXiaomiOrRedmi() -> "请将「省电策略」设为「无限制」，并开启「自启动」"
            isVivoOrIqoo() -> "请允许「后台高耗电」，并关闭电池设置中的「睡眠待机优化」"
            isHuaweiOrHonor() -> "请将应用启动管理设为「手动管理」（允许自启动、关联启动与后台活动）"
            isOppoOrOnePlusOrRealme() -> "请将耗电行为设为「完全允许后台行为」，开启「开机/自启动」，并关闭「睡眠待机优化」"
            isMeizu() -> "请将待机耗电管理设为「保持运行」，并开启「自启动」"
            isSamsung() -> "请将本应用加入「未休眠应用程序」白名单"
            else -> "请允许后台高耗电/完全后台行为并忽略电池优化；若系统有夜间睡眠待机优化请将其关闭"
        }
    }

    /**
     * 获取当前机型对应的后台耗电/行为权限友好展示文案
     */
    fun getBatteryOptimizationLabel(): String {
        return when {
            isOppoOrOnePlusOrRealme() -> "完全允许后台行为"
            isVivoOrIqoo() -> "允许后台高耗电"
            isXiaomiOrRedmi() -> "省电策略设为无限制"
            else -> "允许后台耗电/行为"
        }
    }

    /**
     * 获取当前机型对应的自启动权限友好展示文案（含开机启动别名）
     */
    fun getAutostartLabel(): String {
        return when {
            isOppoOrOnePlusOrRealme() -> "自启动 / 开机启动"
            else -> "自启动权限"
        }
    }

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

    // ─── 4. 全品牌自启动管理智能直跳 ───
    fun openAutostartSettings(context: Context): Boolean {
        val candidates = mutableListOf<Intent>()

        when {
            isXiaomiOrRedmi() -> {
                candidates.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            }
            isHuaweiOrHonor() -> {
                candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.honor.systemmanager", "com.honor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.honor.systemmanager", "com.honor.systemmanager.appcontrol.activity.StartupAppListActivity")))
            }
            isOppoOrOnePlusOrRealme() -> {
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startupInit.StartupNormalAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.batteryoptimization.BatteryOptimizationActivity")))
            }
            isVivoOrIqoo() -> {
                candidates.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.MainActivity")))
            }
            isMeizu() -> {
                candidates.add(Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity")))
            }
            isSamsung() -> {
                candidates.add(Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")))
            }
        }

        // 尝试启动厂商专属自启动组件
        for (intent in candidates) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }

        // 兜底：回退到应用详情页
        return openAppDetails(context)
    }

    // 兼容旧接口命名
    fun openVivoAutostart(context: Context): Boolean = openAutostartSettings(context)

    // ─── 5. 全品牌电池省电策略 / 后台耗电 / 睡眠优化直跳 ───
    fun openBatteryKeepAliveSettings(context: Context): Boolean {
        val candidates = mutableListOf<Intent>()

        when {
            isXiaomiOrRedmi() -> {
                // 小米神隐模式 / 应用智能省电（直接设为无限制）
                candidates.add(Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY").apply {
                    putExtra("package_name", context.packageName)
                    putExtra("package_label", "决定守护")
                })
                candidates.add(Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")).apply {
                    putExtra("package_name", context.packageName)
                })
                candidates.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerCenter")))
            }
            isHuaweiOrHonor() -> {
                candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity")))
            }
            isOppoOrOnePlusOrRealme() -> {
                candidates.add(Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.BatteryMainActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.batteryoptimization.BatteryOptimizationActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaard.PowerConsumptionActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaard.PowerSaverModeActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.powermanager", "com.coloros.powermanager.fuelgaard.PowerConsumptionActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.powermanager", "com.coloros.powermanager.fuelgaard.PowerSaverModeActivity")))
            }
            isVivoOrIqoo() -> {
                candidates.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.HighPowerConsumptionActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.HighPowerConsumptionActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.activity.ExcessivePowerDescriptionActivity")).apply {
                    putExtra("package_name", context.packageName)
                })
                candidates.add(Intent().setComponent(ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")).apply {
                    putExtra("package_name", context.packageName)
                })
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.activity.ExcessivePowerManagerActivity")).apply {
                    putExtra("package_name", context.packageName)
                })
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.BackgroundHighUsageActivity")).apply {
                    putExtra("package_name", context.packageName)
                })
            }
            isMeizu() -> {
                candidates.add(Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")))
            }
            isSamsung() -> {
                candidates.add(Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")))
            }
        }

        // 系统标准电源管理页面
        candidates.add(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        candidates.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

        for (intent in candidates) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }

        // 兜底：先尝试标准忽略电池优化请求弹窗，若失败再进 App 详情页
        if (openBatteryOptimization(context)) return true
        return openAppDetails(context)
    }

    // 兼容旧接口命名
    fun openVivoHighPowerConsumption(context: Context): Boolean = openBatteryKeepAliveSettings(context)

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
