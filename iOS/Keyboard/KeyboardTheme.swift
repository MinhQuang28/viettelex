import UIKit
import CoreText

// Theme bàn phím — MỌI token màu ở một chỗ (dùng chung Keyboard + App + test).
// Android giữ bảng y hệt ở android/keyboard/.../KeyboardTheme.kt — sửa màu thì
// sửa cả hai. Vẽ phím vẫn phẳng: không bóng, không blur runtime, không offscreen
// pass (xem lịch sử bỏ shadow trong KeyboardView). Kính = để lộ backdrop mờ SẴN
// CÓ của hệ thống + phím bán trong suốt, không tự blur.

/// Màu RGBA thuần (0…1) — tách khỏi UIColor để test tính tương phản.
struct RGBA: Equatable {
    var r: Double, g: Double, b: Double, a: Double = 1
    init(r: Double, g: Double, b: Double, a: Double = 1) {
        self.r = r; self.g = g; self.b = b; self.a = a
    }
    init(hex: UInt32, alpha: Double = 1) {
        r = Double((hex >> 16) & 0xFF) / 255
        g = Double((hex >> 8) & 0xFF) / 255
        b = Double(hex & 0xFF) / 255
        a = alpha
    }
    static let white = RGBA(hex: 0xFFFFFF), black = RGBA(hex: 0x000000)
    func alpha(_ x: Double) -> RGBA { RGBA(r: r, g: g, b: b, a: a * x) }
    var ui: UIColor { UIColor(red: r, green: g, blue: b, alpha: a) }

    /// Trộn màu này (có alpha) lên nền đục.
    func over(_ bg: RGBA) -> RGBA {
        RGBA(r: r * a + bg.r * (1 - a), g: g * a + bg.g * (1 - a), b: b * a + bg.b * (1 - a))
    }
    /// Độ chói tương đối WCAG 2.x.
    var luminance: Double {
        func ch(_ c: Double) -> Double { c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
    }
    /// Tỉ lệ tương phản WCAG (1…21), cả hai màu coi như đục.
    static func contrast(_ x: RGBA, _ y: RGBA) -> Double {
        let a = x.luminance, b = y.luminance
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}

/// Bộ token một theme đã giải (theo sáng/tối hệ thống + ảnh nền).
struct KeyboardPalette: Equatable {
    /// nil = nền trong suốt (backdrop hệ thống lộ ra: theme Hệ thống, Kính).
    var background: RGBA?
    var keyFill: RGBA
    /// Phím chức năng khi ĐÈ (iOS 26+: mọi phím cùng nền, đè thì sẫm lại).
    var specialFill: RGBA
    var ink: RGBA
    var trail: RGBA
    /// Chữ/icon thanh gợi ý.
    var barInk: RGBA
    var balloon: RGBA
    /// Phím return dạng hành động (go/search/send…).
    var accent: RGBA
    var accentInk: RGBA
    /// Viền phím 1pt (chỉ theme tương phản cao) — borderWidth không gây offscreen.
    var keyBorder: RGBA?
    /// Chữ sáng trên nền tối → icon hệ thống, emoji plane, … đi theo nhánh tối.
    var isDark: Bool
    /// Chữ/icon TRÊN PHÍM sau hai thanh trượt độ trong suốt; nil = `ink`.
    /// `ink` giữ nguyên cho balloon/popup — phản hồi khi gõ luôn rõ.
    var keyLabel: RGBA? = nil
    /// Hệ số alpha của bề mặt PHÍM (nền phím, viền, phím shift bật) theo độ trong suốt phím
    /// (1 = như cũ). KHÔNG áp cho ảnh nền — ảnh nền có độ tối/mờ riêng (wallpaperLayer).
    var surfaceAlpha: Double = 1
    /// Chữ trên phím do người dùng chọn (Tuỳ chỉnh nâng cao) — độ trong suốt không đổi
    /// nó sang đen/trắng.
    var keyLabelPinned = false

    /// Nền phía sau phần KHÔNG phải phím (thanh gợi ý, bảng emoji, ô tìm emoji) tối hay sáng;
    /// nil = `isDark`. Khác `isDark` khi theme tự vẽ phím nhưng nền theo hệ thống.
    var surfaceDark: Bool? = nil

    /// Nền phím CHỨC NĂNG + phím cách (⇧ ⌫ 123 🌐 😊 return thường) — nil = `keyFill` (iOS 26+:
    /// mọi phím cùng nền). Chỉ theme tô phím chức năng riêng (Xanh lá) đặt. Android cùng ý nghĩa.
    var functionFill: RGBA? = nil
    /// Chữ/icon trên phím chức năng (đã áp độ trong suốt ký tự) — nil = `keyInk`. Trắng hồng.
    var functionInk: RGBA? = nil
    /// Hoạ tiết vẽ MỘT lần vào lớp nền (tim / hoa) — nil = nền phẳng. Không vẽ gì mỗi phím.
    var decoration: KeyboardDecoration? = nil
    /// Người dùng chọn "Màu nhấn" (KeyStyle.accentColor) ⇒ phím ⇧ bật / Caps tô `accent` + `accentInk`
    /// (mặc định: nền trắng glyph đen như stock). Android cùng ý nghĩa (`accentShift`).
    var accentShift = false

    var keyInk: RGBA { keyLabel ?? ink }
    var fnFill: RGBA { functionFill ?? keyFill }
    var fnInk: RGBA { functionInk ?? keyInk }

    /// "Nền theo hệ thống": bỏ nền theme (vật liệu bàn phím hệ thống lộ ra, cùng màu dải
    /// 🌐/🎤), GIỮ màu phím. Chữ thanh gợi ý đổi đen/trắng nếu không đọc được trên nền đó.
    func onSystemBackdrop(systemDark: Bool) -> KeyboardPalette {
        var p = self
        p.background = nil
        p.decoration = nil
        p.barInk = KeyboardTransparency.readable(barInk, on: KeyboardTransparency.systemBackdrop(dark: systemDark))
        p.surfaceDark = systemDark
        return p
    }

    /// Có ảnh nền: phím hơi trong để ảnh lộ ra; lớp phủ (dim) do view vẽ riêng.
    /// Độ trong được chọn sao cho chữ vẫn ≥ 4.5:1 (WCAG AA) kể cả trên ảnh TỆ NHẤT
    /// (trắng tuyền dưới theme tối, đen tuyền dưới theme sáng) và lớp phủ 0%.
    static let wallpaperKeyAlpha = 0.8
    func overWallpaper() -> KeyboardPalette {
        let worst: RGBA = isDark ? .white : .black
        func legible(_ base: RGBA) -> RGBA {
            // Theme phím vốn trong (Kính): đổi sang phím phủ đen/trắng bán trong.
            let translucent = base.a < 1
            let solid = translucent ? (isDark ? RGBA.black : RGBA.white) : base
            var a = translucent ? 0.5 : Self.wallpaperKeyAlpha
            while a < 1, RGBA.contrast(ink, solid.alpha(a).over(worst)) < 4.5 { a += 0.05 }
            return solid.alpha(min(a, 1))
        }
        var p = self
        p.keyFill = legible(keyFill)
        p.specialFill = legible(specialFill)
        p.functionFill = functionFill.map(legible)
        p.background = nil
        p.decoration = nil
        return p
    }
    /// Màu lớp phủ ảnh nền: tối cho theme tối, sáng cho theme sáng.
    var wallpaperOverlay: RGBA { isDark ? .black : .white }

    /// Cách vẽ ảnh nền (mô hình vẽ thuần — KeyboardView + bản xem trước dùng chung): ảnh luôn
    /// ĐỤC, lớp phủ theo "Độ tối lớp phủ". Độ trong suốt phím KHÔNG làm mất ảnh nền (bug 100%
    /// trước đây: ảnh + lớp phủ nhân surfaceAlpha ⇒ 0 ⇒ ảnh biến mất).
    func wallpaperLayer(dim: Int) -> (imageAlpha: Double, overlay: RGBA) {
        (1, wallpaperOverlay.alpha(Double(max(0, min(80, dim))) / 100))
    }
}

/// Độ trong suốt (Cài đặt → Giao diện), hai thanh độc lập 0…100%:
/// - phím: nền phím + viền (+ nền theme khi KHÔNG có ảnh nền); 100% = phím vô hình, chỉ còn
///   chữ. Ảnh nền luôn giữ nguyên (đã có độ tối/mờ riêng).
/// - ký tự: chữ/icon/nhãn phụ trên phím; 100% = phím trơn không chữ.
/// Áp bằng alpha của MÀU lúc dựng palette — không alpha nhóm (không offscreen pass,
/// không làm mờ chữ theo), 0 chi phí mỗi phím. Balloon/popup không đổi.
/// Android giữ y hệt ở android/keyboard/.../KeyboardTheme.kt.
enum KeyboardTransparency {
    static let keyboardKey = "keyboardTransparency"
    static let labelKey = "keyLabelTransparency"
    /// Chữ trên phím ≥ 18pt = "chữ lớn" WCAG → ngưỡng 3:1. Dưới ngưỡng (vì nền đã
    /// trong suốt, lộ nền khác tông) mới đổi sang đen/trắng.
    static let minLabelContrast = 3.0

    static func clamp(_ v: Int) -> Int { max(0, min(100, v)) }
    /// 0% → 1 (như cũ), 100% → 0.
    static func alpha(_ pct: Int) -> Double { 1 - Double(clamp(pct)) / 100 }

    /// Nền hệ thống sau input view: iOS luôn có lớp kính bàn phím (không gỡ được) —
    /// tối đo từ stock iOS 27 (#202020), sáng xấp xỉ kính sáng.
    static func systemBackdrop(dark: Bool) -> RGBA { dark ? RGBA(hex: 0x202020) : RGBA(hex: 0xD4D6DC) }

    /// Giữ `ink` nếu còn đọc được trên `bg`; không thì đen/trắng (cái tương phản hơn).
    static func readable(_ ink: RGBA, on bg: RGBA) -> RGBA {
        guard RGBA.contrast(ink, bg) < minLabelContrast else { return ink }
        let alt: RGBA = RGBA.contrast(.white, bg) >= RGBA.contrast(.black, bg) ? .white : .black
        return RGBA.contrast(alt, bg) > RGBA.contrast(ink, bg) ? alt : ink
    }
}

extension KeyboardPalette {
    func withTransparency(keyboard: Int, labels: Int, systemDark: Bool,
                          wallpaper: Bool) -> KeyboardPalette {
        typealias T = KeyboardTransparency
        let k = T.clamp(keyboard), l = T.clamp(labels)
        guard k > 0 || l > 0 else { return self }
        var p = self
        var label = ink
        if k > 0 {
            let s = T.alpha(k)
            p.surfaceAlpha = s
            p.background = background?.alpha(s)
            p.keyFill = keyFill.alpha(s)
            p.specialFill = specialFill.alpha(s)
            p.accent = accent.alpha(s)
            p.keyBorder = keyBorder?.alpha(s)
            p.functionFill = functionFill?.alpha(s)
            if let d = decoration { p.decoration?.color = d.color.alpha(s) }
            // Nền thật sau chữ ≈ lớp nền mới trên backdrop hệ thống. Ảnh nền vẫn hiện nguyên
            // ⇒ ước lượng bằng tông lớp phủ (tối cho theme tối, sáng cho theme sáng).
            let sys = T.systemBackdrop(dark: systemDark)
            let behind = (wallpaper ? wallpaperOverlay : p.background)?.over(sys) ?? sys
            if !keyLabelPinned {
                label = T.readable(ink, on: p.keyFill.over(behind))
                p.functionInk = functionInk.map { T.readable($0, on: p.fnFill.over(behind)) }
            }
            p.barInk = T.readable(barInk, on: behind)
            p.accentInk = T.readable(accentInk, on: p.accent.over(behind))
            if label != ink { p.isDark = label.luminance > 0.5 }
        }
        let la = T.alpha(l)
        p.keyLabel = label.alpha(la)
        p.functionInk = p.functionInk?.alpha(la)
        p.accentInk = p.accentInk.alpha(la)
        return p
    }
}

enum KeyboardTheme: String, CaseIterable {
    case system, oled, contrast, peach, mint, sky, lavender, glass
    // Bộ 10/2026 (gợi ý từ các theme cộng đồng được thích nhất — thiết kế riêng, không dùng tài
    // sản của ai, không nhân vật có thương hiệu). rawValue = giá trị lưu, trùng id Android.
    case blackHeart, greenFloral, sage, neon, pink
    // "Trắng hồng" (pureWhite, 1.5.12) đã bỏ 10/2026: chỉ là Hệ thống + màu nhấn hồng ⇒ chuyển sang
    // Hệ thống + Màu nhấn D6336C (KeyStyleStore.migrate). Id lạ / đã bỏ luôn về Hệ thống.

    var title: String {
        switch self {
        case .system: return L("Hệ thống")
        case .oled: return L("Tối OLED")
        case .contrast: return L("Tương phản cao")
        case .peach: return L("Hồng đào")
        case .mint: return L("Bạc hà")
        case .sky: return L("Trời xanh")
        case .lavender: return L("Oải hương")
        case .glass: return L("Kính")
        case .blackHeart: return L("Tim đen")
        case .greenFloral: return L("Hoa xanh")
        case .sage: return L("Xanh lá")
        case .neon: return L("Neon")
        case .pink: return L("Hồng")
        }
    }

    /// Bo góc mặc định của theme (% như KeyStyle.corner) khi người dùng chưa chỉnh — nil = bo stock.
    /// "Hồng": phím tròn trịa. Android cùng giá trị.
    var defaultCorner: Int? { self == .pink ? 70 : nil }

    /// Theme tự vẽ nền (OLED, tương phản cao, pastel) — có tuỳ chọn "Nền theo hệ thống".
    /// Hệ thống / Kính vốn trong suốt.
    var hasOwnBackground: Bool { palette(systemDark: false).background != nil }

    /// Key App Group + sao lưu của "Nền theo hệ thống" cho theme này ("systemBackdropOled"…);
    /// nil = theme vốn trong suốt (không có tuỳ chọn).
    var systemBackdropKey: String? {
        guard hasOwnBackground else { return nil }
        return "systemBackdrop" + rawValue.prefix(1).uppercased() + rawValue.dropFirst()
    }

    /// Theme cao cấp thuộc gói Plus. Tương phản cao là trợ năng → luôn miễn phí.
    var isPlus: Bool {
        switch self {
        case .system, .oled, .contrast: return false
        case .peach, .mint, .sky, .lavender, .glass,
             .blackHeart, .greenFloral, .sage, .neon, .pink: return true
        }
    }

    func palette(systemDark: Bool) -> KeyboardPalette {
        let blue = RGBA(r: 0, g: 0.478, b: 1)   // ≈ systemBlue
        switch self {
        case .system:
            // Sáng: giá trị cũ của KeyboardView. Tối: theo bàn phím STOCK iOS 27 (đo pixel
            // ảnh chụp simulator iPhone 17, English US, dark, 27/09/2026): nền #202020, MỌI
            // phím (chữ, shift, ⌫, 123, space, return) #444444, chữ trắng. Trước đây
            // #6B6B6B (phím chữ iOS ≤ 18, KeyboardKit standardButtonBackground dark) — sáng
            // hơn hẳn stock iOS 26/27 (góp ý user). Đè phím: sẫm lại về phía nền.
            return systemDark
                ? KeyboardPalette(background: nil, keyFill: RGBA(hex: 0x444444),
                                  specialFill: RGBA(hex: 0x2E2E2E), ink: .white,
                                  trail: RGBA.white.alpha(0.55), barInk: .white,
                                  balloon: RGBA(r: 0.35, g: 0.35, b: 0.35),
                                  accent: blue, accentInk: .white, keyBorder: nil, isDark: true)
                : KeyboardPalette(background: nil, keyFill: .white,
                                  specialFill: RGBA(r: 0.68, g: 0.70, b: 0.74), ink: .black,
                                  trail: blue.alpha(0.55), barInk: .black, balloon: .white,
                                  accent: blue, accentInk: .white, keyBorder: nil, isDark: false)
        case .oled:
            return KeyboardPalette(background: .black, keyFill: RGBA(hex: 0x1C1C1E),
                                   specialFill: RGBA(hex: 0x3A3A3C), ink: .white,
                                   trail: RGBA.white.alpha(0.6), barInk: .white,
                                   balloon: RGBA(hex: 0x2C2C2E),
                                   accent: RGBA(hex: 0x0A84FF), accentInk: .white,
                                   keyBorder: nil, isDark: true)
        case .contrast:
            // Nền đen tuyền, phím đen viền trắng, chữ trắng, nhấn vàng (≥ AAA 7:1).
            return KeyboardPalette(background: .black, keyFill: RGBA(hex: 0x141414),
                                   specialFill: RGBA(hex: 0x4D4D4D), ink: .white,
                                   trail: RGBA(hex: 0xFFD60A), barInk: .white,
                                   balloon: RGBA(hex: 0x141414),
                                   accent: RGBA(hex: 0xFFD60A), accentInk: .black,
                                   keyBorder: RGBA.white.alpha(0.85), isDark: true)
        // Pastel: sáng = bảng cũ; tối = cùng họ màu, nền + phím sẫm ngả màu, chữ sáng, giữ màu nhấn
        // (trước 10/2026 pastel không đổi theo chế độ tối).
        case .peach:
            return systemDark
                ? Self.solid(bg: 0x2A1A1C, key: 0x45292D, special: 0x5C3A3F, ink: 0xFBE3E1, accent: 0xC2505A)
                : Self.solid(bg: 0xFBE3E1, key: 0xFFF8F7, special: 0xF1C4BF, ink: 0x4A2226, accent: 0xC2505A)
        case .mint:
            return systemDark
                ? Self.solid(bg: 0x10241D, key: 0x1D3B30, special: 0x2A5244, ink: 0xD9F2E7, accent: 0x1F7A5C)
                : Self.solid(bg: 0xD9F2E7, key: 0xF6FFFA, special: 0xAFDDC9, ink: 0x173B30, accent: 0x1F7A5C)
        case .sky:
            return systemDark
                ? Self.solid(bg: 0x111E2E, key: 0x1E324A, special: 0x2B4566, ink: 0xDAE9F8, accent: 0x2C6BB8)
                : Self.solid(bg: 0xDAE9F8, key: 0xF6FAFF, special: 0xB3CFEE, ink: 0x14304C, accent: 0x2C6BB8)
        case .lavender:
            return systemDark
                ? Self.solid(bg: 0x1C1830, key: 0x2E2848, special: 0x403862, ink: 0xE8E2F6, accent: 0x6A48C4)
                : Self.solid(bg: 0xE8E2F6, key: 0xFBF9FF, special: 0xCBBFEA, ink: 0x2C2345, accent: 0x6A48C4)
        // Tim đen: luôn tối — nền đen, chữ trắng, tim hồng nhỏ vẽ một lần ở lớp nền.
        case .blackHeart:
            return Self.solid(bg: 0x000000, key: 0x1A1A1A, special: 0x333333, ink: 0xFFFFFF, accent: 0xE63E7A,
                              decoration: KeyboardDecoration(motif: .hearts, color: RGBA(hex: 0xFF6FA3, alpha: 0.55)))
        // Hoa xanh: phím trắng viền xanh mảnh, chữ xanh, hoa nhỏ mờ ở nền.
        case .greenFloral:
            return systemDark
                ? Self.solid(bg: 0x0F1D15, key: 0x16291E, special: 0x21392A, ink: 0x9EE0B6, accent: 0x2E8B57,
                             border: RGBA(hex: 0x4CAF6E, alpha: 0.8),
                             decoration: KeyboardDecoration(motif: .flowers, color: RGBA(hex: 0x4CAF6E, alpha: 0.28)))
                : Self.solid(bg: 0xEEF6EF, key: 0xFFFFFF, special: 0xDCEEDF, ink: 0x256B42, accent: 0x2E8B57,
                             border: RGBA(hex: 0x4CAF6E),
                             decoration: KeyboardDecoration(motif: .flowers, color: RGBA(hex: 0x6DBE8A, alpha: 0.35)))
        // Xanh lá: phím sáng, phím chức năng + phím cách màu xanh xô thơm (sage).
        case .sage:
            return systemDark
                ? Self.solid(bg: 0x161C15, key: 0x2A3328, special: 0x3A4A36, ink: 0xE4EDE1, accent: 0x5E7F55,
                             function: 0x4A5E45)
                : Self.solid(bg: 0xE4EBE2, key: 0xFBFCFA, special: 0x93AB8C, ink: 0x24331F, accent: 0x5E7F55,
                             function: 0xA9BFA3)
        // Neon: luôn tối — nền gần đen, phím xanh mòng két sẫm viền cyan, chữ cyan.
        case .neon:
            return Self.solid(bg: 0x05080A, key: 0x0E2A2E, special: 0x15393E, ink: 0x5FF5E8, accent: 0x00BFB2,
                              accentInk: 0x000000, border: RGBA(hex: 0x00E5D4, alpha: 0.7))
        // Hồng: hồng pastel, phím bo tròn (defaultCorner).
        case .pink:
            return systemDark
                ? Self.solid(bg: 0x2B1520, key: 0x472335, special: 0x5E2F46, ink: 0xFCDDE8, accent: 0xD94680)
                : Self.solid(bg: 0xFCDDE8, key: 0xFFF5F9, special: 0xF6B8CE, ink: 0x5A1F38, accent: 0xD94680)
        case .glass:
            // Nền trong suốt: backdrop mờ của hệ thống lộ ra (miễn phí, không
            // blur tự vẽ); phím trắng bán trong kiểu Liquid Glass.
            return systemDark
                ? KeyboardPalette(background: nil, keyFill: RGBA.white.alpha(0.16),
                                  specialFill: RGBA.white.alpha(0.32), ink: .white,
                                  trail: RGBA.white.alpha(0.6), barInk: .white,
                                  balloon: RGBA(hex: 0x4A4A4E),
                                  accent: blue, accentInk: .white, keyBorder: RGBA.white.alpha(0.18),
                                  isDark: true)
                : KeyboardPalette(background: nil, keyFill: RGBA.white.alpha(0.55),
                                  specialFill: RGBA.white.alpha(0.3), ink: .black,
                                  trail: blue.alpha(0.55), barInk: .black, balloon: .white,
                                  accent: blue, accentInk: .white, keyBorder: RGBA.white.alpha(0.6),
                                  isDark: false)
        }
    }

    /// Theme nền đục một màu (pastel, Tim đen, Neon…). `special` = phím khi đè (iOS) / phím chức
    /// năng (Android); `function` = nền phím chức năng + phím cách nếu theme tô riêng.
    private static func solid(bg: UInt32, key: UInt32, special: UInt32, ink: UInt32, accent: UInt32,
                              accentInk: UInt32 = 0xFFFFFF, border: RGBA? = nil, function: UInt32? = nil,
                              functionInk: UInt32? = nil, decoration: KeyboardDecoration? = nil) -> KeyboardPalette {
        let a = RGBA(hex: accent), i = RGBA(hex: ink)
        var p = KeyboardPalette(background: RGBA(hex: bg), keyFill: RGBA(hex: key),
                                specialFill: RGBA(hex: special), ink: i,
                                trail: a.alpha(0.6), barInk: i,
                                balloon: RGBA(hex: key), accent: a, accentInk: RGBA(hex: accentInk),
                                keyBorder: border, isDark: i.luminance > 0.5)
        p.functionFill = function.map { RGBA(hex: $0) }
        p.functionInk = functionInk.map { RGBA(hex: $0) }
        p.decoration = decoration
        return p
    }
}

/// Hoạ tiết nền theme (Tim đen: tim; Hoa xanh: hoa) — MỘT path vẽ một lần vào lớp nền
/// (CAShapeLayer iOS / Drawable Android), dựng lại chỉ khi cỡ / hàng phím đổi. 0 chi phí mỗi phím,
/// không ảnh. Motif CHỈ nằm trong khe GIỮA các hàng phím (không ở thanh gợi ý, không dưới nhãn phím
/// kể cả khi phím trong suốt). Hình học thuần (test được); Android giữ y hệt (ThemeDecoration).
struct KeyboardDecoration: Equatable {
    enum Motif: String { case hearts, flowers }
    var motif: Motif
    var color: RGBA

    /// Bước ngang (pt / dp), cỡ motif tối đa, lề hai bên, cỡ nhỏ nhất còn vẽ.
    static let stepX = 64.0, motifSize = 8.0, inset = 14.0, minMotifSize = 5.0
    /// Cách mép phím tối thiểu (motif không chạm phím).
    static let clearance = 1.0
    /// Trần số motif — bàn phím rộng (iPad) giãn bước thay vì vẽ thêm.
    static let maxMotifs = 120

    struct Gap: Equatable { var center: Double; var height: Double }
    struct Placement: Equatable { var x: Double; var y: Double; var size: Double }

    /// Khe giữa các HÀNG phím từ khung phím (top, bottom): gộp phím cùng hàng (chồng dọc), khe =
    /// từ đáy hàng trên tới đỉnh hàng dưới. Không tính phía trên hàng đầu (thanh gợi ý).
    static func rowGaps(_ spans: [(top: Double, bottom: Double)]) -> [Gap] {
        var rows: [(top: Double, bottom: Double)] = []
        for s in spans.sorted(by: { $0.top < $1.top }) where s.bottom > s.top {
            if let last = rows.last, s.top < last.bottom { rows[rows.count - 1].bottom = max(last.bottom, s.bottom) }
            else { rows.append(s) }
        }
        guard rows.count > 1 else { return [] }
        return (1..<rows.count).compactMap { i in
            let h = rows[i].top - rows[i - 1].bottom
            return h > 0 ? Gap(center: rows[i - 1].bottom + h / 2, height: h) : nil
        }
    }

    /// Motif dọc từng khe (so le giữa các khe), cỡ vừa khe (chừa `clearance` mỗi bên); khe quá hẹp bỏ.
    static func placements(width w: Double, gaps: [Gap]) -> [Placement] {
        guard w > 2 * inset else { return [] }
        func layout(_ step: Double) -> [Placement] {
            var out: [Placement] = []
            for (i, g) in gaps.enumerated() {
                let size = min(motifSize, g.height - 2 * clearance)
                guard size >= minMotifSize else { continue }
                var x = inset + size / 2 + (i % 2 == 1 ? step / 2 : 0)
                while x + size / 2 <= w - inset { out.append(Placement(x: x, y: g.center, size: size)); x += step }
            }
            return out
        }
        var step = stepX
        var pts = layout(step)
        while pts.count > maxMotifs { step *= 1.15; pts = layout(step) }
        return pts
    }

    /// Path mọi motif (gốc trên-trái, y xuống).
    func path(_ placements: [Placement]) -> CGPath {
        let path = CGMutablePath()
        for c in placements {
            let s = CGFloat(c.size)
            let o = CGPoint(x: c.x, y: c.y)
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: o.x + x * s, y: o.y + y * s) }
            switch motif {
            case .hearts:
                path.move(to: p(0, 0.45))
                path.addCurve(to: p(-0.5, -0.18), control1: p(-0.35, 0.2), control2: p(-0.5, 0))
                path.addCurve(to: p(-0.22, -0.5), control1: p(-0.5, -0.38), control2: p(-0.36, -0.5))
                path.addCurve(to: p(0, -0.33), control1: p(-0.1, -0.5), control2: p(-0.03, -0.43))
                path.addCurve(to: p(0.22, -0.5), control1: p(0.03, -0.43), control2: p(0.1, -0.5))
                path.addCurve(to: p(0.5, -0.18), control1: p(0.36, -0.5), control2: p(0.5, -0.38))
                path.addCurve(to: p(0, 0.45), control1: p(0.5, 0), control2: p(0.35, 0.2))
                path.closeSubpath()
            case .flowers:
                // 5 cánh tròn không chồng nhau + nhuỵ — tô nonzero, không cần lớp thứ hai.
                for i in 0..<5 {
                    let a = Double(i) * 2 * .pi / 5 - .pi / 2
                    let r: CGFloat = 0.18
                    let cx = CGFloat(0.32 * cos(a)), cy = CGFloat(0.32 * sin(a))
                    path.addEllipse(in: CGRect(origin: p(cx - r, cy - r), size: CGSize(width: 2 * r * s, height: 2 * r * s)))
                }
                path.addEllipse(in: CGRect(origin: p(-0.12, -0.12), size: CGSize(width: 0.24 * s, height: 0.24 * s)))
            }
        }
        return path
    }
}

/// Trang "Chọn giao diện" (logic thuần — test được; Android: ThemePicker trong KeyboardTheme.kt).
enum ThemePicker {
    struct Section { let title: String; let themes: [KeyboardTheme] }
    /// "Miễn phí" (Hệ thống, OLED, Tương phản cao) rồi "Plus" (còn lại), giữ thứ tự khai báo.
    static var sections: [Section] {
        [Section(title: LK("Miễn phí"), themes: KeyboardTheme.allCases.filter { !$0.isPlus }),
         Section(title: "Plus", themes: KeyboardTheme.allCases.filter(\.isPlus))]
    }
    /// Chạm thẻ: chọn ngay nếu được phép; theme Plus bị khoá ⇒ nil (không đổi gì).
    static func select(_ t: KeyboardTheme, in s: ThemeSettings) -> ThemeSettings? {
        guard ThemeGate.allows(t) else { return nil }
        var n = s
        n.theme = t
        return n
    }

    enum Tap: Equatable { case select(ThemeSettings), openPlus }
    /// Chạm thẻ: chọn ngay; theme Plus bị khoá ⇒ mở màn VietTelex Plus (như Android).
    static func tap(_ t: KeyboardTheme, in s: ThemeSettings) -> Tap {
        select(t, in: s).map(Tap.select) ?? .openPlus
    }
}

/// Cổng Plus cho theme: dùng PlusGate thật (iOS/Shared) — `.premiumThemes` gồm
/// theme cao cấp + ảnh nền. Hệ thống/OLED/tương phản cao luôn miễn phí.
enum ThemeGate {
    static func allows(_ theme: KeyboardTheme) -> Bool {
        !theme.isPlus || PlusGate.isUnlocked(.premiumThemes)
    }
    static var allowsWallpaper: Bool { PlusGate.isUnlocked(.premiumThemes) }
}

/// Cài đặt giao diện (App Group) — app ghi, bàn phím đọc mỗi lần hiện.
struct ThemeSettings: Equatable {
    static let themeKey = "keyboardTheme"
    static let wallpaperKey = "wallpaperEnabled"
    static let dimKey = "wallpaperDim"        // % lớp phủ 0…80
    static let blurKey = "wallpaperBlur"      // bán kính mờ 0…20 (app áp khi lưu ảnh)
    static let versionKey = "wallpaperVersion" // đổi mỗi lần lưu ảnh → vứt cache
    static let cropKey = "wallpaperCrop"      // "x,y,w,h" chuẩn hoá theo ảnh gốc; thiếu = cắt giữa

    var theme: KeyboardTheme = .system
    var wallpaper = false
    var dim = 30
    var blur = 0
    var version: Double = 0
    /// Khung cắt đã chỉnh (nil = ảnh cũ / chưa chỉnh → cắt giữa). Thuộc về ẢNH (như file),
    /// nên "Khôi phục giao diện gốc" giữ nguyên.
    var crop: WallpaperCrop?
    /// Độ trong suốt phím / ký tự 0…100 (miễn phí, mọi theme).
    var keyboardTransparency = 0
    var labelTransparency = 0
    /// Theme bật "Nền theo hệ thống" (tuỳ chọn RIÊNG từng theme có nền; mặc định không theme nào).
    var systemBackdropThemes: Set<KeyboardTheme> = []
    /// "Tuỳ chỉnh nâng cao" (Plus) RIÊNG từng theme: id theme (rawValue) → KeyStyle; theme chưa chỉnh
    /// không có mục. Lưu nguyên kể cả khi hết quyền; áp qua `effectiveKeyStyle`. Id lạ (bản mới hơn)
    /// giữ nguyên để khứ hồi.
    var keyStyles: [String: KeyStyle] = [:]

    /// Tuỳ chỉnh của theme ĐANG CHỌN (màn Giao diện đọc/ghi qua đây). Gán mặc định ⇒ xoá mục.
    var keyStyle: KeyStyle {
        get { style(for: theme) }
        set { setStyle(newValue, for: theme) }
    }
    func style(for t: KeyboardTheme) -> KeyStyle { keyStyles[t.rawValue] ?? .default }
    mutating func setStyle(_ st: KeyStyle, for t: KeyboardTheme) {
        keyStyles[t.rawValue] = st.isDefault ? nil : st
    }

    /// "Khôi phục giao diện gốc": mọi chỉnh ở màn Giao diện về mặc định. Ảnh nền chỉ
    /// bỏ chọn (file giữ nguyên để bật lại); version giữ nguyên (không vứt cache vô cớ).
    func resetToDefaults() -> ThemeSettings {
        var s = ThemeSettings()
        s.version = version
        s.crop = crop
        return s
    }
    var isDefault: Bool { self == resetToDefaults() }

    /// Đọc + chuyển đổi trong bộ nhớ (KeyStyleStore.migrate — thuần, không ghi; bàn phím gọi mỗi lần
    /// hiện). App ghi bản đã chuyển một lần lúc khởi động (`migrateStored`).
    static func load(_ d: UserDefaults?) -> ThemeSettings {
        var s = ThemeSettings()
        guard let d else { return s }
        let m = KeyStyleStore.migrate(theme: d.string(forKey: themeKey),
                                      styles: KeyStyleStore.parse(d.string(forKey: KeyStyleStore.key)),
                                      legacy: KeyStyleStore.loadLegacy(d))
        // Id lạ / đã bỏ ⇒ Hệ thống (mặc định).
        if let raw = m.theme, let t = KeyboardTheme(rawValue: raw) { s.theme = t }
        s.keyStyles = m.styles
        s.wallpaper = d.bool(forKey: wallpaperKey)
        if d.object(forKey: dimKey) != nil { s.dim = max(0, min(80, d.integer(forKey: dimKey))) }
        s.blur = max(0, min(20, d.integer(forKey: blurKey)))
        s.version = d.double(forKey: versionKey)
        s.crop = WallpaperCrop(serialized: d.string(forKey: cropKey))
        s.keyboardTransparency = KeyboardTransparency.clamp(d.integer(forKey: KeyboardTransparency.keyboardKey))
        s.labelTransparency = KeyboardTransparency.clamp(d.integer(forKey: KeyboardTransparency.labelKey))
        for t in KeyboardTheme.allCases {
            if let k = t.systemBackdropKey, d.bool(forKey: k) { s.systemBackdropThemes.insert(t) }
        }
        return s
    }

    /// Chuyển đổi đã lưu MỘT lần (app, lúc khởi động): khoá KeyStyle chung cũ → theme đang chọn,
    /// "Trắng hồng" → Hệ thống + Màu nhấn hồng. Không có gì cũ ⇒ không ghi gì. Lặp lại vô hại.
    @discardableResult
    static func migrateStored(_ d: UserDefaults?) -> Bool {
        guard let d, KeyStyleStore.needsMigration(theme: d.string(forKey: themeKey),
                                                  hasLegacy: KeyStyleStore.hasLegacy(d)) else { return false }
        load(d).save(d)
        return true
    }

    func save(_ d: UserDefaults?) {
        d?.set(theme.rawValue, forKey: Self.themeKey)
        d?.set(wallpaper, forKey: Self.wallpaperKey)
        d?.set(dim, forKey: Self.dimKey)
        d?.set(blur, forKey: Self.blurKey)
        d?.set(version, forKey: Self.versionKey)
        if let crop { d?.set(crop.serialized, forKey: Self.cropKey) } else { d?.removeObject(forKey: Self.cropKey) }
        d?.set(keyboardTransparency, forKey: KeyboardTransparency.keyboardKey)
        d?.set(labelTransparency, forKey: KeyboardTransparency.labelKey)
        for t in KeyboardTheme.allCases {
            if let k = t.systemBackdropKey { d?.set(systemBackdropThemes.contains(t), forKey: k) }
        }
        // Chưa từng chỉnh: không tạo khoá (0 chi phí đọc). Đã có khoá thì ghi (kể cả "{}" sau đặt lại).
        let styles = KeyStyleStore.serialize(keyStyles)
        if styles != KeyStyleStore.empty || d?.object(forKey: KeyStyleStore.key) != nil {
            d?.set(styles, forKey: KeyStyleStore.key)
        }
        KeyStyleStore.removeLegacy(d)
    }

    /// Theme thực dùng sau cổng Plus (hết quyền → về Hệ thống, không crash/không trắng).
    var effectiveTheme: KeyboardTheme { ThemeGate.allows(theme) ? theme : .system }
    /// Tuỳ chỉnh nâng cao thực dùng: hết quyền Plus ⇒ mặc định (giá trị lưu giữ nguyên để
    /// mua lại là có lại).
    /// Bo góc chưa chỉnh ⇒ bo mặc định của theme (Hồng: tròn trịa).
    var effectiveKeyStyle: KeyStyle { effectiveStyle(for: effectiveTheme) }
    /// Như `effectiveKeyStyle` cho theme `t` bất kỳ (thẻ "Chọn giao diện" xem trước tuỳ chỉnh riêng).
    func effectiveStyle(for t: KeyboardTheme) -> KeyStyle {
        var s = ThemeGate.allowsAdvanced ? style(for: t) : .default
        if s.corner == nil { s.corner = t.defaultCorner }
        return s
    }
    /// Thẻ "Chọn giao diện": palette gốc của `t` + màu tuỳ chỉnh RIÊNG của nó (không ảnh nền / độ trong
    /// suốt / nền hệ thống — thẻ chỉ để nhận ra theme). Chưa chỉnh ⇒ đúng palette gốc.
    func cardPalette(for t: KeyboardTheme, systemDark: Bool) -> KeyboardPalette {
        let st = effectiveStyle(for: t)
        return st.applyAccent(to: st.applyColors(to: t.palette(systemDark: systemDark)))
    }
    /// Ảnh nền chỉ bật khi có quyền Plus VÀ file tồn tại.
    func wallpaperActive(fileExists: Bool) -> Bool {
        wallpaper && fileExists && ThemeGate.allowsWallpaper
    }

    /// Nền bàn phím để TRONG — lộ vật liệu bàn phím hệ thống, cùng màu dải 🌐/🎤 iOS vẽ bên
    /// dưới (kính iOS 26/27, sáng lẫn tối). Chỉ theme nền trong suốt (Hệ thống, Kính) không
    /// ảnh nền; theme có nền riêng (Tối OLED / Tương phản cao = đen tuyền, pastel) và ảnh nền
    /// tự vẽ nền ⇒ chấp nhận lệch màu với dải hệ thống. Độ trong suốt phím không đổi quyết
    /// định này (theme trong suốt vẫn trong suốt).
    func clearBackground(systemDark: Bool, wallpaperActive: Bool) -> Bool {
        !wallpaperActive && palette(systemDark: systemDark, wallpaperActive: false).background == nil
    }

    /// "Nền theo hệ thống" đang áp: theme có nền riêng + người dùng bật cho theme đó.
    /// Mặc định TẮT ⇒ 0 chi phí, như trước.
    var usesSystemBackdrop: Bool {
        effectiveTheme.hasOwnBackground && systemBackdropThemes.contains(effectiveTheme)
    }

    func palette(systemDark: Bool, wallpaperActive: Bool) -> KeyboardPalette {
        var p = effectiveTheme.palette(systemDark: systemDark)
        if usesSystemBackdrop, !wallpaperActive { p = p.onSystemBackdrop(systemDark: systemDark) }
        let style = effectiveKeyStyle
        p = style.applyColors(to: p)
        p.keyLabelPinned = style.labelColor != nil
        p = style.applyAccent(to: p)
        return (wallpaperActive ? p.overWallpaper() : p)
            .withTransparency(keyboard: keyboardTransparency, labels: labelTransparency,
                              systemDark: systemDark, wallpaper: wallpaperActive)
    }
}

// MARK: - Tuỳ chỉnh nâng cao (Plus): màu chữ/nền phím, bo góc, cỡ chữ, phông, độ đậm

/// Phông nhãn phím — CHỈ phông hệ thống (không nhúng phông: giữ nhẹ app). Id dùng chung
/// Android (Keys.KEY_LABEL_FONT); máy không có kiểu đó (Android không có "rounded") ⇒ mặc định.
enum KeyLabelFont: String, CaseIterable {
    /// SF Compact như stock (SF Pro nếu máy không có Compact).
    case standard = "default"
    case rounded, serif, mono, condensed

    var title: String {
        switch self {
        case .standard: return L("Mặc định")
        case .rounded: return L("Tròn")
        case .serif: return L("Có chân")
        case .mono: return L("Đơn cách")
        case .condensed: return L("Hẹp")
        }
    }
}

/// "Độ đậm" nhãn phím → độ đậm hệ thống.
enum KeyLabelWeight: String, CaseIterable {
    case light, regular, medium, bold, heavy

    var title: String {
        switch self {
        case .light: return L("Mỏng")
        case .regular: return L("Thường")
        case .medium: return L("Vừa")
        case .bold: return L("Đậm")
        case .heavy: return L("Rất đậm")
        }
    }
    var uiWeight: UIFont.Weight {
        switch self {
        case .light: return .light
        case .regular: return .regular
        case .medium: return .medium
        case .bold: return .bold
        case .heavy: return .heavy
        }
    }
}

/// "Tuỳ chỉnh nâng cao" ở Cài đặt → Giao diện (Plus), RIÊNG từng theme (ThemeSettings.keyStyles,
/// lưu bằng KeyStyleStore). Mọi giá trị nil/mặc định = như cũ.
/// Màu: MỘT màu dùng cho cả sáng lẫn tối (đơn giản, đoán được); "Theo theme" = nil.
/// Android giữ y hệt (android/keyboard/.../KeyStyle.kt). Áp lúc dựng palette / plane (0 chi phí mỗi phím).
struct KeyStyle: Equatable {
    // Khoá App Group CŨ (≤ 1.5.12 / iOS 1.2.17: một bộ chung cho mọi theme) — chỉ còn để chuyển đổi
    // (KeyStyleStore.loadLegacy); app xoá sau khi chuyển. Giá trị canh: -1 / 0 = mặc định.
    static let labelColorKey = "keyLabelColor"     // 0xRRGGBB; -1 = theo theme
    static let fillColorKey = "keyFillColor"       // 0xRRGGBB; -1 = theo theme
    static let cornerKey = "keyCornerRadius"       // 0…100 (% nửa cạnh ngắn); -1 = mặc định
    static let fontScaleKey = "keyFontScale"       // 80…150 (%); 0 = mặc định
    static let fontKey = "keyLabelFont"            // KeyLabelFont.rawValue
    static let weightKey = "keyLabelWeight"        // KeyLabelWeight.rawValue
    static let fontScaleMin = 80, fontScaleMax = 150

    var labelColor: UInt32? = nil
    var fillColor: UInt32? = nil
    /// "Màu nhấn": phím Nhập/hành động, ⇧ bật, vệt gõ vuốt, glyph màu nhấn; nil = theo theme.
    var accentColor: UInt32? = nil
    /// 0 = chữ nhật, 100 = tròn/viên thuốc (bán kính = nửa cạnh ngắn của phím); nil = mặc định.
    var corner: Int?
    /// 80…150 (% cỡ mặc định); nil = mặc định.
    var fontScale: Int?
    var font: KeyLabelFont = .standard
    var weight: KeyLabelWeight = .regular

    static let `default` = KeyStyle()
    var isDefault: Bool { self == .default }
    /// Có đổi chữ (phông / độ đậm / cỡ) — bật đường đo + kẹp chữ.
    var customizesLabels: Bool { font != .standard || weight != .regular || fontScale != nil }
    var labelScale: CGFloat { CGFloat(fontScale ?? 100) / 100 }

    static func parseColor(_ v: Int?) -> UInt32? {
        guard let v, v >= 0, v <= 0xFFFFFF else { return nil }
        return UInt32(v)
    }
    static func parseCorner(_ v: Int?) -> Int? {
        guard let v, v >= 0 else { return nil }
        return min(v, 100)
    }
    static func parseFontScale(_ v: Int?) -> Int? {
        guard let v, v > 0 else { return nil }
        return min(max(v, fontScaleMin), fontScaleMax)
    }

    /// Bán kính bo góc phím cỡ `size`: 0% = chữ nhật, 100% = nửa cạnh ngắn (phím vuông ⇒
    /// tròn, phím dài ⇒ viên thuốc). nil ⇒ `fallback` (bo mặc định).
    static func cornerRadius(percent: Int?, size: CGSize, fallback: CGFloat) -> CGFloat {
        guard let p = percent else { return fallback }
        let half = max(0, min(size.width, size.height)) / 2
        return half * CGFloat(min(max(p, 0), 100)) / 100
    }

    /// Màu phím theo lựa chọn (trước ảnh nền / độ trong suốt). Màu chữ do người dùng chọn
    /// được giữ nguyên; chỉ đổi nền ⇒ chữ theme đổi đen/trắng nếu không đọc được trên nền mới.
    /// Ô phóng to / popup đi theo để chữ trong đó luôn đọc được.
    func applyColors(to p: KeyboardPalette) -> KeyboardPalette {
        guard labelColor != nil || fillColor != nil else { return p }
        var q = p
        q.surfaceDark = p.surfaceDark ?? p.isDark   // thanh gợi ý / emoji giữ tông nền cũ
        if let f = fillColor {
            let c = RGBA(hex: f)
            q.keyFill = c
            // Phím khi đè: sẫm (nền sáng) / sáng (nền tối) hơn một chút.
            let toward: RGBA = c.luminance > 0.4 ? .black : .white
            q.specialFill = toward.alpha(0.18).over(c)
            q.balloon = c
            q.functionFill = nil      // màu người dùng chọn phủ MỌI phím
        }
        if let l = labelColor {
            q.ink = RGBA(hex: l)
            q.functionInk = nil       // chữ người dùng chọn: mọi phím cùng màu
        } else {
            let sys = KeyboardTransparency.systemBackdrop(dark: p.isDark)
            let behind = p.background?.over(sys) ?? sys
            q.ink = KeyboardTransparency.readable(p.ink, on: q.keyFill.over(behind))
            q.functionInk = p.functionInk.map { KeyboardTransparency.readable($0, on: q.fnFill.over(behind)) }
        }
        if RGBA.contrast(q.ink, q.balloon.over(.white)) < KeyboardTransparency.minLabelContrast {
            q.balloon = q.ink.luminance > 0.5 ? RGBA(hex: 0x3A3A3C) : .white
        }
        q.isDark = q.ink.luminance > 0.5
        return q
    }

    /// "Màu nhấn" thay màu nhấn của theme ở MỌI chỗ dùng: phím Nhập/hành động, ⇧ bật (accentShift),
    /// vệt gõ vuốt, glyph màu nhấn trên phím chức năng (theme có functionInk). Chữ trên nền nhấn giữ
    /// màu theme nếu còn đọc được (≥ 3:1), không thì đen/trắng (KeyboardTransparency.readable).
    /// Áp SAU applyColors (glyph đọc được trên nền phím mới). nil ⇒ y như cũ (0 chi phí).
    func applyAccent(to p: KeyboardPalette) -> KeyboardPalette {
        guard let a = accentColor else { return p }
        let c = RGBA(hex: a)
        var q = p
        q.accent = c
        q.accentInk = Self.accentInk(p.accentInk, on: c)
        q.trail = c.alpha(0.6)
        q.accentShift = true
        if p.functionInk != nil {
            let sys = KeyboardTransparency.systemBackdrop(dark: p.surfaceDark ?? p.isDark)
            let behind = p.background?.over(sys) ?? sys
            q.functionInk = KeyboardTransparency.readable(c, on: p.fnFill.over(behind))
        }
        return q
    }

    /// Chữ/icon trên nền nhấn `accent`: giữ `themeInk` nếu ≥ 3:1, không thì đen/trắng tương phản hơn.
    static func accentInk(_ themeInk: RGBA, on accent: RGBA) -> RGBA {
        KeyboardTransparency.readable(themeInk, on: accent)
    }
}

/// Lưu "Tuỳ chỉnh nâng cao" theo theme: MỘT khoá App Group `keyStyles` = chuỗi JSON gọn, khoá sắp xếp
/// (y hệt Android KeyStyleStore — sao lưu / iCloud chuyển nguyên chuỗi giữa hai nền tảng):
///   {"pink":{"accent":14239104,"corner":70},"system":{"fill":1056816,"font":"mono","label":16723285}}
/// Trường (đều tuỳ chọn, thiếu = mặc định): label / fill / accent = 0xRRGGBB (số thập phân),
/// corner 0…100, fontScale 80…150, font = KeyLabelFont.rawValue, weight = KeyLabelWeight.rawValue.
/// Theme không chỉnh gì thì không có mục; trống = "{}". Giá trị sai/ngoài phạm vi bị bỏ / kẹp.
enum KeyStyleStore {
    static let key = "keyStyles"
    static let empty = "{}"
    /// Màu nhấn của "Trắng hồng" (đã bỏ) — người dùng theme đó chuyển sang Hệ thống + màu này.
    static let pureWhiteAccent: UInt32 = 0xD6336C
    static let removedPureWhite = "pureWhite"
    static let legacyKeys = [KeyStyle.labelColorKey, KeyStyle.fillColorKey, KeyStyle.cornerKey,
                             KeyStyle.fontScaleKey, KeyStyle.fontKey, KeyStyle.weightKey]
    /// "Nền theo hệ thống" của Trắng hồng — xoá cùng lúc chuyển.
    static let removedBackdropKey = "systemBackdropPureWhite"

    /// Id theme hợp lệ để lưu: ASCII chữ/số/_/-, ≤ 40 ký tự (Android cùng luật).
    static func validId(_ s: String) -> Bool {
        !s.isEmpty && s.utf8.count <= 40 && s.utf8.allSatisfy {
            ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || $0 == 0x5F || $0 == 0x2D
        }
    }

    static func parse(_ raw: String?) -> [String: KeyStyle] {
        guard let raw, raw != empty, let data = raw.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return [:] }
        var out: [String: KeyStyle] = [:]
        for (id, v) in obj {
            guard validId(id), let o = v as? [String: Any] else { continue }
            func int(_ k: String) -> Int? {
                guard let n = o[k] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
                let d = n.doubleValue
                return d.isFinite && d == d.rounded() && abs(d) < 1e9 ? n.intValue : nil
            }
            var st = KeyStyle()
            st.labelColor = KeyStyle.parseColor(int("label"))
            st.fillColor = KeyStyle.parseColor(int("fill"))
            st.accentColor = KeyStyle.parseColor(int("accent"))
            st.corner = KeyStyle.parseCorner(int("corner"))
            st.fontScale = KeyStyle.parseFontScale(int("fontScale"))
            st.font = (o["font"] as? String).flatMap(KeyLabelFont.init(rawValue:)) ?? .standard
            st.weight = (o["weight"] as? String).flatMap(KeyLabelWeight.init(rawValue:)) ?? .regular
            if !st.isDefault { out[id] = st }
        }
        return out
    }

    /// Chuỗi CHUẨN (khoá sắp xếp, không khoảng trắng) — so sánh / đồng bộ theo chuỗi được.
    static func serialize(_ m: [String: KeyStyle]) -> String {
        var parts: [String] = []
        for id in m.keys.sorted() where validId(id) {
            guard let st = m[id], !st.isDefault else { continue }
            var f: [String] = []
            if let v = st.accentColor { f.append("\"accent\":\(v)") }
            if let v = st.corner { f.append("\"corner\":\(v)") }
            if let v = st.fillColor { f.append("\"fill\":\(v)") }
            if st.font != .standard { f.append("\"font\":\"\(st.font.rawValue)\"") }
            if let v = st.fontScale { f.append("\"fontScale\":\(v)") }
            if let v = st.labelColor { f.append("\"label\":\(v)") }
            if st.weight != .regular { f.append("\"weight\":\"\(st.weight.rawValue)\"") }
            parts.append("\"\(id)\":{" + f.joined(separator: ",") + "}")
        }
        return "{" + parts.joined(separator: ",") + "}"
    }

    /// Chuẩn hoá chuỗi từ file sao lưu / iCloud; nil = không phải object JSON (bỏ qua).
    static func normalize(_ raw: String) -> String? {
        guard let data = raw.data(using: .utf8),
              (try? JSONSerialization.jsonObject(with: data)) is [String: Any] else { return nil }
        return serialize(parse(raw))
    }

    static func hasLegacy(_ d: UserDefaults) -> Bool { legacyKeys.contains { d.object(forKey: $0) != nil } }

    /// Bộ chung cũ; nil = không có khoá cũ nào.
    static func loadLegacy(_ d: UserDefaults) -> KeyStyle? {
        guard hasLegacy(d) else { return nil }
        func int(_ k: String) -> Int? { (d.object(forKey: k) as? NSNumber)?.intValue }
        var s = KeyStyle()
        s.labelColor = KeyStyle.parseColor(int(KeyStyle.labelColorKey))
        s.fillColor = KeyStyle.parseColor(int(KeyStyle.fillColorKey))
        s.corner = KeyStyle.parseCorner(int(KeyStyle.cornerKey))
        s.fontScale = KeyStyle.parseFontScale(int(KeyStyle.fontScaleKey))
        s.font = d.string(forKey: KeyStyle.fontKey).flatMap(KeyLabelFont.init(rawValue:)) ?? .standard
        s.weight = d.string(forKey: KeyStyle.weightKey).flatMap(KeyLabelWeight.init(rawValue:)) ?? .regular
        return s
    }

    static func removeLegacy(_ d: UserDefaults?) {
        for k in legacyKeys { d?.removeObject(forKey: k) }
        d?.removeObject(forKey: removedBackdropKey)
    }

    static func needsMigration(theme: String?, hasLegacy: Bool) -> Bool {
        hasLegacy || theme == removedPureWhite
    }

    /// Chuyển đổi THUẦN (Android cùng quy tắc), áp lại lên kết quả của chính nó không đổi gì:
    /// 1. theme "pureWhite" (đã bỏ) ⇒ "system"; "system" chưa có màu nhấn ⇒ màu nhấn D6336C;
    /// 2. bộ chung cũ (khác mặc định) ⇒ theme ĐANG CHỌN (sau bước 1; id lạ ⇒ "system"), chỉ khi
    ///    theme đó chưa có mục riêng — không ghi đè thứ người dùng chỉnh sau này;
    /// 3. mục "pureWhite" (nếu có) bỏ.
    static func migrate(theme: String?, styles: [String: KeyStyle], legacy: KeyStyle?)
        -> (theme: String?, styles: [String: KeyStyle]) {
        let sys = KeyboardTheme.system.rawValue
        var t = theme, m = styles
        let fromPureWhite = t == removedPureWhite
        if fromPureWhite { t = sys }
        if let legacy, !legacy.isDefault {
            let id = t.flatMap(KeyboardTheme.init(rawValue:))?.rawValue ?? sys
            if m[id] == nil { m[id] = legacy }
        }
        if fromPureWhite {
            var st = m[sys] ?? .default
            if st.accentColor == nil { st.accentColor = pureWhiteAccent; m[sys] = st }
        }
        m[removedPureWhite] = nil
        return (t, m)
    }
}

/// Số đo mực chữ theo em (đo một lần mỗi phông + độ đậm, CTLine — có thay phông dự phòng như
/// lúc vẽ thật). Dùng để kẹp cỡ chữ: chữ rộng nhất vừa bề ngang phím, dấu chồng (Ễ, Ỗ) và
/// phần dưới dòng (g, ỵ) nằm trọn trong phím / ô phóng to.
struct KeyLabelEm: Equatable {
    /// Bề ngang glyph rộng nhất / em.
    var width: CGFloat
    /// Mực trên baseline / dưới baseline (đều dương) theo em.
    var ascent: CGFloat
    var descent: CGFloat
    /// Hộp dòng của phông (UIFont.ascender, −descender) theo em — UILabel căn giữa hộp này.
    var lineAscender: CGFloat
    var lineDescender: CGFloat
    var inkHeight: CGFloat { ascent + descent }

    /// Mẫu chữ có thể hiện TRÊN phím (chữ hoa có dấu mũ/móc, không dấu thanh).
    static let keySample = "WMĐÂĂÊÔƠƯgjpqy"
    /// Mẫu chữ ô phóng to / popup ký tự phụ (dấu chồng).
    static let stackedSample = "ỄẪỖẰẲỮỂgjpqyỵỹ"

    static func measure(_ font: UIFont, sample: String) -> KeyLabelEm {
        let s = font.pointSize
        guard s > 0 else { return KeyLabelEm(width: 0, ascent: 0, descent: 0, lineAscender: 0, lineDescender: 0) }
        var maxW: CGFloat = 0, top: CGFloat = 0, bottom: CGFloat = 0
        for ch in sample {
            let line = CTLineCreateWithAttributedString(NSAttributedString(string: String(ch), attributes: [.font: font]))
            let ink = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
            maxW = max(maxW, ink.maxX, CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil)))
            top = max(top, ink.maxY)
            bottom = max(bottom, -ink.minY)
        }
        return KeyLabelEm(width: maxW / s, ascent: top / s, descent: bottom / s,
                          lineAscender: font.ascender / s, lineDescender: -font.descender / s)
    }
}

/// Kẹp cỡ + baseline nhãn phím (thuần — test được).
enum KeyLabelFit {
    static let sidePad: CGFloat = 3
    static let vPad: CGFloat = 1

    /// Cỡ lớn nhất ≤ `want` để glyph rộng nhất vừa bề ngang và mực (trên + dưới baseline)
    /// vừa chiều cao phím.
    static func size(want: CGFloat, em: KeyLabelEm, keyWidth: CGFloat, keyHeight: CGFloat) -> CGFloat {
        var s = want
        // ×0.98: chừa sai số cỡ quang học (đo một cỡ, vẽ cỡ khác).
        if em.width > 0, keyWidth > 0 { s = min(s, (keyWidth - 2 * sidePad) / em.width * 0.98) }
        if em.inkHeight > 0, keyHeight > 0 { s = min(s, (keyHeight - 2 * vPad) / em.inkHeight) }
        return max(s, 1)
    }

    /// Baseline (pt dưới đỉnh phím): giữ `preferred` (baseline stock) nếu mực vẫn trong phím,
    /// không thì dời vừa đủ.
    static func baseline(preferred: CGFloat, size: CGFloat, em: KeyLabelEm, keyHeight: CGFloat) -> CGFloat {
        let lo = vPad + em.ascent * size, hi = keyHeight - vPad - em.descent * size
        guard lo <= hi else { return (lo + hi) / 2 }
        return min(max(preferred, lo), hi)
    }

    /// Cỡ lớn nhất ≤ `want` để chữ trong UILabel CĂN GIỮA hộp dòng (ô phóng to, popup) cao
    /// `height` không bị cắt mực trên/dưới (UILabel cắt nội dung theo bounds).
    static func centeredSize(want: CGFloat, em: KeyLabelEm, height: CGFloat) -> CGFloat {
        let line = em.lineAscender + em.lineDescender
        let over = max(0, em.ascent - em.lineAscender, em.descent - em.lineDescender)
        let need = line + 2 * over
        guard need > 0, height > 0 else { return want }
        return max(1, min(want, height / need))
    }
}

/// Phông nhãn phím đã giải (phông + độ đậm + hệ số cỡ) — KeyboardView giữ một bản tĩnh,
/// đặt lại mỗi lần hiện; UIFont cache theo cỡ trong bản này.
final class KeyLabelFonts {
    let font: KeyLabelFont
    let weight: KeyLabelWeight
    let scale: CGFloat
    /// Có tuỳ chỉnh chữ — false ⇒ đường cũ y nguyên (không đo, không kẹp).
    let active: Bool
    private let compact: UIFontDescriptor?
    private var cache: [CGFloat: UIFont] = [:]
    private var emCache: [String: KeyLabelEm] = [:]

    init(style: KeyStyle, compact: UIFontDescriptor?) {
        font = style.font; weight = style.weight; scale = style.labelScale
        active = style.customizesLabels
        self.compact = compact
    }

    /// Phông ở đúng cỡ `size` (nơi gọi tự nhân `scale` nếu cần).
    func font(size: CGFloat) -> UIFont {
        if let f = cache[size] { return f }
        let f = Self.make(font, weight, size: size, compact: compact)
        if cache.count > 64 { cache.removeAll() }
        cache[size] = f
        return f
    }

    static func make(_ design: KeyLabelFont, _ weight: KeyLabelWeight, size: CGFloat,
                     compact: UIFontDescriptor?) -> UIFont {
        let w = weight.uiWeight
        switch design {
        case .standard:
            guard let c = compact else { return .systemFont(ofSize: size, weight: w) }
            if weight == .regular { return UIFont(descriptor: c, size: size) }
            let d = c.addingAttributes([.traits: [UIFontDescriptor.TraitKey.weight: w]])
            return UIFont(descriptor: d, size: size)
        case .rounded, .serif, .mono:
            let sd: UIFontDescriptor.SystemDesign = design == .rounded ? .rounded : design == .serif ? .serif : .monospaced
            let base = UIFont.systemFont(ofSize: size, weight: w)
            return base.fontDescriptor.withDesign(sd).map { UIFont(descriptor: $0, size: size) } ?? base
        case .condensed:
            return .systemFont(ofSize: size, weight: w, width: .condensed)
        }
    }

    /// Số đo em (đo ở cỡ thật của nhãn phím ~24pt — SF / New York đổi nét theo cỡ quang học,
    /// đo ở cỡ khác lệch bề ngang; một lần cho mỗi mẫu).
    func em(_ sample: String) -> KeyLabelEm {
        if let e = emCache[sample] { return e }
        let e = KeyLabelEm.measure(Self.make(font, weight, size: 24, compact: compact), sample: sample)
        emCache[sample] = e
        return e
    }
}

extension ThemeGate {
    /// "Tuỳ chỉnh nâng cao" đi cùng quyền theme cao cấp (Plus).
    static var allowsAdvanced: Bool { PlusGate.isUnlocked(.premiumThemes) }
}
