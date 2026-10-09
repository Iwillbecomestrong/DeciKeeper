package com.decikeeper.app.statistics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.decikeeper.app.manager.statistics.RestLedger
import com.decikeeper.app.manager.statistics.RestStatsDatabase
import com.decikeeper.app.manager.statistics.RestStatsRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 流水库从 v1（没有提前发放两列）升级到 v2 后，老记录还在，新记录能正常写入、读出。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RestStatsDatabaseUpgradeTest {

    private lateinit var context: Context
    private val OLD_TS = System.currentTimeMillis() - 120_000L
    private val NEW_TS = System.currentTimeMillis() - 60_000L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(RestStatsDatabase.DATABASE_NAME)
        context.getSharedPreferences("rest_stats_ledger", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        RestStatsRecorder.clearAll(context)
        context.deleteDatabase(RestStatsDatabase.DATABASE_NAME)
    }

    /** 与上一版发布的 v1 建表语句一致。 */
    private class V1Helper(context: Context) : SQLiteOpenHelper(context, RestStatsDatabase.DATABASE_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE rest_records (
                    id TEXT PRIMARY KEY NOT NULL,
                    timestamp INTEGER NOT NULL,
                    date_label TEXT NOT NULL,
                    type TEXT NOT NULL,
                    type_name TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    requested_minutes REAL NOT NULL,
                    actual_minutes REAL NOT NULL,
                    cost_minutes REAL NOT NULL,
                    cost_count REAL NOT NULL,
                    balance_minutes REAL NOT NULL,
                    balance_count INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_rest_records_timestamp ON rest_records(timestamp DESC)")
            db.execSQL("CREATE TABLE metadata (meta_key TEXT PRIMARY KEY NOT NULL, meta_value TEXT NOT NULL)")
            db.insert("metadata", null, ContentValues().apply {
                put("meta_key", "legacy_prefs_migrated_v1")
                put("meta_value", "1")
            })
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun seedV1Row() {
        val helper = V1Helper(context)
        helper.writableDatabase.insert("rest_records", null, ContentValues().apply {
            put("id", "old_1"); put("timestamp", OLD_TS); put("date_label", "2026-10-09 08:30")
            put("type", "GRANT"); put("type_name", "额度发放"); put("reason", "旧记录")
            put("requested_minutes", 0.0); put("actual_minutes", 0.0)
            put("cost_minutes", -30.0); put("cost_count", -7.0)
            put("balance_minutes", 21.0); put("balance_count", 11)
        })
        helper.close()
    }

    @Test
    fun oldRowsSurviveTheUpgradeWithZeroAdvance() {
        seedV1Row()

        val records = RestStatsRecorder.getAllRecords(context)

        assertEquals(listOf("old_1"), records.map { it.id })
        assertEquals(0.0, records.single().advancedMinutes, 0.0)
        assertEquals(0.0, records.single().advancedCount, 0.0)
    }

    @Test
    fun newRecordsAreStoredAndReadBackAfterTheUpgrade() {
        seedV1Row()

        RestStatsRecorder.record(
            context = context, type = RestLedger.TYPE_GRANT, typeName = RestLedger.TYPE_NAME_GRANT,
            costMinutes = -35.0, costCount = -7.0, balanceMinutes = 35.0, balanceCount = 14,
            timestamp = NEW_TS, advancedMinutes = 5.0, advancedCount = 1.0
        )

        val records = RestStatsRecorder.getAllRecords(context)
        assertEquals("升级后新记录必须能读出来，不能悄悄落到备用存储里", listOf(NEW_TS, OLD_TS), records.map { it.timestamp })
        assertEquals(5.0, records.first().advancedMinutes, 0.0)
        assertTrue(records.first().advancedCount == 1.0)
    }
}
