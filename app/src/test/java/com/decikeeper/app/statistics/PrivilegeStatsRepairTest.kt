package com.decikeeper.app.statistics

import android.content.Context
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.statistics.PrivilegeStatsRepair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeStatsRepairTest {

    private lateinit var context: Context

    private val morning = "privilege_usage_20261006_上午"
    private val afternoon = "privilege_usage_20261006_下午"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun halvesTheDoubleSettledDay() {
        // 10/06 那次特权被结算了两遍：真实 上午 104 / 下午 91，被记成 208 / 182
        prefs().edit().putInt(morning, 208).putInt(afternoon, 182).commit()

        PrivilegeStatsRepair.repairOnce(context)

        assertEquals(104, prefs().getInt(morning, -1))
        assertEquals(91, prefs().getInt(afternoon, -1))
    }

    @Test
    fun runsOnlyOnce() {
        prefs().edit().putInt(morning, 208).putInt(afternoon, 182).commit()

        PrivilegeStatsRepair.repairOnce(context)
        PrivilegeStatsRepair.repairOnce(context)

        assertEquals("第二次不能再减半", 104, prefs().getInt(morning, -1))
        assertEquals(91, prefs().getInt(afternoon, -1))
    }

    @Test
    fun leavesAnyOtherValueAlone() {
        // 不是已确认翻倍的数值（例如已经被人改过 / 备份恢复成别的值）就不动
        prefs().edit()
            .putInt(morning, 104).putInt(afternoon, 150)
            .putInt("privilege_usage_20261007_上午", 152)
            .putInt("privilege_usage_20261003_下午", 91)
            .commit()

        PrivilegeStatsRepair.repairOnce(context)

        assertEquals(104, prefs().getInt(morning, -1))
        assertEquals(150, prefs().getInt(afternoon, -1))
        assertEquals("10/07 的 152 是正常的一遍", 152, prefs().getInt("privilege_usage_20261007_上午", -1))
        assertEquals(91, prefs().getInt("privilege_usage_20261003_下午", -1))
    }

    @Test
    fun doesNothingOnADeviceWithoutThatDay() {
        PrivilegeStatsRepair.repairOnce(context)

        assertFalse(prefs().contains(morning))
        assertFalse(prefs().contains(afternoon))
        assertTrue("没有可修的数据也要打上已处理标记，不每次启动都查", prefs().all.isNotEmpty())
    }
}
