package com.viettelex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Kết quả phép tính theo fixture chung iOS/KeyboardTests/Fixtures/math-results.txt (cùng bộ ca với iOS MathResultsTests). */
class MathResultsTests {
    private fun fixtureLines(): List<List<String>> {
        val f = listOf("../../iOS", "../iOS", "iOS").map { File(it, "KeyboardTests/Fixtures/math-results.txt") }
            .firstOrNull { it.exists() } ?: error("không thấy iOS/KeyboardTests/Fixtures/math-results.txt")
        return f.readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> line.split('|').map { it.replace("␣", " ") } }
    }

    @Test fun fixture() {
        var n = 0
        for (c in fixtureLines()) {
            n++
            when (c[0]) {
                "eval" -> assertEquals("eval ${c[1]}", c[2], MathResults.result(c[1]) ?: "-")
                "chip" -> {
                    val chip = MathResults.chip(c[1])
                    if (c[2] == "-") assertNull("chip «${c[1]}» phải null", chip)
                    else assertEquals("chip «${c[1]}»", NumberChip(c[2], "", c[2]), chip)
                }
                "implicit" -> {
                    val chip = MathResults.implicitChip(c[1])
                    if (c[2] == "-") assertNull("chip ngầm «${c[1]}» phải null", chip)
                    else assertEquals("chip ngầm «${c[1]}»", NumberChip(c[2], "", " " + c[2]), chip)
                }
                else -> error("dòng fixture lạ: $c")
            }
        }
        assertTrue(n > 80)
    }

    /** Cửa chung: "…=" ⇒ chip thường (chèn sau "="); kết thúc bằng phép tính ⇒ chip ngầm. */
    @Test fun chipAtCaret() {
        assertEquals(NumberChip("36", "", "36"), MathResults.chipAtCaret("12*3="))
        assertEquals(NumberChip("= 36", "", " = 36"), MathResults.chipAtCaret("12*3"))
        assertNull(MathResults.chipAtCaret("12/10"))
    }

    /** Luật hình dạng (field report "dấu bằng để rất sâu") — song sinh iOS testLooksLikeExpression. */
    @Test fun looksLikeExpression() {
        for (e in listOf("12*3", "1,5+2,5", "100/4", "12 / 10", "10 : 30", "(1+2)", "7-3", "32/5"))
            assertTrue(e, MathResults.looksLikeExpression(e))
        for (e in listOf("12", "50%", "-5", "12/10", "1/2", "12/10/2026", "2026-10-18", "10:30", "16:9",
                "090-123-4567", "5-10", "1990-2000", "1800-599-920", "1.2.3", "3x4"))
            assertTrue(e, !MathResults.looksLikeExpression(e))
    }

    @Test fun implicitLongContext() {
        assertEquals("= 36", MathResults.implicitChip("chữ ".repeat(500) + "12*3")?.display)
        assertNull(MathResults.implicitChip("1+".repeat(200) + "1"))
    }

    /** Chip số không còn nhận "…=" (tránh 2 chip cùng một kết quả). */
    @Test fun numberChipsIgnoreEquals() = assertNull(NumberChips.chip("12*3="))
}
