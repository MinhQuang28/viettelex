// "Hiệu ứng khi bấm phím" (Plus, 19/10/2026): model cài đặt, cổng Plus, điều kiện tự tắt, trần hạt,
// overlay không nhận chạm / không rò, và A/B chi phí main thread mỗi phím (bộ chậm).
// Ràng buộc từ Phil: chèn chữ TRƯỚC rồi mới vẽ; tắt = 0 chi phí (không tạo overlay); ≤ ~20 hạt;
// tăng > ~0,3 ms/phím trên main thread thì không phát hành.
import XCTest
import UIKit

final class KeyEffectTests: XCTestCase {
    private var savedDefaults: UserDefaults!
    private var savedPaywall = true
    private let plusSuite = "vt-keyeffect-plus"
    private let suite = "vt-keyeffect"

    override func setUp() {
        super.setUp()
        savedDefaults = PlusGate.defaults
        savedPaywall = PlusGate.paywallEnabled
        UserDefaults(suiteName: plusSuite)!.removePersistentDomain(forName: plusSuite)
        PlusGate.defaults = UserDefaults(suiteName: plusSuite)!
        #if DEBUG
        KeyEffectOverlay.drawsOffscreen = true
        #endif
    }

    override func tearDown() {
        PlusGate.defaults = savedDefaults
        PlusGate.paywallEnabled = savedPaywall
        #if DEBUG
        KeyEffectOverlay.drawsOffscreen = false
        #endif
        super.tearDown()
    }

    // MARK: model

    func testIdsMatchAndroidAndDefaultIsNone() {
        // Cùng id với android/keyboard/.../KeyEffect.kt (sao lưu chéo nền tảng).
        XCTAssertEqual(KeyEffect.allCases.map(\.rawValue), ["none", "heart", "bubble", "ripple", "petal", "firework"])
        XCTAssertEqual(KeyEffect.key, "keyEffect")
        XCTAssertEqual(KeyEffect.defaultValue, .none)
        XCTAssertEqual(KeyEffect.parse(nil), .none)
        XCTAssertEqual(KeyEffect.parse("sparkle"), .none, "id lạ ⇒ Không")
        XCTAssertEqual(KeyEffect.parse(3), .none)
        XCTAssertEqual(KeyEffect.parse("heart"), .heart)
        let d = UserDefaults(suiteName: suite)!
        d.removePersistentDomain(forName: suite)
        XCTAssertEqual(KeyEffect.load(d), .none)
        d.set("firework", forKey: KeyEffect.key)
        XCTAssertEqual(KeyEffect.load(d), .firework)
        d.removePersistentDomain(forName: suite)
    }

    func testOnlyNoneIsFree() {
        XCTAssertFalse(KeyEffect.none.isPlus)
        for e in KeyEffect.allCases where e != .none { XCTAssertTrue(e.isPlus, e.rawValue) }
    }

    func testBackupSpecWhitelistsIds() {
        let spec = BackupSettings.all.first { $0.key == KeyEffect.key }
        XCTAssertEqual(spec?.kind, .string("none", ["none", "heart", "bubble", "ripple", "petal", "firework"]))
    }

    func testTitlesBilingual() {
        for e in KeyEffect.allCases {
            XCTAssertNotNil(L10n.en[e.title], "thiếu bản dịch: \(e.title)")
        }
        XCTAssertNotNil(L10n.en["Hiệu ứng khi bấm phím"])
    }

    // MARK: cổng Plus

    func testPlusGate() {
        PlusGate.paywallEnabled = true
        PlusGate.defaults.set(false, forKey: PlusConfig.unlockedKey)
        PlusGate.defaults.set(false, forKey: PlusConfig.debugOverrideKey)
        XCTAssertFalse(KeyEffectGate.allows)
        XCTAssertTrue(KeyEffectGate.allows(.none))
        XCTAssertFalse(KeyEffectGate.allows(.heart))
        PlusGate.defaults.set(true, forKey: PlusConfig.unlockedKey)
        XCTAssertTrue(KeyEffectGate.allows(.heart))
        PlusGate.paywallEnabled = false
        PlusGate.defaults.set(false, forKey: PlusConfig.unlockedKey)
        XCTAssertTrue(KeyEffectGate.allows(.firework), "chưa bật thanh toán ⇒ mở hết")
    }

    /// Đã chọn Trái tim rồi hết Plus (hoàn tiền / máy mới) ⇒ bàn phím không vẽ; giá trị lưu giữ nguyên.
    func testLapsedPlusDisablesButKeepsSetting() {
        PlusGate.paywallEnabled = true
        PlusGate.defaults.set(false, forKey: PlusConfig.unlockedKey)
        let d = UserDefaults(suiteName: suite)!
        d.removePersistentDomain(forName: suite)
        d.set("heart", forKey: KeyEffect.key)
        XCTAssertEqual(KeyEffectPolicy.current(d), .none)
        XCTAssertEqual(KeyEffect.load(d), .heart)
        PlusGate.defaults.set(true, forKey: PlusConfig.unlockedKey)
        XCTAssertEqual(KeyEffectPolicy.current(d), .heart)
        d.removePersistentDomain(forName: suite)
    }

    // MARK: tự tắt

    func testAutoDisableConditions() {
        XCTAssertEqual(KeyEffectPolicy.active(setting: .heart, plus: true, lowPower: false, reduceMotion: false), .heart)
        XCTAssertEqual(KeyEffectPolicy.active(setting: .heart, plus: false, lowPower: false, reduceMotion: false), .none)
        XCTAssertEqual(KeyEffectPolicy.active(setting: .heart, plus: true, lowPower: true, reduceMotion: false), .none,
                       "Chế độ nguồn điện thấp ⇒ tắt")
        XCTAssertEqual(KeyEffectPolicy.active(setting: .ripple, plus: true, lowPower: false, reduceMotion: true), .none,
                       "Giảm chuyển động ⇒ tắt")
        XCTAssertEqual(KeyEffectPolicy.active(setting: .none, plus: true, lowPower: false, reduceMotion: false), .none)
    }

    // MARK: hạt / quỹ đạo

    func testPoolCapsAndRecyclesOldest() {
        XCTAssertLessThanOrEqual(KeyEffectPool.cap, 20)
        var p = KeyEffectPool()
        var seen = Set<Int>()
        for _ in 0..<200 { let i = p.next(); XCTAssertTrue((0..<KeyEffectPool.cap).contains(i)); seen.insert(i) }
        XCTAssertEqual(seen.count, KeyEffectPool.cap)
        var q = KeyEffectPool()
        let first = q.next()
        for _ in 1..<KeyEffectPool.cap { _ = q.next() }
        XCTAssertEqual(q.next(), first, "đầy ⇒ thay ô cũ nhất")
        XCTAssertEqual(KeyEffectPool(capacity: 0).capacity, 1)
    }

    func testParticlesPerTapFitInPool() {
        for e in KeyEffect.allCases {
            XCTAssertLessThanOrEqual(e.particlesPerTap, KeyEffectPool.cap / 2, e.rawValue)
            XCTAssertEqual(e.variantCount, e.particlesPerTap * 2)
            XCTAssertLessThanOrEqual(e.burstSlots * e.particlesPerTap, KeyEffectPool.cap, e.rawValue)
            if e != .none { XCTAssertGreaterThanOrEqual(e.burstSlots, 3, "gõ nhanh vẫn thấy vài lần bấm gần nhất") }
        }
        XCTAssertEqual(KeyEffect.none.particlesPerTap, 0)
    }

    /// Hạt của MỘT lần bấm dùng quỹ đạo khác nhau (tim trái/giữa/phải, pháo hoa toả đều); mọi quỹ đạo
    /// gọn (không bay quá ~72pt, ≤ 1 s) — Android dùng y hệt số này.
    func testTrajectoriesDistinctAndBounded() {
        for e in KeyEffect.allCases where e != .none {
            for seed in 0..<5 {
                let vs = (0..<e.particlesPerTap).map { e.variant(particle: $0, seed: seed) }
                XCTAssertEqual(Set(vs).count, vs.count, "\(e.rawValue) seed \(seed)")
                for v in vs { XCTAssertTrue((0..<e.variantCount).contains(v)) }
            }
            for v in 0..<e.variantCount {
                let t = e.trajectory(v)
                XCTAssertLessThanOrEqual(hypot(t.dx, t.dy), 72, "\(e.rawValue) #\(v)")
                XCTAssertGreaterThan(t.duration, 0.2)
                XCTAssertLessThanOrEqual(t.duration, 1.0)
                XCTAssertGreaterThan(t.scaleTo, 0)
            }
        }
        XCTAssertLessThan(KeyEffect.heart.trajectory(0).dy, 0, "tim bay lên")
        XCTAssertLessThan(KeyEffect.heart.trajectory(0).dx, 0)
        XCTAssertGreaterThan(KeyEffect.heart.trajectory(5).dx, 0)
        XCTAssertEqual(KeyEffect.firework.trajectory(0).dx, 30, accuracy: 1e-9)
        XCTAssertEqual(KeyEffect.firework.trajectory(3).dy, 36, accuracy: 1e-9)
    }

    func testShapesAreVectorPaths() {
        for e in KeyEffect.allCases {
            let p = KeyEffectShapes.path(e)
            if e == .none { XCTAssertNil(p) } else {
                XCTAssertNotNil(p, e.rawValue)
                XCTAssertFalse(p!.boundingBoxOfPath.isEmpty, e.rawValue)
                XCTAssertLessThanOrEqual(p!.boundingBoxOfPath.width, 44)
            }
        }
    }

    // MARK: overlay

    @MainActor func testInstallNoneCreatesNothing() {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 300, height: 200))
        XCTAssertNil(KeyEffectOverlay.install(.none, color: .red, in: host, existing: nil))
        XCTAssertTrue(host.subviews.isEmpty)
        let o = KeyEffectOverlay.install(.heart, color: .red, in: host, existing: nil)
        XCTAssertNotNil(o)
        XCTAssertTrue(o?.superview === host)
        XCTAssertNil(KeyEffectOverlay.install(.none, color: .red, in: host, existing: o), "đổi sang Không ⇒ gỡ")
        XCTAssertTrue(host.subviews.isEmpty)
    }

    @MainActor func testOverlayNeverTakesTouchesAndSitsBelowBalloon() {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 300, height: 200))
        let o = KeyEffectOverlay.install(.bubble, color: .blue, in: host, existing: nil)!
        XCTAssertFalse(o.isUserInteractionEnabled)
        XCTAssertNil(o.hitTest(CGPoint(x: 10, y: 10), with: nil))
        XCTAssertFalse(o.point(inside: CGPoint(x: 10, y: 10), with: nil))
        XCTAssertGreaterThan(o.layer.zPosition, 0, "trên phím")
        XCTAssertLessThan(o.layer.zPosition, 10, "dưới ô phóng to (BalloonView zPosition 10)")
        XCTAssertEqual(o.frame, host.bounds)
        // Một nút phía dưới vẫn nhận chạm xuyên qua overlay.
        let b = UIButton(frame: CGRect(x: 0, y: 0, width: 50, height: 50))
        host.insertSubview(b, belowSubview: o)
        XCTAssertTrue(host.hitTest(CGPoint(x: 10, y: 10), with: nil) === b)
    }

    /// emit chỉ ghi điểm + hẹn MỘT lượt runloop (vẽ sau khi chữ đã chèn) — không vẽ đồng bộ.
    @MainActor func testEmitDefersToNextRunloop() {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 300, height: 200))
        let o = KeyEffectOverlay.install(.heart, color: .red, in: host, existing: nil)!
        o.emit(at: CGPoint(x: 50, y: 100))
        o.emit(at: CGPoint(x: 60, y: 100))
        XCTAssertEqual(o.debugBurstCount, 0, "chưa vẽ trong lượt hiện tại")
        XCTAssertTrue(o.debugScheduled)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertFalse(o.debugScheduled)
        XCTAssertEqual(o.debugBurstCount, 2)
        XCTAssertEqual(o.debugParticleCount, 2 * KeyEffect.heart.particlesPerTap)
        XCTAssertEqual(o.debugPendingCount, 0)
    }

    /// Bắn lại một chùm chỉ bằng beginTime: animation chạy lại từ đầu (đọc presentation layer — CA tính
    /// trong process y như render server), xong thì vô hình (opacity 0), không gỡ/không thêm animation.
    @MainActor func testBurstReplaysByBeginTimeOnly() throws {
        let host = UIWindow(frame: CGRect(x: 0, y: 0, width: 300, height: 200))
        host.isHidden = false
        defer { host.isHidden = true }
        let o = KeyEffectOverlay.install(.heart, color: .red, in: host, existing: nil)!
        o.emit(at: CGPoint(x: 100, y: 150)); o.flush()
        let burst = try XCTUnwrap(o.layer.sublayers?.first)
        let particle = try XCTUnwrap(burst.sublayers?.first)
        let anim = particle.animation(forKey: "fx")
        XCTAssertNotNil(anim)
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        let mid = try XCTUnwrap(particle.presentation())
        XCTAssertGreaterThan(mid.opacity, 0.9, "đang bay")
        XCTAssertLessThan(mid.position.y, -5, "tim đã bay lên")
        RunLoop.main.run(until: Date().addingTimeInterval(1.0))
        XCTAssertEqual(particle.presentation()?.opacity ?? 1, 0, accuracy: 0.01, "bay xong: vô hình")
        // Bắn lại đúng chùm này (vòng hết các chùm khác).
        for _ in 0..<KeyEffect.heart.burstSlots { o.emit(at: CGPoint(x: 40, y: 150)); o.flush() }
        XCTAssertEqual(o.debugBurstCount, KeyEffect.heart.burstSlots, "không dựng thêm chùm")
        XCTAssertNotNil(particle.animation(forKey: "fx"), "animation gắn một lần, không bị gỡ")
        RunLoop.main.run(until: Date().addingTimeInterval(0.1))
        let again = try XCTUnwrap(particle.presentation())
        XCTAssertGreaterThan(again.opacity, 0.9, "chạy lại từ đầu")
        XCTAssertEqual(burst.position, CGPoint(x: 40, y: 150))
    }

    /// 200 phím dồn dập: pool không vượt trần, không dồn điểm chờ, overlay giải phóng khi gỡ (không rò).
    @MainActor func testBurstOf200KeysStaysCappedAndDoesNotLeak() {
        weak var weakOverlay: KeyEffectOverlay?
        let footprint0 = KeyboardBenchTests.footprintMB()
        autoreleasepool {
            let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 260))
            for e in KeyEffect.allCases where e != .none {
                let o = KeyEffectOverlay.install(e, color: .systemPink, in: host, existing: nil)!
                for i in 0..<200 {
                    o.emit(at: CGPoint(x: CGFloat(i % 10) * 39 + 19, y: CGFloat(i % 3) * 50 + 80))
                    if i % 3 == 0 { o.flush() }   // gõ chồng: vài phím chung một lượt
                }
                o.flush()
                XCTAssertLessThanOrEqual(o.debugParticleCount, KeyEffectPool.cap, e.rawValue)
                XCTAssertEqual(o.debugBurstCount, e.burstSlots, e.rawValue)
                XCTAssertEqual(o.layer.sublayers?.count ?? 0, e.burstSlots, "chùm dùng lại, không mọc thêm layer")
                XCTAssertEqual(o.debugPendingCount, 0)
                for i in 0..<10 { o.emit(at: CGPoint(x: CGFloat(i), y: 1)) }
                XCTAssertLessThanOrEqual(o.debugPendingCount, 4, "gõ dồn trong một lượt: chỉ giữ vài điểm mới nhất")
                o.flush()
                weakOverlay = o
                XCTAssertNil(KeyEffectOverlay.install(.none, color: .red, in: host, existing: o))
            }
        }
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))   // block hẹn giờ (weak self) chạy xong
        XCTAssertNil(weakOverlay, "overlay gỡ ra phải được giải phóng")
        let grew = KeyboardBenchTests.footprintMB() - footprint0
        XCTAssertLessThan(grew, 8, "RAM sau 5 × 200 phím (MB)")
    }

    // MARK: KeyboardView

    @MainActor private func withKeyboard(effect: String?, plus: Bool, _ body: (KeyboardView, UIWindow) throws -> Void) rethrows {
        PlusGate.paywallEnabled = true
        PlusGate.defaults.set(plus, forKey: PlusConfig.unlockedKey)
        let d = UserDefaults(suiteName: suite)!
        d.removePersistentDomain(forName: suite)
        if let effect { d.set(effect, forKey: KeyEffect.key) }
        d.set(false, forKey: KeyboardView.keyPreviewKey)
        let saved = UserDefaultsProvider.shared
        UserDefaultsProvider.shared = d
        defer { UserDefaultsProvider.shared = saved; d.removePersistentDomain(forName: suite) }
        let win = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 300))
        win.isHidden = false
        let kb = KeyboardView(needsGlobe: false, inputController: nil) { _ in }
        kb.frame = win.bounds
        win.addSubview(kb)
        kb.applyAppearance(.light, style: .light)
        kb.layoutIfNeeded()
        try body(kb, win)
        win.isHidden = true
    }

    /// Mặc định / hết Plus: KHÔNG có overlay (0 chi phí) — gõ vẫn như cũ.
    @MainActor func testKeyboardOffMeansNoOverlay() throws {
        try withKeyboard(effect: nil, plus: true) { kb, _ in
            XCTAssertNil(kb.keyEffects)
            XCTAssertFalse(kb.subviews.contains { $0 is KeyEffectOverlay })
        }
        try withKeyboard(effect: "heart", plus: false) { kb, _ in
            XCTAssertNil(kb.keyEffects, "chưa có Plus ⇒ không tạo overlay")
        }
    }

    /// Bật: gõ chữ ⇒ chữ chèn ngay (đồng bộ), hạt chỉ xuất hiện ở lượt runloop sau, tại phím vừa bấm.
    @MainActor func testKeyboardTypingInsertsFirstThenAnimates() throws {
        var typed: [KeyboardView.Key] = []
        PlusGate.paywallEnabled = true
        PlusGate.defaults.set(true, forKey: PlusConfig.unlockedKey)
        let d = UserDefaults(suiteName: suite)!
        d.removePersistentDomain(forName: suite)
        d.set("heart", forKey: KeyEffect.key)
        let saved = UserDefaultsProvider.shared
        UserDefaultsProvider.shared = d
        defer { UserDefaultsProvider.shared = saved; d.removePersistentDomain(forName: suite) }
        let win = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 300))
        win.isHidden = false
        let kb = KeyboardView(needsGlobe: false, inputController: nil) { typed.append($0) }
        kb.frame = win.bounds
        win.addSubview(kb)
        kb.applyAppearance(.light, style: .light)
        kb.layoutIfNeeded()
        let o = try XCTUnwrap(kb.keyEffects)
        XCTAssertTrue(o.superview === kb)
        let f = try XCTUnwrap(kb.debugLetterFrame("a"))
        kb.benchTap(at: CGPoint(x: f.midX, y: f.midY), time: 1)
        guard case .letter(let ch)? = typed.last else { return XCTFail("không chèn chữ") }
        XCTAssertEqual(String(ch).lowercased(), "a")   // shift đầu câu có thể bật
        XCTAssertEqual(o.debugBurstCount, 0, "chưa vẽ gì trong lượt chạm")
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertEqual(o.debugBurstCount, 1)
        XCTAssertEqual(o.debugParticleCount, KeyEffect.heart.particlesPerTap)
        // Chùm đặt tại tâm phím "a".
        let positions = o.debugBurstPositions
        XCTAssertTrue(positions.allSatisfy { abs($0.x - f.midX) < 1 && abs($0.y - f.midY) < 1 }, "\(positions) vs \(f)")
        // ⌫ không mang hiệu ứng (không đánh dấu điểm) — không bắn thêm.
        _ = kb.debugBackspaceSwipe(from: 0, through: [])
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertEqual(o.debugBurstCount, 1)
        // Đổi sang Không ở lần hiện kế ⇒ gỡ overlay.
        d.set("none", forKey: KeyEffect.key)
        kb.applyAppearance(.light, style: .light)
        XCTAssertNil(kb.keyEffects)
        XCTAssertFalse(kb.subviews.contains { $0 is KeyEffectOverlay })
        win.isHidden = true
    }

    /// Overlay nằm dưới ô phóng to.
    @MainActor func testOverlayBelowBalloon() throws {
        try withKeyboard(effect: "ripple", plus: true) { kb, _ in
            let o = try XCTUnwrap(kb.keyEffects)
            // Thứ tự Phil: nền → phím → ảnh tiền cảnh (theme JSON) → hiệu ứng → ô phóng to.
            XCTAssertGreaterThan(o.layer.zPosition, kb.debugForegroundView.layer.zPosition, "trên ảnh tiền cảnh")
            kb.debugSetKeyPreview(true)
            let f = try XCTUnwrap(kb.debugLetterFrame("q"))
            kb.benchTap(at: CGPoint(x: f.midX, y: f.midY), time: 1)
            let balloon = kb.subviews.first { $0.layer.zPosition == 10 }
            if let balloon { XCTAssertLessThan(o.layer.zPosition, balloon.layer.zPosition) }
        }
    }

    // MARK: A/B chi phí main thread (bộ chậm)

    /// Cùng process, xen kẽ khối TẮT / BẬT (mỗi kiểu) để trôi nhiệt / nhiễu chia đều. Mỗi phím: thời gian
    /// đồng bộ (chạm → chèn xong) + CPU main gồm lượt vẽ hoãn. In "KEYFX …" cho báo cáo.
    @MainActor func testMainThreadCostPerKeyOnVsOff() throws {
        try SlowTests.require()
        PlusGate.paywallEnabled = false
        let saved = UserDefaultsProvider.shared
        defer { UserDefaultsProvider.shared = saved }
        let keys = Array("xinchaomoinguoihomnaytoiddilamvieccongty")
        func run(_ effect: KeyEffect, rounds: Int) -> (sync: [Double], main: [Double], fx: [Double]) {
            let name = "vt-keyfx-bench-\(effect.rawValue)"
            let d = UserDefaults(suiteName: name)!
            d.removePersistentDomain(forName: name)
            d.set(effect.rawValue, forKey: KeyEffect.key)
            d.set(true, forKey: KeyboardView.keyPreviewKey)
            UserDefaultsProvider.shared = d
            let win = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 290))
            win.isHidden = false
            let kb = KeyboardView(needsGlobe: false, inputController: nil) { _ in }
            kb.frame = win.bounds
            win.addSubview(kb)
            kb.applyAppearance(.light, style: .light)
            kb.layoutIfNeeded()
            XCTAssertEqual(kb.keyEffects?.effect ?? .none, effect)
            var sync: [Double] = [], main: [Double] = [], fx: [Double] = []
            for r in 0..<rounds {
                for c in keys {
                    guard let f = kb.debugLetterFrame(String(c)) else { continue }
                    KeyEffectOverlay.debugCost = 0
                    let c0 = KeyboardBenchTests.mainThreadCPU()
                    let t0 = CACurrentMediaTime()
                    kb.benchTap(at: CGPoint(x: f.midX, y: f.midY), time: t0)
                    let t1 = CACurrentMediaTime()
                    KeyboardBenchTests.spin(0.03)   // lượt vẽ hoãn + commit CA
                    if r > 0 {   // vòng đầu: làm nóng
                        sync.append((t1 - t0) * 1000)
                        main.append((KeyboardBenchTests.mainThreadCPU() - c0) * 1000)
                        fx.append(KeyEffectOverlay.debugCost * 1000)
                    }
                }
            }
            win.isHidden = true
            kb.tearDown()
            d.removePersistentDomain(forName: name)
            return (sync, main, fx)
        }
        let p = KeyboardBenchTests.pct
        _ = run(.none, rounds: 2)   // làm nóng cả process
        for e in KeyEffect.allCases where e != .none {
            var offS: [Double] = [], offM: [Double] = [], onS: [Double] = [], onM: [Double] = [], fx: [Double] = []
            var dMainBlocks: [Double] = [], dSyncBlocks: [Double] = []
            for _ in 0..<6 {   // xen kẽ khối TẮT / BẬT: trôi nhiệt / tải máy chia đều
                let a = run(.none, rounds: 2), b = run(e, rounds: 2)
                offS += a.sync; offM += a.main; onS += b.sync; onM += b.main; fx += b.fx
                dMainBlocks.append(p(b.main, 0.5) - p(a.main, 0.5))
                dSyncBlocks.append(p(b.sync, 0.5) - p(a.sync, 0.5))
            }
            let dMain = p(dMainBlocks, 0.5), dSync = p(dSyncBlocks, 0.5)
            let line = String(format: "KEYFX %@ n=%d | trực tiếp emit+flush p50=%.4f p95=%.4f p99=%.4f ms | A/B main_p50 off=%.3f on=%.3f Δ(trung vị khối)=%.3f | sync_p50 off=%.3f on=%.3f Δ=%.3f | main_p95 off=%.3f on=%.3f",
                              e.rawValue, onS.count, p(fx, 0.5), p(fx, 0.95), p(fx, 0.99),
                              p(offM, 0.5), p(onM, 0.5), dMain, p(offS, 0.5), p(onS, 0.5), dSync,
                              p(offM, 0.95), p(onM, 0.95))
            print(line)
            XCTContext.runActivity(named: line) { _ in }
            // Ngưỡng Phil: tăng > ~0,3 ms/phím trên main là không phát hành. Phần của hiệu ứng đo trực tiếp
            // (chặt); A/B cả đường phím có nhiễu simulator ±0,2 ms nên dùng trung vị các khối xen kẽ.
            XCTAssertLessThan(p(fx, 0.95), 0.3, "\(e.rawValue): emit+flush p95 (ms)")
            XCTAssertLessThan(dMain, 0.3, "\(e.rawValue): CPU main tăng mỗi phím (ms)")
        }
    }
}
