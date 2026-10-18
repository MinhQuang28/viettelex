// KeyVariants — giữ phím ở bàn SỐ / KÝ HIỆU ra hàng biến thể như stock iOS (bàn US
// English iOS 17/18): giữ `"` ⇒ " ” “ „ » «, giữ `$` ⇒ $ ₫ € … Phần THUẦN (không UIKit);
// view dùng chung popup trượt-chọn của DomainPopup (layout, chỉ số dưới ngón, luật huỷ).
//
// Thứ tự = từ phím ra ngoài: ô 0 là ký tự GỐC (mặc định được chọn khi popup mở, như stock),
// các biến thể loe dần theo hướng hàng mở (DomainPopup.layout tự lật khi sát mép phải).
//
// Bàn CHỮ không có biến thể (quyết định 30/09/2026): giữ phím chữ đã là ký tự phụ số/ký hiệu
// (KeyAlternates — q…p → 1…0, a → @…) và "," → "."; dấu tiếng Việt gõ bằng Telex/VNI nên
// hàng è é ê ë… của stock vừa trùng cử chỉ vừa thừa. iPad: phím số/ký hiệu có nhãn phụ xám
// (vuốt xuống / giữ ra nhãn phụ) giữ nguyên hành vi đó — chỉ phím KHÔNG có nhãn phụ mới có
// biến thể.
enum KeyVariants {
    /// Bảng biến thể (khoá = ký tự gốc trên phím). ₫ đứng ngay sau $ cho người dùng Việt.
    static let table: [String: [String]] = [
        // bàn 123
        "1": ["1", "¹", "½", "⅓", "¼", "⅛"],
        "2": ["2", "²", "⅔"],
        "3": ["3", "³", "¾", "⅜"],
        "4": ["4", "⁴"],
        "5": ["5", "⅝"],
        "7": ["7", "⅞"],
        "0": ["0", "°"],
        "-": ["-", "–", "—", "•"],
        // "=" ngay cạnh "/" (field report 18/10/2026: "=" chỉ ở bàn #+= iPhone, quá sâu cho
        // "Hiện kết quả phép tính") — giữ "/" rồi trượt một ô.
        "/": ["/", "=", "\\"],
        "$": ["$", "₫", "€", "£", "¥", "₩", "₹", "₽", "¢"],
        "&": ["&", "§"],
        ".": [".", "…"],
        "?": ["?", "¿"],
        "!": ["!", "¡"],
        "'": ["'", "‘", "’", "`"],
        "\"": ["\"", "”", "“", "„", "»", "«"],
        // bàn #+=
        "%": ["%", "‰"],
        "=": ["=", "≠", "≈"],
        "+": ["+", "±"],
        "<": ["<", "≤", "‹", "«"],
        ">": [">", "≥", "›", "»"],
        "*": ["*", "×"],
        "~": ["~", "≈"],
        "€": ["€", "$", "£", "¥", "₫"],
        "•": ["•", "·", "°"],
    ]

    /// Hàng biến thể của phím `key` ở bàn số/ký hiệu; rỗng = không có (chạm-giữ như cũ).
    /// Bàn chữ luôn rỗng (xem đầu file). Ô số (keypad) không có.
    static func variants(for key: String, symbolPlane: Bool, numericField: Bool = false) -> [String] {
        guard symbolPlane, !numericField else { return [] }
        return table[key] ?? []
    }
}
