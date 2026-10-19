// User báo (iOS): phím sát mép trái/phải (q a p l ⇧ ⌫ …) bấm/giữ ăn chậm, phím giữa ăn ngay.
// Nguyên nhân: recognizer cổng cử chỉ hệ thống trên window extension có delaysTouchesBegan =
// true ⇒ gần mép iOS giữ touchesBegan để phân xử vuốt hệ thống. Fix: tắt trì hoãn trên
// recognizer của window + chuỗi superview khi gắn window (KeyboardView.releaseSystemTouchDelays).
import XCTest
import UIKit

final class EdgeTouchDelayTests: XCTestCase {
    private func delayed() -> UIGestureRecognizer {
        let g = UIGestureRecognizer()
        g.delaysTouchesBegan = true
        g.delaysTouchesEnded = true
        return g
    }

    func testPureHelperDisablesDelaysAndKeepsCancel() {
        let a = delayed(), b = delayed()
        let c = UIGestureRecognizer()
        c.delaysTouchesBegan = false; c.delaysTouchesEnded = false
        XCTAssertEqual(KeyboardView.releaseTouchDelays([a, b, c]), 2)
        for g in [a, b, c] {
            XCTAssertFalse(g.delaysTouchesBegan)
            XCTAssertFalse(g.delaysTouchesEnded)
            XCTAssertTrue(g.cancelsTouchesInView, "chỉ bỏ trì hoãn, không đổi cancel")
        }
        XCTAssertEqual(KeyboardView.releaseTouchDelays([a, b, c]), 0, "chạy lại = 0 việc")
    }

    @MainActor func testWindowAndAncestorRecognizersReleased() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 300))
        // Đo 20/10/2026 (simulator iOS 27): UIWindow mới có sẵn 2 _UISystemGestureGateGestureRecognizer
        // (1 cái began=true ended=true) + 1 UITapGestureRecognizer (ended=true).
        let gate = delayed()                 // giả lập _UISystemGestureGateGestureRecognizer
        window.addGestureRecognizer(gate)
        let container = UIView(frame: window.bounds)
        let mid = delayed()
        container.addGestureRecognizer(mid)
        window.addSubview(container)
        let leaf = UIView(frame: container.bounds)
        container.addSubview(leaf)

        // UIWindow thật tự gắn thêm recognizer cổng hệ thống (delaysTouchesBegan = true) ⇒ ≥ 2.
        XCTAssertGreaterThanOrEqual(KeyboardView.releaseSystemTouchDelays(for: leaf), 2)
        XCTAssertFalse(gate.delaysTouchesBegan)
        XCTAssertFalse(mid.delaysTouchesBegan)
        XCTAssertFalse(gate.delaysTouchesEnded)
        for g in window.gestureRecognizers ?? [] {
            XCTAssertFalse(g.delaysTouchesBegan, "\(type(of: g)) vẫn trì hoãn touchesBegan")
        }
    }

    @MainActor func testNoWindowIsNoOp() {
        let v = UIView()
        XCTAssertEqual(KeyboardView.releaseSystemTouchDelays(for: v), 0)
    }
}
