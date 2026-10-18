import XCTest
import InputMethodKit
@testable import VietTelex

/// Field report 18/10/2026 (macOS 1.8.24/1.8.25): gõ "- abc" ⏎ "- def" ra "- abc-" ⏎ "def" —
/// "-" (và dấu cách sau nó) gõ ngay sau Enter rơi về cuối dòng trước. Gõ một dấu cách cuối
/// dòng 1 trước Enter thì không bị.
///
/// Gốc: Enter chốt một từ ĐANG SOẠN mà lần chốt đó ghi lại văn bản (app marked luôn
/// insertText khi chốt; in-place khi tự khôi phục / gõ tắt / học viết hoa đổi từ) ⇒
/// controller NUỐT Enter và re-post một bản sao để nó tới SAU lần sửa (WhatsApp/web editor).
/// Bản sao vào CUỐI hàng sự kiện — phím user gõ tiếp ("-", " ") đã nằm trong hàng của app
/// (app đang chờ handle() của Enter; web editor marked còn hoãn 60ms) ⇒ app nhận "- " rồi
/// mới tới Return. Dấu cách trước Enter = engine rỗng ⇒ Enter không bị nuốt ⇒ không bị.
/// Sửa: RepostOrder — phím thật tới khi bản sao chưa quay lại được nuốt và post lại SAU nó.
final class NewlineAfterWordTests: XCTestCase {
    private var savedDebug = false
    private var savedBreaker = true

    override func setUp() {
        super.setUp()
        Accessibility.testTrustOverride = true
        FocusedFieldDetector._testSetCached(false)
        FocusedFieldDetector._testSetMarked(false)
        FocusedFieldDetector._testSetPassthrough(false)
        SystemInlinePrediction.setForTesting(false)
        SystemAutoTextSettings.shared._testSet(.init(period: true, capitalize: true, quotes: true, dashes: true))
        savedDebug = AppState.shared.debugLogging
        AppState.shared.debugLogging = true
        savedBreaker = AppState.shared.tapCascadeBreaker
        AppState.shared.tapCascadeBreaker = false
        CaretHint.shared.setPendingForTesting(nil)
        DebugLog.clear()
    }

    override func tearDown() {
        SyntheticKeyboard._testPostKeyHook = nil
        Accessibility.testTrustOverride = nil
        SystemInlinePrediction.setForTesting(nil)
        FocusedFieldDetector._testSetMarked(false)
        AppState.shared.debugLogging = savedDebug
        AppState.shared.tapCascadeBreaker = savedBreaker
        AppState.shared.currentBundleID = nil
        FrontmostApp.shared._testSetBundleID(nil)
        super.tearDown()
    }

    private func harness(_ id: String = "com.apple.TextEdit", text: String = "") -> ControllerHarness {
        let h = ControllerHarness(bundleID: id, text: text)
        h.capturePosts()
        return h
    }

    private func dump(_ h: ControllerHarness) -> String {
        "text=\(h.client.string.debugDescription) posted=\(h.posted.map(\.key))\nclient:\n"
            + h.client.log.joined(separator: "\n") + "\nlog:\n" + DebugLog.snapshot(header: [])
    }

    /// Dòng 1, Enter, dòng 2 gõ đồng bộ (mỗi phím tới sau khi phím trước đã xong).
    private func typeLines(_ h: ControllerHarness, _ first: String, _ second: String) {
        h.type(first)
        h.enter()
        h.deliverPosted()
        h.type(second)
        h.deliverPosted()
    }

    /// Như field: phím dòng 2 tới IME TRƯỚC bản re-post của Enter (gõ nhanh / app bận).
    private func typeLinesRacing(_ h: ControllerHarness, _ first: String, _ second: String,
                                 magicIntact: Bool = false) {
        h.type(first)
        h.enter()
        h.type(second)
        h.deliverPosted(magicIntact: magicIntact)
    }

    // MARK: Đồng bộ (TextEdit / Notes in-place) — mọi cài đặt bật/tắt

    func testDashListSyncTextEditAllSettings() {
        for st in [SystemAutoText.Settings(period: true, capitalize: true, quotes: true, dashes: true),
                   SystemAutoText.Settings()] {
            for predict in [true, false] {
                SystemAutoTextSettings.shared._testSet(st)
                SystemInlinePrediction.setForTesting(predict)
                for id in ["com.apple.TextEdit", "com.apple.Notes"] {
                    let h = harness(id)
                    typeLines(h, "- abc", "- ghi")
                    let want = "- abc\n- ghi"
                    XCTAssertEqual(h.client.string, want, "\(id) settings=\(st.any) predict=\(predict): " + dump(h))
                }
            }
        }
    }

    func testDashListSyncWithoutAppKitCommit() {
        let h = harness()
        h.appKitCommitsAfterNativeKey = false
        typeLines(h, "- abc", "- ghi")
        XCTAssertEqual(h.client.string, "- abc\n- ghi", dump(h))
    }

    func testDashListAfterAcceptedPrediction() {
        let h = harness(text: "- ")
        h.acceptPrediction(typing: "dduw", typed: "đư", word: "được")
        XCTAssertEqual(h.client.string, "- được ", dump(h))
        h.enter()
        h.type("- ghi")
        h.deliverPosted()
        XCTAssertEqual(h.client.string, "- được \n- ghi", dump(h))
    }

    // MARK: Enter bị nuốt + re-post (chốt có ghi lại văn bản) — phím dòng 2 tới trước bản sao

    /// In-place, từ được tự khôi phục ở Enter ("user" gõ Telex = "úe…" → "user"): Enter bị
    /// nuốt + re-post. Đúng chuỗi phím của field report.
    func testDashAfterRewrittenWordAndEnterKeepsOrder() {
        for magic in [false, true] {
            let h = harness()
            typeLinesRacing(h, "- user", "- ghi", magicIntact: magic)
            XCTAssertEqual(h.client.string, "- user\n- ghi", "magic=\(magic): " + dump(h))
        }
    }

    func testPlainWordThenDashLine() {
        let h = harness()
        typeLinesRacing(h, "user", "- ghi")
        XCTAssertEqual(h.client.string, "User\n- ghi", dump(h))
    }

    func testStarListKeepsShiftAndOrder() {
        let h = harness()
        typeLinesRacing(h, "- user", "* ghi")
        XCTAssertEqual(h.client.string, "- user\n* ghi", dump(h))
        XCTAssertTrue(DebugLog.snapshot(header: []).contains("repost-order"), dump(h))
    }

    func testNumberedListKeepsOrder() {
        let h = harness()
        typeLinesRacing(h, "1. user", "2. ghi")
        XCTAssertEqual(h.client.string, "1. User\n2. Ghi", dump(h))
    }

    /// Telex vẫn ghép ở các phím được post lại ("dduwowcj" → "được").
    func testOvertakingLettersStillCompose() {
        let h = harness()
        typeLinesRacing(h, "- user", "- dduwowcj")
        XCTAssertEqual(h.client.string, "- user\n- được", dump(h))
    }

    /// Dấu cách cuối dòng trước Enter (cách user né lỗi): Enter không bị nuốt — không post gì.
    func testSpaceBeforeEnterPostsNothing() {
        let h = harness()
        h.type("- user ")
        h.enter()
        XCTAssertTrue(h.posted.isEmpty, dump(h))
        h.type("- ghi")
        XCTAssertEqual(h.client.string, "- user \n- ghi", dump(h))
    }

    /// App marked (ô web editor marked: hoãn re-post 60ms): mọi phím trong 60ms đó phải
    /// đi SAU Return (trước đây chúng tới app native ngay ⇒ "- abc- " ⏎ "ghi").
    func testMarkedWebEditorDelayedRepostKeepsOrder() {
        FocusedFieldDetector._testSetMarked(true)
        let h = harness("com.apple.Safari")
        guard AppState.shared.usesAxDetect("com.apple.Safari") else { return }
        h.type("- abc")
        h.enter()
        h.type("- ghi")
        XCTAssertTrue(h.posted.isEmpty, "phím vượt phải chờ Return (còn hẹn giờ): " + dump(h))
        let deadline = Date().addingTimeInterval(2)
        while h.posted.isEmpty, Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertEqual(h.posted.map(\.key), [36, 27, 49, 5, 4, 34], "Return trước, rồi đúng thứ tự gõ: " + dump(h))
        h.deliverPosted()
        XCTAssertEqual(h.client.string, "- abc\n- ghi", dump(h))
    }

    // MARK: RepostOrder (pure)

    func testRepostOrderClassification() {
        var o = RepostOrder()
        XCTAssertEqual(o.classify(keyCode: 27, synthetic: false, nowNs: 1), .normal)
        o.expect(.boundary(36), nowNs: 1_000)
        XCTAssertEqual(o.classify(keyCode: 27, synthetic: true, nowNs: 2_000), .normal, "echo burst của mình không bao giờ 'vượt'")
        XCTAssertEqual(o.classify(keyCode: 27, synthetic: false, nowNs: 2_000), .overtook)
        o.expect(.userKey(27), nowNs: 2_000)
        XCTAssertEqual(o.classify(keyCode: 36, synthetic: false, nowNs: 3_000), .boundaryEcho)
        XCTAssertEqual(o.classify(keyCode: 27, synthetic: true, nowNs: 3_000), .userKeyEcho)
        XCTAssertTrue(o.isEmpty)
    }

    /// Bản sao bị hệ thống bỏ: hàng tự xoá — bàn phím không bao giờ kẹt ở chế độ nuốt.
    func testRepostOrderSelfHealsWhenCopyIsLost() {
        var o = RepostOrder()
        o.expect(.boundary(36), nowNs: 0)
        XCTAssertEqual(o.classify(keyCode: 27, synthetic: false, nowNs: RepostOrder.staleNs + 1), .normal)
        XCTAssertTrue(o.isEmpty)
        // Phím vượt liên tục không giữ hàng sống quá hạn của ĐẦU hàng.
        o.expect(.boundary(36), nowNs: 0)
        for (i, k) in [UInt16(27), 49, 5].enumerated() {
            let t = UInt64(i + 1) * 100_000_000
            XCTAssertEqual(o.classify(keyCode: k, synthetic: false, nowNs: t), .overtook)
            o.expect(.userKey(k), nowNs: t)
        }
        XCTAssertEqual(o.classify(keyCode: 4, synthetic: false, nowNs: RepostOrder.staleNs + 1), .normal)
        XCTAssertTrue(o.isEmpty)
    }

    /// Return đầu hàng mất nhưng bản sao phím vượt quay lại (macOS 26: không magic):
    /// nhận ra là echo, KHÔNG post lại lần nữa (không vòng lặp).
    func testRepostOrderEchoOfUserKeyAfterLostBoundary() {
        var o = RepostOrder()
        o.expect(.boundary(36), nowNs: 0)
        o.expect(.userKey(27), nowNs: 1)
        o.expect(.userKey(49), nowNs: 2)
        XCTAssertEqual(o.classify(keyCode: 27, synthetic: false, nowNs: 3), .userKeyEcho)
        XCTAssertEqual(o.classify(keyCode: 49, synthetic: false, nowNs: 4), .userKeyEcho)
        XCTAssertTrue(o.isEmpty)
    }

    /// Đường controller: bản sao Return bị hệ thống bỏ — phím dòng 2 vẫn tới app đúng một lần.
    func testLostBoundaryCopyNeverLoops() {
        let h = harness()
        h.type("- user")
        h.enter()
        h.type("- ghi")
        XCTAssertEqual(h.posted.first?.key, 36, dump(h))
        h.posted.removeFirst()                 // Return copy lost
        h.deliverPosted()
        XCTAssertEqual(h.client.string, "- user- ghi", dump(h))
        XCTAssertTrue(h.posted.isEmpty, dump(h))
    }

    /// Không Trợ năng (không post được): phím vượt đi như thường, không bị nuốt mất.
    func testNoAccessibilityNeverSwallowsOvertakingKey() {
        let h = harness()
        h.type("- user")
        h.enter()
        Accessibility.testTrustOverride = false
        SyntheticKeyboard._testPostKeyHook = nil
        h.posted.removeAll()
        h.type("-")
        XCTAssertTrue(h.client.string.hasSuffix("-"), dump(h))
    }
}
