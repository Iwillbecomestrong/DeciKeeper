package com.decikeeper.app.manager

import android.content.Context
import com.decikeeper.app.data.TimeSegment
import com.decikeeper.app.data.calculateTemporaryAccessQuotaPlan
import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UsableCountCarryConsumptionTest {
    private lateinit var context: Context
    private var nowMinute = 0
    private val ledger get() = context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clear()
        TimeRuleManager.saveRefreshTime(context, "00:00")
        val cal = Calendar.getInstance()
        nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        assumeTrue(nowMinute in 30..(23 * 60 + 50))
    }

    @After
    fun clear() {
        for (name in listOf("decikeeper_rules", "decikeeper_rules_baseline", "decikeeper_rules_pending", "decikeeper_ledger")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        TimeRuleManager.invalidateSegmentsCache()
    }

    private fun segment(name: String, start: Int, end: Int, enabled: Boolean = true) = TimeSegment(
        startHour = start / 60, startMinute = start % 60,
        endHour = end / 60, endMinute = end % 60,
        limitMins = 1, name = name, usableCountLimitHalfUnits = 6, isEnabled = enabled
    )

    private fun install(evening: Boolean = false, morningEnabled: Boolean = true) {
        val split = nowMinute - 10
        val afternoonEnd = if (evening) nowMinute - 2 else nowMinute + 2
        val segments = listOf(
            segment("上午", 0, split, morningEnabled),
            segment("下午", split, afternoonEnd),
            segment("晚上", afternoonEnd, 1439)
        )
        val json = TimeRuleManager.segmentsToJson(segments)
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().putString("segments_json", json).commit()
        TimeRuleManager.invalidateSegmentsCache()
    }

    private fun usageKey(name: String) = "count_usage_half_units_v2_${CreditManager.getBusinessDateString(context)}_$name"

    @Test
    fun consumedMorningCarryDoesNotReturnWhenEveningStarts() {
        install()
        ledger.edit().putInt(usageKey("上午"), 3).commit()
        assertEquals(9, CreditManager.getRemainingUsableCountHalfUnits(context))

        repeat(9) { CreditManager.consumeUsableCount(context) }

        assertEquals(0, CreditManager.getRemainingUsableCountHalfUnits(context))
        assertEquals(6, ledger.getInt(usageKey("上午"), 0))
        assertEquals(6, ledger.getInt(usageKey("下午"), 0))
        install(evening = true)
        assertEquals("只剩晚上新发放的 3 次", 6, CreditManager.getRemainingUsableCountHalfUnits(context))
    }

    @Test
    fun weightedConsumptionUsesStoredEntitlementAndSkipsDisabledCarry() {
        install(morningEnabled = false)
        val date = CreditManager.getBusinessDateString(context)
        ledger.edit().putString("quota_entitle_${date}_下午", "30.0;2;1;1.0").commit()

        CreditManager.consumeUsableCountWeighted(context, 2)

        assertEquals(0, CreditManager.getRemainingUsableCountHalfUnits(context))
        assertEquals(0, ledger.getInt(usageKey("上午"), 0))
        assertEquals(2, ledger.getInt(usageKey("下午"), 0))
        install(evening = true, morningEnabled = false)
        assertEquals(6, CreditManager.getRemainingUsableCountHalfUnits(context))
    }

    @Test
    fun borrowingTransactionConsumesEffectiveCurrentQuotaThenRealCarry() {
        install()
        val date = CreditManager.getBusinessDateString(context)
        ledger.edit()
            .putString("quota_entitle_${date}_下午", "30.0;2;1;1.0")
            .putInt(usageKey("上午"), 3)
            .commit()
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 0, currentAvailableSec = 0, nextAvailableSec = 0,
            totalCountCostHalfUnits = 6, currentAvailableCountHalfUnits = 5,
            nextAvailableCountHalfUnits = 6
        )

        assertTrue(CreditManager.applyTemporaryAccessQuotaPlan(context, plan))

        assertEquals(2, ledger.getInt(usageKey("下午"), 0))
        assertEquals(6, ledger.getInt(usageKey("上午"), 0))
        assertEquals(2, ledger.getInt(usageKey("晚上"), 0))
        install(evening = true)
        assertEquals(4, CreditManager.getRemainingUsableCountHalfUnits(context))
    }

    @Test
    fun weightedChargeSplitsAcrossCurrentAndPreviousQuota() {
        install()
        ledger.edit().putInt(usageKey("下午"), 5).putInt(usageKey("上午"), 3).commit()

        CreditManager.consumeUsableCountWeighted(context, 2)

        assertEquals(6, ledger.getInt(usageKey("下午"), 0))
        assertEquals(4, ledger.getInt(usageKey("上午"), 0))
        assertEquals(2, CreditManager.getRemainingUsableCountHalfUnits(context))
        install(evening = true)
        assertEquals(8, CreditManager.getRemainingUsableCountHalfUnits(context))
    }

    @Test
    fun transactionWithUnavailableCurrentCountDoesNotWritePartialCharges() {
        install(morningEnabled = false)
        ledger.edit().putInt(usageKey("下午"), 5).commit()
        val plan = calculateTemporaryAccessQuotaPlan(
            totalTimeCostSec = 0, currentAvailableSec = 0, nextAvailableSec = 0,
            totalCountCostHalfUnits = 2, currentAvailableCountHalfUnits = 2,
            nextAvailableCountHalfUnits = 6
        )

        assertFalse(CreditManager.applyTemporaryAccessQuotaPlan(context, plan))

        assertEquals(5, ledger.getInt(usageKey("下午"), 0))
        assertEquals(0, ledger.getInt(usageKey("上午"), 0))
        assertEquals(0, ledger.getInt(usageKey("晚上"), 0))
    }
}
