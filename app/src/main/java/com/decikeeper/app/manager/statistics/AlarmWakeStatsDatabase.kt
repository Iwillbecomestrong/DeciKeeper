package com.decikeeper.app.manager.statistics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.decikeeper.app.data.statistics.AlarmWakeRecord

/**
 * SQLite storage for the unbounded alarm-wake history.
 *
 * The old SharedPreferences JSON is intentionally not deleted. It remains a
 * migration/old-backup source, while this table is the runtime source of truth.
 */
class AlarmWakeStatsDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_RECORDS (
                $COLUMN_ID TEXT PRIMARY KEY NOT NULL,
                $COLUMN_DATE_LABEL TEXT NOT NULL,
                $COLUMN_ALARM_MINUTE INTEGER NOT NULL,
                $COLUMN_FIRST_STAGE_MINUTE INTEGER NOT NULL,
                $COLUMN_FIRST_STAGE_DURATION INTEGER NOT NULL,
                $COLUMN_TOTAL_DURATION INTEGER NOT NULL,
                $COLUMN_COMPLETED_AT INTEGER NOT NULL,
                $COLUMN_ALARM_ID INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_${TABLE_RECORDS}_completed_at " +
                "ON $TABLE_RECORDS($COLUMN_COMPLETED_AT DESC)"
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_META (
                $COLUMN_META_KEY TEXT PRIMARY KEY NOT NULL,
                $COLUMN_META_VALUE TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the initial schema. Future schema changes must be
        // additive and preserve existing rows.
    }

    fun migrateIfNeeded(legacyRecordsProvider: () -> List<AlarmWakeRecord>) {
        // 迁移完成后不再触碰旧的大 JSON，避免每次统计查询重新解析历史。
        if (isMigrationDone(readableDatabase)) return
        val legacyRecords = legacyRecordsProvider()
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!isMigrationDone(db)) {
                insertAllInternal(db, legacyRecords, SQLiteDatabase.CONFLICT_IGNORE)
                db.insertWithOnConflict(
                    TABLE_META,
                    null,
                    ContentValues().apply {
                        put(COLUMN_META_KEY, MIGRATION_KEY)
                        put(COLUMN_META_VALUE, "1")
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insert(record: AlarmWakeRecord): Boolean {
        return writableDatabase.insertWithOnConflict(
            TABLE_RECORDS,
            null,
            record.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }

    fun readAll(): List<AlarmWakeRecord> {
        val result = mutableListOf<AlarmWakeRecord>()
        readableDatabase.query(
            TABLE_RECORDS,
            ALL_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_COMPLETED_AT DESC, rowid DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.toAlarmWakeRecord()
            }
        }
        return result
    }

    fun findEarliestForDate(dateLabel: String): AlarmWakeRecord? {
        return readableDatabase.query(
            TABLE_RECORDS,
            ALL_COLUMNS,
            "$COLUMN_DATE_LABEL = ?",
            arrayOf(dateLabel),
            null,
            null,
            "$COLUMN_ALARM_MINUTE ASC, $COLUMN_COMPLETED_AT ASC, rowid ASC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toAlarmWakeRecord() else null
        }
    }

    fun update(record: AlarmWakeRecord): Boolean {
        return writableDatabase.update(
            TABLE_RECORDS,
            record.toContentValues(),
            "$COLUMN_ID = ?",
            arrayOf(record.id)
        ) > 0
    }

    fun delete(id: String): Boolean {
        return writableDatabase.delete(
            TABLE_RECORDS,
            "$COLUMN_ID = ?",
            arrayOf(id)
        ) > 0
    }

    fun deleteBefore(cutoffMillis: Long): Int = writableDatabase.delete(
        TABLE_RECORDS,
        "$COLUMN_COMPLETED_AT > 0 AND $COLUMN_COMPLETED_AT < ?",
        arrayOf(cutoffMillis.toString())
    )

    fun replaceAll(records: List<AlarmWakeRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_RECORDS, null, null)
            insertAllInternal(db, records, SQLiteDatabase.CONFLICT_REPLACE)
            markMigrationDone(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Restore an old backup only when the current database has no records. */
    fun replaceIfEmpty(records: List<AlarmWakeRecord>): Boolean {
        if (readCount() > 0 || records.isEmpty()) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (readCount(db) == 0) {
                insertAllInternal(db, records, SQLiteDatabase.CONFLICT_IGNORE)
                markMigrationDone(db)
                db.setTransactionSuccessful()
                return true
            }
            return false
        } finally {
            db.endTransaction()
        }
    }

    private fun readCount(): Int = readCount(readableDatabase)

    private fun readCount(db: SQLiteDatabase): Int {
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_RECORDS", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun isMigrationDone(db: SQLiteDatabase): Boolean {
        db.query(
            TABLE_META,
            arrayOf(COLUMN_META_VALUE),
            "$COLUMN_META_KEY = ?",
            arrayOf(MIGRATION_KEY),
            null,
            null,
            null,
            "1"
        ).use { cursor -> return cursor.moveToFirst() }
    }

    private fun markMigrationDone(db: SQLiteDatabase) {
        db.insertWithOnConflict(
            TABLE_META,
            null,
            ContentValues().apply {
                put(COLUMN_META_KEY, MIGRATION_KEY)
                put(COLUMN_META_VALUE, "1")
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun insertAllInternal(
        db: SQLiteDatabase,
        records: List<AlarmWakeRecord>,
        conflictAlgorithm: Int
    ) {
        records.forEach { record ->
            db.insertWithOnConflict(
                TABLE_RECORDS,
                null,
                record.toContentValues(),
                conflictAlgorithm
            )
        }
    }

    private fun AlarmWakeRecord.toContentValues(): ContentValues = ContentValues().apply {
        put(COLUMN_ID, id)
        put(COLUMN_DATE_LABEL, dateLabel)
        put(COLUMN_ALARM_MINUTE, alarmMinuteOfDay)
        put(COLUMN_FIRST_STAGE_MINUTE, firstStageCompletedMinute)
        put(COLUMN_FIRST_STAGE_DURATION, firstStageDurationMinutes)
        put(COLUMN_TOTAL_DURATION, totalFlowDurationMinutes)
        put(COLUMN_COMPLETED_AT, completedAt)
        put(COLUMN_ALARM_ID, alarmId)
    }

    private fun android.database.Cursor.toAlarmWakeRecord(): AlarmWakeRecord = AlarmWakeRecord(
        id = getString(getColumnIndexOrThrow(COLUMN_ID)),
        dateLabel = getString(getColumnIndexOrThrow(COLUMN_DATE_LABEL)),
        alarmMinuteOfDay = getInt(getColumnIndexOrThrow(COLUMN_ALARM_MINUTE)),
        firstStageCompletedMinute = getInt(getColumnIndexOrThrow(COLUMN_FIRST_STAGE_MINUTE)),
        firstStageDurationMinutes = getInt(getColumnIndexOrThrow(COLUMN_FIRST_STAGE_DURATION)),
        totalFlowDurationMinutes = getInt(getColumnIndexOrThrow(COLUMN_TOTAL_DURATION)),
        completedAt = getLong(getColumnIndexOrThrow(COLUMN_COMPLETED_AT)),
        alarmId = getInt(getColumnIndexOrThrow(COLUMN_ALARM_ID))
    )

    companion object {
        const val DATABASE_NAME = "alarm_stats.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_RECORDS = "wake_records"
        private const val TABLE_META = "metadata"
        private const val MIGRATION_KEY = "legacy_prefs_migrated_v1"

        private const val COLUMN_ID = "id"
        private const val COLUMN_DATE_LABEL = "date_label"
        private const val COLUMN_ALARM_MINUTE = "alarm_minute_of_day"
        private const val COLUMN_FIRST_STAGE_MINUTE = "first_stage_completed_minute"
        private const val COLUMN_FIRST_STAGE_DURATION = "first_stage_duration_minutes"
        private const val COLUMN_TOTAL_DURATION = "total_flow_duration_minutes"
        private const val COLUMN_COMPLETED_AT = "completed_at"
        private const val COLUMN_ALARM_ID = "alarm_id"
        private const val COLUMN_META_KEY = "meta_key"
        private const val COLUMN_META_VALUE = "meta_value"

        private val ALL_COLUMNS = arrayOf(
            COLUMN_ID,
            COLUMN_DATE_LABEL,
            COLUMN_ALARM_MINUTE,
            COLUMN_FIRST_STAGE_MINUTE,
            COLUMN_FIRST_STAGE_DURATION,
            COLUMN_TOTAL_DURATION,
            COLUMN_COMPLETED_AT,
            COLUMN_ALARM_ID
        )
    }
}
