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
            isOppoOrOnePlusOrRealme() -> "请在「应用详情」中找到「耗电管理/电池」，并勾选「完全允许后台行为」"
            isXiaomiOrRedmi() -> "请在系统设置中找到「省电策略」，设为「无限制」，并开启「自启动」"
            isVivoOrIqoo() -> "请在系统设置中允许「后台高耗电」，并关闭电池设置中的「睡眠待机优化」"
            isHuaweiOrHonor() -> "请在「应用启动管理」设为「手动管理」（允许自启动、关联启动与后台活动）"
            isMeizu() -> "请在系统设置中将待机耗电管理设为「保持运行」，并开启「自启动」"
            isSamsung() -> "请将本应用加入「未休眠应用程序」白名单"
            else -> "请在系统设置中找到「耗电管理」「电池优化」或类似选项，并允许应用后台运行"
        }
    }

    /**
     * 获取针对各品牌系统级「睡眠待机优化 / 省电管理」关闭指引的提示文案
     * （系统级对睡眠的优化，区别于单个应用的后台高耗电行为）
     */
    fun getSleepOptimizationTips(): String {
        return when {
            isVivoOrIqoo() -> "请前往：电池 → 省电模式 → 省电管理，关闭「睡眠待机优化」"
            isOppoOrOnePlusOrRealme() -> "请在「电池 → 高级设置」或「省电设置」中关闭「睡眠待机优化」"
            isXiaomiOrRedmi() -> "请在「电池与性能」中检查夜间睡眠待机或极速省电设置"
            isHuaweiOrHonor() -> "请在「电池」中检查睡眠休眠设置"
            else -> "请在系统电池或省电设置中，关闭夜间「睡眠待机优化」"
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
     * 获取当前机型对应的自启动权限友好展示文案（含开机启动与关联启动别名）
     */
    fun getAutostartLabel(): String {
        return when {
            isOppoOrOnePlusOrRealme() -> "自启动与关联启动"
            else -> "自启动权限"
        }
    }

    /**
     * 获取针对 OPPO/ColorOS 闪达小窗保活建议文案
     */
    fun getOppoWindowTips(): String =
        "提示：ColorOS 支持将决定守护设为「小窗/闪达窗口」常驻，小窗模式下可有效免疫多任务一键清理误杀。"

    private fun startActivitySafely(context: Context, intent: Intent): Boolean {
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
                // ColorOS / Oplus 专属自启动列表组件
                candidates.add(Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startupInit.StartupNormalAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.AutoBootActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.startupdetails.StartupCombineActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.startupdetails.StartupCombineActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.batteryoptimization.BatteryOptimizationActivity")))
                // OPPO 单独应用信息页没有自启动开关，因此优先进入「设置 → 应用」总菜单
                candidates.add(Intent(Settings.ACTION_APPLICATION_SETTINGS))
                candidates.add(Intent("android.settings.APPLICATION_SETTINGS"))
                candidates.add(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$ManageApplicationsActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$AppAndNotificationDashboardActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.settings", "com.oplus.settings.Settings\$AppAndNotificationDashboardActivity")))
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
            if (startActivitySafely(context, intent)) return true
        }

        // 兜底：OPPO 优先跳「设置 → 应用」；其他机型回退到应用详情页
        if (isOppoOrOnePlusOrRealme()) {
            if (openApplicationSettings(context)) return true
        }
        return openAppDetails(context)
    }

    // ─── 4.1 全品牌关联启动智能直跳 ───
    fun openAssociateStartSettings(context: Context): Boolean {
        val candidates = mutableListOf<Intent>()

        when {
            isOppoOrOnePlusOrRealme() -> {
                // ColorOS / Oplus 专属关联启动组件
                candidates.add(Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.AssociateStartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.AssociateStartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.securitypermission", "com.oplus.securitypermission.startup.AssociateStartupActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.AssociateStartupActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.AssociateStartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.AssociateStartupAppListActivity")))
                // ColorOS 自启动与关联启动同在「设置 → 应用」
                candidates.add(Intent(Settings.ACTION_APPLICATION_SETTINGS))
                candidates.add(Intent("android.settings.APPLICATION_SETTINGS"))
                candidates.add(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$ManageApplicationsActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$AppAndNotificationDashboardActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.settings", "com.oplus.settings.Settings\$AppAndNotificationDashboardActivity")))
            }
            isHuaweiOrHonor() -> {
                candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppListActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.honor.systemmanager", "com.honor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
            }
            isVivoOrIqoo() -> {
                candidates.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")).apply {
                    putExtra("packagename", context.packageName)
                    putExtra("packageName", context.packageName)
                    putExtra("pkg", context.packageName)
                })
                candidates.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
            }
        }

        for (intent in candidates) {
            if (startActivitySafely(context, intent)) return true
        }

        if (isOppoOrOnePlusOrRealme()) {
            if (openApplicationSettings(context)) return true
        }
        return openAppDetails(context)
    }

    // ─── 4.2 系统「设置 → 应用」总入口直跳 ───
    fun openApplicationSettings(context: Context): Boolean {
        val candidates = mutableListOf<Intent>()
        candidates.add(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        candidates.add(Intent("android.settings.APPLICATION_SETTINGS"))
        candidates.add(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$ManageApplicationsActivity")))
        candidates.add(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$AppAndNotificationDashboardActivity")))
        candidates.add(Intent().setComponent(ComponentName("com.oplus.settings", "com.oplus.settings.Settings\$AppAndNotificationDashboardActivity")))
        candidates.add(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.applications.ManageApplications")))
        candidates.add(Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS))
        candidates.add(Intent(Settings.ACTION_SETTINGS))

        for (intent in candidates) {
            if (startActivitySafely(context, intent)) return true
        }
        return false
    }

    // 兼容旧接口命名
    fun openVivoAutostart(context: Context): Boolean = openAutostartSettings(context)

    // ─── 5. 全品牌电池省电策略 / 后台耗电 / 睡眠优化直跳 ───
    fun openBatteryKeepAliveSettings(context: Context): Boolean {
        val candidates = mutableListOf<Intent>()

        when {
            isXiaomiOrRedmi() -> {
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
                // 核心铁律：宁愿多点两下，也不要跳错页面！
                // OPPO ColorOS 的「耗电行为控制」位于「应用详情 → 耗电管理」内。
                // 绝对不硬猜私有 Activity，100% 稳妥直达「应用详情页」，用户点击「耗电管理」即可勾选「完全允许后台行为」。
                return openAppDetails(context)
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
            if (startActivitySafely(context, intent)) return true
        }

        // 兜底：先尝试标准忽略电池优化请求弹窗，若失败再进 App 详情页
        if (openBatteryOptimization(context)) return true
        return openAppDetails(context)
    }

    // 兼容旧接口命名
    fun openVivoHighPowerConsumption(context: Context): Boolean = openBatteryKeepAliveSettings(context)

    // ─── 6. 全品牌系统级「睡眠待机优化 / 省电管理」直跳（对睡眠的优化，区别于单个应用的后台高耗电） ───
    fun openSleepOptimizationSettings(context: Context): Boolean {
        val candidates = mutableListOf<Intent>()

        when {
            isVivoOrIqoo() -> {
                // vivo/iQOO 系统级省电管理（路径：设置 -> 电池 -> 省电模式 -> 省电管理 -> 睡眠待机优化）
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.UpSlidePowerManagerActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerManagerSettingsActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerDirectorManagerActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.fuelgauge.PowerUsageSummaryActivity")))
                candidates.add(Intent("android.settings.BATTERY_SAVER_SETTINGS"))
                candidates.add(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            }
            isOppoOrOnePlusOrRealme() -> {
                // OPPO ColorOS 省电与电池设置
                candidates.add(Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.BatteryMainActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.oplus.battery", "com.oplus.battery.batteryoptimization.BatteryOptimizationActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaard.PowerSaverModeActivity")))
                candidates.add(Intent().setComponent(ComponentName("com.coloros.powermanager", "com.coloros.powermanager.fuelgaard.PowerSaverModeActivity")))
                candidates.add(Intent("android.settings.BATTERY_SAVER_SETTINGS"))
                candidates.add(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            }
            isXiaomiOrRedmi() -> {
                candidates.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerCenter")))
                candidates.add(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            }
            isHuaweiOrHonor() -> {
                candidates.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity")))
                candidates.add(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            }
        }

        // 系统通用标准省电与电池用量页面
        candidates.add(Intent("android.settings.BATTERY_SAVER_SETTINGS"))
        candidates.add(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        candidates.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

        for (intent in candidates) {
            if (startActivitySafely(context, intent)) {
                Log.i(TAG, "openSleepOptimizationSettings: successfully jumped via $intent")
                return true
            }
        }

        // 兜底：尝试标准电池优化页面，最后回退至应用详情
        if (openBatteryOptimization(context)) return true
        return openAppDetails(context)
    }

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
