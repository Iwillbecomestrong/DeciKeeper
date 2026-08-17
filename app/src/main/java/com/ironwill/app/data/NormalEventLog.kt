package com.ironwill.app.data

import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Immutable domain data collected before a normal event receives its process-local envelope.
 *
 * Normal-path callers create this through [NormalEvents] so their mutable input collections
 * cannot change a record after the business transition has been observed.
 */
class NormalEventDraft internal constructor(
    type: String,
    val occurredAtMs: Long,
    val elapsedRealtimeMs: Long,
    fields: Map<String, String>,
    bodyLines: List<String>
) {
    val type: String = type
    val fields: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(fields))
    val bodyLines: List<String> = Collections.unmodifiableList(ArrayList(bodyLines))
}

/** A process-local, strictly ordered envelope for one semantic normal event. */
class NormalEvent internal constructor(
    val eventId: String,
    val processInstanceId: String,
    val sequence: Long,
    val draft: NormalEventDraft
)

/** The exact single rendering delivered to every normal-event output channel. */
data class RenderedNormalEvent(
    val event: NormalEvent,
    val text: String
)

fun interface NormalEventSink {
    fun accept(record: RenderedNormalEvent)
}

/** Persistence boundary used by [LogExporter]'s normal-event sink. */
fun interface NormalEventExportWriter {
    fun append(record: RenderedNormalEvent)
}

/** Android logcat boundary retained as an injectable record writer for verification. */
fun interface NormalEventLogcatWriter {
    fun append(record: RenderedNormalEvent)
}

/** Writes the already-rendered normal event to Android logcat without rebuilding any fields. */
class AndroidLogcatNormalEventSink(
    private val writer: NormalEventLogcatWriter = NormalEventLogcatWriter { record ->
        Log.i(LOGCAT_TAG, record.text)
    }
) : NormalEventSink {
    override fun accept(record: RenderedNormalEvent) {
        writer.append(record)
    }

    private companion object {
        private const val LOGCAT_TAG = "IronWillEvent"
    }
}

fun interface NormalEventRenderer {
    fun render(event: NormalEvent): String
}

/** Separates display wall time from monotonic timing used by later state aggregation. */
interface EventClock {
    fun wallNowMs(): Long

    fun elapsedNowMs(): Long
}

object SystemEventClock : EventClock {
    override fun wallNowMs(): Long = System.currentTimeMillis()

    override fun elapsedNowMs(): Long = SystemClock.elapsedRealtime()
}

/** Read-only feature flags for optional raw diagnostic details. */
interface LogDebugConfig {
    val foregroundRaw: Boolean
    val quotaCalculation: Boolean
    val protectionVerbose: Boolean
    val desktopTimerTicks: Boolean
}

/** Default disabled configuration. Test or application wiring can supply another implementation. */
object DefaultLogDebugConfig : LogDebugConfig {
    @Volatile
    var foregroundRawEnabled: Boolean = false

    @Volatile
    var quotaCalculationEnabled: Boolean = false

    @Volatile
    var protectionVerboseEnabled: Boolean = false

    @Volatile
    var desktopTimerTicksEnabled: Boolean = false

    override val foregroundRaw: Boolean
        get() = foregroundRawEnabled

    override val quotaCalculation: Boolean
        get() = quotaCalculationEnabled

    override val protectionVerbose: Boolean
        get() = protectionVerboseEnabled

    override val desktopTimerTicks: Boolean
        get() = desktopTimerTicksEnabled
}

/** Factory for immutable normal-event drafts. */
object NormalEvents {
    fun create(
        type: String,
        clock: EventClock,
        fields: Map<String, String> = emptyMap(),
        bodyLines: List<String> = emptyList()
    ): NormalEventDraft {
        require(type.isNotBlank()) { "Normal event type must not be blank" }

        val fieldSnapshot = LinkedHashMap<String, String>(fields.size)
        fields.forEach { (key, value) -> fieldSnapshot[key] = value }
        val bodySnapshot = ArrayList<String>(bodyLines)
        return NormalEventDraft(
            type = type,
            occurredAtMs = clock.wallNowMs(),
            elapsedRealtimeMs = clock.elapsedNowMs(),
            fields = Collections.unmodifiableMap(fieldSnapshot),
            bodyLines = Collections.unmodifiableList(bodySnapshot)
        )
    }
}

/**
 * The normal-path text renderer. Field insertion order is retained and values are kept on the
 * header line by escaping physical line breaks instead of producing unrelated log entries.
 */
object DefaultNormalEventRenderer : NormalEventRenderer {
    override fun render(event: NormalEvent): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val header = buildString {
            append(formatter.format(Date(event.draft.occurredAtMs)))
            append(" [")
            append(event.draft.type)
            append("] eventId=")
            append(event.eventId)
            append(" processInstanceId=")
            append(event.processInstanceId)
            append(" seq=")
            append(event.sequence)
            event.draft.fields.forEach { (key, value) ->
                append(' ')
                append(key)
                append('=')
                append(escapeFieldValue(value))
            }
        }
        return if (event.draft.bodyLines.isEmpty()) {
            header
        } else {
            buildString {
                append(header)
                event.draft.bodyLines.forEach { line ->
                    append('\n')
                    append(line)
                }
            }
        }
    }

    private fun escapeFieldValue(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(character)
            }
        }
    }
}

/**
 * Serializes envelope allocation, rendering, and fan-out. A logging [Exception] is isolated so
 * normal business callers continue and remaining sinks still receive the same rendered record.
 */
class NormalEventLogger(
    private val renderer: NormalEventRenderer,
    sinks: List<NormalEventSink>,
    private val fallbackErrorSink: (Exception) -> Unit = ::reportFallbackError
) {
    private val sinkSnapshot = sinks.toList()

    fun emit(draft: NormalEventDraft) {
        synchronized(processEmissionLock) {
            val sequence = ++nextProcessSequence
            val event = NormalEvent(
                eventId = "$processInstanceId-$sequence",
                processInstanceId = processInstanceId,
                sequence = sequence,
                draft = draft
            )
            val rendered = try {
                RenderedNormalEvent(event, renderer.render(event))
            } catch (error: Exception) {
                reportFailure(error)
                return
            }

            sinkSnapshot.forEach { sink ->
                try {
                    sink.accept(rendered)
                } catch (error: Exception) {
                    reportFailure(error)
                }
            }
        }
    }

    private fun reportFailure(error: Exception) {
        try {
            fallbackErrorSink(error)
        } catch (_: Exception) {
            // A fallback must never create a second logging failure path.
        }
    }

    private companion object {
        private const val TAG = "NormalEventLogger"
        private val processEmissionLock = Any()
        private val processInstanceId = UUID.randomUUID().toString()
        private var nextProcessSequence = 0L

        private fun reportFallbackError(error: Exception) {
            try {
                Log.e(TAG, "Normal event logging failed", error)
            } catch (_: Exception) {
                // Android logging can be unavailable in an unusual bootstrap/test environment.
            }
        }
    }
}
