// "Số từ gợi ý" (Minh Trí 10/2026) — THUẦN, song sinh android/keyboard/.../SuggestionCount.kt.
// Khoá App Group `suggestionCount` (Int): 0 = Tự động (như trước: 3 ô, cả iPhone lẫn iPad),
// 1…5 = đúng chừng ấy ô. Cài đặt: Tính Năng → Gợi ý (FeaturePages GoiYPage, nút − / +).
// Bàn phím: controller xin N ứng viên (resolved), thanh gợi ý hiện `fit` ô — ô hẹp hơn
// `minSlotWidth` (bar chật: máy nhỏ + nút 😊/📋) thì bớt ô cho vừa, không bao giờ < 1.
// Tài liệu: iOS/docs/IOS-SUGGESTIONS.md mục "Số từ gợi ý".
import CoreGraphics
import Foundation

enum SuggestionCount {
    static let key = "suggestionCount"
    /// 0 = Tự động.
    static let auto = 0
    static let max = 5
    static let range = 0...max
    /// Số ô ở chế độ Tự động — hành vi trước khi có cài đặt (cả điện thoại lẫn máy tính bảng).
    static let autoSlots = 3
    /// Bề ngang tối thiểu một ô (vùng chạm ~44pt theo HIG); bar không đủ ⇒ bớt ô.
    static let minSlotWidth: CGFloat = 44

    /// Giá trị thô (UserDefaults / sao lưu) → 0…5; sai kiểu / ngoài phạm vi ⇒ kẹp, thiếu ⇒ Tự động.
    static func parse(_ raw: Any?) -> Int {
        guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else {
            if let s = raw as? String, let i = Int(s) { return Swift.min(Swift.max(i, 0), max) }
            return auto
        }
        return Swift.min(Swift.max(n.intValue, 0), max)
    }

    static func load(_ d: UserDefaults?) -> Int { parse(d?.object(forKey: key)) }

    /// Số ô muốn hiện (chưa xét bề rộng): Tự động ⇒ `autoSlots`.
    static func resolved(_ setting: Int) -> Int {
        setting <= 0 ? autoSlots : Swift.min(setting, max)
    }

    /// Số ô thật sự hiện trên bar rộng `barWidth`: không quá `resolved`, mỗi ô ≥ `minWidth`,
    /// tối thiểu 1. Bề rộng ≤ 0 (chưa layout) ⇒ `resolved`.
    static func fit(_ setting: Int, barWidth: CGFloat, minWidth: CGFloat = minSlotWidth) -> Int {
        let want = resolved(setting)
        guard barWidth > 0, minWidth > 0 else { return want }
        let room = Int((barWidth / minWidth).rounded(.down))
        return Swift.max(1, Swift.min(want, room))
    }

    /// Nút − / +: Tự động → 1 → … → 5 (− ở 1 về Tự động; dừng ở hai đầu).
    static func step(_ v: Int, by d: Int) -> Int { Swift.min(Swift.max(v + d, 0), max) }
}
