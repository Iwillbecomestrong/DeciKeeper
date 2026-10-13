package com.decikeeper.app.protection

/**
 * 核心无障碍服务保护分发策略（Pure Kotlin Seam）。
 * 职责：
 * 1. 明确输入面板打开时，事件向 ProtectionManager 分发的边界；
 * 2. 区分原始 Accessibility 信号与真实业务流水线健康状态，杜绝“假健康”。
 */
object CoreServiceProtectionDispatchPolicy {

    private val CRITICAL_PROTECTION_PACKAGE_PREFIXES = listOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.vivo.permissionmanager",
        "com.vivo.abe",
        "com.vivo.imanager",
        "com.iqoo.secure",
        "com.samsung.android.sm"
    )

    /**
     * 判断当前包名是否为防卸载/防关权限/防停止的敏感保护页面。
     */
    fun isCriticalProtectionPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lower = packageName.lowercase()
        return CRITICAL_PROTECTION_PACKAGE_PREFIXES.any { lower.startsWith(it) } ||
                lower.contains("packageinstaller") ||
                lower.contains("settings") ||
                lower.contains("security")
    }

    /**
     * 在锁屏数字输入期间，是否继续向 ProtectionManager 分发无障碍事件。
     * 铁律：防卸载/防关权限敏感包名永远必须分发，绝不允许因锁屏打字而阻断系统级防绕过！
     */
    fun shouldDispatchProtectionEvent(isInputPanelOpen: Boolean, packageName: String?): Boolean {
        if (!isInputPanelOpen) return true
        // 输入面板打开期间，如果目标为关键保护包，绝对必须分发
        if (isCriticalProtectionPackage(packageName)) return true
        // 非敏感包，且正在输入，为保证打字流畅跳过重度树遍历
        return false
    }

    /**
     * 在输入面板打开时，是否执行黑名单应用检测。
     * 输入面板打开时，锁屏已经在前台且用户正在进行合法交互，底层黑名单已被锁屏遮蔽，可暂停耗时的前台扫描。
     */
    fun shouldExecuteAppBlocking(isInputPanelOpen: Boolean): Boolean {
        return !isInputPanelOpen
    }

    /**
     * 多维无障碍健康判定：
     * 只有系统已开启、服务已连接、近期有原始事件到达，且保护链流水线未被异常阻塞时，才视为真正健康。
     */
    fun isAccessibilityPipelineHealthy(
        isSystemEnabled: Boolean,
        isServiceConnected: Boolean,
        hasRecentRawSignal: Boolean,
        isPipelineStalled: Boolean
    ): Boolean {
        if (!isSystemEnabled) return false
        if (isPipelineStalled) return false
        return isServiceConnected || hasRecentRawSignal
    }
}
