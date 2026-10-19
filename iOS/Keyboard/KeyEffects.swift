// "Hiệu ứng khi bấm phím" (Plus, Phil 19/10/2026 — kiểu Laban Key): Trái tim, Bong bóng, Gợn sóng,
// Cánh hoa, Pháo hoa; "Không" = mặc định. Song sinh android/keyboard/.../KeyEffect.kt (cùng khoá
// `keyEffect`, cùng id, cùng quỹ đạo).
//
// Luật hiệu năng (feature-flags-perf-policy — gõ mượt là số 1):
//  • TẮT (mặc định) / chưa có Plus / Chế độ nguồn điện thấp ⇒ KeyboardView không
//    tạo overlay (`keyEffects == nil`) — đường phím chỉ đọc một biến optional.
//  • BẬT: chữ chèn TRƯỚC (KeyboardView.tapped → onKey), overlay chỉ ghi toạ độ rồi hẹn MỘT lượt
//    DispatchQueue.main.async — lượt vẽ chạy sau khi xử lý xong sự kiện chạm.
//  • Chỉ Core Animation, path vector: mỗi lần bấm bắn lại MỘT "chùm" dựng sẵn (animation gắn một lần,
//    bấm chỉ đổi position + beginTime của chùm — render server chạy phần còn lại, không timer/display
//    link). Chùm xoay vòng (`burstSlots`, tổng ≤ KeyEffectPool.cap hạt): gõ nhanh ⇒ chùm cũ nhất bị thay.
//  • Overlay không nhận chạm, nằm trên phím, dưới ô phóng to (BalloonView zPosition 10).
import UIKit

/// Kiểu hiệu ứng (giá trị lưu App Group / sao lưu = rawValue).
enum KeyEffect: String, CaseIterable {
    case none, heart, bubble, ripple, petal, firework

    static let key = "keyEffect"
    static let defaultValue: KeyEffect = .none

    /// Giá trị lạ / thiếu ⇒ Không.
    static func parse(_ raw: Any?) -> KeyEffect {
        (raw as? String).flatMap(KeyEffect.init(rawValue:)) ?? defaultValue
    }
    static func load(_ d: UserDefaults?) -> KeyEffect { parse(d?.object(forKey: key)) }

    var title: String {
        switch self {
        case .none: return L("Không")
        case .heart: return L("Trái tim")
        case .bubble: return L("Bong bóng")
        case .ripple: return L("Gợn sóng")
        case .petal: return L("Cánh hoa")
        case .firework: return L("Pháo hoa")
        }
    }

    /// Mọi kiểu trừ "Không" thuộc Plus.
    var isPlus: Bool { self != .none }

    /// Số hạt mỗi lần bấm.
    var particlesPerTap: Int {
        switch self {
        case .none: return 0
        case .heart, .bubble, .petal: return 3
        case .ripple: return 1
        case .firework: return 6
        }
    }

    /// Số chùm xoay vòng của overlay (mỗi lần bấm một chùm) — tổng hạt ≤ KeyEffectPool.cap; gõ nhanh
    /// hơn số chùm thì chùm cũ nhất bị bắn lại.
    var burstSlots: Int {
        guard particlesPerTap > 0 else { return 1 }
        return max(1, min(8, KeyEffectPool.cap / particlesPerTap))
    }

    /// Số quỹ đạo dựng sẵn (bội của particlesPerTap): hạt i của lần bấm dùng quỹ đạo
    /// `i * stride + seed % stride` — trái/giữa/phải (tim) hay toả đều (pháo hoa) nhưng mỗi lần lệch chút.
    var variantCount: Int { particlesPerTap * 2 }

    /// Quỹ đạo thứ `v` (pt, giây, radian) — hàm thuần, Android dùng y hệt.
    func trajectory(_ v: Int) -> KeyEffectTrajectory {
        let n = max(variantCount, 1)
        let t = n > 1 ? (Double(v) - Double(n - 1) / 2) / (Double(n - 1) / 2) : 0   // −1…1
        switch self {
        case .none:
            return KeyEffectTrajectory(dx: 0, dy: 0, scaleFrom: 1, scaleTo: 1, rotation: 0, duration: 0)
        case .heart:
            return KeyEffectTrajectory(dx: t * 24, dy: -(46 + Double(v % 3) * 7), scaleFrom: 0.5,
                                       scaleTo: 1.0 + Double(v % 2) * 0.15, rotation: t * 0.35,
                                       duration: 0.8 + Double(v % 3) * 0.05)
        case .bubble:
            return KeyEffectTrajectory(dx: t * 16, dy: -(52 + Double(v % 3) * 8), scaleFrom: 0.4,
                                       scaleTo: 1.0 + Double(v % 2) * 0.2, rotation: 0,
                                       duration: 0.9 + Double(v % 2) * 0.1)
        case .ripple:
            return KeyEffectTrajectory(dx: 0, dy: 0, scaleFrom: 0.35, scaleTo: 1.6 + Double(v % 2) * 0.2,
                                       rotation: 0, duration: 0.45)
        case .petal:
            return KeyEffectTrajectory(dx: t * 34, dy: 12 + Double(v % 3) * 8, scaleFrom: 0.7, scaleTo: 1.0,
                                       rotation: t * 2.4 + 0.6, duration: 0.95)
        case .firework:
            let a = Double(v) * 2 * Double.pi / Double(n)
            let d = 30.0 + Double(v % 2) * 6
            return KeyEffectTrajectory(dx: cos(a) * d, dy: sin(a) * d, scaleFrom: 1.0, scaleTo: 0.4,
                                       rotation: 0, duration: 0.55)
        }
    }

    /// Quỹ đạo của hạt `i` trong lần bấm số `seed`.
    func variant(particle i: Int, seed: Int) -> Int {
        let stride = max(variantCount / max(particlesPerTap, 1), 1)
        return (i * stride + abs(seed) % stride) % max(variantCount, 1)
    }
}

struct KeyEffectTrajectory: Equatable {
    var dx: Double, dy: Double
    var scaleFrom: Double, scaleTo: Double
    var rotation: Double
    var duration: Double
}

/// Cổng Plus: hiệu ứng đi cùng quyền theme cao cấp (như ảnh nền / tuỳ chỉnh nâng cao).
enum KeyEffectGate {
    static var allows: Bool { PlusGate.isUnlocked(.premiumThemes) }
    static func allows(_ e: KeyEffect) -> Bool { !e.isPlus || allows }
}

/// Kiểu thực chạy — hàm thuần (test): tắt / hết Plus / tiết kiệm pin ⇒ Không.
enum KeyEffectPolicy {
    /// Giảm chuyển động KHÔNG tắt hiệu ứng (Phil 19/10/2026: người bật Giảm chuyển động vẫn muốn
    /// thấy hiệu ứng mình đã chọn) — chỉ hết Plus / nguồn điện thấp.
    static func active(setting: KeyEffect, plus: Bool, lowPower: Bool) -> KeyEffect {
        guard setting != .none, plus, !lowPower else { return .none }
        return setting
    }

    /// Đọc lúc hiện bàn phím (applyAppearance) — không mỗi phím. Cài đặt "Không" ⇒ không hỏi gì thêm.
    static func current(_ d: UserDefaults?) -> KeyEffect {
        let s = KeyEffect.load(d)
        guard s != .none else { return .none }
        return active(setting: s, plus: KeyEffectGate.allows(s),
                      lowPower: ProcessInfo.processInfo.isLowPowerModeEnabled)
    }
}

/// Cấp ô pool xoay vòng: đủ `cap` hạt đang bay thì hạt cũ nhất bị thay (gõ nhanh không dồn hạt).
struct KeyEffectPool {
    static let cap = 20
    let capacity: Int
    private(set) var cursor = 0
    init(capacity: Int = KeyEffectPool.cap) { self.capacity = max(1, capacity) }
    mutating func next() -> Int {
        let i = cursor
        cursor = (cursor + 1) % capacity
        return i
    }
}

/// Lớp phủ hiệu ứng — một UIView không nhận chạm, phủ trọn KeyboardView.
///
/// Mỗi lần bấm = MỘT "chùm" (CALayer chứa `particlesPerTap` hạt). Animation của từng hạt gắn MỘT lần
/// lúc dựng chùm (không bị gỡ, fill cả hai đầu); bấm phím chỉ đặt `position` + `beginTime` của chùm
/// ⇒ cả cây thời gian chạy lại từ đầu trên render server. Main thread mỗi phím: 2 thuộc tính trên một
/// layer, không tạo animation / layer mới. Có `burstSlots` chùm xoay vòng (≤ KeyEffectPool.cap hạt).
final class KeyEffectOverlay: UIView {
    private(set) var effect: KeyEffect
    private var color: UIColor
    private var bursts: [CALayer] = []
    private var pool = KeyEffectPool(capacity: 1)
    private var path: CGPath?
    /// Điểm chờ vẽ (gõ chồng trong cùng lượt runloop) — giữ tối đa `maxPending` điểm mới nhất.
    private var pending: [CGPoint] = []
    private static let maxPending = 4
    private var scheduled = false
    /// beginTime cục bộ của animation hạt (> 0: CA không tự gán "bây giờ" lúc thêm).
    private static let animBegin: CFTimeInterval = 1e-3

    init(effect: KeyEffect, color: UIColor) {
        self.effect = effect
        self.color = color
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        accessibilityElementsHidden = true
        backgroundColor = .clear
        isOpaque = false
        // Trên phím (zPosition 0), dưới ô phóng to (BalloonView 10) và popup (11).
        layer.zPosition = 5
        pending.reserveCapacity(Self.maxPending)
        rebuild()
    }
    required init?(coder: NSCoder) { fatalError() }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool { false }

    /// Gắn / cập nhật / gỡ overlay của `host`. Kiểu "Không" ⇒ gỡ và trả nil (0 chi phí).
    static func install(_ effect: KeyEffect, color: UIColor, in host: UIView,
                        existing: KeyEffectOverlay?) -> KeyEffectOverlay? {
        guard effect != .none else {
            existing?.removeFromSuperview()
            return nil
        }
        let o = existing ?? KeyEffectOverlay(effect: effect, color: color)
        o.configure(effect: effect, color: color)
        if o.superview !== host {
            o.frame = host.bounds
            o.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            host.addSubview(o)
        }
        return o
    }

    func configure(effect: KeyEffect, color: UIColor) {
        guard effect != self.effect || !color.isEqual(self.color) else { return }
        self.effect = effect
        self.color = color
        rebuild()
    }

    /// Ghi điểm (toạ độ overlay) — vẽ ở lượt runloop kế, SAU khi phím đã chèn chữ.
    func emit(at p: CGPoint) {
        #if DEBUG
        let t0 = CACurrentMediaTime()
        defer { Self.debugCost += CACurrentMediaTime() - t0 }
        #endif
        if pending.count >= Self.maxPending { pending.removeFirst() }
        pending.append(p)
        guard !scheduled else { return }
        scheduled = true
        DispatchQueue.main.async { [weak self] in self?.flush() }
    }

    /// Vẽ các điểm đang chờ (test gọi thẳng).
    func flush() {
        #if DEBUG
        let t0 = CACurrentMediaTime()
        defer { Self.debugCost += CACurrentMediaTime() - t0 }
        #endif
        scheduled = false
        guard !pending.isEmpty, effect != .none, window != nil || Self.drawsOffscreen else {
            pending.removeAll(keepingCapacity: true)
            return
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let now = layer.convertTime(CACurrentMediaTime(), from: nil)
        for p in pending { fire(at: p, now: now) }
        CATransaction.commit()
        pending.removeAll(keepingCapacity: true)
    }

    #if DEBUG
    /// Test: vẽ cả khi chưa gắn vào window (test không có window thật).
    nonisolated(unsafe) static var drawsOffscreen = false
    /// Đo: tổng thời gian main thread trong emit + flush (giây) — bench cộng dồn mỗi phím.
    nonisolated(unsafe) static var debugCost: CFTimeInterval = 0
    /// Số chùm đã dựng / tổng số hạt (layer hạt) đang có.
    var debugBurstCount: Int { bursts.count }
    var debugParticleCount: Int { bursts.reduce(0) { $0 + ($1.sublayers?.count ?? 0) } }
    var debugPendingCount: Int { pending.count }
    var debugScheduled: Bool { scheduled }
    /// Vị trí các chùm đã bắn (toạ độ overlay).
    var debugBurstPositions: [CGPoint] { bursts.map(\.position) }
    #else
    static let drawsOffscreen = false
    #endif

    private func fire(at p: CGPoint, now: CFTimeInterval) {
        let slot = pool.next()
        let b = slot < bursts.count ? bursts[slot] : makeBurst(index: slot)
        b.position = p
        b.beginTime = now      // chạy lại animation cả chùm từ đầu (thời gian cục bộ = 0)
        b.isHidden = false
    }

    /// Một chùm: `particlesPerTap` hạt, mỗi hạt một quỹ đạo (lệch theo số chùm), animation gắn sẵn.
    private func makeBurst(index: Int) -> CALayer {
        let b = CALayer()
        b.bounds = .zero
        b.isHidden = true
        for i in 0..<effect.particlesPerTap {
            let l = CAShapeLayer()
            style(l)
            l.position = .zero
            l.opacity = 0
            l.add(Self.makeAnimation(effect.trajectory(effect.variant(particle: i, seed: index + 1))), forKey: "fx")
            b.addSublayer(l)
        }
        layer.addSublayer(b)
        bursts.append(b)
        return b
    }

    private func style(_ l: CAShapeLayer) {
        l.path = path
        l.bounds = .zero
        l.contentsScale = UIScreen.main.scale
        switch effect {
        case .bubble:
            l.fillColor = color.withAlphaComponent(0.18).cgColor
            l.strokeColor = color.cgColor
            l.lineWidth = 1.5
        case .ripple:
            l.fillColor = nil
            l.strokeColor = color.withAlphaComponent(0.8).cgColor
            l.lineWidth = 2
        default:
            l.fillColor = color.cgColor
            l.strokeColor = nil
            l.lineWidth = 0
        }
    }

    /// Đổi kiểu / màu (lúc hiện bàn phím): bỏ các chùm cũ, dựng lại lười ở lần bấm kế.
    private func rebuild() {
        path = KeyEffectShapes.path(effect)
        pool = KeyEffectPool(capacity: effect.burstSlots)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for b in bursts { b.removeFromSuperlayer() }
        CATransaction.commit()
        bursts.removeAll()
    }

    private static func makeAnimation(_ t: KeyEffectTrajectory) -> CAAnimationGroup {
        let move = CABasicAnimation(keyPath: "position")
        move.isAdditive = true
        move.fromValue = NSValue(cgPoint: .zero)
        move.toValue = NSValue(cgPoint: CGPoint(x: t.dx, y: t.dy))
        move.timingFunction = CAMediaTimingFunction(name: .easeOut)
        let scale = CABasicAnimation(keyPath: "transform.scale")
        scale.fromValue = t.scaleFrom
        scale.toValue = t.scaleTo
        let fade = CAKeyframeAnimation(keyPath: "opacity")
        fade.values = [1, 1, 0]
        fade.keyTimes = [0, 0.5, 1]
        var parts: [CAAnimation] = [move, scale, fade]
        if t.rotation != 0 {
            let spin = CABasicAnimation(keyPath: "transform.rotation.z")
            spin.fromValue = 0
            spin.toValue = t.rotation
            parts.append(spin)
        }
        let g = CAAnimationGroup()
        g.animations = parts
        g.duration = t.duration
        g.beginTime = animBegin
        // Giữ animation (không gỡ khi xong): chùm chỉ cần đổi beginTime là chạy lại; xong thì đứng ở
        // khung cuối (opacity 0 — vô hình, render server không vẽ gì).
        g.fillMode = .both
        g.isRemovedOnCompletion = false
        return g
    }
}

/// Hình vector của từng kiểu (toạ độ quanh gốc 0,0 — layer đặt position là tâm).
enum KeyEffectShapes {
    static func path(_ e: KeyEffect) -> CGPath? {
        switch e {
        case .none: return nil
        case .heart: return heart(8)
        case .bubble: return CGPath(ellipseIn: CGRect(x: -7, y: -7, width: 14, height: 14), transform: nil)
        case .ripple: return CGPath(ellipseIn: CGRect(x: -20, y: -20, width: 40, height: 40), transform: nil)
        case .petal: return petal(8)
        case .firework: return star(5, inner: 1.6)
        }
    }

    static func heart(_ s: CGFloat) -> CGPath {
        let p = CGMutablePath()
        p.move(to: CGPoint(x: 0, y: s * 0.9))
        p.addCurve(to: CGPoint(x: -s, y: -s * 0.2), control1: CGPoint(x: -s * 0.6, y: s * 0.5),
                   control2: CGPoint(x: -s, y: s * 0.15))
        p.addCurve(to: CGPoint(x: 0, y: -s * 0.55), control1: CGPoint(x: -s, y: -s * 0.85),
                   control2: CGPoint(x: -s * 0.2, y: -s * 0.95))
        p.addCurve(to: CGPoint(x: s, y: -s * 0.2), control1: CGPoint(x: s * 0.2, y: -s * 0.95),
                   control2: CGPoint(x: s, y: -s * 0.85))
        p.addCurve(to: CGPoint(x: 0, y: s * 0.9), control1: CGPoint(x: s, y: s * 0.15),
                   control2: CGPoint(x: s * 0.6, y: s * 0.5))
        p.closeSubpath()
        return p
    }

    static func petal(_ s: CGFloat) -> CGPath {
        let p = CGMutablePath()
        p.move(to: CGPoint(x: 0, y: -s))
        p.addQuadCurve(to: CGPoint(x: 0, y: s), control: CGPoint(x: s * 0.9, y: -s * 0.1))
        p.addQuadCurve(to: CGPoint(x: 0, y: -s), control: CGPoint(x: -s * 0.9, y: -s * 0.1))
        p.closeSubpath()
        return p
    }

    static func star(_ r: CGFloat, inner: CGFloat) -> CGPath {
        let p = CGMutablePath()
        for k in 0..<8 {
            let a = CGFloat(k) * .pi / 4 - .pi / 2
            let rr = k % 2 == 0 ? r : inner
            let pt = CGPoint(x: cos(a) * rr, y: sin(a) * rr)
            if k == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        p.closeSubpath()
        return p
    }
}
