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

    private fun assertCovers(x: Double, msg: String) {
        val (lo, hi) = RainbowBorder.coverage(x)
        assertTrue("$msg: x=$x lo=$lo", lo <= 1e-9)
        assertTrue("$msg: x=$x hi=$hi", hi >= 1 - 1e-9)
    }

    /**
     * Phủ kín (bug iOS "tắt đèn từ phía phải qua", Phil 19/10/2026): dải 2W ở x = −offset·W phải phủ [0, W] ở mọi
     * độ lệch, qua nhiều chu kỳ, nhiều phiên gõ / nghỉ / gõ lại giữa lúc dừng dần. Bề ngang chỉ nhân tỉ lệ (offset
     * tính theo W) — kiểm thêm px thật cho vài bề ngang (dọc / ngang / tablet).
     */
    @Test fun coverageAtAnyOffsetOverManyCycles() {
        assertTrue("dải ≥ W + một chu kỳ", RainbowBorder.STRIP_PERIODS >= 2)
        assertTrue("công thức không lấy dư hở mép phải", RainbowBorder.coverage(0.5 + 3 / RainbowBorder.PERIOD).second < 1)
        for (i in 0..2000) assertCovers(RainbowBorder.wrap(i * 0.0173 - 7), "wrap $i")
        assertEquals(0.75, RainbowBorder.wrap(-0.25), 1e-12)
        val d = RainbowDriver(idleAfter = 0.5)
        var t = 0.0
        val pattern = listOf(3.1 to 1.7, 0.3 to 0.4, 7.9 to 2.5, 1.0 to 0.05, 13.3 to 0.9, 0.2 to 3.0)
        for (round in 0 until 20) for ((typing, idle) in pattern) {
            val end = t + typing
            while (t < end) { d.poke(t); d.frame(t); assertCovers(d.offset(t), "gõ r$round"); t += 0.02 }
            val stop = t + idle
            while (t < stop) {
                d.frame(t)
                val x = d.offset(t)
                assertCovers(x, "nghỉ r$round")
                for (w in intArrayOf(360, 411, 800, 914, 1280, 2560)) {
                    val tx = -x * w
                    assertTrue("px W=$w tx=$tx", tx <= 0 && tx + RainbowBorder.STRIP_PERIODS * w >= w)
                }
                t += 0.02
            }
        }
        assertTrue("qua nhiều chu kỳ", t / RainbowBorder.PERIOD > 50)
    }

    @Test fun motionDeceleratesAndAcceleratesSmoothly() {
        val v = RainbowMotion.FULL_SPEED
        val run = RainbowMotion.rest(0.3).resumed(10.0)
        assertEquals(RainbowBorder.ACCEL_TIME, run.ramp, 1e-12)
        assertEquals(v / 2, run.velocity(10.2), 1e-12); assertEquals(v, run.velocity(20.0), 0.0)
        val stop = run.settled(20.0)
        assertEquals("dừng dần ~1 s", RainbowBorder.DECEL_TIME, stop.ramp, 1e-12)
        assertEquals("tốc độ liền", v, stop.velocity(20.0), 1e-12)
        assertEquals(0.0, stop.velocity(21.0), 0.0)
        assertEquals("đứng yên", stop.offset(21.0), stop.offset(99.0), 1e-12)
        assertEquals("vị trí liền", run.offset(20.0), stop.offset(20.0), 1e-12)
        val again = stop.resumed(20.5)
        assertEquals(RainbowBorder.ACCEL_TIME / 2, again.ramp, 1e-12)
        assertEquals(v / 2, again.velocity(20.5), 1e-12)
        var prev = stop.travelled(20.0)
        for (k in 1..200) { val u = stop.travelled(20 + k * 0.01); assertTrue("không chạy lùi", u >= prev); prev = u }
    }

    @Test fun fadeEasesToRestGlowAndBack() {
        assertEquals(0.58, RainbowBorder.REST_ALPHA, 0.0)
        assertEquals(1.0, RainbowBorder.FADE_OUT_TIME, 0.0); assertEquals(0.2, RainbowBorder.FADE_IN_TIME, 0.0)
        val on = RainbowFade().toward(1.0, 5.0, RainbowBorder.FADE_IN_TIME)
        assertEquals(RainbowBorder.REST_ALPHA, on.alpha(5.0), 1e-12); assertEquals(1.0, on.alpha(5.2), 0.0)
        val off = on.toward(RainbowBorder.REST_ALPHA, 9.0, RainbowBorder.FADE_OUT_TIME)
        assertEquals("mờ dần, không cắt", (1 + RainbowBorder.REST_ALPHA) / 2, off.alpha(9.5), 1e-12)
        assertEquals(RainbowBorder.REST_ALPHA, off.alpha(10.0), 0.0)
        var prev = 1.0
        for (k in 0..100) { val a = off.alpha(9 + k * 0.01); assertTrue(a <= prev + 1e-12); prev = a }
    }

    /**
     * Phiên thật trên CÙNG một driver (như RainbowBorderView, khung 60 Hz): gõ ⇒ chạy + sáng 100%; nghỉ ⇒ vẫn hẹn
     * khung trong lúc giảm tốc + mờ (~1 s) rồi THÔI (0 khung khi nghỉ), đứng yên ở ánh nghỉ; gõ lại ⇒ sáng lại.
     */
    @Test fun idleDeceleratesAndFadesThenNoFrames() {
        val d = RainbowDriver()
        assertEquals("mới hiện: ánh nghỉ", RainbowBorder.REST_ALPHA, d.alpha(0.0), 0.0)
        var t = 100.0
        assertTrue(d.poke(t))
        assertFalse("đang chạy ⇒ chỉ ghi giờ", d.poke(t + 0.1))
        var frames = 0
        while (d.frame(t)) { frames++; t += 1 / 60.0; if (frames > 10_000) break }
        val lastKey = 100.1
        // Khung cuối = hết nghỉ (1,2 s) + dừng/mờ (1 s).
        assertEquals(lastKey + RainbowBorder.IDLE_AFTER + RainbowBorder.DECEL_TIME, t, 2 / 60.0)
        assertFalse(d.running)
        assertEquals(RainbowBorder.REST_ALPHA, d.alpha(t), 0.0)
        val rest = d.offset(t)
        assertTrue("đã trượt", rest > 0)
        assertFalse("nghỉ ⇒ không hẹn khung", d.frame(t + 5))
        assertEquals("đứng yên", rest, d.offset(t + 5), 1e-12)
        // Trong lúc dừng dần: độ sáng giảm dần (không cắt), tốc độ giảm dần.
        val s = lastKey + RainbowBorder.IDLE_AFTER
        assertEquals((1 + RainbowBorder.REST_ALPHA) / 2, d.fade.alpha(s + 0.5), 0.02)
        assertEquals(RainbowMotion.FULL_SPEED / 2, d.motion.velocity(s + 0.5), 0.01)
        // Gõ lại: từ đúng chỗ đứng, sáng 100% sau 0,2 s.
        t += 5
        assertTrue(d.poke(t))
        assertEquals(rest, d.offset(t), 1e-12)
        assertEquals(1.0, d.alpha(t + RainbowBorder.FADE_IN_TIME), 0.0)
        assertTrue(d.frame(t + 0.5))
        // Ẩn giữa lúc gõ ⇒ đứng ngay, ánh nghỉ, không hẹn khung.
        d.halt(t + 1)
        assertFalse(d.frame(t + 1))
        assertEquals(RainbowBorder.REST_ALPHA, d.alpha(t + 1), 0.0)
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
        val c = RainbowDriver()
        var sink = 0
        repeat(50_000) { if (c.poke(it * 0.03)) sink++; c.frame(it * 0.03 + 3) }
        val n = 200_000
        val a0 = bean.getThreadAllocatedBytes(tid)
        val t0 = System.nanoTime()
        for (i in 0 until n) { if (c.poke(1e4 + i * 0.03)) sink++ }
        val ns = (System.nanoTime() - t0).toDouble() / n
        val bytes = bean.getThreadAllocatedBytes(tid) - a0
        // Một khung (đang chạy / dừng dần): độ lệch + alpha.
        val f0 = System.nanoTime()
        var acc = 0.0
        for (i in 0 until n) { val t = 2e4 + i / 60.0; c.frame(t); acc += c.offset(t) + c.alpha(t) }
        val frameNs = (System.nanoTime() - f0).toDouble() / n
        // Khung hình/giây khi nghỉ (sau dừng + mờ): 0.
        val idleFrames = (0 until 600).count { c.frame(1e6 + it / 60.0) }
        println("RGB-ANDROID poke/phím=%.1f ns | cấp phát %d B / %d phím | khung=%.1f ns | khung khi nghỉ=%d/10 s (%d %.0f)"
            .format(ns, bytes, n, frameNs, idleFrames, sink % 3, acc % 1))
        assertEquals("nghỉ ⇒ 0 khung hình", 0, idleFrames)
        assertTrue("không cấp phát mỗi phím: $bytes B", bytes < 1024)
        assertTrue(ns < 10_000)
    }
}
