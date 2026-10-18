// SensitiveWords — bộ lọc từ tục/nhạy cảm khỏi THANH GỢI Ý (chuẩn ngành:
// Gboard/SwiftKey đều chặn mặc định). Từ vẫn nằm trong datastore và vẫn được
// HỌC bình thường — chỉ không chủ động gợi ý khi bộ lọc bật. Từ bạo lực phổ
// thông (cướp, giết, trộm — từ vựng báo chí/đời thường) KHÔNG nằm trong lọc.
// Cặp chửi/tục ghép từ hai TỪ THƯỜNG (thằng chó, sờ chim, nâng bi…) lọc theo từ không chặn được ⇒
// bảng cặp `pairs` (SensitivePairs, sinh từ Scripts/seed-curated.txt [sensitive-bigram]): lọc bật
// thì từ đó không được gợi ý NGAY SAU từ trước tương ứng (`filter(_:after:enabled:)`) và cặp không
// thưởng ngữ cảnh (`dropPairs` — vuốt, xếp gợi ý đang gõ, Thêm dấu).
import Foundation

enum SensitiveWords {
    static func filter(_ words: [String], enabled: Bool) -> [String] {
        enabled ? words.filter { !set.contains($0.lowercased()) } : words
    }

    /// Gợi ý cho vị trí đứng SAU `prev` (từ kế tiếp / hoàn thành từ / phương án vuốt): bỏ từ nhạy cảm
    /// VÀ từ tạo cặp nhạy cảm với `prev` (`pairs`). Tắt lọc ⇒ nguyên văn.
    static func filter(_ words: [String], after prev: String?, enabled: Bool) -> [String] {
        guard enabled else { return words }
        let gated = prev.flatMap { pairs[$0.lowercased()] }
        return words.filter { w in
            let l = w.lowercased()
            return !set.contains(l) && gated?.contains(l) != true
        }
    }

    /// Chỉ phần CẶP — cho tập ngữ cảnh (từ kế tiếp cá nhân/seed làm điểm thưởng): lọc bật ⇒ cặp nhạy
    /// cảm sau `prev` không thưởng (vd "sờ" không đẩy "chim" khi vuốt / gõ dở).
    static func dropPairs(_ words: [String], after prev: String?, enabled: Bool) -> [String] {
        guard enabled, let g = prev.flatMap({ pairs[$0.lowercased()] }) else { return words }
        return words.filter { !g.contains($0.lowercased()) }
    }

    /// (prev, word) có trong bảng cặp nhạy cảm (không phân biệt hoa/thường).
    static func isPair(_ prev: String?, _ word: String) -> Bool {
        guard let prev else { return false }
        return pairs[prev.lowercased()]?.contains(word.lowercased()) == true
    }

    static let set: Set<String> = [
        "đcm", "đm", "dm", "dcm", "vcl", "vkl", "vl", "cl", "clgt", "cmnr",
        "cứt", "lồn", "cặc", "buồi", "đĩ", "điếm", "đụ", "địt",
        "tml", "sml", "dâm", "đkm", "dkm", "buscu",
        "đéo", "nứng", "chịch", "trym", "loz", "cmm", "cmn", "đmm", "dmm", "đcmm", "dcmm", "clm", "clg", "vcc", "dume",
        "cc", "đb", "lìn", "fuckboy",
        "fuck", "shit", "bitch", "wtf", "motherfucker", "asshole", "bullshit", "dick", "dickhead", "pussy",
        "bastard", "dumbass", "fucking", "fucked", "fucker", "shitty", "stfu",
    ]

    /// Phần tiếng Anh của `set` (⊂ set, test). enlexicon.bin chặn hết từ tục tiếng Anh (gen-enlexicon
    /// BLOCK) ⇒ gợi ý tiếng Anh trên máy tính lấy từ đây khi TẮT lọc (WordPrediction.candidates);
    /// từ nhẹ (damn, hell, piss) KHÔNG lọc. Song sinh windows word_prediction.cpp kEnglishVulgar.
    static let english: [String] = [
        "asshole", "bastard", "bitch", "bullshit", "dick", "dickhead", "dumbass", "fuck", "fucked", "fucker",
        "fucking", "motherfucker", "pussy", "shit", "shitty", "stfu", "wtf",
    ]
    /// Tần suất (thang enlexicon 0–255) khi trộn `english` vào gợi ý tiếng Anh lúc TẮT lọc — như sàn
    /// vuốt từ tục (SwipeVocab.sensitiveFloor): sau từ thường phổ biến cùng tiền tố ("fu" → full, fun…),
    /// lên đầu khi tiền tố đủ riêng ("fuc" → fuck).
    static let englishFreq = 150

    // BEGIN GENERATED SensitivePairs — Scripts/gen-seed-data.py từ Scripts/seed-curated.txt [sensitive-bigram]; KHÔNG sửa tay.
    static let pairs: [String: Set<String>] = [
        "bú": ["cu"],
        "bố": ["mày"],
        "chó": ["chết", "má"],
        "chúa": ["phòng"],
        "chơi": ["cave", "gái"],
        "clip": ["nóng"],
        "cái": ["mâm"],
        "cò": ["hương"],
        "damn": ["it"],
        "fuck": ["boy"],
        "kích": ["dục"],
        "láo": ["chó"],
        "lùn": ["tịt"],
        "lộ": ["clip"],
        "massage": ["kích"],
        "má": ["mày"],
        "móc": ["lốp"],
        "mẹ": ["mày"],
        "như": ["chó", "cột"],
        "nâng": ["bi"],
        "não": ["phẳng"],
        "nện": ["nhau"],
        "phang": ["nhau"],
        "phim": ["heo"],
        "piss": ["off"],
        "quay": ["tay"],
        "rờ": ["cu"],
        "sờ": ["chim", "cu"],
        "thằng": ["chó", "khốn", "mặt", "ngu", "điên"],
        "vãi": ["đái"],
        "xoạc": ["nhau"],
        "óc": ["lợn"],
        "đù": ["má"],
        "đậu": ["má"],
        "đồ": ["chó", "khốn", "ngu", "điên"],
    ]
    // END GENERATED SensitivePairs
}
