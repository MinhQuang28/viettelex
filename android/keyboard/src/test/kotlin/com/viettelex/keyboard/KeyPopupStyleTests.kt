package com.viettelex.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ô phóng to / popup ký tự phụ theo KIỂU PHÍM của theme (Phil 19/10/2026: popup trơn kiểu Laban Key ⇒
 * phải giống phím). Y hệt iOS PerThemeKeyStyleTests.testKeyPopupStyle*.
 */
class KeyPopupStyleTests {
    private val c = ThemeColor
    private var savedPaywall = false
    @Before fun setUp() { savedPaywall = PlusGate.paywallEnabled; PlusGate.paywallEnabled = false }
    @After fun tearDown() { PlusGate.paywallEnabled = savedPaywall; PlusGate.install({ null }, debugBuild = false) }

    /** Như ImeTheme: theme + tuỳ chỉnh → độ trong suốt → kiểu popup. */
    private fun popup(s: ThemeSettings, dark: Boolean): KeyPopupStyle {
        val base = s.basePalette(dark) ?: ThemeSpec.palette(ThemeSlots(bg = c.rgb(0xEEF0F4), key = c.WHITE,
            special = c.rgb(0xD6DBE3), ink = c.rgb(0x1F1F1F), accent = c.rgb(0x0B57D0)), dark)
        val p = s.styledCurrent(base, dark).withTransparency(s.keyboardTransparency, s.labelTransparency, dark)
        return KeyPopupStyle.make(p, s.effectiveKeyStyle, dark)
    }

    @Test fun followsThemeKeyFillInkAndBorder() {
        for (t in KeyboardTheme.entries) for (dark in listOf(false, true)) {
            val pal = t.palette(dark) ?: continue
            val ps = popup(ThemeSettings(theme = t), dark)
            val tag = "$t $dark"
            assertEquals(tag, c.over(pal.keyFill, c.over(pal.bg, KeyboardTransparency.systemBackdrop(dark))), ps.fill)
            assertEquals(tag, 255, c.alpha(ps.fill))
            assertTrue(tag, c.contrast(ps.ink, ps.fill) >= KeyboardTransparency.MIN_LABEL_CONTRAST)
            assertTrue(tag, c.contrast(ps.selectedInk, ps.selectedFill) >= KeyboardTransparency.MIN_LABEL_CONTRAST)
            assertEquals(tag, c.over(pal.action, c.over(pal.bg, KeyboardTransparency.systemBackdrop(dark))), ps.selectedFill)
            if (pal.keyBorder != null) { assertNotNull(tag, ps.border); assertEquals(1f, ps.borderWidth) }
            else { assertNull(tag, ps.border); assertEquals(0f, ps.borderWidth) }
        }
        // Theme có viền: Hoa xanh / Neon / Tương phản cao.
        for (t in listOf(KeyboardTheme.GREEN_FLORAL, KeyboardTheme.NEON, KeyboardTheme.CONTRAST)) assertNotNull(t.id, popup(ThemeSettings(theme = t), true).border)
        // Hồng: bo góc của theme.
        assertEquals(70, popup(ThemeSettings(theme = KeyboardTheme.PINK), false).cornerPercent)
    }

    @Test fun followsUserCustomizationAndTransparency() {
        val st = KeyStyle(labelColor = 0xFFE066, fillColor = 0x102030, accentColor = 0xFF9500, corner = 100,
            fontScale = 130, font = KeyLabelFont.SERIF, weight = KeyLabelWeight.BOLD)
        val s = ThemeSettings(theme = KeyboardTheme.MINT).withKeyStyle(st)
        val ps = popup(s, false)
        assertEquals(c.rgb(0x102030), ps.fill)
        assertEquals(c.rgb(0xFFE066), ps.ink)
        assertEquals(100, ps.cornerPercent)
        assertEquals(1.3f, ps.labelScale, 1e-6f)
        assertEquals(c.rgb(0xFF9500), ps.selectedFill)
        // Phím trong suốt hẳn: ô phóng to lấy màu nền bàn phím (đục), chữ vẫn đọc được.
        val clear = popup(ThemeSettings(theme = KeyboardTheme.SKY, keyboardTransparency = 100), true)
        assertEquals(255, c.alpha(clear.fill))
        assertTrue(c.contrast(clear.ink, clear.fill) >= KeyboardTransparency.MIN_LABEL_CONTRAST)
        // Một nửa: giữa nền phím và nền bàn phím.
        val half = popup(ThemeSettings(theme = KeyboardTheme.OLED, keyboardTransparency = 50), true)
        assertEquals(255, c.alpha(half.fill))
        // Hết Plus ⇒ tuỳ chỉnh bỏ qua (giống phím).
        PlusGate.paywallEnabled = true; PlusGate.install({ null }, debugBuild = false)
        assertEquals(popup(ThemeSettings(theme = KeyboardTheme.SYSTEM), false), popup(ThemeSettings(theme = KeyboardTheme.SYSTEM).withKeyStyle(st), false))
    }
}
