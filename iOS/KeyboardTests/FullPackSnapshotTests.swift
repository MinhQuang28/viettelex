// Ảnh bộ theme đủ món (Máy chữ cổ / Gaming RGB / Vũ trụ) — chỉ chạy khi có VT_THEME_SNAPSHOT_DIR
// (xcodebuild: TEST_RUNNER_VT_THEME_SNAPSHOT_DIR=…). Vẽ cây layer (layer.render bỏ qua mask ⇒ viền cầu vồng vẽ
// tay bằng RainbowBorderView.debugDraw). Máy chữ: cả khi CHƯA tải phông (offline ⇒ phông mặc định) và khi đã tải.
import XCTest
import UIKit

final class FullPackSnapshotTests: XCTestCase {
    @MainActor func testWriteFullPackSnapshots() async throws {
        guard let dir = ProcessInfo.processInfo.environment["VT_THEME_SNAPSHOT_DIR"] else {
            throw XCTSkip("đặt VT_THEME_SNAPSHOT_DIR để xuất ảnh theme")
        }
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let d = UserDefaultsProvider.shared
        let saved = d?.object(forKey: ThemeSettings.themeKey)
        let savedPaywall = PlusGate.paywallEnabled
        let store = FileManager.default.temporaryDirectory.appendingPathComponent("vt-fullpack-\(UUID().uuidString)")
        PlusGate.paywallEnabled = false
        ThemeAssetStore.dirOverride = store          // kho rỗng = máy chưa tải phông
        defer {
            d?.set(saved, forKey: ThemeSettings.themeKey); PlusGate.paywallEnabled = savedPaywall
            ThemeAssetStore.dirOverride = nil; ThemeAssetLoader.dropCache()
            try? FileManager.default.removeItem(at: store)
        }
        func shot(_ theme: KeyboardTheme, dark: Bool, name: String) throws {
            d?.set(theme.rawValue, forKey: ThemeSettings.themeKey)
            ThemeAssetLoader.dropCache()
            let w: CGFloat = 402
            let win = UIWindow(frame: CGRect(x: 0, y: 0, width: w, height: 320))
            win.overrideUserInterfaceStyle = dark ? .dark : .light
            win.backgroundColor = dark ? UIColor(white: 0.11, alpha: 1) : UIColor(red: 0.82, green: 0.83, blue: 0.86, alpha: 1)
            win.isHidden = false
            defer { win.isHidden = true }
            let kb = KeyboardView(needsGlobe: false, inputController: nil, onKey: { _ in })
            win.addSubview(kb)
            kb.frame = win.bounds
            kb.applyAppearance(dark ? .dark : .light, style: dark ? .dark : .light)
            kb.setSuggestionsEnabled(true)
            kb.configureInputKind(.normal)
            kb.setNeedsLayout(); kb.layoutIfNeeded()
            kb.showSuggestions(.init(nextWords: ["Việt", "chào", "Tôi"]))
            let h = kb.systemLayoutSizeFitting(CGSize(width: w, height: 0)).height
            win.frame.size.height = max(h, 200); kb.frame = win.bounds
            win.layoutIfNeeded(); kb.layoutIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.1))
            // Bộ test không có app chủ ⇒ không chụp màn được: vẽ cây layer rồi vẽ tay viền
            // cầu vồng (layer.render bỏ qua mask).
            let rb = kb.rainbow
            rb?.isHidden = true
            let img = UIGraphicsImageRenderer(bounds: win.bounds).image { ctx in
                win.backgroundColor?.setFill(); ctx.fill(win.bounds)
                kb.layer.render(in: ctx.cgContext)
                if let rb {
                    ctx.cgContext.saveGState()
                    ctx.cgContext.translateBy(x: rb.frame.minX, y: rb.frame.minY)
                    rb.debugDraw(in: ctx.cgContext)
                    ctx.cgContext.restoreGState()
                }
            }
            rb?.isHidden = false
            try XCTUnwrap(img.pngData()).write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
            kb.tearDown()
        }
        for t in [KeyboardTheme.typewriter, .rgb, .space] {
            for dark in [false, true] { try shot(t, dark: dark, name: "fullpack-\(t.rawValue)-\(dark ? "dark" : "light")") }
        }
        // Máy chữ với phông đã tải (kho giả: chính tệp docs/fonts, đúng sha256).
        let font = try XCTUnwrap(KeyboardTheme.typewriter.spec?.assets.font)
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: repo.appendingPathComponent("docs/fonts/XanhMono-Regular.ttf"))
        let dl = ThemeAssetDownloader(transport: ThemeAssetTests.FakeTransport(files: [font.ref.url: .success(data)]), dir: store,
                                      screenLongSidePx: 1000)
        let out = await dl.download([ThemeAssetFile(cls: .font, ref: font.ref)])
        XCTAssertEqual(out.done.count, 1)
        for dark in [false, true] { try shot(.typewriter, dark: dark, name: "fullpack-typewriter-font-\(dark ? "dark" : "light")") }
    }
}
