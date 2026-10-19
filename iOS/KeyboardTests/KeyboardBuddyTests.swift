// "Bạn đồng hành" (Keyboard/KeyboardBuddy*.swift): máy trạng thái theo dấu giờ, bậc tốc độ, dáng nhảy, cổng
// Plus, 0 view khi tắt, 0 hoạt ảnh khi ngủ, ô không đè gợi ý / phím, 500 phím không rò, chi phí mỗi phím.
// Số tham chiếu (bậc, mốc giờ, checksum bảng hình) GIỐNG HỆT android KeyboardBuddyTests.
import XCTest
import UIKit

final class KeyboardBuddyTests: XCTestCase {
    private let suite = "vt-buddy-tests"
    private let plusSuite = "vt-buddy-plus"
    private var savedPaywall = false
    private var savedDefaults: UserDefaults!

    override func setUp() {
        super.setUp()
        savedPaywall = PlusGate.paywallEnabled
        savedDefaults = PlusGate.defaults
        UserDefaults(suiteName: plusSuite)!.removePersistentDomain(forName: plusSuite)
        PlusGate.defaults = UserDefaults(suiteName: plusSuite)!
    }

    override func tearDown() {
        PlusGate.paywallEnabled = savedPaywall
        PlusGate.defaults = savedDefaults
        UserDefaults(suiteName: plusSuite)!.removePersistentDomain(forName: plusSuite)
        super.tearDown()
    }

    // MARK: Cài đặt

    func testIdsMatchAndroidAndDefaultIsOff() {
        XCTAssertEqual(BuddyCharacter.allCases.map(\.rawValue), ["off", "girl", "boy", "cat", "chick"])
        XCTAssertEqual(BuddyCharacter.key, "keyboardBuddy")
        XCTAssertEqual(BuddyCharacter.defaultValue, .off)
        XCTAssertEqual(BuddyCharacter.parse("lạ"), .off)
        XCTAssertEqual(BuddyCharacter.parse(nil), .off)
        XCTAssertEqual(BuddyCharacter.parse("chick"), .chick)
    }

    func testBackupSpecWhitelistsIds() {
        let spec = BackupSettings.all.first { $0.key == BuddyCharacter.key }
        XCTAssertNotNil(spec, "sao lưu cùng khoá với Android")
    }

    func testTitlesBilingual() {
        defer { L10n.apply("vi") }
        L10n.apply("vi")
        XCTAssertEqual(BuddyCharacter.girl.title, "Cô gái")
        XCTAssertEqual(BuddyCharacter.off.title, "Tắt")
        L10n.apply("en")
        XCTAssertEqual(BuddyCharacter.allCases.map(\.title), ["Off", "Girl", "Boy", "Cat", "Chick"])
        XCTAssertEqual(L("Bạn đồng hành"), "Keyboard buddy")
    }

    /// Plus: hết Plus ⇒ Tắt (không tạo view) nhưng GIỮ cài đặt (mua lại là có ngay).
    func testPlusFallback() {
        XCTAssertEqual(BuddyPolicy.active(setting: .cat, plus: false), .off)
        XCTAssertEqual(BuddyPolicy.active(setting: .cat, plus: true), .cat)
        XCTAssertEqual(BuddyPolicy.active(setting: .off, plus: true), .off)
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        d.set("girl", forKey: BuddyCharacter.key)
        PlusGate.paywallEnabled = true
        PlusGate.defaults.set(false, forKey: PlusConfig.unlockedKey)
        XCTAssertEqual(BuddyPolicy.current(d), .off)
        XCTAssertEqual(d.string(forKey: BuddyCharacter.key), "girl", "không xoá lựa chọn")
        PlusGate.defaults.set(true, forKey: PlusConfig.unlockedKey)
        XCTAssertEqual(BuddyPolicy.current(d), .girl)
        XCTAssertFalse(BuddyCharacter.off.isPlus)
        XCTAssertTrue(BuddyCharacter.allCases.filter { $0 != .off }.allSatisfy(\.isPlus))
    }

    // MARK: Bậc tốc độ

    func testTierFunction() {
        // Số phím trong 2 s → bậc (cùng ngưỡng Lửa combo 6/10/14).
        XCTAssertEqual((0...16).map { BuddyTempo.tier(count: $0, previous: 0) },
                       [0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3])
        // Trễ xuống: đang bậc 3, tụt về 12–13 vẫn giữ 3; 11 ⇒ 2; 4 ⇒ 0.
        XCTAssertEqual(BuddyTempo.tier(count: 13, previous: 3), 3)
        XCTAssertEqual(BuddyTempo.tier(count: 12, previous: 3), 3)
        XCTAssertEqual(BuddyTempo.tier(count: 11, previous: 3), 2)
        XCTAssertEqual(BuddyTempo.tier(count: 8, previous: 2), 2)
        XCTAssertEqual(BuddyTempo.tier(count: 7, previous: 2), 1)
        XCTAssertEqual(BuddyTempo.tier(count: 4, previous: 1), 1)
        XCTAssertEqual(BuddyTempo.tier(count: 3, previous: 1), 0)
        // Không bao giờ vượt bậc thô khi đi lên.
        for n in 0...20 { for p in 0...3 { XCTAssertGreaterThanOrEqual(BuddyTempo.tier(count: n, previous: p), BuddyTempo.raw(n)) } }
    }

    /// Gõ đều với khoảng cách `dt` giây: bậc sau ~2 s.
    func testTierFromInterKeyInterval() {
        func tierAfter(interval dt: Double) -> Int {
            var b = BuddyBrain()
            var t = 0.0
            for _ in 0..<40 { b.press(at: t); t += dt }
            return b.tier
        }
        XCTAssertEqual(tierAfter(interval: 0.5), 0)    // 2 phím/s
        XCTAssertEqual(tierAfter(interval: 0.3), 1)    // ~3,3 phím/s
        XCTAssertEqual(tierAfter(interval: 0.18), 2)   // ~5,5 phím/s
        XCTAssertEqual(tierAfter(interval: 0.11), 3)   // ~9 phím/s
    }

    // MARK: Máy trạng thái

    /// Ngủ → thức → nhảy (lên bậc) → đứng → ngủ ("z z" 2 s) → ngủ yên; mốc hẹn giờ đúng từng chặng.
    func testStateMachineTimeline() {
        var b = BuddyBrain()
        XCTAssertEqual(b.phase(at: 0), .asleep(zzz: false), "mới mở bàn phím: ngủ yên, không hoạt ảnh")
        XCTAssertNil(b.nextDeadline(after: 0))
        // Gõ nhanh 9 phím/s từ t=10.
        var t = 10.0
        b.press(at: t)
        XCTAssertEqual(b.phase(at: t), .dancing(0))
        XCTAssertEqual(b.nextDeadline(after: t), t + BuddyBrain.tail)
        var seen: [Int] = []
        for _ in 0..<30 {
            t += 0.11
            b.press(at: t)
            if case .dancing(let k) = b.phase(at: t), seen.last != k { seen.append(k) }
        }
        XCTAssertEqual(seen, [0, 1, 2, 3], "lên bậc dần, không nhảy cóc / lùi")
        let last = t
        XCTAssertEqual(b.phase(at: last + 0.59), .dancing(3), "đuôi nhảy 0,6 s")
        XCTAssertEqual(b.phase(at: last + 0.61), .awake)
        XCTAssertEqual(b.nextDeadline(after: last + 0.61)!, last + 3, accuracy: 1e-9)
        XCTAssertEqual(b.phase(at: last + 2.99), .awake)
        XCTAssertEqual(b.phase(at: last + 3.01), .asleep(zzz: true), "≥ 3 s không gõ ⇒ ngủ, z z")
        XCTAssertEqual(b.nextDeadline(after: last + 3.01)!, last + 5, accuracy: 1e-9)
        XCTAssertEqual(b.phase(at: last + 5.01), .asleep(zzz: false), "z z chỉ ~2 s rồi dừng hẳn")
        XCTAssertNil(b.nextDeadline(after: last + 5.01), "ngủ yên ⇒ không hẹn giờ nào")
        // Gõ lại sau giấc ngủ: bậc tính lại từ 0.
        b.press(at: last + 20)
        XCTAssertEqual(b.phase(at: last + 20), .dancing(0))
    }

    func testCheerWakesThenSettles() {
        var b = BuddyBrain()
        b.cheer(at: 5)
        XCTAssertEqual(b.phase(at: 5.1), .cheering)
        XCTAssertEqual(b.phase(at: 5.0 + BuddyBrain.cheer + 0.01), .awake)
        XCTAssertEqual(b.phase(at: 8.1), .asleep(zzz: true))
    }

    func testLowPowerShowsStaticPosesOnly() {
        for p: BuddyPhase in [.dancing(3), .cheering, .awake] {
            XCTAssertEqual(BuddyPolicy.visual(p, lowPower: true), .awake)
        }
        XCTAssertEqual(BuddyPolicy.visual(.asleep(zzz: true), lowPower: true), .asleep(zzz: false))
        XCTAssertEqual(BuddyPolicy.visual(.dancing(2), lowPower: false), .dancing(2))
        XCTAssertFalse(BuddyPolicy.animates(.awake))
        XCTAssertFalse(BuddyPolicy.animates(.asleep(zzz: false)))
        XCTAssertTrue(BuddyPolicy.animates(.dancing(0)))
    }

    // MARK: Dáng nhảy

    func testDancePoseLoopsAndEnergyRisesWithTier() {
        for c in BuddyCharacter.allCases where c != .off {
            let s = BuddyRig.rig(c)!.style
            var peaks: [Double] = []
            for t in 0..<4 {
                XCTAssertEqual(BuddyDance.pose(s, tier: t, p: 0), BuddyDance.pose(s, tier: t, p: 1), "vòng khép kín")
                var bounce = 0.0
                for i in 0..<200 { bounce = max(bounce, -BuddyDance.pose(s, tier: t, p: Double(i) / 200).dy) }
                peaks.append(bounce)
            }
            XCTAssertEqual(peaks, peaks.sorted(), "\(c): gõ nhanh hơn ⇒ nhún cao hơn")
            XCTAssertLessThan(peaks[0], peaks[3])
            // Xoay vòng chỉ ở bậc cao nhất.
            XCTAssertTrue((0..<100).allSatisfy { BuddyDance.pose(s, tier: 2, p: Double($0) / 100).sx == 1 })
            XCTAssertTrue((0..<100).contains { BuddyDance.pose(s, tier: 3, p: Double($0) / 100).sx < 0 })
            XCTAssertEqual(BuddyDance.cheer(s, f: 0), .rest)
            XCTAssertEqual(BuddyDance.cheer(s, f: 1).dy, 0, accuracy: 1e-9)
        }
        XCTAssertEqual(BuddyDance.loopLength(0), 2.48, accuracy: 1e-9)
        XCTAssertEqual(BuddyDance.loopLength(3), 1.44, accuracy: 1e-9)
    }

    /// Số chung với Android: pose cô gái bậc 2 tại p = 0,3.
    func testPoseParityNumbers() {
        let p = BuddyDance.pose(BuddyRig.girl.style, tier: 2, p: 0.3)
        XCTAssertEqual(p.dy, -4.4966, accuracy: 1e-3)
        XCTAssertEqual(p.dx, 6.4672, accuracy: 1e-3)
        XCTAssertEqual(p.armL, 1.8051, accuracy: 1e-3)
        XCTAssertEqual(p.armR, -2.1049, accuracy: 1e-3)
        XCTAssertEqual(p.tail, 0.2829, accuracy: 1e-3)
    }

    // MARK: Hình (dữ liệu)

    func testSVGParse() {
        XCTAssertEqual(BuddySVG.parse("M1 2 l3 4 h5 V-6 q1 1 2 2 Z"),
                       [.move(1, 2), .line(4, 6), .line(9, 6), .line(9, -6), .quad(10, -5, 11, -4), .close])
        XCTAssertEqual(BuddySVG.numbers("M-8 72 L-10 140"), [-8, 72, -10, 140])
        XCTAssertEqual(BuddySVG.numbers("h1.5-2"), [1.5, -2])
    }

    /// Bảng hình giống hệt Android (KeyboardBuddyTests.rigChecksumParity).
    func testRigChecksumParity() {
        let sums = BuddyCharacter.allCases.map { String(format: "%.4f", BuddyRig.checksum($0)) }
        print("BUDDY checksum \(sums)")
        XCTAssertEqual(sums, Self.checksums)
    }
    static let checksums = ["0.0000", "13803.1358", "12756.7491", "15286.2573", "12025.5890"]

    func testEveryRigHasAllPartsAndSleepPose() {
        for c in BuddyCharacter.allCases where c != .off {
            let r = BuddyRig.rig(c)!
            for p in [BuddyPart.legL, .legR, .body, .armL, .armR, .head, .sleep] {
                XCTAssertTrue(r.pieces.contains { $0.part == p }, "\(c) thiếu \(p)")
            }
            for p in r.pieces where p.part != .sleep { XCTAssertNotNil(r.pivots[p.part], "\(c) \(p.part) thiếu điểm xoay") }
            XCTAssertTrue(r.pieces.contains { $0.show == .rest } == r.pieces.contains { $0.show == .dance })
        }
        XCTAssertTrue(BuddyRig.girl.hasTail && BuddyRig.cat.hasTail)
        XCTAssertFalse(BuddyRig.boy.hasTail || BuddyRig.chick.hasTail)
        XCTAssertEqual(BuddySprites.frameIndex(p: 0.99, count: 8), 7)
        XCTAssertEqual(BuddySprites.frameIndex(p: 1.0, count: 8), 0)
    }

    // MARK: View

    private final class Clock { var t = 100.0 }

    @MainActor private func makeView(_ c: BuddyCharacter, low: Bool = false, clock: Clock) -> BuddyView {
        let v = BuddyView(character: c, bar: BuddyHalo.systemLight, lowPower: low)
        v.clock = { clock.t }
        v.frame = CGRect(x: 0, y: 0, width: KeyboardView.buddyZoneWidth, height: 40)
        v.layoutIfNeeded()
        return v
    }

    /// Ngủ ⇒ 0 hoạt ảnh (kể cả lúc mới hiện); "z z" có hạn rồi gỡ; đứng yên cũng 0 (sau lượt nối).
    @MainActor func testZeroAnimationsWhenAsleep() {
        let clock = Clock()
        for c in BuddyCharacter.allCases where c != .off {
            let v = makeView(c, clock: clock)
            XCTAssertEqual(v.shown, .asleep(zzz: false))
            XCTAssertEqual(v.runningAnimationCount, 0, "\(c): mới hiện — ngủ yên")
            XCTAssertFalse(v.hasPendingTimer)
            for _ in 0..<20 { clock.t += 0.1; v.keyPressed() }
            v.tick()
            guard case .dancing = v.shown else { return XCTFail("\(c): gõ ⇒ nhảy") }
            XCTAssertGreaterThan(v.runningAnimationCount, 3)
            XCTAssertTrue(v.hasPendingTimer)
            clock.t += 1; v.tick()
            XCTAssertEqual(v.shown, .awake)
            clock.t += 2.5; v.tick()
            XCTAssertEqual(v.shown, .asleep(zzz: true))
            XCTAssertEqual(v.runningAnimationCount, 3, "\(c): chỉ ba chữ z")
            clock.t += 2.1; v.tick()
            XCTAssertEqual(v.shown, .asleep(zzz: false))
            XCTAssertEqual(v.runningAnimationCount, 0, "\(c): ngủ yên ⇒ 0 hoạt ảnh")
            XCTAssertFalse(v.hasPendingTimer, "\(c): ngủ yên ⇒ không hẹn giờ")
            v.halt()
        }
    }

    @MainActor func testLowPowerNeverAnimates() {
        let clock = Clock()
        let v = makeView(.girl, low: true, clock: clock)
        for _ in 0..<30 { clock.t += 0.08; v.keyPressed(); v.tick() }
        XCTAssertEqual(v.shown, .awake)
        XCTAssertEqual(v.runningAnimationCount, 0)
        v.cheer(); v.tick()
        XCTAssertEqual(v.runningAnimationCount, 0)
        clock.t += 3.5; v.tick()
        XCTAssertEqual(v.shown, .asleep(zzz: false))
        XCTAssertEqual(v.runningAnimationCount, 0)
        v.halt()
    }

    /// Đổi bậc: vòng mới + hoạt ảnh nối (không nhảy dáng); key per-layer giữ ≤ 2 (loop + blend).
    @MainActor func testTierChangeBlendsAndStaysBounded() {
        let clock = Clock()
        let v = makeView(.cat, clock: clock)
        var tiers: [Int] = []
        for _ in 0..<40 {
            clock.t += 0.1; v.keyPressed(); v.tick()
            if case .dancing(let k) = v.shown, tiers.last != k { tiers.append(k) }
            XCTAssertLessThanOrEqual(v.runningAnimationCount, 2 * 20)   // vòng + nối mỗi kênh; bộ phận có bản quầng xoay cùng
        }
        XCTAssertEqual(tiers, [0, 1, 2, 3])
        v.halt()
        XCTAssertEqual(v.runningAnimationCount, 0)
    }

    /// 500 phím: không thêm layer, hoạt ảnh có trần, view giải phóng được (không vòng giữ qua timer).
    @MainActor func testBurstOf500KeysNoLeak() {
        let clock = Clock()
        weak var weakView: BuddyView?
        autoreleasepool {
            let v = makeView(.boy, clock: clock)
            weakView = v
            let layers = v.layerCount
            for i in 0..<500 {
                clock.t += i % 50 < 40 ? 0.07 : 0.35   // nhanh / chậm xen kẽ ⇒ đổi bậc nhiều lần
                v.keyPressed()
                if i % 5 == 0 { v.tick() }
                if i % 100 == 0 { v.cheer() }
            }
            v.tick()
            XCTAssertEqual(v.layerCount, layers, "không tạo thêm layer khi gõ")
            XCTAssertLessThanOrEqual(v.runningAnimationCount, 40)
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))   // lượt apply hoãn
        }
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertNil(weakView, "BuddyView rò (timer / block giữ self)")
    }

    /// Chi phí main mỗi phím (đường đồng bộ `keyPressed`) — ngưỡng < 0,1 ms.
    @MainActor func testPerKeyCost() {
        let v = BuddyView(character: .girl, bar: BuddyHalo.systemLight, lowPower: false)
        v.frame = CGRect(x: 0, y: 0, width: 34, height: 40)
        v.layoutIfNeeded()
        var samples: [Double] = []
        for _ in 0..<600 {
            let t0 = CACurrentMediaTime()
            v.keyPressed()
            samples.append((CACurrentMediaTime() - t0) * 1000)
            if samples.count % 60 == 0 { RunLoop.main.run(until: Date().addingTimeInterval(0.002)) }
        }
        samples.removeFirst(100)
        samples.sort()
        let p50 = samples[samples.count / 2], p99 = samples[samples.count * 99 / 100]
        let mean = samples.reduce(0, +) / Double(samples.count)
        // Lượt áp trạng thái (hoãn, ngoài đường phím): đổi bậc ⇒ gắn lại vòng + nối.
        let clock = Clock()
        let w = makeView(.girl, clock: clock)
        var apply: [Double] = []
        for i in 0..<200 {
            clock.t += i % 40 < 20 ? 0.06 : 0.4
            w.keyPressed()
            let t0 = CACurrentMediaTime()
            w.tick()
            apply.append((CACurrentMediaTime() - t0) * 1000)
        }
        apply.sort()
        let line = String(format: "BUDDY cost keyPressed mean=%.4f p50=%.4f p99=%.4f ms | deferred apply p50=%.4f p95=%.4f max=%.4f ms",
                          mean, p50, p99, apply[100], apply[190], apply.last!)
        print(line)
        XCTContext.runActivity(named: line) { _ in }
        XCTAssertLessThan(p50, 0.1)
        XCTAssertLessThan(mean, 0.1)
        v.halt(); w.halt()
    }

    // MARK: KeyboardView

    @MainActor private func withKeyboard(buddy: String?, plus: Bool, width: CGFloat = 390, height: CGFloat = 300,
                                         emojiTop: Bool = false,
                                         _ body: (KeyboardView) throws -> Void) rethrows {
        PlusGate.paywallEnabled = true
        PlusGate.defaults.set(plus, forKey: PlusConfig.unlockedKey)
        let d = UserDefaults(suiteName: suite)!
        d.removePersistentDomain(forName: suite)
        if let buddy { d.set(buddy, forKey: BuddyCharacter.key) }
        if emojiTop { d.set("top", forKey: EmojiKeyPlacement.key) }
        d.set(false, forKey: KeyboardView.keyPreviewKey)
        let saved = UserDefaultsProvider.shared
        UserDefaultsProvider.shared = d
        defer { UserDefaultsProvider.shared = saved; d.removePersistentDomain(forName: suite) }
        let win = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: height))
        win.isHidden = false
        let kb = KeyboardView(needsGlobe: false, inputController: nil) { _ in }
        kb.frame = win.bounds
        win.addSubview(kb)
        kb.applyAppearance(.light, style: .light)
        kb.setSuggestionsEnabled(true)
        kb.layoutIfNeeded()
        try body(kb)
        win.isHidden = true
        kb.tearDown()
    }

    /// Tắt / hết Plus ⇒ không view, không layer; bật ⇒ một view; đổi về Tắt ⇒ gỡ sạch.
    @MainActor func testNoLayersWhenOff() throws {
        try withKeyboard(buddy: nil, plus: true) { kb in
            XCTAssertNil(kb.buddy)
            XCTAssertFalse(kb.subviews.contains { $0 is BuddyView })
        }
        try withKeyboard(buddy: "cat", plus: false) { kb in
            XCTAssertNil(kb.buddy, "chưa Plus ⇒ không tạo")
            XCTAssertFalse(kb.subviews.contains { $0 is BuddyView })
        }
        try withKeyboard(buddy: "cat", plus: true) { kb in
            let b = try XCTUnwrap(kb.buddy)
            XCTAssertTrue(b.superview === kb)
            XCTAssertFalse(b.isUserInteractionEnabled, "không nhận chạm")
            UserDefaultsProvider.shared?.set("off", forKey: BuddyCharacter.key)
            kb.applyAppearance(.light, style: .light)
            XCTAssertNil(kb.buddy)
            XCTAssertFalse(kb.subviews.contains { $0 is BuddyView })
        }
    }

    /// Ô nhân vật không bao giờ chồng chip gợi ý / ⌄ / 📋 / 😊 và không lấn xuống hàng phím — điện thoại dọc,
    /// ngang, iPad.
    @MainActor func testLayoutNeverOverlapsChipsOrKeys() throws {
        let sizes: [(CGFloat, CGFloat)] = [(320, 260), (375, 260), (390, 300), (430, 300), (667, 200), (844, 200),
                                           (932, 210), (768, 320), (1024, 360), (1366, 400)]
        for (w, h) in sizes {
            for emojiTop in [false, true] {
                try withKeyboard(buddy: "girl", plus: true, width: w, height: h, emojiTop: emojiTop) { kb in
                    let b = try XCTUnwrap(kb.buddy)
                    let s = kb.debugStripLayout()
                    XCTAssertFalse(b.isHidden, "\(w)")
                    let f = b.frame
                    XCTAssertGreaterThan(f.width, 0)
                    XCTAssertFalse(f.intersects(s.bar), "\(w)×\(h): đè thanh gợi ý \(f) vs \(s.bar)")
                    for c in s.cells { XCTAssertFalse(f.intersects(c), "\(w): đè ô gợi ý") }
                    XCTAssertFalse(f.intersects(s.chevron), "\(w): đè ⌄")
                    if let c = s.clip { XCTAssertFalse(f.intersects(c), "\(w): đè 📋") }
                    if let e = kb.debugEmojiBarButton { XCTAssertFalse(f.intersects(e.frame), "\(w): đè 😊") }
                    XCTAssertLessThanOrEqual(f.maxY, kb.keyAreaFrame.minY + 0.5, "\(w)×\(h): lấn hàng phím")
                    XCTAssertTrue(b.clipsToBounds, "nhân vật không vẽ ra ngoài ô")
                    // Nhân vật cao gần bằng thanh (~30–44 pt), nằm trọn trong ô.
                    XCTAssertTrue(b.bounds.contains(b.figureFrame.insetBy(dx: 0.5, dy: 0.5)), "\(w): \(b.figureFrame)")
                    XCTAssertGreaterThanOrEqual(b.figureFrame.height, min(26, b.bounds.height - 2), "\(w): nhân vật quá nhỏ")
                }
            }
        }
        // Hàm thuần.
        XCTAssertEqual(KeyboardView.buddyFrame(width: 390, strip: 40),
                       CGRect(x: 390 - KeyboardView.stripZoneWidth - 34, y: 0, width: 34, height: 40))
        XCTAssertEqual(KeyboardView.emojiBarFrame(width: 390, strip: 36, collapsed: false, clip: false, buddy: 34).maxX,
                       390 - KeyboardView.stripZoneWidth - 34)
    }

    /// Thu gọn thanh / plane emoji ⇒ nhân vật ẩn (không chỗ, không đè lưới emoji).
    @MainActor func testHiddenWhenBarCollapsedOrEmoji() throws {
        try withKeyboard(buddy: "chick", plus: true) { kb in
            let b = try XCTUnwrap(kb.buddy)
            kb.debugSetBarCollapsed(true); kb.layoutIfNeeded()
            XCTAssertTrue(b.isHidden)
            kb.debugSetBarCollapsed(false); kb.layoutIfNeeded()
            XCTAssertFalse(b.isHidden)
            kb.debugShowEmojiPlane(); kb.layoutIfNeeded()
            XCTAssertTrue(b.isHidden)
        }
    }

    /// Gõ phím thật trên KeyboardView: nhân vật thức dậy và nhảy (lượt sau); chữ vẫn chèn.
    @MainActor func testTypingWakesBuddy() throws {
        try withKeyboard(buddy: "boy", plus: true) { kb in
            let b = try XCTUnwrap(kb.buddy)
            XCTAssertEqual(b.shown, .asleep(zzz: false))
            let f = try XCTUnwrap(kb.debugLetterFrame("a"))
            kb.benchTap(at: CGPoint(x: f.midX, y: f.midY), time: 1)
            XCTAssertEqual(b.shown, .asleep(zzz: false), "đường phím không đụng layer")
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
            XCTAssertEqual(b.shown, .dancing(0))
        }
    }

    // MARK: Phản ứng theo từ (giận / thả tim / cười / ngạc nhiên / buồn) + hoảng khi xoá dồn

    func testReactionMatching() {
        let love = ["yêu", "Yêu", "YÊU", "yeu", "iu", "thương", "thuong", "love", "LOVE!", "luv", "miss", "thích",
                    "thich", "crush", "tim", "hun", "honey", "baby", "nhớ", "cưng", "thơm", "muah"]
        for w in love { XCTAssertEqual(BuddyWords.reaction(word: w, prev: nil), .love, w) }
        let laugh = ["haha", "hahaha", "HAHAHAHA", "hahah", "hihi", "hihihi", "hehe", "kkk", "kkkkkkk", "kaka", "lol",
                     "lmao", "=))", "=)))))", ":))", "😂", "hjhjhj"]
        for w in laugh { XCTAssertEqual(BuddyWords.reaction(word: w, prev: nil), .laugh, w) }
        let surprised = ["wow", "woooow", "WOW!", "ôi", "omg", "omggg", "ủa", "hả", "what", "ồ", "á"]
        for w in surprised { XCTAssertEqual(BuddyWords.reaction(word: w, prev: nil), .surprised, w) }
        XCTAssertEqual(BuddyWords.reaction(word: "ơi", prev: "trời"), .surprised, "cụm trời ơi")
        let sad = ["buồn", "buon", "khóc", "huhu", "huhuhu", "hic", "hichic", "hix", "sad", ":(", ":(((", "chán", "😭"]
        for w in sad { XCTAssertEqual(BuddyWords.reaction(word: w, prev: nil), .sad, w) }
        XCTAssertEqual(BuddyWords.reaction(word: "mỏi", prev: "mệt"), .sad)
        // strict: bỏ dấu thành từ khác ⇒ chỉ khớp đúng dấu; từ thường ⇒ không.
        for w in ["nho", "nhỏ", "cung", "thom", "chan", "a", "o", "ha", "an", "anh", "bàn", "trời", "k", "kk", "hah", ""] {
            XCTAssertNil(BuddyWords.reaction(word: w, prev: nil), w)
        }
        XCTAssertNil(BuddyWords.reaction(word: String(repeating: "a", count: 30), prev: nil))
    }

    /// Giận dùng ĐÚNG các bảng của bộ lọc gợi ý (từ / cặp / bộ ba) — không phụ thuộc công tắc lọc (không nhận tham số).
    func testAngryUsesSensitiveTables() throws {
        let bad = try XCTUnwrap(SensitiveWords.set.sorted().first)
        XCTAssertEqual(BuddyWords.reaction(word: bad, prev: nil), .angry)
        XCTAssertEqual(BuddyWords.reaction(word: bad.uppercased() + "!", prev: nil), .angry)
        // Cặp: tìm một cặp trong bảng.
        let pair = try XCTUnwrap(SensitiveWords.pairs.first(where: { !$0.value.isEmpty }))
        let w2 = try XCTUnwrap(pair.value.sorted().first)
        if !SensitiveWords.set.contains(w2) {
            XCTAssertEqual(BuddyWords.reaction(word: w2, prev: pair.key), .angry, "cặp \(pair.key) \(w2)")
            XCTAssertNotEqual(BuddyWords.reaction(word: w2, prev: "xyz"), .angry)
        }
        // "wtf" vừa tục vừa ngạc nhiên ⇒ giận thắng.
        XCTAssertEqual(BuddyWords.reaction(word: "wtf", prev: nil), .angry)
        // Từ yêu thương sau từ tục: cặp không tục ⇒ vẫn thả tim (giận chỉ theo từ VỪA chốt / cặp / bộ ba).
        XCTAssertEqual(BuddyWords.reaction(word: "yêu", prev: "anh"), .love)
    }

    func testReactionPriorityOrder() {
        XCTAssertEqual(BuddyReaction.allCases.prefix(5), [.angry, .love, .laugh, .surprised, .sad])
        for r in BuddyReaction.allCases { XCTAssertGreaterThanOrEqual(r.duration, 0.8); XCTAssertLessThanOrEqual(r.duration, 1.5) }
    }

    func testGrawlixDeterministicSymbolsOnly() {
        let a = BuddyWords.grawlix(seed: 7), b = BuddyWords.grawlix(seed: 7)
        XCTAssertEqual(a, b)
        XCTAssertEqual(a.count, 4)
        var distinct = Set<String>()
        for s in 0..<50 {
            let g = BuddyWords.grawlix(seed: s)
            XCTAssertTrue(g.allSatisfy { BuddyWords.grawlixSymbols.contains($0) }, g)
            distinct.insert(g)
        }
        XCTAssertGreaterThan(distinct.count, 10)
        XCTAssertEqual(BuddyWords.grawlix(seed: 1), Self.grawlixSeed1, "số chung với Android")
    }
    static let grawlixSeed1 = "%&*#"

    func testReactionStateMachine() {
        // Đang ngủ ⇒ giật mình thức, diễn xong ngủ tiếp (mốc ngủ giữ nguyên, không z z lại).
        var b = BuddyBrain()
        b.press(at: 0)
        XCTAssertEqual(b.phase(at: 10), .asleep(zzz: false))
        b.react(.love, at: 10)
        XCTAssertEqual(b.phase(at: 10.5), .react(.love))
        XCTAssertEqual(b.nextDeadline(after: 10.5)!, 11.2, accuracy: 1e-9)
        XCTAssertEqual(b.phase(at: 11.21), .asleep(zzz: false))
        XCTAssertNil(b.nextDeadline(after: 11.21))
        // Đang nhảy ⇒ phản ứng rồi về nhảy.
        var t = 20.0
        for _ in 0..<12 { b.press(at: t); t += 0.15 }
        b.react(.angry, at: t)
        XCTAssertEqual(b.phase(at: t + 0.1), .react(.angry))
        for k in 0..<10 { b.press(at: t + 0.15 * Double(k + 1)) }
        XCTAssertEqual(b.phase(at: t + 1.21), .dancing(b.tier))
        // Liên tiếp ⇒ diễn lại từ đầu (không chồng); MỚI NHẤT thắng (giận rồi thả tim ⇒ thả tim).
        let s0 = b.reactSerial
        b.react(.love, at: 50); b.react(.love, at: 50.5)
        XCTAssertEqual(b.reactSerial, s0 + 2)
        XCTAssertEqual(b.phase(at: 51.6), .react(.love))
        b.react(.angry, at: 51.65)
        XCTAssertEqual(b.phase(at: 51.7), .react(.angry))
        b.react(.love, at: 51.8)
        XCTAssertEqual(b.phase(at: 51.9), .react(.love))
        XCTAssertNotEqual(b.phase(at: 53.01), .react(.love))
        // Mới mở bàn phím (chưa gõ) cũng phản ứng được, xong ngủ yên.
        var c = BuddyBrain()
        c.react(.sad, at: 1)
        XCTAssertEqual(c.phase(at: 1.1), .react(.sad))
        XCTAssertEqual(c.phase(at: 2.6), .asleep(zzz: false))
        XCTAssertEqual(BuddyPolicy.visual(.react(.laugh), lowPower: true), .awake)
        for r in BuddyReaction.allCases where r != .panic {
            for s in [BuddyRig.girl.style, BuddyRig.cat.style] {
                XCTAssertEqual(BuddyDance.react(r, s, f: 0), .rest, "\(r) bắt đầu ở dáng nghỉ")
                XCTAssertEqual(BuddyDance.react(r, s, f: 1).armL, 0, accuracy: 1e-9, "\(r) về dáng nghỉ")
            }
        }
        XCTAssertLessThan(BuddyDance.react(.love, BuddyRig.girl.style, f: 0.5).armL, 0, "tay khép ôm tim")
        XCTAssertGreaterThan(BuddyDance.react(.panic, BuddyRig.girl.style, f: 0.5).armL, 2, "ôm đầu")
    }

    /// Xoá dồn: > 8 phím ⌫ liên tiếp ⇒ hoảng, kéo dài tới 0,8 s sau phím cuối (không diễn lại mỗi phím);
    /// phím chữ / ngắt quãng ⇒ đếm lại.
    func testDeletePanicCounter() {
        var b = BuddyBrain()
        var t = 5.0
        for i in 1...8 { b.delete(at: t); XCTAssertNotEqual(b.phase(at: t), .react(.panic), "\(i)"); t += 0.09 }
        b.delete(at: t)
        XCTAssertEqual(b.phase(at: t), .react(.panic))
        let serial = b.reactSerial
        for _ in 0..<20 { t += 0.09; b.delete(at: t) }
        XCTAssertEqual(b.reactSerial, serial, "giữ ⌫ không diễn lại mỗi phím")
        XCTAssertEqual(b.phase(at: t + 0.79), .react(.panic))
        XCTAssertNotEqual(b.phase(at: t + 0.81), .react(.panic), "thôi xoá 0,8 s ⇒ hết hoảng")
        // Phím chữ xen giữa ⇒ đếm lại.
        t += 5
        for _ in 0..<6 { b.delete(at: t); t += 0.09 }
        b.press(at: t); t += 0.09
        for _ in 0..<6 { b.delete(at: t); t += 0.09 }
        XCTAssertNotEqual(b.phase(at: t - 0.09), .react(.panic))
        // Ngắt quãng > 0,5 s ⇒ đếm lại.
        t += 1
        for _ in 0..<6 { b.delete(at: t); t += 0.09 }
        t += 0.6
        for _ in 0..<6 { b.delete(at: t); t += 0.09 }
        XCTAssertNotEqual(b.phase(at: t - 0.09), .react(.panic))
    }

    func testFXParticlesCappedAndConfined() {
        let r = BuddyFX.particleSize / 2
        for re in BuddyReaction.allCases {
            XCTAssertLessThanOrEqual(BuddyFX.count(re), BuddyFX.maxParticles)
            for (w, h) in [(34.0, 30.0), (34.0, 40.0), (34.0, 46.0), (120.0, 96.0)] {
                for i in 0..<BuddyFX.count(re) {
                    let p = BuddyFX.particle(re, i, width: w, height: h)
                    for (x, y) in [(p.x0, p.y0), (p.x1, p.y1)] {
                        XCTAssertTrue(x - r >= 0 && x + r <= w && y - r >= 0 && y + r <= h, "\(re) \(w)×\(h) #\(i): \(p)")
                    }
                }
                if let b = BuddyFX.badge(re, width: w, height: h) {
                    XCTAssertTrue(b.x >= 0 && b.y >= 0 && b.x + b.w <= w && b.maxY <= h, "\(re) biển \(b)")
                }
            }
        }
        XCTAssertLessThanOrEqual(BuddyFX.maxParticles, 8)
        // Số chung với Android.
        let p = BuddyFX.particle(.love, 3, width: 34, height: 40)
        XCTAssertEqual(p.x1, 22.4, accuracy: 0.01)
        XCTAssertEqual(p.y1, 3.5, accuracy: 0.01)
    }

    @MainActor func testReactionViewRestartsWithoutStackingAndReturns() {
        let clock = Clock()
        let v = makeView(.cat, clock: clock)
        v.wordCommitted("bàn", prev: nil)
        v.tick()
        XCTAssertEqual(v.shown, .asleep(zzz: false), "từ thường: không gì")
        for w in ["yêu", "yêu", "haha", "buồn", "wow", "yêu"] {
            clock.t += 0.2
            v.wordCommitted(w, prev: nil)
            v.tick()
            guard case .react = v.shown else { return XCTFail(w) }
            XCTAssertLessThanOrEqual(v.fxParticles.count, BuddyFX.maxParticles)
            for h in v.fxParticles { XCTAssertLessThanOrEqual(h.animationKeys()?.count ?? 0, 1, "không chồng") }
        }
        XCTAssertTrue(v.heartPropVisible)
        let layers = v.layerCount
        clock.t += 1.3; v.tick()
        XCTAssertEqual(v.shown, .asleep(zzz: false), "ngủ tiếp")
        XCTAssertFalse(v.heartPropVisible)
        XCTAssertEqual(v.runningAnimationCount, 0)
        XCTAssertEqual(v.layerCount, layers)
        for h in v.fxParticles { XCTAssertTrue(v.bounds.contains(h.frame), "hạt trong ô: \(h.frame)") }
        // Giận: mặt đỏ + bong bóng ký hiệu trong ô.
        let bad = SensitiveWords.set.sorted().first!
        clock.t += 1; v.wordCommitted(bad, prev: nil); v.tick()
        XCTAssertEqual(v.shown, .react(.angry))
        XCTAssertTrue(v.angryFaceVisible)
        let bubble = try! XCTUnwrap(v.fxBadgeFrame)
        XCTAssertTrue(v.bounds.contains(bubble), "bong bóng trong ô")
        XCTAssertTrue((v.fxBadgeText ?? "").allSatisfy { BuddyWords.grawlixSymbols.contains($0) })
        clock.t += 1.3; v.tick()
        XCTAssertFalse(v.angryFaceVisible)
        XCTAssertNil(v.fxBadgeText)
        XCTAssertEqual(v.runningAnimationCount, 0)
        // Hoảng khi xoá dồn rồi hết sau 0,8 s.
        for _ in 0..<12 { clock.t += 0.09; v.deletePressed() }
        v.tick()
        XCTAssertEqual(v.shown, .react(.panic))
        XCTAssertLessThanOrEqual(v.fxParticles.count, BuddyFX.maxParticles)
        clock.t += 0.85; v.tick()
        XCTAssertNotEqual(v.shown, .react(.panic))
        v.halt()
    }

    // MARK: Trang phục theo mùa

    func testSeasonResolution() {
        // Ngày cố định theo bảng âm lịch nhúng.
        XCTAssertEqual(BuddySeason.of(year: 2026, month: 2, day: 17), .tet)       // mùng 1 Tết Bính Ngọ
        XCTAssertEqual(BuddySeason.of(year: 2026, month: 2, day: 7), .tet)        // −10
        XCTAssertNil(BuddySeason.of(year: 2026, month: 2, day: 6))                // −11
        XCTAssertEqual(BuddySeason.of(year: 2026, month: 2, day: 24), .tet)       // +7
        XCTAssertNil(BuddySeason.of(year: 2026, month: 2, day: 25))
        XCTAssertEqual(BuddySeason.of(year: 2028, month: 1, day: 16), .tet)       // Tết 26/1/2028 −10
        XCTAssertEqual(BuddySeason.of(year: 2026, month: 9, day: 25), .midAutumn) // Rằm tháng Tám
        XCTAssertEqual(BuddySeason.of(year: 2026, month: 9, day: 20), .midAutumn)
        XCTAssertNil(BuddySeason.of(year: 2026, month: 9, day: 19))
        XCTAssertEqual(BuddySeason.of(year: 2028, month: 10, day: 8), .midAutumn) // 3/10/2028 +5
        XCTAssertEqual(BuddySeason.of(year: 2026, month: 12, day: 15), .christmas)
        XCTAssertEqual(BuddySeason.of(year: 2026, month: 12, day: 26), .christmas)
        XCTAssertNil(BuddySeason.of(year: 2026, month: 12, day: 27))
        XCTAssertEqual(BuddySeason.of(year: 2026, month: 10, day: 25), .halloween)
        XCTAssertEqual(BuddySeason.of(year: 2026, month: 11, day: 1), .halloween)
        XCTAssertNil(BuddySeason.of(year: 2026, month: 11, day: 2))
        XCTAssertNil(BuddySeason.of(year: 2026, month: 7, day: 1))
        XCTAssertEqual(BuddySeason.lunarNewYear.count, 10)
        XCTAssertEqual(BuddySeason.midAutumnDay.count, 10)
        XCTAssertEqual(BuddySeason.dayNumber(1970, 1, 1), 0)
        XCTAssertEqual(BuddySeason.dayNumber(2026, 10, 19), 20745)
    }

    func testOutfitByThemeThenDate() {
        XCTAssertEqual(BuddySeason.resolve(themeID: "tetMai", year: 2026, month: 7, day: 1), .tet, "theme thắng ngày")
        XCTAssertEqual(BuddySeason.resolve(themeID: "xmasSnow", year: 2026, month: 2, day: 17), .christmas)
        XCTAssertEqual(BuddySeason.resolve(themeID: "halloween", year: 2026, month: 7, day: 1), .halloween)
        XCTAssertEqual(BuddySeason.resolve(themeID: "trungthu", year: 2026, month: 7, day: 1), .midAutumn)
        XCTAssertEqual(BuddySeason.resolve(themeID: "system", year: 2026, month: 12, day: 20), .christmas, "theme thường ⇒ theo ngày")
        XCTAssertNil(BuddySeason.resolve(themeID: "oled", year: 2026, month: 7, day: 1))
        for c in BuddyCharacter.allCases where c != .off {
            XCTAssertTrue(BuddyOutfit.make(nil, c).pieces.isEmpty)
            for s in BuddySeason.allCases {
                let o = BuddyOutfit.make(s, c)
                XCTAssertFalse(o.pieces.isEmpty && o.recolor.isEmpty, "\(c) \(s) có trang phục")
            }
        }
        XCTAssertEqual(BuddyOutfit.make(.tet, .girl).recolor[0xFFB3C7], BuddyOutfit.red, "áo dài đỏ")
        XCTAssertTrue(BuddyOutfit.make(.midAutumn, .boy).pieces.allSatisfy { $0.part == .armR }, "lồng đèn theo tay")
    }

    @MainActor func testOutfitViewIsStaticNoExtraAnimations() {
        let clock = Clock()
        let plain = makeView(.girl, clock: clock)
        let v = BuddyView(character: .girl, bar: BuddyHalo.systemLight, lowPower: false, season: .christmas)
        v.clock = { clock.t }
        v.frame = plain.frame
        v.layoutIfNeeded()
        XCTAssertGreaterThan(v.outfitPieceCount, 0)
        XCTAssertEqual(v.layerCount, plain.layerCount + v.outfitPieceCount)
        XCTAssertEqual(v.runningAnimationCount, 0)
    }

    // MARK: Ảnh xem trước (chỉ khi đặt VT_BUDDY_SHOTS=<thư mục>)

    @MainActor func testSavePreviewShots() throws {
        guard let dir = ProcessInfo.processInfo.environment["VT_BUDDY_SHOTS"], !dir.isEmpty else {
            throw XCTSkip("đặt TEST_RUNNER_VT_BUDDY_SHOTS để lưu ảnh")
        }
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        // Nền thanh thật: tối (OLED, Kính tối, Neon) + sáng (Hệ thống sáng, Đào/pastel).
        let bars: [(String, KeyboardTheme, Bool)] = [("oled", .oled, true), ("glass-dark", .glass, true), ("neon", .neon, true),
                                                      ("system-light", .system, false), ("peach", .peach, false)]
        for (tag, theme, sysDark) in bars {
            let pal = theme.palette(systemDark: sysDark)
            let bar = pal.buddyBar
            for c in BuddyCharacter.allCases where c != .off {
                let style = BuddyRig.rig(c)!.style
                for (name, pose) in [("asleep", nil), ("dance", BuddyDance.pose(style, tier: 3, p: 0.125)),
                                     ("love", BuddyDance.react(.love, style, f: 0.5)),
                                     ("angry", BuddyDance.react(.angry, style, f: 0.3)),
                                     ("bar", BuddyDance.pose(style, tier: 2, p: 0.3))] as [(String, BuddyPose?)] {
                    let big = name != "bar"
                    let size = big ? CGSize(width: 200, height: 220) : CGSize(width: 34, height: 40)
                    let host = UIView(frame: CGRect(origin: .zero, size: size))
                    host.backgroundColor = UIColor(red: CGFloat((bar >> 16) & 0xFF) / 255, green: CGFloat((bar >> 8) & 0xFF) / 255,
                                                   blue: CGFloat(bar & 0xFF) / 255, alpha: 1)
                    let v = BuddyView(character: c, bar: bar, lowPower: false, framing: .full)
                    v.maxFigureHeight = big ? 200 : 44
                    v.frame = host.bounds
                    host.addSubview(v)
                    v.debugPose(pose, dance: name != "angry", heart: name == "love", angry: name == "angry")
                    let img = UIGraphicsImageRenderer(size: size, format: { let f = UIGraphicsImageRendererFormat(); f.scale = 3; return f }())
                        .image { ctx in host.layer.render(in: ctx.cgContext) }
                    try img.pngData()!.write(to: URL(fileURLWithPath: "\(dir)/\(tag)-\(c.rawValue)-\(name).png"))
                }
            }
        }
    }

    // MARK: Tương phản (Phil: chân mảnh lẫn vào nền tối)

    /// Mọi theme dựng sẵn (sáng + tối): quầng nhân vật tương phản ≥ 3:1 với nền thanh.
    func testHaloContrastOnEveryTheme() {
        for t in KeyboardTheme.allCases {
            for sysDark in [false, true] {
                let bar = t.palette(systemDark: sysDark).buddyBar
                let tones = BuddyHalo.colors(bar: bar)
                let c = BuddyHalo.contrast(tones.halo, bar)
                XCTAssertGreaterThanOrEqual(c, 3, "\(t.rawValue) \(sysDark ? "tối" : "sáng"): quầng \(String(tones.halo, radix: 16)) / nền \(String(bar, radix: 16)) = \(c)")
            }
        }
        XCTAssertTrue(BuddyHalo.colors(bar: 0x000000).darkBar)
        XCTAssertEqual(BuddyHalo.colors(bar: 0x000000).halo, BuddyHalo.light)
        XCTAssertEqual(BuddyHalo.colors(bar: 0xFFFFFF).halo, BuddyHalo.dark)
        XCTAssertEqual(BuddyHalo.colors(bar: 0xFFFFFF).outline, 0x3A2A2A)
    }

    /// Chân / tay là hình TÔ (không phải nét đậm trơn) và đủ dày ở cỡ thanh (~38 pt cao): ≥ 2,5 pt.
    func testLimbsAreFilledAndThickEnough() {
        for c in BuddyCharacter.allCases where c != .off {
            let r = BuddyRig.rig(c)!
            let pt = 38 / r.full.h
            for part in [BuddyPart.legL, .legR, .armL, .armR] {
                let ps = r.pieces.filter { $0.part == part }
                // Nét viền trơn phải đi cặp với nét màu cùng đường (chi = viền + ruột màu).
                for p in ps where p.fill == nil && p.stroke == .outline {
                    guard case .path(let d) = p.shape else { continue }
                    XCTAssertTrue(ps.contains { q in
                        if case .path(let e) = q.shape, e == d, case .color = q.stroke { return true }
                        return false
                    }, "\(c) \(part): nét đậm trơn \(d)")
                }
                var thick = 0.0
                for p in ps {
                    if p.fill == nil { thick = max(thick, p.width) }
                    else if case .path(let d) = p.shape {
                        let n = BuddySVG.numbers(d)
                        let xs = stride(from: 0, to: n.count - 1, by: 2).map { n[$0] }, ys = stride(from: 1, to: n.count, by: 2).map { n[$0] }
                        if let a = xs.min(), let b = xs.max(), let y0 = ys.min(), let y1 = ys.max(), y1 - y0 > 30 {
                            thick = max(thick, min(b - a, y1 - y0) * 0.6)   // ống quần: bề ngang trung bình ≈ 0,6 hộp
                        }
                    }
                }
                XCTAssertGreaterThanOrEqual(thick * pt, 2.5, "\(c) \(part): \(thick * pt) pt")
            }
        }
    }
}
