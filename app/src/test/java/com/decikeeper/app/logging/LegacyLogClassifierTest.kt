package com.decikeeper.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyLogClassifierTest {

    private fun keep(tag: String, message: String): LogRoute {
        val decision = LegacyLogClassifier.classify(tag, message)
        assertTrue("$tag: $message 应当保留", decision is LegacyLogDecision.Keep)
        return (decision as LegacyLogDecision.Keep).route
    }

    @Test
    fun retiredLabelsAreDroppedAtWriteTime() {
        listOf(
            "MonitorService" to "[DETECT] Blacklist app: pkg=com.x",
            "MonitorService" to "[VIS_CHECK_DIAG] pkg=com.x visible=true",
            "LockScreen" to "[AUTO_RELEASE] restrictions cleared for pkg=com.x",
            "AlarmWindowManager" to "ring show=true",
            "CoreService" to "onServiceConnected",
            "Penalty" to "[STEP1] foo"
        ).forEach { (tag, message) ->
            assertEquals(
                "$tag: $message",
                LegacyLogDecision.Drop,
                LegacyLogClassifier.classify(tag, message)
            )
        }
    }

    @Test
    fun lowVolumeKeyTagsGoToPermanentTier() {
        assertEquals(LogRoute(LogTier.A, LogModule.ALARM_SCHEDULE), keep("AlarmRecovery", "setAlarmActive id=1"))
        assertEquals(LogRoute(LogTier.A, LogModule.ALARM_SCHEDULE), keep("AlarmReceiver", "[ALARM_TRIGGER_ACCEPTED]"))
        assertEquals(LogRoute(LogTier.A, LogModule.PROTECT), keep("A11ySelfDisableAuth", "[CLEAR] reason=permission_restored"))
        assertEquals(LogRoute(LogTier.A, LogModule.PROTECT), keep("PermissionManager", "[OK] Permission restored"))
        assertEquals(LogRoute(LogTier.A, LogModule.RULE_QUOTA), keep("CreditManager", "[QUOTA_TRANSACTION] x"))
        assertEquals(LogRoute(LogTier.A, LogModule.RULE_CONFIG), keep("ConfigChange", "x"))
        assertEquals(LogRoute(LogTier.A, LogModule.FOCUS_LOCK), keep("TemporaryAccess", "[EMERGENCY] start"))
        assertEquals(LogRoute(LogTier.A, LogModule.FOCUS_LOCK), keep("LockScreen", "[DEFERRED] reason=x"))
        assertEquals(
            LogRoute(LogTier.A, LogModule.FOCUS_LOCK),
            keep("MonitorService", "[EVENT] type=UNLOCK_RESULT traceId=x")
        )
    }

    @Test
    fun antiUninstallOnlyBlockAndHandleArePermanent() {
        assertEquals(LogTier.A, keep("AntiUninstall", "[BLOCK] pkg=x").tier)
        assertEquals(LogTier.A, keep("AntiUninstall", "[HANDLE] pkg=x").tier)
        assertEquals(LogTier.B, keep("AntiUninstall", "some other message").tier)
    }

    @Test
    fun highFrequencyTagsStayInDiagnosticTier() {
        assertEquals(LogTier.B, keep("AlarmOverlay", "ACTIVITY_ON_CREATE").tier)
        assertEquals(LogTier.B, keep("TemporaryAccess", "[EXPIRY_LOCK] x").tier)
        assertEquals(LogTier.B, keep("TemporaryAccess", "[COOLDOWN_START] x").tier)
        assertEquals(LogTier.B, keep("MonitorService", "[EVENT] type=SUSPECT x").tier)
        assertEquals(LogTier.B, keep("SomethingUnknown", "x").tier)
    }

    @Test
    fun dropPatternListKeepsItsOriginalBreadth() {
        assertTrue(LegacyLogClassifier.dropPatterns.size >= 36)
    }
}
