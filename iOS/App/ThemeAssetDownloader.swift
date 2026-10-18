// "Giao diện JSON" — tải tệp của theme (CHỈ app; bàn phím không bao giờ dùng mạng). Android y hệt
// (android/app/.../ThemeAssetDownloader.kt). Luồng: kế hoạch (ThemeAssetPlan) → tệp thiếu → HỎI người dùng
// (ThemeAssetPrompt) → tải tuần tự (https, cỡ, chuyển hướng chỉ https, hết giờ) → kiểm sha256 / loại tệp →
// phông: CoreText đọc được (thiếu glyph tiếng Việt ⇒ cảnh báo); ảnh: ImageIO đọc kích thước TRƯỚC, giải có thu
// nhỏ (không bao giờ giải bản gốc), lưu lại JPEG (đục) / PNG (alpha) không metadata, ≤ cỡ bàn phím / 512 px
// → App Group ThemeAssets/<sha256>.<lớp>.<đuôi> + index.json. Lỗi một tệp không chặn tệp khác; theme vẫn
// chạy bằng màu / phông mặc định.
import Foundation
import SwiftUI
import UIKit
import ImageIO
import CoreText
import CryptoKit
import UniformTypeIdentifiers

// MARK: - Lỗi

enum ThemeAssetError: String, Error, CaseIterable {
    case notHttps, redirectInsecure, httpStatus, tooLarge, wrongType, shaMismatch, timeout, offline,
         decodeFailed, fontInvalid, tooManyPixels

    var message: String {
        switch self {
        case .notHttps: return L("Chỉ tải qua https.")
        case .redirectInsecure: return L("Máy chủ chuyển hướng sang địa chỉ không an toàn.")
        case .httpStatus: return L("Máy chủ báo lỗi (không tìm thấy tệp?).")
        case .tooLarge: return L("Tệp quá lớn.")
        case .wrongType: return L("Sai loại tệp.")
        case .shaMismatch: return L("Tệp không khớp mã sha256.")
        case .timeout: return L("Hết thời gian chờ.")
        case .offline: return L("Không có kết nối mạng.")
        case .decodeFailed: return L("Không đọc được ảnh.")
        case .fontInvalid: return L("Không đọc được phông.")
        case .tooManyPixels: return L("Ảnh quá nhiều điểm ảnh.")
        }
    }
}

/// Cảnh báo lưu kèm tệp (index.json `warn`).
enum ThemeAssetWarning: String {
    case missingVietnamese, foregroundCoverage
    var message: String {
        switch self {
        case .missingVietnamese: return L("Phông thiếu chữ tiếng Việt (ạ ế ữ đ Ơ Ư) — chữ đó sẽ dùng phông hệ thống.")
        case .foregroundCoverage: return L("Ảnh tiền cảnh che nhiều phím — chữ có thể khó đọc.")
        }
    }
}

// MARK: - Câu hỏi trước khi tải (thuần)

enum ThemeAssetPrompt {
    /// "Giao diện này cần tải 3 tệp (≈ 120 KB) từ viettelex.com. Tải?" — cỡ biết trước (phông có sẵn) thì ≈ KB,
    /// không thì "tối đa" theo giới hạn từng loại (không gửi yêu cầu nào để hỏi cỡ).
    static func text(_ files: [ThemeAssetFile]) -> String {
        let hosts = Array(Set(files.map { ThemeAssetURL.host($0.ref.url) })).sorted().joined(separator: ", ")
        let known = files.map { BuiltinFonts.knownBytes($0.ref) }
        if known.allSatisfy({ $0 != nil }) {
            let kb = max(1, (known.compactMap { $0 }.reduce(0, +) + 1023) / 1024)
            return L("Giao diện này cần tải %@ tệp (≈ %@ KB) từ %@. Tải?", files.count, kb, hosts)
        }
        let mb = files.map { ThemeAssetLimits.maxBytes($0.cls) }.reduce(0, +) / (1024 * 1024)
        return L("Giao diện này cần tải %@ tệp (tối đa %@ MB) từ %@. Tải?", files.count, mb, hosts)
    }
}

// MARK: - Mạng (tiêm được — test dùng URLProtocol giả)

protocol ThemeAssetTransport {
    func get(_ url: URL, maxBytes: Int) async throws -> Data
}

/// URLSession phiên tạm (không cookie / cache), hết giờ 20 s, chuyển hướng chỉ sang https, đọc dần và dừng khi
/// vượt cỡ (không tin Content-Length).
final class URLSessionAssetTransport: ThemeAssetTransport {
    private let session: URLSession

    init(configuration: URLSessionConfiguration = .ephemeral, timeout: TimeInterval = ThemeAssetLimits.timeout) {
        let c = configuration
        c.timeoutIntervalForRequest = timeout
        c.timeoutIntervalForResource = timeout * 3
        c.httpCookieStorage = nil
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.waitsForConnectivity = false
        session = URLSession(configuration: c)
    }

    private final class Redirects: NSObject, URLSessionTaskDelegate {
        var insecure = false
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
            if ThemeAssetDownloader.allowRedirect(to: request.url) { completionHandler(request) }
            else { insecure = true; completionHandler(nil) }
        }
    }

    func get(_ url: URL, maxBytes: Int) async throws -> Data {
        guard url.scheme == "https" else { throw ThemeAssetError.notHttps }
        let r = Redirects()
        var req = URLRequest(url: url)
        req.httpShouldHandleCookies = false
        do {
            let (bytes, resp) = try await session.bytes(for: req, delegate: r)
            if r.insecure { throw ThemeAssetError.redirectInsecure }
            guard let http = resp as? HTTPURLResponse, http.url?.scheme == "https" else { throw ThemeAssetError.redirectInsecure }
            guard http.statusCode == 200 else { throw ThemeAssetError.httpStatus }
            if http.expectedContentLength > Int64(maxBytes) { throw ThemeAssetError.tooLarge }
            var out = Data()
            out.reserveCapacity(min(maxBytes, max(0, Int(http.expectedContentLength))))
            for try await b in bytes {
                out.append(b)
                if out.count > maxBytes { bytes.task.cancel(); throw ThemeAssetError.tooLarge }
            }
            return out
        } catch let e as ThemeAssetError {
            throw e
        } catch let e as URLError {
            // Chuyển hướng sang http bị từ chối: tuỳ đường (URLSession / URLProtocol) có thể ra lỗi thay vì 3xx.
            throw r.insecure ? ThemeAssetError.redirectInsecure : ThemeAssetDownloader.map(e)
        }
    }
}

// MARK: - Tải + xử lý

final class ThemeAssetDownloader {
    let transport: ThemeAssetTransport
    let dir: URL
    /// Cạnh dài bàn phím (px, scale ≤ 2) — trần cho ảnh nền / tiền cảnh.
    let screenEdge: Int

    init(transport: ThemeAssetTransport = URLSessionAssetTransport(), dir: URL, screenLongSidePx: Int) {
        self.transport = transport
        self.dir = dir
        screenEdge = ThemeAssetLimits.screenEdge(screenLongSidePx: screenLongSidePx)
    }

    /// Cạnh dài màn hình máy này ở scale giải (≤ 2).
    static var deviceScreenLongSidePx: Int {
        let b = UIScreen.main.bounds.size
        return Int(max(b.width, b.height) * min(UIScreen.main.scale, 2))
    }

    static func allowRedirect(to url: URL?) -> Bool { url?.scheme == "https" }

    /// Sao lưu (iCloud / máy tính) chỉ mang JSON — tệp tải về tải lại được, không vào bản sao lưu.
    static func excludeFromBackup(_ dir: URL) {
        var u = dir
        var v = URLResourceValues()
        v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
    }

    static func map(_ e: URLError) -> ThemeAssetError {
        switch e.code {
        case .timedOut: return .timeout
        case .appTransportSecurityRequiresSecureConnection, .unsupportedURL: return .notHttps
        case .httpTooManyRedirects, .redirectToNonExistentLocation: return .redirectInsecure
        case .badServerResponse, .fileDoesNotExist: return .httpStatus
        case .dataLengthExceedsMaximum: return .tooLarge
        default: return .offline
        }
    }

    struct Outcome {
        var done: [ThemeAssetFile] = []
        var failed: [ThemeAssetFile: ThemeAssetError] = [:]
        var warnings: [ThemeAssetFile: ThemeAssetWarning] = [:]
    }

    /// Tải tuần tự các tệp; ghi tệp + mục lục. Không ném — lỗi từng tệp nằm trong `failed`.
    func download(_ files: [ThemeAssetFile]) async -> Outcome {
        var out = Outcome()
        var index = ThemeAssetStore.readIndex(dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        Self.excludeFromBackup(dir)
        for f in files {
            do {
                guard let url = URL(string: f.ref.url), ThemeAssetURL.isValid(f.ref.url, font: f.cls == .font) else {
                    throw ThemeAssetError.notHttps
                }
                let data = try await transport.get(url, maxBytes: ThemeAssetLimits.maxBytes(f.cls))
                let p = try Self.process(data, file: f, screenEdge: screenEdge)
                try p.data.write(to: dir.appendingPathComponent(p.entry.file), options: .atomic)
                index[f.key] = p.entry
                if let w = p.entry.warn.flatMap(ThemeAssetWarning.init(rawValue:)) { out.warnings[f] = w }
                out.done.append(f)
            } catch let e as ThemeAssetError {
                out.failed[f] = e
            } catch {
                out.failed[f] = .offline
            }
        }
        try? ThemeAssetStore.writeIndex(index, dir)
        return out
    }

    // MARK: xử lý (thuần với dữ liệu — test không cần mạng)

    struct Processed { var data: Data; var entry: ThemeAssetStore.Entry }

    static func sha256Hex(_ d: Data) -> String { SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined() }

    enum Sniffed: Equatable { case ttf, otf, jpeg, png, webp, heic }

    /// Nhận loại theo byte đầu (không tin đuôi URL / Content-Type).
    static func sniff(_ d: Data) -> Sniffed? {
        let b = [UInt8](d.prefix(16))
        func at(_ i: Int, _ s: [UInt8]) -> Bool { b.count >= i + s.count && Array(b[i..<(i + s.count)]) == s }
        if at(0, [0x00, 0x01, 0x00, 0x00]) || at(0, Array("true".utf8)) { return .ttf }
        if at(0, Array("OTTO".utf8)) { return .otf }
        if at(0, [0xFF, 0xD8, 0xFF]) { return .jpeg }
        if at(0, [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) { return .png }
        if at(0, Array("RIFF".utf8)), at(8, Array("WEBP".utf8)) { return .webp }
        if at(4, Array("ftyp".utf8)), b.count >= 12 {
            let brand = String(decoding: b[8..<12], as: UTF8.self)
            if ["heic", "heix", "hevc", "hevx", "mif1", "msf1"].contains(brand) { return .heic }
        }
        return nil
    }

    static let vietnameseSample = "ạếữđƠƯ"

    static func process(_ data: Data, file f: ThemeAssetFile, screenEdge: Int) throws -> Processed {
        guard data.count <= ThemeAssetLimits.maxBytes(f.cls) else { throw ThemeAssetError.tooLarge }
        let sha = sha256Hex(data)
        if let want = f.ref.sha256, want != sha { throw ThemeAssetError.shaMismatch }
        guard let kind = sniff(data) else { throw ThemeAssetError.wrongType }
        switch f.cls {
        case .font:
            guard kind == .ttf || kind == .otf else { throw ThemeAssetError.wrongType }
            let warn = try validateFont(data)
            let name = ThemeAssetStore.fileName(sha256: sha, cls: .font, ext: kind == .otf ? "otf" : "ttf")
            return Processed(data: data, entry: .init(file: name, w: 0, h: 0, sw: 0, sh: 0, warn: warn?.rawValue))
        case .screen, .key:
            guard kind != .ttf, kind != .otf else { throw ThemeAssetError.wrongType }
            let edge = f.cls == .key ? ThemeAssetLimits.keyMaxEdge : screenEdge
            return try processImage(data, sha: sha, cls: f.cls, maxEdge: edge, isForeground: f.cls == .screen)
        }
    }

    /// Phông: CoreText đọc được (không thì từ chối); thiếu glyph tiếng Việt ⇒ cảnh báo.
    static func validateFont(_ data: Data) throws -> ThemeAssetWarning? {
        guard let ds = CTFontManagerCreateFontDescriptorsFromData(data as CFData) as? [CTFontDescriptor],
              let d = ds.first else { throw ThemeAssetError.fontInvalid }
        let font = CTFontCreateWithFontDescriptor(d, 20, nil)
        let chars = Array(vietnameseSample.utf16)
        var glyphs = [CGGlyph](repeating: 0, count: chars.count)
        let ok = CTFontGetGlyphsForCharacters(font, chars, &glyphs, chars.count)
        return ok && !glyphs.contains(0) ? nil : .missingVietnamese
    }

    /// Kích thước (đã xoay EXIF) + có alpha, đọc header — không giải.
    static func imageInfo(_ src: CGImageSource) -> (w: Int, h: Int, alpha: Bool)? {
        guard let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = p[kCGImagePropertyPixelWidth] as? Int, let h = p[kCGImagePropertyPixelHeight] as? Int,
              w > 0, h > 0 else { return nil }
        let o = (p[kCGImagePropertyOrientation] as? Int) ?? 1
        let alpha = (p[kCGImagePropertyHasAlpha] as? Bool) ?? false
        return o >= 5 ? (h, w, alpha) : (w, h, alpha)
    }

    /// Cạnh dài đích: ≤ trần và không phóng to.
    static func targetEdge(sourceW: Int, sourceH: Int, cap: Int) -> Int { min(cap, max(sourceW, sourceH)) }

    static func processImage(_ data: Data, sha: String, cls: ThemeAssetClass, maxEdge: Int,
                             isForeground: Bool) throws -> Processed {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(src) > 0, let info = imageInfo(src) else { throw ThemeAssetError.decodeFailed }
        guard info.w <= ThemeAssetLimits.maxSourceEdge, info.h <= ThemeAssetLimits.maxSourceEdge,
              info.w * info.h <= ThemeAssetLimits.maxSourcePixels else { throw ThemeAssetError.tooManyPixels }
        let edge = targetEdge(sourceW: info.w, sourceH: info.h, cap: maxEdge)
        guard let img = Wallpaper.downsample(source: src, maxPixel: edge) else { throw ThemeAssetError.decodeFailed }
        let alpha = info.alpha && hasTransparency(img)
        let png = alpha
        guard let out = encode(img, png: png) else { throw ThemeAssetError.decodeFailed }
        var warn: ThemeAssetWarning?
        if isForeground, coverage(img) > ThemeAssetLimits.foregroundCoverageWarn { warn = .foregroundCoverage }
        let name = ThemeAssetStore.fileName(sha256: sha, cls: cls, ext: png ? "png" : "jpg")
        return Processed(data: out, entry: .init(file: name, w: img.width, h: img.height, sw: info.w, sh: info.h,
                                                 warn: warn?.rawValue))
    }

    /// Mã hoá lại KHÔNG metadata (không truyền thuộc tính nguồn ⇒ không EXIF/GPS).
    static func encode(_ img: CGImage, png: Bool) -> Data? {
        let d = NSMutableData()
        let type = (png ? UTType.png : UTType.jpeg).identifier as CFString
        guard let dst = CGImageDestinationCreateWithData(d, type, 1, nil) else { return nil }
        let props: [CFString: Any] = png ? [:] : [kCGImageDestinationLossyCompressionQuality: 0.85]
        CGImageDestinationAddImage(dst, img, props as CFDictionary)
        return CGImageDestinationFinalize(dst) ? d as Data : nil
    }

    /// Ảnh vẽ vào bitmap RGBA 8-bit (nhỏ — ảnh đã thu) để đọc alpha.
    private static func alphaBytes(_ img: CGImage, maxEdge: Int = 256) -> [UInt8] {
        let k = min(1, Double(maxEdge) / Double(max(img.width, img.height)))
        let w = max(1, Int(Double(img.width) * k)), h = max(1, Int(Double(img.height) * k))
        var buf = [UInt8](repeating: 0, count: w * h)
        buf.withUnsafeMutableBytes { p in
            guard let ctx = CGContext(data: p.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.alphaOnly.rawValue)
            else { return }
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        return buf
    }

    static func hasTransparency(_ img: CGImage) -> Bool {
        switch img.alphaInfo {
        case .none, .noneSkipFirst, .noneSkipLast: return false
        default: return alphaBytes(img).contains { $0 < 255 }
        }
    }

    /// Tỉ lệ điểm ảnh phủ (alpha ≥ 50 %).
    static func coverage(_ img: CGImage) -> Double {
        let a = alphaBytes(img)
        guard !a.isEmpty else { return 0 }
        return Double(a.filter { $0 >= 128 }.count) / Double(a.count)
    }
}

// MARK: - Điều phối trong app (hỏi → tải → dọn)

/// Một lượt tải cho một theme: tệp thiếu + câu hỏi; lỗi / cảnh báo theo URL để trình sửa hiện.
@MainActor
final class ThemeAssetCenter: ObservableObject {
    static let shared = ThemeAssetCenter()

    struct Request: Identifiable {
        let id = UUID()
        let files: [ThemeAssetFile]
        /// Màn hiện câu hỏi (mỗi màn chỉ hiện câu hỏi của mình — tránh hai alert chồng nhau).
        var context = ""
        var prompt: String { ThemeAssetPrompt.text(files) }
    }
    /// Đang chờ người dùng trả lời (alert).
    @Published var pending: Request?
    @Published private(set) var busy = false
    /// Lỗi / cảnh báo lần tải gần nhất, theo URL.
    @Published private(set) var errors: [String: ThemeAssetError] = [:]
    @Published private(set) var warnings: [String: ThemeAssetWarning] = [:]

    var dir: URL? { ThemeAssetStore.defaultDir }
    var transport: ThemeAssetTransport = URLSessionAssetTransport()

    /// Tệp thiếu của bộ tệp `a` (đọc mục lục; không mạng).
    func missing(_ a: ThemeAssetSpec) -> [ThemeAssetFile] {
        guard !a.isEmpty else { return [] }
        return ThemeAssetStore.missing(ThemeAssetPlan.make(a), index: ThemeAssetStore.readIndex(dir), dir: dir)
    }

    /// Cảnh báo đã lưu (mục lục) cho các tệp của `a`.
    func storedWarnings(_ a: ThemeAssetSpec) -> [ThemeAssetWarning] {
        let idx = ThemeAssetStore.readIndex(dir)
        return ThemeAssetPlan.make(a).files.compactMap { idx[$0.key]?.warn.flatMap(ThemeAssetWarning.init(rawValue:)) }
    }

    /// Có tệp thiếu ⇒ đặt câu hỏi (KHÔNG tải). Plus khoá ⇒ không hỏi (giao diện tự tạo đã chặn ở cổng Plus).
    func offer(_ a: ThemeAssetSpec, context: String = "picker") {
        guard ThemeGate.allowsCustom else { return }
        let m = missing(a)
        pending = m.isEmpty ? nil : Request(files: m, context: context)
    }

    /// Alert của màn `context`.
    func binding(_ context: String) -> Binding<Bool> {
        Binding(get: { self.pending?.context == context }, set: { if !$0 { self.pending = nil } })
    }

    /// Người dùng đồng ý.
    func accept(_ r: Request) {
        pending = nil
        guard let dir, !busy else { return }
        busy = true
        let d = ThemeAssetDownloader(transport: transport, dir: dir,
                                     screenLongSidePx: ThemeAssetDownloader.deviceScreenLongSidePx)
        Task {
            let out = await d.download(r.files)
            for f in out.done { errors[f.ref.url] = nil }
            for (f, e) in out.failed { errors[f.ref.url] = e }
            for (f, w) in out.warnings { warnings[f.ref.url] = w }
            busy = false
            Self.touchKeyboard()
        }
    }

    func errors(for a: ThemeAssetSpec) -> [(String, ThemeAssetError)] {
        ThemeAssetPlan.make(a).files.compactMap { f in errors[f.ref.url].map { (f.ref.url, $0) } }
    }

    /// Bàn phím đọc lại kho ở lần hiện kế (chữ ký kế hoạch gồm mtime mục lục).
    static func touchKeyboard() { ThemeAssetLoader.dropCache() }

    /// Dọn tệp không theme nào còn dùng (kho theme tự tạo + bản vá mọi theme có sẵn).
    @discardableResult
    static func collectGarbage(_ d: UserDefaults?) -> [String] {
        guard let d else { return [] }
        let specs = Array(CustomThemeStore.load(d).values)
        let styles = Array(KeyStyleStore.parse(d.string(forKey: KeyStyleStore.key)).values)
        return ThemeAssetStore.collectGarbage(dir: ThemeAssetStore.defaultDir,
                                              referenced: ThemeAssetStore.referencedKeys(specs: specs, styles: styles))
    }

    /// Mọi tệp thiếu của mọi theme đã lưu (sau khôi phục sao lưu ⇒ đề nghị tải lại).
    static func allMissing(_ d: UserDefaults?) -> [ThemeAssetFile] {
        guard let d else { return [] }
        var a: [ThemeAssetSpec] = CustomThemeStore.load(d).values.map(\.assets)
        a += KeyStyleStore.parse(d.string(forKey: KeyStyleStore.key)).values.map(\.assets)
        let dir = ThemeAssetStore.defaultDir
        let idx = ThemeAssetStore.readIndex(dir)
        var out = Set<ThemeAssetFile>()
        for x in a where !x.isEmpty { out.formUnion(ThemeAssetStore.missing(ThemeAssetPlan.make(x), index: idx, dir: dir)) }
        return out.sorted()
    }

    /// Sau khôi phục: hỏi tải lại mọi tệp thiếu (cùng câu hỏi).
    func offerAfterRestore(_ d: UserDefaults?) {
        guard ThemeGate.allowsCustom else { return }
        let m = Self.allMissing(d)
        pending = m.isEmpty ? nil : Request(files: m, context: "restore")
    }
}
