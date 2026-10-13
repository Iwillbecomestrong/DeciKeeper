package com.decikeeper.app.logging

/**
 * 日志存储等级。C 级不落盘，因此没有对应枚举值。
 *
 * - [A]：永久账本，独立配额，不被高频日志冲刷。
 * - [B]：近期诊断，分片滚动，写满丢最老整片。
 */
enum class LogTier(val marker: Char) {
    A('A'),
    B('B')
}

/** 日志模块，对应 docs/plans/2026-10-10-log-system-redesign.md §5。 */
enum class LogModule(val code: String) {
    RULE_CONFIG("RULE.CONFIG"),
    RULE_QUOTA("RULE.QUOTA"),
    FOCUS_DETECT("FOCUS.DETECT"),
    FOCUS_LOCK("FOCUS.LOCK"),
    ALARM_SCHEDULE("ALARM.SCHEDULE"),
    ALARM_RING("ALARM.RING"),
    ALARM_CHALLENGE("ALARM.CHALLENGE"),
    PROTECT("PROTECT"),
    SYS("SYS")
}

/** 一条日志归属：写入哪一层、属于哪个模块。 */
data class LogRoute(val tier: LogTier, val module: LogModule)
