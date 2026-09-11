package com.decikeeper.app.manager.expiry

/**
 * 临时放行（Free Time / Temporary Access / Privilege）到期后的决策结果。
 */
enum class ExpiryDecision {
    LOCK,
    UNLOCK,
    WAIT_DESKTOP_CONFIRM
}

/**
 * 临时放行到期原因。
 */
enum class ExpiryReason {
    FREE_TIME_END,
    TEMP_ACCESS_END,
    PRIVILEGE_END
}

/**
 * 到期评估上下文输入模型。
 */
data class ExpiryContext(
    val reason: ExpiryReason,
    // 当前前台包名
    val foregroundPackage: String?,
    // 最近历史包名（从近到远）
    val recentForegroundPackages: List<String>,
    // 是否开启无障碍
    val accessibilityEnabled: Boolean,
    // 当前可见黑名单窗口
    val visibleBlacklistPackages: List<String>,
    // 当前可见白名单窗口
    val visibleWhitelistPackages: List<String>,
    // 当前是否Launcher
    val isLauncher: Boolean
)
