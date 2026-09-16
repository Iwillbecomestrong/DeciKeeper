package com.decikeeper.app.manager

import com.decikeeper.app.ui.resolveTemporaryAccessQuotaPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryAccessQuotaPresentationTest {

    @Test
    fun testGapPeriodPresentsFreeHintAndGreenColor() {
        val presentation = resolveTemporaryAccessQuotaPresentation(
            isInDndSegment = false,
            isInRest = false,
            availMinsFormatted = "0",
            availCountFormatted = "0",
            borrowableMinsFormatted = "2.5",
            borrowableCountFormatted = "1"
        )

        assertTrue(presentation.isFreeScenario)
        assertEquals("#2E7D32", presentation.hintColorHex)
        assertTrue(presentation.hintText.contains("自由空档期"))
        assertTrue(presentation.hintText.contains("免扣额度"))
        assertTrue(presentation.hintText.contains("不计入预支"))
        assertTrue(presentation.defaultDetailText.contains("空档期"))
        assertTrue(presentation.defaultDetailText.contains("免费"))
    }

    @Test
    fun testRestPeriodPresentsFreeHintAndGreenColor() {
        val presentation = resolveTemporaryAccessQuotaPresentation(
            isInDndSegment = true,
            isInRest = true,
            availMinsFormatted = "10",
            availCountFormatted = "2",
            borrowableMinsFormatted = "2.5",
            borrowableCountFormatted = "1"
        )

        assertTrue(presentation.isFreeScenario)
        assertEquals("#2E7D32", presentation.hintColorHex)
        assertTrue(presentation.hintText.contains("休息时段"))
        assertTrue(presentation.hintText.contains("免扣额度"))
        assertTrue(presentation.defaultDetailText.contains("休息中"))
        assertTrue(presentation.defaultDetailText.contains("免费"))
    }

    @Test
    fun testNormalDndSegmentPresentsStandardQuotaNumbers() {
        val presentation = resolveTemporaryAccessQuotaPresentation(
            isInDndSegment = true,
            isInRest = false,
            availMinsFormatted = "5",
            availCountFormatted = "1.5",
            borrowableMinsFormatted = "2.5",
            borrowableCountFormatted = "1"
        )

        assertFalse(presentation.isFreeScenario)
        assertEquals("#666666", presentation.hintColorHex)
        assertEquals("当前可用: 5min / 1.5次 | 可借用: 2.5min / 1次", presentation.hintText)
        assertEquals("", presentation.defaultDetailText)
    }
}
