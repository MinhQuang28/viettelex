package com.viettelex.keyboard

/**
 * Lưu "Tuỳ chỉnh nâng cao" theo theme: MỘT pref [KEY] = chuỗi JSON gọn, khoá sắp xếp — y hệt iOS
 * KeyStyleStore (iOS/Keyboard/KeyboardTheme.swift); sao lưu / đồng bộ chuyển nguyên chuỗi giữa hai
 * nền tảng:
 *   {"pink":{"accent":14239104,"corner":70},"system":{"fill":1056816,"font":"mono","label":16723285}}
 * Trường (tuỳ chọn, thiếu = mặc định): label / fill / accent = 0xRRGGBB (thập phân), corner 0…100,
 * fontScale 80…150, font = [KeyLabelFont.id], weight = [KeyLabelWeight.id]. Theme chưa chỉnh không có
 * mục; trống = "{}". Giá trị sai bị bỏ / kẹp như khoá cũ.
 */
object KeyStyleStore {
    const val KEY = Keys.KEY_STYLES
    const val EMPTY = "{}"
    /** Màu nhấn của "Trắng hồng" (đã bỏ) — người dùng theme đó chuyển sang Hệ thống + màu này. */
    const val PURE_WHITE_ACCENT = 0xD6336C
    const val REMOVED_PURE_WHITE = "pureWhite"
    /** Khoá chung CŨ (≤ 1.5.12) — chỉ để chuyển đổi; app xoá sau khi chuyển. */
    val LEGACY_KEYS = listOf(Keys.KEY_LABEL_COLOR, Keys.KEY_FILL_COLOR, Keys.KEY_CORNER_RADIUS, Keys.KEY_FONT_SCALE,
        Keys.KEY_LABEL_FONT, Keys.KEY_LABEL_WEIGHT)

    /** Id theme hợp lệ để lưu: ASCII chữ/số/_/-, ≤ 40 ký tự (iOS cùng luật). */
    fun validId(s: String): Boolean = s.isNotEmpty() && s.length <= 40 &&
        s.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '_' || it == '-' }

    private fun intOf(v: Any?): Int? = when (v) {
        is Long -> v.takeIf { it in -1_000_000_000L..1_000_000_000L }?.toInt()
        is Int -> v
        is Double -> v.takeIf { it.isFinite() && it == Math.rint(it) && Math.abs(it) < 1e9 }?.toInt()
        else -> null
    }

    private fun parseObject(raw: String?): Map<*, *>? {
        if (raw == null) return null
        return try { MiniJson.parse(raw) as? Map<*, *> } catch (e: IllegalArgumentException) { null }
    }

    fun parse(raw: String?): Map<String, KeyStyle> {
        if (raw == null || raw == EMPTY) return emptyMap()
        val obj = parseObject(raw) ?: return emptyMap()
        val out = LinkedHashMap<String, KeyStyle>()
        for ((id, v) in obj) {
            if (id !is String || !validId(id) || v !is Map<*, *>) continue
            val st = KeyStyle(
                labelColor = KeyStyle.parseColor(intOf(v["label"])),
                fillColor = KeyStyle.parseColor(intOf(v["fill"])),
                accentColor = KeyStyle.parseColor(intOf(v["accent"])),
                corner = KeyStyle.parseCorner(intOf(v["corner"])),
                fontScale = KeyStyle.parseFontScale(intOf(v["fontScale"])),
                font = KeyLabelFont.of(v["font"] as? String),
                weight = KeyLabelWeight.of(v["weight"] as? String),
            )
            if (!st.isDefault) out[id] = st
        }
        return out
    }

    /** Chuỗi CHUẨN (khoá sắp xếp, không khoảng trắng) — trùng từng ký tự với iOS. */
    fun serialize(m: Map<String, KeyStyle>): String {
        val parts = ArrayList<String>()
        for (id in m.keys.sorted()) {
            val st = m[id] ?: continue
            if (!validId(id) || st.isDefault) continue
            val f = ArrayList<String>()
            st.accentColor?.let { f.add("\"accent\":$it") }
            st.corner?.let { f.add("\"corner\":$it") }
            st.fillColor?.let { f.add("\"fill\":$it") }
            if (st.font != KeyLabelFont.DEFAULT) f.add("\"font\":\"${st.font.id}\"")
            st.fontScale?.let { f.add("\"fontScale\":$it") }
            st.labelColor?.let { f.add("\"label\":$it") }
            if (st.weight != KeyLabelWeight.REGULAR) f.add("\"weight\":\"${st.weight.id}\"")
            parts.add("\"$id\":{" + f.joinToString(",") + "}")
        }
        return "{" + parts.joinToString(",") + "}"
    }

    /** Chuẩn hoá chuỗi từ file sao lưu / đồng bộ; null = không phải object JSON (bỏ qua). */
    fun normalize(raw: String): String? = parseObject(raw)?.let { serialize(parse(raw)) }

    fun hasLegacy(get: (String) -> Any?): Boolean = LEGACY_KEYS.any { get(it) != null }

    /** Bộ chung cũ; null = không có khoá cũ nào. */
    fun loadLegacy(get: (String) -> Any?): KeyStyle? = if (hasLegacy(get)) KeyStyle.loadLegacy(get) else null

    fun needsMigration(theme: String?, hasLegacy: Boolean) = hasLegacy || theme == REMOVED_PURE_WHITE

    data class Migrated(val theme: String?, val styles: Map<String, KeyStyle>)

    /**
     * Chuyển đổi THUẦN (iOS cùng quy tắc), áp lại lên kết quả của chính nó không đổi gì:
     * 1. theme "pureWhite" (đã bỏ, bản 1.5.12) ⇒ "system"; "system" chưa có màu nhấn ⇒ màu nhấn D6336C;
     * 2. bộ chung cũ (khác mặc định) ⇒ theme ĐANG CHỌN (sau bước 1; id lạ ⇒ "system"), chỉ khi theme
     *    đó chưa có mục riêng — không ghi đè thứ người dùng chỉnh sau này;
     * 3. mục "pureWhite" (nếu có) bỏ.
     */
    fun migrate(theme: String?, styles: Map<String, KeyStyle>, legacy: KeyStyle?): Migrated {
        val sys = KeyboardTheme.SYSTEM.id
        val fromPureWhite = theme == REMOVED_PURE_WHITE
        val t = if (fromPureWhite) sys else theme
        val m = LinkedHashMap(styles)
        if (legacy != null && !legacy.isDefault) {
            val id = KeyboardTheme.entries.firstOrNull { it.id == t }?.id ?: sys
            if (m[id] == null) m[id] = legacy
        }
        if (fromPureWhite) {
            val st = m[sys] ?: KeyStyle.DEFAULT
            if (st.accentColor == null) m[sys] = st.copy(accentColor = PURE_WHITE_ACCENT)
        }
        m.remove(REMOVED_PURE_WHITE)
        return Migrated(t, m)
    }
}
