package com.viettelex.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        KeyboardTheme.PURE_WHITE, KeyboardTheme.NEON, KeyboardTheme.PINK)
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
        assertEquals(24, rows.size)
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
        val fixed = setOf(KeyboardTheme.OLED, KeyboardTheme.CONTRAST, KeyboardTheme.BLACK_HEART, KeyboardTheme.NEON)
        for (t in KeyboardTheme.entries) {
            val light = t.palette(false) ?: continue
            val dark = t.palette(true)!!
            if (t in fixed) { assertEquals("$t", light, dark); assertTrue("$t", dark.isDark); continue }
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
            assertTrue("$t/$d phím cách", c.contrast(p.ink, space) >= 4.5)
            assertEquals(p.ink, KeyboardTransparency.readable(p.ink, key))
            assertEquals(p.fnInk, KeyboardTransparency.readable(p.fnInk, fn))
            p.decoration?.let { assertTrue("$t/$d hoạ tiết", c.contrast(p.ink, c.over(it.color, p.bg)) >= 4.5) }
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
        assertEquals(10, ThemeSettings(theme = KeyboardTheme.PINK, keyStyle = KeyStyle(corner = 10)).effectiveKeyStyle.corner)
    }

    @Test fun decorationGeometryBoundedAndMatchesIOS() {
        val d = ThemeDecoration
        for ((w, h) in listOf(402.0 to 300.0, 320.0 to 260.0, 1366.0 to 420.0, 20.0 to 20.0)) {
            val pts = d.placements(w, h)
            assertTrue(pts.size / 2 <= d.MAX_MOTIFS)
            var i = 0
            while (i < pts.size) {
                assertTrue(pts[i] - d.MOTIF_SIZE / 2 >= d.INSET - 1e-9 && pts[i] <= w - d.INSET + 1e-9)
                assertTrue(pts[i + 1] - d.MOTIF_SIZE / 2 >= d.INSET - 1e-9 && pts[i + 1] <= h - d.INSET + 1e-9)
                i += 2
            }
        }
        // Mốc chung với iOS (ThemeSetTests.testDecorationGeometryBoundedAndInside): 402×300 → 36 motif.
        assertEquals(36, d.placements(402.0, 300.0).size / 2)
        assertEquals(KeyboardTheme.BLACK_HEART.palette(false)!!.decoration!!.motif, ThemeDecoration.HEARTS)
        assertEquals(KeyboardTheme.GREEN_FLORAL.palette(true)!!.decoration!!.motif, ThemeDecoration.FLOWERS)
        for (t in KeyboardTheme.entries) if (t != KeyboardTheme.BLACK_HEART && t != KeyboardTheme.GREEN_FLORAL)
            for (dk in listOf(false, true)) assertNull("$t", t.palette(dk)?.decoration)
    }

    @Test fun darkComposesWithTransparencyWallpaperAndAdvanced() {
        for (t in pastels + newThemes) {
            val base = t.palette(true)!!
            val wp = base.overWallpaper()
            assertNull(wp.decoration); assertTrue(wp.isDark)
            assertTrue("$t chức năng trên ảnh", c.contrast(wp.ink, c.over(wp.fnFill, c.WHITE)) >= 4.5)
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
