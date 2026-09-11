package com.decikeeper.app.manager.expiry

import android.util.Log
import com.decikeeper.app.data.LogExporter

/**
 * 统一到期决策解析器 (Pure Kotlin Seam)
 *
 * 遵循设计原则：
 * 原则 A：到期不是立即锁，而是重新评估；
 * 原则 B：黑名单拥有最高否决权（所有可见容器中只要存在黑名单就 LOCK）；
 * 原则 C：白名单只能在没有黑名单时生效（白名单不覆盖黑名单）；
 * 原则 D：桌面与未知进入 WAIT_DESKTOP_CONFIRM，超时由 History fallback 裁决。
 */
object ExpiryResolver {

    private const val TAG = "ExpiryResolver"

    /**
     * 对当前到期上下文进行纯策略判定。
     *
     * @return Pair<ExpiryDecision, String> 决策及决策成因 (cause)，用于可观测日志。
     */
    fun resolve(context: ExpiryContext): Pair<ExpiryDecision, String> {
        // Step 1: 检测黑名单（前台黑名单 或 小窗黑名单）
        if (context.visibleBlacklistPackages.isNotEmpty()) {
            val cause = "VISIBLE_BLACKLIST: ${context.visibleBlacklistPackages}"
            return ExpiryDecision.LOCK to cause
        }

        // Step 2: 检测明确白名单
        // 没有黑名单，且当前前台明确是非 Launcher 白名单应用
        val fg = context.foregroundPackage
        if (fg != null && !context.isLauncher && context.visibleWhitelistPackages.contains(fg)) {
            val cause = "EXPLICIT_WHITELIST: $fg"
            return ExpiryDecision.UNLOCK to cause
        }

        // Step 3: Launcher / Unknown -> 进入桌面等待确认状态
        val cause = if (context.isLauncher) "DESKTOP_PENDING" else "UNKNOWN_FOREGROUND_PENDING"
        return ExpiryDecision.WAIT_DESKTOP_CONFIRM to cause
    }

    /**
     * Desktop Pending 达到 35 秒后的历史回退判定 (History fallback)。
     *
     * @param recentForegroundPackages 最近前台记录（按从近到远顺序）
     * @param isBlacklist 判断一个包名是否为黑名单
     * @param isLauncher 判断一个包名是否为桌面
     */
    fun resolveHistoryFallback(
        recentForegroundPackages: List<String>,
        isBlacklist: (String) -> Boolean,
        isLauncher: (String) -> Boolean
    ): Pair<ExpiryDecision, String> {
        // 优先获取最近一个有效非 Launcher App
        val historyApp = recentForegroundPackages.firstOrNull { pkg ->
            !isLauncher(pkg)
        }

        return when {
            historyApp != null && isBlacklist(historyApp) -> {
                ExpiryDecision.LOCK to "HISTORY_BLACKLIST: $historyApp"
            }
            historyApp != null -> {
                ExpiryDecision.UNLOCK to "HISTORY_WHITELIST: $historyApp"
            }
            else -> {
                ExpiryDecision.UNLOCK to "HISTORY_EMPTY_FALLBACK_UNLOCK"
            }
        }
    }

    /**
     * 规范化打印结构化诊断日志（符合 SPEC Phase 6 规范）。
     */
    fun logResolution(
        context: ExpiryContext,
        decision: ExpiryDecision,
        cause: String
    ) {
        val logContent = buildString {
            append("[EXPIRY_RESOLVE] ")
            append("reason=${context.reason} ")
            append("foreground=${context.foregroundPackage ?: "unknown"} ")
            append("recent=${context.recentForegroundPackages} ")
            append("accessibility=${context.accessibilityEnabled} ")
            append("visibleBlacklist=${context.visibleBlacklistPackages} ")
            append("visibleWhitelist=${context.visibleWhitelistPackages} ")
            append("decision=$decision ")
            append("cause=$cause")
        }
        Log.i(TAG, logContent)
        LogExporter.logEvent(TAG, logContent)
    }
}
