package com.decikeeper.app.service

/**
 * 把「无障碍可见包集合」和「已关闭墓碑」渲染成日志里的一个无空格片段，
 * 用来事后判断：小窗仍在屏幕上却没被锁，是集合里没有它，还是它被墓碑标成了已关闭。
 *
 * 只做诊断，不参与任何锁屏判定。
 */
object VisiblePackagesDiagnostic {
    const val SRC_NONE = "NONE"

    /** 全部窗口快照 + 近 3 秒无障碍事件融合（`CoreService.captureAccessibilityWindowSnapshot`）。 */
    const val SRC_ALL_WINDOWS_FUSED = "ALL_WINDOWS_FUSED"

    /** 事件内窗口扫描，只收「活动且有焦点」的窗口。 */
    const val SRC_ACTIVE_FOCUSED_SCAN = "ACTIVE_FOCUSED_SCAN"

    private const val MAX_PACKAGES = 8

    /** 由 MonitorService 登记：返回 Presence 追踪器里当前处于已关闭墓碑的包。 */
    @Volatile
    var closedPackagesProvider: (() -> Set<String>)? = null

    fun render(visible: Set<String>, closed: Set<String>, source: String, ageMs: Long): String {
        val shown = visible.sorted().take(MAX_PACKAGES)
            .joinToString(",") { if (it in closed) "$it(CLOSED)" else it }
            .ifEmpty { "NONE" }
        val hidden = (visible.size - MAX_PACKAGES).coerceAtLeast(0)
        val more = if (hidden > 0) ",+$hidden" else ""
        val tombstones = closed.sorted().take(MAX_PACKAGES).joinToString(",").ifEmpty { "NONE" }
        return "visible:$shown$more;src:$source;ageMs:$ageMs;closed:$tombstones"
    }

    fun current(nowMs: Long = System.currentTimeMillis()): String {
        val closed = runCatching { closedPackagesProvider?.invoke() }.getOrNull() ?: emptySet()
        val atMs = CoreService.lastVisiblePackagesAtMs
        val ageMs = if (atMs > 0L) (nowMs - atMs).coerceAtLeast(0L) else -1L
        return render(
            visible = CoreService.lastVisiblePackagesFromWindows,
            closed = closed,
            source = CoreService.lastVisiblePackagesSource,
            ageMs = ageMs
        )
    }
}
