package com.decikeeper.app.oem

import android.content.Context

/**
 * 厂商/定制 ROM 深度差异化与兼容性策略契约。
 * 每个具体机型（vivo, oppo, default 等）维护独立实现，物理隔离机型特性与跳转细节。
 */
interface OemAdapter {

    /** 品牌/厂商显示名称 */
    val brandName: String

    /** 是否推荐并默认展示设备管理器防卸载锁（当前仅 OPPO/ColorOS 验证推荐） */
    fun isDeviceAdminRecommended(): Boolean

    /** 是否支持并默认展示隐私空间保护（当前仅 vivo 原子隐私系统验证推荐） */
    fun isPrivacySpaceGuardSupported(): Boolean

    /** 「加强约束功能」统一问号弹窗底部机型特有说明资源 ID，无专属说明返回 null */
    fun getProtectionHelpNoteRes(): Int?

    /** 后台耗电管理/行为控制友好展示标签 */
    fun getBatteryOptimizationLabel(): String

    /** 自启动权限友好展示标签 */
    fun getAutostartLabel(): String

    /** 针对该品牌的后台保活与防睡眠冻结提示文案 */
    fun getKeepAliveTips(): String

    /** 针对该品牌的系统级「睡眠待机优化」关闭指引文案 */
    fun getSleepOptimizationTips(): String

    /** 自启动设置跳转 */
    fun openAutostartSettings(context: Context): Boolean

    /** 关联启动设置跳转 */
    fun openAssociateStartSettings(context: Context): Boolean

    /** 后台耗电/完全允许后台行为设置跳转 */
    fun openBatteryKeepAliveSettings(context: Context): Boolean

    /** 睡眠待机优化设置跳转 */
    fun openSleepOptimizationSettings(context: Context): Boolean
}
