// "Bạn đồng hành" (Keyboard buddy, Plus, Phil 19/10/2026): nhân vật nhỏ ở góc phải thanh gợi ý — gõ thì
// nhảy (gõ càng nhanh càng sung), ngừng ≥ 3 s thì ngủ. Phần THUẦN (không UIKit) — song sinh
// android/keyboard/.../KeyboardBuddy.kt: cùng khoá `keyboardBuddy`, cùng id, cùng máy trạng thái, cùng
// bậc tốc độ, cùng hàm dáng nhảy, cùng bảng hình vẽ (test số chung `BuddyRig.checksum`).
//
// Luật hiệu năng (feature-flags-perf-policy):
//  • Tắt (mặc định) / chưa Plus ⇒ KeyboardView không tạo view nào (`buddy == nil`) — đường phím đọc một optional.
//  • Bật: mỗi phím chỉ ghi giờ vào vòng 16 ô + đếm (không cấp phát); đổi trạng thái mới đụng layer, ở lượt
//    runloop sau. Hoạt ảnh = Core Animation trên transform các bộ phận (render server chạy), không timer
//    mỗi khung. Ngủ = dáng tĩnh, "z z" chạy ~2 s rồi gỡ hẳn ⇒ 0 hoạt ảnh.
//  • Chế độ nguồn điện thấp ⇒ chỉ dáng tĩnh (đứng / ngủ), không nhảy.
//
// Hình vẽ là DỮ LIỆU (`BuddyRig`: mảnh hình + điểm xoay) — đổi nét vẽ không đụng code; `BuddyArt.sprites`
// chừa sẵn đường cho bộ khung hình vẽ sẵn (hoạ sĩ) sau này.
import Foundation

// MARK: - Cài đặt

/// Nhân vật (giá trị lưu App Group / sao lưu = rawValue — CHỈ thêm cuối).
enum BuddyCharacter: String, CaseIterable {
    case off, girl, boy, cat, chick

    static let key = "keyboardBuddy"
    static let defaultValue: BuddyCharacter = .off

    static func parse(_ raw: Any?) -> BuddyCharacter {
        (raw as? String).flatMap(BuddyCharacter.init(rawValue:)) ?? defaultValue
    }
    static func load(_ d: UserDefaults?) -> BuddyCharacter { parse(d?.object(forKey: key)) }

    var title: String {
        switch self {
        case .off: return L("Tắt")
        case .girl: return L("Cô gái")
        case .boy: return L("Chàng trai")
        case .cat: return L("Mèo")
        case .chick: return L("Gà con")
        }
    }

    var isPlus: Bool { self != .off }
}

/// Cổng Plus: đi cùng quyền giao diện cao cấp (như hiệu ứng khi bấm phím).
enum BuddyGate {
    static var allows: Bool { PlusGate.isUnlocked(.premiumThemes) }
    static func allows(_ c: BuddyCharacter) -> Bool { !c.isPlus || allows }
}

enum BuddyPolicy {
    /// Nhân vật thực hiện — hàm thuần: tắt / hết Plus ⇒ `.off` (không tạo view).
    static func active(setting: BuddyCharacter, plus: Bool) -> BuddyCharacter {
        guard setting != .off, plus else { return .off }
        return setting
    }

    /// Đọc lúc hiện bàn phím (applyAppearance) — không mỗi phím.
    static func current(_ d: UserDefaults?) -> BuddyCharacter {
        let s = BuddyCharacter.load(d)
        guard s != .off else { return .off }
        return active(setting: s, plus: BuddyGate.allows(s))
    }

    /// Trạng thái HIỂN THỊ: nguồn điện thấp ⇒ không nhảy / không cổ vũ / không "z z" (chỉ dáng tĩnh).
    static func visual(_ p: BuddyPhase, lowPower: Bool) -> BuddyPhase {
        guard lowPower else { return p }
        switch p {
        case .dancing, .cheering, .awake, .react: return .awake
        case .asleep: return .asleep(zzz: false)
        }
    }

    /// Trạng thái này có hoạt ảnh chạy không (dáng tĩnh = không).
    static func animates(_ p: BuddyPhase) -> Bool {
        switch p {
        case .dancing, .cheering, .react, .asleep(zzz: true): return true
        case .awake, .asleep(zzz: false): return false
        }
    }
}

// MARK: - Tốc độ gõ → bậc nhảy

/// Bậc nhảy theo số phím trong `window` giây gần nhất (≈ khoảng cách giữa các phím). Cùng ngưỡng với
/// "Lửa combo" (ComboMeter: 6 / 10 / 14 phím trong 2 s ≈ 3 / 5 / 7 phím mỗi giây). Bậc 0…3; trễ
/// (`hysteresis`) để không nhảy qua lại ở sát ngưỡng.
enum BuddyTempo {
    static let window = 2.0
    static let thresholds = [6, 10, 14]
    static let hysteresis = 2
    static let tiers = 4

    static func raw(_ n: Int, slack: Int = 0) -> Int {
        var lv = 0
        for th in thresholds where n >= th - slack { lv += 1 }
        return lv
    }

    /// Lên bậc ngay; xuống chỉ khi tụt dưới ngưỡng − `hysteresis`.
    static func tier(count n: Int, previous p: Int) -> Int {
        max(raw(n), min(p, raw(n, slack: hysteresis)))
    }
}

// MARK: - Máy trạng thái (thuần, theo dấu giờ)

enum BuddyPhase: Equatable {
    /// Ngủ; `zzz` = đang chạy "z z" (~2 s đầu sau khi thiếp đi).
    case asleep(zzz: Bool)
    /// Thức, đứng yên (hết đuôi nhảy, chưa tới 3 s).
    case awake
    /// Nhảy bậc 0…3.
    case dancing(Int)
    /// Cổ vũ ngắn (chọn từ gợi ý).
    case cheering
    /// Phản ứng ngắn (BuddyReaction: giận / thả tim / cười / ngạc nhiên / buồn / hoảng khi xoá dồn).
    case react(BuddyReaction)
}

struct BuddyBrain {
    /// Nhảy thêm sau phím cuối.
    static let tail = 0.6
    /// Không gõ từng này giây ⇒ ngủ.
    static let sleepAfter = 3.0
    /// "z z" chạy từng này giây rồi dừng hẳn.
    static let zzz = 2.0
    static let cheer = 0.6
    static let capacity = 16
    /// Xoá dồn: quá từng này phím ⌫ liên tiếp (cách nhau ≤ `deleteGap` s) ⇒ hoảng.
    static let panicAfter = 8
    static let deleteGap = 0.5

    private var times = [Double](repeating: -Double.greatestFiniteMagnitude, count: BuddyBrain.capacity)
    private var head = 0
    private(set) var lastKey = -Double.greatestFiniteMagnitude
    private(set) var tier = 0
    private(set) var cheerUntil = -Double.greatestFiniteMagnitude
    private(set) var reaction: BuddyReaction?
    private(set) var reactUntil = -Double.greatestFiniteMagnitude
    /// Tăng mỗi lần BẮT ĐẦU một phản ứng — phản ứng liên tiếp ⇒ diễn LẠI từ đầu (không chồng).
    private(set) var reactSerial = 0
    private(set) var deleteRun = 0
    private var lastDelete = -Double.greatestFiniteMagnitude

    /// Một phím lúc `t` (giây). Không cấp phát.
    mutating func press(at t: Double) {
        deleteRun = 0
        tap(at: t)
    }

    private mutating func tap(at t: Double) {
        if t - lastKey > BuddyTempo.window { resetRing() }   // lượt gõ mới: bậc tính lại từ đầu
        lastKey = t
        times[head] = t
        head = (head + 1) % Self.capacity
        tier = BuddyTempo.tier(count: count(at: t), previous: tier)
    }

    /// Chọn từ gợi ý: cổ vũ ngắn (cũng tính là "đang gõ" để đánh thức).
    mutating func cheer(at t: Double) {
        if t - lastKey > BuddyTempo.window { resetRing() }
        lastKey = max(lastKey, t)
        cheerUntil = t + Self.cheer
    }

    /// Phản ứng theo từ: diễn `r.duration` giây. KHÔNG tính là phím (đang ngủ thì giật mình thức, diễn xong
    /// ngủ tiếp — mốc ngủ giữ nguyên); gọi lại / phản ứng khác ⇒ mới nhất thắng, diễn lại từ đầu.
    mutating func react(_ r: BuddyReaction, at t: Double) {
        reaction = r
        reactUntil = t + r.duration
        reactSerial &+= 1
    }

    /// Phím ⌫ (cả lặp khi giữ / xoá theo từ): đếm chuỗi; quá `panicAfter` ⇒ hoảng, kéo dài tới 0,8 s sau phím
    /// ⌫ cuối (không diễn lại mỗi phím). Chỉ một bộ đếm — không cấp phát.
    mutating func delete(at t: Double) {
        if t - lastDelete > Self.deleteGap { deleteRun = 0 }
        lastDelete = t
        deleteRun += 1
        tap(at: t)
        guard deleteRun > Self.panicAfter else { return }
        if !(reaction == .panic && t < reactUntil) { reactSerial &+= 1 }
        reaction = .panic
        reactUntil = t + BuddyReaction.panic.duration
    }

    func count(at t: Double) -> Int {
        var n = 0
        for x in times where x <= t && t - x <= BuddyTempo.window { n += 1 }
        return n
    }

    func phase(at t: Double) -> BuddyPhase {
        if t < reactUntil, let r = reaction { return .react(r) }
        guard lastKey > -Double.greatestFiniteMagnitude else { return .asleep(zzz: false) }
        if t < cheerUntil { return .cheering }
        let dt = t - lastKey
        if dt < Self.tail { return .dancing(tier) }
        if dt < Self.sleepAfter { return .awake }
        return .asleep(zzz: dt < Self.sleepAfter + Self.zzz)
    }

    /// Lúc trạng thái tự đổi tiếp (không có phím mới) — nil = đã ngủ hẳn, không cần hẹn giờ.
    func nextDeadline(after t: Double) -> Double? {
        var best: Double? = reactUntil > t ? reactUntil : nil
        guard lastKey > -Double.greatestFiniteMagnitude else { return best }
        for d in [cheerUntil, lastKey + Self.tail, lastKey + Self.sleepAfter, lastKey + Self.sleepAfter + Self.zzz]
            where d > t { best = min(best ?? d, d) }
        return best
    }

    private mutating func resetRing() {
        for i in 0..<Self.capacity { times[i] = -Double.greatestFiniteMagnitude }
        head = 0
        tier = 0
    }
}

// MARK: - Dáng nhảy (hàm thuần, đơn vị thiết kế + radian)

/// Các kênh chuyển động. dx/dy: dời cả người (đơn vị thiết kế); rot: nghiêng quanh chân; sx: xoay
/// vòng (lật ngang); còn lại: góc quay bộ phận quanh điểm xoay (dương = cùng chiều kim đồng hồ, y xuống
/// ⇒ tay TRÁI dương = giơ ra ngoài/lên, tay PHẢI âm = giơ ra ngoài/lên).
struct BuddyPose: Equatable {
    var dx = 0.0, dy = 0.0, rot = 0.0, sx = 1.0
    var head = 0.0, armL = 0.0, armR = 0.0, legL = 0.0, legR = 0.0, tail = 0.0

    static let rest = BuddyPose()

    /// Kênh cộng dồn (mọi kênh trừ sx) theo thứ tự cố định — dùng cho hoạt ảnh nối.
    static let additiveChannels: [WritableKeyPath<BuddyPose, Double>] =
        [\.dx, \.dy, \.rot, \.head, \.armL, \.armR, \.legL, \.legR, \.tail]
}

enum BuddyDance {
    /// Nhịp (giây / phách) theo bậc 0…3; một vòng = 4 phách.
    static let beats: [Double] = [0.62, 0.5, 0.42, 0.36]
    /// Độ sung theo bậc.
    static let energy: [Double] = [0.35, 0.6, 0.85, 1.0]
    static let beatsPerLoop = 4.0
    /// Thời gian nối mượt khi đổi bậc / trạng thái.
    static let blend = 0.25

    static func loopLength(_ tier: Int) -> Double { beats[clamp(tier)] * beatsPerLoop }
    static func clamp(_ tier: Int) -> Int { min(max(tier, 0), BuddyTempo.tiers - 1) }

    /// Kiểu nhảy riêng từng nhân vật (rad / đơn vị thiết kế), nhân với độ sung của bậc.
    struct Style: Equatable {
        var armL: Double, armR: Double, armAmp: Double, alternate: Bool
        var kick: Double, bounce: Double, step: Double
    }

    /// Dáng nhảy bậc `tier` tại pha `p` (0…1 của một vòng). Bậc cao nhất: xoay một vòng ở phách 4.
    static func pose(_ s: Style, tier: Int, p: Double) -> BuddyPose {
        let t = clamp(tier)
        let e = energy[t]
        let pp = p - p.rounded(.down)
        let beats = pp * beatsPerLoop
        let bi = Int(beats.rounded(.down))
        let bf = beats - Double(bi)
        let two = 2 * Double.pi
        let s2 = sin(two * pp * 2)
        var o = BuddyPose()
        o.dy = -s.bounce * e * abs(sin(Double.pi * bf))
        o.dx = s.step * e * sin(two * pp)
        o.rot = 0.1 * e * s2
        o.sx = (t == BuddyTempo.tiers - 1 && bi == 3) ? cos(two * bf) : 1
        o.head = 0.12 * e * s2
        o.armL = s.armL * e + s.armAmp * e * s2
        o.armR = -(s.armR * e + s.armAmp * e * sin(two * pp * 2 + (s.alternate ? Double.pi : 0)))
        o.legL = s.kick * e * max(0, s2)
        o.legR = -s.kick * e * max(0, -s2)
        o.tail = 0.35 * e * sin(two * pp * 4)
        return o
    }

    /// Cổ vũ: nhảy lên, hai tay giơ cao — f 0…1 (về dáng nghỉ ở 0 và 1).
    static func cheer(_ s: Style, f: Double) -> BuddyPose {
        let k = sin(Double.pi * min(max(f, 0), 1))
        var o = BuddyPose()
        o.dy = -s.bounce * 1.4 * k
        o.armL = 2.4 * k
        o.armR = -2.4 * k
        o.legL = 0.4 * k
        o.legR = -0.4 * k
        o.tail = 0.5 * k
        return o
    }

    /// Dáng phản ứng — f 0…1 của `r.duration` (về nghỉ ở 0 và 1; hoảng: lặp, không về nghỉ ở cuối).
    static func react(_ r: BuddyReaction, _ s: Style, f: Double) -> BuddyPose {
        let ff = min(max(f, 0), 1)
        let pi = Double.pi
        let a = r == .panic ? min(1, ff / 0.15 + 1) : min(1, ff / 0.15, (1 - ff) / 0.15)
        var o = BuddyPose()
        switch r {
        case .love:       // tay khép ôm trái tim trước ngực, nhún ba nhịp, nghiêng đầu
            o.dy = -s.bounce * 0.5 * a * abs(sin(3 * pi * ff))
            o.armL = -0.55 * a; o.armR = 0.55 * a
            o.head = 0.18 * a * sin(2 * pi * ff)
            o.tail = 0.4 * a * sin(4 * pi * ff)
        case .angry:      // dậm chân, nắm tay hai bên, lắc đầu, đuôi dựng
            let st = sin(6 * pi * ff)
            o.dy = -s.bounce * 0.25 * a * abs(st)
            o.legL = 0.35 * a * max(0, st); o.legR = -0.35 * a * max(0, -st)
            o.armL = 0.35 * a; o.armR = -0.35 * a
            o.head = 0.12 * a * sin(10 * pi * ff)
            o.tail = -0.4 * a
        case .laugh:      // ôm bụng cười, rung người
            o.dx = 2 * a * sin(16 * pi * ff)
            o.dy = -s.bounce * 0.2 * a * abs(sin(8 * pi * ff))
            o.armL = -0.7 * a; o.armR = 0.7 * a
            o.head = 0.1 * a * sin(8 * pi * ff)
            o.tail = 0.3 * a * sin(8 * pi * ff)
        case .surprised:  // bật nhảy, giang tay
            o.dy = ff < 0.4 ? -s.bounce * 1.6 * sin(pi * ff / 0.4) : 0
            o.armL = 1.3 * a; o.armR = -1.3 * a
            o.legL = 0.3 * a; o.legR = -0.3 * a
            o.tail = -0.5 * a
        case .sad:        // chùng xuống, cúi đầu, tay khép, đuôi cụp
            o.dy = s.bounce * 0.6 * a
            o.head = 0.3 * a
            o.armL = -0.35 * a; o.armR = 0.35 * a
            o.tail = 0.6 * a
        case .panic:      // ôm đầu, rung lắc
            o.dx = 1.5 * a * sin(20 * pi * ff)
            o.armL = 2.6 * a; o.armR = -2.6 * a
            o.head = 0.08 * a * sin(20 * pi * ff)
            o.dy = -s.bounce * 0.15 * a * abs(sin(10 * pi * ff))
        }
        return o
    }

    /// Mắt khi phản ứng: true = mắt cười (cung), false = mắt tròn.
    static func happyEyes(_ r: BuddyReaction) -> Bool { r == .love || r == .laugh }
}

// MARK: - Phản ứng theo từ (ranh giới từ, tra O(1))

/// Phản ứng ngắn (~1–1,5 s) rồi về trạng thái cũ. Mới nhất thắng, không chồng. Thứ tự ƯU TIÊN khi một từ
/// khớp nhiều bảng = thứ tự case: tục (giận) > yêu thương > cười > ngạc nhiên > buồn ("wtf" = giận).
/// `panic` (xoá dồn) không theo từ — bộ đếm phím ⌫ trong BuddyBrain.
enum BuddyReaction: Int, CaseIterable {
    case angry, love, laugh, surprised, sad, panic

    /// Thời gian diễn (giây). Hoảng: kéo dài tới `duration` sau phím ⌫ cuối.
    var duration: Double {
        switch self {
        case .angry, .love: return 1.2
        case .laugh: return 1.3
        case .surprised: return 1.0
        case .sad: return 1.5
        case .panic: return 0.8
        }
    }
}

/// Bảng từ DÙNG CHUNG hai nền tảng (Android BuddyWords y hệt). Khớp bằng từ vừa chốt (và cụm "từ trước +
/// từ"), không phân biệt hoa/thường; từ không dấu khớp mọi dạng có dấu — trừ từ `strict` (bỏ dấu thành từ
/// khác hẳn: nhớ → "nho", chán → "chan", á → "a") chỉ khớp đúng dấu. Chữ lặp ("hahaha", "kkkkk", "woww")
/// khớp theo mẫu, không liệt kê.
enum BuddyWords {
    static let love: [(String, Bool)] = [
        ("yêu", false), ("iu", false), ("thương", false), ("nhớ", true), ("love", false), ("luv", false),
        ("lub", false), ("miss", false), ("cưng", true), ("thích", false), ("crush", false), ("tim", false),
        ("hun", false), ("thơm", true), ("honey", false), ("baby", false), ("anh yêu", false), ("em yêu", false),
        ("thương thương", false), ("nhớ nhớ", true), ("muah", false), ("chụt", true), ("xoxo", false),
        ("darling", false), ("lovely", false),
    ]
    static let laugh: [(String, Bool)] = [
        ("haha", false), ("hihi", false), ("hehe", false), ("hoho", false), ("kkk", false), ("kaka", false),
        ("keke", false), ("hjhj", false), ("lol", false), ("lmao", false), ("rofl", false), ("=))", false),
        (":))", false), ("😂", false), ("🤣", false), ("cười", true),
    ]
    static let surprised: [(String, Bool)] = [
        ("wow", false), ("ôi", true), ("trời ơi", false), ("omg", false), ("ủa", true),
        ("hả", true), ("what", false), ("wtf", false), ("ồ", true), ("á", true), ("woa", false), ("whoa", false),
    ]
    static let sad: [(String, Bool)] = [
        ("buồn", false), ("khóc", false), ("huhu", false), ("hic", false), ("hix", false), ("sad", false),
        (":(", false), ("chán", true), ("mệt mỏi", false), ("😢", false), ("😭", false),
    ]

    private struct Table {
        let exact: Set<String>, folded: Set<String>, squeezed: Set<String>
        init(_ e: [(String, Bool)]) {
            exact = Set(e.map(\.0))
            let loose = e.filter { !$0.1 }.map { BuddyWords.fold($0.0) }
            folded = Set(loose)
            squeezed = Set(loose.map(BuddyWords.squeeze).filter { $0.count >= 3 })
        }
        func has(_ k: String) -> Bool {
            guard !k.isEmpty else { return false }
            if exact.contains(k) { return true }
            let f = BuddyWords.fold(k)
            if folded.contains(f) { return true }
            let q = BuddyWords.squeeze(f)
            return q.count >= 3 && squeezed.contains(q)
        }
    }
    private static let tables: [(BuddyReaction, Table)] = [
        (.love, Table(love)), (.laugh, Table(laugh)), (.surprised, Table(surprised)), (.sad, Table(sad)),
    ]

    /// Chữ thường, bỏ dấu (đ → d).
    static func fold(_ s: String) -> String { EmojiWords.fold(s) }

    /// Gộp chữ lặp liên tiếp: "woooow" → "wow", "omggg" → "omg".
    static func squeeze(_ s: String) -> String {
        var out = ""
        var last: Character?
        for c in s where c != last { out.append(c); last = c }
        return out
    }

    /// Bỏ dấu câu bao quanh — giữ nguyên chuỗi toàn ký hiệu (":))", "=))", emoji).
    static func clean(_ s: String) -> String {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let letters = t.trimmingCharacters(in: CharacterSet.letters.inverted)
        return letters.isEmpty ? t : letters
    }

    /// Cười theo mẫu chữ lặp: k…k (≥ 3), ha/he/hi/ho/hj/ka/ke lặp ≥ 2 lần (cho dư một chữ cuối), =)))…
    static func isLaughPattern(_ f: String) -> Bool {
        let c = Array(f)
        if c.count >= 3, c.allSatisfy({ $0 == "k" }) { return true }
        if c.count >= 3, (f.hasPrefix("=)") || f.hasPrefix(":)")), c.dropFirst(2).allSatisfy({ $0 == ")" }) { return true }
        let units: Set<String> = ["ha", "he", "hi", "ho", "hj", "ka", "ke"]
        guard c.count >= 4 else { return false }
        var i = 0, n = 0
        while i + 1 < c.count, units.contains(String(c[i...(i + 1)])) { i += 2; n += 1 }
        return n >= 2 && c.count - i <= 1
    }

    /// Buồn theo mẫu: hu lặp ≥ 2 ("huhuhu"), hic/hix lặp, ":(((".
    static func isSadPattern(_ f: String) -> Bool {
        let c = Array(f)
        if c.count >= 2, f.hasPrefix(":("), c.dropFirst(2).allSatisfy({ $0 == "(" }) { return true }
        for u in ["hu", "hic", "hix"] {
            let uc = Array(u)
            guard c.count >= uc.count * 2, c.count % uc.count == 0 else { continue }
            if stride(from: 0, to: c.count, by: uc.count).allSatisfy({ Array(c[$0..<($0 + uc.count)]) == uc }) { return true }
        }
        return false
    }

    /// Phản ứng cho từ vừa chốt `word` (từ trước `prev`, `prev2`) — nil = không. Tục / cặp / bộ ba tục
    /// (CÙNG bảng của bộ lọc gợi ý) ⇒ giận, KHÔNG phụ thuộc công tắc "Lọc từ nhạy cảm" (công tắc chỉ cho gợi ý).
    static func reaction(word: String, prev: String?, prev2: String? = nil) -> BuddyReaction? {
        let w = clean(word)
        guard !w.isEmpty, w.count <= 24 else { return nil }
        let p = prev.map(clean).flatMap { $0.isEmpty ? nil : $0 }
        let p2 = prev2.map(clean).flatMap { $0.isEmpty ? nil : $0 }
        if SensitiveWords.set.contains(w) || SensitiveWords.isPair(p, w) || SensitiveWords.isTriple(p2, p, w) {
            return .angry
        }
        let f = fold(w)
        for (r, t) in tables {
            if let p, t.has(p + " " + w) { return r }
            if t.has(w) { return r }
            if r == .laugh, isLaughPattern(f) { return r }
            if r == .sad, isSadPattern(f) { return r }
        }
        return nil
    }

    /// Ký hiệu chửi thề kiểu truyện tranh (#$%&*!) — chỉ các ký hiệu này, thứ tự theo `seed` (tất định).
    static let grawlixSymbols: [Character] = ["#", "$", "%", "&", "*", "!", "@"]
    static func grawlix(seed: Int, count: Int = 4) -> String {
        var r = FxRandom(seed: seed)
        var out = ""
        var last: Character?
        while out.count < count {
            let c = grawlixSymbols[min(Int(r.next() * Double(grawlixSymbols.count)), grawlixSymbols.count - 1)]
            if c == last { continue }
            out.append(c); last = c
        }
        return out
    }
}

/// Hiệu ứng phụ của phản ứng — hạt nhỏ bay (pool ≤ `maxParticles`) + một "biển" cố định (bong bóng chửi,
/// "!", mây mưa). Toạ độ pt trong ô nhân vật (gốc trên trái): luôn TRỌN trong ô — ô cắt theo strip nên
/// không bao giờ ra vùng phím / chữ gợi ý. Hàm thuần (Android y hệt).
enum BuddyFX {
    static let maxParticles = 8
    static let particleSize = 7.0
    static let particleDuration = 1.0
    static let heartColor: UInt32 = 0xFF5C8A
    static let tearColor: UInt32 = 0x6EC6FF
    static let rainColor: UInt32 = 0x7FA7D9
    static let cloudColor: UInt32 = 0xA9B1BD
    static let angryRed: UInt32 = 0xFF3B30
    /// Chữ bay khi xoá dồn.
    static let panicGlyphs: [String] = ["a", "b", "c", "x", "?"]

    struct Particle: Equatable {
        var x0: Double, y0: Double, x1: Double, y1: Double, delay: Double
    }

    /// Số hạt mỗi phản ứng (≤ maxParticles).
    static func count(_ r: BuddyReaction) -> Int {
        switch r {
        case .love: return 5
        case .laugh: return 4
        case .sad: return 3
        case .panic: return 3
        case .angry, .surprised: return 0
        }
    }

    /// Quỹ đạo hạt thứ i của phản ứng `r` trong ô `w`×`h`.
    static func particle(_ r: BuddyReaction, _ i: Int, width w: Double, height h: Double) -> Particle {
        let n = count(r)
        let rad = particleSize / 2
        let t = n > 1 ? Double(i) / Double(n - 1) : 0.5            // 0…1 trái → phải
        var p: Particle
        switch r {
        case .love:      // tim bay lên, toả ra
            p = Particle(x0: w / 2 + (t - 0.5) * w * 0.3, y0: h * 0.55,
                         x1: rad + (w - 2 * rad) * (0.1 + 0.8 * t), y1: rad + (h * 0.35) * Double((i * 7) % 3) / 2,
                         delay: Double(i) * 0.08)
        case .laugh:     // nước mắt văng hai bên đầu, rơi nhẹ
            let side = i % 2 == 0 ? -1.0 : 1.0
            p = Particle(x0: w / 2 + side * w * 0.12, y0: h * 0.25,
                         x1: w / 2 + side * w * (0.36 + 0.06 * Double(i / 2)), y1: h * (0.35 + 0.12 * Double(i / 2)),
                         delay: Double(i) * 0.15)
        case .sad:       // giọt mưa rơi từ mây trên đầu
            p = Particle(x0: w * (0.3 + 0.2 * Double(i)), y0: h * 0.18, x1: w * (0.3 + 0.2 * Double(i)), y1: h * 0.5,
                         delay: Double(i) * 0.3)
        case .panic:     // chữ bay khỏi người, lên + ra hai bên
            p = Particle(x0: w / 2, y0: h * 0.6, x1: rad + (w - 2 * rad) * t, y1: rad + h * 0.1 * Double(i % 2),
                         delay: Double(i) * 0.1)
        case .angry, .surprised:
            p = Particle(x0: w / 2, y0: h / 2, x1: w / 2, y1: h / 2, delay: 0)
        }
        func cx(_ x: Double) -> Double { min(max(x, rad), w - rad) }
        func cy(_ y: Double) -> Double { min(max(y, rad), h - rad) }
        return Particle(x0: cx(p.x0), y0: cy(p.y0), x1: cx(p.x1), y1: cy(p.y1), delay: p.delay)
    }

    /// Khung "biển" cố định (pt): bong bóng chửi (giận), "!" (ngạc nhiên), mây (buồn); nil = không có.
    static func badge(_ r: BuddyReaction, width w: Double, height h: Double) -> BuddyRect? {
        switch r {
        case .angry: return BuddyRect(x: 0, y: 0, w: w, h: min(13, h * 0.32))
        case .surprised: return BuddyRect(x: w - 9, y: 1, w: 8, h: min(14, h * 0.36))
        case .sad: return BuddyRect(x: w * 0.18, y: 0, w: w * 0.64, h: min(10, h * 0.24))
        default: return nil
        }
    }

    /// Đường trái tim tâm (cx, cy), nửa bề ngang ~k (đơn vị bất kỳ).
    static func heartPath(cx: Double, cy: Double, k: Double) -> String {
        func f(_ v: Double) -> String { String(format: "%.2f", v) }
        return "M\(f(cx)) \(f(cy + k * 0.9)) Q\(f(cx - k * 1.4)) \(f(cy + k * 0.1)) \(f(cx - k * 0.8)) \(f(cy - k * 0.6)) "
            + "Q\(f(cx - k * 0.3)) \(f(cy - k * 1.1)) \(f(cx)) \(f(cy - k * 0.4)) "
            + "Q\(f(cx + k * 0.3)) \(f(cy - k * 1.1)) \(f(cx + k * 0.8)) \(f(cy - k * 0.6)) "
            + "Q\(f(cx + k * 1.4)) \(f(cy + k * 0.1)) \(f(cx)) \(f(cy + k * 0.9)) Z"
    }

    /// Giọt nước (nước mắt / mưa) tâm (cx, cy) cỡ k.
    static func dropPath(cx: Double, cy: Double, k: Double) -> String {
        func f(_ v: Double) -> String { String(format: "%.2f", v) }
        return "M\(f(cx)) \(f(cy - k)) Q\(f(cx + k * 0.9)) \(f(cy + k * 0.2)) \(f(cx)) \(f(cy + k * 0.8)) "
            + "Q\(f(cx - k * 0.9)) \(f(cy + k * 0.2)) \(f(cx)) \(f(cy - k)) Z"
    }

    /// Mây trong khung (x, y, w, h).
    static func cloudPath(_ b: BuddyRect) -> String {
        func f(_ v: Double) -> String { String(format: "%.2f", v) }
        let x = b.x, y = b.y, w = b.w, h = b.h
        return "M\(f(x + w * 0.1)) \(f(y + h)) Q\(f(x - w * 0.05)) \(f(y + h * 0.5)) \(f(x + w * 0.2)) \(f(y + h * 0.45)) "
            + "Q\(f(x + w * 0.3)) \(f(y)) \(f(x + w * 0.55)) \(f(y + h * 0.25)) "
            + "Q\(f(x + w * 0.85)) \(f(y)) \(f(x + w * 0.85)) \(f(y + h * 0.5)) "
            + "Q\(f(x + w * 1.05)) \(f(y + h * 0.6)) \(f(x + w * 0.9)) \(f(y + h)) Z"
    }
}

// MARK: - Hình vẽ (dữ liệu)

/// Bộ phận — thứ tự vẽ (sau đè trước) = thứ tự case.
enum BuddyPart: Int, CaseIterable {
    case tail, legL, legR, body, armL, armR, head
    /// Dáng ngủ: nhóm tĩnh riêng.
    case sleep
}

/// Một mảnh hình (một màu tô / một nét).
struct BuddyPiece {
    enum Shape {
        /// Đường SVG rút gọn: M L H V Q Z (hoa = tuyệt đối, thường = tương đối).
        case path(String)
        case circle(Double, Double, Double)
        case ellipse(Double, Double, Double, Double)
        case rrect(Double, Double, Double, Double, Double)
    }
    enum Stroke: Equatable {
        case none
        /// Viền nhân vật: nâu đậm ở nền sáng, kem ở nền tối (luôn tách khỏi nền).
        case outline
        /// Nét mặt (mắt, miệng): luôn đậm.
        case ink
        case color(UInt32)
    }
    /// Mắt mở khi đứng / mắt cười khi nhảy; còn lại luôn hiện.
    enum Show { case always, rest, dance }

    let part: BuddyPart
    let shape: Shape
    let fill: UInt32?
    let alpha: Double
    let stroke: Stroke
    let width: Double
    let show: Show
    /// Xoay mảnh (độ, quanh cx, cy) — chỉ dáng ngủ (đầu nghiêng).
    let turn: (deg: Double, cx: Double, cy: Double)?

    init(_ part: BuddyPart, _ shape: Shape, fill: UInt32? = nil, alpha: Double = 1, stroke: Stroke = .outline,
         width: Double = BuddyRig.outlineWidth, show: Show = .always, turn: (Double, Double, Double)? = nil) {
        self.part = part; self.shape = shape; self.fill = fill; self.alpha = alpha
        self.stroke = stroke; self.width = width; self.show = show
        self.turn = turn.map { (deg: $0.0, cx: $0.1, cy: $0.2) }
    }
}

/// Cách hiển thị: thanh gợi ý (nhân vật dáng cao ⇒ cắt ngang eo cho đầu đủ lớn) hay cả người (xem trước /
/// "sân khấu" cao hơn nếu Phil chọn). Đổi một chỗ: `BuddyRig.viewport(_:)`.
enum BuddyFraming { case bar, full }

struct BuddyRect: Equatable {
    var x: Double, y: Double, w: Double, h: Double
    var maxY: Double { y + h }
}

/// Bộ khung một nhân vật — toàn bộ là dữ liệu (đơn vị thiết kế, y xuống).
struct BuddyRig {
    static let outlineWidth = 6.0

    let pieces: [BuddyPiece]
    let pivots: [BuddyPart: (x: Double, y: Double)]
    /// Khung cả người.
    let full: BuddyRect
    /// Đáy khung khi hiện ở thanh gợi ý (nil = cả người — nhân vật chibi).
    let barBottom: Double?
    /// Điểm chân (tâm nghiêng / xoay vòng).
    let feet: (x: Double, y: Double)
    /// Đáy hình dáng ngủ (toạ độ nhóm ngủ) — nhóm ngủ được dời cho đáy chạm đáy khung.
    let sleepBottom: Double
    /// "z z Z": (x, y góc trên trái, cỡ) trong toạ độ nhóm ngủ.
    let zzz: [(x: Double, y: Double, size: Double)]
    let style: BuddyDance.Style
    /// Trái tim ôm trước ngực khi thả tim (tâm, nửa bề ngang).
    var heart: (x: Double, y: Double, size: Double) = (0, 46, 11)
    /// Giận: vùng mặt đỏ bừng (elip) + lông mày cau (đường).
    var face: (x: Double, y: Double, rx: Double, ry: Double) = (0, -6, 24, 18)
    var brows = "M-16 -25 L-6 -21 M16 -25 L6 -21"
    /// Mũ (giữa đáy mũ trên đỉnh đầu, bề ngang) — đứng / ngủ; cổ (nơ); tay phải lúc nghỉ (lồng đèn).
    var hat: (x: Double, y: Double, size: Double) = (0, -42, 34)
    var sleepHat: (x: Double, y: Double, size: Double) = (-34, -2, 26)
    var neck: (x: Double, y: Double) = (0, 18)
    var hand: (x: Double, y: Double) = (23, 64)

    var hasTail: Bool { pieces.contains { $0.part == .tail } }

    func viewport(_ f: BuddyFraming) -> BuddyRect {
        guard f == .bar, let b = barBottom else { return full }
        return BuddyRect(x: full.x, y: full.y, w: full.w, h: b - full.y)
    }

    /// Dời nhóm ngủ (đơn vị thiết kế) để đáy hình ngủ chạm đáy khung đang dùng.
    func sleepOffset(_ f: BuddyFraming) -> Double { viewport(f).maxY - 4 - sleepBottom }

    static let zColor: UInt32 = 0x8AA6E8
    /// Màu viền nền sáng / nền tối.
    static let outlineLight: UInt32 = 0x3A2A2A
    static let outlineDark: UInt32 = 0xF3E6DC
    static let ink: UInt32 = 0x3A2A2A

    /// Nét chữ "z" cỡ `k` tại (x, y).
    static func zPath(x: Double, y: Double, size k: Double) -> String {
        "M\(x) \(y) h\(k) l\(-k) \(k) h\(k)"
    }

    static func rig(_ c: BuddyCharacter) -> BuddyRig? {
        switch c {
        case .off: return nil
        case .girl: return girl
        case .boy: return boy
        case .cat: return cat
        case .chick: return chick
        }
    }

    /// Tổng mọi số trong bảng hình (toạ độ, cỡ, màu, điểm xoay) — Android tính y hệt (test chung):
    /// sửa hình một bên mà quên bên kia thì test đỏ.
    static func checksum(_ c: BuddyCharacter) -> Double {
        guard let r = rig(c) else { return 0 }
        var s = 0.0
        func nums(_ str: String) -> [Double] { BuddySVG.numbers(str) }
        for p in r.pieces {
            s += Double(p.part.rawValue) + p.width + p.alpha + Double(p.fill ?? 0) / 65536
            switch p.stroke {
            case .none: s += 1
            case .outline: s += 2
            case .ink: s += 3
            case .color(let v): s += 4 + Double(v) / 65536
            }
            switch p.show { case .always: break; case .rest: s += 5; case .dance: s += 7 }
            if let t = p.turn { s += t.deg + t.cx + t.cy }
            switch p.shape {
            case .path(let d): s += nums(d).reduce(0, +)
            case .circle(let a, let b, let r): s += a + b + r
            case .ellipse(let a, let b, let rx, let ry): s += a + b + rx + ry
            case .rrect(let x, let y, let w, let h, let rr): s += x + y + w + h + rr
            }
        }
        for part in BuddyPart.allCases { if let v = r.pivots[part] { s += v.x * 3 + v.y * 5 } }
        s += r.full.x + r.full.y + r.full.w + r.full.h + (r.barBottom ?? 0) + r.feet.x + r.feet.y + r.sleepBottom
        for z in r.zzz { s += z.x + z.y + z.size }
        s += r.heart.x + r.heart.y * 2 + r.heart.size * 3
        s += r.face.x + r.face.y + r.face.rx + r.face.ry + nums(r.brows).reduce(0, +)
        s += r.hat.x + r.hat.y + r.hat.size + r.sleepHat.x + r.sleepHat.y + r.sleepHat.size
        s += r.neck.x + r.neck.y + r.hand.x * 2 + r.hand.y * 2
        return s
    }
}

/// Nghệ thuật của nhân vật: khung vector (mặc định) hoặc bộ khung hình vẽ sẵn (hoạ sĩ vẽ — chưa có).
enum BuddyArt {
    case rig(BuddyRig)
    case sprites(BuddySprites)
}

/// Bộ khung hình (tên ảnh) — mỗi bậc nhảy một chuỗi khung chạy hết trong một vòng (`BuddyDance.loopLength`).
struct BuddySprites {
    var sleep: String
    var rest: String
    var dance: [[String]]
    var cheer: [String]

    /// Khung thứ mấy tại pha `p` (0…1) của chuỗi `count` khung — hàm thuần (Android y hệt).
    static func frameIndex(p: Double, count: Int) -> Int {
        guard count > 0 else { return 0 }
        let pp = p - p.rounded(.down)
        return min(Int(pp * Double(count)), count - 1)
    }
}

// MARK: - Bảng hình 4 nhân vật

extension BuddyRig {
    private static func P(_ part: BuddyPart, _ d: String, fill: UInt32? = nil, alpha: Double = 1,
                          stroke: BuddyPiece.Stroke = .outline, width: Double = outlineWidth,
                          show: BuddyPiece.Show = .always, turn: (Double, Double, Double)? = nil) -> BuddyPiece {
        BuddyPiece(part, .path(d), fill: fill, alpha: alpha, stroke: stroke, width: width, show: show, turn: turn)
    }
    private static func C(_ part: BuddyPart, _ cx: Double, _ cy: Double, _ r: Double, fill: UInt32,
                          stroke: BuddyPiece.Stroke = .outline, alpha: Double = 1,
                          show: BuddyPiece.Show = .always, turn: (Double, Double, Double)? = nil) -> BuddyPiece {
        BuddyPiece(part, .circle(cx, cy, r), fill: fill, alpha: alpha, stroke: stroke, show: show, turn: turn)
    }
    private static func E(_ part: BuddyPart, _ cx: Double, _ cy: Double, _ rx: Double, _ ry: Double, fill: UInt32,
                          stroke: BuddyPiece.Stroke = .outline, alpha: Double = 1,
                          turn: (Double, Double, Double)? = nil) -> BuddyPiece {
        BuddyPiece(part, .ellipse(cx, cy, rx, ry), fill: fill, alpha: alpha, stroke: stroke, turn: turn)
    }
    private static func R(_ part: BuddyPart, _ x: Double, _ y: Double, _ w: Double, _ h: Double, _ r: Double,
                          fill: UInt32, turn: (Double, Double, Double)? = nil) -> BuddyPiece {
        BuddyPiece(part, .rrect(x, y, w, h, r), fill: fill, turn: turn)
    }
    /// Nét đơn (không tô).
    private static func L(_ part: BuddyPart, _ d: String, _ stroke: BuddyPiece.Stroke, _ w: Double,
                          show: BuddyPiece.Show = .always, turn: (Double, Double, Double)? = nil) -> BuddyPiece {
        BuddyPiece(part, .path(d), fill: nil, stroke: stroke, width: w, show: show, turn: turn)
    }
    /// Chi có viền: nét viền dày + nét màu mảnh hơn đè lên.
    private static func limb(_ part: BuddyPart, _ d: String, _ color: UInt32, _ w: Double) -> [BuddyPiece] {
        [L(part, d, .outline, w + 5), L(part, d, .color(color), w)]
    }

    // Cô gái — chibi thon, chân dài: tóc dài nâu, băng đô hồng tai mèo trắng (lòng hồng), đuôi mèo nâu, váy hồng, giày hồng.
    static let girl: BuddyRig = {
        let skin: UInt32 = 0xFFE0C7, dress: UInt32 = 0xFFB3C7, shoe: UInt32 = 0xFF6F91, hair: UInt32 = 0x5B3A29
        let earIn: UInt32 = 0xFFC2D4, blush: UInt32 = 0xFF9EAA
        // Đuôi mèo sau váy: vẫy khi nhảy.
        var p: [BuddyPiece] = limb(.tail, "M14 66 Q50 70 52 40 Q54 20 40 18", hair, 5)
        p += [
            L(.tail, "M44 22 Q40 17 38 19", .color(0xFFFFFF), 4),
            L(.legL, "M-8 72 L-10 140", .outline, outlineWidth),
            E(.legL, -11, 142, 8, 4.5, fill: shoe),
            L(.legR, "M8 72 L10 140", .outline, outlineWidth),
            E(.legR, 11, 142, 8, 4.5, fill: shoe),
            P(.body, "M-14 24 Q0 18 14 24 L24 76 Q0 84 -24 76 Z", fill: dress),
            L(.body, "M-20 70 Q0 78 20 70", .color(0xFFFFFF), 4),
            L(.armL, "M-12 30 L-22 62", .outline, outlineWidth),
            C(.armL, -23, 64, 5, fill: skin),
            L(.armR, "M12 30 L22 62", .outline, outlineWidth),
            C(.armR, 23, 64, 5, fill: skin),
            C(.head, 0, -16, 32, fill: skin),
            P(.head, "M-30 -36 L-26 -72 L-8 -48 Z", fill: 0xFFFFFF),
            P(.head, "M-25 -42 L-23 -62 L-13 -48 Z", fill: earIn, stroke: .none),
            P(.head, "M30 -36 L26 -72 L8 -48 Z", fill: 0xFFFFFF),
            P(.head, "M25 -42 L23 -62 L13 -48 Z", fill: earIn, stroke: .none),
            P(.head, "M-34 -14 Q-38 -52 0 -52 Q38 -52 34 -14 Q36 18 26 30 Q26 -6 18 -26 Q4 -18 -6 -30 "
                + "Q-18 -20 -26 -26 Q-30 -6 -26 30 Q-36 18 -34 -14 Z", fill: hair),
            L(.head, "M-30 -42 Q0 -62 30 -42", .color(shoe), 5),
            C(.head, -10, -13, 3.5, fill: ink, stroke: .none, show: .rest),
            C(.head, 10, -13, 3.5, fill: ink, stroke: .none, show: .rest),
            L(.head, "M-14 -14 q4 -5 8 0 M6 -14 q4 -5 8 0", .ink, 4.5, show: .dance),
            E(.head, -18, -2, 6, 3.5, fill: blush, stroke: .none, alpha: 0.6),
            E(.head, 18, -2, 6, 3.5, fill: blush, stroke: .none, alpha: 0.6),
            P(.head, "M-6 0 Q0 8 6 0 Z", fill: 0xE84D6A, stroke: .ink, width: 2.5),
        ]
        // Ngủ: nằm cuộn tròn, tai mèo, đuôi quấn quanh.
        p += [
            E(.sleep, 14, 40, 56, 17, fill: dress),
            E(.sleep, 74, 46, 8, 4.5, fill: shoe),
            C(.sleep, -34, 22, 28, fill: skin),
            P(.sleep, "M-58 4 L-66 -24 L-46 -10 Z", fill: 0xFFFFFF),
            P(.sleep, "M-58 0 L-62 -16 L-50 -8 Z", fill: earIn, stroke: .none),
            P(.sleep, "M-26 -8 L-18 -34 L-8 -10 Z", fill: 0xFFFFFF),
            P(.sleep, "M-22 -10 L-18 -26 L-12 -12 Z", fill: earIn, stroke: .none),
            P(.sleep, "M-62 22 Q-66 -12 -34 -12 Q-2 -12 -6 22 Q-12 6 -22 6 Q-30 -2 -40 6 Q-54 2 -62 22 Z", fill: hair),
            L(.sleep, "M-46 24 q4 4 8 0 M-28 24 q4 4 8 0", .ink, 4),
            E(.sleep, -48, 34, 5, 3, fill: blush, stroke: .none, alpha: 0.6),
            E(.sleep, -16, 34, 5, 3, fill: blush, stroke: .none, alpha: 0.6),
        ]
        // Đuôi mèo cuộn quanh người khi ngủ.
        p += limb(.sleep, "M66 36 Q96 30 86 60 Q70 76 30 64", hair, 5)
        return BuddyRig(
            pieces: p,
            pivots: [.tail: (14, 66), .legL: (-8, 72), .legR: (8, 72), .body: (0, 72), .armL: (-12, 30),
                     .armR: (12, 30), .head: (0, 18)],
            full: BuddyRect(x: -96, y: -84, w: 196, h: 234), barBottom: nil, feet: (0, 146), sleepBottom: 72,
            zzz: [(0, -30, 12), (16, -46, 15), (36, -64, 19)],
            style: .init(armL: 2.3, armR: 2.3, armAmp: 0.3, alternate: true, kick: 0.6, bounce: 9, step: 8))
    }()

    // Chàng trai — chibi thon hiphop: mũ lưỡi trai đội ngược sọc đỏ, kính râm, headphone đeo cổ, áo vàng "69",
    // dây chuyền vàng to có mặt dây, quần baggy xanh đen, sneaker trắng dày.
    static let boy: BuddyRig = {
        let skin: UInt32 = 0xFFE0C7, tee: UInt32 = 0xF2B134, pants: UInt32 = 0x3B4252, cap: UInt32 = 0x2E2A3A
        let red: UInt32 = 0xE2453C, lens: UInt32 = 0x1B1B22, blush: UInt32 = 0xFF9EAA
        let gold: UInt32 = 0xFFD54F, gold2: UInt32 = 0xB8860B
        var p: [BuddyPiece] = [
            P(.legL, "M-16 64 L-26 132 L-12 134 L-2 76 Z", fill: pants),
            P(.legL, "M-32 132 q-2 10 8 10 h16 q4 -9 -4 -10 Z", fill: 0xFFFFFF),
            L(.legL, "M-30 139 h20", .color(red), 3),
            P(.legR, "M2 76 L14 134 L28 130 L16 64 Z", fill: pants),
            P(.legR, "M10 132 q-2 10 8 10 h16 q4 -9 -4 -10 Z", fill: 0xFFFFFF),
            L(.legR, "M12 139 h20", .color(red), 3),
            P(.body, "M-20 20 Q0 12 20 20 L24 70 Q0 76 -24 70 Z", fill: tee),
            L(.body, "M-3 40 Q-11 40 -11 50 Q-11 56 -7 56 Q-3 56 -3 52 Q-3 47 -7 47 Q-11 47 -11 51 "
                + "M3 56 Q11 56 11 46 Q11 40 7 40 Q3 40 3 44 Q3 49 7 49 Q11 49 11 45", .ink, 3.5),
            // Dây chuyền vàng to + mặt dây.
            L(.body, "M-11 21 Q0 40 11 21", .color(gold2), 6),
            L(.body, "M-11 21 Q0 40 11 21", .color(gold), 3.5),
            BuddyPiece(.body, .circle(0, 34, 4.5), fill: gold, stroke: .color(gold2), width: 2.5),
            L(.armL, "M-18 26 L-26 58", .outline, outlineWidth),
            C(.armL, -27, 60, 5, fill: skin),
            L(.armR, "M18 26 L26 58", .outline, outlineWidth),
            C(.armR, 27, 60, 5, fill: skin),
            C(.head, 0, -16, 32, fill: skin),
            P(.head, "M-32 -24 Q-30 -54 2 -54 Q32 -52 32 -24 Z", fill: cap),
            P(.head, "M-30 -30 Q-46 -30 -48 -22 Q-40 -18 -28 -22 Z", fill: cap),
            L(.head, "M-20 -46 h40", .color(red), 3.5),
            P(.head, "M-22 -18 h16 q1 11 -8 11 q-8 0 -8 -11 Z", fill: lens, width: 4),
            P(.head, "M6 -18 h16 q0 11 -8 11 q-9 0 -8 -11 Z", fill: lens, width: 4),
            L(.head, "M-6 -16 h12", .ink, 3),
            L(.head, "M-18 -15 l5 -1 M10 -15 l5 -1", .color(0x7FB8FF), 2.5),
            L(.head, "M-7 6 Q2 13 9 4", .ink, 4),
            L(.head, "M-20 14 Q0 24 20 14", .color(red), 5),
            R(.head, -28, 6, 10, 14, 4, fill: 0x2A2A33),
            R(.head, 18, 6, 10, 14, 4, fill: 0x2A2A33),
        ]
        // Ngủ: kéo mũ che mắt, ngáy.
        p += [
            E(.sleep, 14, 40, 56, 17, fill: tee),
            P(.sleep, "M62 40 h16 v8 h-18 Z", fill: 0xFFFFFF),
            C(.sleep, -34, 22, 28, fill: skin),
            P(.sleep, "M-62 16 Q-62 -14 -34 -14 Q-6 -14 -6 16 Z", fill: cap),
            P(.sleep, "M-64 14 Q-34 24 -4 14 Q-34 30 -64 14 Z", fill: cap),
            L(.sleep, "M-52 2 h36", .color(red), 3.5),
            E(.sleep, -34, 38, 4, 3, fill: ink, stroke: .none),
            E(.sleep, -56, 32, 5, 3, fill: blush, stroke: .none, alpha: 0.6),
            E(.sleep, -12, 32, 5, 3, fill: blush, stroke: .none, alpha: 0.6),
            L(.sleep, "M-50 50 Q-34 58 -18 50", .color(red), 4),
            L(.sleep, "M-44 44 Q-30 54 -14 44", .color(gold2), 5.5),
            L(.sleep, "M-44 44 Q-30 54 -14 44", .color(gold), 3.5),
            BuddyPiece(.sleep, .circle(-28, 50, 3.5), fill: gold, stroke: .color(gold2), width: 2),
        ]
        return BuddyRig(
            pieces: p,
            pivots: [.legL: (-9, 68), .legR: (9, 68), .body: (0, 70), .armL: (-18, 26), .armR: (18, 26),
                     .head: (0, 16)],
            full: BuddyRect(x: -92, y: -76, w: 184, h: 226), barBottom: nil, feet: (0, 142), sleepBottom: 57,
            zzz: [(0, -30, 12), (16, -46, 15), (36, -64, 19)],
            style: .init(armL: -1.3, armR: 2.3, armAmp: 0.2, alternate: false, kick: 0.3, bounce: 8, step: 10),
            brows: "M-20 -24 L-8 -20 M20 -24 L8 -20", hat: (2, -46, 36), hand: (27, 60))
    }()

    // Mèo (chibi): cam mướp, bụng kem, đuôi vẫy.
    static let cat: BuddyRig = {
        let fur: UInt32 = 0xF6A94E, cream: UInt32 = 0xFFF1DC, stripe: UInt32 = 0xD9822B, pink: UInt32 = 0xFFB3C7
        let nose: UInt32 = 0xF27A8A, blush: UInt32 = 0xFF9EAA
        var p: [BuddyPiece] = limb(.tail, "M18 80 Q50 82 48 50 Q47 36 56 30", fur, 10)
        p += limb(.legL, "M-14 88 L-14 106", fur, 11)
        p += [E(.legL, -16, 110, 10, 6, fill: cream)]
        p += limb(.legR, "M14 88 L14 106", fur, 11)
        p += [E(.legR, 16, 110, 10, 6, fill: cream),
              E(.body, 0, 72, 30, 28, fill: fur),
              E(.body, 0, 78, 17, 18, fill: cream, stroke: .none)]
        p += limb(.armL, "M-22 52 L-30 74", fur, 10)
        p += [C(.armL, -31, 77, 6.5, fill: cream)]
        p += limb(.armR, "M22 52 L30 74", fur, 10)
        p += [C(.armR, 31, 77, 6.5, fill: cream),
              P(.head, "M-36 -16 L-32 -58 L-6 -40 Z", fill: fur),
              P(.head, "M36 -16 L32 -58 L6 -40 Z", fill: fur),
              P(.head, "M-30 -26 L-28 -48 L-14 -38 Z M30 -26 L28 -48 L14 -38 Z", fill: pink, stroke: .none),
              E(.head, 0, -4, 42, 36, fill: fur),
              L(.head, "M-7 -38 L-4 -28 M7 -38 L4 -28 M0 -40 L0 -29", .color(stripe), 4),
              E(.head, 0, 12, 13, 9, fill: cream, stroke: .none),
              C(.head, -16, -6, 4.5, fill: ink, stroke: .none, show: .rest),
              C(.head, 16, -6, 4.5, fill: ink, stroke: .none, show: .rest),
              L(.head, "M-21 -4 q5 -6 10 0 M11 -4 q5 -6 10 0", .ink, 5, show: .dance),
              P(.head, "M-4 5 L4 5 L0 10 Z", fill: nose, stroke: .none),
              L(.head, "M-6 13 q3 4 6 0 q3 4 6 0", .ink, 3),
              L(.head, "M-26 8 L-44 4 M-26 14 L-44 16 M26 8 L44 4 M26 14 L44 16", .ink, 3),
              E(.head, -26, 6, 6, 3.5, fill: blush, stroke: .none, alpha: 0.6),
              E(.head, 26, 6, 6, 3.5, fill: blush, stroke: .none, alpha: 0.6)]
        // Ngủ: cuộn tròn, đuôi quấn trước.
        p += [E(.sleep, 10, 40, 52, 24, fill: fur),
              L(.sleep, "M10 18 L12 28 M26 18 L26 28 M42 22 L40 31", .color(stripe), 4)]
        p += limb(.sleep, "M58 46 Q72 66 40 66 Q10 66 -8 60", fur, 10)
        p += [P(.sleep, "M-58 10 L-62 -14 L-42 0 Z", fill: fur),
              P(.sleep, "M-26 0 L-10 -14 L-12 8 Z", fill: fur),
              C(.sleep, -34, 26, 30, fill: fur),
              E(.sleep, -34, 38, 9, 6, fill: cream, stroke: .none),
              L(.sleep, "M-48 26 q5 5 10 0 M-30 26 q5 5 10 0", .ink, 4.5),
              P(.sleep, "M-37 33 L-31 33 L-34 37 Z", fill: nose, stroke: .none),
              E(.sleep, -52, 36, 5, 3, fill: blush, stroke: .none, alpha: 0.6),
              E(.sleep, -16, 36, 5, 3, fill: blush, stroke: .none, alpha: 0.6)]
        return BuddyRig(
            pieces: p,
            pivots: [.tail: (18, 80), .legL: (-14, 88), .legR: (14, 88), .body: (0, 96), .armL: (-22, 52),
                     .armR: (22, 52), .head: (0, 32)],
            full: BuddyRect(x: -80, y: -66, w: 160, h: 188), barBottom: nil, feet: (0, 116), sleepBottom: 68,
            zzz: [(0, -30, 12), (16, -46, 15), (36, -64, 19)],
            style: .init(armL: 1.2, armR: 1.2, armAmp: 0.6, alternate: true, kick: 0.2, bounce: 10, step: 8),
            heart: (0, 68, 13), face: (0, 6, 30, 18), brows: "M-23 -16 L-11 -11 M23 -16 L11 -11",
            hat: (0, -36, 36), sleepHat: (-34, -2, 28), neck: (0, 34), hand: (31, 77))
    }()

    // Gà con (chibi): tròn vàng, cánh vỗ.
    static let chick: BuddyRig = {
        let yellow: UInt32 = 0xFFD43B, wing: UInt32 = 0xF5BE2A, orange: UInt32 = 0xF08A24, blush: UInt32 = 0xFF9EAA
        var p: [BuddyPiece] = limb(.legL, "M-12 86 L-12 104", orange, 6)
        p += limb(.legL, "M-22 108 L-12 103 L-2 108", orange, 5)
        p += limb(.legR, "M12 86 L12 104", orange, 6)
        p += limb(.legR, "M2 108 L12 103 L22 108", orange, 5)
        p += [E(.body, 0, 64, 38, 30, fill: yellow),
              E(.body, 0, 70, 22, 18, fill: 0xFFE68A, stroke: .none),
              P(.armL, "M-32 50 Q-58 62 -44 84 Q-34 74 -28 62 Z", fill: wing),
              P(.armR, "M32 50 Q58 62 44 84 Q34 74 28 62 Z", fill: wing),
              P(.head, "M-4 -38 Q-12 -62 2 -56 Q8 -68 14 -54 Q10 -44 4 -39 Z", fill: yellow),
              C(.head, 0, -2, 38, fill: yellow),
              C(.head, -14, -4, 4.5, fill: ink, stroke: .none, show: .rest),
              C(.head, 14, -4, 4.5, fill: ink, stroke: .none, show: .rest),
              L(.head, "M-19 -2 q5 -6 10 0 M9 -2 q5 -6 10 0", .ink, 5, show: .dance),
              P(.head, "M-9 8 L9 8 L0 18 Z", fill: orange),
              E(.head, -24, 8, 6, 3.5, fill: blush, stroke: .none, alpha: 0.6),
              E(.head, 24, 8, 6, 3.5, fill: blush, stroke: .none, alpha: 0.6)]
        // Ngủ: tròn xoe, đầu gục.
        p += [E(.sleep, 6, 42, 46, 26, fill: yellow),
              P(.sleep, "M18 30 Q42 32 38 54 Q26 50 18 40 Z", fill: wing)]
        p += limb(.sleep, "M44 64 L54 66", orange, 5)
        p += [P(.sleep, "M-34 -1 Q-42 -18 -30 -14 Q-26 -24 -18 -10 Z", fill: yellow),
              C(.sleep, -30, 26, 28, fill: yellow),
              P(.sleep, "M-56 30 L-68 36 L-56 42 Z", fill: orange),
              L(.sleep, "M-44 24 q5 5 10 0 M-28 24 q5 5 10 0", .ink, 4.5),
              E(.sleep, -46, 34, 5, 3, fill: blush, stroke: .none, alpha: 0.6),
              E(.sleep, -16, 36, 5, 3, fill: blush, stroke: .none, alpha: 0.6)]
        return BuddyRig(
            pieces: p,
            pivots: [.legL: (-12, 86), .legR: (12, 86), .body: (0, 94), .armL: (-32, 50), .armR: (32, 50),
                     .head: (0, 34)],
            full: BuddyRect(x: -80, y: -66, w: 160, h: 188), barBottom: nil, feet: (0, 110), sleepBottom: 68,
            zzz: [(0, -34, 12), (16, -50, 15), (36, -68, 19)],
            style: .init(armL: 1.0, armR: 1.0, armAmp: 0.7, alternate: false, kick: 0.3, bounce: 12, step: 8),
            heart: (0, 66, 13), face: (0, 6, 28, 18), brows: "M-21 -13 L-9 -9 M21 -13 L9 -9",
            hat: (0, -36, 34), sleepHat: (-30, 0, 26), neck: (0, 36), hand: (44, 84))
    }()
}

// MARK: - Trang phục theo mùa lễ (không theo phím — giải MỘT lần lúc hiện bàn phím)

/// Mùa lễ: theme lễ đang dùng (Tết / Tết mai vàng, Giáng sinh / Giáng sinh tuyết, Halloween, Trung Thu) HOẶC
/// ngày rơi vào cửa sổ mùa. Âm lịch tra bảng nhúng 2026–2035 (không mạng).
enum BuddySeason: String, CaseIterable {
    case tet, christmas, halloween, midAutumn

    /// Theme lễ ⇒ mùa (theme thắng ngày).
    static func of(themeID: String?) -> BuddySeason? {
        switch themeID {
        case "tet", "tetMai": return .tet
        case "xmas", "xmasSnow": return .christmas
        case "halloween": return .halloween
        case "trungthu": return .midAutumn
        default: return nil
        }
    }

    /// Mùng 1 Tết (năm → (tháng, ngày) dương lịch), 2026–2035.
    /// LƯU Ý: bảng nhúng theo lịch âm Việt Nam (UTC+7) — cần đối chiếu nguồn chính thức trước khi phát hành.
    static let lunarNewYear: [Int: (Int, Int)] = [
        2026: (2, 17), 2027: (2, 6), 2028: (1, 26), 2029: (2, 13), 2030: (2, 3),
        2031: (1, 23), 2032: (2, 11), 2033: (1, 31), 2034: (2, 19), 2035: (2, 8),
    ]
    /// Rằm tháng Tám (Trung Thu), 2026–2035 — cùng lưu ý như trên.
    static let midAutumnDay: [Int: (Int, Int)] = [
        2026: (9, 25), 2027: (9, 15), 2028: (10, 3), 2029: (9, 22), 2030: (9, 12),
        2031: (10, 1), 2032: (9, 19), 2033: (9, 8), 2034: (9, 27), 2035: (9, 16),
    ]

    /// Số ngày từ 1/1/1970 (lịch Gregory) — hàm thuần, không phụ thuộc múi giờ.
    static func dayNumber(_ y: Int, _ m: Int, _ d: Int) -> Int {
        let a = (14 - m) / 12
        let yy = y + 4800 - a
        let mm = m + 12 * a - 3
        let jdn = d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
        return jdn - 2_440_588
    }

    /// Mùa theo ngày (năm, tháng, ngày) — Tết −10…+7 ngày, Giáng sinh 15–26/12, Halloween 25/10–1/11,
    /// Trung Thu ±5 ngày.
    static func of(year y: Int, month m: Int, day d: Int) -> BuddySeason? {
        let today = dayNumber(y, m, d)
        for yy in [y, y + 1] {   // Tết đầu năm sau có thể bắt đầu từ cuối tháng 1 — chỉ cần năm này + năm sau
            if let t = lunarNewYear[yy] {
                let n = dayNumber(yy, t.0, t.1)
                if today >= n - 10 && today <= n + 7 { return .tet }
            }
        }
        if m == 12 && d >= 15 && d <= 26 { return .christmas }
        if (m == 10 && d >= 25) || (m == 11 && d == 1) { return .halloween }
        if let t = midAutumnDay[y] {
            let n = dayNumber(y, t.0, t.1)
            if abs(today - n) <= 5 { return .midAutumn }
        }
        return nil
    }

    /// Theme lễ thắng; không thì theo ngày.
    static func resolve(themeID: String?, year: Int, month: Int, day: Int) -> BuddySeason? {
        of(themeID: themeID) ?? of(year: year, month: month, day: day)
    }
}

/// Trang phục: mảnh thêm (mũ, nơ, lồng đèn…) + đổi màu (áo dài đỏ ngày Tết). Hình là DỮ LIỆU như BuddyRig.
struct BuddyOutfit {
    var pieces: [BuddyPiece] = []
    var recolor: [UInt32: UInt32] = [:]

    static let red: UInt32 = 0xD7263D, gold: UInt32 = 0xF2C14E, white: UInt32 = 0xFFFFFF

    static func make(_ s: BuddySeason?, _ c: BuddyCharacter) -> BuddyOutfit {
        guard let s, let r = BuddyRig.rig(c) else { return BuddyOutfit() }
        var o = BuddyOutfit()
        let h = r.hat, sh = r.sleepHat
        switch s {
        case .tet:
            switch c {
            case .girl:   // áo dài đỏ, băng đô vàng, hoạ tiết vàng
                o.recolor = [0xFFB3C7: red, 0xFF6F91: gold]
                o.pieces = [BuddyPiece(.body, .circle(-6, 44, 2.5), fill: gold, stroke: .none),
                            BuddyPiece(.body, .circle(6, 56, 2.5), fill: gold, stroke: .none),
                            BuddyPiece(.body, .circle(-4, 66, 2.5), fill: gold, stroke: .none)]
            case .boy:    // áo đỏ + khăn đóng
                o.recolor = [0xF2B134: red]
                o.pieces = khanDong(.head, h) + khanDong(.sleep, sh)
            case .cat, .chick, .off:   // nơ đỏ ở cổ
                o.pieces = bow(.head, r.neck)
            }
        case .christmas:
            o.pieces = santa(.head, h) + santa(.sleep, sh)
        case .halloween:
            o.pieces = c == .girl ? witch(.head, h) + witch(.sleep, sh) : pumpkin(.head, h) + pumpkin(.sleep, sh)
        case .midAutumn:
            o.pieces = lantern(r.hand)
        }
        return o
    }

    private static func f(_ v: Double) -> String { String(format: "%.2f", v) }

    static func santa(_ part: BuddyPart, _ a: (x: Double, y: Double, size: Double)) -> [BuddyPiece] {
        let x = a.x, y = a.y, k = a.size
        return [
            BuddyPiece(part, .path("M\(f(x - k / 2)) \(f(y)) Q\(f(x - k * 0.1)) \(f(y - k * 1.1)) \(f(x + k * 0.55)) \(f(y - k * 0.75)) L\(f(x + k / 2)) \(f(y)) Z"),
                       fill: 0xD62828),
            BuddyPiece(part, .rrect(x - k * 0.55, y - k * 0.12, k * 1.1, k * 0.24, k * 0.12), fill: white),
            BuddyPiece(part, .circle(x + k * 0.55, y - k * 0.75, k * 0.13), fill: white),
        ]
    }

    static func witch(_ part: BuddyPart, _ a: (x: Double, y: Double, size: Double)) -> [BuddyPiece] {
        let x = a.x, y = a.y, k = a.size
        return [
            BuddyPiece(part, .ellipse(x, y, k * 0.75, k * 0.13), fill: 0x3B2B4F),
            BuddyPiece(part, .path("M\(f(x - k * 0.35)) \(f(y)) L\(f(x + k * 0.2)) \(f(y - k * 1.2)) L\(f(x + k * 0.35)) \(f(y)) Z"), fill: 0x3B2B4F),
            BuddyPiece(part, .rrect(x - k * 0.33, y - k * 0.22, k * 0.66, k * 0.12, 0), fill: 0xF28C28, stroke: .none),
        ]
    }

    static func pumpkin(_ part: BuddyPart, _ a: (x: Double, y: Double, size: Double)) -> [BuddyPiece] {
        let x = a.x, y = a.y, k = a.size
        return [
            BuddyPiece(part, .rrect(x - k * 0.06, y - k * 0.72, k * 0.12, k * 0.18, k * 0.04), fill: 0x4E7A2C),
            BuddyPiece(part, .ellipse(x, y - k * 0.28, k * 0.5, k * 0.33), fill: 0xF28C28),
            BuddyPiece(part, .path("M\(f(x)) \(f(y - k * 0.58)) L\(f(x)) \(f(y + k * 0.02)) M\(f(x - k * 0.25)) \(f(y - k * 0.52)) "
                                   + "Q\(f(x - k * 0.34)) \(f(y - k * 0.28)) \(f(x - k * 0.25)) \(f(y - k * 0.02))"),
                       fill: nil, stroke: .color(0xC75F12), width: 3),
        ]
    }

    static func khanDong(_ part: BuddyPart, _ a: (x: Double, y: Double, size: Double)) -> [BuddyPiece] {
        let x = a.x, y = a.y, k = a.size
        return [
            BuddyPiece(part, .path("M\(f(x - k * 0.55)) \(f(y)) Q\(f(x)) \(f(y - k * 0.55)) \(f(x + k * 0.55)) \(f(y)) Z"), fill: red),
            BuddyPiece(part, .path("M\(f(x - k * 0.45)) \(f(y - k * 0.12)) Q\(f(x)) \(f(y - k * 0.32)) \(f(x + k * 0.45)) \(f(y - k * 0.12))"),
                       fill: nil, stroke: .color(gold), width: 3),
        ]
    }

    static func bow(_ part: BuddyPart, _ a: (x: Double, y: Double)) -> [BuddyPiece] {
        let x = a.x, y = a.y
        return [
            BuddyPiece(part, .path("M\(f(x)) \(f(y)) L\(f(x - 11)) \(f(y - 7)) L\(f(x - 11)) \(f(y + 7)) Z M\(f(x)) \(f(y)) L\(f(x + 11)) \(f(y - 7)) L\(f(x + 11)) \(f(y + 7)) Z"),
                       fill: red),
            BuddyPiece(part, .circle(x, y, 3.5), fill: gold),
        ]
    }

    /// Lồng đèn cầm tay phải (đi theo tay khi nhảy).
    static func lantern(_ hnd: (x: Double, y: Double)) -> [BuddyPiece] {
        let x = hnd.x, y = hnd.y
        return [
            BuddyPiece(.armR, .path("M\(f(x)) \(f(y)) L\(f(x + 2)) \(f(y + 12))"), fill: nil, stroke: .ink, width: 2.5),
            BuddyPiece(.armR, .ellipse(x + 2, y + 21, 9, 8), fill: 0xE63946),
            BuddyPiece(.armR, .rrect(x - 3, y + 11, 10, 3, 1), fill: gold),
            BuddyPiece(.armR, .rrect(x - 3, y + 28, 10, 3, 1), fill: gold),
        ]
    }
}

// MARK: - Đường SVG rút gọn

/// M/m L/l H/h V/v Q/q Z/z — đủ cho bảng hình. Hàm thuần (Android y hệt).
enum BuddySVG {
    enum Cmd: Equatable {
        case move(Double, Double), line(Double, Double), quad(Double, Double, Double, Double), close
    }

    /// Mọi số trong chuỗi (theo thứ tự).
    static func numbers(_ s: String) -> [Double] {
        var out: [Double] = []
        var cur = ""
        func flush() { if let v = Double(cur) { out.append(v) }; cur = "" }
        for ch in s {
            if ch.isNumber || ch == "." { cur.append(ch) }
            else if ch == "-" { flush(); cur = "-" }
            else { flush() }
        }
        flush()
        return out
    }

    /// Lệnh tuyệt đối.
    static func parse(_ s: String) -> [Cmd] {
        var out: [Cmd] = []
        var x = 0.0, y = 0.0, sx = 0.0, sy = 0.0
        var cmd: Character = "M"
        var nums: [Double] = []
        func emit() {
            var i = 0
            let rel = cmd.isLowercase
            let c = Character(cmd.uppercased())
            func take(_ n: Int) -> [Double]? { guard i + n <= nums.count else { return nil }; defer { i += n }; return Array(nums[i..<(i + n)]) }
            switch c {
            case "Z":
                out.append(.close); x = sx; y = sy
            case "M":
                var first = true
                while let v = take(2) {
                    x = rel ? x + v[0] : v[0]; y = rel ? y + v[1] : v[1]
                    if first { out.append(.move(x, y)); sx = x; sy = y; first = false } else { out.append(.line(x, y)) }
                }
            case "L":
                while let v = take(2) { x = rel ? x + v[0] : v[0]; y = rel ? y + v[1] : v[1]; out.append(.line(x, y)) }
            case "H":
                while let v = take(1) { x = rel ? x + v[0] : v[0]; out.append(.line(x, y)) }
            case "V":
                while let v = take(1) { y = rel ? y + v[0] : v[0]; out.append(.line(x, y)) }
            case "Q":
                while let v = take(4) {
                    let cx = rel ? x + v[0] : v[0], cy = rel ? y + v[1] : v[1]
                    x = rel ? x + v[2] : v[2]; y = rel ? y + v[3] : v[3]
                    out.append(.quad(cx, cy, x, y))
                }
            default: break
            }
        }
        var token = ""
        var started = false
        for ch in s {
            if "MmLlHhVvQqZz".contains(ch) {
                if started { nums = numbers(token); emit() }
                cmd = ch; token = ""; started = true
            } else { token.append(ch) }
        }
        if started { nums = numbers(token); emit() }
        return out
    }
}
