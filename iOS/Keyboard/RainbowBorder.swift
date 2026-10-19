// Viền phím cầu vồng — theme "Gaming RGB" (slot "borderFx":"rainbow", Phil 19/10/2026).
//
// MỘT lớp gradient (dải cầu vồng lặp 2 chu kỳ, rộng gấp đôi bàn phím) bị CHE bởi MỘT CAShapeLayer là hình viền
// của mọi phím (path dựng lại chỉ khi khung phím đổi — không mỗi phím). "Gợn sóng RGB": dịch dải gradient theo
// trục x (transform.translation.x) bằng CABasicAnimation ⇒ render server / GPU chạy, main thread không làm gì
// mỗi khung. Không animation từng phím.
//
// Luật hiệu năng (feature-flags-perf-policy): chỉ chạy KHI ĐANG GÕ. Phím đầu tiên gắn animation; nghỉ
// `idleAfter` giây ⇒ đọc vị trí đang hiện, ghi vào model rồi GỠ animation ⇒ đứng yên ở cầu vồng tĩnh, 0 animation,
// 0 CPU khi nghỉ. Mỗi phím chỉ ghi một mốc thời gian (+ hẹn MỘT lượt kiểm nghỉ nếu chưa hẹn). Theme khác ⇒ view
// không tồn tại (KeyboardView.rainbow == nil). Android RainbowBorderView y hệt (cùng hằng số / màu / đồng hồ).
import UIKit

/// Hằng số + hàm thuần dùng chung hai nền tảng (Android RainbowBorder cùng số — test chéo).
enum RainbowBorder {
    /// Một chu kỳ cầu vồng trượt hết một bề ngang bàn phím (giây).
    static let period = 4.0
    /// Nghỉ gõ chừng này ⇒ đứng yên (giây).
    static let idleAfter = 1.2
    /// Độ dày viền (pt / dp).
    static let lineWidth = 1.5
    /// Một chu kỳ màu (đỏ → cam → vàng → lục → lam → chàm → tím → đỏ); dải gradient lặp HAI lần (0…1 = 2 chu kỳ)
    /// để dịch đúng một bề ngang là liền mạch.
    static let hues: [UInt32] = [0xFF3B30, 0xFF9500, 0xFFCC00, 0x34C759, 0x00C7BE, 0x007AFF, 0xAF52DE, 0xFF3B30]

    /// Màu + vị trí của dải 2 chu kỳ (bỏ điểm trùng ở giữa).
    static func stops() -> [(color: UInt32, location: Double)] {
        let n = hues.count - 1
        var out: [(color: UInt32, location: Double)] = []
        for cycle in 0..<2 {
            for i in 0..<n { out.append((hues[i], (Double(cycle) + Double(i) / Double(n)) / 2)) }
        }
        out.append((hues[n], 1))
        return out
    }

    /// Độ lệch (0…1 bề ngang) sau `elapsed` giây chạy kể từ lệch `from` — tuần hoàn, luôn trong [0, 1).
    static func offset(from: Double, elapsed: Double) -> Double {
        let v = (from + elapsed / period).truncatingRemainder(dividingBy: 1)
        return v < 0 ? v + 1 : v
    }
}

/// Đồng hồ "chỉ chạy khi đang gõ" (thuần — test được; Android RainbowClock y hệt).
struct RainbowClock {
    var idleAfter = RainbowBorder.idleAfter
    private(set) var running = false
    private(set) var lastInput = -Double.infinity

    /// Một phím lúc `t`; true = vừa chuyển nghỉ → chạy (gắn animation).
    mutating func poke(at t: Double) -> Bool {
        lastInput = t
        if running { return false }
        running = true
        return true
    }
    /// Còn bao lâu nữa thì nghỉ (≤ 0 ⇒ nghỉ ngay).
    func idleRemaining(at t: Double) -> Double { idleAfter - (t - lastInput) }
    mutating func stop() { running = false }
}

/// Lớp viền cầu vồng phủ trên hàng phím (không nhận chạm). Chỉ tồn tại khi theme có "borderFx":"rainbow".
final class RainbowBorderView: UIView {
    private let strip = CAGradientLayer()
    private let outline = CAShapeLayer()
    private var clock = RainbowClock()
    private var idleScheduled = false
    /// Độ lệch hiện tại (0…1 bề ngang) — model khi đứng yên.
    private(set) var phase = 0.0
    private var runStart: CFTimeInterval = 0
    private var outlines: [CGRect] = []
    private var radii: [CGFloat] = []
    private static let animKey = "rgb"

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        accessibilityElementsHidden = true
        backgroundColor = .clear
        isOpaque = false
        let st = RainbowBorder.stops()
        strip.colors = st.map { UIColor(rgbHex: $0.color).cgColor }
        strip.locations = st.map { NSNumber(value: $0.location) }
        strip.startPoint = CGPoint(x: 0, y: 0.5)
        strip.endPoint = CGPoint(x: 1, y: 0.5)
        strip.anchorPoint = .zero
        outline.fillColor = nil
        outline.strokeColor = UIColor.black.cgColor
        outline.lineWidth = CGFloat(RainbowBorder.lineWidth)
        layer.addSublayer(strip)
        layer.mask = outline
    }
    required init?(coder: NSCoder) { fatalError() }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool { false }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        outline.frame = bounds
        strip.bounds = CGRect(x: 0, y: 0, width: bounds.width * 2, height: bounds.height)
        strip.position = .zero
        if !clock.running { applyPhase() }
        CATransaction.commit()
        if clock.running { restartAnimation() }   // đổi bề ngang giữa lúc gõ: dải mới, chạy tiếp từ phase
    }

    /// Khung phím (toạ độ view này) + bán kính bo — path chỉ dựng lại khi khác lần trước.
    func setKeys(_ frames: [CGRect], radii: [CGFloat]) {
        guard frames != outlines || radii != self.radii else { return }
        outlines = frames; self.radii = radii
        let p = CGMutablePath()
        let h = CGFloat(RainbowBorder.lineWidth) / 2
        for (f, r) in zip(frames, radii) where f.width > 2 * h && f.height > 2 * h {
            let rr = max(0, min(r - h, min(f.width, f.height) / 2 - h))
            p.addRoundedRect(in: f.insetBy(dx: h, dy: h), cornerWidth: rr, cornerHeight: rr)
        }
        CATransaction.begin(); CATransaction.setDisableActions(true)
        outline.path = p
        CATransaction.commit()
    }

    /// Một phím vừa gõ (sau khi chữ đã chèn). Nghỉ → chạy: gắn animation MỘT lần. Đang chạy: chỉ ghi giờ.
    func poke() {
        #if DEBUG
        let t0 = CACurrentMediaTime()
        defer { Self.debugCost += CACurrentMediaTime() - t0 }
        #endif
        let now = CACurrentMediaTime()
        if clock.poke(at: now) {
            runStart = now
            restartAnimation()
        }
        scheduleIdleCheck(after: clock.idleRemaining(at: now))
    }

    /// Ẩn bàn phím / đổi theme: đứng yên ngay (không còn animation).
    func halt() {
        guard clock.running else { return }
        freeze()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { halt() }
    }

    private func scheduleIdleCheck(after s: Double) {
        guard !idleScheduled else { return }
        idleScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + max(0.05, s)) { [weak self] in
            guard let self else { return }
            self.idleScheduled = false
            guard self.clock.running else { return }
            let left = self.clock.idleRemaining(at: CACurrentMediaTime())
            if left > 0 { self.scheduleIdleCheck(after: left) } else { self.freeze() }
        }
    }

    /// Nghỉ: lấy vị trí đang hiện (theo thời gian đã chạy — cùng công thức với animation), ghi vào model, gỡ animation.
    private func freeze() {
        phase = RainbowBorder.offset(from: phase, elapsed: CACurrentMediaTime() - runStart)
        clock.stop()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        strip.removeAnimation(forKey: Self.animKey)
        applyPhase()
        CATransaction.commit()
    }

    private func applyPhase() {
        strip.setAffineTransform(CGAffineTransform(translationX: -CGFloat(phase) * bounds.width, y: 0))
    }

    /// Dịch dải từ phase hiện tại đi đúng một bề ngang, lặp mãi (liền mạch vì dải lặp 2 chu kỳ).
    private func restartAnimation() {
        let w = Double(bounds.width)
        guard w > 0 else { return }
        if strip.animation(forKey: Self.animKey) != nil {
            phase = RainbowBorder.offset(from: phase, elapsed: CACurrentMediaTime() - runStart)
            runStart = CACurrentMediaTime()
        }
        let a = CABasicAnimation(keyPath: "transform.translation.x")
        a.fromValue = -phase * w
        a.toValue = -(phase + 1) * w
        a.duration = RainbowBorder.period
        a.repeatCount = .infinity
        a.timingFunction = CAMediaTimingFunction(name: .linear)
        a.isRemovedOnCompletion = false
        strip.add(a, forKey: Self.animKey)
    }

    #if DEBUG
    /// Đo: tổng thời gian main thread trong poke (giây).
    nonisolated(unsafe) static var debugCost: CFTimeInterval = 0
    var debugRunning: Bool { clock.running }
    var debugAnimationCount: Int { strip.animationKeys()?.count ?? 0 }
    var debugMaskPath: CGPath? { outline.path }
    var debugStrip: CAGradientLayer { strip }
    func debugSetIdle(_ s: Double) { clock.idleAfter = s }
    /// Ảnh chụp (test không có window: layer.render bỏ qua mask) — vẽ tay: dải gradient ở độ lệch hiện tại, cắt theo
    /// nét viền. Toạ độ = toạ độ view này.
    func debugDraw(in c: CGContext) {
        guard let path = outline.path, bounds.width > 0 else { return }
        c.saveGState()
        c.addPath(path.copy(strokingWithWidth: outline.lineWidth, lineCap: .butt, lineJoin: .miter, miterLimit: 10))
        c.clip()
        let st = RainbowBorder.stops()
        let cols = st.map { UIColor(rgbHex: $0.color).cgColor } as CFArray
        let locs = st.map { CGFloat($0.location) }
        if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: cols, locations: locs) {
            let x0 = -CGFloat(phase) * bounds.width
            c.drawLinearGradient(g, start: CGPoint(x: x0, y: 0), end: CGPoint(x: x0 + 2 * bounds.width, y: 0), options: [])
        }
        c.restoreGState()
    }
    #endif
}

private extension UIColor {
    convenience init(rgbHex v: UInt32) {
        self.init(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                  blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
}
