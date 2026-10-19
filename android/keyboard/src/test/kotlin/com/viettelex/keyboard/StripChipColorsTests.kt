package com.viettelex.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Chip thanh gợi ý (thẻ Dán "Nội dung vừa copy", chip số clipboard, pill Khôi phục) — màu lấy từ palette,
 * chữ luôn đọc được.
 *
 * Field report 19/10/2026 (Android, giao diện tối): "mục clipboard để dán này giao diện tối hình như bị lỗi
 * trắng bác" — thẻ Dán thành viên thuốc TRẮNG ĐẶC, chữ trắng biến mất. Nguyên nhân: StripView đặt
 * `chipPaint.alpha = 255` (và = alpha hoạt ảnh) ⇒ ghi đè alpha gốc của chip: Kính tối có chip = trắng 30%
 * ⇒ trắng đặc; mọi theme có độ trong suốt phím cũng mất độ trong của chip. Fix: [chipFill] nhân alpha.
 */
class StripChipColorsTests {
    private val c = ThemeColor
    private var savedPaywall = false
    @Before fun setUp() { savedPaywall = PlusGate.paywallEnabled; PlusGate.paywallEnabled = false }
    @After fun tearDown() { PlusGate.paywallEnabled = savedPaywall; PlusGate.install({ null }, debugBuild = false) }

    /** "Hệ thống" = màu resource tĩnh (values / values-night ime_colors.xml, < Android 12). */
    private fun systemBase(dark: Boolean) = if (dark) ThemePalette(bg = c.rgb(0x1F1F1F), keyFill = c.rgb(0x3C3C3F),
        specialFill = c.rgb(0x2B2F36), ink = c.rgb(0xE3E3E3), trail = c.rgb(0xA8C7FA), balloon = c.rgb(0x4A4A4E),
        popup = c.rgb(0x3C3C3F), action = c.rgb(0xA8C7FA), actionInk = c.rgb(0x062E6F), chip = c.rgb(0x004A77), isDark = true)
    else ThemePalette(bg = c.rgb(0xEEF0F4), keyFill = c.WHITE, specialFill = c.rgb(0xD6DBE3), ink = c.rgb(0x1F1F1F),
        trail = c.rgb(0x0B57D0), balloon = c.WHITE, popup = c.WHITE, action = c.rgb(0x0B57D0), actionInk = c.WHITE,
        chip = c.rgb(0xD3E3FD), isDark = false)

    /** Như ImeTheme: theme + tuỳ chỉnh → độ trong suốt. */
    private fun palette(s: ThemeSettings, dark: Boolean): ThemePalette =
        s.styledCurrent(s.basePalette(dark) ?: systemBase(dark), dark)
            .withTransparency(s.keyboardTransparency, s.labelTransparency, dark)

    private fun behind(p: ThemePalette, dark: Boolean) = c.over(p.bg, KeyboardTransparency.systemBackdrop(dark))

    private fun assertReadable(tag: String, p: ThemePalette, dark: Boolean) {
        // Nền chip đúng là chip của palette (giữ alpha gốc), kể cả khi vẽ với độ mờ hoạt ảnh/nhấn.
        assertEquals(tag, p.chip, p.chipFill())
        assertEquals(tag, (c.alpha(p.chip) * 0.8f).toInt(), c.alpha(p.chipFill(0.8f)))
        val fill = c.over(p.chipFill(), behind(p, dark))
        val ratio = c.contrast(p.chipInk(dark), fill)
        assertTrue("$tag ratio=$ratio", ratio >= KeyboardTransparency.MIN_LABEL_CONTRAST)
    }

    @Test fun everyBuiltinThemeLightAndDark() {
        for (t in KeyboardTheme.entries) for (dark in listOf(false, true)) for (tr in listOf(0, 40, 100)) {
            val p = palette(ThemeSettings(theme = t, keyboardTransparency = tr), dark)
            assertReadable("$t dark=$dark tr=$tr", p, dark)
            // Không tuỳ chỉnh màu ⇒ chữ chip = chữ theme (không đổi sang đen/trắng).
            if (tr == 0) assertEquals("$t dark=$dark", p.keyInk, p.chipInk(dark))
        }
    }

    /** Đúng field report: Kính tối — chip trắng 30%, chữ trắng. */
    @Test fun glassDarkChipStaysTranslucent() {
        val p = palette(ThemeSettings(theme = KeyboardTheme.GLASS), true)
        assertEquals(c.WHITE, p.chipInk(true))
        assertEquals((255 * 0.3f).toInt(), c.alpha(p.chipFill()))
        // Bug cũ: alpha ghi đè 255 ⇒ trắng đặc ⇒ chữ trắng 1:1.
        assertEquals(1.0, c.contrast(p.chipInk(true), c.withAlpha(p.chip or (0xFF shl 24), 1f)), 1e-9)
        assertNotEquals(255, c.alpha(p.chipFill(1f)))
        assertReadable("glass dark", p, true)
    }

    /** Tuỳ chỉnh màu chữ / phím / nhấn, theme JSON vá chế độ: chip vẫn đọc được. */
    @Test fun customizationsAndPatchesStayReadable() {
        val styles = listOf(KeyStyle(fillColor = 0xFFFFFF), KeyStyle(fillColor = 0x101010), KeyStyle(labelColor = 0x000000),
            KeyStyle(labelColor = 0xFFFFFF), KeyStyle(accentColor = 0xD6336C),
            KeyStyle(dark = ThemeSlots(special = c.WHITE), light = ThemeSlots(special = c.BLACK)),
            KeyStyle(dark = ThemeSlots(special = c.withAlpha(c.WHITE, 0.25f))))
        for (t in KeyboardTheme.entries) for (dark in listOf(false, true)) for (st in styles) for (tr in listOf(0, 50)) {
            val s = ThemeSettings(theme = t, keyboardTransparency = tr).withKeyStyle(st)
            assertReadable("$t dark=$dark $st tr=$tr", palette(s, dark), dark)
        }
    }
}
