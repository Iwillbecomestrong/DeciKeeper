package com.zhizhi.app

import com.zhizhi.app.util.ContrastHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastHelperTest {

    @Test
    fun testBlackAndWhiteContrast() {
        val contrast = ContrastHelper.calculateContrast(ContrastHelper.COLOR_WHITE, ContrastHelper.COLOR_BLACK)
        assertEquals(21.0, contrast, 0.1)
    }

    @Test
    fun testYellowBackgroundSelectsBlackText() {
        // 黄色 #FFFF00
        val yellow = -0x100 // 0xFFFFFF00
        val bestText = ContrastHelper.getOptimalTextColor(yellow)
        assertEquals("黄底必须选黑字，不能选白字", ContrastHelper.COLOR_BLACK, bestText)
        val blackContrast = ContrastHelper.calculateContrast(ContrastHelper.COLOR_BLACK, yellow)
        assertTrue("黄底黑字对比度必须 >= 4.5:1", blackContrast >= 4.5)
    }

    @Test
    fun testGreenBackgroundSelectsBlackText() {
        // 纯绿 #00FF00
        val green = -0xff0100 // 0xFF00FF00
        val bestText = ContrastHelper.getOptimalTextColor(green)
        assertEquals("绿底人眼感知亮度极高，必须选黑字", ContrastHelper.COLOR_BLACK, bestText)
    }

    @Test
    fun testDarkBlueBackgroundSelectsWhiteText() {
        // 深蓝 #000080
        val darkBlue = -0xffff80 // 0xFF000080
        val bestText = ContrastHelper.getOptimalTextColor(darkBlue)
        assertEquals("深蓝底必须选白字", ContrastHelper.COLOR_WHITE, bestText)
    }

    @Test
    fun testDarkBackgroundSelectsWhiteText() {
        // 深灰 #222222
        val darkGray = -0xddddde // 0xFF222222
        val bestText = ContrastHelper.getOptimalTextColor(darkGray)
        assertEquals("深灰底必须选白字", ContrastHelper.COLOR_WHITE, bestText)
    }
}
