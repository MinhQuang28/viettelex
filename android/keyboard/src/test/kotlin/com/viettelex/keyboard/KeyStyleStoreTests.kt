package com.viettelex.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Tuỳ chỉnh nâng cao" RIÊNG từng theme + "Màu nhấn" + chuyển đổi (khoá chung cũ, "Trắng hồng" đã bỏ).
 * Y hệt iOS PerThemeKeyStyleTests — chuỗi lưu trùng từng ký tự ([GOLDEN]).
 */
class KeyStyleStoreTests {
    private val c = ThemeColor
    private var savedPaywall = false

    @Before fun setUp() { savedPaywall = PlusGate.paywallEnabled; PlusGate.paywallEnabled = false }
    @After fun tearDown() {
        PlusGate.paywallEnabled = savedPaywall
        PlusGate.install({ null }, debugBuild = false)
    }

    private fun lockPlus() {
        PlusGate.paywallEnabled = true
        PlusGate.install({ null }, debugBuild = false)
    }

    companion object {
        /** Chuỗi chuẩn — iOS PerThemeKeyStyleTests.golden phải trùng (sao lưu đi qua lại). */
        const val GOLDEN = """{"pink":{"accent":14037868,"corner":70},"system":{"fill":1056816,"font":"mono","label":16723285,"weight":"heavy"}}"""
        val GOLDEN_MAP = mapOf(
            "pink" to KeyStyle(accentColor = 0xD6336C, corner = 70),
            "system" to KeyStyle(labelColor = 0xFF2D55, fillColor = 0x102030, font = KeyLabelFont.MONO, weight = KeyLabelWeight.HEAVY),
        )
    }

    /** Pref giả (SharedPreferences.all). */
    private class FakePrefs(val m: MutableMap<String, Any?> = HashMap()) {
        val get: (String) -> Any? = { m[it] }
        /** Như saveThemeSettings (app): ghi toMap + xoá khoá cũ. */
        fun save(s: ThemeSettings) { m.putAll(s.toMap()); KeyStyleStore.LEGACY_KEYS.forEach { m.remove(it) } }
        /** Như migrateThemeSettings (app). */
        fun migrate(): Boolean { if (!ThemeSettings.needsMigration(get)) return false; save(ThemeSettings.load(get)); return true }
    }

    /** Palette như ImeTheme dựng (SYSTEM dùng màu resource ⇒ giả lập). */
    private fun imePalette(s: ThemeSettings, dark: Boolean): ThemePalette {
        val base = s.effectiveTheme.palette(dark) ?: ThemePalette(bg = c.rgb(0xEEF0F4), keyFill = c.WHITE,
            specialFill = c.rgb(0xD6DBE3), ink = c.rgb(0x1F1F1F), trail = c.rgb(0x0B57D0), balloon = c.WHITE,
            popup = c.WHITE, action = c.rgb(0x0B57D0), actionInk = c.WHITE, chip = c.rgb(0xD3E3FD), isDark = dark)
        return s.styled(s.effectiveTheme, base).withTransparency(s.keyboardTransparency, s.labelTransparency, dark)
    }

    @Test fun canonicalFormatMatchesIOS() {
        assertEquals(GOLDEN, KeyStyleStore.serialize(GOLDEN_MAP))
        assertEquals(GOLDEN_MAP, KeyStyleStore.parse(GOLDEN))
        assertEquals(GOLDEN, KeyStyleStore.normalize(
            """{ "system": {"weight":"heavy","label":16723285,"font":"mono","fill":1056816}, "pink":{"corner":70,"accent":14037868} }"""))
        assertNull(KeyStyleStore.normalize("[1,2]"))
        assertNull(KeyStyleStore.normalize("oops"))
        assertEquals("{}", KeyStyleStore.serialize(emptyMap()))
        assertEquals("keyStyles", Keys.KEY_STYLES)
    }

    // ---- riêng từng theme

    @Test fun perThemeIsolation() {
        var s = ThemeSettings(theme = KeyboardTheme.MINT).withKeyStyle(KeyStyle(fillColor = 0x102030, accentColor = 0xFF9500, corner = 0))
        s = s.copy(theme = KeyboardTheme.PEACH)
        assertEquals("đổi theme ⇒ thấy tuỳ chỉnh của theme đó (chưa có)", KeyStyle(), s.keyStyle)
        s = s.withKeyStyle(KeyStyle(labelColor = 0x3366FF))
        s = s.copy(theme = KeyboardTheme.MINT)
        assertEquals(KeyStyle(fillColor = 0x102030, accentColor = 0xFF9500, corner = 0), s.keyStyle)
        assertEquals(c.rgb(0x102030), imePalette(s, false).keyFill)
        s = s.copy(theme = KeyboardTheme.PEACH)
        val p = imePalette(s, false)
        val peach = KeyboardTheme.PEACH.palette(false)!!
        assertEquals("màu nền của mint không lan sang peach", peach.keyFill, p.keyFill)
        assertEquals(c.rgb(0x3366FF), p.keyInk)
        assertEquals(peach.action, p.action)
        assertFalse(p.accentShift)
        val prefs = FakePrefs(); prefs.save(s)
        assertEquals(s.keyStyles, ThemeSettings.load(prefs.get).keyStyles)
        assertEquals(setOf("mint", "peach"), s.keyStyles.keys)
        // Thẻ "Chọn giao diện" phản ánh tuỳ chỉnh riêng; theme chưa chỉnh = palette gốc.
        assertEquals(c.rgb(0x102030), s.styled(KeyboardTheme.MINT, KeyboardTheme.MINT.palette(false)!!).keyFill)
        assertEquals(KeyboardTheme.SKY.palette(false), s.styled(KeyboardTheme.SKY, KeyboardTheme.SKY.palette(false)!!))
        assertEquals(70, s.effectiveStyleFor(KeyboardTheme.PINK).corner)
    }

    @Test fun resetOnlyCurrentTheme() {
        var s = ThemeSettings(theme = KeyboardTheme.MINT).withKeyStyle(KeyStyle(accentColor = 0x00FF00))
            .withKeyStyle(KeyStyle(corner = 10), KeyboardTheme.OLED)
        s = s.withKeyStyle(KeyStyle.DEFAULT)                      // "Đặt lại về mặc định của giao diện"
        assertNull(s.keyStyles["mint"])
        assertEquals(KeyStyle(corner = 10), s.styleFor(KeyboardTheme.OLED))
        val prefs = FakePrefs(); prefs.save(s)
        assertEquals(mapOf("oled" to KeyStyle(corner = 10)), ThemeSettings.load(prefs.get).keyStyles)
        assertEquals(emptyMap<String, KeyStyle>(), s.resetToDefaults().keyStyles)   // "Khôi phục giao diện gốc"
    }

    /** 0 chi phí khi chưa chỉnh: palette y như theme gốc. */
    @Test fun noCustomizationIsBaseline() {
        val s = ThemeSettings(theme = KeyboardTheme.SKY)
        assertEquals(KeyStyle(), s.effectiveKeyStyle)
        val base = KeyboardTheme.SKY.palette(true)!!
        assertEquals(base, KeyStyle().applyAccent(base))
        assertEquals(base, s.styled(KeyboardTheme.SKY, base))
        assertEquals("{}", s.toMap()[Keys.KEY_STYLES])
    }

    // ---- màu nhấn

    @Test fun accentOverrideAndInkContrast() {
        val accents = listOf(0x000000, 0xFFFFFF, 0xFFD60A, 0xD6336C, 0x00BFB2, 0x7F7F7F, 0x2C6BB8, 0x9EE0B6)
        for (t in KeyboardTheme.entries) for (dark in listOf(false, true)) for (a in accents) {
            val s = ThemeSettings(theme = t).withKeyStyle(KeyStyle(accentColor = a))
            val p = imePalette(s, dark)
            val tag = "$t $dark ${Integer.toHexString(a)}"
            assertEquals(tag, c.rgb(a), p.action)
            assertTrue(tag, p.accentShift)
            assertEquals(tag, c.withAlpha(c.rgb(a), 0.6f), p.trail)
            assertTrue(tag, c.contrast(p.actionInk, p.action) >= KeyboardTransparency.MIN_LABEL_CONTRAST)
            val base = t.palette(dark) ?: continue
            if (c.contrast(base.actionInk, c.rgb(a)) >= KeyboardTransparency.MIN_LABEL_CONTRAST) assertEquals(tag, base.actionInk, p.actionInk)
        }
        assertEquals(c.BLACK, KeyStyle.accentInk(c.WHITE, c.WHITE))
        // Glyph màu nhấn của theme (functionInk) đi theo màu nhấn, vẫn đọc được trên phím chức năng.
        val p = KeyboardTheme.PEACH.palette(false)!!.copy(functionInk = c.rgb(0xC2255C))
        assertEquals(c.rgb(0x1F7A5C), KeyStyle(accentColor = 0x1F7A5C).applyAccent(p).functionInk)
        val pale = KeyStyle(accentColor = 0xFFF8F7).applyAccent(p)
        assertTrue(c.contrast(pale.functionInk!!, c.over(p.fnFill, p.bg)) >= KeyboardTransparency.MIN_LABEL_CONTRAST)
        // Hết Plus ⇒ màu nhấn không áp (giá trị giữ nguyên); mua lại ⇒ áp lại.
        val locked = ThemeSettings(theme = KeyboardTheme.OLED).withKeyStyle(KeyStyle(accentColor = 0xFF0000))
        lockPlus()
        val lp = imePalette(locked, true)
        assertEquals(KeyboardTheme.OLED.palette(true)!!.action, lp.action)
        assertFalse(lp.accentShift)
        assertEquals(0xFF0000, locked.keyStyle.accentColor)
        PlusGate.paywallEnabled = false
        assertEquals(c.rgb(0xFF0000), imePalette(locked, true).action)
    }

    // ---- chuyển đổi

    @Test fun migrationFromGlobalKeysIsIdempotent() {
        val prefs = FakePrefs(hashMapOf(Keys.KEYBOARD_THEME to "mint", Keys.KEY_LABEL_COLOR to 0xFF2D55,
            Keys.KEY_FILL_COLOR to -1, Keys.KEY_CORNER_RADIUS to 100, Keys.KEY_FONT_SCALE to 0,
            Keys.KEY_LABEL_FONT to "serif", Keys.KEY_LABEL_WEIGHT to "regular"))
        val want = KeyStyle(labelColor = 0xFF2D55, corner = 100, font = KeyLabelFont.SERIF)
        // IME (app chưa chạy): chuyển trong bộ nhớ, không ghi.
        assertEquals(mapOf("mint" to want), ThemeSettings.load(prefs.get).keyStyles)
        assertNotNull(prefs.m[Keys.KEY_LABEL_COLOR])
        // App: ghi một lần, khoá cũ bị xoá; theme khác không nhận.
        assertTrue(prefs.migrate())
        for (k in KeyStyleStore.LEGACY_KEYS) assertNull(k, prefs.m[k])
        assertEquals(KeyStyleStore.serialize(mapOf("mint" to want)), prefs.m[Keys.KEY_STYLES])
        val after = ThemeSettings.load(prefs.get)
        assertEquals(KeyboardTheme.MINT, after.theme)
        assertEquals(mapOf("mint" to want), after.keyStyles)
        assertEquals(KeyStyle(), after.styleFor(KeyboardTheme.PEACH))
        // Lần 2: không làm gì.
        assertFalse(prefs.migrate())
        assertEquals(after, ThemeSettings.load(prefs.get))
        // Hàm thuần: áp lên kết quả của chính nó ⇒ không đổi; không ghi đè mục đã có.
        val once = KeyStyleStore.migrate("mint", emptyMap(), want)
        assertEquals(once, KeyStyleStore.migrate(once.theme, once.styles, want))
        assertEquals(KeyStyle(corner = 5), KeyStyleStore.migrate("mint", mapOf("mint" to KeyStyle(corner = 5)), want).styles["mint"])
        // Theme lạ / chưa chọn ⇒ bộ chung về Hệ thống.
        assertEquals(mapOf("system" to want), KeyStyleStore.migrate("future", emptyMap(), want).styles)
        assertEquals(mapOf("system" to want), KeyStyleStore.migrate(null, emptyMap(), want).styles)
        // Bộ chung toàn mặc định (giá trị canh) ⇒ không tạo mục nào nhưng vẫn dọn khoá cũ.
        val e = FakePrefs(hashMapOf(Keys.KEY_LABEL_COLOR to -1, Keys.KEY_LABEL_FONT to "default"))
        assertTrue(e.migrate())
        assertEquals(emptyMap<String, KeyStyle>(), ThemeSettings.load(e.get).keyStyles)
        assertNull(e.m[Keys.KEY_LABEL_COLOR])
        assertFalse(e.migrate())
    }

    /** "Trắng hồng" (bản 1.5.12 đã phát hành) ⇒ Hệ thống + màu nhấn D6336C (chỉ khi Hệ thống chưa có). */
    @Test fun pureWhiteMigratesToSystemWithPinkAccent() {
        val prefs = FakePrefs(hashMapOf(Keys.KEYBOARD_THEME to "pureWhite"))
        val s = ThemeSettings.load(prefs.get)
        assertEquals(KeyboardTheme.SYSTEM, s.theme)
        assertEquals(mapOf("system" to KeyStyle(accentColor = 0xD6336C)), s.keyStyles)
        assertTrue(prefs.migrate())
        assertEquals("system", prefs.m[Keys.KEYBOARD_THEME])
        assertFalse(prefs.migrate())
        assertEquals(s, ThemeSettings.load(prefs.get))
        assertEquals(c.rgb(0xD6336C), imePalette(s, false).action)
        // Hệ thống đã có màu nhấn ⇒ giữ; mục "pureWhite" bỏ.
        val m = KeyStyleStore.migrate("pureWhite",
            mapOf("system" to KeyStyle(accentColor = 0x00FF00), "pureWhite" to KeyStyle(corner = 3)), KeyStyle(corner = 40))
        assertEquals("system", m.theme)
        assertEquals(mapOf("system" to KeyStyle(accentColor = 0x00FF00)), m.styles)
        // Bộ chung cũ + Trắng hồng ⇒ cả hai vào Hệ thống; lặp lại không đổi.
        val both = KeyStyleStore.migrate("pureWhite", emptyMap(), KeyStyle(fillColor = 0x111111))
        assertEquals(mapOf("system" to KeyStyle(fillColor = 0x111111, accentColor = 0xD6336C)), both.styles)
        assertEquals(both.styles, KeyStyleStore.migrate(both.theme, both.styles, KeyStyle(fillColor = 0x111111)).styles)
        assertTrue(KeyboardTheme.entries.none { it.id == "pureWhite" })
    }

    @Test fun unknownThemeFallsBackToSystem() {
        for (raw in listOf("pureWhite", "future", "", "PINK")) {
            assertEquals(raw, KeyboardTheme.SYSTEM, ThemeSettings.load { if (it == Keys.KEYBOARD_THEME) raw else null }.theme)
        }
        // Mục của theme lạ (bản mới hơn) giữ nguyên khi khứ hồi.
        val prefs = FakePrefs(hashMapOf(Keys.KEYBOARD_THEME to "system", Keys.KEY_STYLES to """{"future":{"corner":20}}"""))
        val s = ThemeSettings.load(prefs.get)
        assertEquals(KeyStyle(corner = 20), s.keyStyles["future"])
        prefs.save(s)
        assertEquals("""{"future":{"corner":20}}""", prefs.m[Keys.KEY_STYLES])
    }

    // ---- sao lưu

    @Test fun backupRoundTripPerTheme() {
        val prefs = FakePrefs(); prefs.save(ThemeSettings(theme = KeyboardTheme.PINK, keyStyles = GOLDEN_MAP))
        val snap = BackupPrefs.snapshot(prefs.get, emptyList(), null)
        assertEquals(GOLDEN, snap.settings!![Keys.KEY_STYLES])
        val back = BackupCodec.decode(BackupCodec.encode(snap))
        assertEquals(GOLDEN, back.settings!![Keys.KEY_STYLES])
        val plan = BackupPrefs.plan(back, { null }, emptyList())
        assertEquals(GOLDEN_MAP, ThemeSettings.load { plan.writes[it] }.keyStyles)
        // File cũ có khoá chung ⇒ bỏ qua như key lạ; chuỗi lộn xộn ⇒ chuẩn hoá; rác ⇒ bỏ.
        val old = """{"format":"viettelex-backup","version":1,"settings":{"keyLabelColor":255,"keyStyles":"{ \"oled\" : {\"corner\":0} }"}}"""
        val p = BackupCodec.decode(old)
        assertNull(p.settings!!["keyLabelColor"])
        assertEquals("""{"oled":{"corner":0}}""", p.settings!![Keys.KEY_STYLES])
        val junk = BackupCodec.decode("""{"format":"viettelex-backup","version":1,"settings":{"keyStyles":"oops"}}""")
        assertNull(junk.settings!![Keys.KEY_STYLES])
    }
}
