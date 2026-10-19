// PR #129: thêm app vào Bảng cơ chế gõ bằng đường dẫn .app (chọn / kéo thả) thay vì tự tra bundle id.
import XCTest
@testable import VietTelex

final class AppPickerBundleIDTests: XCTestCase {
    private let calculator = "/System/Applications/Calculator.app"

    func testReadsBundleIDFromAppPath() throws {
        try XCTSkipUnless(FileManager.default.fileExists(atPath: calculator))
        XCTAssertEqual(SettingsModel.extractBundleID(from: calculator), "com.apple.calculator")
        XCTAssertEqual(SettingsModel.extractBundleID(from: "  \(calculator)/  \n"), "com.apple.calculator")
        XCTAssertEqual(SettingsModel.extractBundleID(from: "file://\(calculator)/"), "com.apple.calculator")
        XCTAssertNotNil(SettingsModel.nameCache["com.apple.calculator"], "tên hiển thị được nhớ sẵn")
    }

    func testTildePathExpands() throws {
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        let tmp = (home as NSString).appendingPathComponent("vtx-pr129-\(UUID().uuidString).app")
        let contents = (tmp as NSString).appendingPathComponent("Contents")
        try FileManager.default.createDirectory(atPath: contents, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(atPath: tmp) }
        let plist: NSDictionary = ["CFBundleIdentifier": "com.example.pr129", "CFBundlePackageType": "APPL"]
        plist.write(toFile: (contents as NSString).appendingPathComponent("Info.plist"), atomically: true)
        let tilde = "~/" + (tmp as NSString).lastPathComponent
        XCTAssertEqual(SettingsModel.extractBundleID(from: tilde), "com.example.pr129")
    }

    func testNonAppPathsAndPlainIDs() {
        XCTAssertNil(SettingsModel.extractBundleID(from: "/usr/bin"), "đường dẫn không phải app ⇒ không có mã")
        XCTAssertNil(SettingsModel.extractBundleID(from: "/nope/Missing.app"))
        XCTAssertTrue(SettingsModel.looksLikeAppPath("/usr/bin"))
        XCTAssertFalse(SettingsModel.looksLikeAppPath("com.google.Chrome"), "bundle id gõ tay vẫn đi đường cũ")
        XCTAssertNil(SettingsModel.extractBundleID(from: "com.google.Chrome"))
    }
}
