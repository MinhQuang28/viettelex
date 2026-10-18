package com.viettelex.keyboard

/**
 * "Chiều cao thanh gợi ý" (Phil 18/10/2026) — THUẦN, song sinh iOS/Shared/SuggestionBarHeight.swift.
 * Khoá [Keys.SUGGESTION_BAR_HEIGHT] (String, cùng iOS): "low" (Thấp) / "medium" (Vừa, MẶC ĐỊNH) /
 * "high" (Cao); giá trị lạ / thiếu ⇒ Vừa. Cài đặt: Tính Năng → Giao diện → Bàn phím (cạnh
 * "Chiều cao hàng phím").
 *
 * iOS danh nghĩa 30/36/44 pt; Android giữ đúng TỈ LỆ đó quanh strip mặc định 42 dp (đã thoải mái
 * kiểu Gboard từ #112): Thấp 35 · Vừa 42 (= như trước) · Cao 51 dp. Chỉ strip MỞ đổi — thu gọn
 * 14 dp, tắt 0 giữ nguyên; tổng bàn phím tăng/giảm đúng phần chênh. Điện thoại NGANG: kẹp ở Vừa
 * (màn thấp). Cỡ chữ gợi ý nhích theo mức (16/17/18 sp). Đọc lúc dựng layout — 0 chi phí mỗi phím.
 */
enum class SuggestionBarHeight(val id: String, val openStripDp: Float, val wordSp: Float) {
    LOW("low", 35f, 16f), MEDIUM("medium", 42f, 17f), HIGH("high", 51f, 18f);

    /** Strip mở (dp) — [landscapeCap]: điện thoại ngang ⇒ không cao quá [MEDIUM]. */
    fun openStripDp(landscapeCap: Boolean): Float = effective(landscapeCap).openStripDp

    /** Cỡ chữ gợi ý (sp) theo mức hiệu dụng. */
    fun wordSp(landscapeCap: Boolean): Float = effective(landscapeCap).wordSp

    /** Mức thật dùng: ngang kẹp ở [MEDIUM]. */
    fun effective(landscapeCap: Boolean): SuggestionBarHeight =
        if (landscapeCap && ordinal > MEDIUM.ordinal) MEDIUM else this

    companion object {
        val DEFAULT = MEDIUM
        val IDS: Set<String> = entries.map { it.id }.toSet()
        /** Giá trị lạ / thiếu ⇒ [DEFAULT]. */
        fun of(raw: Any?): SuggestionBarHeight = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}
