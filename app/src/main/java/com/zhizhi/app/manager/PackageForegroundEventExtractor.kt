package com.zhizhi.app.manager

/**
 * 纯数据模型：表示单个应用包名提取出的最新前台与后台事件时间戳。
 */
data class PackageForegroundEvents(
    val lastForegroundAtMs: Long = 0L,
    val lastBackgroundAtMs: Long = 0L
)

/**
 * 纯数据模型：解耦 Android UsageEvents.Event 的轻量数据载体，便于纯单元测试。
 */
data class RawUsageEvent(
    val packageName: String,
    val eventType: Int,
    val timeStamp: Long
)

/**
 * 纯事件提取器：按包维度精确提取 FG/BG 事件，杜绝跨包干扰。
 */
object PackageForegroundEventExtractor {
    const val MOVE_TO_FG = 1
    const val MOVE_TO_BG = 2
    const val ACTIVITY_RESUMED = 15
    const val ACTIVITY_PAUSED = 16
    const val ACTIVITY_STOPPED = 23

    fun extract(
        events: Iterable<RawUsageEvent>,
        targetPackage: String
    ): PackageForegroundEvents {
        var lastFg = 0L
        var lastBg = 0L
        for (event in events) {
            if (event.packageName != targetPackage) continue
            val type = event.eventType
            if (type == MOVE_TO_FG || type == ACTIVITY_RESUMED) {
                lastFg = event.timeStamp
            } else if (type == MOVE_TO_BG || type == ACTIVITY_PAUSED || type == ACTIVITY_STOPPED) {
                lastBg = event.timeStamp
            }
        }
        return PackageForegroundEvents(
            lastForegroundAtMs = lastFg,
            lastBackgroundAtMs = lastBg
        )
    }
}
