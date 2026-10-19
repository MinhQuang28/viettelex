package com.viettelex.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * Viền cầu vồng (theme Gaming RGB): dải màu + đồng hồ "chỉ chạy khi đang gõ" — số tham chiếu GIỐNG HỆT iOS
 * RainbowBorderTests. Phần View (app/.../ime/RainbowBorderView) không chạy trên JVM: test các hàm thuần quyết định
 * hành vi + chi phí mỗi phím.
 */
class RainbowBorderTests {
    @Test fun stopsAndOffsetParity() {
        assertArrayEquals(intArrayOf(0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x00C7BE, 0x007AFF, 0xAF52DE, 0xFF3B30), RainbowBorder.HUES)
        val (cols, pos) = RainbowBorder.stops()
        assertEquals(15, cols.size); assertEquals(15, pos.size)
        assertEquals(0.0, pos.first(), 0.0); assertEquals(1.0, pos.last(), 0.0)
        assertEquals(0xFF3B30, cols[7]); assertEquals("chu kỳ hai bắt đầu giữa dải", 0.5, pos[7], 1e-12)
        assertEquals(3.0 / 14, pos[3], 1e-12)
        for (i in 1 until pos.size) assertTrue(pos[i] > pos[i - 1])
        for (i in 0 until 7) { assertEquals(cols[i], cols[i + 7]); assertEquals(pos[i] + 0.5, pos[i + 7], 1e-12) }
        assertEquals(0.5, RainbowBorder.offset(0.25, 1.0), 1e-12)
        assertEquals(0.1, RainbowBorder.offset(0.9, 0.8), 1e-12)
        assertEquals(0.0, RainbowBorder.offset(0.0, RainbowBorder.PERIOD * 3), 1e-12)
        assertEquals(4.0, RainbowBorder.PERIOD, 0.0); assertEquals(1.2, RainbowBorder.IDLE_AFTER, 0.0)
        assertEquals(1.5, RainbowBorder.LINE_WIDTH, 0.0)
    }

    @Test fun clockRunsOnlyWhileTyping() {
        val c = RainbowClock()
        assertFalse(c.running)
        assertTrue("phím đầu ⇒ chạy", c.poke(10.0))
        assertFalse("đang chạy ⇒ chỉ ghi giờ", c.poke(10.3))
        assertEquals(0.5, c.idleRemaining(11.0), 1e-9)
        assertTrue(c.idleRemaining(11.6) <= 0)
        c.stop()
        assertFalse(c.running)
        assertTrue(c.poke(20.0))
    }

    /** Chỉ theme có "borderFx":"rainbow" mang cờ (theme khác ⇒ IME không tạo view nào). */
    @Test fun onlyRgbThemeHasRainbow() {
        for (t in KeyboardTheme.entries) for (dk in listOf(false, true)) {
            assertEquals("$t", t == KeyboardTheme.RGB, t.palette(dk)?.rainbowBorder ?: false)
        }
    }

    /** Chi phí mỗi phím: một mốc thời gian (+ một phép so) — không cấp phát, vài chục ns. In "RGB-ANDROID". */
    @Test fun perKeyCostAllocationFree() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        val c = RainbowClock()
        var sink = 0
        repeat(50_000) { if (c.poke(it * 0.03)) sink++; if (c.idleRemaining(it * 0.03 + 2) <= 0) c.stop() }
        val n = 200_000
        val a0 = bean.getThreadAllocatedBytes(tid)
        val t0 = System.nanoTime()
        for (i in 0 until n) { if (c.poke(1e4 + i * 0.03)) sink++ }
        val ns = (System.nanoTime() - t0).toDouble() / n
        val bytes = bean.getThreadAllocatedBytes(tid) - a0
        println("RGB-ANDROID poke/phím=%.1f ns | cấp phát %d B / %d phím (%d)".format(ns, bytes, n, sink % 3))
        assertTrue("không cấp phát mỗi phím: $bytes B", bytes < 1024)
        assertTrue(ns < 10_000)
    }
}
