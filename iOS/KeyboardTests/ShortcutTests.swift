// Gõ tắt: hàm thuần (khớp / giữ hoa / ranh giới / #82 #87) + luồng EngineBridge qua
// MockProxy (bung, ⌫ trả lại, passthrough/secure không bung, từ vuốt không bung) + file
// YAML khứ hồi với sample-shortcuts.yml. Cùng bộ ca với android ShortcutsTests.kt.
import XCTest
import TelexCore

final class ShortcutTests: XCTestCase {

    private let table = ShortcutTable([
        "ko": "không", "đc": "được", "mn": "mọi người", "->": "→", "/shop": "cửa hàng", "k2": "không hai",
        "h": "giờ", "HN": "Hà Nội", "sig": "Thân mến,\nPhil", "j": "gì",
    ])

    private func settings(_ t: ShortcutTable? = nil, enabled: Bool = true) -> KeyboardSettings {
        var s = KeyboardSettings()
        s.shortcuts = t ?? table
        s.shortcutsEnabled = enabled
        return s
    }

    /// Gõ `keys` qua bridge: " " "," "." "\n" "!" = ranh giới, "⌫" = xoá, chữ số/ký hiệu
    /// khác = boundary(.text) như phím số/ký hiệu, còn lại = phím chữ.
    @discardableResult
    private func type(_ keys: String, bridge: EngineBridge, proxy: MockProxy) -> String {
        for ch in keys {
            if ch == "⌫" { bridge.backspace(proxy: proxy) }
            else if ch.isLetter { bridge.letter(ch, proxy: proxy) }
            else { bridge.boundary(String(ch), proxy: proxy) }
        }
        return proxy.text
    }

    private func typed(_ keys: String, _ s: KeyboardSettings? = nil) -> String {
        let p = MockProxy()
        return type(keys, bridge: EngineBridge(settings: s ?? settings()), proxy: p)
    }

    // MARK: hàm thuần

    func testCaseFollowsTyping() {
        XCTAssertEqual(table.expansion(for: "ko"), "không")
        XCTAssertEqual(table.expansion(for: "Ko"), "Không")
        XCTAssertEqual(table.expansion(for: "KO"), "KHÔNG")
        XCTAssertNil(table.expansion(for: "kO"))                 // hoa lộn xộn: không đoán
        XCTAssertEqual(table.expansion(for: "Mn"), "Mọi người")
        XCTAssertEqual(table.expansion(for: "MN"), "MỌI NGƯỜI")
        XCTAssertEqual(table.expansion(for: "J"), "Gì")          // một chữ hoa = viết hoa chữ đầu
        XCTAssertEqual(table.expansion(for: "HN"), "Hà Nội")     // khớp nguyên văn trước
        XCTAssertNil(table.expansion(for: "hn"))                 // khoá hoa không khớp chữ thường
    }

    func testWordMatchComposedThenRaw() {
        // "ddc" → hiển thị "đc" khớp khoá "đc"; "dc" không khớp (chỉ có "đc")
        XCTAssertEqual(table.wordExpansion(composed: "đc", raw: "ddc"), "được")
        XCTAssertNil(table.wordExpansion(composed: "dc", raw: "dc"))
        let t2 = ShortcutTable(["dc": "được"])
        XCTAssertEqual(t2.wordExpansion(composed: "dc", raw: "dc"), "được")
        XCTAssertNil(t2.wordExpansion(composed: "đc", raw: "ddc"))
        // khoá chứa phím Telex chỉ khớp qua phím thô
        let t3 = ShortcutTable(["ks": "khách sạn"])
        XCTAssertEqual(t3.wordExpansion(composed: "kś", raw: "ks"), "khách sạn")
    }

    func testTokenKeysAndTriggers() {
        XCTAssertTrue(table.hasTokenKeys)
        XCTAssertFalse(ShortcutTable(["ko": "không"]).hasTokenKeys)
        XCTAssertEqual(table.tokenExpansion(context: "a ->")?.expansion, "→")
        XCTAssertEqual(table.tokenExpansion(context: "k2")?.token, "k2")
        XCTAssertNil(table.tokenExpansion(context: "a->"))       // cả cụm "a->" ≠ "->"
        XCTAssertNil(table.tokenExpansion(context: "ko"))        // khoá chữ đi đường từ
        XCTAssertTrue(ShortcutTable.triggersWord(" "))
        XCTAssertTrue(ShortcutTable.triggersWord(","))
        XCTAssertTrue(ShortcutTable.triggersWord("\n"))
        XCTAssertFalse(ShortcutTable.triggersWord("1"))
        XCTAssertFalse(ShortcutTable.triggersWord("@"))
        XCTAssertFalse(ShortcutTable.triggersWord("/"))
        XCTAssertFalse(ShortcutTable.triggersToken(","))
        XCTAssertFalse(ShortcutTable.isValidKey("a b"))
        XCTAssertFalse(ShortcutTable.isValidKey(""))
    }

    func testGlued() {
        XCTAssertTrue(ShortcutTable.isGlued(word: "h", context: "5h"))     // #82
        XCTAssertTrue(ShortcutTable.isGlued(word: "h", context: "/h"))     // #87
        XCTAssertTrue(ShortcutTable.isGlued(word: "ko", context: "a@ko"))
        XCTAssertFalse(ShortcutTable.isGlued(word: "h", context: "5 h"))
        XCTAssertFalse(ShortcutTable.isGlued(word: "ko", context: "ko"))
        XCTAssertFalse(ShortcutTable.isGlued(word: "ko", context: nil))
        XCTAssertTrue(ShortcutTable.isGlued(word: "ko", context: "khác"))  // màn hình lệch
    }

    // MARK: luồng EngineBridge

    func testExpandsOnBoundaries() {
        XCTAssertEqual(typed("ko "), "không ")
        XCTAssertEqual(typed("Ko,"), "Không,")
        XCTAssertEqual(typed("KO."), "KHÔNG.")
        XCTAssertEqual(typed("mn!"), "mọi người!")
        XCTAssertEqual(typed("ko\n"), "không\n")
        XCTAssertEqual(typed("ddc "), "được ")
        XCTAssertEqual(typed("dc "), "dc ")                     // chỉ có khoá "đc"
        XCTAssertEqual(typed("sig "), "Thân mến,\nPhil ")       // nhiều dòng
    }

    /// Khoá có tiền tố dấu câu (fork vtx "/shop"): cả cụm trước con trỏ khớp khoá ký hiệu.
    func testPunctuationPrefixKey() {
        XCTAssertEqual(typed("/shop "), "cửa hàng ")
        XCTAssertEqual(typed("xem /shop "), "xem cửa hàng ")
        XCTAssertEqual(typed("a/shop "), "a/shop ")          // dính chữ trước ⇒ không nở
        XCTAssertEqual(typed("/h "), "/h ")                  // #87 vẫn giữ
    }

    func testNotOnNonTriggerOrGlued() {
        XCTAssertEqual(typed("ko1"), "ko1")
        XCTAssertEqual(typed("5h "), "5h ")                     // #82
        XCTAssertEqual(typed("5h30"), "5h30")
        XCTAssertEqual(typed("5 h "), "5 giờ ")
        XCTAssertEqual(typed("/h "), "/h ")                     // #87
        XCTAssertEqual(typed("a@ko "), "a@ko ")
    }

    func testSymbolAndDigitKeys() {
        XCTAssertEqual(typed("a -> b"), "a → b")
        XCTAssertEqual(typed("k2 "), "không hai ")
        XCTAssertEqual(typed("a->b "), "a->b ")
        XCTAssertEqual(typed("->,"), "->,")                      // khoá ký hiệu chỉ nở ở space/Enter
    }

    // MARK: issue #109 — khoá chữ+số ("ad1", "sdt2", "2fa") nở như khoá chữ

    private let alnum = ShortcutTable([
        "ad": "add", "ad1": "address", "sdt2": "số điện thoại 2", "2fa": "xác thực hai lớp",
        "fa": "FA", "ko": "không", "->": "→", "123": "một hai ba",
    ])

    /// VNI: phím số là phím dấu (controller gọi vniDigit trước, false ⇒ ranh giới).
    private func typedVNI(_ keys: String) -> String {
        var s = settings(alnum); s.vniMode = true
        let b = EngineBridge(settings: s), p = MockProxy()
        for ch in keys {
            if ch.isNumber, b.vniDigit(ch, proxy: p) { continue }
            if ch.isLetter { b.letter(ch, proxy: p) } else { b.boundary(String(ch), proxy: p) }
        }
        return p.text
    }

    func testAlnumKeyPure() {
        XCTAssertTrue(ShortcutTable.isAlnumKey("ad1"))
        XCTAssertTrue(ShortcutTable.isAlnumKey("2fa"))
        XCTAssertFalse(ShortcutTable.isAlnumKey("ad"))
        XCTAssertFalse(ShortcutTable.isAlnumKey("123"))
        XCTAssertFalse(ShortcutTable.isAlnumKey("a1-"))
        XCTAssertTrue(ShortcutTable.triggersAlnum("."))
        XCTAssertTrue(ShortcutTable.triggersAlnum(" "))
        XCTAssertFalse(ShortcutTable.triggersAlnum("1"))
        XCTAssertFalse(ShortcutTable.triggersAlnum("-"))
        // VNI: "ad1" soạn thành "ád" — khớp qua phím thô.
        XCTAssertEqual(alnum.wordExpansion(composed: "ád", raw: "ad1"), "address")
        XCTAssertEqual(alnum.wordExpansion(composed: "sdt2", raw: "sdt2"), "số điện thoại 2")
        XCTAssertNil(alnum.wordExpansion(composed: "", raw: "->"))
    }

    func testAlnumTelexFlow() {
        let s = settings(alnum)
        XCTAssertEqual(typed("ad1 ", s), "address ")
        XCTAssertEqual(typed("ad1.", s), "address.")
        XCTAssertEqual(typed("xem ad1, ", s), "xem address, ")
        XCTAssertEqual(typed("sdt2!", s), "số điện thoại 2!")
        XCTAssertEqual(typed("2fa ", s), "xác thực hai lớp ")   // "fa" dính số nhưng cả cụm là khoá
        XCTAssertEqual(typed("2fa.", s), "xác thực hai lớp.")
        XCTAssertEqual(typed("ad12 ", s), "ad12 ")
        XCTAssertEqual(typed("xad1 ", s), "xad1 ")
        XCTAssertEqual(typed("12ko ", s), "12ko ")               // #82
        XCTAssertEqual(typed("5fa ", s), "5fa ")
        XCTAssertEqual(typed("->, ", s), "->, ")                 // ký hiệu: chỉ space/Enter
        XCTAssertEqual(typed("123, ", s), "123, ")               // thuần số: như cũ
        XCTAssertEqual(typed("123 ", s), "một hai ba ")
        XCTAssertEqual(typed("ad ", s), "add ")
    }

    func testAlnumVNIFlow() {
        XCTAssertEqual(typedVNI("ad1 "), "address ")
        XCTAssertEqual(typedVNI("ad1."), "address.")
        XCTAssertEqual(typedVNI("Ad1 "), "Address ")
        XCTAssertEqual(typedVNI("ad "), "add ")
    }

    func testBackspaceRestoresTypedOnce() {
        let p = MockProxy(), b = EngineBridge(settings: settings())
        type("ko ", bridge: b, proxy: p)
        XCTAssertEqual(p.text, "không ")
        type("⌫", bridge: b, proxy: p)
        XCTAssertEqual(p.text, "ko ")
        type("⌫", bridge: b, proxy: p)                           // lần hai: ⌫ thường
        XCTAssertEqual(p.text, "ko")
        type(" ", bridge: b, proxy: p)                           // không bung lại
        XCTAssertEqual(p.text, "ko ")

        let p2 = MockProxy(), b2 = EngineBridge(settings: settings())
        type("KO a -> ", bridge: b2, proxy: p2)
        XCTAssertEqual(p2.text, "KHÔNG a → ")
        type("⌫", bridge: b2, proxy: p2)
        XCTAssertEqual(p2.text, "KHÔNG a -> ")
    }

    func testBackspaceUndoOnlyImmediately() {
        let p = MockProxy(), b = EngineBridge(settings: settings())
        type("ko a⌫", bridge: b, proxy: p)
        XCTAssertEqual(p.text, "không ")
        // context lệch (host đổi chữ) ⇒ ⌫ thường, không xoá mù
        let p2 = MockProxy(), b2 = EngineBridge(settings: settings())
        type("ko ", bridge: b2, proxy: p2)
        p2.fakeContext = .some("khác ")
        b2.backspace(proxy: p2)
        XCTAssertEqual(p2.text, "không")
        // Enter không hứa hoàn tác
        let p3 = MockProxy(), b3 = EngineBridge(settings: settings())
        type("ko\n⌫", bridge: b3, proxy: p3)
        XCTAssertEqual(p3.text, "không")
    }

    func testPassthroughSecureDisabledOmnibox() {
        let p = MockProxy(); p.isSecure = true
        XCTAssertEqual(type("ko ", bridge: EngineBridge(settings: settings()), proxy: p), "ko ")
        let b = EngineBridge(settings: settings()); b.passthrough = true
        XCTAssertEqual(type("ko ", bridge: b, proxy: MockProxy()), "ko ")
        let b2 = EngineBridge(settings: settings()); b2.shortcutsAllowed = false
        XCTAssertEqual(type("ko ", bridge: b2, proxy: MockProxy()), "ko ")
        XCTAssertEqual(typed("ko ", settings(enabled: false)), "ko ")
    }

    func testBeforeAutoRestoreAndFailSafe() {
        // "ok" là khoá: thắng auto-restore/tiếng Anh
        let t = ShortcutTable(["gg": "Google", "ok": "được rồi"])
        XCTAssertEqual(typed("gg ", settings(t)), "Google ")
        XCTAssertEqual(typed("ok ", settings(t)), "được rồi ")
        // chữ trước con trỏ không còn là từ đang gõ ⇒ không xoá mù
        let p = MockProxy(), b = EngineBridge(settings: settings())
        type("ko", bridge: b, proxy: p)
        p.fakeContext = .some("xyz")
        b.boundary(" ", proxy: p)
        XCTAssertEqual(p.text, "ko ")
    }

    func testSwipeWordNotExpanded() {
        let p = MockProxy(), b = EngineBridge(settings: settings(ShortcutTable(["mn": "mọi người", "ko": "không"])))
        b.insertSwipeWord("ko", proxy: p)
        b.boundary(" ", proxy: p)
        XCTAssertEqual(p.text, "ko ")
    }

    func testExpandedFlagAndPreview() {
        let p = MockProxy(), b = EngineBridge(settings: settings())
        type("Ko", bridge: b, proxy: p)
        XCTAssertEqual(b.shortcutPreview, "Không")
        XCTAssertEqual(b.boundary(" ", proxy: p), "Không")
        XCTAssertTrue(b.expandedAtLastBoundary)
        type("vui", bridge: b, proxy: p)
        XCTAssertNil(b.shortcutPreview)
        b.boundary(" ", proxy: p)
        XCTAssertFalse(b.expandedAtLastBoundary)
        XCTAssertEqual(ShortcutLearning.words("mọi người"), ["mọi", "người"])
    }

    func testReEditStillWorks() {
        // ⌫ mở lại từ thường vẫn chạy khi có bảng gõ tắt
        let p = MockProxy(), b = EngineBridge(settings: settings())
        type("thay ⌫s", bridge: b, proxy: p)
        XCTAssertEqual(p.text, "tháy")
    }

    // MARK: file

    func testParseExportRoundTripSample() throws {
        let url = try XCTUnwrap(Bundle(for: ShortcutTests.self)
            .url(forResource: "sample-shortcuts", withExtension: "yml"))
        let text = try String(contentsOf: url, encoding: .utf8)
        let parsed = ShortcutFile.parse(text)
        XCTAssertEqual(parsed["ko"], "không")
        XCTAssertEqual(parsed["đc"], "được")
        XCTAssertEqual(parsed["stk"], "số tài khoản")
        XCTAssertEqual(parsed.count, 9)
        let exported = ShortcutFile.exportYAML(parsed)
        XCTAssertEqual(exported, text)                           // byte-for-byte như macOS
        XCTAssertEqual(ShortcutFile.parse(exported), parsed)
    }

    func testParseFormatsAndExtensions() {
        let t: [String: String] = [
            "sig": "Thân mến,\nPhil", ":)": "🙂", "->": "→", "sp": " có space ", "q": "\"trích\"",
            "bs": "C:\\temp", "k2": "không hai",
        ]
        XCTAssertEqual(ShortcutFile.parse(ShortcutFile.exportYAML(t)), t)
        XCTAssertEqual(ShortcutFile.parse("{\"ko\":\"không\"}"), ["ko": "không"])
        XCTAssertEqual(ShortcutFile.parse("; txt\nko:không\n// c\nvs: 'với'\nbad line\na b: x\n"),
                       ["ko": "không", "vs": "với"])
        XCTAssertEqual(ShortcutFile.parse("\u{FEFF}ko: không\r\ndc: được\r\n"), ["ko": "không", "dc": "được"])
        let m = ShortcutFile.merge(["ko": "khong", "a": "b"], ["ko": "không", "c": "d"])
        XCTAssertEqual(m.table, ["ko": "không", "a": "b", "c": "d"])
        XCTAssertEqual(m.added, 1); XCTAssertEqual(m.replaced, 1)
        // bộ gợi ý: toàn khoá hợp lệ, không trống
        XCTAssertEqual(ShortcutTable(ShortcutFile.suggested).entries.count, ShortcutFile.suggested.count)
    }

    // MARK: Thay thế văn bản của iOS

    private let lexicon: SupplementaryLexiconCache.Pairs = [
        (input: "omw", text: "On my way!"),
        (input: "ko", text: "KHÔNG PHẢI CỦA TÔI"),     // trùng bảng VietTelex
        (input: "Mn", text: "mọi người ơi"),            // trùng khác hoa/thường
        (input: "Phil", text: "Phil"),                  // tên danh bạ
        (input: "Trinh", text: "Trinh"),
        (input: "a b", text: "khoá có khoảng trắng"),
        (input: "->>", text: "⇒"),
    ]

    func testSystemReplacementFiltersContacts() {
        let e = SystemTextReplacement.entries(from: lexicon)
        XCTAssertEqual(e["omw"], "On my way!")
        XCTAssertNil(e["Phil"]); XCTAssertNil(e["Trinh"])      // userInput == documentText
        XCTAssertNil(e["a b"])
        XCTAssertEqual(e.count, 4)
    }

    func testSystemReplacementMergePrecedence() {
        let sys = SystemTextReplacement.entries(from: lexicon)
        let m = SystemTextReplacement.merged(own: table.entries, system: sys)
        XCTAssertEqual(m.expansion(for: "ko"), "không")           // VietTelex thắng
        XCTAssertEqual(m.expansion(for: "Mn"), "Mọi người")       // thắng cả khi iOS khác hoa
        XCTAssertEqual(m.expansion(for: "omw"), "On my way!")
        XCTAssertEqual(m.expansion(for: "->"), "→")
        XCTAssertEqual(m.expansion(for: "->>"), "⇒")
        XCTAssertTrue(m.hasTokenKeys)
        XCTAssertEqual(SystemTextReplacement.merged(own: table.entries, system: [:]), table)
    }

    func testSystemReplacementExpandsThroughBridge() {
        let p = MockProxy(), b = EngineBridge(settings: settings())
        b.setSystemReplacements(SystemTextReplacement.entries(from: lexicon))
        type("Omw ko ->> ", bridge: b, proxy: p)
        XCTAssertEqual(p.text, "On my way! không ⇒ ")
        type("omw,", bridge: b, proxy: p)
        XCTAssertEqual(p.text, "On my way! không ⇒ On my way!,")  // khoá chữ nở ở dấu câu
        let p2 = MockProxy(), b2 = EngineBridge(settings: settings(ShortcutTable()))
        b2.setSystemReplacements(["brb": "be right back"])
        type("brb ⌫", bridge: b2, proxy: p2)
        XCTAssertEqual(p2.text, "brb ")                             // ⌫ trả lại chữ đã gõ
        XCTAssertEqual(typed("brb ", settings(ShortcutTable())), "brb ")   // chưa nạp ⇒ không bung
    }

    func testSystemReplacementOffMeansNoRequest() {
        var requests = 0
        let cache = SupplementaryLexiconCache { done in requests += 1; done(self.lexicon) }
        let loader = SystemTextReplacementLoader(cache: cache)
        var off = settings(); off.useSystemTextReplacement = false
        let b = EngineBridge(settings: off)
        XCTAssertFalse(loader.load(settings: off, bridge: { b }))
        var shortcutsOff = settings(enabled: false)
        shortcutsOff.useSystemTextReplacement = true
        XCTAssertFalse(loader.load(settings: shortcutsOff, bridge: { b }))
        XCTAssertEqual(requests, 0)
        b.setSystemReplacements(["brb": "be right back"])          // tắt ⇒ bridge bỏ qua
        XCTAssertEqual(type("brb ", bridge: b, proxy: MockProxy()), "brb ")

        // bật: một lần hỏi mỗi phiên, bridge mới vẫn được gộp từ cache
        var loadedCount = 0
        let b1 = EngineBridge(settings: settings())
        XCTAssertTrue(loader.load(settings: settings(), bridge: { b1 }) { _ in loadedCount += 1 })
        XCTAssertEqual(type("omw ", bridge: b1, proxy: MockProxy()), "On my way! ")
        let b2 = EngineBridge(settings: settings())
        loader.load(settings: settings(), bridge: { b2 }) { _ in loadedCount += 1 }
        XCTAssertEqual(type("omw ", bridge: b2, proxy: MockProxy()), "On my way! ")
        XCTAssertEqual(requests, 1)
        XCTAssertEqual(loadedCount, 1)
    }

    func testSystemReplacementSnapshot() throws {
        let d = try XCTUnwrap(UserDefaults(suiteName: "test.systemTextReplacement"))
        d.removePersistentDomain(forName: "test.systemTextReplacement")
        XCTAssertNil(SystemTextReplacement.readSnapshot(d))
        let snap = SystemTextReplacement.Snapshot(["b": "1", "a": "2"])
        SystemTextReplacement.writeSnapshot(snap, to: d)
        XCTAssertEqual(SystemTextReplacement.readSnapshot(d), .init(count: 2, sample: ["a", "b"]))
        XCTAssertNil(SystemTextReplacement.readSnapshot(nil))
    }

    // MARK: thứ tự danh sách — mới nhất trước (góp ý người dùng 19/10/2026)

    func testOrderNewestFirstThenLegacyAlphabetical() {
        let keys = ["ko", "cty", "dc", "mn", "vs"]
        // bảng cũ (chưa có mốc): giữ thứ tự cũ = chữ cái
        XCTAssertEqual(ShortcutOrder.sorted(keys, times: [:]), ["cty", "dc", "ko", "mn", "vs"])
        // thêm "zz" rồi "aa" ⇒ "aa" (mới nhất) đầu, "zz" sau, rồi bảng cũ nguyên thứ tự
        var table = Dictionary(uniqueKeysWithValues: keys.map { ($0, $0) })
        var times: [String: Int64] = [:]
        var old = table; table["zz"] = "z"
        times = ShortcutOrder.updated(times, from: old, to: table, now: 100)
        old = table; table["aa"] = "a"
        times = ShortcutOrder.updated(times, from: old, to: table, now: 200)
        XCTAssertEqual(ShortcutOrder.sorted(table.keys, times: times), ["aa", "zz", "cty", "dc", "ko", "mn", "vs"])
        // sửa nội dung một mục cũ ⇒ lên đầu; đổi tên ⇒ khoá mới lên đầu, mốc khoá cũ bị bỏ
        old = table; table["mn"] = "mọi người"
        times = ShortcutOrder.updated(times, from: old, to: table, now: 300)
        XCTAssertEqual(ShortcutOrder.sorted(table.keys, times: times).prefix(3), ["mn", "aa", "zz"])
        old = table; table.removeValue(forKey: "zz"); table["zz2"] = "z"
        times = ShortcutOrder.updated(times, from: old, to: table, now: 400)
        XCTAssertNil(times["zz"])
        XCTAssertEqual(ShortcutOrder.sorted(table.keys, times: times).first, "zz2")
        // không đổi gì ⇒ mốc giữ nguyên
        XCTAssertEqual(ShortcutOrder.updated(times, from: table, to: table, now: 999), times)
        // hoà mốc (nhập cả loạt) ⇒ chữ cái trong loạt
        XCTAssertEqual(ShortcutOrder.sorted(["b", "a", "c"], times: ["b": 5, "a": 5, "c": 1]), ["a", "b", "c"])
    }

    func testOrderTimesPersistInDefaults() {
        let d = UserDefaults(suiteName: "vt.shortcut.order.\(UUID().uuidString)")!
        XCTAssertEqual(ShortcutOrder.times(from: d), [:])           // di trú: chưa có ⇒ rỗng
        ShortcutOrder.setTimes(["ko": 1_760_860_800_123], in: d)
        XCTAssertEqual(ShortcutOrder.times(from: d), ["ko": 1_760_860_800_123])
    }

    func testOrderImportKeepsFileTimes() {
        let cur = ["ko": "không", "dc": "được"]
        let file = ["ko": "khum", "mn": "mọi người", "vs": "với"]
        let table = cur.merging(file) { _, n in n }
        let t = ShortcutOrder.imported(["dc": 10], fileTimes: ["mn": 50], old: cur, file: file, table: table, now: 100)
        // file có mục mốc: khoá của file theo đúng file (ko/vs không mốc ⇒ mục cũ, cuối theo chữ cái)
        XCTAssertEqual(t, ["dc": 10, "mn": 50])
        XCTAssertEqual(ShortcutOrder.sorted(table.keys, times: t), ["mn", "dc", "ko", "vs"])
        // file bản cũ (không có mốc): mục mới/đổi nội dung lên đầu
        let legacy = ShortcutOrder.imported(["dc": 10], fileTimes: nil, old: cur, file: file, table: table, now: 100)
        XCTAssertEqual(legacy, ["dc": 10, "mn": 100, "ko": 100, "vs": 100])
    }
}
