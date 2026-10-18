package com.viettelex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Chiều cao thanh gợi ý" ([SuggestionBarHeight]) — song sinh iOS SuggestionBarHeightTests.swift. */
class SuggestionBarHeightTests {
    @Test fun parseAndDefault() {
        assertEquals(SuggestionBarHeight.MEDIUM, SuggestionBarHeight.DEFAULT)
        assertEquals(SuggestionBarHeight.MEDIUM, SuggestionBarHeight.of(null))
        assertEquals(SuggestionBarHeight.LOW, SuggestionBarHeight.of("low"))
        assertEquals(SuggestionBarHeight.HIGH, SuggestionBarHeight.of("high"))
        assertEquals(SuggestionBarHeight.MEDIUM, SuggestionBarHeight.of("tall"))   // lạ ⇒ Vừa
        assertEquals(SuggestionBarHeight.MEDIUM, SuggestionBarHeight.of(2))
        assertEquals(setOf("low", "medium", "high"), SuggestionBarHeight.IDS)       // cùng chuỗi iOS
        assertEquals(SuggestionBarHeight.MEDIUM, KeyboardSettings.load { null }.suggestionBarHeight)
        assertEquals(SuggestionBarHeight.HIGH,
            KeyboardSettings.load { if (it == Keys.SUGGESTION_BAR_HEIGHT) "high" else null }.suggestionBarHeight)
        assertEquals(SuggestionBarHeight.MEDIUM,
            KeyboardSettings.load { if (it == Keys.SUGGESTION_BAR_HEIGHT) "huge" else null }.suggestionBarHeight)
    }

    /** Vừa = strip cũ 42 dp; Thấp/Cao giữ tỉ lệ iOS 30/36/44 (±0.5 dp). */
    @Test fun proportionalToIOS() {
        assertEquals(42f, SuggestionBarHeight.MEDIUM.openStripDp, 0f)
        val ios = mapOf(SuggestionBarHeight.LOW to 30f, SuggestionBarHeight.MEDIUM to 36f, SuggestionBarHeight.HIGH to 44f)
        for ((h, pt) in ios) assertEquals("$h", 42f * pt / 36f, h.openStripDp, 0.5f)
        assertEquals(listOf(16f, 17f, 18f), SuggestionBarHeight.entries.map { it.wordSp })
        assertTrue(SuggestionBarHeight.entries.zipWithNext().all { (a, b) -> a.openStripDp < b.openStripDp })
    }

    @Test fun landscapeCapsAtMedium() {
        assertEquals(listOf(35f, 42f, 42f), SuggestionBarHeight.entries.map { it.openStripDp(landscapeCap = true) })
        assertEquals(listOf(35f, 42f, 51f), SuggestionBarHeight.entries.map { it.openStripDp(landscapeCap = false) })
        assertEquals(17f, SuggestionBarHeight.HIGH.wordSp(landscapeCap = true), 0f)
    }
}
