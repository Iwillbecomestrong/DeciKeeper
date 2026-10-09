package com.decikeeper.app.data

/**
 * 每周特权自动结算的时刻：到期时间与当日 24:00 取较早者；两者都还没到则不结算（返回 null）。
 *
 * 必须取较早者：如果结算被推迟到 24:00 之后才触发（如熄屏、进程被杀），
 * 直接按 24:00 结算会把本该在到期时刻结束的特权算成一直持续到午夜。
 */
internal fun resolvePrivilegeSettlementTime(endTs: Long, midnightTs: Long, nowMillis: Long): Long? {
    val due = minOf(endTs, midnightTs)
    return if (nowMillis >= due) due else null
}

/**
 * 特权结算的有效结束时刻：不得晚于申请的计划结束时刻，也不得早于开始时刻。
 * [requestedMinutes] <= 0 表示没有计划时长，不设上限。
 */
internal fun clampPrivilegeStopAt(stopAtMillis: Long, startTs: Long, requestedMinutes: Int): Long {
    val plannedEnd = if (requestedMinutes > 0) startTs + requestedMinutes * 60_000L else Long.MAX_VALUE
    return minOf(stopAtMillis, plannedEnd).coerceAtLeast(startTs)
}
