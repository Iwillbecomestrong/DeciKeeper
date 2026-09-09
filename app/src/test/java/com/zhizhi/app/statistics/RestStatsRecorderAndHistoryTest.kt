package com.zhizhi.app.statistics

import android.content.Context
import com.zhizhi.app.manager.statistics.RestStatsRecorder
import com.zhizhi.app.manager.statistics.StatisticsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RestStatsRecorderAndHistoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        RestStatsRecorder.clearAll(context)
    }

    @After
    fun tearDown() {
        RestStatsRecorder.clearAll(context)
    }

    @Test
    fun testRestSessionRecordsCompleteInformation() {
        val now = System.currentTimeMillis()

        // 1. 常规休息
        RestStatsRecorder.record(
            context = context,
            type = "NORMAL",
            typeName = "常规休息",
            reason = "常规申请休息",
            requestedMinutes = 15.0,
            actualMinutes = 15.0,
            costMinutes = 15.0,
            costCount = 1,
            balanceMinutes = 45.0,
            balanceCount = 3,
            timestamp = now - 10000
        )

        // 2. 借用休息
        RestStatsRecorder.record(
            context = context,
            type = "BORROW",
            typeName = "借用休息",
            reason = "预支未来时段额度",
            requestedMinutes = 20.0,
            actualMinutes = 20.0,
            costMinutes = 40.0,
            costCount = 2,
            balanceMinutes = 25.0,
            balanceCount = 1,
            timestamp = now - 5000
        )

        // 3. 违规惩罚
        RestStatsRecorder.record(
            context = context,
            type = "PENALTY",
            typeName = "违规惩罚",
            reason = "频繁触发防沉迷或多次锁屏扣减",
            requestedMinutes = 10.0,
            actualMinutes = 0.0,
            costMinutes = 10.0,
            costCount = 1,
            balanceMinutes = 15.0,
            balanceCount = 0,
            timestamp = now
        )

        val records = StatisticsRepository.getAllRestRecords(context)
        assertEquals(3, records.size)

        // 最新的记录排在第一位（PENALTY）
        val latest = records.first()
        assertEquals("PENALTY", latest.type)
        assertEquals("违规惩罚", latest.typeName)
        assertEquals(10.0, latest.costMinutes, 0.001)
        assertEquals(1, latest.costCount)
        assertEquals(15.0, latest.balanceMinutes, 0.001)
        assertEquals(0, latest.balanceCount)

        // 验证 recent 5 条截取
        val recents = RestStatsRecorder.getRecentRecords(context, 2)
        assertEquals(2, recents.size)

        // 验证删除单条记录
        assertTrue(StatisticsRepository.deleteRestRecord(context, latest.id))
        val afterDelete = StatisticsRepository.getAllRestRecords(context)
        assertEquals(2, afterDelete.size)
        assertFalse(afterDelete.any { it.id == latest.id })
    }

    @Test
    fun testRefundRecordIsNegativeCost() {
        val record = RestStatsRecorder.record(
            context = context,
            type = "REFUND",
            typeName = "提前退还",
            reason = "提前结束休息返还未用额度",
            requestedMinutes = 20.0,
            actualMinutes = 5.0,
            costMinutes = -15.0,
            costCount = 0,
            balanceMinutes = 60.0,
            balanceCount = 3
        )

        assertEquals("REFUND", record.type)
        assertEquals(-15.0, record.costMinutes, 0.001)
        assertEquals(60.0, record.balanceMinutes, 0.001)
    }
}
