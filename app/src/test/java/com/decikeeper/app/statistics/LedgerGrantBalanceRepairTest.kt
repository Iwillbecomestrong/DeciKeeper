package com.decikeeper.app.statistics

import android.content.Context
import com.decikeeper.app.manager.statistics.LedgerEntry
import com.decikeeper.app.manager.statistics.LedgerGrantBalanceRepair
import com.decikeeper.app.manager.statistics.RestLedger
import com.decikeeper.app.manager.statistics.RestStatsRecorder
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LedgerGrantBalanceRepairTest {

    private lateinit var context: Context
    private val zone = ZoneId.systemDefault()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        RestStatsRecorder.clearAll(context)
        context.getSharedPreferences("rest_stats_repair", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        RestStatsRecorder.clearAll(context)
    }

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 10, 9, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun grant(ts: Long, cost: Double, countTimes: Double, balance: Double, halfUnits: Int) =
        RestStatsRecorder.record(
            context = context, type = RestLedger.TYPE_GRANT, typeName = RestLedger.TYPE_NAME_GRANT,
            costMinutes = -cost, costCount = -countTimes, balanceMinutes = balance, balanceCount = halfUnits, timestamp = ts
        )

    private fun balanceOf(ts: Long) = RestStatsRecorder.getAllRecords(context).single { it.timestamp == ts }

    @Test
    fun fixesTheTwoLateDetectedGrantsOfThatDay() {
        // 装包那一刻的当前结余被当成了「发放后」的结余
        grant(at(8, 30), 30.0, 7.0, 21.0, 11)
        grant(at(12, 45), 35.0, 7.0, 26.0, 16)

        LedgerGrantBalanceRepair.repairOnce(context)

        with(balanceOf(at(8, 30))) { assertEquals(30.0, balanceMinutes, 0.001); assertEquals(14, balanceCount) }
        with(balanceOf(at(12, 45))) { assertEquals(49.1, balanceMinutes, 0.001); assertEquals(22, balanceCount) }
    }

    @Test
    fun theFirstGrantOfTheDayStartsFromZeroAfterTheRepair() {
        grant(at(8, 30), 30.0, 7.0, 21.0, 11)
        val rest = RestStatsRecorder.record(
            context = context, type = "NORMAL", typeName = "常规休息", costMinutes = 3.0, costCount = 1.0,
            balanceMinutes = 27.0, balanceCount = 13, timestamp = at(9, 23)
        )

        LedgerGrantBalanceRepair.repairOnce(context)

        val entries = RestLedger.buildEntries(
            RestStatsRecorder.getAllRecords(context).sortedByDescending { it.timestamp },
            includeGaps = true
        )
        val firstGrant = entries.filterIsInstance<LedgerEntry.Row>().first { it.record.type == RestLedger.TYPE_GRANT }
        assertEquals("当天第一笔发放，变动前一定是 0", 0.0, firstGrant.beforeMinutes!!, 0.001)
        assertEquals(0.0, firstGrant.beforeCount!!, 0.001)
        assertTrue("发放后接上 09:23 的休息，没有缺口", entries.none { it is LedgerEntry.Gap })
        assertEquals(rest.id, (entries.first() as LedgerEntry.Row).record.id)
    }

    @Test
    fun onlyTouchesRowsWhoseStoredValuesAreTheKnownWrongOnes() {
        // 同一时间但结余不是已确认的错误值（比如已经是对的 / 被改过）：不动
        grant(at(8, 30), 30.0, 7.0, 30.0, 14)
        grant(at(12, 45), 35.0, 7.0, 40.0, 20)

        LedgerGrantBalanceRepair.repairOnce(context)

        assertEquals(30.0, balanceOf(at(8, 30)).balanceMinutes, 0.001)
        assertEquals(40.0, balanceOf(at(12, 45)).balanceMinutes, 0.001)
    }

    @Test
    fun runsOnlyOnce() {
        grant(at(8, 30), 30.0, 7.0, 21.0, 11)
        LedgerGrantBalanceRepair.repairOnce(context)
        // 修完之后，如果后来又有人写入同样的错误值，不会被再次改动
        RestStatsRecorder.updateRecord(context, balanceOf(at(8, 30)).copy(balanceMinutes = 21.0, balanceCount = 11))

        LedgerGrantBalanceRepair.repairOnce(context)

        assertEquals(21.0, balanceOf(at(8, 30)).balanceMinutes, 0.001)
    }
}
