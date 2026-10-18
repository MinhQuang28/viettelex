// SensitiveWords — bộ lọc từ tục/nhạy cảm khỏi THANH GỢI Ý (chuẩn ngành:
// Gboard/SwiftKey đều chặn mặc định). Từ vẫn nằm trong datastore và vẫn được
// HỌC bình thường — chỉ không chủ động gợi ý khi bộ lọc bật. Từ bạo lực phổ
// thông (cướp, giết, trộm — từ vựng báo chí/đời thường) KHÔNG nằm trong lọc.
import Foundation

enum SensitiveWords {
    static func filter(_ words: [String], enabled: Bool) -> [String] {
        enabled ? words.filter { !set.contains($0.lowercased()) } : words
    }

    static let set: Set<String> = [
        "đcm", "đm", "dm", "dcm", "vcl", "vkl", "vl", "cl", "clgt", "cmnr",
        "cứt", "lồn", "cặc", "buồi", "đĩ", "điếm", "đụ", "địt",
        "tml", "sml", "dâm", "đkm", "dkm", "buscu",
        "đéo", "nứng", "chịch", "trym", "loz", "cmm", "cmn", "đmm", "dmm", "đcmm", "dcmm", "clm", "clg", "vcc", "dume",
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
}
