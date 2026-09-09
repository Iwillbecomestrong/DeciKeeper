package com.zhizhi.app

import com.zhizhi.app.ui.HomeFragment
import com.zhizhi.app.ui.HomeFragment.HomeStatusPillModel
import com.zhizhi.app.ui.HomeFragment.HomeSystemStatusType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStatusPresentationTest {

    @Test
    fun restingStateHidesPill() {
        val model = HomeFragment.resolveHomeStatusPillModel(
            isResting = true,
            restRemainSeconds = 300L,
            tempAccessRemainSeconds = 0L,
            tempCooldownRemainSeconds = 0L,
            restCooldownRemainSeconds = 0L,
            isDndSegment = true
        )
        assertEquals(HomeSystemStatusType.RESTING, model.statusType)
        assertFalse("休息中时胶囊条应隐藏，由下方操作大卡片接管", model.isVisible)
    }

    @Test
    fun temporaryAccessActiveShowsActivePill() {
        val model = HomeFragment.resolveHomeStatusPillModel(
            isResting = false,
            restRemainSeconds = 0L,
            tempAccessRemainSeconds = 125L, // 2min 5s
            tempCooldownRemainSeconds = 0L,
            restCooldownRemainSeconds = 0L,
            isDndSegment = true
        )
        assertEquals(HomeSystemStatusType.TEMP_ACCESS_ACTIVE, model.statusType)
        assertTrue(model.isVisible)
        assertEquals("🟡 临时解锁中 还剩 2min 5s", model.text)
        assertEquals(R.attr.zzWarning, model.colorAttrId)
    }

    @Test
    fun temporaryAccessCooldownPreferredWhenGreaterOrEqual() {
        val model = HomeFragment.resolveHomeStatusPillModel(
            isResting = false,
            restRemainSeconds = 0L,
            tempAccessRemainSeconds = 0L,
            tempCooldownRemainSeconds = 55L,
            restCooldownRemainSeconds = 20L,
            isDndSegment = true
        )
        assertEquals(HomeSystemStatusType.COOLDOWN, model.statusType)
        assertTrue(model.isVisible)
        assertEquals("🔒 临时解锁冷却 还剩 55s", model.text)
        assertEquals(R.attr.zzWarning, model.colorAttrId)
    }

    @Test
    fun restCooldownShownWhenRestCooldownGreater() {
        val model = HomeFragment.resolveHomeStatusPillModel(
            isResting = false,
            restRemainSeconds = 0L,
            tempAccessRemainSeconds = 0L,
            tempCooldownRemainSeconds = 10L,
            restCooldownRemainSeconds = 45L,
            isDndSegment = false
        )
        assertEquals(HomeSystemStatusType.COOLDOWN, model.statusType)
        assertTrue(model.isVisible)
        assertEquals("⏳ 休息冷却中 还剩 45s", model.text)
        assertEquals(R.attr.zzWarning, model.colorAttrId)
    }

    @Test
    fun dndActiveShownWhenInDndSegmentWithoutCooldown() {
        val model = HomeFragment.resolveHomeStatusPillModel(
            isResting = false,
            restRemainSeconds = 0L,
            tempAccessRemainSeconds = 0L,
            tempCooldownRemainSeconds = 0L,
            restCooldownRemainSeconds = 0L,
            isDndSegment = true
        )
        assertEquals(HomeSystemStatusType.DND_ACTIVE, model.statusType)
        assertTrue(model.isVisible)
        assertEquals("🔒 勿扰中", model.text)
        assertEquals(R.attr.zzMuted, model.colorAttrId)
    }

    @Test
    fun gapShownWhenNotInDndSegmentWithoutCooldown() {
        val model = HomeFragment.resolveHomeStatusPillModel(
            isResting = false,
            restRemainSeconds = 0L,
            tempAccessRemainSeconds = 0L,
            tempCooldownRemainSeconds = 0L,
            restCooldownRemainSeconds = 0L,
            isDndSegment = false
        )
        assertEquals(HomeSystemStatusType.GAP, model.statusType)
        assertTrue(model.isVisible)
        assertEquals("🍃 空档期", model.text)
        assertEquals(R.attr.zzSuccess, model.colorAttrId)
    }
}
