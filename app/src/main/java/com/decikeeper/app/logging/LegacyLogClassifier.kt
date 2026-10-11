package com.decikeeper.app.logging

/** 旧式字符串日志（`LogExporter.logEvent(tag, message)`）的写入时分流结果。 */
sealed class LegacyLogDecision {
    /** C 级：不落盘。 */
    object Drop : LegacyLogDecision()

    data class Keep(val route: LogRoute) : LegacyLogDecision()
}

/**
 * 在旧调用点尚未迁移到统一门面之前，按 tag 和消息前缀在**写入时**决定日志去向：
 * 命中 [dropPatterns] 的丢弃（C 级）；已知的低频关键 tag 进永久账本（A 级）；其余进近期诊断（B 级）。
 *
 * 全量迁移完成后，旧调用点会被门面取代，本分类器连同 [dropPatterns] 一并退役。
 */
object LegacyLogClassifier {

    /**
     * 已退役或已被结构化事件取代的旧标签。匹配对象是 `"$tag: $message"`，
     * 与导出时看到的行文本一致，因此语义和原 exportDropPatterns 相同。
     */
    val dropPatterns: List<Regex> = listOf(
        // Task 1 — foreground polling/raw retired sites
        Regex("""\[DETECT\]"""),
        Regex("""\[MINI_WINDOW_FALLBACK\]"""),
        Regex("""\[EVENT\] type=APP_STATE"""),
        Regex("""FG pkg="""),
        Regex("""FG_SUMMARY"""),
        Regex("""\[EVENT\] type=NAVIGATION"""),
        Regex("""\[FG_BYPASS_DIAG\]"""),
        Regex("""\[SHOULD_BLOCK_DIAG\]"""),
        Regex("""\[EVENT\] type=BLOCK_DECISION"""),
        Regex("""\[FG_GUARD_DIAG\]"""),
        Regex("""\[VIS_CHECK_DIAG\]"""),
        Regex("""\[EVENT\] type=VIS_DECISION"""),
        // Task 2 — MonitorService seven-line evidence block + `[锁机]` + `[RESTORE_REQUEST]`
        Regex("""前台结论:"""),
        Regex("""^[^|]*Accessibility:\s+(?:可用|不可用)"""),
        Regex("""UsageEvents:\s+#1"""),
        Regex("""UsageStats:\s+#1"""),
        Regex("""内部状态:"""),
        Regex("""判断理由:"""),
        Regex("""锁机上下文:"""),
        Regex("""\[锁机\]"""),
        Regex("""\[RESTORE_REQUEST\]"""),
        Regex("""\[AUTO_RELEASE\]"""),
        // Task 3 — lock/temp lifecycle duplicates
        Regex("""LockScreen: 解锁:"""),
        Regex("""LockScreen: 🏠 回到桌面:"""),
        Regex("""TempAccess: 临时解锁:"""),
        // Task 4 — quota UI ticks + consumption/penalty step traces + daily status header
        Regex("""\[BORROW_UI_DEBUG\]"""),
        Regex("""\[TIME_CONSUME\]"""),
        Regex("""\[STEP1\]"""),
        Regex("""\[STEP2\]"""),
        Regex("""\[STEP3\]"""),
        Regex("""\[DONE\] penalty applied"""),
        Regex("""══════ 每日状态播报 ══════"""),
        // Task 5 — alarm/protection raw lifecycle + heartbeats + service lifecycle
        Regex("""💓 心跳"""),
        Regex("""AlarmWindowManager: ring show=true"""),
        Regex("""LockWindowManager: snooze action=\S+"""),
        Regex("""CoreService: onServiceConnected"""),
        Regex("""CoreService: onUnbind"""),
        Regex("""CoreService: onRebind"""),
        // Pre-existing Task 1/3 drops retained for completeness
        Regex("""TemporaryAccess: \[FINAL_RELOCK_SUCCEEDED] cleared flag"""),
        Regex("""LockScreen: \[REASSERT]"""),
        // 已删除的前台诊断器与事件转储（旧版本升级后残留在旧日志文件里）
        Regex("""\[FG_RAW_"""),
        Regex("""\[FG_RESOLVE_TRACE]"""),
        Regex("""\[PRESENCE_EVENT]"""),
        Regex("""AntiUninstall: \[Event]""")
    )

    private val alarmScheduleTags = setOf(
        "AlarmRecovery", "AlarmReceiver", "AlarmScheduler", "Alarm",
        "AlarmGuardian", "AlarmWatchdog", "AlarmEmergencyGuard"
    )
    private val quotaTags = setOf("CreditManager", "Penalty", "Privilege", "QuotaGrant", "Rest")
    private val configTags = setOf("Config", "ConfigChange", "Import", "TimeRuleManager")
    private val protectTags = setOf("A11ySelfDisableAuth", "PermissionManager", "AntiUninstall")
    private val focusLockTags = setOf("TemporaryAccess", "LockScreen", "DesktopTimer", "Timer", "AppWindowManager", "AutoBlacklist")

    fun classify(tag: String, message: String): LegacyLogDecision {
        val line = "$tag: $message"
        if (dropPatterns.any { it.containsMatchIn(line) }) return LegacyLogDecision.Drop
        return LegacyLogDecision.Keep(route(tag, message))
    }

    private fun route(tag: String, message: String): LogRoute = when {
        tag == ProcessStartLine.TAG -> LogRoute(LogTier.A, LogModule.SYS)
        tag in alarmScheduleTags -> LogRoute(LogTier.A, LogModule.ALARM_SCHEDULE)
        tag == "AlarmOverlay" -> LogRoute(LogTier.B, LogModule.ALARM_CHALLENGE)
        tag == "AntiUninstall" ->
            if (message.startsWith("[BLOCK]") || message.startsWith("[HANDLE]")) {
                LogRoute(LogTier.A, LogModule.PROTECT)
            } else {
                LogRoute(LogTier.B, LogModule.PROTECT)
            }
        tag in protectTags -> LogRoute(LogTier.A, LogModule.PROTECT)
        tag in quotaTags -> LogRoute(LogTier.A, LogModule.RULE_QUOTA)
        tag in configTags -> LogRoute(LogTier.A, LogModule.RULE_CONFIG)
        tag == "TemporaryAccess" && message.startsWith("[EMERGENCY") ->
            LogRoute(LogTier.A, LogModule.FOCUS_LOCK)
        tag == "LockScreen" && message.startsWith("[DEFERRED]") ->
            LogRoute(LogTier.A, LogModule.FOCUS_LOCK)
        tag in focusLockTags -> LogRoute(LogTier.B, LogModule.FOCUS_LOCK)
        tag == "MonitorService" && message.startsWith("[EVENT] type=UNLOCK_RESULT") ->
            LogRoute(LogTier.A, LogModule.FOCUS_LOCK)
        tag == "MonitorService" -> LogRoute(LogTier.B, LogModule.FOCUS_DETECT)
        else -> LogRoute(LogTier.B, LogModule.SYS)
    }
}
