/**
 * =================================================================================
 * [Context Header]
 * * @file: EventLog.kt
 * * @role: Utility
 * * @description: 统一关键事件日志格式 [EVENT] type=XXX key=value ...
 * * @responsibilities:
 * 1. 提供 8 种 type 的静态日志方法。
 * 2. 三类来源（前台/解锁/决策）的枚举常量。
 * 3. traceId 生成规则。
 * * @context:
 * - 上游: MonitorService, LockWindowManager
 * - 下游: LogExporter.logEvent()
 * * @constraints:
 * 1. 纯日志工具，不包含业务判定逻辑。
 * 2. 所有方法内部调用 LogExporter.logEvent() 写入磁盘。
 * =================================================================================
 */
package com.ironwill.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {

    private const val TAG = "MonitorService"

    // ═══════════════════════════════════════════════════
    // 三类来源枚举常量
    // ═══════════════════════════════════════════════════

    /** 前台判断来源 (selectedForegroundSource) */
    object ForegroundSource {
        const val EVENTS = "EVENTS"
        const val USAGE_STATS = "USAGE_STATS"
        const val A11Y = "A11Y"
        const val CACHE = "CACHE"
        const val HISTORY = "HISTORY"
        const val NONE = "NONE"
    }

    /** 解锁来源 (unlockSource) */
    object UnlockSource {
        const val USER_TEMP_ACCESS = "USER_TEMP_ACCESS"
        const val EMERGENCY_FREE_ACCESS = "EMERGENCY_FREE_ACCESS"
        const val USER_CHALLENGE_SUCCESS = "USER_CHALLENGE_SUCCESS"
        const val FREE_TIME = "FREE_TIME"
        const val ALLOW_RULE = "ALLOW_RULE"
        const val WEEKLY_PRIVILEGE = "WEEKLY_PRIVILEGE"
        const val PRESENCE_STALE = "PRESENCE_STALE"
        const val LAUNCHER_DETECTED = "LAUNCHER_DETECTED"
        const val SESSION_END = "SESSION_END"
        const val NATURAL_FREEDOM = "NATURAL_FREEDOM"
        const val NONE = "NONE"
    }

    /** 锁屏/放行决策原因 (decisionReason) */
    object DecisionReason {
        const val BLACKLIST_DND_LOCKED = "BLACKLIST_DND_LOCKED"
        const val TEMP_ACCESS_ACTIVE = "TEMP_ACCESS_ACTIVE"
        const val EMERGENCY_ACTIVE = "EMERGENCY_ACTIVE"
        const val VIS_GUARD_FAILED = "VIS_GUARD_FAILED"
        const val FG_GUARD_FAILED = "FG_GUARD_FAILED"
        const val APP_WINDOW_BLOCKED = "APP_WINDOW_BLOCKED"
        const val APP_WINDOW_ALLOWED = "APP_WINDOW_ALLOWED"
        const val FREE_TIME_ACTIVE = "FREE_TIME_ACTIVE"
        const val PRIVILEGE_ACTIVE = "PRIVILEGE_ACTIVE"
        const val SAFE_APP = "SAFE_APP"
        const val NOT_BLACKLISTED = "NOT_BLACKLISTED"
        const val NO_PACKAGE_DETECTED = "NO_PACKAGE_DETECTED"
        const val CARRIER_NOISE = "CARRIER_NOISE"
        const val PRESENCE_LOCK = "PRESENCE_LOCK"
        const val FORCE_RELOCK = "FORCE_RELOCK"
        const val UNKNOWN = "UNKNOWN"
    }

    // ═══════════════════════════════════════════════════
    // traceId 生成
    // ═══════════════════════════════════════════════════

    /** 生成 traceId，格式: LOCK-yyyyMMdd-HHmmss-XXXX */
    fun generateTraceId(now: Long, seq: Long): String {
        val dateSdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val timeSdf = SimpleDateFormat("HHmmss", Locale.getDefault())
        return "LOCK-${dateSdf.format(Date(now))}-${timeSdf.format(Date(now))}-${"%04d".format(seq % 10000)}"
    }

    /** 生成前台汇报 traceId，格式: FG-yyyyMMdd-HHmmss-XXXX */
    fun generateForegroundTraceId(now: Long, seq: Long): String {
        val dateSdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val timeSdf = SimpleDateFormat("HHmmss", Locale.getDefault())
        return "FG-${dateSdf.format(Date(now))}-${timeSdf.format(Date(now))}-${"%04d".format(seq % 10000)}"
    }

    // ═══════════════════════════════════════════════════
    // 内部构建方法
    // ═══════════════════════════════════════════════════

    private fun emit(type: String, fields: Map<String, String?>) {
        val parts = mutableListOf("type=$type")
        for ((k, v) in fields) {
            if (!v.isNullOrBlank()) {
                parts += "$k=${v.replace(" ", "_")}"
            }
        }
        val message = "[EVENT] ${parts.joinToString(" ")}"
        LogExporter.logEvent(TAG, message)
    }

    // ═══════════════════════════════════════════════════
    // 1. APP_STATE
    // ═══════════════════════════════════════════════════

    fun appState(
        traceId: String,
        mode: String,          // FULL / DEGRADED_WITHOUT_ACCESSIBILITY
        a11y: Boolean,
        isDndLocked: Boolean,
        isFreeTime: Boolean,
        isPrivilege: Boolean,
        lockedPkg: String?,
        lastPkg: String?,
        lastSrc: String?
    ) {
        emit("APP_STATE", mapOf(
            "traceId" to traceId,
            "mode" to mode,
            "a11y" to a11y.toString(),
            "isDndLocked" to isDndLocked.toString(),
            "isFreeTime" to isFreeTime.toString(),
            "isPrivilege" to isPrivilege.toString(),
            "lockedPkg" to (lockedPkg ?: "none"),
            "lastPkg" to (lastPkg ?: "none"),
            "lastSrc" to (lastSrc ?: "NONE")
        ))
    }

    // ═══════════════════════════════════════════════════
    // 2. FG_DECISION
    // ═══════════════════════════════════════════════════

    fun fgDecision(
        traceId: String,
        selectedPkg: String?,
        selectedSrc: String?,
        confidence: String,
        conflict: Boolean,
        a11yAvailable: Boolean,
        // evidence: UsageEvents
        eventsPkg: String?,
        eventsAgeMs: Long,
        // evidence: UsageStats
        usmPkg: String?,
        usmAgeMs: Long,
        // evidence: Accessibility
        a11yPkg: String?,
        // evidence: Cache
        cachePkg: String?,
        cacheAgeMs: Long,
        cacheUsedForDecision: Boolean,
        // RB-03 diagnostic fields
        usageEventsStatus: String = "",
        accessibilityWindowsCount: Int = 0,
        conflictReason: String = ""
    ) {
        emit("FG_DECISION", mapOf(
            "traceId" to traceId,
            "selectedPkg" to (selectedPkg ?: "none"),
            "selectedSrc" to (selectedSrc ?: "NONE"),
            "confidence" to confidence,
            "conflict" to conflict.toString(),
            "a11y" to a11yAvailable.toString(),
            "eventsPkg" to (eventsPkg ?: "none"),
            "eventsAgeMs" to eventsAgeMs.toString(),
            "usmPkg" to (usmPkg ?: "none"),
            "usmAgeMs" to usmAgeMs.toString(),
            "a11yAvailable" to a11yAvailable.toString(),
            "a11yPkg" to (a11yPkg ?: "none"),
            "cachePkg" to (cachePkg ?: "none"),
            "cacheAgeMs" to cacheAgeMs.toString(),
            "cacheUsedForDecision" to cacheUsedForDecision.toString(),
            // RB-03 diagnostic fields
            "usageEventsStatus" to usageEventsStatus,
            "accessibilityWindowsCount" to accessibilityWindowsCount.toString(),
            "conflictReason" to conflictReason
        ))
    }

    // ═══════════════════════════════════════════════════
    // 3. VIS_DECISION
    // ═══════════════════════════════════════════════════

    fun visDecision(
        traceId: String,
        pkg: String,
        result: Boolean,
        lastVisibleDeltaMs: Long,
        windowMs: Long,
        a11yWindows: Boolean,
        a11yService: Boolean,
        reason: String   // VISIBLE / VISIBLE_EVIDENCE_STALE / NOT_VISIBLE / A11Y_WINDOW_MATCH / API_TOO_OLD
    ) {
        emit("VIS_DECISION", mapOf(
            "traceId" to traceId,
            "pkg" to pkg,
            "result" to result.toString(),
            "lastVisibleDeltaMs" to lastVisibleDeltaMs.toString(),
            "windowMs" to windowMs.toString(),
            "a11yWindows" to a11yWindows.toString(),
            "a11yService" to a11yService.toString(),
            "reason" to reason
        ))
    }

    // ═══════════════════════════════════════════════════
    // 4. BLOCK_DECISION
    // ═══════════════════════════════════════════════════

    fun blockDecision(
        traceId: String,
        pkg: String,
        decision: String,     // LOCK / PASS / SKIP
        isBlacklisted: Boolean,
        isDndLocked: Boolean,
        isFreeTime: Boolean,
        isManaged: Boolean,
        isAllowed: Boolean,
        guard: Boolean,
        decisionReason: String,
        selectedForegroundSource: String,
        unlockSource: String
    ) {
        emit("BLOCK_DECISION", mapOf(
            "traceId" to traceId,
            "pkg" to pkg,
            "decision" to decision,
            "isBlacklisted" to isBlacklisted.toString(),
            "isDndLocked" to isDndLocked.toString(),
            "isFreeTime" to isFreeTime.toString(),
            "isManaged" to isManaged.toString(),
            "isAllowed" to isAllowed.toString(),
            "guard" to guard.toString(),
            "decisionReason" to decisionReason,
            "selectedForegroundSource" to selectedForegroundSource,
            "unlockSource" to unlockSource
        ))
    }

    // ═══════════════════════════════════════════════════
    // 5. LOCK_RESULT
    // ═══════════════════════════════════════════════════

    fun lockResult(
        traceId: String,
        pkg: String,
        label: String,
        result: String,          // LOCKED
        reason: String,
        selectedSrc: String,
        quotaRemaining: String,  // e.g. "72.5min/25"
        detectionSource: String?
    ) {
        emit("LOCK_RESULT", mapOf(
            "traceId" to traceId,
            "pkg" to pkg,
            "label" to label,
            "result" to result,
            "reason" to reason,
            "selectedSrc" to selectedSrc,
            "quotaRemaining" to quotaRemaining,
            "detectionSource" to (detectionSource ?: "NONE")
        ))
    }

    // ═══════════════════════════════════════════════════
    // 6. UNLOCK_RESULT
    // ═══════════════════════════════════════════════════

    fun unlockResult(
        traceId: String,
        pkg: String?,
        unlockType: String,     // TEMP / EMERGENCY / CHALLENGE / FREE_TIME / REST / PRIVILEGE
        source: String,         // USER_TEMP_ACCESS / EMERGENCY_FREE_ACCESS / USER_CHALLENGE_SUCCESS / etc.
        durationSec: Long,
        endTime: String?,       // HH:mm:ss format
        quotaConsumeSec: Long,
        goHome: Boolean,
        reason: String?
    ) {
        val fields = mutableMapOf(
            "traceId" to traceId,
            "pkg" to (pkg ?: "none"),
            "unlockType" to unlockType,
            "unlockSource" to source,
            "durationSec" to durationSec.toString()
        )
        if (!endTime.isNullOrBlank()) fields["endTime"] = endTime
        if (quotaConsumeSec > 0) fields["quotaConsumeSec"] = quotaConsumeSec.toString()
        if (goHome) fields["goHome"] = "true"
        if (!reason.isNullOrBlank()) fields["reason"] = reason
        emit("UNLOCK_RESULT", fields)
    }

    // ═══════════════════════════════════════════════════
    // 7. NAVIGATION
    // ═══════════════════════════════════════════════════

    fun navigation(
        traceId: String,
        action: String,         // GO_HOME / ENTER_APP / LAUNCH_SETTINGS
        from: String?,
        to: String?,
        source: String?
    ) {
        emit("NAVIGATION", mapOf(
            "traceId" to traceId,
            "action" to action,
            "from" to (from ?: "unknown"),
            "to" to (to ?: "unknown"),
            "source" to (source ?: "unknown")
        ))
    }

    // ═══════════════════════════════════════════════════
    // 8. SUSPECT
    // ═══════════════════════════════════════════════════

    fun suspect(
        traceId: String,
        pkg: String,
        issue: String,          // NO_A11Y_STATIC_FOREGROUND_BYPASS / VIS_EVIDENCE_STALE / FG_GUARD_REJECTED / etc.
        a11y: Boolean,
        guard: Boolean,
        vis: Boolean,
        durationMs: Long,
        reason: String?
    ) {
        emit("SUSPECT", mapOf(
            "traceId" to traceId,
            "pkg" to pkg,
            "issue" to issue,
            "a11y" to a11y.toString(),
            "guard" to guard.toString(),
            "vis" to vis.toString(),
            "durationMs" to durationMs.toString(),
            "reason" to (reason ?: "unknown")
        ))
    }
}
