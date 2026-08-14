package com.ironwill.app

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED contract for the normal event bridge.
 *
 * These tests deliberately describe the public boundary from the approved
 * event-driven logging plan.  A logger must allocate the envelope, render once,
 * and hand the very same rendered record to both output channels without
 * allowing a logging failure to change the caller's business path.
 */
class NormalEventLogTest {

    @Test
    fun draftsReceiveProcessIdentityAndStrictlyIncreasingSequenceOnEmission() {
        val export = RecordingSink()
        val logger = newLogger(export)
        val clock = FakeClock(wallMs = 1_721_234_567_890L, elapsedMs = 42L)

        logger.emit(NormalEvents.create("CONFIG_CHANGE", clock, linkedMapOf("remainingCount" to "0")))
        logger.emit(NormalEvents.create("LOCK", clock, linkedMapOf("borrowed" to "false")))

        val first = export.records[0].event
        val second = export.records[1].event
        assertTrue(first.processInstanceId.isNotBlank())
        assertEquals(first.processInstanceId, second.processInstanceId)
        assertTrue(first.sequence > 0L)
        assertEquals(first.sequence + 1L, second.sequence)
        assertEquals("${first.processInstanceId}-${first.sequence}", first.eventId)
        assertEquals("${second.processInstanceId}-${second.sequence}", second.eventId)
        assertNotEquals(first.eventId, second.eventId)
        assertEquals(1_721_234_567_890L, first.draft.occurredAtMs)
        assertEquals(42L, first.draft.elapsedRealtimeMs)
    }

    @Test
    fun emitsOneRenderedRecordToBothSinksAndInvokesRendererOnce() {
        val export = RecordingSink()
        val logcat = RecordingSink()
        val renderer = RecordingRenderer()
        val logger = NormalEventLogger(renderer, listOf(export, logcat)) { throw AssertionError(it) }

        logger.emit(NormalEvents.create("TEMP_ACCESS_START", FakeClock(1000L, 50L)))

        assertEquals(1, renderer.renderedEvents.size)
        assertEquals(1, export.records.size)
        assertEquals(1, logcat.records.size)
        assertSame(export.records.single(), logcat.records.single())
        assertEquals(export.records.single().text, logcat.records.single().text)
        assertEquals(export.records.single().event, renderer.renderedEvents.single())
    }

    @Test
    fun logExporterAndAndroidLogcatBridgesReceiveTheSameRenderedRecord() {
        val exportedRecords = Collections.synchronizedList(mutableListOf<RenderedNormalEvent>())
        val logcatRecords = Collections.synchronizedList(mutableListOf<RenderedNormalEvent>())
        val logger = NormalEventLogger(
            renderer = RecordingRenderer(),
            sinks = listOf(
                LogExporter.normalEventSink(NormalEventExportWriter { exportedRecords += it }),
                AndroidLogcatNormalEventSink(NormalEventLogcatWriter { logcatRecords += it })
            ),
            fallbackErrorSink = { throw AssertionError(it) }
        )

        logger.emit(NormalEvents.create("ALARM_TRIGGER", FakeClock(2_000L, 20L)))

        assertEquals(1, exportedRecords.size)
        assertEquals(1, logcatRecords.size)
        assertSame(exportedRecords.single(), logcatRecords.single())
        assertEquals(exportedRecords.single().text, logcatRecords.single().text)
        assertEquals(exportedRecords.single().event.eventId, logcatRecords.single().event.eventId)
    }

    @Test
    fun loggerInstancesShareOneProcessIdentityAndSequence() {
        val firstSink = RecordingSink()
        val secondSink = RecordingSink()
        val firstLogger = newLogger(firstSink)
        val secondLogger = newLogger(secondSink)

        firstLogger.emit(NormalEvents.create("LOCK", FakeClock(4_000L, 4L)))
        secondLogger.emit(NormalEvents.create("LOCK", FakeClock(5_000L, 5L)))

        val first = firstSink.records.single().event
        val second = secondSink.records.single().event
        assertEquals(first.processInstanceId, second.processInstanceId)
        assertEquals(first.sequence + 1L, second.sequence)
        assertEquals("${second.processInstanceId}-${second.sequence}", second.eventId)
    }

    @Test
    fun exceptionFromOneSinkIsReportedButDoesNotBlockLaterSinksOrEscape() {
        val export = RecordingSink()
        val failures = mutableListOf<Exception>()
        val failingSink = NormalEventSink { throw IllegalStateException("export unavailable") }
        val logger = NormalEventLogger(
            renderer = RecordingRenderer(),
            sinks = listOf(failingSink, export),
            fallbackErrorSink = { failures += it }
        )

        logger.emit(NormalEvents.create("SERVICE_STATE", FakeClock(1000L, 1L)))

        assertEquals(1, export.records.size)
        assertEquals(1, failures.size)
        assertEquals("export unavailable", failures.single().message)
    }

    @Test
    fun draftSnapshotsCallerFieldsAndBodyBeforeCallerCanMutateThem() {
        val sourceFields = linkedMapOf("remainingCount" to "0", "borrowed" to "false")
        val sourceBody = mutableListOf("[result] quotaCost=0")
        val draft = NormalEvents.create(
            type = "REST_QUOTA_CHANGE",
            clock = FakeClock(3000L, 3L),
            fields = sourceFields,
            bodyLines = sourceBody
        )
        sourceFields["remainingCount"] = "9"
        sourceFields["newField"] = "must-not-leak"
        sourceBody[0] = "mutated"
        sourceBody += "must-not-leak"

        val export = RecordingSink()
        newLogger(export).emit(draft)

        val captured = export.records.single().event.draft
        assertEquals(linkedMapOf("remainingCount" to "0", "borrowed" to "false"), captured.fields)
        assertEquals(listOf("[result] quotaCost=0"), captured.bodyLines)
        assertFalse(captured.fields.containsKey("newField"))
    }

    @Test
    fun directDraftConstructionAlsoSnapshotsMutableCollections() {
        val sourceFields = linkedMapOf("quotaCost" to "0")
        val sourceBody = mutableListOf("[result] borrowed=false")
        val draft = NormalEventDraft(
            type = "PENALTY",
            occurredAtMs = 6_000L,
            elapsedRealtimeMs = 6L,
            fields = sourceFields,
            bodyLines = sourceBody
        )
        val event = NormalEvent("process-1", "process", 1L, draft)
        sourceFields["quotaCost"] = "9"
        sourceBody += "must-not-leak"

        assertEquals(linkedMapOf("quotaCost" to "0"), event.draft.fields)
        assertEquals(listOf("[result] borrowed=false"), event.draft.bodyLines)
    }

    @Test
    fun concurrentEmitsKeepBothSinkOrdersIdenticalAndStrictlySequential() {
        val export = RecordingSink()
        val logcat = RecordingSink()
        val logger = NormalEventLogger(RecordingRenderer(), listOf(export, logcat)) { throw AssertionError(it) }
        val callerCount = 32
        val start = CountDownLatch(1)
        val ready = CountDownLatch(callerCount)
        val executor = Executors.newFixedThreadPool(callerCount)

        try {
            val futures = (1..callerCount).map { index ->
                executor.submit {
                    ready.countDown()
                    assertTrue("concurrent callers did not start together", start.await(5, TimeUnit.SECONDS))
                    logger.emit(NormalEvents.create("LOCK", FakeClock(10_000L + index, index.toLong())))
                }
            }
            assertTrue("workers did not become ready", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val exportSequences = export.records.map { it.event.sequence }
        val logcatSequences = logcat.records.map { it.event.sequence }
        assertEquals(
            (exportSequences.first()..exportSequences.first() + callerCount - 1L).toList(),
            exportSequences
        )
        assertEquals(exportSequences, logcatSequences)
        assertEquals(
            export.records.map { it.event.eventId },
            logcat.records.map { it.event.eventId }
        )
        assertEquals(
            export.records.map { it.text },
            logcat.records.map { it.text }
        )
    }

    private fun newLogger(export: RecordingSink): NormalEventLogger =
        NormalEventLogger(RecordingRenderer(), listOf(export)) { throw AssertionError(it) }

    private class FakeClock(
        private val wallMs: Long,
        private val elapsedMs: Long
    ) : EventClock {
        override fun wallNowMs(): Long = wallMs

        override fun elapsedNowMs(): Long = elapsedMs
    }

    private class RecordingRenderer : NormalEventRenderer {
        val renderedEvents = Collections.synchronizedList(mutableListOf<NormalEvent>())

        override fun render(event: NormalEvent): String {
            renderedEvents += event
            return "rendered eventId=${event.eventId} sequence=${event.sequence}"
        }
    }

    private class RecordingSink : NormalEventSink {
        val records = Collections.synchronizedList(mutableListOf<RenderedNormalEvent>())

        override fun accept(record: RenderedNormalEvent) {
            records += record
        }
    }
}
