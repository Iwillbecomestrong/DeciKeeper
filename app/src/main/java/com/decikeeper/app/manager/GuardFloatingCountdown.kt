package com.decikeeper.app.manager

/** Pure projection for the small guard-window exit countdown. */
enum class FloatingExit(val label: String, val priority: Int) {
    PRIVILEGE("特权", 0),
    TEMPORARY_UNLOCK("临时解锁", 1),
    REST("休息", 2)
}

data class FloatingExitCountdown(
    val exit: FloatingExit,
    val endAtMs: Long,
    val remainingSeconds: Long,
    val text: String
) {
    val label: String
        get() = exit.label
}

object GuardFloatingCountdown {

    private const val LAST_HOUR_SECONDS = 60 * 60L

    fun activeItems(
        nowMs: Long,
        privilegeEndMs: Long = 0L,
        temporaryUnlockEndMs: Long = 0L,
        restEndMs: Long = 0L
    ): List<FloatingExitCountdown> {
        return listOf(
            FloatingExit.PRIVILEGE to privilegeEndMs,
            FloatingExit.TEMPORARY_UNLOCK to temporaryUnlockEndMs,
            FloatingExit.REST to restEndMs
        ).mapNotNull { (exit, endAtMs) ->
            if (endAtMs <= nowMs) return@mapNotNull null
            val remainingSeconds = ((endAtMs - nowMs + 999L) / 1000L).coerceAtLeast(1L)
            FloatingExitCountdown(
                exit = exit,
                endAtMs = endAtMs,
                remainingSeconds = remainingSeconds,
                text = formatExact(remainingSeconds)
            )
        }.sortedBy { it.exit.priority }
    }

    fun collapsedText(
        nowMs: Long,
        privilegeEndMs: Long = 0L,
        temporaryUnlockEndMs: Long = 0L,
        restEndMs: Long = 0L
    ): String? {
        val highest = activeItems(
            nowMs = nowMs,
            privilegeEndMs = privilegeEndMs,
            temporaryUnlockEndMs = temporaryUnlockEndMs,
            restEndMs = restEndMs
        ).firstOrNull() ?: return null
        return if (highest.remainingSeconds > LAST_HOUR_SECONDS) {
            ">1h"
        } else {
            formatShort(highest.remainingSeconds)
        }
    }

    fun expandedItems(
        nowMs: Long,
        privilegeEndMs: Long = 0L,
        temporaryUnlockEndMs: Long = 0L,
        restEndMs: Long = 0L
    ): List<FloatingExitCountdown> = activeItems(
        nowMs = nowMs,
        privilegeEndMs = privilegeEndMs,
        temporaryUnlockEndMs = temporaryUnlockEndMs,
        restEndMs = restEndMs
    )

    private fun formatShort(totalSeconds: Long): String {
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(java.util.Locale.ROOT, minutes, seconds)
    }

    private fun formatExact(totalSeconds: Long): String {
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, seconds)
        } else {
            "%d:%02d".format(java.util.Locale.ROOT, minutes, seconds)
        }
    }
}
