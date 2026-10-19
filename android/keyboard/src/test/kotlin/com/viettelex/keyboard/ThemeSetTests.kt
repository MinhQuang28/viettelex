package com.viettelex.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Bản tối của theme pastel + bộ theme 10/2026 (Tim đen, Hoa xanh, Xanh lá, Trắng hồng, Neon, Hồng):
 * bảng màu chung với iOS (iOS/KeyboardTests/Fixtures/theme-palettes.tsv — iOS ThemeSetTests đọc cùng
 * file), độ đọc được, cổng Plus, hoạ tiết nền rẻ, và đi cùng độ trong suốt / ảnh nền / Tuỳ chỉnh nâng cao.
 */
class ThemeSetTests {
    private val c = ThemeColor
    private var savedPaywall = false

    @Before fun setUp() { savedPaywall = PlusGate.paywallEnabled; PlusGate.paywallEnabled = false }
    @After fun tearDown() {
        PlusGate.paywallEnabled = savedPaywall
        PlusGate.install({ null }, debugBuild = false)
    }

    private val newThemes = listOf(KeyboardTheme.BLACK_HEART, KeyboardTheme.GREEN_FLORAL, KeyboardTheme.SAGE,
        KeyboardTheme.NEON, KeyboardTheme.PINK, KeyboardTheme.TET, KeyboardTheme.TET_MAI, KeyboardTheme.XMAS,
        KeyboardTheme.XMAS_SNOW, KeyboardTheme.PIANO)
    private val pastels = listOf(KeyboardTheme.PEACH, KeyboardTheme.MINT, KeyboardTheme.SKY, KeyboardTheme.LAVENDER)

    private fun fixtureLines(): List<List<String>> {
        val f = listOf("../../iOS", "../iOS", "iOS").map { File(it, "KeyboardTests/Fixtures/theme-palettes.tsv") }
            .firstOrNull { it.exists() } ?: error("không thấy iOS/KeyboardTests/Fixtures/theme-palettes.tsv")
        return f.readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t') }
    }

    private fun hex(v: Int?): String = if (v == null) "-" else String.format("%08X", v)

    @Test fun palettesMatchSharedFixtureWithIOS() {
        val lines = fixtureLines()
        assertEquals(lines.first { it[0] == "order" }[1].split(','), KeyboardTheme.entries.map { it.id })
        assertEquals(lines.first { it[0] == "plus" }[1].split(','), KeyboardTheme.entries.filter { it.isPlus }.map { it.id })
        val rows = lines.filter { it[0] != "order" && it[0] != "plus" }
        assertEquals(32, rows.size)
        for (r in rows) {
            val t = KeyboardTheme.entries.first { it.id == r[0] }
            val p = t.palette(r[1] == "1")!!
            val deco = p.decoration?.let { "${it.motif}:${hex(it.color)}" } ?: "-"
            val got = listOf(hex(p.bg), hex(p.keyFill), hex(p.specialFill), hex(p.ink), hex(p.action), hex(p.actionInk),
                hex(p.keyBorder), hex(p.functionFill), hex(p.functionInk), deco)
            assertEquals("${r[0]} dark=${r[1]}", r.drop(2), got)
        }
    }

    @Test fun darkVariantsDifferAndFollowSystem() {
        val fixed = setOf(KeyboardTheme.OLED, KeyboardTheme.CONTRAST, KeyboardTheme.BLACK_HEART, KeyboardTheme.NEON,
            KeyboardTheme.TET, KeyboardTheme.XMAS, KeyboardTheme.PIANO)
        for (t in KeyboardTheme.entries) {
            val light = t.palette(false) ?: continue
            val dark = t.palette(true)!!
            // Piano: phím ngà chữ đen nhưng NỀN tối ⇒ vùng ngoài phím theo nhánh tối (surfaceDark).
            if (t in fixed) { assertEquals("$t", light, dark); assertTrue("$t", dark.surfaceDark ?: dark.isDark); continue }
            assertNotEquals("$t", light, dark)
            assertFalse("$t", light.isDark)
            assertTrue("$t bản tối", dark.isDark)
            if (t != KeyboardTheme.GLASS) assertEquals("$t giữ màu nhấn", light.action, dark.action)
        }
    }

    @Test fun labelsReadableInEveryMode() {
        for (t in KeyboardTheme.entries) for (d in listOf(false, true)) {
            val p = t.palette(d) ?: continue
            if (t == KeyboardTheme.GLASS) continue
            val key = c.over(p.keyFill, p.bg); val fn = c.over(p.fnFill, p.bg); val space = c.over(p.spaceFill, p.bg)
            assertTrue("$t/$d phím", c.contrast(p.ink, key) >= 4.5)
            assertTrue("$t/$d phím chức năng", c.contrast(p.fnInk, fn) >= 4.5)
            val spaceInk = if (p.functionFill != null) p.fnInk else p.ink
            assertTrue("$t/$d phím cách", c.contrast(spaceInk, space) >= 4.5)
            val bar = p.barInk ?: p.ink
            assertEquals("$t/$d thanh gợi ý", bar, KeyboardTransparency.readable(bar, p.bg))
            assertEquals(p.ink, KeyboardTransparency.readable(p.ink, key))
            assertEquals(p.fnInk, KeyboardTransparency.readable(p.fnInk, fn))
            p.decoration?.let { assertTrue("$t/$d hoạ tiết", c.contrast(bar, c.over(it.color, p.bg)) >= 4.5) }
        }
    }

    @Test fun newThemesArePlusAndGated() {
        newThemes.forEach { assertTrue("$it", it.isPlus) }
        PlusGate.paywallEnabled = true
        PlusGate.install({ null }, debugBuild = false)
        for (t in newThemes) {
            assertFalse("$t", ThemeGate.allows(t))
            val s = ThemeSettings(theme = t)
            assertEquals(KeyboardTheme.SYSTEM, s.effectiveTheme)
            assertNull("hết Plus ⇒ không mang bo góc Hồng", s.effectiveKeyStyle.corner)
        }
        PlusGate.install({ if (it == Keys.PLUS_UNLOCKED) true else null }, debugBuild = false)
        newThemes.forEach { assertTrue("$it", ThemeGate.allows(it)) }
    }

    @Test fun newThemesRoundTrip() {
        for (t in newThemes) {
            val m = ThemeSettings(theme = t).toMap()
            assertEquals(t, ThemeSettings.load { m[it] }.theme)
            L10n.lang = "en"
            assertNotEquals("", t.title)
        }
        L10n.lang = "vi"
    }

    // ---- âm / hiệu ứng đi kèm theme ("sound" / "effect") — iOS ThemeSetTests y hệt

    @Test fun festiveThemesCarrySoundAndEffect() {
        assertEquals("phao", KeyboardTheme.TET.spec?.sound); assertEquals("blossom", KeyboardTheme.TET.spec?.effect)
        assertEquals("phao", KeyboardTheme.TET_MAI.spec?.sound)
        assertEquals("jingle", KeyboardTheme.XMAS.spec?.sound); assertEquals("snow", KeyboardTheme.XMAS.spec?.effect)
        assertEquals("piano", KeyboardTheme.PIANO.spec?.sound); assertNull(KeyboardTheme.PIANO.spec?.effect)
        assertNull(KeyboardTheme.PEACH.spec?.sound); assertNull(KeyboardTheme.PEACH.spec?.effect)
        assertEquals("phao", ThemeSettings(theme = KeyboardTheme.TET).effectiveKeyStyle.sound)
        for (t in listOf(KeyboardTheme.TET, KeyboardTheme.TET_MAI, KeyboardTheme.XMAS, KeyboardTheme.XMAS_SNOW, KeyboardTheme.PIANO))
            assertNotNull("$t", L10nTable.EN[t.viTitle])
        assertNotNull(L10nTable.EN[ThemeExtras.HINT])
    }

    /** Chép JSON → sửa → dán: "sound" / "effect" đi trọn vòng (theme có sẵn, bản vá, theme tự tạo). */
    @Test fun soundEffectRoundTrip() {
        val base = ThemeJsonActions.Base { t, dk -> t.palette(dk)!! }
        val s = ThemeSettings(theme = KeyboardTheme.TET)
        val text = ThemeJsonActions.copyText(ThemeEditTarget.Builtin(KeyboardTheme.TET), s, emptyMap(), base)!!
        assertTrue(text, text.contains("\"sound\""))
        val back = ThemeSpecParser.parse(text).spec!!
        assertEquals("phao", back.sound); assertEquals("blossom", back.effect)
        assertEquals(back, ThemeSpecParser.parse(back.canonical).spec)
        val edited = text.replace("\"phao\"", "\"wood\"")
        val r = ThemeJsonActions.save(edited, ThemeEditTarget.Builtin(KeyboardTheme.TET), s, emptyMap(), base)
        assertTrue(r.ok)
        assertEquals("wood", r.settings.keyStyles["tet"]?.sound)
        assertEquals("wood", r.settings.effectiveKeyStyle.sound)
        assertNull(r.settings.keyStyles["tet"]?.effect)
        val raw = KeyStyleStore.serialize(r.settings.keyStyles)
        assertEquals(r.settings.keyStyles, KeyStyleStore.parse(raw))
        val same = ThemeJsonActions.save(text, ThemeEditTarget.Builtin(KeyboardTheme.TET), s, emptyMap(), base)
        assertNull(same.settings.keyStyles["tet"]?.sound)
        val restored = CustomThemeStore.parse(CustomThemeStore.serialize(mapOf("mytet" to back.copy(id = "mytet"))))
        assertEquals("phao", restored["mytet"]?.sound); assertEquals("blossom", restored["mytet"]?.effect)
    }

    @Test fun themeExtrasPlanNeverEnablesSound() {
        val yes: (String) -> Boolean = { true }; val no: (String) -> Boolean = { false }
        val st = KeyStyle(sound = "phao", effect = "blossom")
        assertEquals(ThemeExtras.Outcome("blossom", "phao", true), ThemeExtras.plan(st, false, yes, yes))
        assertFalse(ThemeExtras.plan(st, true, yes, yes).soundHint)
        assertEquals(ThemeExtras.Outcome(null, null, false), ThemeExtras.plan(KeyStyle(), false, yes, yes))
        assertEquals(ThemeExtras.Outcome(null, null, false), ThemeExtras.plan(st, false, no, no))
    }

    @Test fun themeExtrasApplyOnSelect() {
        val prefs = HashMap<String, String>()
        prefs[Keys.KEY_SOUND_STYLE] = "subtle"
        fun apply(st: KeyStyle, soundOn: Boolean) = ThemeExtras.applyOnSelect(st, soundOn) { k, v -> prefs[k] = v }
        // Chọn theme có âm + hiệu ứng: lưu kiểu âm + hiệu ứng; âm thanh phím không bị bật (hàm không ghi KEY_SOUND).
        val r = apply(KeyStyle(sound = "wood", effect = "heart"), soundOn = false)
        assertTrue(r.soundHint)
        assertEquals("wood", prefs[Keys.KEY_SOUND_STYLE]); assertEquals("heart", prefs[Keys.KEY_EFFECT])
        assertFalse(prefs.containsKey(Keys.KEY_SOUND))
        // Đổi tay sau đó: giữ — chọn theme KHÔNG khai báo cũng không đổi.
        prefs[Keys.KEY_SOUND_STYLE] = "bubble"; prefs[Keys.KEY_EFFECT] = "ripple"
        assertFalse(apply(ThemeSettings(theme = KeyboardTheme.PEACH).effectiveKeyStyle, soundOn = false).soundHint)
        assertEquals("bubble", prefs[Keys.KEY_SOUND_STYLE]); assertEquals("ripple", prefs[Keys.KEY_EFFECT])
        assertFalse(apply(KeyStyle(sound = "wood"), soundOn = true).soundHint)
        // Id chưa có trên máy ⇒ bỏ qua.
        apply(KeyStyle(sound = "notASound", effect = "notAnEffect"), soundOn = false)
        assertEquals("wood", prefs[Keys.KEY_SOUND_STYLE]); assertEquals("ripple", prefs[Keys.KEY_EFFECT])
        // Khoá Plus: hiệu ứng Plus không được đặt.
        PlusGate.paywallEnabled = true
        PlusGate.install({ null }, debugBuild = false)
        prefs[Keys.KEY_EFFECT] = "none"
        assertNull(apply(KeyStyle(effect = "firework"), soundOn = false).effect)
        assertEquals("none", prefs[Keys.KEY_EFFECT])
    }

    @Test fun pickerSectionsFreeThenPlus() {
        val secs = ThemePicker.sections
        assertEquals(listOf("Miễn phí", "Plus"), secs.map { it.title })
        assertEquals(listOf(KeyboardTheme.SYSTEM, KeyboardTheme.OLED, KeyboardTheme.CONTRAST), secs[0].themes)
        assertEquals(KeyboardTheme.entries.toSet(), (secs[0].themes + secs[1].themes).toSet())
        assertTrue(secs[1].themes.all { it.isPlus })
        for ((vi, en) in listOf("Miễn phí" to "Free", "Chọn giao diện" to "Choose theme", "Giao diện bàn phím" to "Keyboard theme"))
            assertEquals(en, L10nTable.EN[vi])
    }

    @Test fun pickerSelectRespectsPlusGate() {
        val s = ThemeSettings(theme = KeyboardTheme.OLED, keyboardTransparency = 30)
        assertEquals(ThemeSettings(theme = KeyboardTheme.NEON, keyboardTransparency = 30), ThemePicker.select(KeyboardTheme.NEON, s))
        PlusGate.paywallEnabled = true
        PlusGate.install({ null }, debugBuild = false)
        assertNull(ThemePicker.select(KeyboardTheme.BLACK_HEART, s))
        assertEquals(KeyboardTheme.CONTRAST, ThemePicker.select(KeyboardTheme.CONTRAST, s)?.theme)
        PlusGate.install({ if (it == Keys.PLUS_UNLOCKED) true else null }, debugBuild = false)
        assertEquals(KeyboardTheme.BLACK_HEART, ThemePicker.select(KeyboardTheme.BLACK_HEART, s)?.theme)
    }

    @Test fun pinkRoundedByDefaultButUserCornerWins() {
        assertEquals(70, ThemeSettings(theme = KeyboardTheme.PINK).effectiveKeyStyle.corner)
        assertNull(ThemeSettings(theme = KeyboardTheme.PEACH).effectiveKeyStyle.corner)
        assertEquals(10, ThemeSettings(theme = KeyboardTheme.PINK).withKeyStyle(KeyStyle(corner = 10)).effectiveKeyStyle.corner)
    }

    /** Hoạ tiết CHỈ ở khe giữa các hàng phím (bug ảnh 18/10: tim đè chữ gợi ý + ☰) — y hệt iOS. */
    @Test fun decorationOnlyInRowGapsMatchesIOS() {
        val d = ThemeDecoration
        val spans = (0 until 4).flatMap { r -> val t = 46.0 + r * 53; listOf(doubleArrayOf(t, t + 42), doubleArrayOf(t + 0.5, t + 41.5)) }
        val gaps = d.rowGaps(spans)
        assertEquals(listOf(ThemeDecoration.Gap(93.5, 11.0), ThemeDecoration.Gap(146.5, 11.0), ThemeDecoration.Gap(199.5, 11.0)), gaps)
        assertTrue(d.rowGaps(listOf(doubleArrayOf(0.0, 40.0))).isEmpty())
        assertTrue(d.rowGaps(listOf(doubleArrayOf(0.0, 40.0), doubleArrayOf(38.0, 80.0))).isEmpty())
        val pts = d.placements(402.0, gaps)
        assertEquals(18, pts.size)                      // mốc chung iOS (testDecorationOnlyInRowGaps)
        for (p in pts) {
            assertTrue("không lên thanh gợi ý / hàng đầu", p.y - p.size / 2 > 88.0 - 1e-9)
            val g = gaps.first { Math.abs(it.center - p.y) < 1e-9 }
            assertTrue("motif nằm trọn trong khe", p.size <= g.height - 2 * d.CLEARANCE + 1e-9)
            assertTrue(p.x - p.size / 2 >= d.INSET - 1e-9 && p.x + p.size / 2 <= 402 - d.INSET + 1e-9)
        }
        assertTrue(d.placements(800.0, listOf(ThemeDecoration.Gap(50.0, 6.0))).isEmpty())
        assertTrue(d.placements(5000.0, List(8) { ThemeDecoration.Gap(50.0, 11.0) }).size <= d.MAX_MOTIFS)
        assertEquals(KeyboardTheme.BLACK_HEART.palette(false)!!.decoration!!.motif, ThemeDecoration.HEARTS)
        assertEquals(KeyboardTheme.GREEN_FLORAL.palette(true)!!.decoration!!.motif, ThemeDecoration.FLOWERS)
        val festive = mapOf(KeyboardTheme.TET to ThemeDecoration.MAI, KeyboardTheme.TET_MAI to ThemeDecoration.MAI,
            KeyboardTheme.XMAS to ThemeDecoration.SNOWFLAKE, KeyboardTheme.PIANO to ThemeDecoration.NOTES)
        for ((t, m) in festive) for (dk in listOf(false, true)) assertEquals("$t", m, t.palette(dk)!!.decoration!!.motif)
        assertEquals(ThemeDecoration.SNOWFLAKE, KeyboardTheme.XMAS_SNOW.palette(false)!!.decoration!!.motif)
        assertEquals(ThemeDecoration.STAR, KeyboardTheme.XMAS_SNOW.palette(true)!!.decoration!!.motif)
        for (t in KeyboardTheme.entries) if (t != KeyboardTheme.BLACK_HEART && t != KeyboardTheme.GREEN_FLORAL &&
            t != KeyboardTheme.XMAS_SNOW && t !in festive)
            for (dk in listOf(false, true)) assertNull("$t", t.palette(dk)?.decoration)
    }

    @Test fun darkComposesWithTransparencyWallpaperAndAdvanced() {
        for (t in pastels + newThemes) {
            val base = t.palette(true)!!
            val wp = base.overWallpaper()
            assertNull(wp.decoration)
            if (t != KeyboardTheme.PIANO) assertTrue("$t", wp.isDark)   // Piano: phím ngà chữ đen ở cả hai chế độ
            assertTrue("$t chức năng trên ảnh", c.contrast(wp.fnInk, c.over(wp.fnFill, c.WHITE)) >= 4.5)
            wp.barInk?.let { assertEquals("$t thanh gợi ý trên lớp phủ", it, KeyboardTransparency.readable(it, wp.wallpaperOverlay)) }
            val half = base.withTransparency(50, 0, true)
            base.decoration?.let { assertTrue(Math.abs(c.alpha(it.color) / 2 - c.alpha(half.decoration!!.color)) <= 1) }
            base.functionFill?.let { assertEquals(127, c.alpha(half.functionFill!!)) }
            val full = base.withTransparency(100, 0, true)
            val sys = KeyboardTransparency.systemBackdrop(true)
            assertTrue("$t", c.contrast(full.fnInk, c.over(full.fnFill, sys)) >= KeyboardTransparency.MIN_LABEL_CONTRAST)
            assertTrue("$t", c.contrast(full.keyInk, c.over(full.keyFill, sys)) >= KeyboardTransparency.MIN_LABEL_CONTRAST)
            val adv = KeyStyle(labelColor = 0xFF2D55, fillColor = 0x102030).applyColors(base)
            assertNull(adv.functionFill); assertEquals(c.rgb(0xFF2D55), adv.fnInk)
            val wf = KeyStyle(fillColor = 0xFFFFFF).applyColors(base)
            assertTrue("$t", c.contrast(wf.fnInk, wf.fnFill) >= KeyboardTransparency.MIN_LABEL_CONTRAST)
        }
    }
}
