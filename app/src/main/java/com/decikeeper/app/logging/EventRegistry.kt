package com.decikeeper.app.logging

/**
 * 结构化事件类型 → 模块与存储等级的登记表。模块归属不逐行写入日志，导出和筛选时查表。
 *
 * 未登记的事件类型按 B 级、[LogModule.SYS] 处理：不会冲刷永久账本，同时由治理测试
 * 保证现有事件类型都已登记。
 */
object EventRegistry {

    private val routes: Map<String, LogRoute> = buildMap {
        fun a(module: LogModule, vararg types: String) =
            types.forEach { put(it, LogRoute(LogTier.A, module)) }

        fun b(module: LogModule, vararg types: String) =
            types.forEach { put(it, LogRoute(LogTier.B, module)) }

        a(LogModule.RULE_CONFIG, "CONFIG_CHANGE", "RULE_EFFECTIVE")
        a(
            LogModule.RULE_QUOTA,
            "REST_QUOTA_CHANGE", "REST_COUNT_CHANGE", "PENALTY", "BORROW", "REFUND",
            "CARRYOVER_CREATED", "CARRYOVER_USED", "PRIVILEGE_QUOTA_CHANGE"
        )
        b(LogModule.FOCUS_DETECT, "FG_DECISION", "FG_END", "FOREGROUND_TRANSITION")
        a(
            LogModule.FOCUS_LOCK,
            "LOCK_RESULT", "LOCK_EXIT",
            "TEMP_ACCESS_REQUEST", "TEMP_ACCESS_START", "TEMP_ACCESS_END", "TEMP_ACCESS_COOLDOWN_END",
            "COOLDOWN_END",
            "EMERGENCY_ACCESS_START", "EMERGENCY_ACCESS_END",
            "ALLOW_START", "ALLOW_END",
            "DESKTOP_TIMER_START", "DESKTOP_TIMER_END",
            "PRIVILEGE_REQUEST", "PRIVILEGE_APPROVED", "PRIVILEGE_REJECTED", "PRIVILEGE_START", "PRIVILEGE_END",
            "REST_REQUEST"
        )
        b(LogModule.FOCUS_LOCK, "DESKTOP_TIMER_TICK")
        a(LogModule.ALARM_SCHEDULE, "ALARM_SCHEDULE", "ALARM_TRIGGER", "ALARM_RECOVERY", "ALARM_WATCHDOG")
        a(LogModule.ALARM_RING, "ALARM_RING_START", "ALARM_RING_END")
        a(
            LogModule.ALARM_CHALLENGE,
            "ALARM_CHALLENGE_START", "ALARM_CHALLENGE_RESULT",
            "ALARM_OVERLAY_SHOW", "ALARM_OVERLAY_HIDE", "ALARM_DISMISS", "ALARM_SNOOZE"
        )
        a(LogModule.PROTECT, "PROTECTION_STATE_CHANGE", "SERVICE_STATE", "BYPASS_RISK")
    }

    private val fallback = LogRoute(LogTier.B, LogModule.SYS)

    /** 已登记的全部事件类型。 */
    val registeredTypes: Set<String> get() = routes.keys

    fun isRegistered(type: String): Boolean = routes.containsKey(type)

    fun route(type: String): LogRoute = routes[type] ?: fallback
}
