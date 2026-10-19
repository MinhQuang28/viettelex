// "Đập chuột" (Whack-a-mole) — trò chơi khi gõ của theme "Đập chuột" (theme JSON "game":"mole", Phil 19/10/2026).
//
// Đang gõ, thỉnh thoảng một chú chuột thò lên từ MỘT phím chữ — đúng phím người dùng SẮP gõ (đoán từ chữ đang
// soạn + ứng viên trên thanh gợi ý; không có thì một chữ phổ biến). Gõ trúng phím đó lúc chuột đang lên ⇒ "éc"
// (chỉ khi Âm thanh phím bật) + bẹp + sao + cộng điểm. Không trúng trong `upTime` ⇒ thụt xuống. Phím vẫn gõ bình
// thường — chuột chỉ là lớp vẽ, không bao giờ nhận chạm (MoleOverlay.swift).
//
// File này THUẦN (không UIKit) — app (thẻ thống kê, id "game") + bàn phím + test dùng chung. Android
// keyboard/.../MoleGame.kt y hệt (cùng hằng số, cùng LCG FxRandom ⇒ cùng chuỗi chọn phím với cùng seed).
//
// Luật hiệu năng (feature-flags-perf-policy): theme khác ⇒ bàn phím không tạo overlay (0 lớp, 0 việc). Bật: mỗi
// phím = MoleGame.key (vài phép so + cộng, O(1)); đoán phím + vẽ chỉ lúc chuột xuất hiện (cách nhau 2–6 s gõ),
// chạy sau sự kiện chạm (DispatchQueue.main.async). Tắt khi Nguồn điện thấp / VoiceOver.
import Foundation

enum MoleEvent: Equatable {
    case none
    /// Đến lúc thả chuột: overlay đoán phím rồi gọi `place` (hoặc `cancelSpawn`).
    case spawn
    /// Đập trúng chuột trên phím này.
    case hit(Character)
    /// Chuột thụt xuống không bị đập (hết giờ / nghỉ gõ) — chuỗi trúng về 0.
    case duck
}

/// Máy trạng thái theo mốc thời gian (giây) — thuần, test được.
struct MoleGame {
    /// Giá trị "game" của theme JSON bật trò này.
    static let themeGame = "mole"
    /// Chuột đứng lên tối đa chừng này (giây).
    static let upTime = 1.5
    /// Nghỉ gõ chừng này ⇒ chuột thụt + không thả con mới tới khi gõ lại (bắt đầu đếm lại).
    static let idleAfter = 3.0
    /// Khoảng thời gian GÕ giữa hai con (ngẫu nhiên đều trong [min, max]).
    static let spawnMin = 2.0, spawnMax = 6.0
    /// Khe giữa hai phím được tính vào "thời gian gõ" tối đa chừng này (ngừng tay lâu không làm chuột ra sớm).
    static let gapCap = 1.0

    private(set) var rng: FxRandom
    /// Chữ (a–z) của chuột đang lên; nil = không có chuột.
    private(set) var mole: Character?
    private(set) var moleSince = 0.0
    private(set) var lastKey: Double?
    /// Thời gian gõ tích luỹ từ lần chuột trước (giây).
    private(set) var typed = 0.0
    private(set) var nextSpawn = 0.0
    /// Đã báo `.spawn`, chờ overlay đặt chuột.
    private(set) var pending = false
    private(set) var hits = 0, streak = 0, bestStreak = 0, misses = 0, spawns = 0

    init(seed: Int) {
        rng = FxRandom(seed: seed)
        nextSpawn = drawGap()
    }

    private mutating func drawGap() -> Double { Self.spawnMin + (Self.spawnMax - Self.spawnMin) * rng.next() }

    /// Một phím vừa gõ lúc `t`. `letter` = chữ thường a–z của phím chữ, nil = phím khác (cách, xoá…) — vẫn tính là
    /// đang gõ nhưng không bao giờ "đập". Đường nóng: chỉ so + cộng.
    mutating func key(_ letter: Character?, at t: Double) -> MoleEvent {
        let gap = lastKey.map { t - $0 } ?? 0
        let idle = gap >= Self.idleAfter
        lastKey = t
        if let m = mole {
            // Đồng hồ chưa kịp chạy (main bận): hết giờ / sau quãng nghỉ ⇒ coi như trượt.
            if t - moleSince >= Self.upTime || idle { miss(); return .duck }
            guard let l = letter, l == m else { return .none }
            mole = nil
            hits += 1; streak += 1
            if streak > bestStreak { bestStreak = streak }
            rearm()
            return .hit(m)
        }
        if idle { typed = 0; return .none }       // gõ lại sau khi nghỉ: đếm lại từ đầu
        typed += min(max(gap, 0), Self.gapCap)
        guard !pending, typed >= nextSpawn else { return .none }
        pending = true
        return .spawn
    }

    /// Overlay đã chọn phím + vẽ chuột lúc `t`.
    mutating func place(_ k: Character, at t: Double) {
        guard pending else { return }
        pending = false
        mole = k; moleSince = t; spawns += 1
    }

    /// Không đặt được (không ở bảng chữ / chưa có khung phím) ⇒ đợi một quãng gõ mới.
    mutating func cancelSpawn() {
        pending = false
        rearm()
    }

    /// Đồng hồ (MỘT lượt hẹn sau upTime mỗi con): hết giờ hoặc đã nghỉ gõ ⇒ thụt xuống (trượt).
    mutating func tick(at t: Double) -> MoleEvent {
        guard mole != nil else { return .none }
        let idle = lastKey.map { t - $0 >= Self.idleAfter } ?? true
        if t - moleSince >= Self.upTime - 1e-9 || idle { miss(); return .duck }
        return .none
    }

    /// Đổi bảng phím / ẩn bàn phím: dẹp chuột, không tính trượt.
    mutating func hide() {
        let had = mole != nil || pending
        mole = nil; pending = false
        if had { rearm() }
    }

    /// Chọn phím cho con sắp thả (dùng RNG của ván — tất định theo seed).
    mutating func chooseTarget(prefix: String, candidates: [String], allowed: (Character) -> Bool = { _ in true }) -> Character {
        MoleTarget.next(prefix: prefix, candidates: candidates, rng: &rng, allowed: allowed)
    }

    #if DEBUG
    /// Test: coi như đến lúc thả (bỏ qua quãng gõ chờ), giữ nguyên điểm.
    mutating func debugArm() { mole = nil; pending = true }
    #endif

    private mutating func miss() {
        mole = nil
        streak = 0
        misses += 1
        rearm()
    }

    private mutating func rearm() {
        typed = 0
        nextSpawn = drawGap()
    }
}

/// Đoán phím sắp gõ (thuần). Gọi CHỈ lúc thả chuột (vài giây một lần), không mỗi phím.
enum MoleTarget {
    /// Chữ phổ biến đầu/giữa âm tiết tiếng Việt (lặp = trọng số) — khi không có ứng viên.
    static let common: [Character] = Array("nnnhhhaaattccciiggoommuuyed")

    /// Bỏ dấu tiếng Việt + thường hoá ("Đường" → "duong"). Android: Normalizer NFD bỏ dấu + đ→d (cùng kết quả).
    static func fold(_ s: String) -> String {
        s.lowercased()
            .replacingOccurrences(of: "đ", with: "d")
            .folding(options: .diacriticInsensitive, locale: Locale(identifier: "en_US_POSIX"))
    }

    static func isAZ(_ c: Character) -> Bool { c >= "a" && c <= "z" && c.unicodeScalars.count == 1 }

    /// Chữ kế tiếp: ứng viên ĐẦU TIÊN (thứ tự thanh gợi ý) có dạng không dấu bắt đầu bằng `prefix` (không dấu) và dài
    /// hơn ⇒ chữ ngay sau. Không có ⇒ chữ phổ biến (RNG). Luôn a–z (không bao giờ phím chức năng).
    static func next(prefix: String, candidates: [String], rng: inout FxRandom,
                     allowed: (Character) -> Bool = { _ in true }) -> Character {
        let p = Array(fold(prefix))
        for c in candidates {
            let f = Array(fold(c))
            guard f.count > p.count, Array(f[0..<p.count]) == p else { continue }
            let ch = f[p.count]
            if isAZ(ch), allowed(ch) { return ch }
        }
        let pool = common.filter(allowed)
        let from = pool.isEmpty ? common : pool
        let i = min(from.count - 1, Int(rng.next() * Double(from.count)))
        return from[max(0, i)]
    }
}

/// Thống kê lưu App Group: số chuột đập HÔM NAY (ngày địa phương), chuỗi trúng dài nhất, tổng. Bàn phím ghi (sau
/// sự kiện chạm), app đọc cho thẻ "Hôm nay đập N con chuột 🐭". Android cùng khoá (SharedPreferences).
struct MoleStats: Equatable {
    static let dayKey = "moleDay", todayKey = "moleToday", bestKey = "moleBest", totalKey = "moleTotal"
    /// yyyymmdd (lịch địa phương).
    var day = 0
    var today = 0
    var best = 0
    var total = 0

    static func dayNumber(_ date: Date = Date(), calendar: Calendar = .current) -> Int {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return (c.year ?? 0) * 10000 + (c.month ?? 0) * 100 + (c.day ?? 0)
    }

    /// Số chuột đập trong ngày `d` (ngày khác ⇒ 0).
    func count(on d: Int) -> Int { d == day ? today : 0 }

    /// Ghi `n` lần trúng + chuỗi hiện tại vào ngày `d` (sang ngày mới ⇒ đếm lại "hôm nay").
    mutating func record(hits n: Int, streak: Int, day d: Int) {
        if d != day { day = d; today = 0 }
        today += n
        total += n
        if streak > best { best = streak }
    }

    static func load(_ d: UserDefaults?) -> MoleStats {
        guard let d else { return MoleStats() }
        return MoleStats(day: d.integer(forKey: dayKey), today: d.integer(forKey: todayKey),
                         best: d.integer(forKey: bestKey), total: d.integer(forKey: totalKey))
    }

    func save(_ d: UserDefaults?) {
        d?.set(day, forKey: Self.dayKey)
        d?.set(today, forKey: Self.todayKey)
        d?.set(best, forKey: Self.bestKey)
        d?.set(total, forKey: Self.totalKey)
    }
}

/// Bật / tắt (thuần): theme đang dùng có "game":"mole" + có Plus + không Nguồn điện thấp + không VoiceOver.
enum MolePolicy {
    static func active(game: String?, plus: Bool, lowPower: Bool, voiceOver: Bool) -> Bool {
        game == MoleGame.themeGame && plus && !lowPower && !voiceOver
    }

    /// "Éc" chỉ phát khi Âm thanh phím (đường âm riêng) đang bật — tắt ⇒ im.
    static func shouldSqueak(customSound: Bool) -> Bool { customSound }
}
