package com.viettelex.keyboard

/**
 * "Số từ gợi ý" (Minh Trí 10/2026) — THUẦN, song sinh iOS/Shared/SuggestionCount.swift.
 * Khoá [Keys.SUGGESTION_COUNT] (Int): 0 = Tự động (như trước: 3 ô, cả điện thoại lẫn máy tính
 * bảng), 1…5 = đúng chừng ấy ô. Cài đặt: Tính Năng → Gợi ý (FeaturePages, nút − / +).
 * Bàn phím: [KeyboardSession] xin [resolved] ứng viên, StripView hiện [fit] ô — ô hẹp hơn
 * [MIN_SLOT_DP] (bar chật: máy nhỏ + nút 😊/📋/icon con trỏ) thì bớt ô cho vừa, tối thiểu 1.
 */
object SuggestionCount {
    /** 0 = Tự động. */
    const val AUTO = 0
    const val MAX = 5
    val RANGE = 0..MAX
    /** Số ô ở chế độ Tự động — hành vi trước khi có cài đặt. */
    const val AUTO_SLOTS = 3
    /** Bề ngang tối thiểu một ô (dp, ~vùng chạm 44 dp); bar không đủ ⇒ bớt ô. */
    const val MIN_SLOT_DP = 44f

    /** Giá trị thô (prefs / sao lưu) → 0…5; sai kiểu ⇒ Tự động, ngoài phạm vi ⇒ kẹp. */
    fun parse(raw: Any?): Int = when (raw) {
        is Boolean -> AUTO
        is Number -> raw.toInt().coerceIn(0, MAX)
        is String -> raw.toIntOrNull()?.coerceIn(0, MAX) ?: AUTO
        else -> AUTO
    }

    /** Số ô muốn hiện (chưa xét bề rộng): Tự động ⇒ [AUTO_SLOTS]. */
    fun resolved(setting: Int): Int = if (setting <= 0) AUTO_SLOTS else minOf(setting, MAX)

    /**
     * Số ô thật sự hiện trên bar rộng [barWidth] (cùng đơn vị với [minWidth]): không quá [resolved],
     * mỗi ô ≥ [minWidth], tối thiểu 1. Bề rộng ≤ 0 (chưa đo) ⇒ [resolved].
     */
    fun fit(setting: Int, barWidth: Float, minWidth: Float = MIN_SLOT_DP): Int {
        val want = resolved(setting)
        if (barWidth <= 0f || minWidth <= 0f) return want
        return (barWidth / minWidth).toInt().coerceIn(1, want)
    }

    /** Nút − / +: Tự động → 1 → … → 5 (− ở 1 về Tự động; dừng ở hai đầu). */
    fun step(v: Int, by: Int): Int = (v + by).coerceIn(0, MAX)
}
