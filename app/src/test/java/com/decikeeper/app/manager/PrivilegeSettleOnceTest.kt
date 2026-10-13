package com.decikeeper.app.manager

import android.content.Context
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 回归：同一次特权被结算两遍（10-06 的特权统计翻倍成 390 分钟，实际只有 195 个勿扰分钟）。
 * 到期瞬间监控线程、界面线程会同时触发结算，结算必须只生效一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeSettleOnceTest {

    private lateinit var context: Context

    private val ledgerPrefs get() =
        context.getSharedPreferences("decikeeper_ledger", Context.MODE_PRIVATE)

    private val start: Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 6, 10, 16, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val end: Long = start + 60 * 60_000L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("decikeeper_rules", Context.MODE_PRIVATE).edit().clear().commit()
        ledgerPrefs.edit().clear().commit()
        TimeRuleManager.invalidateSegmentsCache()
        TimeRuleManager.saveRefreshTime(context, "06:00")
        // 一次 60 分钟的特权会话，已到期
        ledgerPrefs.edit()
            .putBoolean("is_privilege_mode", true)
            .putLong("privilege_session_start", start)
            .putInt("privilege_session_duration", 60)
            .putLong("free_time_end", end)
            .commit()
    }

    private fun recordedPrivilegeMinutes(): Int =
        ledgerPrefs.all.filterKeys { it.startsWith("privilege_usage_") }
            .values.sumOf { (it as? Int) ?: 0 }

    @Test
    fun settlingTwiceRecordsTheSessionOnce() {
        CreditManager.stopWeeklyPrivilegeAt(context, end)
        CreditManager.stopWeeklyPrivilegeAt(context, end)

        assertEquals(60, recordedPrivilegeMinutes())
    }

    @Test
    fun concurrentSettlementsRecordTheSessionOnce() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                ready.countDown()
                go.await()
                try {
                    CreditManager.stopWeeklyPrivilegeAt(context, end)
                } finally {
                    done.countDown()
                }
            }
        }
        ready.await()
        go.countDown()
        assertTrue("结算线程应在 30 秒内结束", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals("同一次特权不能被记多遍", 60, recordedPrivilegeMinutes())
    }
}
