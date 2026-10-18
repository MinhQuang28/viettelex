// "Tuỳ chỉnh nâng cao" RIÊNG từng theme + "Màu nhấn" + chuyển đổi (khoá chung cũ, "Trắng hồng" đã bỏ).
// Android giữ y hệt (android/keyboard/.../KeyStyleStoreTests.kt) — chuỗi lưu trùng từng ký tự (golden).
import XCTest

final class PerThemeKeyStyleTests: XCTestCase {
    private var savedDefaults: UserDefaults!
    private var savedPaywall = false
    private var suite = ""
    private var store = ""
    private var d: UserDefaults!

    override func setUp() {
        super.setUp()
        savedDefaults = PlusGate.defaults
        savedPaywall = PlusGate.paywallEnabled
        PlusGate.paywallEnabled = false
        suite = "perthemestyle-\(UUID().uuidString)"
        PlusGate.defaults = UserDefaults(suiteName: suite)!
        store = "perthemestyle-store-\(UUID().uuidString)"
        d = UserDefaults(suiteName: store)!
    }

    override func tearDown() {
        PlusGate.defaults.removePersistentDomain(forName: suite)
        PlusGate.defaults = savedDefaults
        PlusGate.paywallEnabled = savedPaywall
        d.removePersistentDomain(forName: store)
        super.tearDown()
    }

    /// Chuỗi chuẩn — Android KeyStyleStoreTests.GOLDEN phải trùng (sao lưu đi qua lại).
    static let golden = ##"{"pink":{"accent":"#D6336C","corner":70},"system":{"fill":"#102030","font":"mono","label":"#FF2D55","weight":"heavy"}}"##
    static let goldenMap: [String: KeyStyle] = [
        "pink": KeyStyle(accentColor: 0xD6336C, corner: 70),
        "system": KeyStyle(labelColor: 0xFF2D55, fillColor: 0x102030, font: .mono, weight: .heavy),
    ]

    func testCanonicalFormatMatchesAndroid() {
        XCTAssertEqual(KeyStyleStore.serialize(Self.goldenMap), Self.golden)
        XCTAssertEqual(KeyStyleStore.parse(Self.golden), Self.goldenMap)
        // Thứ tự khoá / khoảng trắng khác ⇒ chuẩn hoá về cùng chuỗi.
        XCTAssertEqual(KeyStyleStore.normalize(##"{ "system": {"weight":"heavy","label":"#ff2d55","font":"mono","fill":"#102030"}, "pink":{"corner":70,"accent":"#D6336C"} }"##), Self.golden)
        XCTAssertNil(KeyStyleStore.normalize("[1,2]"))
        XCTAssertNil(KeyStyleStore.normalize("oops"))
        XCTAssertEqual(KeyStyleStore.serialize([:]), "{}")
    }

    // MARK: riêng từng theme

    func testPerThemeIsolation() {
        var s = ThemeSettings(theme: .mint)
        s.keyStyle = KeyStyle(fillColor: 0x102030, accentColor: 0xFF9500, corner: 0)
        s.theme = .peach
        XCTAssertEqual(s.keyStyle, KeyStyle(), "đổi theme ⇒ thấy tuỳ chỉnh của theme đó (chưa có)")
        s.keyStyle = KeyStyle(labelColor: 0x3366FF)
        s.theme = .mint
        XCTAssertEqual(s.keyStyle, KeyStyle(fillColor: 0x102030, accentColor: 0xFF9500, corner: 0))
        XCTAssertEqual(s.palette(systemDark: false, wallpaperActive: false).keyFill, RGBA(hex: 0x102030))
        s.theme = .peach
        let p = s.palette(systemDark: false, wallpaperActive: false)
        let peach = KeyboardTheme.peach.palette(systemDark: false)
        XCTAssertEqual(p.keyFill, peach.keyFill, "màu nền của mint không lan sang peach")
        XCTAssertEqual(p.keyInk, RGBA(hex: 0x3366FF))
        XCTAssertEqual(p.accent, peach.accent)
        XCTAssertFalse(p.accentShift)
        // Lưu / đọc giữ đủ mọi theme.
        s.save(d)
        XCTAssertEqual(ThemeSettings.load(d).keyStyles, s.keyStyles)
        XCTAssertEqual(Set(s.keyStyles.keys), ["mint", "peach"])
        // Thẻ "Chọn giao diện" phản ánh tuỳ chỉnh riêng; theme chưa chỉnh = palette gốc.
        XCTAssertEqual(s.cardPalette(for: .mint, systemDark: false).keyFill, RGBA(hex: 0x102030))
        XCTAssertEqual(s.cardPalette(for: .mint, systemDark: false).accent, RGBA(hex: 0xFF9500))
        XCTAssertEqual(s.cardPalette(for: .sky, systemDark: false), KeyboardTheme.sky.palette(systemDark: false))
        XCTAssertEqual(s.effectiveStyle(for: .pink).corner, 70)
    }

    func testResetOnlyCurrentTheme() {
        var s = ThemeSettings(theme: .mint)
        s.keyStyle = KeyStyle(accentColor: 0x00FF00)
        s.setStyle(KeyStyle(corner: 10), for: .oled)
        s.keyStyle = KeyStyle()                          // "Đặt lại về mặc định của giao diện"
        XCTAssertNil(s.keyStyles["mint"])
        XCTAssertEqual(s.style(for: .oled), KeyStyle(corner: 10))
        s.save(d)
        XCTAssertEqual(ThemeSettings.load(d).keyStyles, ["oled": KeyStyle(corner: 10)])
        // "Khôi phục giao diện gốc" xoá hết.
        XCTAssertEqual(s.resetToDefaults().keyStyles, [:])
        s.resetToDefaults().save(d)
        XCTAssertEqual(d.string(forKey: KeyStyleStore.key), "{}")
    }

    /// 0 chi phí khi chưa chỉnh: không tạo khoá, palette y như theme gốc.
    func testNoCustomizationIsBaseline() {
        let s = ThemeSettings(theme: .sky)
        s.save(d)
        XCTAssertNil(d.object(forKey: KeyStyleStore.key))
        XCTAssertEqual(s.effectiveKeyStyle, KeyStyle())
        let base = KeyboardTheme.sky.palette(systemDark: true)
        XCTAssertEqual(KeyStyle().applyAccent(to: base), base)
        XCTAssertEqual(s.palette(systemDark: true, wallpaperActive: false), base)
    }

    // MARK: màu nhấn

    func testAccentOverrideAndInkContrast() {
        let accents: [UInt32] = [0x000000, 0xFFFFFF, 0xFFD60A, 0xD6336C, 0x00BFB2, 0x7F7F7F, 0x2C6BB8, 0x9EE0B6]
        for t in KeyboardTheme.allCases {
            for dark in [false, true] {
                for a in accents {
                    var s = ThemeSettings(theme: t)
                    s.keyStyle = KeyStyle(accentColor: a)
                    let p = s.palette(systemDark: dark, wallpaperActive: false)
                    let tag = "\(t) \(dark) \(String(a, radix: 16))"
                    XCTAssertEqual(p.accent, RGBA(hex: a), tag)
                    XCTAssertTrue(p.accentShift, tag)
                    XCTAssertEqual(p.trail, RGBA(hex: a).alpha(0.6), tag)
                    XCTAssertGreaterThanOrEqual(RGBA.contrast(p.accentInk, p.accent), KeyboardTransparency.minLabelContrast, tag)
                    // Chữ của theme vốn đọc được ⇒ giữ nguyên.
                    let base = t.palette(systemDark: dark)
                    if RGBA.contrast(base.accentInk, RGBA(hex: a)) >= KeyboardTransparency.minLabelContrast {
                        XCTAssertEqual(p.accentInk, base.accentInk, tag)
                    }
                }
            }
        }
        // Nhấn trắng, theme chữ trắng ⇒ chữ đen.
        XCTAssertEqual(KeyStyle.accentInk(.white, on: .white), .black)
        // Glyph màu nhấn của theme (functionInk) đi theo màu nhấn, vẫn đọc được trên phím chức năng.
        var p = KeyboardTheme.peach.palette(systemDark: false)
        p.functionInk = RGBA(hex: 0xC2255C)
        XCTAssertEqual(KeyStyle(accentColor: 0x1F7A5C).applyAccent(to: p).functionInk, RGBA(hex: 0x1F7A5C))
        let pale = KeyStyle(accentColor: 0xFFF8F7).applyAccent(to: p)
        XCTAssertGreaterThanOrEqual(RGBA.contrast(pale.functionInk!, p.fnFill.over(p.background!)),
                                    KeyboardTransparency.minLabelContrast)
        // Đi cùng độ trong suốt: chữ trên nhấn vẫn đọc được.
        var tr = ThemeSettings(theme: .mint, keyboardTransparency: 60)
        tr.keyStyle = KeyStyle(accentColor: 0xFFFFFF)
        let tp = tr.palette(systemDark: true, wallpaperActive: false)
        XCTAssertEqual(tp.accent.a, 0.4, accuracy: 1e-9)
        // Hết Plus ⇒ màu nhấn không áp (giá trị giữ nguyên).
        PlusGate.paywallEnabled = true; PlusGate.setPurchased(false); PlusGate.debugOverride = false
        var locked = ThemeSettings(theme: .oled)
        locked.keyStyle = KeyStyle(accentColor: 0xFF0000)
        let lp = locked.palette(systemDark: true, wallpaperActive: false)
        XCTAssertEqual(lp.accent, KeyboardTheme.oled.palette(systemDark: true).accent)
        XCTAssertFalse(lp.accentShift)
        XCTAssertEqual(locked.keyStyle.accentColor, 0xFF0000)
    }

    /// Bàn phím thật: palette (một lần mỗi lần hiện) mang màu nhấn; ⇧ bật tô màu nhấn.
    @MainActor func testKeyboardViewUsesAccent() {
        let ud = UserDefaultsProvider.shared
        let keys = [ThemeSettings.themeKey, KeyStyleStore.key] + KeyStyleStore.legacyKeys
        let saved = keys.map { ud?.object(forKey: $0) }
        defer { for (k, v) in zip(keys, saved) { ud?.set(v, forKey: k) } }
        for k in KeyStyleStore.legacyKeys { ud?.removeObject(forKey: k) }
        ud?.set("oled", forKey: ThemeSettings.themeKey)
        ud?.set(KeyStyleStore.serialize(["oled": KeyStyle(accentColor: 0xFF9500)]), forKey: KeyStyleStore.key)
        let kb = KeyboardView(needsGlobe: false, inputController: nil) { _ in }
        kb.frame = CGRect(x: 0, y: 0, width: 390, height: 300)
        kb.applyAppearance(.dark, style: .dark)
        XCTAssertEqual(kb.debugPalette.accent, RGBA(hex: 0xFF9500))
        XCTAssertTrue(kb.debugPalette.accentShift)
        XCTAssertEqual(kb.debugShiftOnColors.fill, RGBA(hex: 0xFF9500).ui)
        // Theme khác chưa chỉnh ⇒ ⇧ bật như cũ (nền trắng).
        ud?.set("mint", forKey: ThemeSettings.themeKey)
        kb.applyAppearance(.dark, style: .dark)
        XCTAssertFalse(kb.debugPalette.accentShift)
        XCTAssertEqual(kb.debugShiftOnColors.fill, RGBA.white.ui)
    }

    // MARK: chuyển đổi

    func testMigrationFromGlobalKeysIsIdempotent() {
        // Bản cũ lưu bộ chung (kể cả giá trị canh) + theme đang chọn mint.
        d.set("mint", forKey: ThemeSettings.themeKey)
        d.set(0xFF2D55, forKey: KeyStyle.labelColorKey)
        d.set(-1, forKey: KeyStyle.fillColorKey)
        d.set(100, forKey: KeyStyle.cornerKey)
        d.set(0, forKey: KeyStyle.fontScaleKey)
        d.set("serif", forKey: KeyStyle.fontKey)
        d.set("regular", forKey: KeyStyle.weightKey)
        let want = KeyStyle(labelColor: 0xFF2D55, corner: 100, font: .serif)
        // Bàn phím (app chưa chạy): chuyển trong bộ nhớ, không ghi.
        XCTAssertEqual(ThemeSettings.load(d).keyStyles, ["mint": want])
        XCTAssertNotNil(d.object(forKey: KeyStyle.labelColorKey))
        // App: ghi một lần, khoá cũ bị xoá; theme khác không nhận.
        XCTAssertTrue(ThemeSettings.migrateStored(d))
        for k in KeyStyleStore.legacyKeys { XCTAssertNil(d.object(forKey: k), k) }
        XCTAssertEqual(d.string(forKey: KeyStyleStore.key), KeyStyleStore.serialize(["mint": want]))
        let after = ThemeSettings.load(d)
        XCTAssertEqual(after.theme, .mint)
        XCTAssertEqual(after.keyStyles, ["mint": want])
        XCTAssertEqual(after.style(for: .peach), KeyStyle())
        // Lần 2: không làm gì.
        XCTAssertFalse(ThemeSettings.migrateStored(d))
        XCTAssertEqual(ThemeSettings.load(d), after)
        // Hàm thuần: áp lên kết quả của chính nó ⇒ không đổi; không ghi đè mục đã có.
        let once = KeyStyleStore.migrate(theme: "mint", styles: [:], legacy: want)
        let twice = KeyStyleStore.migrate(theme: once.theme, styles: once.styles, legacy: want)
        XCTAssertEqual(once.styles, twice.styles); XCTAssertEqual(once.theme, twice.theme)
        let kept = KeyStyleStore.migrate(theme: "mint", styles: ["mint": KeyStyle(corner: 5)], legacy: want)
        XCTAssertEqual(kept.styles["mint"], KeyStyle(corner: 5))
        // Theme lạ / chưa chọn ⇒ bộ chung về Hệ thống.
        XCTAssertEqual(KeyStyleStore.migrate(theme: "future", styles: [:], legacy: want).styles, ["system": want])
        XCTAssertEqual(KeyStyleStore.migrate(theme: nil, styles: [:], legacy: want).styles, ["system": want])
        // Bộ chung toàn mặc định (giá trị canh) ⇒ không tạo mục nào nhưng vẫn dọn khoá cũ.
        let e = "perthemestyle-e-\(UUID().uuidString)"
        let de = UserDefaults(suiteName: e)!
        defer { de.removePersistentDomain(forName: e) }
        de.set(-1, forKey: KeyStyle.labelColorKey); de.set("default", forKey: KeyStyle.fontKey)
        XCTAssertTrue(ThemeSettings.migrateStored(de))
        XCTAssertEqual(ThemeSettings.load(de).keyStyles, [:])
        XCTAssertNil(de.object(forKey: KeyStyle.labelColorKey))
        XCTAssertFalse(ThemeSettings.migrateStored(de))
    }

    /// "Trắng hồng" (Android 1.5.12 đã phát hành) ⇒ Hệ thống + màu nhấn D6336C (chỉ khi Hệ thống chưa có).
    func testPureWhiteMigratesToSystemWithPinkAccent() {
        d.set("pureWhite", forKey: ThemeSettings.themeKey)
        d.set(true, forKey: KeyStyleStore.removedBackdropKey)
        let s = ThemeSettings.load(d)
        XCTAssertEqual(s.theme, .system)
        XCTAssertEqual(s.keyStyles, ["system": KeyStyle(accentColor: 0xD6336C)])
        XCTAssertTrue(ThemeSettings.migrateStored(d))
        XCTAssertEqual(d.string(forKey: ThemeSettings.themeKey), "system")
        XCTAssertNil(d.object(forKey: KeyStyleStore.removedBackdropKey))
        XCTAssertFalse(ThemeSettings.migrateStored(d))
        XCTAssertEqual(ThemeSettings.load(d), s)
        XCTAssertEqual(s.palette(systemDark: false, wallpaperActive: false).accent, RGBA(hex: 0xD6336C))
        // Hệ thống đã có màu nhấn ⇒ giữ; mục "pureWhite" bỏ.
        let m = KeyStyleStore.migrate(theme: "pureWhite",
                                      styles: ["system": KeyStyle(accentColor: 0x00FF00), "pureWhite": KeyStyle(corner: 3)],
                                      legacy: KeyStyle(corner: 40))
        XCTAssertEqual(m.theme, "system")
        XCTAssertEqual(m.styles, ["system": KeyStyle(accentColor: 0x00FF00)])
        // Bộ chung cũ + Trắng hồng ⇒ cả hai vào Hệ thống; lặp lại không đổi.
        let both = KeyStyleStore.migrate(theme: "pureWhite", styles: [:], legacy: KeyStyle(fillColor: 0x111111))
        XCTAssertEqual(both.styles, ["system": KeyStyle(fillColor: 0x111111, accentColor: 0xD6336C)])
        let again = KeyStyleStore.migrate(theme: both.theme, styles: both.styles, legacy: KeyStyle(fillColor: 0x111111))
        XCTAssertEqual(again.styles, both.styles)
        XCTAssertNil(KeyboardTheme(rawValue: "pureWhite"))
    }

    func testUnknownThemeFallsBackToSystem() {
        for raw in ["pureWhite", "future", "", "PINK"] {
            d.set(raw, forKey: ThemeSettings.themeKey)
            XCTAssertEqual(ThemeSettings.load(d).theme, .system, raw)
        }
        // Mục của theme lạ (bản mới hơn) giữ nguyên khi khứ hồi.
        d.set("system", forKey: ThemeSettings.themeKey)
        d.set(#"{"future":{"corner":20}}"#, forKey: KeyStyleStore.key)
        let s = ThemeSettings.load(d)
        XCTAssertEqual(s.keyStyles["future"], KeyStyle(corner: 20))
        s.save(d)
        XCTAssertEqual(d.string(forKey: KeyStyleStore.key), #"{"future":{"corner":20}}"#)
    }

    // MARK: sao lưu

    func testBackupRoundTripPerTheme() throws {
        var s = ThemeSettings(theme: .pink)
        s.keyStyles = Self.goldenMap
        s.save(d)
        let snap = BackupStore(defaults: d, containerURL: nil).settings()
        XCTAssertEqual(snap[KeyStyleStore.key], .string(Self.golden))
        let payload = BackupPayload(createdAt: nil, platform: "ios", settings: snap, shortcuts: nil, templates: nil, learnedWords: nil)
        let back = try BackupCodec.decode(BackupCodec.encode(payload))
        XCTAssertEqual(back.settings?[KeyStyleStore.key], .string(Self.golden))
        let o = "perthemestyle-o-\(UUID().uuidString)"
        let other = UserDefaults(suiteName: o)!
        defer { other.removePersistentDomain(forName: o) }
        BackupStore(defaults: other, containerURL: nil).setSettings(try XCTUnwrap(back.settings))
        XCTAssertEqual(ThemeSettings.load(other).keyStyles, Self.goldenMap)
        // Đồng bộ iCloud: chuỗi sổ đồng bộ = chuỗi chuẩn; rác bị bỏ.
        XCTAssertEqual(SettingValue.fromSyncString(Self.golden, key: KeyStyleStore.key), .string(Self.golden))
        XCTAssertNil(SettingValue.fromSyncString("oops", key: KeyStyleStore.key))
        // File cũ có khoá chung ⇒ bỏ qua như key lạ; chuỗi lộn xộn ⇒ chuẩn hoá.
        let old = #"{"format":"viettelex-backup","version":1,"settings":{"keyLabelColor":255,"keyStyles":"{ \"oled\" : {\"corner\":0} }"}}"#
        let p = try BackupCodec.decode(Data(old.utf8))
        XCTAssertNil(p.settings?["keyLabelColor"])
        XCTAssertEqual(p.settings?[KeyStyleStore.key], .string(#"{"oled":{"corner":0}}"#))
    }
}
