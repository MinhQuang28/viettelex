// Lớp vẽ "Đập chuột" (MoleGame.swift) — chỉ tồn tại khi theme đang dùng có "game":"mole" (KeyboardView.mole == nil
// với mọi theme khác ⇒ 0 lớp, đường phím chỉ đọc một optional).
//
// • KHÔNG nhận chạm (isUserInteractionEnabled = false, hitTest nil): phím dưới chuột vẫn gõ y như thường.
// • Lớp dựng MỘT lần (pool): hố + chuột (tai, đầu, mắt, mũi, má, râu — vector tự vẽ) + mép hố trong một hộp cắt
//   theo khung phím (chuột trồi lên từ đáy phím như từ hang), 3 ngôi sao + nhãn điểm ngoài hộp. Path chỉ dựng lại
//   khi cỡ phím đổi. Mọi chuyển động là animation Core Animation (render server / GPU), không timer khung hình.
// • Mỗi phím: `typed` = MoleGame.key (O(1)). Thả chuột / đập trúng / thụt: dồn sang DispatchQueue.main.async (sau
//   khi phím đã chèn chữ). Đồng hồ: MỘT asyncAfter mỗi con (upTime).
import UIKit

final class MoleOverlayView: UIView {
    /// Khung phím chữ `c` (toạ độ overlay) + bán kính bo. nil ⇒ không ở bảng chữ (không thả).
    var keyFrame: ((Character) -> (rect: CGRect, radius: CGFloat)?)?
    /// Chữ đang soạn + ứng viên trên thanh gợi ý — CHỈ gọi lúc thả chuột.
    var context: (() -> (prefix: String, candidates: [String]))?
    /// Ghi thống kê (mặc định App Group). Test thay.
    var statsDefaults: UserDefaults? = UserDefaultsProvider.shared

    private(set) var game: MoleGame
    private var generation = 0

    // Pool lớp.
    private let holder = CALayer()            // cắt theo khung phím
    private let hole = CAShapeLayer()
    private let mouse = CALayer()
    private let ears = CAShapeLayer(), earsInner = CAShapeLayer()
    private let head = CAShapeLayer(), cheeks = CAShapeLayer()
    private let eyes = CAShapeLayer(), glints = CAShapeLayer(), nose = CAShapeLayer(), whiskers = CAShapeLayer()
    private let lip = CAShapeLayer()           // mép hố phía trước (che chân đầu chuột)
    private let stars: [CAShapeLayer] = (0..<3).map { _ in CAShapeLayer() }
    private let badge = CATextLayer()
    private var builtSize: CGSize = .zero
    private var upY: CGFloat = 0, downY: CGFloat = 0

    #if DEBUG
    /// Thời gian (giây) của lần `typed` gần nhất — đo chi phí mỗi phím.
    nonisolated(unsafe) static var debugCost: Double = 0
    private(set) var debugSpawnKey: Character?
    private(set) var debugBonks = 0
    private(set) var debugSqueaks = 0
    #endif

    static let colors = (fur: 0xA9A3B5 as UInt32, furDark: 0x8C8699 as UInt32, pink: 0xF4A6B8 as UInt32,
                         nose: 0xEE7396 as UInt32, eye: 0x1E1A22 as UInt32, holeFill: 0x2A170B as UInt32,
                         lip: 0x5A3A22 as UInt32, star: 0xFFD54F as UInt32, starEdge: 0xE0A000 as UInt32)

    init(seed: Int = Int(Date().timeIntervalSince1970)) {
        game = MoleGame(seed: seed)
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        accessibilityElementsHidden = true
        backgroundColor = .clear
        holder.masksToBounds = true
        holder.isHidden = true
        layer.addSublayer(holder)
        holder.addSublayer(hole)
        holder.addSublayer(mouse)
        for l in [ears, earsInner, head, cheeks, eyes, glints, nose, whiskers] { mouse.addSublayer(l) }
        holder.addSublayer(lip)
        func ui(_ v: UInt32, _ a: CGFloat = 1) -> CGColor { UIColor(moleRGB: v).withAlphaComponent(a).cgColor }
        hole.fillColor = ui(Self.colors.holeFill, 0.9)
        lip.fillColor = ui(Self.colors.lip)
        ears.fillColor = ui(Self.colors.furDark)
        earsInner.fillColor = ui(Self.colors.pink)
        head.fillColor = ui(Self.colors.fur)
        cheeks.fillColor = ui(Self.colors.pink, 0.55)
        eyes.fillColor = ui(Self.colors.eye)
        glints.fillColor = UIColor.white.cgColor
        nose.fillColor = ui(Self.colors.nose)
        whiskers.strokeColor = ui(0x4A4452, 0.8)
        whiskers.fillColor = nil
        whiskers.lineCap = .round
        for s in stars {
            s.fillColor = ui(Self.colors.star)
            s.strokeColor = ui(Self.colors.starEdge)
            s.lineWidth = 1
            s.opacity = 0
            layer.addSublayer(s)
        }
        badge.contentsScale = UIScreen.main.scale
        badge.alignmentMode = .center
        badge.fontSize = 13
        badge.font = UIFont.systemFont(ofSize: 13, weight: .heavy)
        badge.foregroundColor = UIColor.white.cgColor
        badge.shadowColor = UIColor.black.cgColor
        badge.shadowOpacity = 0.55
        badge.shadowRadius = 1.5
        badge.shadowOffset = CGSize(width: 0, height: 1)
        badge.opacity = 0
        layer.addSublayer(badge)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }

    /// Số lớp (test "0 lớp khi tắt" so với khi bật).
    var layerCount: Int { 1 + (layer.sublayers?.count ?? 0) + (holder.sublayers?.count ?? 0) + (mouse.sublayers?.count ?? 0) }
    var isMoleUp: Bool { game.mole != nil }

    // MARK: đường nóng

    /// Mỗi phím (KeyboardView.tapped). `letter` = chữ thường a–z hoặc nil.
    @inline(__always) func typed(_ letter: Character?, at t: CFTimeInterval) {
        #if DEBUG
        let t0 = CACurrentMediaTime()
        defer { Self.debugCost = CACurrentMediaTime() - t0 }
        #endif
        switch game.key(letter, at: t) {
        case .none: return
        case .spawn: DispatchQueue.main.async { [weak self] in self?.spawn() }
        case .hit(let k): DispatchQueue.main.async { [weak self] in self?.bonk(k) }
        case .duck: DispatchQueue.main.async { [weak self] in self?.duck() }
        }
    }

    // MARK: thả / đập / thụt (ngoài đường nóng)

    func spawn(at now: CFTimeInterval = CACurrentMediaTime()) {
        guard game.pending, window != nil || bounds.width > 0 else { game.cancelSpawn(); return }
        let ctx: (prefix: String, candidates: [String]) = context?() ?? ("", [])
        let k = game.chooseTarget(prefix: ctx.prefix, candidates: ctx.candidates) { [weak self] c in
            self?.keyFrame?(c) != nil
        }
        guard let f = keyFrame?(k), f.rect.width > 4, f.rect.height > 4 else { game.cancelSpawn(); return }
        game.place(k, at: now)
        #if DEBUG
        debugSpawnKey = k
        #endif
        show(in: f.rect, radius: f.radius)
        generation += 1
        let g = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + MoleGame.upTime + 0.02) { [weak self] in
            guard let self, self.generation == g else { return }
            if self.game.tick(at: CACurrentMediaTime()) == .duck { self.duck() }
        }
    }

    private func show(in r: CGRect, radius: CGFloat) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        holder.removeAllAnimations(); mouse.removeAllAnimations(); hole.removeAllAnimations(); lip.removeAllAnimations()
        holder.frame = r
        holder.cornerRadius = radius
        if r.size != builtSize { buildPaths(r.size) }
        mouse.transform = CATransform3DIdentity
        mouse.position = CGPoint(x: r.width / 2, y: upY)
        hole.opacity = 1; lip.opacity = 1
        holder.isHidden = false
        CATransaction.commit()
        let up = CABasicAnimation(keyPath: "position.y")
        up.fromValue = downY; up.toValue = upY
        up.duration = 0.24
        up.timingFunction = CAMediaTimingFunction(controlPoints: 0.34, 1.56, 0.64, 1)   // vọt lên, nảy nhẹ
        mouse.add(up, forKey: "mole.up")
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0; fade.toValue = 1; fade.duration = 0.12
        hole.add(fade, forKey: "mole.hole"); lip.add(fade, forKey: "mole.hole")
    }

    /// Thụt xuống (trượt) — model về vị trí dưới, ẩn hộp khi xong.
    func duck() {
        generation += 1
        guard !holder.isHidden else { return }
        let g = generation
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        CATransaction.setCompletionBlock { [weak self] in
            guard let self, self.generation == g else { return }
            self.holder.isHidden = true
        }
        let down = CABasicAnimation(keyPath: "position.y")
        down.fromValue = mouse.presentation()?.position.y ?? upY
        down.toValue = downY
        down.duration = 0.18
        down.timingFunction = CAMediaTimingFunction(name: .easeIn)
        mouse.position.y = downY
        mouse.add(down, forKey: "mole.down")
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 1; fade.toValue = 0; fade.duration = 0.18
        hole.opacity = 0; lip.opacity = 0
        hole.add(fade, forKey: "mole.hole"); lip.add(fade, forKey: "mole.hole")
        CATransaction.commit()
    }

    /// Đập trúng: "éc" (nếu Âm thanh phím bật) + bẹp + sao + nhãn điểm; ghi thống kê.
    func bonk(_ k: Character) {
        generation += 1
        let g = generation
        if MolePolicy.shouldSqueak(customSound: KeyboardView.customSoundEnabled) {
            KeySound.shared.playSqueak()
            #if DEBUG
            debugSqueaks += 1
            #endif
        }
        var st = MoleStats.load(statsDefaults)
        st.record(hits: 1, streak: game.streak, day: MoleStats.dayNumber())
        st.save(statsDefaults)
        #if DEBUG
        debugBonks += 1
        #endif
        guard !holder.isHidden else { return }
        let r = holder.frame
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        CATransaction.setCompletionBlock { [weak self] in
            guard let self, self.generation == g else { return }
            self.holder.isHidden = true
        }
        // Bẹp: dẹt xuống rồi tụt hẳn.
        let squash = CAKeyframeAnimation(keyPath: "transform")
        squash.values = [CATransform3DIdentity, CATransform3DMakeScale(1.28, 0.55, 1), CATransform3DMakeScale(1.15, 0.7, 1)]
            .map { NSValue(caTransform3D: $0) }
        squash.keyTimes = [0, 0.35, 1]
        squash.duration = 0.34
        mouse.transform = CATransform3DMakeScale(1.15, 0.7, 1)
        mouse.add(squash, forKey: "mole.squash")
        let drop = CABasicAnimation(keyPath: "position.y")
        drop.fromValue = upY; drop.toValue = downY
        drop.beginTime = CACurrentMediaTime() + 0.16
        drop.duration = 0.18
        drop.fillMode = .backwards
        drop.timingFunction = CAMediaTimingFunction(name: .easeIn)
        mouse.position.y = downY
        mouse.add(drop, forKey: "mole.down")
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 1; fade.toValue = 0; fade.beginTime = CACurrentMediaTime() + 0.16; fade.duration = 0.18
        fade.fillMode = .backwards
        hole.opacity = 0; lip.opacity = 0
        hole.add(fade, forKey: "mole.hole"); lip.add(fade, forKey: "mole.hole")
        // Sao: toả lên trên đầu phím, xoay, mờ dần.
        let d = min(r.width, r.height)
        let top = CGPoint(x: r.midX, y: r.minY + r.height * 0.3)
        for (i, s) in stars.enumerated() {
            let a = (-150.0 + Double(i) * 60.0) * .pi / 180
            let to = CGPoint(x: top.x + CGFloat(cos(a)) * d * 0.75, y: top.y + CGFloat(sin(a)) * d * 0.75)
            s.position = to
            s.opacity = 0
            let g = CAAnimationGroup()
            let mv = CABasicAnimation(keyPath: "position"); mv.fromValue = NSValue(cgPoint: top); mv.toValue = NSValue(cgPoint: to)
            let rot = CABasicAnimation(keyPath: "transform.rotation.z"); rot.fromValue = 0; rot.toValue = Double.pi * (i == 1 ? -1 : 1)
            let sc = CAKeyframeAnimation(keyPath: "transform.scale"); sc.values = [0.4, 1.15, 0.9]
            let op = CAKeyframeAnimation(keyPath: "opacity"); op.values = [1, 1, 0]; op.keyTimes = [0, 0.6, 1]
            g.animations = [mv, rot, sc, op]
            g.duration = 0.5
            g.timingFunction = CAMediaTimingFunction(name: .easeOut)
            s.add(g, forKey: "mole.star")
        }
        // Nhãn điểm hôm nay, thoáng qua phía trên phím.
        badge.string = "🐭 \(st.today)"
        badge.frame = CGRect(x: r.midX - 40, y: r.minY - 22, width: 80, height: 18)
        badge.opacity = 0
        let bop = CAKeyframeAnimation(keyPath: "opacity"); bop.values = [0, 1, 1, 0]; bop.keyTimes = [0, 0.15, 0.7, 1]
        let rise = CABasicAnimation(keyPath: "transform.translation.y"); rise.fromValue = 4; rise.toValue = -8
        let bg = CAAnimationGroup(); bg.animations = [bop, rise]; bg.duration = 0.9
        badge.add(bg, forKey: "mole.badge")
        CATransaction.commit()
    }

    /// Đổi bảng phím / xoay: dẹp ngay, không tính trượt.
    func hideNow() {
        generation += 1
        game.hide()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        holder.removeAllAnimations(); mouse.removeAllAnimations(); hole.removeAllAnimations(); lip.removeAllAnimations()
        holder.isHidden = true
        CATransaction.commit()
    }

    /// Ẩn bàn phím / đổi theme: dừng hết (0 animation).
    func halt() {
        hideNow()
        for s in stars { s.removeAllAnimations() }
        badge.removeAllAnimations()
    }

    override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil { halt() }
    }

    // MARK: hình (vector tự vẽ — đơn vị d = cỡ đầu chuột)

    private func buildPaths(_ size: CGSize) {
        builtSize = size
        let w = size.width, h = size.height
        let d = min(w * 0.92, h * 0.86)
        mouse.bounds = CGRect(x: 0, y: 0, width: d, height: d)
        mouse.anchorPoint = CGPoint(x: 0.5, y: 1)
        upY = h - h * 0.08
        downY = h + d * 1.05
        func circle(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat) -> CGPath {
            CGPath(ellipseIn: CGRect(x: (x - r) * d, y: (y - r) * d, width: 2 * r * d, height: 2 * r * d), transform: nil)
        }
        func oval(_ x: CGFloat, _ y: CGFloat, _ rx: CGFloat, _ ry: CGFloat) -> CGPath {
            CGPath(ellipseIn: CGRect(x: (x - rx) * d, y: (y - ry) * d, width: 2 * rx * d, height: 2 * ry * d), transform: nil)
        }
        func merge(_ ps: [CGPath]) -> CGPath { let m = CGMutablePath(); ps.forEach { m.addPath($0) }; return m }
        ears.path = merge([circle(0.22, 0.27, 0.22), circle(0.78, 0.27, 0.22)])
        earsInner.path = merge([circle(0.23, 0.29, 0.13), circle(0.77, 0.29, 0.13)])
        head.path = oval(0.5, 0.62, 0.37, 0.36)
        cheeks.path = merge([circle(0.29, 0.73, 0.065), circle(0.71, 0.73, 0.065)])
        eyes.path = merge([circle(0.37, 0.56, 0.058), circle(0.63, 0.56, 0.058)])
        glints.path = merge([circle(0.385, 0.54, 0.02), circle(0.645, 0.54, 0.02)])
        nose.path = oval(0.5, 0.7, 0.055, 0.042)
        let wh = CGMutablePath()
        for (a, b) in [((0.42, 0.72), (0.14, 0.67)), ((0.42, 0.75), (0.16, 0.8)), ((0.58, 0.72), (0.86, 0.67)), ((0.58, 0.75), (0.84, 0.8))] {
            wh.move(to: CGPoint(x: a.0 * d, y: a.1 * d)); wh.addLine(to: CGPoint(x: b.0 * d, y: b.1 * d))
        }
        whiskers.path = wh
        whiskers.lineWidth = max(0.8, d * 0.02)
        for l in [ears, earsInner, head, cheeks, eyes, glints, nose, whiskers] { l.frame = mouse.bounds }
        // Hố: bầu dục tối ở đáy phím; mép trước: nửa dưới bầu dục rộng hơn, màu đất.
        let hw = w * 0.86, hh = max(6, h * 0.24)
        hole.frame = CGRect(origin: .zero, size: size)
        hole.path = CGPath(ellipseIn: CGRect(x: (w - hw) / 2, y: upY - hh * 0.55, width: hw, height: hh), transform: nil)
        lip.frame = hole.frame
        let lp = CGMutablePath()
        let lr = CGRect(x: (w - hw * 1.04) / 2, y: upY - hh * 0.2, width: hw * 1.04, height: hh * 0.9)
        lp.addEllipse(in: lr)
        lip.path = lp
        let sp = CGMutablePath()
        let rs = max(4, d * 0.16), ri = rs * 0.45
        for i in 0..<10 {
            let a = Double(i) * .pi / 5 - .pi / 2
            let r = i % 2 == 0 ? rs : ri
            let p = CGPoint(x: CGFloat(cos(a)) * r, y: CGFloat(sin(a)) * r)
            if i == 0 { sp.move(to: p) } else { sp.addLine(to: p) }
        }
        sp.closeSubpath()
        for s in stars { s.path = sp; s.bounds = .zero }
    }

    #if DEBUG
    var debugHolderHidden: Bool { holder.isHidden }
    var debugHolderFrame: CGRect { holder.frame }
    /// Test (ảnh chụp): đặt model đúng khung giữa lúc bị đập — bẹp + sao toả + nhãn điểm, không animation.
    func debugPoseBonk() {
        let r = holder.frame
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        mouse.removeAllAnimations()
        mouse.transform = CATransform3DMakeScale(1.28, 0.55, 1)
        let d = min(r.width, r.height), top = CGPoint(x: r.midX, y: r.minY + r.height * 0.3)
        for (i, s) in stars.enumerated() {
            let a = (-150.0 + Double(i) * 60.0) * .pi / 180
            s.position = CGPoint(x: top.x + CGFloat(cos(a)) * d * 0.6, y: top.y + CGFloat(sin(a)) * d * 0.6)
            s.opacity = 1
            s.transform = CATransform3DMakeRotation(CGFloat(i) * 0.6, 0, 0, 1)
        }
        badge.string = "🐭 \(MoleStats.load(statsDefaults).today + 1)"
        badge.frame = CGRect(x: r.midX - 40, y: r.minY - 24, width: 80, height: 18)
        badge.opacity = 1
        CATransaction.commit()
    }

    /// Test: thả chuột ngay (đường spawn thật) với ngữ cảnh cho sẵn.
    func debugForceSpawn(prefix: String, candidates: [String]) {
        game.debugArm()
        let saved = context
        context = { (prefix, candidates) }
        spawn()
        context = saved
    }
    #endif
}

private extension UIColor {
    convenience init(moleRGB v: UInt32) {
        self.init(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                  blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
}
