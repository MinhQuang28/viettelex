package com.viettelex.keyboard

/**
 * Viền phím cầu vồng — theme "Gaming RGB" (slot "borderFx":"rainbow"). Hằng số + hàm thuần chung iOS
 * (iOS/Keyboard/RainbowBorder.swift, cùng số — test chéo). Vẽ: app/.../ime/RainbowBorderView (MỘT dải gradient che
 * bằng viền mọi phím, chỉ trượt khi đang gõ).
 */
object RainbowBorder {
    /** Một chu kỳ cầu vồng trượt hết một bề ngang bàn phím (giây). */
    const val PERIOD = 4.0
    /** Nghỉ gõ chừng này ⇒ đứng yên (giây). */
    const val IDLE_AFTER = 1.2
    /** Độ dày viền (dp). */
    const val LINE_WIDTH = 1.5
    /** Một chu kỳ màu (RGB); dải gradient lặp HAI lần để dịch đúng một bề ngang là liền mạch. */
    val HUES = intArrayOf(0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x00C7BE, 0x007AFF, 0xAF52DE, 0xFF3B30)

    /** Màu (RGB) + vị trí (0…1) của dải 2 chu kỳ — 15 điểm, y hệt iOS stops(). */
    fun stops(): Pair<IntArray, DoubleArray> {
        val n = HUES.size - 1
        val colors = ArrayList<Int>(); val pos = ArrayList<Double>()
        for (cycle in 0 until 2) for (i in 0 until n) { colors.add(HUES[i]); pos.add((cycle + i.toDouble() / n) / 2) }
        colors.add(HUES[n]); pos.add(1.0)
        return colors.toIntArray() to pos.toDoubleArray()
    }

    /** Độ lệch (0…1 bề ngang) sau [elapsed] giây chạy kể từ lệch [from] — tuần hoàn, luôn trong [0, 1). */
    fun offset(from: Double, elapsed: Double): Double {
        val v = (from + elapsed / PERIOD) % 1.0
        return if (v < 0) v + 1 else v
    }
}

/** Đồng hồ "chỉ chạy khi đang gõ" (thuần; giây) — iOS RainbowClock y hệt. */
class RainbowClock(var idleAfter: Double = RainbowBorder.IDLE_AFTER) {
    var running = false
        private set
    var lastInput = Double.NEGATIVE_INFINITY
        private set

    /** Một phím lúc [t]; true = vừa chuyển nghỉ → chạy. */
    fun poke(t: Double): Boolean {
        lastInput = t
        if (running) return false
        running = true
        return true
    }

    /** Còn bao lâu nữa thì nghỉ (≤ 0 ⇒ nghỉ ngay). */
    fun idleRemaining(t: Double): Double = idleAfter - (t - lastInput)

    fun stop() { running = false }
}
