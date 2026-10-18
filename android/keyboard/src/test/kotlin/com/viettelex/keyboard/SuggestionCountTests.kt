package com.viettelex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "Số từ gợi ý" ([SuggestionCount]) — cùng bảng ca với iOS SuggestionCountTests.swift (so payload). */
class SuggestionCountTests {
    @Before fun setUp() = TestAssets.install()

    private val N = SuggestionSet.NUMBER_TOKEN
    private val ADD = SuggestionSet.ADD_TONES_TOKEN
    private val M = SuggestionSet.MATH_TOKEN

    private fun payloads(set: SuggestionSet, count: Int, middle: Boolean = false): String =
        when (val l = SuggestionSlots.arrange(set, undoAsPill = true, bestInMiddle = middle, count = count)) {
            is BarLayout.Pill -> "pill:" + l.chip.payload
            BarLayout.PasteCard -> "paste"
            is BarLayout.Chips -> "chips:" + l.chips.joinToString("|") { it.payload }
            is BarLayout.Slots -> l.slots.joinToString("|") { it?.payload ?: "-" } +
                (if (l.emojis.isEmpty()) "" else " e:" + l.emojis.joinToString(""))
        }

    // MARK: cài đặt

    @Test fun parseResolveStep() {
        assertEquals(0, SuggestionCount.parse(null))
        assertEquals(4, SuggestionCount.parse(4))
        assertEquals(5, SuggestionCount.parse(9))
        assertEquals(0, SuggestionCount.parse(-2))
        assertEquals(2, SuggestionCount.parse("2"))
        assertEquals("Bool không phải số ô", 0, SuggestionCount.parse(true))
        assertEquals("Tự động = 3 như trước (điện thoại lẫn tablet)", 3, SuggestionCount.resolved(0))
        for (v in 1..5) assertEquals(v, SuggestionCount.resolved(v))
        assertEquals(1, SuggestionCount.step(0, 1))
        assertEquals(0, SuggestionCount.step(1, -1))
        assertEquals(0, SuggestionCount.step(0, -1))
        assertEquals(5, SuggestionCount.step(5, 1))
        // prefs → KeyboardSettings
        assertEquals(0, KeyboardSettings.load { null }.suggestionCount)
        assertEquals(4, KeyboardSettings.load { if (it == Keys.SUGGESTION_COUNT) 4 else null }.suggestionCount)
        assertEquals(5, KeyboardSettings.load { if (it == Keys.SUGGESTION_COUNT) 12 else null }.suggestionCount)
        assertEquals("suggestionCount", Keys.SUGGESTION_COUNT)   // cùng khoá iOS App Group
    }

    /** Ô < 44 dp ⇒ bớt ô; tối thiểu 1; chưa đo ⇒ số muốn. */
    @Test fun minWidthFallback() {
        assertEquals(5, SuggestionCount.fit(5, 256f))   // điện thoại 360 dp: bar ≈ 256 dp khi có gợi ý
        assertEquals(4, SuggestionCount.fit(5, 216f))   // + nút 😊 trên thanh
        assertEquals(2, SuggestionCount.fit(5, 100f))
        assertEquals(1, SuggestionCount.fit(5, 20f))
        assertEquals(3, SuggestionCount.fit(0, 256f))
        assertEquals(2, SuggestionCount.fit(2, 1000f))
        assertEquals(4, SuggestionCount.fit(4, 0f))
    }

    @Test fun backupIncludesKey() {
        assertEquals(SettingKind.IntRange(0, 0..5), BackupSettings.byKey.getValue(Keys.SUGGESTION_COUNT).kind)
    }

    // MARK: sắp ô

    @Test fun composingPerCount() {
        val s = SuggestionSet(literal = "anh", word = "ánh", word2 = "ảnh", moreWords = listOf("anh", "ành", "ạnh"))
        assertEquals("1 ô = ứng viên chính", "ánh", payloads(s, 1))
        assertEquals("anh|ánh", payloads(s, 2))
        assertEquals("anh|ánh|ảnh", payloads(s, 3))
        assertEquals("anh|ánh|ảnh|anh", payloads(s, 4))
        assertEquals("anh|ánh|ảnh|anh|ành", payloads(s, 5))
        val e = s.copy(emojis = listOf("😀", "🙂", "😎"))
        assertEquals("< 3 ô: không vùng emoji", "anh|ánh", payloads(e, 2))
        assertEquals("anh|ánh|- e:😀🙂😎", payloads(e, 3))
        assertEquals("ô cuối hẹp ⇒ ≤ 2 emoji", "anh|ánh|ảnh|anh|- e:😀🙂", payloads(e, 5))
        assertEquals("không ứng viên ⇒ nguyên văn", "xyz", payloads(SuggestionSet(literal = "xyz"), 1))
    }

    @Test fun nextWordsPerCount() {
        val n = SuggestionSet(nextWords = listOf("a", "b", "c", "d", "e"))
        assertEquals("a", payloads(n, 1))
        assertEquals("a|b|c|d", payloads(n, 4))
        assertEquals("a|b|c|d|e", payloads(n, 5))
        assertEquals("b|a|c", payloads(n, 3, middle = true))
        assertEquals("b|a|c|d", payloads(n, 4, middle = true))
        assertEquals("d|b|a|c|e", payloads(n, 5, middle = true))
        assertEquals(listOf(2, 1, 3, 0, 4), SuggestionSlots.nextOrder(5, true))
        assertEquals(listOf(0, 1), SuggestionSlots.nextOrder(2, true))
        assertEquals(1, SuggestionSlots.primaryIndex(3, nextWords = true, bestInMiddle = true))
        assertEquals(2, SuggestionSlots.primaryIndex(5, nextWords = true, bestInMiddle = true))
        assertEquals(1, SuggestionSlots.primaryIndex(5, nextWords = false, bestInMiddle = true))
        assertEquals(0, SuggestionSlots.primaryIndex(1, nextWords = true, bestInMiddle = true))
    }

    @Test fun chipsPerCount() {
        val s = SuggestionSet(nextWords = listOf("a", "b", "c", "d", "e"), actionLabel = "Thêm dấu", action = ADD)
        assertEquals(ADD, payloads(s, 1))
        assertEquals("$ADD|a|b|c|d", payloads(s, 5))
        val n = s.copy(number = "x")
        assertEquals("$ADD|$N|a|b|c", payloads(n, 5))
        assertEquals(N, payloads(n, 1))
        val m = SuggestionSet(nextWords = listOf("a", "b", "c", "d"), math = "36")
        assertEquals("$M|a|b|c", payloads(m, 4))
        assertEquals(M, payloads(m, 1))
        assertEquals("paste", payloads(SuggestionSet(paste = true), 5))
    }

    // MARK: ranking xin đủ N

    @Test fun nextFillReturnsN() {
        val m = UserLangModel().apply { isKnownWord = { VNSuggest.contains(it) }; seedIfEmpty() }
        val personal = SensitiveWords.filter(m.nextWords("không", "tôi", 6), true)
        val tc = { w: String -> m.trigramCount("tôi", "không", w) }
        val three = SuggestRank.nextFill("không", "tôi", personal, tc)
        for (n in 1..5) {
            val r = SuggestRank.nextFill("không", "tôi", personal, tc, need = n)
            assertEquals("need $n: $r", n, r.size)
            assertEquals(r.size, r.toSet().size)
            assertEquals(three.take(minOf(n, 3)), r.take(minOf(n, 3)))
        }
        assertEquals(5, SuggestRank.nextFill("không", null, emptyList(), { 0 }, need = 5).size)
    }

    /** Đường thật qua session: cài đặt 5 ⇒ 5 từ kế tiếp và nguyên văn + 4 ứng viên khi đang gõ. */
    @Test fun sessionAsksForN() {
        val s = KeyboardSession(UserLangModel(), null) { 1_000_000L }
        s.startInput(KeyboardSettings(suggestionCount = 5), FieldTraits(capSentences = true))
        val p = MockProxy()
        for (c in "toi di ") s.handle(if (c == ' ') Key.Space else Key.Letter(c), p)
        val next = s.suggestionsNow(p)!!
        assertEquals(next.nextWords.toString(), 5, next.nextWords.size)
        for (c in "an") s.handle(Key.Letter(c), p)
        val typing = s.suggestionsNow(p)!!
        if (typing.emojis.isEmpty()) {
            val words = listOfNotNull(typing.word, typing.word2) + typing.moreWords
            assertEquals(words.toString(), 4, words.size)
        }
        assertTrue(payloads(typing, 5).startsWith("an|"))
        // Tự động: như trước — 3 từ kế tiếp, không moreWords.
        val a = KeyboardSession(UserLangModel(), null) { 1_000_000L }
        a.startInput(KeyboardSettings(), FieldTraits(capSentences = true))
        val q = MockProxy()
        for (c in "toi di ") a.handle(if (c == ' ') Key.Space else Key.Letter(c), q)
        assertEquals(3, a.suggestionsNow(q)!!.nextWords.size)
        for (c in "an") a.handle(Key.Letter(c), q)
        assertTrue(a.suggestionsNow(q)!!.moreWords.isEmpty())
    }
}
