package com.viettelex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Port iOS EngineBridgeTests.swift (EngineBridgeTests + SuggestionTests + UserLangModelTests + …). */
class EngineBridgeTests {
    @Before fun setUp() = TestAssets.install()

    private fun type(keys: String, secure: Boolean = false, settings: KeyboardSettings = KeyboardSettings()): String {
        val proxy = MockProxy(secure)
        val bridge = EngineBridge(settings)
        for (ch in keys) when (ch) {
            ' ' -> bridge.boundary(" ", proxy)
            '⌫' -> bridge.backspace(proxy)
            else -> bridge.letter(ch, proxy)
        }
        return proxy.text
    }

    /** Tên app (Phil 18/10/2026): "viettelex"/"telex" gõ ra nguyên văn (x = ngã, ee = ê không áp). = TelexCore testAppNameStaysLiteral. */
    @Test fun testAppNameStaysLiteral() {
        assertEquals("viettelex ", type("viettelex "))
        assertEquals("bộ gõ viettelex ", type("booj gox viettelex "))
        assertEquals("kiểu telex ", type("kieeur telex "))
        assertEquals("tên tác giả: trinh không dấu", "Phil trinh ", type("Phil trinh "))
    }

    /**
     * composeTrial dùng lại MỘT engine mỗi luồng (RAM-AUDIT #8): kết quả phải y hệt engine mới
     * tinh, bất kể lượt trước để lại gì (từ dài tràn, VNI, hủy dấu, chữ hoa…).
     */
    @Test fun testComposeTrialReuseMatchesFreshEngine() {
        val raws = listOf("tieengs", "Ddaay", "hello", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "dduwowngf",
            "his", "khoer", "thuys", "ass", "", "wf", "Deffault", "Vieetj", "nguoiwf", "tieengs")
        for (vni in listOf(false, true)) for (simple in listOf(false, true)) {
            val st = KeyboardSettings(vniMode = vni, simpleTelex = simple)
            val bridge = EngineBridge(st)
            for (r in raws + raws.reversed()) {
                val fresh = com.viettelex.telexcore.TelexEngine().also {
                    it.vniMode = vni; it.simpleTelex = simple
                    it.freeMarking = st.freeMarking; it.liveSpellCheck = st.liveSpellCheck
                    it.quickTelex = st.quickTelex; it.modernTone = st.modernTone; it.teencode = st.teencode
                }
                for (ch in r) fresh.feed(ch)
                assertEquals("vni=$vni simple=$simple «$r»", fresh.composed, bridge.composeTrial(r))
            }
        }
        // Luồng khác: engine riêng, cùng kết quả.
        val bridge = EngineBridge()
        var other: String? = null
        Thread { other = bridge.composeTrial("tieengs") }.apply { start(); join() }
        assertEquals("tiếng", other)
    }

    @Test fun testBasicVietnamese() {
        assertEquals("việt ", type("vieetj "))
        assertEquals("Tiếng Việt rất hay ", type("Tieengs Vieetj raats hay "))
        assertEquals("đường ", type("dduwowngf "))
    }

    /**
     * iOS test này FAIL sẵn trên clean tree ở "off" (engine hiện tại chốt khác) — kỳ vọng
     * "off" ở đây chốt theo hành vi THẬT của engine (xem [testOffActualEngineBehaviour]).
     */
    @Test fun testAutoRestoreAndCollisions() {
        assertEquals("google ", type("google "))
        assertEquals("hí ", type("his "))          // collision THẬT: tiếng Việt thắng
        assertEquals("Default ", type("Deffault "))  // cancel keeps composed
    }

    @Test fun testOffActualEngineBehaviour() {
        // iOS kỳ vọng "off " nhưng engine hiện tại (TelexCore, POLICY V2 31/07: trailing
        // cancel thắng dict) cho "of " — pin theo engine thật.
        assertEquals("of ", type("off "))
    }

    @Test fun testBackspace() {
        assertEquals("việ", type("vieetj⌫"))
        assertEquals("tóa", type("toans⌫"))
        assertEquals("", type("a⌫"))
        assertEquals("", type("⌫"))
    }

    @Test fun testSecureFieldBypassesEngine() {
        assertEquals("vieejt ", type("vieejt ", secure = true))
    }

    @Test fun testPassthroughBypassesEngine() {
        val p = MockProxy(); val b = EngineBridge(); b.passthrough = true
        for (c in "vieejt") b.letter(c, p)
        b.boundary(" ", p)
        assertEquals("vieejt ", p.text)
    }

    @Test fun testResetDropsComposition() {
        // reEdit tắt: bật thì "e" ngay sau "vie" nạp lại từ trên màn hình (ReEditTests).
        val proxy = MockProxy().also { it.reEdit = false }
        val bridge = EngineBridge(KeyboardSettings())
        for (ch in "vie") bridge.letter(ch, proxy)
        bridge.reset()
        for (ch in "em") bridge.letter(ch, proxy)
        bridge.boundary(" ", proxy)
        assertEquals("vieem ", proxy.text)
    }

    @Test fun testSettingsRespected() {
        val s = KeyboardSettings()
        s.simpleTelex = true
        assertEquals("cw ", type("cw ", settings = s))
        s.simpleTelex = false
        assertEquals("như ", type("nhw ", settings = s))
    }

    @Test fun testContextualEnglish() {
        val s = KeyboardSettings()
        assertTrue(s.contextualEnglish)
        assertEquals("he is ", type("he is ", settings = s))
        assertEquals("sao í ", type("sao is ", settings = s))
        s.contextualEnglish = false
        assertEquals("he í ", type("he is ", settings = s))
    }

    @Test fun testTeencodeToggle() {
        val s = KeyboardSettings()
        assertFalse(s.teencode)
        assertEquals("was ", type("was ", settings = s))
        assertEquals("kos ", type("kos ", settings = s))
        s.teencode = true
        assertEquals("wá ", type("was ", settings = s))
        assertEquals("kó ", type("kos ", settings = s))
        assertEquals("thík ", type("thiks ", settings = s))
    }

    @Test fun testDefaultsMatchIOS() {
        val s = KeyboardSettings()
        assertTrue(s.simpleTelex && s.freeMarking && s.liveSpellCheck && s.autoRestore)
        assertFalse(s.quickTelex || s.modernTone || s.teencode || s.hapticFeedback)
        assertTrue(s.contextualEnglish && s.autoFixAdjacent && s.showSuggestions && s.filterSensitive)
    }
}

class SuggestionTests {
    @Before fun setUp() = TestAssets.install()

    @Test fun testEmojiSameForViAndEn() {
        assertEquals(3, EmojiSuggest.emojis("love").size)
        assertEquals(EmojiSuggest.emojis("love"), EmojiSuggest.emojis("yêu"))
        assertEquals(EmojiSuggest.emojis("cat"), EmojiSuggest.emojis("mèo"))
        assertTrue(EmojiSuggest.emojis("").isEmpty())
    }

    @Test fun testInlineDiacriticCompatibleMatch() {
        val to = VNSuggest.matches("to").map { it.word }
        assertTrue(to.contains("tôi")); assertTrue(to.contains("toàn"))
        val toCirc = VNSuggest.matches("tô").map { it.word }
        assertTrue(toCirc.contains("tôi"))
        assertTrue(toCirc.contains("tối") || toCirc.contains("tồi") || toCirc.contains("tội"))
        assertFalse(toCirc.contains("toàn")); assertFalse(toCirc.contains("tơi"))
        val toGrave = VNSuggest.matches("tò").map { it.word }
        assertTrue(toGrave.contains("tòa"))
        assertFalse(toGrave.contains("tôi")); assertFalse(toGrave.contains("tới"))
        assertTrue(VNSuggest.matches("t").map { it.word }.contains("tôi"))
        assertEquals("người", VNSuggest.matches("nguoi").first().word)
        assertTrue(VNSuggest.contains("người"))
        assertFalse(VNSuggest.contains("nguo"))
    }
}

class UserLangModelTests {
    @Before fun setUp() = TestAssets.install()

    private fun freshModel() = UserLangModel().apply { isKnownWord = { true } }

    @Test fun testLearnAndSuggest() {
        val m = freshModel()
        repeat(3) { m.record("anh", null) }
        repeat(3) { m.record("ơi", "anh") }
        m.record("đang", "anh")
        m.record("chào", null)
        assertEquals(listOf("anh"), m.topWords(1))
        val next = m.nextWords("anh", limit = 4)
        assertEquals("ơi", next.first())
        assertTrue(next.contains("đang"))
        assertEquals(listOf("ơn"), freshModel().nextWords("cảm", limit = 1))
        val m2 = freshModel()
        m2.record("xong", "cảm")
        assertEquals(listOf("ơn"), m2.nextWords("cảm", limit = 1))
        m.record("abc123", "anh")
        assertEquals(0, m.count("abc123"))
        assertFalse(UserLangModel.learnable("heeeyyy"))
    }

    @Test fun testTrigramContext() {
        val m = freshModel()
        repeat(2) { m.record("ơn", "cảm") }
        repeat(3) { m.record("nhiều", "ơn", "cảm") }
        m.record("anh", "ơn", "cảm")
        assertEquals(listOf("nhiều"), m.nextWords("ơn", "cảm", 1))
    }

    @Test fun testUnknownWordThreshold() {
        val m = UserLangModel()
        m.record("blib", null); m.record("blib", null)
        assertTrue(m.topWords(3).isEmpty())
        m.record("blib", null)
        assertEquals(listOf("blib"), m.topWords(3))
    }

    @Test fun testTriggerExpansion() {
        assertEquals(EmojiSuggest.emojis("đúng"), EmojiSuggest.emojis("done"))
        assertEquals(EmojiSuggest.emojis("đúng"), EmojiSuggest.emojis("xong"))
        assertEquals(EmojiSuggest.emojis("đúng"), EmojiSuggest.emojis("hoàn thành"))
        assertEquals(EmojiSuggest.emojis("sai"), EmojiSuggest.emojis("failed"))
        assertFalse(EmojiSuggest.emojis("trời ơi").isEmpty())
        assertFalse(EmojiSuggest.emojis("hoan thanh").isEmpty())
    }

    @Test fun testEmojiFoldedKeys() {
        assertEquals(EmojiSuggest.emojis("yêu"), EmojiSuggest.emojis("yeu"))
        assertFalse(EmojiSuggest.emojis("meo").isEmpty())
    }
}

class SeedDataTests {
    @Before fun setUp() = TestAssets.install()

    @Test fun testSeedInjection() {
        val m = UserLangModel()
        m.seedIfEmpty(SeedData.unigrams, SeedData.bigrams)
        assertFalse(m.topWords(3).isEmpty())
        assertTrue(m.nextWords("cảm", limit = 2).contains("ơn"))
        assertTrue(m.nextWords("hôm", limit = 2).contains("nay"))
        val before = m.count("không")
        m.seedIfEmpty(mapOf("xxx" to 99), emptyList())
        assertEquals(before, m.count("không"))
        assertEquals(0, m.count("xxx"))
        assertTrue((SeedData.unigrams.values.maxOrNull() ?: 0) <= 50)
    }

    @Test fun testSeedSizesMatchIOS() {
        val s = SeedData.load()
        assertEquals(1222, s.unigrams.size)
        assertEquals(1281, s.bigrams.size)
    }
}

class DisplayCaseTests {
    @Test fun testProperNounDisplay() {
        assertEquals("SenPrints", DisplayCase.apply("senprints"))
        assertEquals("Printik", DisplayCase.apply("printik"))
        assertEquals("iPhone", DisplayCase.apply("iphone"))
        assertEquals("macOS", DisplayCase.apply("macos"))
        assertEquals("Nguyễn", DisplayCase.apply("nguyễn"))
        assertEquals("GitHub", DisplayCase.apply("github"))
        assertEquals("ChatGPT", DisplayCase.apply("chatgpt"))
        assertEquals("Claude", DisplayCase.apply("claude"))
        assertEquals("cảm", DisplayCase.apply("cảm"))
        assertEquals("trang", DisplayCase.apply("trang"))
        assertEquals("Nội", DisplayCase.apply("nội", "hà"))
        assertEquals("Nội", DisplayCase.apply("nội", "Hà"))
        assertEquals("nội", DisplayCase.apply("nội"))
        assertEquals("Trang", DisplayCase.apply("trang", "nha"))
        assertEquals("Văn", DisplayCase.apply("văn", "nguyễn"))
        assertEquals("văn", DisplayCase.apply("văn"))
        assertEquals("vũ", DisplayCase.apply("vũ"))
    }

    @Test fun testLearningStaysCaseFolded() {
        val m = UserLangModel()
        m.record("SenPrints", null)
        assertEquals(1, m.count("senprints"))
        assertEquals(1, m.count("SenPrints"))
    }
}

class SensitiveWordsTests {
    @Test fun testFilterGate() {
        val words = listOf("vcl", "vui", "đcm", "cảm")
        assertEquals(listOf("vui", "cảm"), SensitiveWords.filter(words, true))
        assertEquals(words, SensitiveWords.filter(words, false))
        assertEquals(listOf("cướp", "giết"), SensitiveWords.filter(listOf("cướp", "giết"), true))
    }
}

/** Song sinh iOS SensitiveWordsTests.testSeedVulgarPairsGated: cặp tục bổ sung 18/10/2026 nằm trong seed
 *  nhưng bật lọc thì từ kế tiếp không bao giờ là từ nhạy cảm. */
class SeedVulgarPairsTests {
    @Before fun setUp() = TestAssets.install()

    @Test fun testSeedVulgarPairsGated() {
        for (w in SensitiveWords.set) assertTrue("từ lọc thiếu trong seed: $w", (SeedData.unigrams[w] ?: 0) > 0)
        assertFalse("từ nhẹ không lọc", "damn" in SensitiveWords.set)
        val m = UserLangModel()
        m.isKnownWord = { VNSuggest.contains(it) }
        m.seedIfEmpty(SeedData.unigrams, SeedData.bigrams)
        for (prev in SeedData.bigrams.map { it.first }.toSet()) {
            val on = SensitiveWords.filter(m.nextWords(prev, limit = 24), true)
            assertTrue("$prev: $on", on.none { it.lowercase() in SensitiveWords.set })
        }
        assertEquals("bú cu: SensitivePairs; bú đá hiện", listOf("đá"), SensitiveWords.filter(m.nextWords("bú", limit = 6), true, after = "bú"))
        assertEquals("lồn", m.nextWords("bú", limit = 6).first())
        assertEquals(listOf("tiên", "tư"), SensitiveWords.filter(m.nextWords("đầu", limit = 3), true))
        assertEquals(listOf("vào"), m.nextWords("sờ", limit = 1))
        assertEquals(listOf("cao"), m.nextWords("nâng", limit = 1))
        assertEquals(listOf("you"), m.nextWords("thank", limit = 1))
        assertEquals(emptyList<String>(), SensitiveWords.filter(m.nextWords("holy", limit = 3), true))
    }
}

/**
 * Song sinh iOS SensitiveWordsTests.testSensitivePairsGated: cặp chửi/tục ghép từ hai TỪ THƯỜNG
 * (SensitiveWords.pairs, sinh từ seed-curated [sensitive-bigram]). Bật lọc ⇒ với MỌI cặp: từ kế tiếp
 * sau prev không có word, vuốt sau prev không được thưởng ngữ cảnh seed, Thêm dấu không thưởng; tắt ⇒ có.
 */
class SensitivePairsTests {
    @Before fun setUp() = TestAssets.install()

    private fun model() = UserLangModel().also {
        it.isKnownWord = { w -> VNSuggest.contains(w) }
        it.seedIfEmpty(SeedData.unigrams, SeedData.bigrams)
    }

    @Test fun testSensitivePairsGated() {
        val m = model()
        val pairs = SensitiveWords.pairs.flatMap { (p, ws) -> ws.map { p to it } }
        assertTrue(pairs.size >= 30)
        val seedPairs = SeedData.bigrams.map { "${it.first} ${it.second}" }.toSet()
        for ((prev, w) in pairs) {
            assertTrue("cặp nhạy cảm phải có trong seed: $prev $w", "$prev $w" in seedPairs)
            assertFalse(w in SensitiveWords.set)
            val raw = m.nextWords(prev, limit = 24)
            assertTrue("tắt lọc: $prev → $w phải hiện ($raw)", w in raw)
            val on = SensitiveWords.filter(raw, true, after = prev)
            assertFalse("$prev: $on", w in on)
            val next = SuggestRank.nextFill(prev, null, on, { 0 }, { SensitiveWords.filter(it, true, after = prev) }, need = 5)
            assertFalse("nextFill $prev: $next", w in next)
            // vuốt: lọc bật ⇒ không +NEXT_BONUS từ cặp seed; tắt ⇒ có (lm = null: chỉ phần cá nhân)
            val ctxOn = SwipeSuggest.context(m, prev, null, lm = null, filterSensitive = true)
            val ctxOff = SwipeSuggest.context(m, prev, null, lm = null, filterSensitive = false)
            assertEquals("vuốt $prev → $w", SwipeSuggest.NEXT_BONUS, ctxOff.word(w) - ctxOn.word(w), 1e-4f)
        }
        assertEquals(listOf("vào"), SensitiveWords.filter(m.nextWords("sờ", limit = 6), true, after = "sờ"))
        assertEquals(listOf("vào", "chim", "cu"), m.nextWords("sờ", limit = 3))
        assertFalse("bi" in SensitiveWords.filter(m.nextWords("nâng", limit = 6), true, after = "nâng"))
        assertFalse("chó" in SensitiveWords.filter(m.nextWords("thằng", limit = 24), true, after = "Thằng"))
        assertEquals(listOf("má"), m.nextWords("đậu", limit = 1))
        assertEquals(listOf("nhau"), m.nextWords("phang", limit = 1))
        for ((a, b) in listOf("lăng" to "nhăng", "lăng" to "loàn", "hãm" to "hiếp", "lừa" to "đảo", "khốn" to "nạn")) {
            assertFalse(SensitiveWords.isPair(a, b))
            assertTrue("$a $b", b in SensitiveWords.filter(m.nextWords(a, limit = 24), true, after = a))
        }
        assertEquals(listOf("lồn"), SensitiveWords.dropPairs(listOf("chó", "lồn"), "thằng", true))
        assertEquals(listOf("chó", "lồn"), SensitiveWords.dropPairs(listOf("chó", "lồn"), "thằng", false))
        // Thêm dấu: closure như KeyboardSession.tonesPersonal
        val pair = { fs: Boolean -> { a: String, b: String -> if (fs && SensitiveWords.isPair(a, b)) 0 else m.bigramCount(a, b) } }
        assertTrue(pair(false)("sờ", "chim") > 0)
        assertEquals(0, pair(true)("sờ", "chim"))
    }

    /** Song sinh iOS testSlangAndInsultBatches. */
    @Test fun testSlangAndInsultBatches() {
        val m = model()
        fun on(p: String) = SensitiveWords.filter(m.nextWords(p, limit = 24), true, after = p)
        assertEquals("lách", on("lạng").first())
        assertEquals(listOf("baby", "daddy"), on("sugar"))
        assertEquals("tay", on("karaoke").first())
        assertEquals("biết", on("đếch").first())
        assertTrue("tiền" in on("rửa"))
        assertTrue("coin" in on("chơi"))
        for (w in listOf("nồi", "đạn", "linh")) assertTrue(w, w in on("vãi"))
        for ((p, w) in listOf("kích" to "dục", "massage" to "kích", "chơi" to "cave", "chơi" to "gái", "óc" to "lợn",
                              "não" to "phẳng", "láo" to "chó", "con" to "chó", "quay" to "tay", "phim" to "heo",
                              "clip" to "nóng", "lộ" to "clip", "như" to "lợn", "như" to "heo", "như" to "bò", "như" to "cột",
                              "cái" to "mâm", "lùn" to "tịt", "cò" to "hương", "chúa" to "phòng")) {
            assertTrue(SensitiveWords.isPair(p, w))
            assertFalse("$p $w", w in on(p))
            assertTrue("tắt lọc: $p $w", w in m.nextWords(p, limit = 24))
        }
        for (w in listOf("cc", "đb", "lìn")) { assertTrue(w in SensitiveWords.set); assertTrue((SeedData.unigrams[w] ?: 0) > 0) }
        for ((p, w) in listOf("cà" to "khịa", "trẻ" to "trâu", "sống" to "ảo", "gen" to "z", "báo" to "thủ", "ô" to "dề", "xu" to "cà",
                              "phong" to "bì", "check" to "var", "anti" to "fan", "xin" to "link", "phòng" to "trọ", "que" to "tăm",
                              "mong" to "manh", "gầy" to "như")) assertTrue("$p $w", w in on(p))
        for ((p, w) in listOf("nước" to "ngoài", "bóng" to "đá", "cá" to "nhân", "hàng" to "hóa", "lên" to "tới", "xin" to "lỗi",
                              "khẩu" to "trang", "đào" to "tạo")) assertEquals(p, w, on(p).first())
        assertEquals("Z", DisplayCase.resolve("z", "gen"))
        assertEquals("Canada", DisplayCase.resolve("canada", "ma"))
        assertEquals("gen di truyền", "gen", DisplayCase.resolve("gen"))
        assertEquals("gô", on("gét").first())
        assertTrue("teencode trong vnlexicon", VNSuggest.contains("khum") && VNSuggest.contains("dề") && VNSuggest.contains("na"))
        assertFalse(SensitiveWords.isPair("phòng", "trọ") || SensitiveWords.isPair("cột", "nhà") || SensitiveWords.isPair("nhà", "cháy"))
        assertFalse("LOL tiếng Anh", "lol" in SensitiveWords.set)
        assertFalse("đếch" in SensitiveWords.set || "méo" in SensitiveWords.set)
    }

    /** Song sinh iOS testFiatAndCurrencyCase. */
    @Test fun testFiatAndCurrencyCase() {
        val m = model()
        assertEquals(listOf("định"), m.nextWords("pháp", "tiền", 1))
        assertTrue("pháp" in m.nextWords("tiền", limit = 3))
        for ((w, d) in listOf("usd" to "USD", "usdt" to "USDT", "vnđ" to "VNĐ", "vnd" to "VND", "bitcoin" to "Bitcoin",
                              "crypto" to "crypto", "fiat" to "fiat")) {
            assertEquals(d, DisplayCase.resolve(w))
            assertTrue(w, (SeedData.unigrams[w] ?: 0) > 0)
        }
        for ((w, d) in listOf("gemini" to "Gemini", "grok" to "Grok", "ubuntu" to "Ubuntu", "copilot" to "Copilot",
                              "docker" to "Docker", "kubernetes" to "Kubernetes", "javascript" to "JavaScript",
                              "typescript" to "TypeScript", "llm" to "LLM", "nft" to "NFT", "etf" to "ETF", "ipo" to "IPO",
                              "defi" to "DeFi", "chatgpt" to "ChatGPT", "github" to "GitHub", "cursor" to "cursor",
                              "stablecoin" to "stablecoin", "cloudflare" to "Cloudflare", "golang" to "Golang", "vpn" to "VPN",
                              "kotlin" to "Kotlin", "panadol" to "Panadol", "viettelex" to "VietTelex", "telex" to "telex",
                              "keto" to "keto", "rust" to "rust")) {
            assertEquals(d, DisplayCase.resolve(w))
        }
        fun on(p: String) = SensitiveWords.filter(m.nextWords(p, limit = 24), true, after = p)
        for ((p, w) in listOf("bắt" to "đáy", "đu" to "đỉnh", "khối" to "ngoại", "ví" to "lạnh", "call" to "margin", "vàng" to "nhẫn"))
            assertTrue("$p $w", w in on(p))
        assertEquals("NEO: ví dụ vẫn đứng đầu", "dụ", on("ví").first())
        for ((p, w) in listOf("chụp" to "x", "x" to "quang", "tập" to "gym", "hậu" to "covid", "test" to "nhanh", "sinh" to "thường"))
            assertTrue("$p $w", w in on(p))
        assertEquals("X", DisplayCase.resolve("x", "chụp"))
        assertEquals("Covid", DisplayCase.resolve("covid"))
        assertEquals("trinh", on("phil").first())
        assertEquals("Phil", DisplayCase.resolve("phil"))
        assertEquals("Trinh", DisplayCase.resolve("trinh", "Phil"))
        assertEquals("trinh", DisplayCase.resolve("trinh"))
        for (w in listOf("kubernetes", "stablecoin", "viettelex", "paracetamol", "cloudflare"))
            assertTrue("enlexicon: $w", SwipeEnglish.indexOf(w) >= 0)
    }
}

class SeedOrderTests {
    @Before fun setUp() = TestAssets.install()

    @Test fun testEmptyFieldTop3() {
        val m = UserLangModel()
        m.isKnownWord = { true }
        m.seedIfEmpty(SeedData.unigrams, SeedData.bigrams)
        assertEquals(listOf("em", "anh", "tôi"), m.topWords(3))
    }
}
