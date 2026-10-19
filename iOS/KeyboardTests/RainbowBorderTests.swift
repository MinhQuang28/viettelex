// Viền cầu vồng (theme Gaming RGB): dải màu + đồng hồ chung Android (số tham chiếu), MỘT lớp gradient che bằng
// viền phím (không animation từng phím), chỉ chạy khi đang gõ — nghỉ thì 0 animation; theme khác ⇒ không có view.
// Bộ chậm (VT_SLOW_TESTS=1): đo chi phí main thread mỗi phím + khi nghỉ, in "RGB-COST".
import XCTest
import UIKit

final class RainbowBorderTests: XCTestCase {
    private var savedPaywall = false
    private var savedProvider: UserDefaults?
    private let suite = "vt-rgb-tests"

    override func setUp() {
        super.setUp()
        savedPaywall = PlusGate.paywallEnabled
        PlusGate.paywallEnabled = false
        savedProvider = UserDefaultsProvider.shared
    }

    override func tearDown() {
        PlusGate.paywallEnabled = savedPaywall
        UserDefaultsProvider.shared = savedProvider
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    // MARK: thuần — chung Android (RainbowBorderTests.kt)

    func testStopsAndOffsetParity() {
        XCTAssertEqual(RainbowBorder.hues, [0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x00C7BE, 0x007AFF, 0xAF52DE, 0xFF3B30])
        let st = RainbowBorder.stops()
        XCTAssertEqual(st.count, 15)
        XCTAssertEqual(st.first?.location, 0); XCTAssertEqual(st.last?.location, 1)
        XCTAssertEqual(st[7].color, 0xFF3B30); XCTAssertEqual(st[7].location, 0.5, accuracy: 1e-12, "chu kỳ hai bắt đầu giữa dải")
        XCTAssertEqual(st[3].location, 3.0 / 14, accuracy: 1e-12)
        for i in 1..<st.count { XCTAssertGreaterThan(st[i].location, st[i - 1].location) }
        // Nửa sau = nửa đầu dời 0.5 ⇒ dịch đúng một bề ngang (nửa dải) là liền mạch.
        for i in 0..<7 { XCTAssertEqual(st[i].color, st[i + 7].color); XCTAssertEqual(st[i].location + 0.5, st[i + 7].location, accuracy: 1e-12) }
        XCTAssertEqual(RainbowBorder.offset(from: 0.25, elapsed: 1), 0.5, accuracy: 1e-12)
        XCTAssertEqual(RainbowBorder.offset(from: 0.9, elapsed: 0.8), 0.1, accuracy: 1e-12)
        XCTAssertEqual(RainbowBorder.offset(from: 0, elapsed: RainbowBorder.period * 3), 0, accuracy: 1e-12)
        XCTAssertEqual(RainbowBorder.period, 4); XCTAssertEqual(RainbowBorder.idleAfter, 1.2); XCTAssertEqual(RainbowBorder.lineWidth, 1.5)
    }

    func testClockRunsOnlyWhileTyping() {
        var c = RainbowClock()
        XCTAssertFalse(c.running)
        XCTAssertTrue(c.poke(at: 10), "phím đầu ⇒ chạy")
        XCTAssertFalse(c.poke(at: 10.3), "đang chạy ⇒ chỉ ghi giờ")
        XCTAssertEqual(c.idleRemaining(at: 11), 0.5, accuracy: 1e-9)
        XCTAssertLessThanOrEqual(c.idleRemaining(at: 11.6), 0)
        c.stop()
        XCTAssertFalse(c.running)
        XCTAssertTrue(c.poke(at: 20))
    }

    // MARK: bàn phím thật

    @MainActor private func keyboard(_ theme: KeyboardTheme, in win: UIWindow? = nil) -> KeyboardView {
        let d = UserDefaults(suiteName: suite)!
        d.removePersistentDomain(forName: suite)
        d.set(theme.rawValue, forKey: ThemeSettings.themeKey)
        UserDefaultsProvider.shared = d
        let kb = KeyboardView(needsGlobe: false, inputController: nil) { _ in }
        let host: UIView = win ?? UIView(frame: CGRect(x: 0, y: 0, width: 402, height: 400))
        host.addSubview(kb)
        kb.frame = CGRect(x: 0, y: 0, width: 402, height: 290)
        kb.applyAppearance(.dark, style: .dark)
        kb.setNeedsLayout(); kb.layoutIfNeeded()
        return kb
    }

    @MainActor func testOnlyRgbThemeHasOverlayAndItIsOneMaskedLayer() throws {
        for t in KeyboardTheme.allCases where t != .rgb {
            XCTAssertNil(keyboard(t).rainbow, "\(t): theme khác ⇒ không có lớp (0 chi phí)")
        }
        let kb = keyboard(.rgb)
        let v = try XCTUnwrap(kb.rainbow)
        XCTAssertFalse(v.isUserInteractionEnabled)
        XCTAssertNil(v.hitTest(CGPoint(x: 50, y: 150), with: nil), "không nhận chạm")
        XCTAssertEqual(v.layer.sublayers?.count, 1, "MỘT lớp gradient")
        XCTAssertEqual(v.debugStrip.colors?.count, 15)
        // Mặt nạ = viền mọi phím đang hiện: bao trùm khung phím chữ q…p.
        let path = try XCTUnwrap(v.debugMaskPath)
        let q = try XCTUnwrap(kb.debugLetterFrame("q")), p = try XCTUnwrap(kb.debugLetterFrame("p"))
        let box = path.boundingBoxOfPath
        XCTAssertLessThanOrEqual(box.minX, kb.convert(q, to: v).minX + 1)
        XCTAssertGreaterThanOrEqual(box.maxX, kb.convert(p, to: v).maxX - 1)
        XCTAssertEqual(v.debugAnimationCount, 0, "mới hiện: cầu vồng tĩnh, không animation")
        XCTAssertFalse(v.debugRunning)
    }

    /// Gõ ⇒ MỘT animation trên lớp gradient (không phải từng phím); nghỉ ⇒ gỡ sạch, đứng yên đúng vị trí đang hiện.
    @MainActor func testAnimatesWhileTypingThenFreezes() throws {
        let win = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 400))
        win.isHidden = false
        defer { win.isHidden = true }
        let kb = keyboard(.rgb, in: win)
        let v = try XCTUnwrap(kb.rainbow)
        v.debugSetIdle(0.25)
        let f = try XCTUnwrap(kb.debugLetterFrame("a"))
        kb.benchTap(at: CGPoint(x: f.midX, y: f.midY), time: CACurrentMediaTime())
        XCTAssertTrue(v.debugRunning)
        XCTAssertEqual(v.debugAnimationCount, 1)
        for _ in 0..<5 { kb.benchTap(at: CGPoint(x: f.midX, y: f.midY), time: CACurrentMediaTime()) }
        XCTAssertEqual(v.debugAnimationCount, 1, "gõ tiếp không thêm animation")
        KeyboardBenchTests.spin(0.15)
        XCTAssertTrue(v.debugRunning, "chưa đủ thời gian nghỉ")
        KeyboardBenchTests.spin(0.5)
        XCTAssertFalse(v.debugRunning)
        XCTAssertEqual(v.debugAnimationCount, 0, "nghỉ ⇒ 0 animation")
        XCTAssertGreaterThan(v.phase, 0, "đứng yên ở vị trí đã trượt tới (không giật về đầu)")
        // Ẩn bàn phím giữa lúc gõ ⇒ dừng ngay.
        kb.benchTap(at: CGPoint(x: f.midX, y: f.midY), time: CACurrentMediaTime())
        XCTAssertEqual(v.debugAnimationCount, 1)
        kb.removeFromSuperview()
        XCTAssertEqual(v.debugAnimationCount, 0)
    }

    /// Bộ chậm: chi phí main thread mỗi phím (poke) + CPU khi nghỉ / khi đang chạy (render server lo animation).
    @MainActor func testCostPerKeyAndIdle() throws {
        try SlowTests.require()
        let win = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 400))
        win.isHidden = false
        defer { win.isHidden = true }
        func cpu(_ sec: Double) -> Double {
            let c0 = KeyboardBenchTests.mainThreadCPU()
            KeyboardBenchTests.spin(sec)
            return (KeyboardBenchTests.mainThreadCPU() - c0) * 1000
        }
        let kb = keyboard(.rgb, in: win)
        let v = try XCTUnwrap(kb.rainbow)
        let keys = Array("xinchaomoinguoihomnaytoiddilamvieccongty")
        var pokes: [Double] = []
        for _ in 0..<5 {
            for c in keys {
                guard let f = kb.debugLetterFrame(String(c)) else { continue }
                RainbowBorderView.debugCost = 0
                kb.benchTap(at: CGPoint(x: f.midX, y: f.midY), time: CACurrentMediaTime())
                pokes.append(RainbowBorderView.debugCost * 1000)
                KeyboardBenchTests.spin(0.01)
            }
        }
        let running = cpu(1.0) / 1.0                           // animation đang chạy (chưa nghỉ)
        KeyboardBenchTests.spin(RainbowBorder.idleAfter + 0.3)
        XCTAssertEqual(v.debugAnimationCount, 0)
        let idle = cpu(1.0)
        kb.removeFromSuperview()
        let base = cpu(1.0)
        let p = KeyboardBenchTests.pct
        let line = String(format: "RGB-COST poke p50=%.4f p95=%.4f p99=%.4f ms/phím | main CPU khi đang chạy=%.3f ms/s | nghỉ=%.3f ms/s | không bàn phím=%.3f ms/s",
                          p(pokes, 0.5), p(pokes, 0.95), p(pokes, 0.99), running, idle, base)
        print(line)
        XCTContext.runActivity(named: line) { _ in }
        XCTAssertLessThan(p(pokes, 0.95), 0.3, "≤ 0,3 ms/phím")
        XCTAssertLessThan(idle - base, 0.5, "nghỉ = 0 việc")
    }
}
