package com.viettelex.keyboard

/**
 * Bàn phím tách đôi (Cài đặt → Giao diện → "Bàn phím tách đôi", mặc định TẮT) — hình học THUẦN,
 * song sinh iOS `SplitLayout` (iOS/Keyboard/KeyLayout.swift), pinned by SplitLayoutTests.
 *
 * Như split keyboard của Apple (đo stock iPhone gập mở 06/10/2026, view 801 pt): mỗi nửa ≈ 38%
 * bề ngang, khe giữa rộng là vùng chết. Hàng 2 nửa phải bắt đầu bằng G, hàng 3 bằng V (lặp G, V
 * để ngón phải với tới). Chỉ tách khi bàn phím rộng ≥ [MIN_WIDTH_DP] dp (Galaxy Z Fold / Pixel
 * Fold mở, điện thoại xoay ngang, máy tính bảng) — điện thoại dọc / màn ngoài máy gập không bao giờ.
 * Đơn vị: iOS pt ≡ Android dp (cùng ~160 điểm/inch), nên ngưỡng và trần giữ nguyên số.
 */
object SplitLayout {
    /** Bề ngang bàn phím tối thiểu (dp) để tách — điện thoại dọc ≤ ~480 dp, màn ngoài Fold ~ 360–420. */
    const val MIN_WIDTH_DP = 600f

    /** Bật + đủ rộng. Công tắc tắt ⇒ false ngay (một phép AND, 0 chi phí). */
    fun active(setting: Boolean, widthDp: Float): Boolean = setting && widthDp >= MIN_WIDTH_DP

    /** Mỗi nửa chiếm phần này của bề ngang (stock 303/801). */
    const val HALF_FRACTION = 0.38f

    /** Trần bước phím (dp) — máy tính bảng ngang rất rộng: phím không phình quá cỡ ngón cái. */
    const val MAX_PITCH_DP = 62f

    /**
     * [pitch] bước phím chữ (tâm → tâm), [keyWidth] bề rộng phím chữ, [half] bề rộng MỖI nửa =
     * 5.5 bước − khe (5 phím + nửa phím thụt). Cùng đơn vị với tham số của [metrics] (px hoặc dp).
     */
    data class Metrics(val pitch: Float, val keyWidth: Float, val half: Float) {
        /** Khe giữa hai nửa (vùng chết — không phím nào nhận chạm ở giữa). */
        fun centerGap(width: Float, margin: Float): Float = width - 2 * margin - 2 * half
    }

    /**
     * [width] bề ngang bàn phím, [margin] lề hai bên, [gap] khe giữa hai phím, [maxPitch] trần bước
     * phím — tất cả cùng đơn vị (Android truyền px: maxPitch = [MAX_PITCH_DP] × density).
     */
    fun metrics(width: Float, margin: Float, gap: Float, maxPitch: Float = MAX_PITCH_DP): Metrics {
        val pitch = minOf(maxPitch, (width * HALF_FRACTION - margin + gap) / 5.5f)
        return Metrics(pitch, pitch - gap, 5.5f * pitch - gap)
    }

    /** Phím chữ mỗi hàng (trái, phải) — G và V lặp ở nửa phải như Apple. */
    val letterRows: List<Pair<String, String>> = listOf(
        "qwert" to "yuiop", "asdfg" to "ghjkl", "zxcv" to "vbnm",
    )

    /** Lề trong (đơn vị bước phím) của một nửa hàng: bên trái [l], bên phải [r]. */
    data class Indent(val l: Float, val r: Float)

    /**
     * Lề trong (trái-nửa, phải-nửa) mỗi hàng chữ: hàng 1 nửa trái chừa nửa phím bên phải (thẳng cột
     * T), nửa phải chừa bên trái (Y thụt sau G); hàng 2 ngược lại; hàng 3 không thụt (⇧ / ⌫ co giãn).
     */
    val letterIndents: List<Pair<Indent, Indent>> = listOf(
        Indent(0f, 0.5f) to Indent(0.5f, 0f),
        Indent(0.5f, 0f) to Indent(0f, 0.5f),
        Indent(0f, 0f) to Indent(0f, 0f),
    )

    /** Chia một hàng (số / ký hiệu) làm hai: nửa trái nhận phần lẻ (10 ⇒ 5 | 5, 7 ⇒ 4 | 3). */
    fun <T> halves(row: List<T>): Pair<List<T>, List<T>> {
        val k = (row.size + 1) / 2
        return row.subList(0, k) to row.subList(k, row.size)
    }
}
