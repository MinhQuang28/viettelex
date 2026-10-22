import XCTest
import UIKit

/// Ảnh chụp tự kiểm (không phải test hồi quy): dựng KeyboardView offscreen, xuất PNG.
/// Chỉ chạy khi có biến môi trường VT_SNAPSHOT_DIR (xcodebuild: TEST_RUNNER_VT_SNAPSHOT_DIR=…).
final class UXSnapshotTests: XCTestCase {
    @MainActor func testWriteSnapshots() throws {
        guard let dir = ProcessInfo.processInfo.environment["VT_SNAPSHOT_DIR"] else {
            throw XCTSkip("đặt VT_SNAPSHOT_DIR để xuất ảnh")
        }
        let w: CGFloat = UIDevice.current.userInterfaceIdiom == .pad ? 834 : 402
        func shot(_ name: String, dark: Bool, _ configure: (KeyboardView) -> Void) throws {
            let kb = KeyboardView(needsGlobe: false, inputController: nil, onKey: { _ in })
            let host = UIView(frame: CGRect(x: 0, y: 0, width: w, height: 300))
            // Nền mô phỏng backdrop bàn phím hệ thống (theme Hệ thống để trong suốt).
            host.backgroundColor = dark ? UIColor(white: 0.11, alpha: 1) : UIColor(red: 0.82, green: 0.83, blue: 0.86, alpha: 1)
            host.overrideUserInterfaceStyle = dark ? .dark : .light
            host.addSubview(kb)
            kb.frame = host.bounds
            kb.applyAppearance(dark ? .dark : .light, style: dark ? .dark : .light)
            kb.setSuggestionsEnabled(true)
            kb.setNeedsLayout(); kb.layoutIfNeeded()
            configure(kb)
            kb.setNeedsLayout(); kb.layoutIfNeeded()
            let h = kb.systemLayoutSizeFitting(CGSize(width: w, height: 0)).height
            host.frame.size.height = max(h, 200); kb.frame = host.bounds
            host.layoutIfNeeded(); kb.layoutIfNeeded()
            let img = UIGraphicsImageRenderer(bounds: host.bounds).image { ctx in host.layer.render(in: ctx.cgContext) }
            try XCTUnwrap(img.pngData()).write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        for dark in [true, false] {
            let s = dark ? "dark" : "light"
            try shot("letters-\(s)", dark: dark) { $0.showSuggestions(.init(literal: "đ", word: "đáp", emojis: ["😀", "👍"])) }
            try shot("paste-\(s)", dark: dark) { kb in
                kb.showSuggestions(.init(nextWords: ["Đáp", "Được", "Đi"]))
                var p = KeyboardView.SuggestionSet(nextWords: ["Đáp", "Được", "Đi"]); p.paste = true
                kb.showSuggestions(p)
                kb.debugRefreshChrome()
            }
            try shot("emoji-\(s)", dark: dark) { $0.debugShowEmojiPlane() }
            // Đường thật: chạm phím emoji (ABC nhớ chỗ phím) — rồi chế độ tìm emoji.
            try shot("emojikey-\(s)", dark: dark) { kb in
                kb.debugControl("Emoji")?.sendActions(for: .touchDown)
                kb.debugControl("Emoji")?.sendActions(for: .touchUpInside)
            }
            try shot("emojisearch-\(s)", dark: dark) { $0.debugEnterEmojiSearch() }
            // Giữ phím bàn 123 ra hàng biến thể (KeyVariants): " và $.
            for (name, key) in [("quote", "\""), ("dollar", "$"), ("dash", "-")] {
                try shot("variants-\(name)-\(s)", dark: dark) { kb in
                    kb.debugSetPlane(numbers: true)
                    kb.setNeedsLayout(); kb.layoutIfNeeded()
                    kb.debugDomainHold(key, keepOpen: true)
                }
            }
            // Công tắc Mẫu câu tắt: thanh gợi ý không còn nút ☰.
            let tdef = UserDefaultsProvider.shared
            let tSaved = tdef?.object(forKey: "templatesEnabled")
            tdef?.set(false, forKey: "templatesEnabled")
            try shot("bar-templatesoff-\(s)", dark: dark) { $0.showSuggestions(.init(nextWords: ["Em", "Anh", "Tôi"])) }
            tdef?.set(tSaved, forKey: "templatesEnabled")
            try shot("bar-templateson-\(s)", dark: dark) { $0.showSuggestions(.init(nextWords: ["Em", "Anh", "Tôi"])) }
            // Balloon hàng đầu (giữ "e" ra "3") — có và không có thanh gợi ý (Phil 30/09).
            try shot("balloon-bar-\(s)", dark: dark) { _ = $0.debugBalloon(letter: "e", text: "3") }
            try shot("balloon-nobar-\(s)", dark: dark) { kb in
                kb.setSuggestionsEnabled(false)
                kb.setNeedsLayout(); kb.layoutIfNeeded()
                _ = kb.debugBalloon(letter: "e", text: "3")
            }
            // Thanh địa chỉ Safari (StripMode.tools): ☰ + chip URL thay dải trống — ô trống
            // (có https://), đang gõ tên miền, và lời mời Dán; so với dải trống ô mật khẩu.
            try shot("urlbar-empty-\(s)", dark: dark) { kb in
                kb.configureInputKind(.search)
                kb.showSuggestions(.init(nextWords: URLChips.chips(before: "", after: "").map(\.label)))
            }
            try shot("urlbar-typing-\(s)", dark: dark) { kb in
                kb.configureInputKind(.search)
                kb.showSuggestions(.init(nextWords: URLChips.chips(before: "vnexpress", after: "").map(\.label)))
            }
            try shot("urlbar-paste-\(s)", dark: dark) { kb in
                kb.configureInputKind(.search)
                var p = KeyboardView.SuggestionSet(nextWords: ["https://", "www.", ".com"]); p.paste = true
                kb.showSuggestions(p)
            }
            try shot("password-blank-\(s)", dark: dark) { kb in
                kb.setSuggestionsEnabled(false, reserveStrip: true)
            }
            // Giữ phím ra số / ký hiệu (issue #98): nhãn nhỏ góc trên-phải.
            try shot("alternates-\(s)", dark: dark) { $0.configureKeyAlternates(numbers: true, symbols: true) }
        }
        // Độ trong suốt phím / ký tự 0/50/100% (Hệ thống + Hồng đào) — ghi App Group rồi trả lại.
        let d = UserDefaultsProvider.shared
        let keys = [ThemeSettings.themeKey, KeyboardTransparency.keyboardKey, KeyboardTransparency.labelKey]
        let saved = keys.map { d?.object(forKey: $0) }
        defer { for (k, v) in zip(keys, saved) { d?.set(v, forKey: k) } }
        // Theme đen (OLED): nền phủ trọn + bo góc trên; "Nền theo hệ thống" bật/tắt.
        let sbKey = try XCTUnwrap(KeyboardTheme.oled.systemBackdropKey)
        let sbSaved = d?.object(forKey: sbKey)
        defer { d?.set(sbSaved, forKey: sbKey) }
        for sysBg in [false, true] {
            for dark in [true, false] {
                d?.set(KeyboardTheme.oled.rawValue, forKey: ThemeSettings.themeKey)
                d?.set(0, forKey: KeyboardTransparency.keyboardKey)
                d?.set(0, forKey: KeyboardTransparency.labelKey)
                d?.set(sysBg, forKey: sbKey)
                try shot("oled\(sysBg ? "-sysbg" : "")-\(dark ? "dark" : "light")", dark: dark) { kb in
                    kb.showSuggestions(.init(nextWords: ["Em", "Anh", "Tôi"]))
                }
            }
        }
        d?.set(false, forKey: sbKey)
        for theme in [KeyboardTheme.system, .peach] {
            for dark in [true, false] {
                for (k, l) in [(0, 0), (50, 0), (100, 0), (0, 50), (0, 100), (50, 50)] {
                    d?.set(theme.rawValue, forKey: ThemeSettings.themeKey)
                    d?.set(k, forKey: KeyboardTransparency.keyboardKey)
                    d?.set(l, forKey: KeyboardTransparency.labelKey)
                    try shot("transp-\(theme.rawValue)-k\(k)-l\(l)-\(dark ? "dark" : "light")", dark: dark) { _ in }
                }
            }
        }
    }

    /// Ảnh xem trước từng theme (sáng + tối) — VT_THEME_SNAPSHOT_DIR (riêng, chụp nhanh không cần cả bộ trên).
    @MainActor func testWriteThemeSnapshots() throws {
        guard let dir = ProcessInfo.processInfo.environment["VT_THEME_SNAPSHOT_DIR"] else {
            throw XCTSkip("đặt VT_THEME_SNAPSHOT_DIR để xuất ảnh theme")
        }
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let d = UserDefaultsProvider.shared
        let saved = d?.object(forKey: ThemeSettings.themeKey)
        let savedPaywall = PlusGate.paywallEnabled
        PlusGate.paywallEnabled = false          // theme Plus hiện thật (không rơi về Hệ thống)
        defer { d?.set(saved, forKey: ThemeSettings.themeKey); PlusGate.paywallEnabled = savedPaywall }
        let w: CGFloat = 402
        for theme in KeyboardTheme.allCases where theme != .system && theme != .oled && theme != .contrast {
            for dark in [false, true] {
                d?.set(theme.rawValue, forKey: ThemeSettings.themeKey)
                let kb = KeyboardView(needsGlobe: false, inputController: nil, onKey: { _ in })
                let host = UIView(frame: CGRect(x: 0, y: 0, width: w, height: 300))
                host.backgroundColor = dark ? UIColor(white: 0.11, alpha: 1) : UIColor(red: 0.82, green: 0.83, blue: 0.86, alpha: 1)
                host.overrideUserInterfaceStyle = dark ? .dark : .light
                host.addSubview(kb)
                kb.frame = host.bounds
                kb.applyAppearance(dark ? .dark : .light, style: dark ? .dark : .light)
                kb.setSuggestionsEnabled(true)
                kb.configureInputKind(.normal)
                kb.setNeedsLayout(); kb.layoutIfNeeded()
                kb.showSuggestions(.init(nextWords: ["Em", "Anh", "Tôi"]))
                let h = kb.systemLayoutSizeFitting(CGSize(width: w, height: 0)).height
                host.frame.size.height = max(h, 200); kb.frame = host.bounds
                host.layoutIfNeeded(); kb.layoutIfNeeded()
                let img = UIGraphicsImageRenderer(bounds: host.bounds).image { host.layer.render(in: $0.cgContext) }
                try XCTUnwrap(img.pngData()).write(to: URL(fileURLWithPath: dir)
                    .appendingPathComponent("theme-\(theme.rawValue)-\(dark ? "dark" : "light").png"))
            }
        }
    }
}
