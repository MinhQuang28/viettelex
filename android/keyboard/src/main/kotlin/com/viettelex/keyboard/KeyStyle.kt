package com.viettelex.keyboard

/*
 * "Tuỳ chỉnh nâng cao" (Plus) ở Cài đặt → Giao diện: màu chữ / nền phím / màu nhấn, độ bo góc,
 * cỡ chữ, phông, độ đậm — RIÊNG từng theme ([ThemeSettings.keyStyles], lưu bằng [KeyStyleStore]).
 * Y hệt iOS (KeyStyle / KeyStyleStore trong iOS/Keyboard/KeyboardTheme.swift) — cùng khoá prefs,
 * cùng chuỗi JSON, cùng id phông/độ đậm ⇒ sao lưu đi qua lại được.
 *
 * Màu: MỘT màu dùng cho cả sáng lẫn tối (đơn giản, đoán được); "Theo theme" = null.
 * Áp lúc dựng theme / layout (ImeTheme, KeyboardView.applyLabelSize) — 0 chi phí mỗi phím.
 */

/** Phông nhãn phím — CHỈ phông hệ thống (không nhúng phông). Android không có "rounded" ⇒ mặc định. */
enum class KeyLabelFont(val id: String, val viTitle: String, val androidFamily: String?) {
    DEFAULT("default", "Mặc định", null), // l10n-key
    ROUNDED("rounded", "Tròn", null),            // l10n-key · chỉ iOS (SF Rounded)
    SERIF("serif", "Có chân", "serif"), // l10n-key
    MONO("mono", "Đơn cách", "monospace"), // l10n-key
    CONDENSED("condensed", "Hẹp", "sans-serif-condensed"); // l10n-key

    val title: String get() = tr(viTitle)
    /** Có trên Android (lựa chọn hiện trong app). */
    val onAndroid: Boolean get() = this != ROUNDED

    companion object {
        val IDS: Set<String> = entries.map { it.id }.toSet()
        fun of(raw: Any?): KeyLabelFont = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/** "Độ đậm" → độ đậm hệ thống (100…900). */
enum class KeyLabelWeight(val id: String, val viTitle: String, val weight: Int) {
    LIGHT("light", "Mỏng", 300), // l10n-key
    REGULAR("regular", "Thường", 400), // l10n-key
    MEDIUM("medium", "Vừa", 500), // l10n-key
    BOLD("bold", "Đậm", 700), // l10n-key
    HEAVY("heavy", "Rất đậm", 900); // l10n-key

    val title: String get() = tr(viTitle)

    companion object {
        val IDS: Set<String> = entries.map { it.id }.toSet()
        fun of(raw: Any?): KeyLabelWeight = entries.firstOrNull { it.id == raw } ?: REGULAR
    }
}

data class KeyStyle(
    /** 0xRRGGBB; null = theo theme. */
    val labelColor: Int? = null,
    val fillColor: Int? = null,
    /** "Màu nhấn": phím Nhập/hành động, ⇧ bật/Caps, vệt gõ vuốt, glyph màu nhấn; null = theo theme. */
    val accentColor: Int? = null,
    /** 0 = chữ nhật, 100 = tròn/viên thuốc (bán kính = nửa cạnh ngắn); null = mặc định. */
    val corner: Int? = null,
    /** 80…150 (% cỡ mặc định); null = mặc định (Android: theo "Cỡ chữ trên phím" Vừa/Lớn). */
    val fontScale: Int? = null,
    val font: KeyLabelFont = KeyLabelFont.DEFAULT,
    val weight: KeyLabelWeight = KeyLabelWeight.REGULAR,
) {
    val isDefault: Boolean get() = this == DEFAULT
    /** Có đổi chữ (phông / độ đậm / cỡ) ⇒ bật đo + kẹp chữ. */
    val customizesLabels: Boolean get() = font != KeyLabelFont.DEFAULT || weight != KeyLabelWeight.REGULAR || fontScale != null

    /** Chữ cái / số / dấu câu; nhãn chức năng + icon; chữ phụ góc phím — khi đặt thanh trượt. */
    val letterScale: Float get() = (fontScale ?: 100) / 100f
    val controlScale: Float get() = 1f + (letterScale - 1f) * 0.55f
    val hintScale: Float get() = 1f + (letterScale - 1f) * 0.45f

    /**
     * Màu phím theo lựa chọn (trước ảnh nền / độ trong suốt). Chữ người dùng chọn giữ nguyên;
     * chỉ đổi nền ⇒ chữ theme đổi đen/trắng nếu không đọc được. Balloon/popup đi theo.
     */
    fun applyColors(p: ThemePalette): ThemePalette {
        if (labelColor == null && fillColor == null) return p
        val c = ThemeColor
        var q = p
        fillColor?.let { rgb ->
            val f = c.rgb(rgb)
            val toward = if (c.luminance(f) > 0.4) c.BLACK else c.WHITE
            val special = c.over(c.withAlpha(toward, 0.18f), f)
            // Màu người dùng chọn phủ MỌI phím (bỏ nền phím chức năng riêng của theme).
            q = q.copy(keyFill = f, specialFill = special, balloon = f, popup = f, functionFill = null)
        }
        val behind = c.over(p.bg, KeyboardTransparency.systemBackdrop(p.isDark))
        val ink = labelColor?.let { c.rgb(it) }
            ?: KeyboardTransparency.readable(p.ink, c.over(q.keyFill, behind))
        // Chữ người dùng chọn: mọi phím cùng màu; chỉ đổi nền ⇒ chữ phím chức năng đổi nếu không đọc được.
        q = q.copy(functionInk = if (labelColor != null) null
            else p.functionInk?.let { KeyboardTransparency.readable(it, c.over(q.fnFill, behind)) })
        var balloon = q.balloon; var popup = q.popup
        val alt = if (c.luminance(ink) > 0.5) c.rgb(0x3A3A3C) else c.WHITE
        if (c.contrast(ink, c.over(balloon, c.WHITE)) < KeyboardTransparency.MIN_LABEL_CONTRAST) balloon = alt
        if (c.contrast(ink, c.over(popup, c.WHITE)) < KeyboardTransparency.MIN_LABEL_CONTRAST) popup = alt
        return q.copy(ink = ink, balloon = balloon, popup = popup, isDark = c.luminance(ink) > 0.5,
            keyLabelPinned = labelColor != null)
    }

    /**
     * "Màu nhấn" thay màu nhấn của theme ở MỌI chỗ dùng: phím Nhập/hành động ([ThemePalette.action]),
     * ⇧ bật/Caps ([ThemePalette.accentShift]), ô chọn ký tự phụ, con trỏ ô tìm emoji, vệt gõ
     * vuốt, glyph màu nhấn của theme ([ThemePalette.functionInk]). Chữ trên nền nhấn giữ màu theme nếu
     * ≥ 3:1, không thì đen/trắng ([KeyboardTransparency.readable]). Áp SAU [applyColors]. null ⇒ y như cũ.
     */
    fun applyAccent(p: ThemePalette): ThemePalette {
        val a = accentColor ?: return p
        val c = ThemeColor
        val accent = c.rgb(a)
        val fnInk = p.functionInk?.let {
            KeyboardTransparency.readable(accent, c.over(p.fnFill, c.over(p.bg, KeyboardTransparency.systemBackdrop(p.isDark))))
        }
        return p.copy(action = accent, actionInk = accentInk(p.actionInk, accent), trail = c.withAlpha(accent, 0.6f),
            accentShift = true, functionInk = fnInk)
    }

    companion object {
        val DEFAULT = KeyStyle()
        const val FONT_SCALE_MIN = 80
        const val FONT_SCALE_MAX = 150

        /** Chữ/icon trên nền nhấn: giữ [themeInk] nếu ≥ 3:1, không thì đen/trắng tương phản hơn. */
        fun accentInk(themeInk: Int, accent: Int): Int = KeyboardTransparency.readable(themeInk, accent)

        fun parseColor(v: Any?): Int? = (v as? Number)?.toInt()?.takeIf { it in 0..0xFFFFFF }
        fun parseCorner(v: Any?): Int? = (v as? Number)?.toInt()?.takeIf { it >= 0 }?.coerceAtMost(100)
        fun parseFontScale(v: Any?): Int? = (v as? Number)?.toInt()?.takeIf { it > 0 }?.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)

        /** Bộ chung CŨ (≤ 1.5.12, khoá [Keys.KEY_LABEL_COLOR]…) — chỉ để chuyển đổi ([KeyStyleStore.loadLegacy]). */
        fun loadLegacy(get: (String) -> Any?) = KeyStyle(
            labelColor = parseColor(get(Keys.KEY_LABEL_COLOR)),
            fillColor = parseColor(get(Keys.KEY_FILL_COLOR)),
            corner = parseCorner(get(Keys.KEY_CORNER_RADIUS)),
            fontScale = parseFontScale(get(Keys.KEY_FONT_SCALE)),
            font = KeyLabelFont.of(get(Keys.KEY_LABEL_FONT)),
            weight = KeyLabelWeight.of(get(Keys.KEY_LABEL_WEIGHT)),
        )

        /**
         * Bán kính bo góc phím [w]×[h]: 0% = chữ nhật, 100% = nửa cạnh ngắn (phím vuông ⇒ tròn,
         * phím dài ⇒ viên thuốc). [percent] null ⇒ [fallback] (bo mặc định).
         */
        fun cornerRadius(percent: Int?, w: Float, h: Float, fallback: Float): Float {
            val p = percent ?: return fallback
            return maxOf(0f, minOf(w, h)) / 2f * p.coerceIn(0, 100) / 100f
        }
    }
}

/**
 * Kẹp cỡ chữ nhãn phím (thuần — test JVM). Số đo theo em (đo bằng Paint lúc dựng layout):
 * [emWidth] glyph rộng nhất, [emAbove]/[emBelow] mực trên / dưới baseline (dương).
 */
object KeyLabelFitter {
    const val SIDE_PAD_DP = 3f
    const val V_PAD_DP = 1f

    /** Cỡ lớn nhất ≤ [want] để glyph rộng nhất vừa bề ngang và mực vừa chiều cao phím. */
    fun size(want: Float, emWidth: Float, emAbove: Float, emBelow: Float, keyW: Float, keyH: Float,
             sidePad: Float, vPad: Float): Float {
        var s = want
        if (emWidth > 0f && keyW > 0f) s = minOf(s, (keyW - 2 * sidePad) / emWidth * 0.98f)
        val ink = emAbove + emBelow
        if (ink > 0f && keyH > 0f) s = minOf(s, (keyH - 2 * vPad) / ink)
        return maxOf(s, 1f)
    }

    /**
     * Cỡ lớn nhất ≤ [want] để chữ căn giữa theo (ascent+descent)/2 của phông (cách KeyboardView /
     * BalloonView vẽ) trong khung cao [height] không tràn mực: [lineAbove]/[lineBelow] = −ascent /
     * descent của phông theo em, [emAbove]/[emBelow] = mực thật (dấu chồng Ễ, ỵ).
     */
    fun centeredSize(want: Float, lineAbove: Float, lineBelow: Float, emAbove: Float, emBelow: Float, height: Float): Float {
        val line = lineAbove + lineBelow
        val over = maxOf(0f, emAbove - lineAbove, emBelow - lineBelow)
        val need = line + 2 * over
        if (need <= 0f || height <= 0f) return want
        return maxOf(1f, minOf(want, height / need))
    }

    /** Mẫu chữ có thể hiện TRÊN phím / trong ô phóng to và popup ký tự phụ. */
    const val KEY_SAMPLE = "WMĐÂĂÊÔƠƯgjpqy"
    const val STACKED_SAMPLE = "ỄẪỖẰẲỮỂgjpqyỵỹ"
}
