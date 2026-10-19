package com.viettelex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Theme "Ghi-ta" (bộ đủ món 10/2026) — y hệt iOS KeyboardThemeTests (testGuitar*, testSpaceDeco*, testSoundhole*). */
class GuitarThemeTests {
    private val base = ThemeJsonActions.Base { t, dk -> t.palette(dk)!! }
    private var savedPaywall = false
    @Before fun setUp() { savedPaywall = PlusGate.paywallEnabled; PlusGate.paywallEnabled = false }
    @After fun tearDown() {
        PlusGate.paywallEnabled = savedPaywall
        PlusGate.install({ null }, debugBuild = false)
    }

    @Test fun guitarFullPackTheme() {
        val t = KeyboardTheme.GUITAR
        val spec = t.spec!!
        assertEquals("guitar", spec.sound); assertEquals("string", spec.effect)
        assertTrue("guitar" in ThemeExtraIds.SOUNDS); assertTrue("string" in ThemeExtraIds.EFFECTS)
        assertTrue(t.isPlus)
        assertEquals("Ghi-ta", spec.nameVi); assertEquals("Guitar", spec.nameEn)
        assertEquals("Guitar", L10nTable.EN[t.viTitle])
        assertEquals("thêm cuối danh sách (sau Halloween)", KeyboardTheme.GUITAR, KeyboardTheme.entries[KeyboardTheme.HALLOWEEN.ordinal + 1])
        val c = ThemeColor
        for (dk in listOf(false, true)) {
            val p = t.palette(dk)!!
            val d = p.decoration!!
            assertEquals(ThemeDecoration.STRINGS, d.motif); assertEquals(ThemeDecoration.INLAY, d.alt)
            assertTrue("lỗ thoát âm cả hai chế độ", p.spaceSoundhole)
            assertFalse(p.rainbowBorder)
            for ((ink, fill, what) in listOf(Triple(p.keyInk, p.keyFill, "phím"), Triple(p.fnInk, p.fnFill, "phím chức năng"),
                    Triple(p.actionInk, p.action, "Enter"), Triple(p.ink, p.specialFill, "phím đè"),
                    Triple(p.barInk ?: p.ink, p.bg, "thanh gợi ý")))
                assertTrue("$what dark=$dk", c.contrast(ink, fill) >= 4.5)
            assertTrue(c.luminance(p.fnFill) < c.luminance(p.keyFill))
            if (!dk) assertTrue(c.luminance(p.bg) > 3 * c.luminance(p.keyFill))
        }
        assertNotEquals(t.palette(false), t.palette(true))
        val yes: (String) -> Boolean = { true }
        assertEquals("âm không tự bật", ThemeExtras.Outcome("string", "guitar", true),
            ThemeExtras.plan(ThemeSettings(theme = t).effectiveKeyStyle, false, yes, yes))
    }

    @Test fun spaceDecoJsonRoundTrip() {
        val t = KeyboardTheme.GUITAR
        val text = ThemeJsonActions.copyText(ThemeEditTarget.Builtin(t), ThemeSettings(theme = t), emptyMap(), base)!!
        assertTrue(text, text.contains("\"spaceDeco\""))
        val back = ThemeSpecParser.parse(text).spec!!
        assertEquals("soundhole", back.light.spaceDeco)
        assertTrue(back.palette(false).spaceSoundhole)
        assertEquals(back, ThemeSpecParser.parse(back.canonical).spec)
        assertEquals("soundhole", ThemeSpec.slots(back.palette(false)).spaceDeco)
        fun errs(v: String) = ThemeSpecParser.parse(
            """{"v":1,"name":{"vi":"x"},"light":{"key":"#FFFFFF","special":"#DDDDDD","ink":"#000000","accent":"#000000","spaceDeco":$v}}""").errors
        assertTrue(errs("\"soundhole\"").isEmpty())
        assertTrue(errs("\"none\"").isEmpty())
        assertEquals(ThemeIssue.Code.badValue, errs("\"rosette\"").first().code)
        assertEquals(ThemeIssue.Code.wrongType, errs("1").first().code)
        val gl = t.spec!!.light
        val patch = gl.copy(spaceDeco = null).patchFrom(gl).first
        assertEquals("none", patch.spaceDeco)
        val st = KeyStyle(light = patch)
        assertFalse(st.applyPatch(t.palette(false)!!, false).spaceSoundhole)
        assertEquals(st, KeyStyleStore.parse(KeyStyleStore.serialize(mapOf("guitar" to st)))["guitar"])
        for (o in KeyboardTheme.entries) if (o != t) assertFalse("$o", o.palette(false)?.spaceSoundhole ?: false)
    }

    @Test fun stringsAndInlayMotifs() {
        val subs = ThemeDecoration.shape(ThemeDecoration.INLAY)
        assertEquals(1, subs.size)
        val pts = ArrayList<DoubleArray>()
        pts.add(doubleArrayOf(subs[0].sx, subs[0].sy))
        for (g in subs[0].segs) { var i = 0; while (i < g.size) { pts.add(doubleArrayOf(g[i], g[i + 1])); i += 2 } }
        var a = 0.0; var sum = 0.0
        for (i in pts.indices) {
            val p = pts[i]; val q = pts[(i + 1) % pts.size]
            a += p[0] * q[1] - q[0] * p[1]; sum += p[0] * 3 + p[1] * 7
            assertTrue(Math.abs(p[0]) <= 0.5 && Math.abs(p[1]) <= 0.5)
        }
        assertTrue(a > 0)
        assertEquals(0.66, sum, 1e-9)
        assertTrue(ThemeDecoration.shape(ThemeDecoration.STRINGS).isEmpty())
        val gaps = listOf(ThemeDecoration.Gap(93.5, 11.0), ThemeDecoration.Gap(146.5, 11.0), ThemeDecoration.Gap(199.5, 11.0))
        val placements = ThemeDecoration.placements(402.0, gaps)
        val s = ThemeDecoration.strings(placements, 402.0)
        assertEquals(listOf(93.5, 146.5, 199.5), s.map { it.y })
        listOf(0.6, 0.9, 1.2).forEachIndexed { i, h -> assertEquals("dây dưới dày hơn", h, s[i].h, 1e-9) }
        for (x in s) { assertEquals(ThemeDecoration.STRING_INSET, x.x0, 0.0); assertEquals(402 - ThemeDecoration.STRING_INSET, x.x1, 0.0) }
        assertEquals(1.5, ThemeDecoration.stringThickness(9), 0.0)
        assertTrue(ThemeDecoration(ThemeDecoration.STRINGS, 0).hasStrings)
        assertTrue(ThemeDecoration(ThemeDecoration.STRINGS, 0, alt = ThemeDecoration.INLAY).hasStrings)
        assertFalse(ThemeDecoration(ThemeDecoration.INLAY, 0).hasStrings)
        assertTrue(ThemeDecoration.strings(emptyList(), 402.0).isEmpty())
        assertEquals((placements.maxOf { it.x + it.size / 2 }) + ThemeDecoration.INSET - ThemeDecoration.STRING_INSET,
            ThemeDecoration.strings(placements, null).first().x1, 1e-9)
        assertTrue(ThemeDecoration.STRINGS in ThemeDeco.MOTIFS && ThemeDecoration.INLAY in ThemeDeco.MOTIFS)
    }

    @Test fun soundholeGeometry() {
        for ((w, h) in listOf(180.0 to 42.0, 120.0 to 38.0, 60.0 to 42.0, 400.0 to 50.0)) {
            assertTrue("$w×$h", Soundhole.outerExtent(w, h) <= 0.47 * h)
            assertTrue(2 * Soundhole.outerExtent(w, h) <= w)
            assertTrue(Soundhole.radius(w, h) > 0)
        }
        assertEquals(0.34 * 42, Soundhole.radius(180.0, 42.0), 1e-9)
        assertEquals(0.18 * 60, Soundhole.radius(60.0, 42.0), 1e-9)
    }
}
