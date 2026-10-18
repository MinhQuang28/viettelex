// WordPredictionTests — gợi ý từ cạnh con trỏ (WordPrediction.swift): công tắc theo cài đặt
// hệ thống "Show inline predictive text", dựng ngữ cảnh từ văn bản trước con trỏ, xếp hạng
// (dùng chung thanh gợi ý iOS), từ kế tiếp chỉ theo trigram 2 từ, thay đúng từ đang soạn, MỘT
// ứng viên trên CaretHint, đo đạc; seed từ kế tiếp (một từ ngữ cảnh), tiếng Anh giữa câu Việt,
// công tắc "Lọc từ nhạy cảm".
import XCTest
import TelexCore
@testable import VietTelex

final class WordPredictionTests: XCTestCase {
    private typealias L = PredictionLogic

    override func tearDown() {
        CaretHint.shared.setPendingForTesting(nil)
        SystemInlinePrediction.setForTesting(nil)
    }

    // MARK: Đuôi email (EmailDomainPrediction — song sinh iOS EmailDomains, Windows, Linux)

    private func email(_ before: String, typed: String = "", raw: String? = nil) -> CaretSuggestion? {
        EmailDomainPrediction.suggestion(before: before, typed: typed, raw: raw ?? typed)
    }

    func testEmailDomainRightAfterAtSuggestsGmail() {
        XCTAssertEqual(email("user@"), CaretSuggestion(kind: .email, display: "gmail.com", replace: "", insert: "gmail.com"))
        XCTAssertEqual(email("hello user@")?.display, "gmail.com")
        XCTAssertEqual(email("<user@")?.display, "gmail.com")
        XCTAssertEqual(email("a.b_c%d+e-f@")?.display, "gmail.com")
    }

    func testEmailDomainPrefixInsertsOnlyMissingPart() {
        XCTAssertEqual(email("user@ic", typed: "ic"),
                       CaretSuggestion(kind: .email, display: "icloud.com", replace: "", insert: "loud.com"))
        XCTAssertEqual(email("user@gm", typed: "gm")?.insert, "ail.com")
        XCTAssertEqual(email("user@GM", typed: "GM")?.insert, "ail.com", "không phân biệt hoa thường")
        XCTAssertEqual(email("user@gmail.")?.insert, "com", "sau dấu chấm (ranh giới) — chỉ phần còn thiếu")
        XCTAssertEqual(email("user@gmail.c", typed: "c")?.insert, "om")
        XCTAssertEqual(email("user@o", typed: "o")?.display, "outlook.com")
        XCTAssertEqual(email("user@y", typed: "y")?.display, "yahoo.com")
        XCTAssertEqual(email("user@g", typed: "g")?.display, "gmail.com", "Gmail trước")
    }

    func testEmailDomainNoSuggestion() {
        XCTAssertNil(email("user@gmail.com"), "đã gõ đủ")
        XCTAssertNil(email("user@x", typed: "x"), "không khớp nhà cung cấp nào")
        XCTAssertNil(email("@"), "phần trước @ rỗng")
        XCTAssertNil(email("hi @"))
        XCTAssertNil(email("phúc@"), "phần trước @ không phải [A-Za-z0-9._%+-]")
        XCTAssertNil(email("x@y@"))
        XCTAssertNil(email("user@gmail.com.vn"))
        XCTAssertNil(email("hello"))
        XCTAssertNil(email("user@gm", typed: "xx"), "từ đang soạn không khớp màn hình")
    }

    /// Telex đổi "yahoo" thành "yahô" khi gõ thô: khớp theo phím thô, thay đúng từ đang soạn.
    func testEmailDomainMatchesRawKeysWhenTelexMangled() {
        var e = TelexEngine()
        for c in "yahoo" { _ = e.feed(c) }
        XCTAssertNotEqual(e.composed, "yahoo", "tiền đề: Telex đổi oo")
        let s = email("user@" + e.composed, typed: e.composed, raw: e.rawKeystrokes)
        XCTAssertEqual(s, CaretSuggestion(kind: .email, display: "yahoo.com", replace: e.composed, insert: "yahoo.com"))
        XCTAssertEqual(email("user@outlô", typed: "outlô", raw: "outloo"),
                       CaretSuggestion(kind: .email, display: "outlook.com", replace: "outlô", insert: "outlook.com"))
        XCTAssertNil(email("user@yahô", typed: "yahô", raw: "yahooo"), "phím thô quá tên miền")
    }

    func testEmailDomainIsPredictionFamilyAndTabAccepts() {
        XCTAssertTrue(CaretSuggestion.Kind.email.isPrediction)
        XCTAssertEqual(CaretHintLogic.surface(kind: .email, imkPath: true, hasCandidateWindow: true, source: .imkFirstRect), .panel)
        XCTAssertEqual(CaretHintLogic.action(kind: .email, keyCode: CaretHintLogic.kTab, plain: true), .accept)
        XCTAssertEqual(CaretHintLogic.action(kind: .email, keyCode: CaretHintLogic.kReturn, plain: true), .dismissPass,
                       "Enter không cướp (gửi / xuống dòng)")
    }

    func testEmailTokenDetection() {
        XCTAssertTrue(EmailDomainPrediction.looksLikeEmailToken("mail user@g"))
        XCTAssertFalse(EmailDomainPrediction.looksLikeEmailToken("user@ gm"))
        XCTAssertFalse(EmailDomainPrediction.looksLikeEmailToken("được"))
    }

    // MARK: Công tắc

    func testSettingValueResolution() {
        XCTAssertTrue(SystemInlinePrediction.resolve(nil), "không có khoá ⇒ mặc định macOS (bật)")
        XCTAssertTrue(SystemInlinePrediction.resolve(NSNumber(value: 1)))
        XCTAssertFalse(SystemInlinePrediction.resolve(NSNumber(value: 0)))
        XCTAssertFalse(SystemInlinePrediction.resolve(false))
        XCTAssertTrue(SystemInlinePrediction.resolve(true))
        XCTAssertFalse(SystemInlinePrediction.resolve("0"))
        XCTAssertEqual(SystemInlinePrediction.key, "NSAutomaticInlinePredictionEnabled")
    }

    func testSettingOffSchedulesNothingAndCostsNothing() {
        var evaluated = false
        func canReplace() -> Bool { evaluated = true; return true }
        XCTAssertFalse(L.shouldSchedule(settingOn: false, canReplace: canReplace()))
        XCTAssertFalse(evaluated, "tắt ⇒ không tính gì thêm (không hỏi app)")
        XCTAssertTrue(L.shouldSchedule(settingOn: true, canReplace: canReplace()))
        XCTAssertTrue(evaluated)
        XCTAssertFalse(L.shouldSchedule(settingOn: true, canReplace: false))
        XCTAssertFalse(L.shouldSchedule(settingOn: true, canReplace: true, secure: true))
        XCTAssertFalse(L.shouldSchedule(settingOn: true, canReplace: true, otherHint: true))
    }

    func testForcedSettingWins() {
        SystemInlinePrediction.setForTesting(false)
        XCTAssertFalse(SystemInlinePrediction.isOn)
        SystemInlinePrediction.setForTesting(true)
        XCTAssertTrue(SystemInlinePrediction.isOn)
    }

    // MARK: Ngữ cảnh từ màn hình

    func testRequestWhileComposing() {
        let r = L.request(before: "xin chào bạn đư", typed: "đư", english: false)
        XCTAssertEqual(r, .init(typed: "đư", prev: "bạn", prev2: "chào", english: false))
        XCTAssertEqual(L.request(before: "đư", typed: "đư", english: false),
                       .init(typed: "đư", prev: nil, prev2: nil, english: false))
        XCTAssertNil(L.request(before: "abc.đư", typed: "đư", english: false), "không đứng riêng")
        XCTAssertNil(L.request(before: "chào đ", typed: "đ", english: false), "1 chữ: không gợi ý")
        XCTAssertNil(L.request(before: "chào đư", typed: "đươ", english: false), "màn hình lệch")
        XCTAssertNil(L.request(before: "a1", typed: "a1", english: false))
    }

    func testRequestNextWord() {
        XCTAssertEqual(L.request(before: "cảm ", typed: "", english: false),
                       .init(typed: "", prev: "cảm", prev2: nil, english: false))
        XCTAssertEqual(L.request(before: "Tôi Đang ", typed: "", english: false)?.prev, "đang")
        XCTAssertEqual(L.request(before: "Tôi Đang ", typed: "", english: false)?.prev2, "tôi")
        XCTAssertEqual(L.request(before: "xin chào, bạn ", typed: "", english: false),
                       .init(typed: "", prev: "bạn", prev2: nil, english: false), "dấu phẩy cắt ngữ cảnh")
        XCTAssertEqual(L.request(before: "dòng một\nđi ", typed: "", english: false)?.prev2, nil)
        XCTAssertNil(L.request(before: "hai  ", typed: "", english: false), "hai dấu cách")
        XCTAssertNil(L.request(before: "xong. ", typed: "", english: false), "sau dấu câu")
        XCTAssertNil(L.request(before: " ", typed: "", english: false))
        XCTAssertNil(L.request(before: "", typed: "", english: false))
        XCTAssertNil(L.request(before: "a.b ", typed: "", english: false), "không phải từ đứng riêng")
    }

    // MARK: Xếp hạng (dữ liệu thật, dùng chung iOS)

    private func candidates(_ before: String, typed: String = "", raw: String = "", english: Bool = false) -> [String] {
        guard let r = L.request(before: before, typed: typed, english: english) else { return [] }
        return PredictionModel.shared.candidates(r, raw: raw)
    }

    func testNextWordRanking() {
        XCTAssertFalse(candidates("cảm ơn ").isEmpty, "hai từ ngữ cảnh có dòng trigram ⇒ có gợi ý")
        XCTAssertFalse(candidates("xin chào ").isEmpty, "\(candidates("xin chào "))")
        XCTAssertEqual(candidates("hôm nay ").count <= L.rankDepth, true)
        XCTAssertEqual(candidates("hello world ", english: true), [], "tiếng Anh: không có dữ liệu từ kế tiếp")
        XCTAssertEqual(candidates("zzqx zzqy "), [], "từ lạ: không gợi ý")
    }

    /// Hồi quy (Phil 16/10/2026): "Tên ␣" → "gọi" là đoán mò từ bigram một từ. Từ kế tiếp từ LM
    /// tĩnh chỉ khi có đủ HAI từ ngữ cảnh VÀ vnlm.bin có dòng trigram — không lùi bigram. Một từ
    /// ngữ cảnh chỉ ra gợi ý khi có cặp SEED curated của iOS ("cảm" → ơn, "xin" → lỗi).
    func testNextWordNeedsTwoWordTrigramOrSeed() {
        XCTAssertEqual(candidates("Tên "), [], "một từ ngữ cảnh, không seed: không gợi ý")
        XCTAssertEqual(candidates("xong, tên "), [], "dấu phẩy cắt ngữ cảnh ⇒ chỉ một từ")
        XCTAssertEqual(candidates("zzqx tên "), [], "bigram tĩnh có nhưng không trigram, không seed")
        XCTAssertFalse(L.wantsNextWord(.init(typed: "", prev: "tên", prev2: nil, english: false)))
        XCTAssertTrue(L.wantsNextWord(.init(typed: "", prev: "ơn", prev2: "cảm", english: false)))
        XCTAssertFalse(L.wantsNextWord(.init(typed: "", prev: "ơn", prev2: "cảm", english: true)))
        XCTAssertTrue(L.wantsSeedNext(.init(typed: "", prev: "cảm", prev2: nil, english: false)))
        XCTAssertFalse(L.wantsSeedNext(.init(typed: "c", prev: "cảm", prev2: nil, english: false)))
        // Hai từ có dòng trigram ⇒ hạng 1 = hàng đầu dòng trigram (iOS nextFill: trigram trước seed).
        let top = SuggestRank.topNext("ơn", prev2: "cảm", limit: 6)
        XCTAssertEqual(top?.trigram, true)
        XCTAssertEqual(candidates("cảm ơn ").first?.lowercased(),
                       SensitiveWords.filter(top?.words ?? [], enabled: true).first?.lowercased())
    }

    /// Seed iOS (SeedNextData = UserLangModel.nextWords của người dùng mới): một từ ngữ cảnh.
    func testSeedNextWord() {
        XCTAssertEqual(candidates("cảm ").first, "ơn")
        XCTAssertEqual(candidates("Cảm ").first, "ơn")
        XCTAssertEqual(candidates("xin ").first, "lỗi", "\(candidates("xin "))")
        XCTAssertTrue(candidates("xin ").contains("chào"))
        XCTAssertEqual(candidates("hôm ").first, "nay")
        XCTAssertEqual(candidates("zzqx cảm ").first, "ơn", "không trigram ⇒ seed")
        XCTAssertEqual(candidates("nguyễn ").first, "Văn", "chuỗi tên: DisplayCase")
        XCTAssertEqual(candidates("good ", english: true).first, "morning", "tiếng Anh: chỉ từ tiếng Anh")
        XCTAssertEqual(candidates("anh ", english: true), [], "ngữ cảnh Anh: từ kế tiếp Việt bị bỏ")
        // Bảng: dòng hợp lệ, NFC, chữ thường; cảm → ơn đứng đầu (seed iOS).
        let t = SeedNextData.table()
        XCTAssertGreaterThan(t.count, 200)
        XCTAssertEqual(t["cảm"]?.first, "ơn")
        for (k, v) in t {
            XCTAssertFalse(v.isEmpty, k)
            XCTAssertEqual(k, k.lowercased().precomposedStringWithCanonicalMapping)
            XCTAssertLessThanOrEqual(v.count, 24)
        }
    }

    /// Công tắc "Lọc từ nhạy cảm": bật (mặc định) ⇒ không bao giờ đưa từ tục; tắt ⇒ như iOS,
    /// seed/LM được hiện nguyên ("ngon ␣" → vl là cặp seed iOS).
    func testSensitiveFilterToggle() {
        func c(_ before: String, _ typed: String = "", _ raw: String = "", on: Bool) -> [String] {
            guard let r = L.request(before: before, typed: typed, english: false) else { return [] }
            return PredictionModel.shared.candidates(r, raw: raw, filterSensitive: on)
        }
        XCTAssertEqual(c("vãi ", on: true).first, "cả")
        XCTAssertEqual(c("vãi ", on: false).first, "lồn")
        XCTAssertEqual(c("ngon ", on: true), [], "seed chỉ có từ nhạy cảm ⇒ bật lọc: không gì")
        XCTAssertEqual(c("ngon ", on: false).first, "vl")
        XCTAssertFalse(c("đị", "đị", "ddij", on: true).contains("địt"))
        XCTAssertEqual(AppState.shared.defaults.object(forKey: "filterSensitive") as? Bool ?? true,
                       AppState.shared.filterSensitive)
        XCTAssertTrue(MacSyncStore.settings.contains { $0.canonical == "filterSensitive" }, "đồng bộ như iOS")
    }

    /// Cặp tục bổ sung 18/10/2026: bật lọc ⇒ sau bú/liếm/móc/đầu/thằng… không bao giờ ra từ nhạy cảm;
    /// tắt lọc ⇒ có. Tiếng Anh tục (enlexicon không có) chỉ gợi ý khi tắt lọc.
    func testVulgarPairsGatedByFilter() {
        func c(_ before: String, _ typed: String = "", english: Bool = false, on: Bool) -> [String] {
            guard let r = L.request(before: before, typed: typed, english: english) else { return [] }
            return PredictionModel.shared.candidates(r, raw: typed, filterSensitive: on)
        }
        for prev in ["bú", "liếm", "móc", "nứng", "đầu", "cái", "thằng", "con", "như", "vãi", "mặt", "sờ", "hãm"] {
            let got = c(prev + " ", on: true)
            XCTAssertTrue(got.allSatisfy { !SensitiveWords.set.contains($0.lowercased()) }, "\(prev): \(got)")
        }
        XCTAssertEqual(c("bú ", on: true), ["đá"], "bú đá (tiếng lóng ma tuý) hiện; bú cu/lồn… không")
        XCTAssertEqual(c("bú ", on: false).first, "lồn")
        XCTAssertFalse(c("motherf", "motherf", english: true, on: true).contains { $0.lowercased() == "motherfucker" })
        XCTAssertEqual(c("motherf", "motherf", english: true, on: false).first, "motherfucker")
        XCTAssertEqual(c("Fuc", "Fuc", english: true, on: false).first, "Fuck")
        XCTAssertFalse(c("fuc", "fuc", english: true, on: true).contains("fuck"))
        XCTAssertEqual(c("holy ", english: true, on: false).first, "shit")
        XCTAssertEqual(c("holy ", english: true, on: true), [])
        // trộn theo tần suất: từ enlexicon ≥ englishFreq trước, hoà theo rank rồi chữ cái
        let en = [VNSuggest.Match(word: "full", freq: 200), VNSuggest.Match(word: "fun", freq: 120)]
        XCTAssertEqual(L.withVulgarEnglish(en, typed: "fu", rank: { _ in 0 }),
                       ["full", "fuck", "fucked", "fucker", "fucking", "fun"])
        XCTAssertEqual(L.withVulgarEnglish([], typed: "DI", rank: { _ in 0 }), ["DICK", "DICKHEAD"])
    }

    /// SensitivePairs (seed-curated [sensitive-bigram]): cặp chửi/tục ghép từ hai TỪ THƯỜNG — bật lọc ⇒
    /// sau prev không bao giờ gợi ý word (từ kế tiếp, hoàn thành từ đang gõ) và cặp không thưởng ngữ
    /// cảnh; tắt lọc ⇒ seed hiện như cũ.
    func testSensitivePairsGatedByFilter() {
        func c(_ before: String, _ typed: String = "", english: Bool = false, on: Bool) -> [String] {
            guard let r = L.request(before: before, typed: typed, english: english) else { return [] }
            return PredictionModel.shared.candidates(r, raw: typed, filterSensitive: on)
        }
        XCTAssertGreaterThanOrEqual(SensitiveWords.pairs.values.map(\.count).reduce(0, +), 30)
        for (prev, words) in SensitiveWords.pairs {
            for w in words {
                XCTAssertFalse(SensitiveWords.set.contains(w), "\(prev) \(w): đã lọc theo từ")
                let english = w == "it" || w == "off"
                XCTAssertFalse(c(prev + " ", english: english, on: true).contains(w), "\(prev) → \(w)")
                let t = String(w.prefix(max(1, min(2, w.count - 1))))
                XCTAssertFalse(c(prev + " " + t, t, english: english, on: true).map { $0.lowercased() }.contains(w),
                               "\(prev) \(t)… → \(w)")
                XCTAssertTrue(PredictionModel.shared.seedNext(prev).contains(w), "seed \(prev) → \(w)")
            }
        }
        XCTAssertEqual(c("sờ ", on: true), ["vào"])
        XCTAssertEqual(c("sờ ", on: false), ["vào", "chim", "cu"])
        XCTAssertEqual(c("nâng ", on: true), ["cao", "cấp"])
        XCTAssertEqual(c("thằng ", on: true), [])
        XCTAssertEqual(c("thằng ", on: false).first, "mặt")
        XCTAssertEqual(c("damn ", english: true, on: true), [])
        XCTAssertEqual(c("damn ", english: true, on: false), ["it"])
        XCTAssertFalse(c("piss of", "of", english: true, on: true).contains("off"))
        XCTAssertEqual(c("piss of", "of", english: true, on: false).first, "off")
        // giữ hiện: từ vựng trung tính
        XCTAssertFalse(SensitiveWords.isPair("lăng", "nhăng") || SensitiveWords.isPair("hãm", "hiếp"))
        XCTAssertTrue(SensitiveWords.isPair("Thằng", "CHÓ"))
        XCTAssertEqual(SensitiveWords.filter(["chó", "bé", "lồn"], after: "thằng", enabled: true), ["bé"])
        XCTAssertEqual(SensitiveWords.filter(["chó", "bé", "lồn"], after: "thằng", enabled: false), ["chó", "bé", "lồn"])
        XCTAssertEqual(SensitiveWords.dropPairs(["chó", "lồn"], after: "thằng", enabled: true), ["lồn"])
    }

    /// Lô tiếng lóng / chửi 18/10/2026 trên máy tính: từ kế tiếp seed hiện đúng, cặp lọc chỉ khi tắt lọc.
    func testSlangBatchesOnDesktop() {
        func c(_ before: String, _ typed: String = "", on: Bool = true) -> [String] {
            guard let r = L.request(before: before, typed: typed, english: false) else { return [] }
            return PredictionModel.shared.candidates(r, raw: typed, filterSensitive: on)
        }
        XCTAssertEqual(c("giang "), [], "giang hồ: vnlm.bin đã có (bigram một từ không đoán trên máy tính) ⇒ không thêm seed")
        XCTAssertEqual(c("gen ").first, "Z", "gen z (DisplayCase chuỗi gen → Z)")
        XCTAssertEqual(c("lạng ").first, "lách")
        XCTAssertEqual(c("rửa ti", "ti").first, "tiền")
        XCTAssertEqual(c("nước ").first, "ngoài")
        XCTAssertEqual(c("xin "), ["lỗi", "chào", "phép"])
        XCTAssertEqual(c("kích "), [])
        XCTAssertEqual(c("kích ", on: false), ["dục"])
        XCTAssertFalse(c("óc ").contains("lợn"))
        XCTAssertEqual(c("óc ", on: false), ["chó", "lợn"])
        XCTAssertTrue(c("vãi ").contains("nồi"))
        for w in ["cc", "đb", "lìn"] { XCTAssertTrue(SensitiveWords.set.contains(w)) }
        // từ công nghệ / tài chính (enlexicon SUPPLEMENT) gợi ý tiếng Anh trên máy tính
        func en(_ t: String) -> [String] {
            guard let r = L.request(before: t, typed: t, english: true) else { return [] }
            return PredictionModel.shared.candidates(r, raw: t, filterSensitive: true)
        }
        XCTAssertTrue(en("kuber").contains("kubernetes"), "\(en("kuber"))")
        XCTAssertTrue(en("stablec").contains("stablecoin"), "\(en("stablec"))")
        XCTAssertTrue(en("typesc").contains("typescript"), "\(en("typesc"))")
        XCTAssertTrue(en("viettel").contains("viettelex"), "\(en("viettel"))")
        XCTAssertTrue(en("paracet").contains("paracetamol"), "\(en("paracet"))")
        // tác giả: "Phil ␣" → Trinh (không dấu, hoa theo chuỗi DisplayCase)
        XCTAssertEqual(c("Phil ").first, "Trinh")
    }

    /// Tiền pháp định + mã tiền tệ (18/10/2026): "tiền pháp" → định; USD/VNĐ/VND/USDT viết hoa, Bitcoin
    /// hoa đầu, crypto/fiat giữ thường.
    func testFiatAndCurrencyCase() {
        guard let r = L.request(before: "tiền pháp ", typed: "", english: false) else { return XCTFail() }
        XCTAssertEqual(PredictionModel.shared.candidates(r, raw: "", filterSensitive: true).first, "định")
        for (w, d) in [("usd", "USD"), ("usdt", "USDT"), ("vnđ", "VNĐ"), ("vnd", "VND"), ("bitcoin", "Bitcoin"),
                       ("crypto", "crypto"), ("fiat", "fiat")] {
            XCTAssertEqual(DisplayCase.resolve(w), d)
            XCTAssertNotNil(SeedData.unigrams[w], w)
        }
    }

    /// Tiếng Anh giữa câu Việt: chỉ khi phím thô là tiền tố tiếng Anh rõ ràng hơn mọi cách đọc
    /// tiếng Việt (prefersEnglish) — "pers" hiện "pé" → person; KHÔNG BAO GIỜ khi chữ đang gõ là
    /// âm tiết Việt trọn vẹn phổ biến (toi, duoc, cam, thẻ, thin, chan).
    func testEnglishInVietnameseContext() {
        XCTAssertEqual(candidates("tôi gặp pé", typed: "pé", raw: "pers").first, "person")
        XCTAssertEqual(candidates("ưởn", typed: "ưởn", raw: "wron").first, "wrong")
        XCTAssertEqual(candidates("bạn tech", typed: "tech", raw: "tech").first, "technology")
        for (typed, raw) in [("toi", "toi"), ("duoc", "duoc"), ("cam", "cam"), ("thẻ", "ther"),
                             ("thin", "thin"), ("chan", "chan"), ("thanh", "thanh"), ("khô", "khoo")] {
            let c = candidates("anh " + typed, typed: typed, raw: raw)
            XCTAssertFalse(c.contains { EnglishLite.contains($0.lowercased()) && !VNSuggest.contains($0) },
                           "\(raw) → \(c)")
        }
    }

    /// Bảo thủ: mọi tiền tố ≥ 4 phím (Telex, hai kiểu đặt dấu) và dạng không dấu của âm tiết
    /// trong câu heldout ⇒ prefersEnglish KHÔNG BAO GIỜ bật (đo 16/10/2026: 0 / ~36k).
    func testEnglishOverrideNeverFiresOnVietnamese() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("iOS/KeyboardTests/Fixtures/bigram-heldout.txt")
        let text = try String(contentsOf: url, encoding: .utf8)
        let syllables = Set(text.split(separator: "\n").filter { !$0.hasPrefix("#") }
            .flatMap { $0.split(separator: " ").map(String.init) })
        let compose = TypoFixLogic.composer(AppState.EngineFlags())
        var checked = 0, fired: [String] = []
        for s in syllables {
            var raws = TelexKeys.variants(s).filter { $0.count >= 4 }.flatMap { v in (4...v.count).map { String(v.prefix($0)) } }
            let t = TelexKeys.toneless(s)
            if t.count >= 4 { raws.append(t) }
            for raw in raws {
                let typed = compose(raw)
                let pool = VNSuggest.matches(typed, poolLimit: 24, excluding: typed.lowercased())
                guard !pool.isEmpty else { continue }   // không khớp gì: đường tiếng Anh cũ
                checked += 1
                if L.prefersEnglish(raw: raw, typed: typed, pool: pool.map { ($0.word, $0.freq) },
                                    typedFreq: VNSuggest.frequency(of: typed),
                                    englishTop: EnglishLite.completions(raw, limit: 1).first?.freq) {
                    fired.append("\(raw)/\(typed)")
                }
            }
        }
        print("PREDICT EN-OVERRIDE vietnamese prefixes checked \(checked), fired \(fired.count): \(fired.prefix(10))")
        XCTAssertGreaterThan(checked, 1000)
        XCTAssertEqual(fired, [])
    }

    func testNextWordDisplayCase() {
        let c = candidates("thành phố hồ ")
        XCTAssertFalse(c.contains("chí"), "\(c)")
        XCTAssertEqual(c.first, "Chí", "\(c)")
        if c.contains(where: { $0.lowercased() == "chí" }) { XCTAssertTrue(c.contains("Chí")) }
    }

    func testCompletionRanking() {
        let c = candidates("tôi đư", typed: "đư", raw: "dduw")
        XCTAssertTrue(c.contains("được"), "\(c)")
        XCTAssertLessThanOrEqual(c.count, L.rankDepth)
        for w in c { XCTAssertGreaterThan(w.count, 2, "chỉ hoàn thành thật: \(w)") }
        // chữ hoa theo chữ đã gõ
        let up = candidates("Đư", typed: "Đư", raw: "Dduw")
        XCTAssertTrue(up.allSatisfy { $0.first?.isUppercase == true }, "\(up)")
    }

    func testCompletionContextMatters() {
        // ngữ cảnh trigram/bigram tĩnh: "cảm ơ" → ơn đứng đầu
        XCTAssertEqual(candidates("cảm ơ", typed: "ơ", raw: "ow"), [], "1 chữ: không gợi ý")
        let c = candidates("xin ch", typed: "ch", raw: "ch")
        XCTAssertEqual(c.first, "chào", "\(c)")
    }

    func testEnglishCompletion() {
        let c = candidates("my keyb", typed: "keyb", raw: "keyb")
        XCTAssertTrue(c.contains("keyboard"), "không âm tiết Việt nào khớp ⇒ tiếng Anh: \(c)")
        let e = candidates("this is beca", typed: "beca", raw: "beca", english: true)
        XCTAssertTrue(e.contains("because"), "\(e)")
        // Telex đã đổi chữ trên màn hình ("thí"), phím thô vẫn là "this" ⇒ hoàn thành theo phím thô
        let t = candidates("thí", typed: "thí", raw: "this", english: true)
        XCTAssertTrue(t.allSatisfy { $0.hasPrefix("this") && $0.count > 4 }, "\(t)")
    }

    func testEnglishLiteCompletionsMatchSwipeEnglish() {
        for p in ["a", "th", "keyb", "Hel", "HEL", "zzz", "beca", "x"] {
            XCTAssertEqual(EnglishLite.completions(p, limit: 24), SwipeEnglish.completions(p, limit: 24), p)
        }
        XCTAssertEqual(EnglishLite.completions("đi"), [])
    }

    func testSensitiveWordsNeverSuggested() {
        for (before, typed) in [("đị", "đị"), ("đi", "đi"), ("fu", "fu"), ("sh", "sh"), ("vc", "vc")] {
            let c = candidates(before, typed: typed, raw: typed)
            XCTAssertTrue(c.allSatisfy { !SensitiveWords.set.contains($0.lowercased()) }, "\(c)")
        }
    }

    // MARK: Gợi ý trên CaretHint

    func testSuggestionReplacesComposingWord() {
        let s = L.suggestion(["được", "đường", "đưa", "đứng"], typed: "đư")!
        XCTAssertEqual(s.kind, .predict)
        XCTAssertEqual(s.replace, "đư", "thay đúng từ đang soạn (đã nằm trên màn hình — in-place)")
        XCTAssertEqual(s.insert, "được ")
        XCTAssertEqual(s, CaretSuggestion(kind: .predict, display: "được", replace: "đư", insert: "được "),
                       "chỉ hạng 1 — không danh sách")
        XCTAssertEqual(L.maxCandidates, 1)
        // bước áp dụng kiểm lại màn hình: từ phải đứng riêng ngay trước con trỏ
        XCTAssertTrue(CaretHintLogic.standsAlone(before: "anh đư", token: s.replace))
        XCTAssertFalse(CaretHintLogic.standsAlone(before: "anhđư", token: s.replace))
        XCTAssertFalse(CaretHintLogic.standsAlone(before: "anh đươ", token: s.replace))
        let window = "chào đư"
        let n = (window as NSString).length
        XCTAssertEqual(ShortcutScreen.tokenRange(caret: 100 + n, token: s.replace, window: window, windowStart: 100),
                       NSRange(location: 105, length: 2))
        XCTAssertNil(L.suggestion([], typed: "x"))
        let next = L.suggestion(["ơn"], typed: "")!
        XCTAssertEqual(next.replace, "", "từ kế tiếp: chỉ chèn")
        XCTAssertEqual(next.insert, "ơn ")
    }

    func testSingleCandidateKeysAndClick() {
        let hint = CaretHint.shared
        let s = L.suggestion(["được", "đường", "đưa"], typed: "đư")!
        hint.setPendingForTesting(s)
        XCTAssertEqual(hint.candidateStrings, ["được"], "một ứng viên")
        XCTAssertEqual(CaretHintLogic.action(kind: .predict, keyCode: CaretHintLogic.kTab, plain: true), .accept)
        XCTAssertEqual(CaretHintLogic.action(kind: .predict, keyCode: CaretHintLogic.kReturn, plain: true), .dismissPass,
                       "Enter không bao giờ chọn gợi ý từ")
        XCTAssertEqual(CaretHintLogic.action(kind: .predict, keyCode: 125 /* ↓ */, plain: true), .dismissPass)
        XCTAssertEqual(CaretHintLogic.action(kind: .predict, keyCode: CaretHintLogic.kEscape, plain: true), .dismissConsume)
        // phím số không chọn (không có số thứ tự)
        XCTAssertEqual(CaretHintLogic.action(kind: .predict, keyCode: 18 /* "1" */, plain: true), .dismissPass)
        XCTAssertEqual(hint.take()?.insert, "được ", "Tab / click = ứng viên duy nhất")
        XCTAssertFalse(hint.isShowing)
    }

    func testOtherHintsWinOverPrediction() {
        // Gợi ý khác đang hiện ⇒ không lên lịch gợi ý từ.
        XCTAssertFalse(L.shouldSchedule(settingOn: true, canReplace: true, otherHint: CaretHint.shared.isShowing || true))
        let typo = CaretSuggestion(kind: .typo, display: "the", replace: "teh ", insert: "the ")
        CaretHint.shared.setPendingForTesting(typo)
        XCTAssertTrue(CaretHint.shared.isShowing)
        XCTAssertEqual(CaretHint.shared.candidateStrings, ["the"])
    }

    func testEscMutesUntilNextWord() {
        XCTAssertTrue(L.muted(typed: "đươ", declined: "đư"))
        XCTAssertTrue(L.muted(typed: "Đư", declined: "đư"))
        XCTAssertFalse(L.muted(typed: "đa", declined: "đư"))
        XCTAssertFalse(L.muted(typed: "", declined: "đư"))
        XCTAssertFalse(L.muted(typed: "đư", declined: nil))
        let hint = CaretHint.shared
        hint.setPendingForTesting(L.suggestion(["được"], typed: "đư"))
        hint.decline()
        XCTAssertFalse(hint.isShowing)
    }

    // MARK: Đo đạc

    /// Thời gian tính một lượt (nền) — đã nạp. In "PREDICT" để đọc số.
    func testPerfPerBoundaryAndPerKey() {
        _ = candidates("cảm ơn ")                    // nạp
        _ = candidates("tôi đư", typed: "đư", raw: "dduw")
        let next = ["cảm ơn ", "xin chào ", "tôi đang ", "hôm nay ", "đi học ", "không có ", "anh ấy ",
                    "chúng ta ", "nhà của ", "tôi và "]
        let comp: [(String, String, String)] = [("tôi đư", "đư", "dduw"), ("xin ch", "ch", "ch"),
                                                ("ng", "ng", "ng"), ("anh th", "th", "th"),
                                                ("my keyb", "keyb", "keyb"), ("khô", "khô", "khoo")]
        let reqs = next.compactMap { L.request(before: $0, typed: "", english: false) }.map { ($0, "") }
            + comp.compactMap { c in L.request(before: c.0, typed: c.1, english: false).map { ($0, c.2) } }
        let rounds = 50
        var worst: UInt64 = 0
        let t0 = DispatchTime.now().uptimeNanoseconds
        for _ in 0..<rounds {
            for (r, raw) in reqs {
                let a = DispatchTime.now().uptimeNanoseconds
                _ = PredictionModel.shared.candidates(r, raw: raw)
                worst = max(worst, DispatchTime.now().uptimeNanoseconds - a)
            }
        }
        let avgUs = Double(DispatchTime.now().uptimeNanoseconds - t0) / Double(rounds * reqs.count) / 1000
        print(String(format: "PREDICT per-request avg %.0f µs, worst %.0f µs (%d requests)",
                     avgUs, Double(worst) / 1000, rounds * reqs.count))
        XCTAssertLessThan(avgUs, 3000, "tính nền, nhưng phải nhỏ hơn nhiều so với idleDelay")
    }
}

/// RAM khi nạp gợi ý từ — CHẠY RIÊNG (-only-testing:VietTelexTests/WordPredictionMemoryTests)
/// để số đo là từ trạng thái lạnh. In "PREDICT MEM".
final class WordPredictionMemoryTests: XCTestCase {
    /// (footprint = phys_footprint "Memory" của Activity Monitor; resident = RSS gồm cả trang
    /// mmap sạch của vnlexicon/vnlm/enlexicon đã chạm).
    private func sample() -> (fp: Int64, rss: Int64) {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return kr == KERN_SUCCESS ? (Int64(info.phys_footprint), Int64(info.resident_size)) : (0, 0)
    }

    func testAResidentDeltaOfLoadingModel() {   // "A": chạy trước test khác trong lớp (trạng thái lạnh)
        func mb(_ a: (fp: Int64, rss: Int64), _ b: (fp: Int64, rss: Int64)) -> String {
            String(format: "footprint %+.2f / resident %+.2f MB", Double(b.fp - a.fp) / 1_048_576,
                   Double(b.rss - a.rss) / 1_048_576)
        }
        let m0 = sample()
        _ = VNSuggest.matches("đư", poolLimit: 24)                        // vnlexicon (có thể đã nạp bởi sửa lỗi gõ)
        let m1 = sample()
        _ = SyllableLM.shared?.bigram(0)                                    // vnlm.bin mmap
        _ = EnglishLite.completions("keyb")                                 // enlexicon.bin mmap + bảng offset
        let m2 = sample()
        let r = PredictionLogic.request(before: "cảm ơn ", typed: "", english: false)!
        _ = PredictionModel.shared.candidates(r, raw: "")                   // đếm seed
        let r2 = PredictionLogic.request(before: "tôi đư", typed: "đư", english: false)!
        _ = PredictionModel.shared.candidates(r2, raw: "dduw")
        let m3 = sample()
        for before in ["xin chào ", "tôi đang ", "hôm nay ", "anh ấy ", "nhà của ", "tôi và ", "không có "] {
            if let r = PredictionLogic.request(before: before, typed: "", english: false) {
                _ = PredictionModel.shared.candidates(r, raw: "")
            }
        }
        let m4 = sample()
        print("PREDICT MEM lexicon: \(mb(m0, m1)); vnlm+English: \(mb(m1, m2)); seed model + first queries: \(mb(m2, m3)); "
              + "7 more boundaries: \(mb(m3, m4)); TOTAL: \(mb(m0, m4))")
        XCTAssertLessThan(m4.fp - m0.fp, 8 << 20)
        PredictionModel.shared.unload()
        XCTAssertFalse(PredictionModel.shared.isLoaded)
    }

    /// In vài lượt mẫu (grep "PREDICT SAMPLE") — đọc bằng mắt, không assert.
    func testPrintSamples() {
        let cases: [(String, String, String, Bool)] = [
            ("cảm ơn ", "", "", false), ("xin chào ", "", "", false), ("tôi đang ", "", "", false),
            ("hôm nay ", "", "", false), ("Tên ", "", "", false), ("thành phố hồ ", "", "", false),
            ("tôi đư", "đư", "dduw", false), ("xin ch", "ch", "ch", false), ("khô", "khô", "khoo", false),
            ("my keyb", "keyb", "keyb", false), ("this is beca", "beca", "beca", true)]
        for c in cases {
            guard let r = PredictionLogic.request(before: c.0, typed: c.1, english: c.3) else { continue }
            print("PREDICT SAMPLE '\(c.0)' → \(PredictionModel.shared.candidates(r, raw: c.2))")
        }
    }
}

/// Chuỗi phím Telex của một âm tiết (dấu thanh cuối / trước phụ âm cuối) + dạng không dấu —
/// cho các bài đo bảo thủ (bản rút gọn TypoFixEvalTests.variants).
enum TelexKeys {
    static func variants(_ syllable: String) -> [String] {
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
