package com.decikeeper.app.logging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventRegistryTest {

    @Test
    fun foregroundDetectionEventsAreDiagnosticTier() {
        listOf("FG_DECISION", "FG_END", "FOREGROUND_TRANSITION").forEach { type ->
            val route = EventRegistry.route(type)
            assertEquals(type, LogTier.B, route.tier)
            assertEquals(type, LogModule.FOCUS_DETECT, route.module)
        }
    }

    @Test
    fun foregroundDecisionIsPermanentOnlyWhenMarkedKeepLong() {
        assertEquals(LogTier.B, EventRegistry.route("FG_DECISION", emptyMap()).tier)
        assertEquals(LogTier.B, EventRegistry.route("FG_DECISION", mapOf("keep" to "SHORT")).tier)
        assertEquals(LogTier.B, EventRegistry.route("FG_DECISION", mapOf("detail" to "COMPACT")).tier)
        val long = EventRegistry.route("FG_DECISION", mapOf("keep" to "LONG"))
        assertEquals(LogTier.A, long.tier)
        assertEquals(LogModule.FOCUS_DETECT, long.module)
    }

    @Test
    fun keepFieldOnlyAffectsForegroundDecision() {
        assertEquals(LogTier.B, EventRegistry.route("FG_END", mapOf("keep" to "LONG")).tier)
        assertEquals(LogTier.A, EventRegistry.route("LOCK_RESULT", mapOf("keep" to "SHORT")).tier)
    }

    @Test
    fun stateTransitionAndLedgerEventsArePermanent() {
        mapOf(
            "LOCK_RESULT" to LogModule.FOCUS_LOCK,
            "TEMP_ACCESS_START" to LogModule.FOCUS_LOCK,
            "DESKTOP_TIMER_START" to LogModule.FOCUS_LOCK,
            "PENALTY" to LogModule.RULE_QUOTA,
            "CONFIG_CHANGE" to LogModule.RULE_CONFIG,
            "ALARM_TRIGGER" to LogModule.ALARM_SCHEDULE,
            "ALARM_RING_START" to LogModule.ALARM_RING,
            "ALARM_CHALLENGE_RESULT" to LogModule.ALARM_CHALLENGE,
            "PROTECTION_STATE_CHANGE" to LogModule.PROTECT
        ).forEach { (type, module) ->
            val route = EventRegistry.route(type)
            assertEquals(type, LogTier.A, route.tier)
            assertEquals(type, module, route.module)
        }
    }

    @Test
    fun desktopTimerTickDoesNotEnterPermanentLedger() {
        assertEquals(LogTier.B, EventRegistry.route("DESKTOP_TIMER_TICK").tier)
    }

    @Test
    fun unregisteredTypeFallsBackToDiagnosticTier() {
        assertFalse(EventRegistry.isRegistered("SOMETHING_NEW"))
        assertEquals(LogTier.B, EventRegistry.route("SOMETHING_NEW").tier)
    }

    /**
     * 治理：生产代码里以字面量声明的每个结构化事件类型都必须登记，
     * 否则新事件会悄悄落入 B 级并在模块筛选里归到 SYS。
     */
    @Test
    fun everyEventTypeDeclaredInProductionSourceIsRegistered() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java") }
            .firstOrNull { it.isDirectory }
            ?: error("找不到 app/src/main/java")

        val patterns = listOf(
            Regex("""\bevent\(\s*"([A-Z][A-Z_]+)""""),
            Regex("""NormalEvents\.create\(\s*(?:type\s*=\s*)?"([A-Z][A-Z_]+)""""),
            Regex("""(?:type|eventType)\s*=\s*"([A-Z][A-Z_]{3,})"""")
        )
        val declared = sortedSetOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            patterns.forEach { pattern ->
                pattern.findAll(text).forEach { declared += it.groupValues[1] }
            }
        }

        assertTrue("应当扫描到事件类型", declared.size > 30)
        val missing = declared.filterNot { EventRegistry.isRegistered(it) }
        assertTrue("以下事件类型未在 EventRegistry 登记: $missing", missing.isEmpty())
    }
}
