package com.decikeeper.app.manager.statistics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.decikeeper.app.data.statistics.RestUsageRecord

/** SQLite source of truth for the long-lived rest/exit history. */
class RestStatsDatabase(context: Context) : SQLiteOpenHelper(
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
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_DATE_LABEL TEXT NOT NULL,
                $COLUMN_TYPE TEXT NOT NULL,
                $COLUMN_TYPE_NAME TEXT NOT NULL,
                $COLUMN_REASON TEXT NOT NULL,
                $COLUMN_REQUESTED REAL NOT NULL,
                $COLUMN_ACTUAL REAL NOT NULL,
                $COLUMN_COST_MINUTES REAL NOT NULL,
                $COLUMN_COST_COUNT REAL NOT NULL,
                $COLUMN_BALANCE_MINUTES REAL NOT NULL,
                $COLUMN_BALANCE_COUNT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_${TABLE_RECORDS}_timestamp ON $TABLE_RECORDS($COLUMN_TIMESTAMP DESC)")
        db.execSQL(
            """
            CREATE TABLE $TABLE_META (
                $COLUMN_META_KEY TEXT PRIMARY KEY NOT NULL,
                $COLUMN_META_VALUE TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun migrateIfNeeded(legacyRecordsProvider: () -> List<RestUsageRecord>) {
        if (isMigrationDone(readableDatabase)) return
        val legacyRecords = legacyRecordsProvider()
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!isMigrationDone(db)) {
                legacyRecords.forEach { record ->
                    db.insertWithOnConflict(
                        TABLE_RECORDS,
                        null,
                        record.toContentValues(),
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
                markMigrationDone(db)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insert(record: RestUsageRecord): Boolean = writableDatabase.insertWithOnConflict(
        TABLE_RECORDS,
        null,
        record.toContentValues(),
        SQLiteDatabase.CONFLICT_REPLACE
    ) != -1L

    fun readAll(): List<RestUsageRecord> {
        val result = mutableListOf<RestUsageRecord>()
        readableDatabase.query(
            TABLE_RECORDS,
            ALL_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_TIMESTAMP DESC, rowid DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toRecord()
        }
        return result
    }

    fun update(record: RestUsageRecord): Boolean = writableDatabase.update(
        TABLE_RECORDS,
        record.toContentValues(),
        "$COLUMN_ID = ?",
        arrayOf(record.id)
    ) > 0

    fun delete(id: String): Boolean = writableDatabase.delete(
        TABLE_RECORDS,
        "$COLUMN_ID = ?",
        arrayOf(id)
    ) > 0

    fun deleteBefore(cutoffMillis: Long): Int = writableDatabase.delete(
        TABLE_RECORDS,
        "$COLUMN_TIMESTAMP > 0 AND $COLUMN_TIMESTAMP < ?",
        arrayOf(cutoffMillis.toString())
    )

    fun replaceAll(records: List<RestUsageRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_RECORDS, null, null)
            records.forEach { record ->
                db.insertWithOnConflict(
                    TABLE_RECORDS,
                    null,
                    record.toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            markMigrationDone(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(TABLE_RECORDS, null, null)
            writableDatabase.delete(TABLE_META, null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun isMigrationDone(db: SQLiteDatabase): Boolean = db.query(
        TABLE_META,
        arrayOf(COLUMN_META_VALUE),
        "$COLUMN_META_KEY = ?",
        arrayOf(MIGRATION_KEY),
        null,
        null,
        null,
        "1"
    ).use { it.moveToFirst() }

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

    private fun RestUsageRecord.toContentValues() = ContentValues().apply {
        put(COLUMN_ID, id)
        put(COLUMN_TIMESTAMP, timestamp)
        put(COLUMN_DATE_LABEL, dateLabel)
        put(COLUMN_TYPE, type)
        put(COLUMN_TYPE_NAME, typeName)
        put(COLUMN_REASON, reason)
        put(COLUMN_REQUESTED, requestedMinutes)
        put(COLUMN_ACTUAL, actualMinutes)
        put(COLUMN_COST_MINUTES, costMinutes)
        put(COLUMN_COST_COUNT, costCount)
        put(COLUMN_BALANCE_MINUTES, balanceMinutes)
        put(COLUMN_BALANCE_COUNT, balanceCount)
    }

    private fun android.database.Cursor.toRecord() = RestUsageRecord(
        id = getString(getColumnIndexOrThrow(COLUMN_ID)),
        timestamp = getLong(getColumnIndexOrThrow(COLUMN_TIMESTAMP)),
        dateLabel = getString(getColumnIndexOrThrow(COLUMN_DATE_LABEL)),
        type = getString(getColumnIndexOrThrow(COLUMN_TYPE)),
        typeName = getString(getColumnIndexOrThrow(COLUMN_TYPE_NAME)),
        reason = getString(getColumnIndexOrThrow(COLUMN_REASON)),
        requestedMinutes = getDouble(getColumnIndexOrThrow(COLUMN_REQUESTED)),
        actualMinutes = getDouble(getColumnIndexOrThrow(COLUMN_ACTUAL)),
        costMinutes = getDouble(getColumnIndexOrThrow(COLUMN_COST_MINUTES)),
        costCount = getDouble(getColumnIndexOrThrow(COLUMN_COST_COUNT)),
        balanceMinutes = getDouble(getColumnIndexOrThrow(COLUMN_BALANCE_MINUTES)),
        balanceCount = getInt(getColumnIndexOrThrow(COLUMN_BALANCE_COUNT))
    )

    companion object {
        const val DATABASE_NAME = "rest_stats.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_RECORDS = "rest_records"
        private const val TABLE_META = "metadata"
        private const val MIGRATION_KEY = "legacy_prefs_migrated_v1"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_DATE_LABEL = "date_label"
        private const val COLUMN_TYPE = "type"
        private const val COLUMN_TYPE_NAME = "type_name"
        private const val COLUMN_REASON = "reason"
        private const val COLUMN_REQUESTED = "requested_minutes"
        private const val COLUMN_ACTUAL = "actual_minutes"
        private const val COLUMN_COST_MINUTES = "cost_minutes"
        private const val COLUMN_COST_COUNT = "cost_count"
        private const val COLUMN_BALANCE_MINUTES = "balance_minutes"
        private const val COLUMN_BALANCE_COUNT = "balance_count"
        private const val COLUMN_META_KEY = "meta_key"
        private const val COLUMN_META_VALUE = "meta_value"
        private val ALL_COLUMNS = arrayOf(
            COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_DATE_LABEL, COLUMN_TYPE, COLUMN_TYPE_NAME,
            COLUMN_REASON, COLUMN_REQUESTED, COLUMN_ACTUAL, COLUMN_COST_MINUTES, COLUMN_COST_COUNT,
            COLUMN_BALANCE_MINUTES, COLUMN_BALANCE_COUNT
        )
    }
}
