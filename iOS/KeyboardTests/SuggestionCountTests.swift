import XCTest

/// "Số từ gợi ý" (SuggestionCount, khoá `suggestionCount`: 0 = Tự động, 1…5) — cùng bảng ca
/// với Android SuggestionCountTests.kt (so payload).
final class SuggestionCountTests: XCTestCase {
    typealias S = KeyboardView.SuggestionSet
    private let N = KeyboardView.numberToken
    private let ADD = KeyboardView.addTonesToken
    private let M = KeyboardView.mathToken

    private func payloads(_ set: S, count: Int, middle: Bool = false) -> String {
        switch SuggestionSlots.arrange(set, bestInMiddle: middle, count: count) {
        case .pill(let c): return "pill:" + c.payload
        case .pasteCard: return "paste"
        case .chips(let c): return "chips:" + c.map(\.payload).joined(separator: "|")
        case .slots(let s, let e):
            return s.map { $0?.payload ?? "-" }.joined(separator: "|") + (e.isEmpty ? "" : " e:" + e.joined())
        }
    }

    // MARK: cài đặt

    func testParseResolveStep() {
        XCTAssertEqual(SuggestionCount.parse(nil), 0)
        XCTAssertEqual(SuggestionCount.parse(4), 4)
        XCTAssertEqual(SuggestionCount.parse(9), 5)
        XCTAssertEqual(SuggestionCount.parse(-2), 0)
        XCTAssertEqual(SuggestionCount.parse("2"), 2)
        XCTAssertEqual(SuggestionCount.parse(true), 0, "Bool không phải số ô")
        XCTAssertEqual(SuggestionCount.resolved(0), 3, "Tự động = 3 như trước (iPhone lẫn iPad)")
        for v in 1...5 { XCTAssertEqual(SuggestionCount.resolved(v), v) }
        // − / +: Tự động → 1 → … → 5; − ở 1 về Tự động; dừng ở hai đầu.
        XCTAssertEqual(SuggestionCount.step(0, by: 1), 1)
        XCTAssertEqual(SuggestionCount.step(1, by: -1), 0)
        XCTAssertEqual(SuggestionCount.step(0, by: -1), 0)
        XCTAssertEqual(SuggestionCount.step(5, by: 1), 5)
        let d = UserDefaults(suiteName: "vt-suggestion-count")!
        d.removePersistentDomain(forName: "vt-suggestion-count")
        XCTAssertEqual(SuggestionCount.load(d), 0)
        d.set(4, forKey: SuggestionCount.key)
        XCTAssertEqual(SuggestionCount.load(d), 4)
    }

    /// Ô < 44pt ⇒ bớt ô; tối thiểu 1; chưa layout ⇒ số muốn.
    func testMinWidthFallback() {
        XCTAssertEqual(SuggestionCount.fit(5, barWidth: 286), 5)        // iPhone 390: bar ≈ 286
        XCTAssertEqual(SuggestionCount.fit(5, barWidth: 200), 4)
        XCTAssertEqual(SuggestionCount.fit(5, barWidth: 100), 2)
        XCTAssertEqual(SuggestionCount.fit(5, barWidth: 20), 1)
        XCTAssertEqual(SuggestionCount.fit(0, barWidth: 286), 3)
        XCTAssertEqual(SuggestionCount.fit(2, barWidth: 1000), 2)
        XCTAssertEqual(SuggestionCount.fit(4, barWidth: 0), 4)
    }

    func testBackupIncludesKey() throws {
        let spec = try XCTUnwrap(BackupSettings.byKey[SuggestionCount.key])
        guard case .int(let def, let r) = spec.kind else { return XCTFail("suggestionCount phải là .int") }
        XCTAssertEqual(def, 0)
        XCTAssertEqual(r, 0...5)
        XCTAssertEqual(SettingValue.fromSyncString("4", key: SuggestionCount.key), .int(4))
        XCTAssertEqual(SettingValue.fromSyncString("9", key: SuggestionCount.key), .int(5))
    }

    // MARK: sắp ô (SuggestionSlots)

    /// count = 3 (Tự động) giữ y nguyên bố cục cũ; bảng ca chính ở SuggestionSlotsTests.
    func testComposingPerCount() {
        var s = S(); s.literal = "anh"; s.word = "ánh"; s.word2 = "ảnh"; s.moreWords = ["anh", "ành", "ạnh"]
        XCTAssertEqual(payloads(s, count: 1), "ánh", "1 ô = ứng viên chính")
        XCTAssertEqual(payloads(s, count: 2), "anh|ánh")
        XCTAssertEqual(payloads(s, count: 3), "anh|ánh|ảnh")
        XCTAssertEqual(payloads(s, count: 4), "anh|ánh|ảnh|anh")
        XCTAssertEqual(payloads(s, count: 5), "anh|ánh|ảnh|anh|ành")
        s.emojis = ["😀", "🙂", "😎"]
        XCTAssertEqual(payloads(s, count: 2), "anh|ánh", "< 3 ô: không vùng emoji")
        XCTAssertEqual(payloads(s, count: 3), "anh|ánh|- e:😀🙂😎")
        XCTAssertEqual(payloads(s, count: 5), "anh|ánh|ảnh|anh|- e:😀🙂", "ô cuối hẹp ⇒ ≤ 2 emoji")
        var lit = S(); lit.literal = "xyz"
        XCTAssertEqual(payloads(lit, count: 1), "xyz", "không ứng viên ⇒ nguyên văn")
    }

    func testNextWordsPerCount() {
        var n = S(); n.nextWords = ["a", "b", "c", "d", "e"]
        XCTAssertEqual(payloads(n, count: 1), "a")
        XCTAssertEqual(payloads(n, count: 4), "a|b|c|d")
        XCTAssertEqual(payloads(n, count: 5), "a|b|c|d|e")
        XCTAssertEqual(payloads(n, count: 3, middle: true), "b|a|c")
        XCTAssertEqual(payloads(n, count: 4, middle: true), "b|a|c|d")
        XCTAssertEqual(payloads(n, count: 5, middle: true), "d|b|a|c|e")
        XCTAssertEqual(SuggestionSlots.nextOrder(count: 5, bestInMiddle: true), [2, 1, 3, 0, 4])
        XCTAssertEqual(SuggestionSlots.nextOrder(count: 2, bestInMiddle: true), [0, 1])
        XCTAssertEqual(SuggestionSlots.primaryIndex(count: 3, nextWords: true, bestInMiddle: true), 1)
        XCTAssertEqual(SuggestionSlots.primaryIndex(count: 5, nextWords: true, bestInMiddle: true), 2)
        XCTAssertEqual(SuggestionSlots.primaryIndex(count: 5, nextWords: false, bestInMiddle: true), 1)
        XCTAssertEqual(SuggestionSlots.primaryIndex(count: 1, nextWords: true, bestInMiddle: true), 0)
    }

    func testChipsPerCount() {
        var s = S(); s.nextWords = ["a", "b", "c", "d", "e"]; s.actionLabel = "Thêm dấu"; s.actionPayload = ADD
        XCTAssertEqual(payloads(s, count: 1), ADD)
        XCTAssertEqual(payloads(s, count: 5), "\(ADD)|a|b|c|d")
        s.number = "x"
        XCTAssertEqual(payloads(s, count: 5), "\(ADD)|\(N)|a|b|c")
        XCTAssertEqual(payloads(s, count: 1), N)
        var m = S(); m.nextWords = ["a", "b", "c", "d"]; m.math = "36"
        XCTAssertEqual(payloads(m, count: 4), "\(M)|a|b|c")
        XCTAssertEqual(payloads(m, count: 1), M)
        // clipboard / thẻ Dán không phụ thuộc số ô
        var c = S(); c.paste = true
        XCTAssertEqual(payloads(c, count: 5), "paste")
    }

    // MARK: thanh gợi ý thật (KeyboardView)

    @MainActor private func makeKeyboard(width: CGFloat) -> (KeyboardView, UIView) {
        let kb = KeyboardView(needsGlobe: false, inputController: nil, onKey: { _ in })
        let host = UIView(frame: CGRect(x: 0, y: 0, width: width, height: 320))
        host.addSubview(kb); kb.frame = host.bounds
        kb.setSuggestionsEnabled(true)
        kb.layoutIfNeeded()
        return (kb, host)
    }

    @MainActor func testBarRendersSlotCountPerOption() {
        let (kb, host) = makeKeyboard(width: 390)
        let set = S(nextWords: ["a", "b", "c", "d", "e"])
        for (setting, want) in [(0, 3), (1, 1), (2, 2), (3, 3), (4, 4), (5, 5)] {
            kb.suggestionCountSetting = setting
            kb.layoutIfNeeded()
            kb.showSuggestions(set)
            XCTAssertEqual(kb.suggestionSlotCount, want, "setting \(setting)")
            XCTAssertEqual(kb.debugSlotPayloads(), Array(["a", "b", "c", "d", "e"].prefix(want)), "setting \(setting)")
            let g = kb.debugSuggestionGeometry()
            XCTAssertEqual(g.cells.count, want)
            XCTAssertEqual(g.dividers.count, want - 1)
            for c in g.cells {
                XCTAssertEqual(c.width, g.cells[0].width, accuracy: 0.5)
                XCTAssertGreaterThanOrEqual(c.width, SuggestionCount.minSlotWidth)
            }
            for t in g.visibleTitles {
                XCTAssertTrue(g.cells.contains { $0.insetBy(dx: -1, dy: -40).contains(t) }, "chữ tràn khỏi ô: \(t)")
            }
        }
        withExtendedLifetime(host) {}
    }

    /// Bar quá hẹp cho 5 ô ≥ 44pt ⇒ hiện số ô vừa (vẽ lại ngay khi bề rộng đổi).
    @MainActor func testNarrowBarShowsAsManyAsFit() {
        let (kb, host) = makeKeyboard(width: 390)
        kb.suggestionCountSetting = 5
        kb.showSuggestions(S(nextWords: ["a", "b", "c", "d", "e"]))
        XCTAssertEqual(kb.debugSlotPayloads().count, 5)
        host.frame.size.width = 290; kb.frame = host.bounds
        kb.layoutIfNeeded()
        let bar = kb.debugStripLayout().bar.width
        let fit = SuggestionCount.fit(5, barWidth: bar)
        XCTAssertLessThan(fit, 5, "bar \(bar)")
        XCTAssertEqual(kb.suggestionSlotCount, fit)
        XCTAssertEqual(kb.debugSlotPayloads(), Array(["a", "b", "c", "d", "e"].prefix(fit)))
        for c in kb.debugSuggestionGeometry().cells { XCTAssertGreaterThanOrEqual(c.width, SuggestionCount.minSlotWidth) }
        withExtendedLifetime(host) {}
    }

    // MARK: ranking xin đủ N ứng viên

    func testNextFillReturnsN() {
        let m = UserLangModel(appGroup: nil)
        m.isKnownWord = { VNSuggest.contains($0) }
        m.seedIfEmpty(unigrams: SeedData.unigrams, bigrams: SeedData.bigrams)
        let personal = SensitiveWords.filter(m.nextWords(after: "không", prev2: "tôi", limit: 6), enabled: true)
        let tc = { (w: String) in m.trigramCount("tôi", "không", w) }
        let three = SuggestRank.nextFill("không", prev2: "tôi", personal: personal, triCount: tc)
        for n in 1...5 {
            let r = SuggestRank.nextFill("không", prev2: "tôi", personal: personal, triCount: tc, need: n)
            XCTAssertEqual(r.count, n, "need \(n): \(r)")
            XCTAssertEqual(Set(r).count, r.count)
            // thứ hạng ổn định: N ô đầu trùng danh sách 3 ô cũ
            XCTAssertEqual(Array(r.prefix(min(n, 3))), Array(three.prefix(min(n, 3))))
        }
        let bigramOnly = SuggestRank.nextFill("không", prev2: nil, personal: [], triCount: { _ in 0 }, need: 5)
        XCTAssertEqual(bigramOnly.count, 5)
    }

    /// Đường thật qua controller: cài đặt 5 ⇒ 5 ô có chữ cả khi đang gõ lẫn sau dấu cách.
    @MainActor func testControllerFillsFiveSlots() {
        let saved = UserDefaultsProvider.shared
        let d = KeyboardBenchTests.makeDefaults("B")
        d.set(5, forKey: SuggestionCount.key)
        d.set(false, forKey: "emojiSuggest")
        UserDefaultsProvider.shared = d
        defer { UserDefaultsProvider.shared = saved }
        let rig = KeyboardBenchTests.Rig()
        defer { rig.close() }
        let kb = rig.vc.debugKeyboard
        for (i, c) in "toi di ".enumerated() { _ = rig.key(c, index: i + 1, drain: 0.04) }
        KeyboardBenchTests.spin(0.15)
        XCTAssertEqual(kb.suggestionSlotCount, 5)
        let next = kb.debugSlotPayloads()
        XCTAssertEqual(next.compactMap { $0 }.count, 5, "\(next)")
        for (i, c) in "an".enumerated() { _ = rig.key(c, index: i + 8, drain: 0.04) }
        KeyboardBenchTests.spin(0.2)
        let typing = kb.debugSlotPayloads()
        XCTAssertEqual(typing.compactMap { $0 }.count, 5, "\(typing)")
        XCTAssertEqual(typing.first ?? nil, "an", "ô đầu vẫn là nguyên văn")
    }
}
