import XCTest

/// Góp ý người dùng 19/10/2026 ("phần gợi ý cũng nên bổ sung thêm gợi ý tiếng anh vì nhiều khi có
/// từ tiếng anh dài mà không nhớ cách viết"): gợi ý tiếng Anh ở chế độ tiếng Việt — EnglishAssist.
/// Song sinh Android: android/keyboard/src/test/kotlin/com/viettelex/keyboard/EnglishAssistTests.kt.
final class EnglishAssistTests: XCTestCase {
    private let bridge = EngineBridge(settings: KeyboardSettings())

    /// Lượt nền như KeyboardViewController.updateSuggestions (chế độ tiếng Việt).
    private func assist(_ raw: String) -> EnglishAssist.Result? {
        let composed = bridge.composeTrial(raw)
        let pool = VNSuggest.matches(composed, poolLimit: 24, excluding: composed.lowercased())
        return EnglishAssist.suggest(raw: raw, composed: composed, pool: pool,
                                     typedFreq: VNSuggest.frequency(of: composed))
    }

    // MARK: hoàn thành

    func testLongEnglishPrefixesComplete() {
        for (raw, want) in [("accom", "accommodation"), ("enviro", "environment"), ("responsib", "responsibility"),
                            ("necess", "necessary"), ("definit", "definitely"), ("keyb", "keyboard")] {
            let r = assist(raw)
            XCTAssertEqual(r?.kind, .completion, raw)
            XCTAssertTrue(r?.words.contains(want) == true, "\(raw) → \(r?.words ?? [])")
        }
    }

    func testCompletionsRankedByFrequency() {
        let w = assist("accom")?.words ?? []
        XCTAssertFalse(w.isEmpty)
        let f = w.map { Int(SwipeEnglish.lexicon.freq[SwipeEnglish.index(of: $0)!]) }
        XCTAssertEqual(f, f.sorted(by: >), "\(w)")
        XCTAssertLessThanOrEqual(w.count, EnglishAssist.completionLimit)
    }

    func testMergeEnglishLeadsThenVietnamese() {
        let r = EnglishAssist.Result(words: ["person", "personal", "personality"], kind: .completion)
        XCTAssertEqual(EnglishAssist.merge(vietnamese: ["pé", "Person"], english: r),
                       ["person", "personal", "pé", "personality"])
        let fix = EnglishAssist.Result(words: ["environment"], kind: .correction)
        XCTAssertEqual(EnglishAssist.merge(vietnamese: ["nhiều"], english: fix), ["nhiều", "environment"])
        XCTAssertEqual(EnglishAssist.merge(vietnamese: ["a"], english: nil), ["a"])
    }

    // MARK: tiếng Việt không đổi

    func testClearlyVietnameseNeverTouchesEnglish() {
        var touched = 0
        for raw in ["nguowif", "ddeens", "thaatj", "chuyeenj", "khoong", "dduowcj", "thin", "chan", "tooi"] {
            let composed = bridge.composeTrial(raw)
            let pool = VNSuggest.matches(composed, poolLimit: 24, excluding: composed.lowercased())
            XCTAssertNil(EnglishAssist.suggest(raw: raw, composed: composed, pool: pool,
                                               typedFreq: VNSuggest.frequency(of: composed),
                                               lex: { touched += 1; return SwipeEnglish.lexicon }()), raw)
        }
        // phím thô ngắn / ngoài a–z: cổng rẻ chặn trước mọi tra cứu
        XCTAssertFalse(EnglishAssist.eligible(raw: "cas"))
        XCTAssertFalse(EnglishAssist.eligible(raw: "abc1"))
        XCTAssertFalse(EnglishAssist.eligible(raw: String(repeating: "a", count: 25)))
        XCTAssertTrue(EnglishAssist.eligible(raw: "Accom"))
        XCTAssertLessThanOrEqual(touched, 2, "từ rõ là tiếng Việt không được đọc lexicon tiếng Anh")
    }

    /// Mọi tiền tố ≥ 4 phím của âm tiết Việt trong câu heldout (Telex + không dấu): có âm tiết Việt
    /// khớp ⇒ không bao giờ trộn tiếng Anh (cùng ngưỡng macOS testEnglishOverrideNeverFiresOnVietnamese).
    func testNeverFiresOnVietnameseHeldout() throws {
        let url = Bundle(for: Self.self).url(forResource: "bigram-heldout", withExtension: "txt")
            ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/bigram-heldout.txt")
        let text = try String(contentsOf: url, encoding: .utf8)
        let syllables = Set(text.split(separator: "\n").filter { !$0.hasPrefix("#") }
            .flatMap { $0.split(separator: " ").map(String.init) })
        var checked = 0, fired: [String] = []
        for s in syllables {
            var raws = Self.telexVariants(s).filter { $0.count >= 4 }.flatMap { v in (4...v.count).map { String(v.prefix($0)) } }
            let t = Self.toneless(s)
            if t.count >= 4 { raws.append(t) }
            for raw in raws {
                let composed = bridge.composeTrial(raw)
                let pool = VNSuggest.matches(composed, poolLimit: 24, excluding: composed.lowercased())
                guard !pool.isEmpty else { continue }
                checked += 1
                if let r = EnglishAssist.suggest(raw: raw, composed: composed, pool: pool,
                                                 typedFreq: VNSuggest.frequency(of: composed)) {
                    fired.append("\(raw)/\(composed)→\(r.words.first ?? "")")
                }
            }
        }
        print("ENGLISH-ASSIST vietnamese prefixes checked \(checked), fired \(fired.count): \(fired.prefix(10))")
        XCTAssertGreaterThan(checked, 1000)
        XCTAssertEqual(fired, [])
    }

    // MARK: sửa chính tả

    func testMisspellingsCorrected() {
        for (raw, want) in [("acommodation", "accommodation"), ("enviroment", "environment"),
                            ("responsability", "responsibility"), ("neccessary", "necessary"),
                            ("definately", "definitely"), ("recieve", "receive"), ("spellnig", "spelling")] {
            let r = assist(raw)
            XCTAssertEqual(r?.kind, .correction, raw)
            XCTAssertEqual(r?.words.first, want, "\(raw) → \(r?.words ?? [])")
            XCTAssertLessThanOrEqual(r?.words.count ?? 0, EnglishAssist.typoLimit)
        }
    }

    func testMisspelledPrefixWhileTyping() {
        // đang gõ dở, đã sai chính tả: "acomm" cách tiền tố "accomm" một chữ
        XCTAssertTrue(assist("acomm")?.words.contains(where: { $0.hasPrefix("accomm") }) == true, "\(assist("acomm")?.words ?? [])")
        XCTAssertTrue(assist("enviro") != nil)
    }

    func testNoCorrectionForRealWordsShortOrFar() {
        XCTAssertNil(assist("hello"))          // từ tiếng Anh đúng, không từ dài hơn ⇒ không sửa
        XCTAssertNil(assist("zzqxv"))          // không gần từ nào
        XCTAssertTrue(EnglishAssist.corrections("abcd", in: SwipeEnglish.lexicon).isEmpty)
    }

    // MARK: lọc + hoa/thường

    func testSensitiveFilter() {
        let r = EnglishAssist.Result(words: ["fucking", "fun"], kind: .completion)
        XCTAssertEqual(EnglishAssist.filtered(r, after: nil, prev2: nil, enabled: true)?.words, ["fun"])
        XCTAssertEqual(EnglishAssist.filtered(r, after: nil, prev2: nil, enabled: false)?.words, ["fucking", "fun"])
        XCTAssertNil(EnglishAssist.filtered(.init(words: ["shit"], kind: .correction), after: nil, prev2: nil, enabled: true))
        // enlexicon không có từ tục: không hoàn thành / sửa ra từ tục nào
        for raw in ["fuck", "shitt", "bitc", "motherfuc"] {
            for w in assist(raw)?.words ?? [] { XCTAssertFalse(SensitiveWords.set.contains(w), "\(raw) → \(w)") }
        }
    }

    func testCasing() {
        // = KeyboardViewController.caseForContext(displayCase(w), typed: composed)
        func shown(_ raw: String) -> [String] {
            let composed = bridge.composeTrial(raw)
            return (assist(raw)?.words ?? []).map {
                SuggestionCase.display(DisplayCase.resolve($0, after: nil), shift: .off, typed: composed)
            }
        }
        XCTAssertTrue(shown("Accom").contains("Accommodation"), "\(shown("Accom"))")
        XCTAssertTrue(shown("ACCOM").contains("ACCOMMODATION"), "\(shown("ACCOM"))")
        XCTAssertTrue(shown("singa").contains("Singapore"), "\(shown("singa"))")   // DisplayCase
        XCTAssertTrue(shown("Enviroment").contains("Environment"), "\(shown("Enviroment"))")
    }

    // MARK: độ trễ

    /// Chi phí thêm của mỗi phím trên hàng đợi nền: tiếng Việt (gõ thường) gần 0; từ tiếng Anh dài /
    /// sai chính tả (quét sửa) vẫn ≪ ngân sách. In "ENGLISH-ASSIST LAT" (trước = VNSuggest + PMI như cũ).
    func testLatencyBounds() {
        _ = SwipeEnglish.lexicon
        let vn = KeyboardBenchTests.corpus.split(separator: " ").map(String.init).filter { !$0.isEmpty }
        let en = ["accommodation", "environment", "responsibility", "acommodation", "enviroment",
                  "responsability", "neccessary", "definately", "information", "experience"]
        func prefixes(_ ws: [String]) -> [(raw: String, composed: String, pool: [VNSuggest.Match])] {
            ws.flatMap { w in (1...w.count).map { String(w.prefix($0)) } }.map { raw in
                let c = bridge.composeTrial(raw)
                return (raw, c, VNSuggest.matches(c, poolLimit: 24, excluding: c.lowercased()))
            }
        }
        let vk = prefixes(vn), ek = prefixes(en)
        func time(_ keys: [(raw: String, composed: String, pool: [VNSuggest.Match])], assist: Bool) -> Double {
            var best = Double.greatestFiniteMagnitude
            for _ in 0..<5 {
                let t = CFAbsoluteTimeGetCurrent()
                for k in keys {
                    let pool = VNSuggest.matches(k.composed, poolLimit: 24, excluding: k.composed.lowercased())
                    _ = SuggestRank.inlinePmi(pool, prev: nil, prev2: nil)
                    if assist, EnglishAssist.eligible(raw: k.raw) {
                        _ = EnglishAssist.suggest(raw: k.raw, composed: k.composed, pool: pool,
                                                  typedFreq: VNSuggest.frequency(of: k.composed))
                    }
                }
                best = min(best, (CFAbsoluteTimeGetCurrent() - t) * 1e6 / Double(keys.count))
            }
            return best
        }
        let vb = time(vk, assist: false), va = time(vk, assist: true)
        let eb = time(ek, assist: false), ea = time(ek, assist: true)
        var worst = 0.0
        for k in ek where EnglishAssist.eligible(raw: k.raw) {
            let t = CFAbsoluteTimeGetCurrent()
            _ = EnglishAssist.suggest(raw: k.raw, composed: k.composed, pool: k.pool, typedFreq: VNSuggest.frequency(of: k.composed))
            worst = max(worst, (CFAbsoluteTimeGetCurrent() - t) * 1e3)
        }
        var reads = 0
        for k in vk {
            _ = EnglishAssist.suggest(raw: k.raw, composed: k.composed, pool: k.pool, typedFreq: VNSuggest.frequency(of: k.composed),
                                      lex: { reads += 1; return SwipeEnglish.lexicon }())
        }
        print("ENGLISH-ASSIST phím tiếng Việt chạm lexicon Anh: \(reads) / \(vk.count)")
        print(String(format: "ENGLISH-ASSIST LAT iOS (µs/phím, nền): tiếng Việt %.1f → %.1f (×%.2f) | tiếng Anh %.1f → %.1f | chậm nhất một phím %.2f ms",
                     vb, va, va / vb, eb, ea, worst))
        XCTAssertLessThan(va, vb * 1.3 + 5, "tiếng Việt: gần như không thêm chi phí")
        // Debug sim (19/10/2026): VN 157 → 151 µs, EN 23 → 998 µs, chậm nhất 7,2 ms. Bản -O (bàn phím thật,
        // swiftc -O cùng mã, 95 phím EN): trung bình 19,6 µs, chậm nhất 1,5 ms — trên hàng đợi nền, không chặn phím.
        XCTAssertLessThan(worst, 20, "một phím tiếng Anh (cả quét sửa chính tả), Debug")
        XCTAssertLessThan(ea, 3000, "trung bình mỗi phím tiếng Anh, Debug")
    }

    // MARK: phím Telex của âm tiết (= AppTests TelexKeys)

    static func telexVariants(_ syllable: String) -> [String] {
        var chars: [String] = []
        var tone: Character?
        for u in syllable.lowercased().decomposedStringWithCanonicalMapping.unicodeScalars {
            switch u.value {
            case 0x302: guard let k = chars.last else { return [] }; chars[chars.count - 1] = k + k
            case 0x306, 0x31B: guard let k = chars.last else { return [] }; chars[chars.count - 1] = k + "w"
            case 0x301: tone = "s"
            case 0x300: tone = "f"
            case 0x309: tone = "r"
            case 0x303: tone = "x"
            case 0x323: tone = "j"
            case 0x111: chars.append("dd")
            case 0x61...0x7A: chars.append(String(Character(u)))
            default: return []
            }
        }
        guard !chars.isEmpty else { return [] }
        let all = chars.joined()
        guard let t = tone else { return [all] }
        var lastVowel = -1
        for (i, c) in chars.enumerated() where "aeiouy".contains(c.first!) { lastVowel = i }
        guard lastVowel >= 0, lastVowel < chars.count - 1 else { return [all + String(t)] }
        return [all + String(t), chars[0...lastVowel].joined() + String(t) + chars[(lastVowel + 1)...].joined()]
    }

    static func toneless(_ s: String) -> String {
        String(String.UnicodeScalarView(s.lowercased().replacingOccurrences(of: "đ", with: "d")
            .decomposedStringWithCanonicalMapping.unicodeScalars.filter { $0.value < 0x300 || $0.value > 0x36F }))
    }
}
