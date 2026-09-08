$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Replace-Once {
    param(
        [string]$Content,
        [string]$Old,
        [string]$New,
        [string]$Label
    )
    $first = $Content.IndexOf($Old, [System.StringComparison]::Ordinal)
    $last = $Content.LastIndexOf($Old, [System.StringComparison]::Ordinal)
    if ($first -lt 0 -or $first -ne $last) {
        throw "$Label expected exactly one match"
    }
    return $Content.Substring(0, $first) + $New + $Content.Substring($first + $Old.Length)
}

$schedulerPath = 'app/src/main/java/com/zhizhi/app/manager/AlarmScheduler.kt'
$scheduler = [System.IO.File]::ReadAllText($schedulerPath, [System.Text.Encoding]::UTF8)
$scheduler = Replace-Once $scheduler @'
import com.zhizhi.app.data.SystemEventClock
import com.zhizhi.app.receiver.AlarmReceiver
'@ @'
import com.zhizhi.app.data.SystemEventClock
import com.zhizhi.app.manager.statistics.AlarmWakeStatsRecorder
import com.zhizhi.app.receiver.AlarmReceiver
'@ 'AlarmScheduler import'

$scheduler = Replace-Once $scheduler @'
        val currentToken = alarm?.scheduleToken ?: ""

        try {
'@ @'
        val currentToken = alarm?.scheduleToken ?: ""

        // A real wake session starts when the alarm becomes active. Pre-warm does
        // not call setAlarmActive, and recovery of the same session must not reset it.
        if (!wasSameActiveAlarm && alarmId > 0 && alarm != null) {
            AlarmWakeStatsRecorder.startSession(
                context = context,
                alarmId = alarmId,
                plannedMinuteOfDay = alarm.hour * 60 + alarm.minute
            )
        }

        try {
'@ 'AlarmScheduler session start'

$scheduler = Replace-Once $scheduler @'
        if (passed) {
            val now = System.currentTimeMillis()
            try {
'@ @'
        if (passed) {
            val now = System.currentTimeMillis()
            AlarmWakeStatsRecorder.markFirstStagePassed(context, getActiveAlarmId(context), now)
            try {
'@ 'AlarmScheduler first stage'
[System.IO.File]::WriteAllText($schedulerPath, $scheduler, $utf8NoBom)

$overlayPath = 'app/src/main/java/com/zhizhi/app/ui/LockAlarmOverlay.kt'
$overlay = [System.IO.File]::ReadAllText($overlayPath, [System.Text.Encoding]::UTF8)
$overlay = Replace-Once $overlay @'
import com.zhizhi.app.manager.AlarmScheduler
'@ @'
import com.zhizhi.app.manager.AlarmScheduler
import com.zhizhi.app.manager.statistics.AlarmWakeStatsRecorder
'@ 'LockAlarmOverlay import'

$overlay = Replace-Once $overlay @'
        // All done (single-stage, or two-stage all challenges complete)
        view.clearFocus()
'@ @'
        // All done (single-stage, or two-stage all challenges complete).
        // Commit before review/close so review time is not counted as wake time.
        if (alarmId > 0) {
            AlarmWakeStatsRecorder.completeSession(ctx, alarmId)
        }
        view.clearFocus()
'@ 'LockAlarmOverlay completion'
[System.IO.File]::WriteAllText($overlayPath, $overlay, $utf8NoBom)

$repoPath = 'app/src/main/java/com/zhizhi/app/manager/statistics/StatisticsRepository.kt'
$repo = [System.IO.File]::ReadAllText($repoPath, [System.Text.Encoding]::UTF8)
$start = $repo.IndexOf('    fun getAlarmStats(', [System.StringComparison]::Ordinal)
$nextFun = $repo.IndexOf('    fun getRuleProtectionStats(', $start, [System.StringComparison]::Ordinal)
$section4 = $repo.LastIndexOf('    // ==========================================', $nextFun, [System.StringComparison]::Ordinal)
if ($start -lt 0 -or $nextFun -lt 0 -or $section4 -lt $start) {
    throw 'StatisticsRepository alarm section anchors not found'
}

$replacement = @'
    fun getAlarmStats(
        context: Context,
        range: StatsDateRange,
        resolvedRange: ResolvedDateRange,
        weekdayFilter: WeekdayFilter
    ): AlarmStatsSummary {
        val buckets = StatsDateRangeResolver.buildBuckets(range, resolvedRange, weekdayFilter, zoneId)
        val today = LocalDate.now(zoneId)
        val prefs = try {
            context.getSharedPreferences("alarm_stats_ledger", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
        val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val datedRecords = mutableListOf<Pair<LocalDate, AlarmWakeRecord>>()

        if (prefs != null) {
            val startDate = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(resolvedRange.startMillis), zoneId
            ).toLocalDate()
            val endDateExclusive = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(resolvedRange.endMillisExclusive), zoneId
            ).toLocalDate()

            var date = startDate
            while (date.isBefore(endDateExclusive)) {
                if (!date.isAfter(today) && weekdayFilter.isEnabled(date.dayOfWeek)) {
                    val dateKey = date.format(dateFormatter)
                    val wakeKey = "alarm_${dateKey}_wake_time"
                    if (prefs.contains(wakeKey)) {
                        val wakeMinute = prefs.getInt(wakeKey, 420).coerceIn(0, 1439)
                        val alarmTimeMillis = date
                            .atTime(wakeMinute / 60, wakeMinute % 60)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli()

                        if (alarmTimeMillis >= resolvedRange.startMillis &&
                            alarmTimeMillis < resolvedRange.endMillisExclusive
                        ) {
                            val stage1 = prefs.getInt("alarm_${dateKey}_stage1_duration", 5)
                                .coerceAtLeast(0)
                            val total = prefs.getInt("alarm_${dateKey}_total_duration", 8)
                                .coerceAtLeast(stage1)
                            val firstStageCompletedMinute = if (
                                prefs.contains("alarm_${dateKey}_stage1_completed_minute")
                            ) {
                                prefs.getInt(
                                    "alarm_${dateKey}_stage1_completed_minute",
                                    (wakeMinute + stage1) % 1440
                                ).coerceIn(0, 1439)
                            } else {
                                (wakeMinute + stage1) % 1440
                            }

                            datedRecords += date to AlarmWakeRecord(
                                id = "alarm-$dateKey",
                                dateLabel = date.format(shortDateFormatter),
                                alarmMinuteOfDay = wakeMinute,
                                firstStageCompletedMinute = firstStageCompletedMinute,
                                firstStageDurationMinutes = stage1,
                                totalFlowDurationMinutes = total
                            )
                        }
                    }
                }
                date = date.plusDays(1)
            }
        }

        fun medianInt(values: List<Int>): Int? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }

        val bucketDataList = buckets.map { bucket ->
            val recordsInBucket = datedRecords.asSequence()
                .filter { (date, record) ->
                    val minute = record.alarmMinuteOfDay.coerceIn(0, 1439)
                    val eventMillis = date
                        .atTime(minute / 60, minute % 60)
                        .atZone(zoneId)
                        .toInstant()
                        .toEpochMilli()
                    eventMillis >= bucket.startMillis && eventMillis < bucket.endMillisExclusive
                }
                .map { it.second }
                .toList()

            if (recordsInBucket.isEmpty()) {
                AlarmBucketData(bucket, null, null, null, null)
            } else {
                AlarmBucketData(
                    bucket = bucket,
                    alarmMinuteOfDay = medianInt(recordsInBucket.map { it.alarmMinuteOfDay })?.toFloat(),
                    firstStageCompletedMinute = medianInt(recordsInBucket.map { it.firstStageCompletedMinute })?.toFloat(),
                    firstStageDurationMinutes = medianInt(recordsInBucket.map { it.firstStageDurationMinutes })?.toFloat(),
                    totalFlowDurationMinutes = medianInt(recordsInBucket.map { it.totalFlowDurationMinutes })?.toFloat()
                )
            }
        }

        val validRecords = datedRecords.map { it.second }
        val recent = datedRecords
            .sortedByDescending { it.first }
            .take(5)
            .map { it.second }

        return AlarmStatsSummary(
            medianAlarmMinuteOfDay = medianInt(validRecords.map { it.alarmMinuteOfDay }),
            medianFirstStageDurationMinutes = medianInt(validRecords.map { it.firstStageDurationMinutes }),
            medianTotalFlowDurationMinutes = medianInt(validRecords.map { it.totalFlowDurationMinutes }),
            recordedDaysCount = validRecords.size,
            buckets = bucketDataList,
            recentRecords = recent
        )
    }

'@
$repo = $repo.Substring(0, $start) + $replacement + $repo.Substring($section4)
[System.IO.File]::WriteAllText($repoPath, $repo, $utf8NoBom)
