package com.decikeeper.app.manager.expiry

import android.content.Context
import com.decikeeper.app.data.SegmentBlacklist
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.service.CoreService
import com.decikeeper.app.ui.LockWindowManager

/**
 * 可见容器解析器 (VisibleContainerResolver)
 *
 * 负责在到期时收集当前屏幕上所有的可见应用容器（前台应用 + 小窗/浮窗）：
 * - 有无障碍服务时：整合 CoreService 的 Window snapshot 与前台包名；
 * - 无无障碍服务时：仅提取当前确信的前台应用，绝不猜测小窗或 PIP。
 */
object VisibleContainerResolver {

    fun resolveContext(
        context: Context,
        reason: ExpiryReason,
        overrideForegroundPackage: String? = null
    ): ExpiryContext {
        val a11yActive = CoreService.isServiceActive
        val selfPkg = context.packageName

        // 1. 获取当前前台应用
        val fgPkg = overrideForegroundPackage
            ?: LockWindowManager.getEffectiveForegroundPkg(context)
            ?: LockWindowManager.getLastKnownForegroundPkg()

        val isFgLauncher = fgPkg != null && CreditManager.isLauncher(fgPkg)

        // 2. 获取最近历史（按从最新到最旧的逆序排列）
        val recentRecords = LockWindowManager.getRecentForegroundRecords()
        val recentPackages = recentRecords.reversed().map { it.pkg }

        val visibleBlacklist = mutableListOf<String>()
        val visibleWhitelist = mutableListOf<String>()

        // 3. 收集可见容器
        if (a11yActive) {
            // 有无障碍：从 Window 树与前台事件融合 Snapshot 中提取所有可见窗口
            val snapshot = CoreService.captureWindowSnapshot()
            val candidatePkgs = mutableSetOf<String>()

            // 加入前台包名
            if (fgPkg != null && fgPkg != selfPkg) {
                candidatePkgs.add(fgPkg)
            }
            // 加入可见窗口包名
            snapshot.packages.forEach { pkg ->
                if (pkg != selfPkg) {
                    candidatePkgs.add(pkg)
                }
            }

            // 分流黑白名单
            for (pkg in candidatePkgs) {
                val isLauncher = CreditManager.isLauncher(pkg)
                val shouldLock = SegmentBlacklist.shouldLock(context, pkg)
                if (shouldLock) {
                    if (!visibleBlacklist.contains(pkg)) {
                        visibleBlacklist.add(pkg)
                    }
                } else if (!isLauncher) {
                    if (!visibleWhitelist.contains(pkg)) {
                        visibleWhitelist.add(pkg)
                    }
                }
            }
        } else {
            // 无无障碍：只能读取当前明确的前台应用，不能猜测小窗/PIP/后台
            if (fgPkg != null && fgPkg != selfPkg) {
                val isLauncher = CreditManager.isLauncher(fgPkg)
                val shouldLock = SegmentBlacklist.shouldLock(context, fgPkg)
                if (shouldLock) {
                    visibleBlacklist.add(fgPkg)
                } else if (!isLauncher) {
                    visibleWhitelist.add(fgPkg)
                }
            }
        }

        return ExpiryContext(
            reason = reason,
            foregroundPackage = fgPkg,
            recentForegroundPackages = recentPackages,
            accessibilityEnabled = a11yActive,
            visibleBlacklistPackages = visibleBlacklist,
            visibleWhitelistPackages = visibleWhitelist,
            isLauncher = isFgLauncher
        )
    }
}
