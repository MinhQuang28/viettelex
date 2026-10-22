import XCTest
import UIKit

/// Regression (tester iOS 1.2.x): bàn phím / thanh gợi ý co lại ở Notes, Facebook và
/// lúc ngẫu nhiên — phím lùn/ép; tìm emoji cũng làm phím co. Quy tắc: vùng 4 hàng chữ
/// LUÔN đủ keyArea, dù đổi ô, tìm emoji hay host cấp thiếu chiều cao.
final class KeyboardHeightTests: XCTestCase {
    // MARK: Hàm thuần

    func testKeysModeStripOnTop() {
        let c = KeyLayout.chrome(keyArea: 212, strip: 34, mode: .keys)
        XCTAssertEqual(c, KeyLayout.Chrome(rowsTop: 34, rows: 212, total: 246, minTop: KeyLayout.balloonHeadroom))
    }

    func testEmojiGridTakesStripSameTotal() {
        let k = KeyLayout.chrome(keyArea: 212, strip: 34, mode: .keys)
        let e = KeyLayout.chrome(keyArea: 212, strip: 34, mode: .emoji)
        XCTAssertEqual(e.total, k.total)            // vào emoji không đổi chiều cao (Telegram)
        XCTAssertEqual(e.rowsTop, 0)
    }

    /// Bug: ô tìm chen vào keyArea ⇒ 4 hàng chữ còn 4/5 chiều cao.
    func testEmojiSearchKeepsFullKeyArea() {
        for strip: CGFloat in [0, 14, 34] {
            let c = KeyLayout.chrome(keyArea: 212, strip: strip, mode: .emojiSearch)
            let bar = KeyLayout.emojiSearchBarHeight(strip: strip)
            XCTAssertEqual(c.rows - bar, 212, "strip \(strip)")      // phần của hàng chữ
            XCTAssertEqual(c.total, c.rowsTop + c.rows)
            XCTAssertGreaterThanOrEqual(c.total, 212 + strip)         // không bao giờ thấp hơn plane chữ
        }
    }

    /// Bug: ô từ chối gợi ý (autocorrect .no — ô tìm Facebook, trait Notes chập chờn) làm
    /// strip 34 → 0 → 34 khi đang hiện; host không cấp lại ⇒ hàng phím bị ép.
    func testStripFollowsGlobalSettingNotField() {
        XCTAssertEqual(KeyLayout.stripHeight(reserved: true, open: 34), 34)
        XCTAssertEqual(KeyLayout.stripHeight(reserved: false, open: 34), 0)
    }

    func testPhoneLandscapeByWidth() {
        XCTAssertFalse(KeyLayout.isPhoneLandscape(width: 440, sceneLandscape: true))   // scene lệch: vẫn dọc
        XCTAssertFalse(KeyLayout.isPhoneLandscape(width: 375, sceneLandscape: nil))
        XCTAssertTrue(KeyLayout.isPhoneLandscape(width: 874, sceneLandscape: false))
        XCTAssertTrue(KeyLayout.isPhoneLandscape(width: 0, sceneLandscape: true))
        XCTAssertFalse(KeyLayout.isPhoneLandscape(width: 0, sceneLandscape: nil))
    }

    // MARK: View thật

    private var width: CGFloat { UIDevice.current.userInterfaceIdiom == .pad ? 834 : 390 }

    @MainActor private func makeKeyboard(suggestions: Bool = true) -> (KeyboardView, UIView) {
        let kb = KeyboardView(needsGlobe: false, inputController: nil) { _ in }
        let host = UIView(frame: CGRect(x: 0, y: 0, width: width, height: 400))
        host.addSubview(kb)
        kb.frame = CGRect(x: 0, y: 0, width: width, height: 300)
        kb.configureInputKind(.normal)
        kb.setSuggestionsEnabled(suggestions)
        kb.layoutIfNeeded()
        // Host cấp đúng mức xin.
        kb.frame.size.height = kb.debugRequestedHeight
        kb.setNeedsLayout(); kb.layoutIfNeeded()
        return (kb, host)
    }

    @MainActor private func letterRowHeight(_ kb: KeyboardView) throws -> (q: CGFloat, z: CGFloat) {
        (try XCTUnwrap(kb.debugLetterFrame("q")).height, try XCTUnwrap(kb.debugLetterFrame("z")).height)
    }

    @MainActor func testFieldWithoutSuggestionsKeepsHeight() throws {
        let (kb, host) = makeKeyboard()
        _ = host
        let h0 = kb.debugRequestedHeight
        kb.setSuggestionsEnabled(false, reserveStrip: true)   // sang ô mật khẩu / autocorrect .no
        XCTAssertEqual(kb.debugRequestedHeight, h0)
        kb.setSuggestionsEnabled(true, reserveStrip: true)
        XCTAssertEqual(kb.debugRequestedHeight, h0)
    }

    /// Host cấp THIẾU: strip co trước (tới sàn headroom balloon 20pt), phím giữ nguyên;
    /// thiếu quá sàn thì phím mới co — headroom balloon KHÔNG bao giờ mất (Phil 30/09).
    @MainActor func testHostShortfallSqueezesStripNotKeys() throws {
        let (kb, host) = makeKeyboard()
        _ = host
        let full = try letterRowHeight(kb)
        let spare = kb.debugRowsTop - KeyLayout.balloonHeadroom         // 34 − 20
        kb.frame.size.height = kb.debugRequestedHeight - spare
        kb.setNeedsLayout(); kb.layoutIfNeeded()
        let squeezed = try letterRowHeight(kb)
        XCTAssertEqual(squeezed.q, full.q, accuracy: 0.5)
        XCTAssertEqual(squeezed.z, full.z, accuracy: 0.5)
        XCTAssertGreaterThanOrEqual(try XCTUnwrap(kb.debugRowFrames().first).minY, KeyLayout.balloonHeadroom - 0.5)
        kb.frame.size.height = kb.debugRequestedHeight - 34 - 20
        kb.setNeedsLayout(); kb.layoutIfNeeded()
        XCTAssertGreaterThanOrEqual(try XCTUnwrap(kb.debugRowFrames().first).minY, KeyLayout.balloonHeadroom - 0.5,
                                    "headroom balloon giữ cả khi host thiếu nhiều")
    }

    /// Tìm emoji: 4 hàng chữ cao y như plane chữ.
    @MainActor func testEmojiSearchDoesNotCompressKeys() throws {
        for suggestions in [true, false] {
            let (kb, host) = makeKeyboard(suggestions: suggestions)
            _ = host
            let full = try letterRowHeight(kb)
            let h0 = kb.debugRequestedHeight
            kb.debugEnterEmojiSearch()
            XCTAssertTrue(kb.debugInEmojiSearch)
            XCTAssertGreaterThanOrEqual(kb.debugRequestedHeight, h0)
            kb.frame.size.height = kb.debugRequestedHeight
            kb.setNeedsLayout(); kb.layoutIfNeeded()
            let s = try letterRowHeight(kb)
            XCTAssertEqual(s.q, full.q, accuracy: 0.5, "suggestions \(suggestions)")
            XCTAssertEqual(s.z, full.z, accuracy: 0.5, "suggestions \(suggestions)")
        }
    }
}

/// Regression (Phil 05/10/2026, iPhone thật iOS 27, WhatsApp, theme đen): dải kính xám bo góc
/// lộ phía trên nền mình — container hệ thống cao hơn view. HostFill xin thêm ĐÚNG phần đã cấp,
/// có xác nhận một lượt, không bao giờ thành vòng lặp tăng dần.
final class HostFillTests: XCTestCase {
    private let base: CGFloat = 252, width: CGFloat = 402

    func testAllocatedOnlyWhenBottomAnchored() {
        let container = CGRect(x: 0, y: 0, width: 402, height: 270)
        XCTAssertEqual(HostFill.allocated(viewFrame: CGRect(x: 0, y: 18, width: 402, height: 252),
                                          container: container), 270)
        XCTAssertNil(HostFill.allocated(viewFrame: CGRect(x: 0, y: 0, width: 402, height: 252),
                                        container: container), "neo đỉnh: không phải dải phía trên")
        XCTAssertNil(HostFill.allocated(viewFrame: .zero, container: container))
    }

    func testNoBandDoesNothing() {
        var f = HostFill()
        XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: base, width: width, animating: false), .none)
        XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: nil, width: width, animating: false), .none)
        // Host cấp DƯ cho chính view (container = view): không xin thêm (phím đã hấp thụ).
        XCTAssertEqual(f.observe(base: base, viewHeight: base + 25, allocated: base + 25, width: width, animating: false), .none)
        XCTAssertEqual(f.extra, 0)
    }

    func testBandConfirmedNextPassThenMatchesExactly() {
        var f = HostFill()
        XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: base + 18, width: width, animating: false), .wait)
        XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: base + 18, width: width, animating: false), .apply(18))
        // Hệ thống chưa kịp cấp lại view (vẫn hở, nhưng ≤ mức đã xin): chờ, không khoá.
        XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: base + 18, width: width, animating: false), .none)
        XCTAssertFalse(f.locked)
        // Cấp xong: view = container → ổn định, giữ nguyên.
        XCTAssertEqual(f.observe(base: base, viewHeight: base + 18, allocated: base + 18, width: width, animating: false), .none)
        XCTAssertEqual(f.extra, 18)
    }

    func testTransientSizeNotConfirmedIsIgnored() {
        var f = HostFill()
        XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: base + 30, width: width, animating: false), .wait)
        // Lượt sau khung đã khác (đang settle) ⇒ chờ tiếp, không áp số cũ.
        XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: base + 12, width: width, animating: false), .wait)
        XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: base, width: width, animating: false), .none)
        XCTAssertEqual(f.extra, 0)
    }

    func testAnimationOnlyWaits() {
        var f = HostFill()
        for _ in 0..<3 {
            XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: base + 18, width: width, animating: true), .wait)
        }
        XCTAssertEqual(f.extra, 0)
    }

    func testHugeOrNegativeAllocationIgnored() {
        var f = HostFill()
        for _ in 0..<3 {
            XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: base + HostFill.maxExtra + 40,
                                     width: width, animating: false), .none)
        }
        XCTAssertEqual(f.extra, 0)
    }

    /// Vòng lặp: container luôn = mức xin + 18 ⇒ tối đa MỘT lần xin thêm rồi trả về 0 và khoá.
    func testFeedbackLoopLocksAfterOneGrow() {
        var f = HostFill()
        var requested = base
        var applies = 0
        for _ in 0..<20 {
            let allocated = requested + 18          // hệ thống "chạy theo" mức xin
            switch f.observe(base: base, viewHeight: requested, allocated: allocated, width: width, animating: false) {
            case .apply(let x): applies += 1; requested = base + x
            case .wait, .none: break
            }
        }
        XCTAssertTrue(f.locked)
        XCTAssertEqual(f.extra, 0)
        XCTAssertEqual(requested, base, "trả về mức xin gốc")
        XCTAssertLessThanOrEqual(applies, 2)
        XCTAssertLessThanOrEqual(requested, base + HostFill.maxExtra)
    }

    func testNeverRequestsBeyondAllocated() {
        for gap: CGFloat in [1, 5, 18, 30, HostFill.maxExtra] {
            var f = HostFill()
            _ = f.observe(base: base, viewHeight: base, allocated: base + gap, width: width, animating: false)
            let d = f.observe(base: base, viewHeight: base, allocated: base + gap, width: width, animating: false)
            XCTAssertEqual(d, .apply(gap), "gap \(gap)")
            XCTAssertLessThanOrEqual(base + f.extra, base + gap)
        }
    }

    func testRotationAndResetDropExtra() {
        var f = HostFill()
        _ = f.observe(base: base, viewHeight: base, allocated: base + 18, width: width, animating: false)
        XCTAssertEqual(f.observe(base: base, viewHeight: base, allocated: base + 18, width: width, animating: false), .apply(18))
        XCTAssertEqual(f.observe(base: 172, viewHeight: 172, allocated: 172, width: 874, animating: false), .apply(0))
        XCTAssertEqual(f.extra, 0)
        f.reset()
        XCTAssertFalse(f.locked)
    }

    /// Phần xin thêm cộng vào mức xin; strip / headroom balloon giữ nguyên, phím hấp thụ.
    @MainActor func testExtraRaisesRequestKeepsStrip() throws {
        let kb = KeyboardView(needsGlobe: false, inputController: nil) { _ in }
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 400))
        host.addSubview(kb)
        kb.frame = CGRect(x: 0, y: 0, width: 390, height: 300)
        kb.configureInputKind(.normal)
        kb.setSuggestionsEnabled(true)
        kb.layoutIfNeeded()
        let h0 = kb.debugRequestedHeight, top0 = kb.debugRowsTop
        XCTAssertEqual(kb.baseRequestedHeight, h0)
        kb.hostFillExtra = 18
        XCTAssertEqual(kb.debugRequestedHeight, h0 + 18)
        XCTAssertEqual(kb.baseRequestedHeight, h0)
        XCTAssertEqual(kb.debugRowsTop, top0)
        kb.frame.size.height = kb.debugRequestedHeight
        kb.setNeedsLayout(); kb.layoutIfNeeded()
        let rows = try XCTUnwrap(kb.debugRowFrames().first)
        XCTAssertEqual(rows.minY, top0, accuracy: 0.5, "strip không dời")
        kb.hostFillExtra = 0
        XCTAssertEqual(kb.debugRequestedHeight, h0)
        withExtendedLifetime(host) {}
    }
}

/// Regression (Phil 30/09/2026, máy thật sau 1.2.3): giữ "e" ra "3" — balloon bị cắt nửa ở
/// mép trên bàn phím khi KHÔNG có thanh gợi ý phía trên (tắt gợi ý / thu gọn / host cấp
/// thiếu làm strip nhường hết). Extension không vẽ ra ngoài inputView ⇒ balloon hàng đầu
/// phải nằm TRỌN trong view, chữ không bị cắt.
final class BalloonHeadroomTests: XCTestCase {
    private var width: CGFloat { UIDevice.current.userInterfaceIdiom == .pad ? 834 : 390 }

    @MainActor private func assertBalloonFits(_ kb: KeyboardView, _ what: String) throws {
        kb.setNeedsLayout(); kb.layoutIfNeeded()
        for (key, text) in [("e", "3"), ("q", "1"), ("p", "0"), ("e", "E")] {
            let b = try XCTUnwrap(kb.debugBalloon(letter: key, text: text), what)
            XCTAssertGreaterThanOrEqual(b.frame.minY, -0.01, "\(what) \(key): balloon vượt mép trên")
            XCTAssertLessThanOrEqual(b.frame.maxY, kb.bounds.height + 0.01, what)
            XCTAssertGreaterThanOrEqual(b.label.minY, -0.01, "\(what) \(key): chữ bị cắt")
            XCTAssertLessThanOrEqual(b.fontSize, b.label.height, "\(what) \(key): chữ cao hơn bubble")
        }
    }

    @MainActor private func make(suggestions: Bool, reserve: Bool? = nil,
                                 shortfall: CGFloat = 0) -> (KeyboardView, UIView) {
        let kb = KeyboardView(needsGlobe: false, inputController: nil) { _ in }
        let host = UIView(frame: CGRect(x: 0, y: 0, width: width, height: 400))
        host.overrideUserInterfaceStyle = .light
        host.addSubview(kb)
        kb.frame = CGRect(x: 0, y: 0, width: width, height: 300)
        kb.applyAppearance(.light, style: .light)
        kb.configureInputKind(.normal)
        kb.setSuggestionsEnabled(suggestions, reserveStrip: reserve)
        kb.layoutIfNeeded()
        kb.frame.size.height = kb.debugRequestedHeight - shortfall
        return (kb, host)
    }

    @MainActor func testBalloonInsideBoundsAllConfigs() throws {
        let configs: [(String, Bool, Bool?, CGFloat)] = [
            ("gợi ý bật", true, nil, 0),
            ("gợi ý tắt", false, nil, 0),
            ("ô từ chối gợi ý", false, true, 0),
            ("host thiếu 34pt", true, nil, 34),
            ("host thiếu 60pt", true, nil, 60),
            ("gợi ý tắt + host thiếu", false, nil, 20),
        ]
        for (what, sug, reserve, short) in configs {
            let (kb, host) = make(suggestions: sug, reserve: reserve, shortfall: short)
            try assertBalloonFits(kb, what)
            withExtendedLifetime(host) {}
        }
        let (kb, host) = make(suggestions: true)
        kb.debugEnterEmojiSearch()
        kb.frame.size.height = kb.debugRequestedHeight
        try assertBalloonFits(kb, "tìm emoji")
        withExtendedLifetime(host) {}
    }

    /// Mọi strip (tắt 0 / thu gọn 14 / mở 34) đều chừa headroom balloon phía trên phím.
    func testHeadroomAlwaysReserved() {
        for strip: CGFloat in [0, 14, 34] {
            let c = KeyLayout.chrome(keyArea: 212, strip: strip, mode: .keys)
            XCTAssertGreaterThanOrEqual(c.rowsTop, KeyLayout.balloonHeadroom)
            XCTAssertGreaterThanOrEqual(c.minTop, KeyLayout.balloonHeadroom)
        }
    }
}
