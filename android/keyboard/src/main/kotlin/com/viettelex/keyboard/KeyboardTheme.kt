package com.viettelex.keyboard

import kotlin.math.pow

/*
 * Theme bàn phím — MỌI token màu ở một chỗ (ARGB Int thuần, không phụ thuộc Android
 * để test JVM). Bảng màu y hệt iOS (iOS/Keyboard/KeyboardTheme.swift) — sửa màu thì
 * sửa cả hai. Vẽ phím vẫn phẳng: không bóng, không blur runtime.
 */

/** Tiện ích màu ARGB + tương phản WCAG 2.x. */
object ThemeColor {
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun rgb(hex: Int) = hex or (0xFF shl 24)
    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun withAlpha(c: Int, a: Float): Int =
        argb((alpha(c) * a).toInt().coerceIn(0, 255), red(c), green(c), blue(c))

    const val WHITE = -0x1          // 0xFFFFFFFF
    const val BLACK = -0x1000000    // 0xFF000000

    /** Trộn [fg] (có alpha) lên nền [bg] (coi như đục) → màu đục. */
    fun over(fg: Int, bg: Int): Int {
        val a = alpha(fg) / 255f
        fun ch(f: Int, b: Int) = (f * a + b * (1 - a) + 0.5f).toInt().coerceIn(0, 255)
        return argb(255, ch(red(fg), red(bg)), ch(green(fg), green(bg)), ch(blue(fg), blue(bg)))
    }

    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(red(c)) + 0.7152 * ch(green(c)) + 0.0722 * ch(blue(c))
    }

    /** Tỉ lệ tương phản WCAG (1…21), bỏ qua alpha. */
    fun contrast(x: Int, y: Int): Double {
        val a = luminance(x); val b = luminance(y)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}

/** Bộ token đã giải (theo sáng/tối + ảnh nền). */
data class ThemePalette(
    /** Nền bàn phím; [bgBottom] ≠ null ⇒ gradient dọc (Kính giả lập — Android không có backdrop mờ). */
    val bg: Int,
    val bgBottom: Int? = null,
    val keyFill: Int,
    /** Phím chức năng (Android: nền riêng như Gboard). */
    val specialFill: Int,
    val ink: Int,
    val trail: Int,
    val balloon: Int,
    val popup: Int,
    val action: Int,
    val actionInk: Int,
    val chip: Int,
    /** Viền phím 1dp (tương phản cao / kính) — stroke thường, không offscreen. */
    val keyBorder: Int? = null,
    val isDark: Boolean,
    /** true ⇒ nền là ảnh nền (IME vẽ bitmap + lớp phủ thay cho [bg]). */
    val wallpaper: Boolean = false,
    /** Chữ/icon trên phím (ĐỤC) sau độ trong suốt phím; null = [ink]. Balloon/popup giữ [ink]. */
    val keyLabel: Int? = null,
    /** Alpha chữ/icon trên phím (độ trong suốt ký tự) — IME nhân vào alpha lúc vẽ. */
    val labelAlpha: Float = 1f,
    /**
     * Hệ số alpha bề mặt PHÍM theo độ trong suốt phím (1 = như cũ). KHÔNG áp cho ảnh nền —
     * ảnh nền có độ tối/mờ riêng ([wallpaperLayer]).
     */
    val surfaceAlpha: Float = 1f,
    /** Chữ trên phím do người dùng chọn ([KeyStyle]) — độ trong suốt không đổi sang đen/trắng. */
    val keyLabelPinned: Boolean = false,
    /**
     * Nền phím chức năng + phím cách khi theme tô riêng (Xanh lá); null = như cũ ([specialFill] cho
     * phím chức năng, [keyFill] cho phím cách). Cùng ý nghĩa iOS `functionFill`.
     */
    val functionFill: Int? = null,
    /** Chữ/icon phím chức năng (ĐỤC; alpha theo [labelAlpha] lúc vẽ) — null = [keyInk]. Trắng hồng. */
    val functionInk: Int? = null,
    /** Hoạ tiết nền (tim / hoa) vẽ MỘT lần ở nền IME — null = nền phẳng. */
    val decoration: ThemeDecoration? = null,
    /**
     * Người dùng chọn "Màu nhấn" ([KeyStyle.accentColor]) ⇒ ⇧ bật/Caps tô [action] +
     * [actionInk] (mặc định: Caps tô [chip]). Cùng ý nghĩa iOS `accentShift`.
     */
    val accentShift: Boolean = false,
) {
    val keyInk: Int get() = keyLabel ?: ink
    /** Nền phím chức năng (⇧ ⌫ 123 🌐 😊…). */
    val fnFill: Int get() = functionFill ?: specialFill
    /** Nền phím cách. */
    val spaceFill: Int get() = functionFill ?: keyFill
    val fnInk: Int get() = functionInk ?: keyInk

    /** Lớp phủ ảnh nền: đen cho theme tối, trắng cho theme sáng. */
    val wallpaperOverlay: Int get() = if (isDark) ThemeColor.BLACK else ThemeColor.WHITE

    /**
     * Cách vẽ ảnh nền (mô hình vẽ thuần — IME + bản xem trước dùng chung): ảnh luôn ĐỤC, lớp phủ
     * theo "Độ tối lớp phủ". Độ trong suốt phím KHÔNG làm mất ảnh nền (bug 100% trước đây: ảnh +
     * lớp phủ nhân surfaceAlpha ⇒ 0 ⇒ ảnh biến mất).
     */
    fun wallpaperLayer(dim: Int): WallpaperLayer =
        WallpaperLayer(1f, ThemeColor.withAlpha(wallpaperOverlay, dim.coerceIn(0, 80) / 100f))

    /**
     * Có ảnh nền: phím hơi trong để ảnh lộ ra. Độ trong chọn sao cho chữ ≥ 4.5:1 (AA)
     * kể cả trên ảnh TỆ NHẤT (trắng dưới theme tối, đen dưới theme sáng), lớp phủ 0%.
     */
    fun overWallpaper(): ThemePalette {
        val worst = if (isDark) ThemeColor.WHITE else ThemeColor.BLACK
        fun legible(base: Int): Int {
            val translucent = ThemeColor.alpha(base) < 255
            val solid = if (translucent) (if (isDark) ThemeColor.BLACK else ThemeColor.WHITE) else base
            var a = if (translucent) 0.5f else WALLPAPER_KEY_ALPHA
            while (a < 1f && ThemeColor.contrast(ink, ThemeColor.over(ThemeColor.withAlpha(solid, a), worst)) < 4.5) a += 0.05f
            return ThemeColor.withAlpha(solid, a.coerceAtMost(1f))
        }
        return copy(keyFill = legible(keyFill), specialFill = legible(specialFill), functionFill = functionFill?.let(::legible),
            bgBottom = null, wallpaper = true, decoration = null)
    }

    companion object { const val WALLPAPER_KEY_ALPHA = 0.8f }
}

/** Ảnh nền: alpha bitmap + màu lớp phủ (ARGB). */
data class WallpaperLayer(val imageAlpha: Float, val overlay: Int)

/**
 * Độ trong suốt (Cài đặt → Giao diện), hai thanh độc lập 0…100% — y hệt iOS
 * (KeyboardTransparency trong iOS/Keyboard/KeyboardTheme.swift):
 * - phím: nền phím + viền (+ nền theme khi KHÔNG có ảnh nền); 100% = phím vô hình, chỉ còn chữ.
 *   Ảnh nền luôn giữ nguyên (đã có độ tối/mờ riêng).
 * - ký tự: chữ/icon/nhãn phụ trên phím; 100% = phím trơn không chữ.
 * Áp bằng alpha của MÀU lúc dựng theme — không alpha nhóm, 0 chi phí mỗi phím.
 */
object KeyboardTransparency {
    /** Chữ phím ≥ 18sp = "chữ lớn" WCAG → 3:1; dưới ngưỡng mới đổi sang đen/trắng. */
    const val MIN_LABEL_CONTRAST = 3.0

    fun clamp(v: Int) = v.coerceIn(0, 100)
    /** 0% → 1 (như cũ), 100% → 0. */
    fun alpha(pct: Int) = 1f - clamp(pct) / 100f

    /**
     * Thứ lộ ra sau cửa sổ IME trong suốt: cửa sổ app (nền windowBackground — thường
     * trắng / tối Material). Không đo được từ IME → ước lượng theo sáng/tối hệ thống.
     */
    fun systemBackdrop(dark: Boolean) = if (dark) ThemeColor.rgb(0x121212) else ThemeColor.rgb(0xFFFFFF)

    /** Giữ [ink] nếu còn đọc được trên [bg]; không thì đen/trắng (cái tương phản hơn). */
    fun readable(ink: Int, bg: Int): Int {
        val c = ThemeColor
        if (c.contrast(ink, bg) >= MIN_LABEL_CONTRAST) return ink
        val alt = if (c.contrast(c.WHITE, bg) >= c.contrast(c.BLACK, bg)) c.WHITE else c.BLACK
        return if (c.contrast(alt, bg) > c.contrast(ink, bg)) alt else ink
    }
}

fun ThemePalette.withTransparency(keyboard: Int, labels: Int, systemDark: Boolean): ThemePalette {
    val t = KeyboardTransparency
    val k = t.clamp(keyboard); val l = t.clamp(labels)
    if (k == 0 && l == 0) return this
    var p = copy(labelAlpha = t.alpha(l))
    if (k > 0) {
        val s = t.alpha(k)
        val c = ThemeColor
        fun f(x: Int) = c.withAlpha(x, s)
        p = p.copy(bg = f(bg), bgBottom = bgBottom?.let(::f), keyFill = f(keyFill), specialFill = f(specialFill),
            action = f(action), chip = f(chip), keyBorder = keyBorder?.let(::f), surfaceAlpha = s,
            functionFill = functionFill?.let(::f), decoration = decoration?.let { it.copy(color = f(it.color)) })
        // Nền thật sau chữ ≈ lớp nền mới trên cửa sổ app. Ảnh nền vẫn hiện nguyên ⇒ ước lượng
        // bằng tông lớp phủ (tối cho theme tối, sáng cho theme sáng).
        val sys = t.systemBackdrop(systemDark)
        val behind = c.over(if (wallpaper) wallpaperOverlay else p.bg, sys)
        val label = if (keyLabelPinned) ink else t.readable(ink, c.over(p.keyFill, behind))
        val fnInk = if (keyLabelPinned) functionInk else functionInk?.let { t.readable(it, c.over(p.fnFill, behind)) }
        p = p.copy(keyLabel = label, functionInk = fnInk, actionInk = t.readable(actionInk, c.over(p.action, behind)),
            isDark = if (label != ink) c.luminance(label) > 0.5 else isDark)
    }
    return p
}

enum class KeyboardTheme(val id: String, val viTitle: String, val isPlus: Boolean) {
    SYSTEM("system", "Hệ thống", false),
    OLED("oled", "Tối OLED", false),
    /** Trợ năng → luôn miễn phí. */
    CONTRAST("contrast", "Tương phản cao", false),
    PEACH("peach", "Hồng đào", true),
    MINT("mint", "Bạc hà", true),
    SKY("sky", "Trời xanh", true),
    LAVENDER("lavender", "Oải hương", true),
    GLASS("glass", "Kính", true),
    // Bộ 10/2026 (gợi ý từ các theme cộng đồng được thích nhất — thiết kế riêng, không dùng tài sản
    // của ai, không nhân vật có thương hiệu). id = giá trị lưu, trùng rawValue iOS.
    BLACK_HEART("blackHeart", "Tim đen", true),
    GREEN_FLORAL("greenFloral", "Hoa xanh", true),
    SAGE("sage", "Xanh lá", true),
    // "Trắng hồng" (pureWhite, 1.5.12) đã bỏ 10/2026: chỉ là Hệ thống + màu nhấn hồng ⇒ chuyển sang
    // Hệ thống + Màu nhấn D6336C ([KeyStyleStore.migrate]). Id lạ / đã bỏ luôn về Hệ thống ([of]).
    NEON("neon", "Neon", true),
    PINK("pink", "Hồng", true);

    /** Bo góc mặc định (% như [KeyStyle.corner]) khi người dùng chưa chỉnh — null = bo stock. iOS cùng giá trị. */
    val defaultCorner: Int? get() = if (this == PINK) 70 else null

    /** Tên hiển thị theo ngôn ngữ giao diện ([L10n]); [viTitle] = khoá dịch. */
    val title: String get() = tr(viTitle)

    /**
     * Palette cố định của theme; SYSTEM trả null (IME dùng màu Material You trong
     * resources như trước — không đổi hình người dùng cũ).
     */
    fun palette(systemDark: Boolean): ThemePalette? {
        val c = ThemeColor
        return when (this) {
            SYSTEM -> null
            OLED -> ThemePalette(bg = c.BLACK, keyFill = c.rgb(0x1C1C1E), specialFill = c.rgb(0x3A3A3C),
                ink = c.WHITE, trail = c.withAlpha(c.WHITE, 0.6f), balloon = c.rgb(0x2C2C2E),
                popup = c.rgb(0x2C2C2E), action = c.rgb(0x0A84FF), actionInk = c.WHITE,
                chip = c.rgb(0x3A3A3C), isDark = true)
            CONTRAST -> ThemePalette(bg = c.BLACK, keyFill = c.rgb(0x141414), specialFill = c.rgb(0x4D4D4D),
                ink = c.WHITE, trail = c.rgb(0xFFD60A), balloon = c.rgb(0x141414), popup = c.rgb(0x141414),
                action = c.rgb(0xFFD60A), actionInk = c.BLACK, chip = c.rgb(0x4D4D4D),
                keyBorder = c.withAlpha(c.WHITE, 0.85f), isDark = true)
            // Pastel: sáng = bảng cũ; tối = cùng họ màu, nền + phím sẫm ngả màu, chữ sáng, giữ màu nhấn.
            PEACH -> if (systemDark) solid(0x2A1A1C, 0x45292D, 0x5C3A3F, 0xFBE3E1, 0xC2505A)
                else solid(0xFBE3E1, 0xFFF8F7, 0xF1C4BF, 0x4A2226, 0xC2505A)
            MINT -> if (systemDark) solid(0x10241D, 0x1D3B30, 0x2A5244, 0xD9F2E7, 0x1F7A5C)
                else solid(0xD9F2E7, 0xF6FFFA, 0xAFDDC9, 0x173B30, 0x1F7A5C)
            SKY -> if (systemDark) solid(0x111E2E, 0x1E324A, 0x2B4566, 0xDAE9F8, 0x2C6BB8)
                else solid(0xDAE9F8, 0xF6FAFF, 0xB3CFEE, 0x14304C, 0x2C6BB8)
            LAVENDER -> if (systemDark) solid(0x1C1830, 0x2E2848, 0x403862, 0xE8E2F6, 0x6A48C4)
                else solid(0xE8E2F6, 0xFBF9FF, 0xCBBFEA, 0x2C2345, 0x6A48C4)
            // Tim đen: luôn tối — nền đen, chữ trắng, tim hồng nhỏ vẽ một lần ở nền.
            BLACK_HEART -> solid(0x000000, 0x1A1A1A, 0x333333, 0xFFFFFF, 0xE63E7A,
                decoration = ThemeDecoration(ThemeDecoration.HEARTS, c.withAlpha(c.rgb(0xFF6FA3), 0.55f)))
            // Hoa xanh: phím trắng viền xanh mảnh, chữ xanh, hoa nhỏ mờ ở nền.
            GREEN_FLORAL -> if (systemDark) solid(0x0F1D15, 0x16291E, 0x21392A, 0x9EE0B6, 0x2E8B57,
                border = c.withAlpha(c.rgb(0x4CAF6E), 0.8f),
                decoration = ThemeDecoration(ThemeDecoration.FLOWERS, c.withAlpha(c.rgb(0x4CAF6E), 0.28f)))
            else solid(0xEEF6EF, 0xFFFFFF, 0xDCEEDF, 0x256B42, 0x2E8B57, border = c.rgb(0x4CAF6E),
                decoration = ThemeDecoration(ThemeDecoration.FLOWERS, c.withAlpha(c.rgb(0x6DBE8A), 0.35f)))
            // Xanh lá: phím sáng, phím chức năng + phím cách màu xanh xô thơm (sage).
            SAGE -> if (systemDark) solid(0x161C15, 0x2A3328, 0x3A4A36, 0xE4EDE1, 0x5E7F55, function = 0x4A5E45)
                else solid(0xE4EBE2, 0xFBFCFA, 0x93AB8C, 0x24331F, 0x5E7F55, function = 0xA9BFA3)
            // Neon: luôn tối — nền gần đen, phím xanh mòng két sẫm viền cyan, chữ cyan.
            NEON -> solid(0x05080A, 0x0E2A2E, 0x15393E, 0x5FF5E8, 0x00BFB2, accentInk = 0x000000,
                border = c.withAlpha(c.rgb(0x00E5D4), 0.7f))
            // Hồng: hồng pastel, phím bo tròn ([defaultCorner]).
            PINK -> if (systemDark) solid(0x2B1520, 0x472335, 0x5E2F46, 0xFCDDE8, 0xD94680)
                else solid(0xFCDDE8, 0xFFF5F9, 0xF6B8CE, 0x5A1F38, 0xD94680)
            // Android không có backdrop mờ sau cửa sổ IME → giả lập: gradient dịu +
            // phím trắng bán trong + viền sáng mảnh.
            GLASS -> if (systemDark) ThemePalette(bg = c.rgb(0x2B3040), bgBottom = c.rgb(0x16181F),
                keyFill = c.withAlpha(c.WHITE, 0.16f), specialFill = c.withAlpha(c.WHITE, 0.08f),
                ink = c.WHITE, trail = c.withAlpha(c.WHITE, 0.6f), balloon = c.rgb(0x4A4A4E),
                popup = c.rgb(0x3A3D48), action = c.rgb(0xA8C7FA), actionInk = c.rgb(0x062E6F),
                chip = c.withAlpha(c.WHITE, 0.3f), keyBorder = c.withAlpha(c.WHITE, 0.18f), isDark = true)
            else ThemePalette(bg = c.rgb(0xDCE6F2), bgBottom = c.rgb(0xC5D0DE),
                keyFill = c.withAlpha(c.WHITE, 0.55f), specialFill = c.withAlpha(c.WHITE, 0.3f),
                ink = c.rgb(0x1F1F1F), trail = c.withAlpha(c.rgb(0x0B57D0), 0.6f), balloon = c.WHITE,
                popup = c.WHITE, action = c.rgb(0x0B57D0), actionInk = c.WHITE,
                chip = c.withAlpha(c.WHITE, 0.8f), keyBorder = c.withAlpha(c.WHITE, 0.6f), isDark = false)
        }
    }

    companion object {
        fun of(id: String?): KeyboardTheme = entries.firstOrNull { it.id == id } ?: SYSTEM

        /** Theme nền đục một màu — y hệt iOS `KeyboardTheme.solid`. */
        private fun solid(bg: Int, key: Int, special: Int, ink: Int, accent: Int, accentInk: Int = 0xFFFFFF,
                          border: Int? = null, function: Int? = null, functionInk: Int? = null,
                          decoration: ThemeDecoration? = null): ThemePalette {
            val c = ThemeColor
            return ThemePalette(bg = c.rgb(bg), keyFill = c.rgb(key), specialFill = c.rgb(special),
                ink = c.rgb(ink), trail = c.withAlpha(c.rgb(accent), 0.6f), balloon = c.rgb(key),
                popup = c.rgb(key), action = c.rgb(accent), actionInk = c.rgb(accentInk), chip = c.rgb(special),
                keyBorder = border, isDark = c.luminance(c.rgb(ink)) > 0.5,
                functionFill = function?.let(c::rgb), functionInk = functionInk?.let(c::rgb), decoration = decoration)
        }
    }
}

/**
 * Hoạ tiết nền theme (Tim đen: tim; Hoa xanh: hoa) — MỘT Path vẽ trong Drawable nền IME (display
 * list ghi một lần, dựng lại khi cỡ / hàng phím đổi). 0 chi phí mỗi phím, không ảnh. Motif CHỈ nằm
 * trong khe GIỮA các hàng phím (không ở thanh gợi ý, không dưới nhãn phím). Hình học y hệt iOS
 * (KeyboardDecoration trong iOS/Keyboard/KeyboardTheme.swift), đơn vị dp.
 */
data class ThemeDecoration(val motif: String, val color: Int) {
    data class Gap(val center: Double, val height: Double)
    data class Placement(val x: Double, val y: Double, val size: Double)

    companion object {
        const val HEARTS = "hearts"
        const val FLOWERS = "flowers"
        const val STEP_X = 64.0
        const val MOTIF_SIZE = 8.0
        const val INSET = 14.0
        const val MIN_MOTIF_SIZE = 5.0
        /** Cách mép phím tối thiểu (motif không chạm phím). */
        const val CLEARANCE = 1.0
        const val MAX_MOTIFS = 120

        /**
         * Khe giữa các HÀNG phím từ khung phím [top, bottom]: gộp phím cùng hàng (chồng dọc), khe = từ
         * đáy hàng trên tới đỉnh hàng dưới. Không tính phía trên hàng đầu (thanh gợi ý).
         */
        fun rowGaps(spans: List<DoubleArray>): List<Gap> {
            val rows = ArrayList<DoubleArray>()
            for (sp in spans.sortedBy { it[0] }) {
                if (sp[1] <= sp[0]) continue
                val last = rows.lastOrNull()
                if (last != null && sp[0] < last[1]) last[1] = maxOf(last[1], sp[1]) else rows.add(doubleArrayOf(sp[0], sp[1]))
            }
            return (1 until rows.size).mapNotNull { i ->
                val h = rows[i][0] - rows[i - 1][1]
                if (h > 0) Gap(rows[i - 1][1] + h / 2, h) else null
            }
        }

        /** Motif dọc từng khe (so le giữa các khe), cỡ vừa khe (chừa [CLEARANCE] mỗi bên); khe quá hẹp bỏ. */
        fun placements(w: Double, gaps: List<Gap>): List<Placement> {
            if (w <= 2 * INSET) return emptyList()
            fun layout(step: Double): List<Placement> {
                val out = ArrayList<Placement>()
                gaps.forEachIndexed { i, g ->
                    val size = minOf(MOTIF_SIZE, g.height - 2 * CLEARANCE)
                    if (size < MIN_MOTIF_SIZE) return@forEachIndexed
                    var x = INSET + size / 2 + if (i % 2 == 1) step / 2 else 0.0
                    while (x + size / 2 <= w - INSET) { out.add(Placement(x, g.center, size)); x += step }
                }
                return out
            }
            var step = STEP_X
            var pts = layout(step)
            while (pts.size > MAX_MOTIFS) { step *= 1.15; pts = layout(step) }
            return pts
        }

        /**
         * Đường tim theo đơn vị motif (tâm 0,0; y xuống): điểm đầu rồi 6 đoạn cubic
         * (c1x, c1y, c2x, c2y, x, y) — y hệt iOS.
         */
        val HEART_START = doubleArrayOf(0.0, 0.45)
        val HEART_CUBICS = arrayOf(
            doubleArrayOf(-0.35, 0.2, -0.5, 0.0, -0.5, -0.18),
            doubleArrayOf(-0.5, -0.38, -0.36, -0.5, -0.22, -0.5),
            doubleArrayOf(-0.1, -0.5, -0.03, -0.43, 0.0, -0.33),
            doubleArrayOf(0.03, -0.43, 0.1, -0.5, 0.22, -0.5),
            doubleArrayOf(0.36, -0.5, 0.5, -0.38, 0.5, -0.18),
            doubleArrayOf(0.5, 0.0, 0.35, 0.2, 0.0, 0.45),
        )

        /** Hoa: 5 cánh tròn (tâm, bán kính) không chồng nhau + nhuỵ — [cx, cy, r] theo đơn vị motif. */
        val FLOWER_CIRCLES: List<DoubleArray> = (0 until 5).map { i ->
            val a = i * 2 * Math.PI / 5 - Math.PI / 2
            doubleArrayOf(0.32 * Math.cos(a), 0.32 * Math.sin(a), 0.18)
        } + listOf(doubleArrayOf(0.0, 0.0, 0.12))
    }
}

/** Trang "Chọn giao diện" (logic thuần — test JVM; iOS: ThemePicker trong KeyboardTheme.swift). */
object ThemePicker {
    data class Section(val title: String, val themes: List<KeyboardTheme>)
    /** "Miễn phí" (Hệ thống, OLED, Tương phản cao) rồi "Plus" (còn lại), giữ thứ tự khai báo. */
    val sections: List<Section> get() = listOf(
        Section("Miễn phí", KeyboardTheme.entries.filter { !it.isPlus }),
        Section("Plus", KeyboardTheme.entries.filter { it.isPlus }))
    /** Chạm thẻ: chọn ngay nếu được phép; theme Plus bị khoá ⇒ null (IME/app không đổi gì). */
    fun select(t: KeyboardTheme, s: ThemeSettings): ThemeSettings? = if (ThemeGate.allows(t)) s.copy(theme = t) else null
}

/**
 * Cổng Plus cho theme — dùng [PlusGate] thật: [PlusFeature.PREMIUM_THEMES] gồm theme
 * cao cấp + ảnh nền. Hệ thống/OLED/tương phản cao luôn miễn phí.
 */
object ThemeGate {
    fun allows(t: KeyboardTheme) = !t.isPlus || PlusGate.isUnlocked(PlusFeature.PREMIUM_THEMES)
    val allowsWallpaper: Boolean get() = PlusGate.isUnlocked(PlusFeature.PREMIUM_THEMES)
    /** "Tuỳ chỉnh nâng cao" đi cùng quyền theme cao cấp (Plus). */
    val allowsAdvanced: Boolean get() = PlusGate.isUnlocked(PlusFeature.PREMIUM_THEMES)
}

/** Cài đặt giao diện — app ghi, IME đọc mỗi lần dựng input view. */
data class ThemeSettings(
    val theme: KeyboardTheme = KeyboardTheme.SYSTEM,
    val wallpaper: Boolean = false,
    /** % lớp phủ 0…80. */
    val dim: Int = 30,
    /** Bán kính mờ 0…20 — app áp lúc lưu ảnh, IME chỉ hiện. */
    val blur: Int = 0,
    /** Đổi mỗi lần lưu ảnh → IME bỏ bitmap cũ. */
    val version: Long = 0,
    /** Độ trong suốt phím / ký tự 0…100 (miễn phí, mọi theme). */
    val keyboardTransparency: Int = 0,
    val labelTransparency: Int = 0,
    /**
     * Khung cắt đã chỉnh (null = ảnh cũ / chưa chỉnh → cắt giữa). Thuộc về ẢNH (như file) nên
     * "Khôi phục giao diện gốc" giữ nguyên.
     */
    val crop: WallpaperCrop? = null,
    /**
     * "Tuỳ chỉnh nâng cao" (Plus) RIÊNG từng theme: id theme → [KeyStyle]; theme chưa chỉnh không có
     * mục. Lưu nguyên kể cả khi hết quyền; áp qua [effectiveKeyStyle]. Id lạ (bản mới hơn) giữ nguyên.
     */
    val keyStyles: Map<String, KeyStyle> = emptyMap(),
) {
    /** Tuỳ chỉnh của theme ĐANG CHỌN (màn Giao diện đọc qua đây; ghi bằng [withKeyStyle]). */
    val keyStyle: KeyStyle get() = styleFor(theme)
    fun styleFor(t: KeyboardTheme): KeyStyle = keyStyles[t.id] ?: KeyStyle.DEFAULT
    /** Đặt tuỳ chỉnh cho [t] (mặc định: theme đang chọn); mặc định ⇒ xoá mục ("Đặt lại về mặc định của giao diện"). */
    fun withKeyStyle(st: KeyStyle, t: KeyboardTheme = theme): ThemeSettings =
        copy(keyStyles = if (st.isDefault) keyStyles - t.id else keyStyles + (t.id to st))

    /**
     * "Khôi phục giao diện gốc": mọi chỉnh ở màn Giao diện về mặc định. Ảnh nền chỉ bỏ
     * chọn (file giữ để bật lại); version giữ nguyên (không vứt bitmap cache vô cớ).
     */
    fun resetToDefaults() = ThemeSettings(version = version, crop = crop)
    val isDefault: Boolean get() = this == resetToDefaults()

    val effectiveTheme: KeyboardTheme get() = if (ThemeGate.allows(theme)) theme else KeyboardTheme.SYSTEM
    fun wallpaperActive(fileExists: Boolean) = wallpaper && fileExists && ThemeGate.allowsWallpaper
    /** Tuỳ chỉnh nâng cao thực dùng: hết quyền Plus ⇒ mặc định (giá trị lưu giữ nguyên). */
    val effectiveKeyStyle: KeyStyle get() = effectiveStyleFor(effectiveTheme)
    /** Như [effectiveKeyStyle] cho theme [t] bất kỳ (thẻ "Chọn giao diện" xem trước tuỳ chỉnh riêng). */
    fun effectiveStyleFor(t: KeyboardTheme): KeyStyle = (if (ThemeGate.allowsAdvanced) styleFor(t) else KeyStyle.DEFAULT)
        .let { s -> if (s.corner == null && t.defaultCorner != null) s.copy(corner = t.defaultCorner) else s }

    /** Theme + tuỳ chỉnh màu RIÊNG của nó (trước ảnh nền / độ trong suốt) — một lần lúc dựng theme. */
    fun styled(t: KeyboardTheme, base: ThemePalette): ThemePalette =
        effectiveStyleFor(t).let { it.applyAccent(it.applyColors(base)) }

    fun toMap(): Map<String, Any> = mapOf(Keys.KEYBOARD_THEME to theme.id, Keys.WALLPAPER_ENABLED to wallpaper,
        Keys.WALLPAPER_DIM to dim, Keys.WALLPAPER_BLUR to blur, Keys.WALLPAPER_VERSION to version,
        Keys.KEYBOARD_TRANSPARENCY to keyboardTransparency, Keys.KEY_LABEL_TRANSPARENCY to labelTransparency,
        Keys.KEY_STYLES to KeyStyleStore.serialize(keyStyles)) +
        (crop?.let { mapOf(Keys.WALLPAPER_CROP to it.serialize()) } ?: emptyMap())

    companion object {
        /**
         * Đọc + chuyển đổi trong bộ nhớ ([KeyStyleStore.migrate] — thuần; IME gọi mỗi lần dựng view).
         * App ghi bản đã chuyển một lần ([needsMigration] ⇒ lưu lại, xoá khoá cũ).
         */
        fun load(get: (String) -> Any?): ThemeSettings {
            val m = KeyStyleStore.migrate(get(Keys.KEYBOARD_THEME) as? String,
                KeyStyleStore.parse(get(Keys.KEY_STYLES) as? String), KeyStyleStore.loadLegacy(get))
            return loadWith(get, m)
        }

        /** Cần ghi bản chuyển đổi (khoá chung cũ còn / theme "Trắng hồng"). */
        fun needsMigration(get: (String) -> Any?) =
            KeyStyleStore.needsMigration(get(Keys.KEYBOARD_THEME) as? String, KeyStyleStore.hasLegacy(get))

        private fun loadWith(get: (String) -> Any?, m: KeyStyleStore.Migrated) = ThemeSettings(
            theme = KeyboardTheme.of(m.theme),
            keyStyles = m.styles,
            wallpaper = get(Keys.WALLPAPER_ENABLED) as? Boolean ?: false,
            dim = ((get(Keys.WALLPAPER_DIM) as? Number)?.toInt() ?: 30).coerceIn(0, 80),
            blur = ((get(Keys.WALLPAPER_BLUR) as? Number)?.toInt() ?: 0).coerceIn(0, 20),
            version = (get(Keys.WALLPAPER_VERSION) as? Number)?.toLong() ?: 0,
            keyboardTransparency = KeyboardTransparency.clamp((get(Keys.KEYBOARD_TRANSPARENCY) as? Number)?.toInt() ?: 0),
            labelTransparency = KeyboardTransparency.clamp((get(Keys.KEY_LABEL_TRANSPARENCY) as? Number)?.toInt() ?: 0),
            crop = WallpaperCrop.parse(get(Keys.WALLPAPER_CROP) as? String),
        )
    }
}

/** Toán ảnh nền thuần (test JVM): cỡ lưu, inSampleSize, mờ hộp 3 lượt ≈ Gauss. */
object WallpaperMath {
    const val MAX_EDGE = 1080
    const val MAX_BYTES = 400_000
    /** Bản gốc giữ để chỉnh khung về sau — cạnh dài ≤ chừng này. */
    const val SOURCE_MAX_EDGE = 2048

    /**
     * Cỡ px ảnh nướng cho IME từ bản gốc iw×ih + khung [crop] (null = cả ảnh, như cũ).
     * Mật độ điểm ảnh = đúng bản cũ (cả ảnh thu ≤[MAX_EDGE]) × zoom, kẹp cạnh dài ≤[MAX_EDGE]
     * và không phóng to ⇒ ở khung mặc định (zoom 1) IME giải ĐÚNG bằng số điểm ảnh như trước;
     * zoom vào thì nét hơn nhưng không bao giờ vượt trần cũ (file ≤1080 px).
     */
    fun bakedSize(iw: Int, ih: Int, crop: WallpaperCrop?): Pair<Int, Int> {
        if (crop == null || iw <= 0 || ih <= 0) return targetSize(iw, ih)
        val r = crop.pixelRect(iw, ih)
        val cw = r[2] - r[0]; val ch = r[3] - r[1]
        val base = minOf(1.0, MAX_EDGE.toDouble() / maxOf(iw, ih))
        val s = minOf(1.0, base * crop.zoom(iw, ih), MAX_EDGE.toDouble() / maxOf(cw, ch))
        return maxOf(1, Math.round(cw * s).toInt()) to maxOf(1, Math.round(ch * s).toInt())
    }

    /**
     * Ước lượng cỡ px vùng bàn phím dọc khi IME chưa từng đo (xem [Keys.IME_PORTRAIT_SIZE]):
     * vùng phím + strip gợi ý mở + đệm thanh điều hướng.
     */
    fun estimatePortraitPx(screenShortPx: Int, density: Float, keyAreaDp: Float, stripDp: Float, navPx: Int): Pair<Int, Int> =
        screenShortPx to Math.round((keyAreaDp + stripDp) * density + navPx)

    /** "w×h" → cặp px dương, sai ⇒ null. */
    fun parseSize(s: String?): Pair<Int, Int>? {
        val p = s?.split("x")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        return if (p.size == 2 && p[0] > 0 && p[1] > 0 && p[0] < 20000 && p[1] < 20000) p[0] to p[1] else null
    }

    /** Cỡ lưu: cạnh dài ≤ [maxEdge], giữ tỉ lệ, không phóng to. */
    fun targetSize(w: Int, h: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxEdge) return w to h
        val s = maxEdge.toDouble() / long
        return maxOf(1, Math.round(w * s).toInt()) to maxOf(1, Math.round(h * s).toInt())
    }

    /** inSampleSize (lũy thừa 2) lớn nhất mà ảnh giải ra vẫn ≥ req ở cả hai chiều. */
    fun sampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        while (srcW / (s * 2) >= reqW && srcH / (s * 2) >= reqH) s *= 2
        return s
    }

    /** Cỡ cần giải để aspect-fill view vw×vh px (kẹp ≤ bản lưu). */
    fun fillSize(imgW: Int, imgH: Int, vw: Int, vh: Int): Pair<Int, Int> {
        if (vw <= 0 || vh <= 0 || imgW <= 0 || imgH <= 0) return imgW to imgH
        val s = maxOf(vw.toDouble() / imgW, vh.toDouble() / imgH).coerceAtMost(1.0)
        return maxOf(1, Math.ceil(imgW * s).toInt()) to maxOf(1, Math.ceil(imgH * s).toInt())
    }

    /**
     * Vùng ảnh THẤY ĐƯỢC khi aspect-fill (center-crop) view vw×vh: [left, top, right, bottom]
     * theo px ảnh gốc — IME chỉ giải vùng này (BitmapRegionDecoder), không giải cả ảnh.
     */
    fun visibleCrop(imgW: Int, imgH: Int, vw: Int, vh: Int): IntArray {
        if (vw <= 0 || vh <= 0 || imgW <= 0 || imgH <= 0) return intArrayOf(0, 0, imgW, imgH)
        val s = maxOf(vw.toDouble() / imgW, vh.toDouble() / imgH)
        val cw = minOf(imgW, Math.ceil(vw / s - 1e-9).toInt().coerceAtLeast(1))
        val ch = minOf(imgH, Math.ceil(vh / s - 1e-9).toInt().coerceAtLeast(1))
        val l = (imgW - cw) / 2; val t = (imgH - ch) / 2
        return intArrayOf(l, t, l + cw, t + ch)
    }

    /**
     * Kế hoạch giải của IME cho file w×h trên view vw×vh: [left, top, right, bottom, inSampleSize]
     * — chỉ vùng thấy được (cắt giữa), sample vừa phủ view. File đã nướng theo khung ⇒ vùng =
     * gần cả file ở hướng dọc, dải giữa ([WallpaperCrop.landscape]) ở hướng ngang.
     */
    fun decodePlan(w: Int, h: Int, vw: Int, vh: Int): IntArray {
        val c = visibleCrop(w, h, vw, vh)
        val (needW, needH) = fillSize(w, h, vw, vh)
        return intArrayOf(c[0], c[1], c[2], c[3], sampleSize(w, h, needW, needH))
    }

    /** Mờ hộp 3 lượt (ngang + dọc) trên ARGB — thay RenderScript đã bỏ. In-place. */
    fun blur(px: IntArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0 || w <= 0 || h <= 0) return
        val tmp = IntArray(px.size)
        val r = maxOf(1, radius / 2)   // 3 lượt hộp bán kính r ≈ Gauss σ≈radius
        repeat(3) {
            boxPass(px, tmp, w, h, r, horizontal = true)
            boxPass(tmp, px, w, h, r, horizontal = false)
        }
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int { val k = i.coerceIn(0, len - 1); return if (horizontal) line * w + k else k * w + line }
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = src[idx(i)]; sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
            for (i in 0 until len) {
                dst[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[idx(i - r)]; val inn = src[idx(i + r + 1)]
                sa += (inn ushr 24) - (out ushr 24)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
