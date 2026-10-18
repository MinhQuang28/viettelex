package com.viettelex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bàn phím tách đôi — hình học THUẦN, song sinh iOS SplitKeyboardTests (phần "Hàm thuần"): cùng
 * ngưỡng, cùng số đo stock (pt iOS ≡ dp Android), G/V lặp. Phần dựng phím + chạm: app SplitKeyboardTest.
 */
class SplitLayoutTests {
    @Test fun onlyWhenWideAndEnabled() {
        // Điện thoại dọc / màn ngoài máy gập (Z Fold ~ 360–420 dp, Pixel Fold ngoài ~ 410 dp).
        for (w in listOf(360f, 375f, 402f, 411f, 440f, 466f, 599f)) {
            assertFalse("$w", SplitLayout.active(true, w))
        }
        // Gập mở / điện thoại ngang / máy tính bảng.
        for (w in listOf(600f, 669f, 750f, 801f, 834f, 1194f)) {
            assertTrue("$w", SplitLayout.active(true, w))
            assertFalse("tắt: $w", SplitLayout.active(false, w))
        }
    }

    @Test fun metricsMatchStockProportions() {
        // Số đo iOS (view 801, lề 6.5, khe 6): stock nửa 303, bước 57 — cùng kết quả iOS.
        val m = SplitLayout.metrics(801f, 6.5f, 6f)
        assertEquals(55.25f, m.pitch, 0.1f)
        assertEquals(m.pitch - 6f, m.keyWidth, 0.001f)
        assertEquals(5.5f * m.pitch - 6f, m.half, 0.001f)
        assertEquals(801f * SplitLayout.HALF_FRACTION - 6.5f, m.half, 0.5f)
        assertTrue("khe giữa rộng như stock", m.centerGap(801f, 6.5f) > 3 * m.pitch)
        // Máy tính bảng ngang rất rộng: bước phím có trần, nửa không phình.
        assertEquals(SplitLayout.MAX_PITCH_DP, SplitLayout.metrics(1366f, 3f, 10f).pitch, 0f)
        // Bề ngang tối thiểu: phím vẫn ≥ phím iPhone dọc (33).
        assertTrue(SplitLayout.metrics(600f, 6.5f, 6f).keyWidth > 33f)
        // Lề / khe Android (3 / 5 dp): phím ≥ phím điện thoại dọc 360 dp (~31 dp).
        assertTrue(SplitLayout.metrics(600f, 3f, 5f).keyWidth > 33f)
        // Trần tính theo đơn vị người gọi (px): density 2.625 cho cùng hình học dp × 2.625.
        val px = SplitLayout.metrics(801f * 2.625f, 3f * 2.625f, 5f * 2.625f, SplitLayout.MAX_PITCH_DP * 2.625f)
        val dp = SplitLayout.metrics(801f, 3f, 5f)
        assertEquals(dp.pitch * 2.625f, px.pitch, 0.01f)
        assertEquals(dp.half * 2.625f, px.half, 0.01f)
    }

    @Test fun letterRowsFollowAppleDuplicates() {
        val rows = SplitLayout.letterRows
        assertEquals(listOf("qwert", "asdfg", "zxcv"), rows.map { it.first })
        assertEquals(listOf("yuiop", "ghjkl", "vbnm"), rows.map { it.second })
        val all = rows.flatMap { (it.first + it.second).toList() }.toSet()
        assertEquals(26, all.size)
        val both = rows.flatMap { it.first.toList() }.toSet()
            .intersect(rows.flatMap { it.second.toList() }.toSet())
        assertEquals("Apple lặp G và V ở nửa phải", setOf('g', 'v'), both)
        val h = SplitLayout.halves("1234567890".toList())
        assertEquals("12345", h.first.joinToString("")); assertEquals("67890", h.second.joinToString(""))
        val odd = SplitLayout.halves(listOf(1, 2, 3, 4, 5, 6, 7))
        assertEquals(listOf(1, 2, 3, 4), odd.first); assertEquals(listOf(5, 6, 7), odd.second)
    }

    /** Hàng chữ: phím + lề trong của mỗi nửa vừa khít bề rộng nửa (không tràn vào khe giữa). */
    @Test fun indentsFillHalf() {
        for (i in 0 until 2) {
            val (l, r) = SplitLayout.letterRows[i]
            val (il, ir) = SplitLayout.letterIndents[i]
            assertEquals(5.5f, l.length + il.l + il.r, 0f)
            assertEquals(5.5f, r.length + ir.l + ir.r, 0f)
        }
    }

    @Test fun settingDefaultOffAndBackedUp() {
        assertFalse(KeyboardSettings().splitKeyboard)
        assertFalse(KeyboardSettings.load { null }.splitKeyboard)
        assertTrue(KeyboardSettings.load { if (it == Keys.SPLIT_KEYBOARD) true else null }.splitKeyboard)
        assertEquals("splitKeyboard", Keys.SPLIT_KEYBOARD)   // cùng khoá iOS App Group
        assertTrue(BackupSettings.byKey.containsKey(Keys.SPLIT_KEYBOARD))
    }
}
