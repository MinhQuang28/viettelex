// MathResultsTests — "Hiện kết quả phép tính" theo fixture chung Fixtures/math-results.txt
// (cùng bộ ca với android MathResultsTests.kt) + vị trí chip trên thanh + điều kiện ô nhập.
import UIKit
import XCTest

final class MathResultsTests: XCTestCase {
    private func fixtureLines() throws -> [[String]] {
        let url = try XCTUnwrap(Bundle(for: MathResultsTests.self)
            .url(forResource: "math-results", withExtension: "txt"))
        let text = try String(contentsOf: url, encoding: .utf8)
        return text.split(separator: "\n").filter { !$0.hasPrefix("#") && !$0.isEmpty }
            .map { $0.split(separator: "|", omittingEmptySubsequences: false)
                .map { String($0).replacingOccurrences(of: "␣", with: " ") } }
    }

    func testFixture() throws {
        var n = 0
        for c in try fixtureLines() {
            n += 1
            switch c[0] {
            case "eval":
                XCTAssertEqual(MathResults.result(c[1]) ?? "-", c[2], "eval \(c[1])")
            case "chip":
                let chip = MathResults.chip(before: c[1])
                if c[2] == "-" {
                    XCTAssertNil(chip, "chip «\(c[1])» phải nil")
                } else {
                    XCTAssertEqual(chip, NumberChip(display: c[2], replace: "", insert: c[2]), "chip «\(c[1])»")
                }
            case "implicit":
                let chip = MathResults.implicitChip(before: c[1])
                if c[2] == "-" {
                    XCTAssertNil(chip, "chip ngầm «\(c[1])» phải nil")
                } else {
                    XCTAssertEqual(chip, NumberChip(display: c[2], replace: "", insert: " " + c[2]), "chip ngầm «\(c[1])»")
                }
            default:
                XCTFail("dòng fixture lạ: \(c)")
            }
        }
        XCTAssertGreaterThan(n, 80)
    }

    /// Cửa chung: "…=" ⇒ chip thường (chèn sau "="); kết thúc bằng phép tính ⇒ chip ngầm.
    func testChipAtCaret() {
        XCTAssertEqual(MathResults.chipAtCaret(before: "12*3="), NumberChip(display: "36", replace: "", insert: "36"))
        XCTAssertEqual(MathResults.chipAtCaret(before: "12*3"), NumberChip(display: "= 36", replace: "", insert: " = 36"))
        XCTAssertNil(MathResults.chipAtCaret(before: "12/10"))
    }

    /// Luật hình dạng tách riêng (field report "dấu bằng để rất sâu" — chip ngầm chỉ cho
    /// phép tính thật, không cho ngày / giờ / điện thoại / phiên bản).
    func testLooksLikeExpression() {
        for e in ["12*3", "1,5+2,5", "100/4", "12 / 10", "10 : 30", "(1+2)", "7-3", "32/5"] {
            XCTAssertTrue(MathResults.looksLikeExpression(e), e)
        }
        for e in ["12", "50%", "-5", "12/10", "1/2", "12/10/2026", "2026-10-18", "10:30", "16:9",
                  "090-123-4567", "5-10", "1990-2000", "1800-599-920", "1.2.3", "3x4"] {
            XCTAssertFalse(MathResults.looksLikeExpression(e), e)
        }
    }

    /// Chip ngầm chỉ xét đuôi ngắn: context dài không đổi kết quả.
    func testImplicitLongContext() {
        let long = String(repeating: "chữ ", count: 500)
        XCTAssertEqual(MathResults.implicitChip(before: long + "12*3")?.display, "= 36")
        XCTAssertNil(MathResults.implicitChip(before: String(repeating: "1+", count: 200) + "1"))
    }

    /// Chip số không còn nhận "…=" (tránh 2 chip cùng một kết quả).
    func testNumberChipsIgnoreEquals() {
        XCTAssertNil(NumberChips.chip(before: "12*3="))
        XCTAssertNotNil(MathResults.chip(before: "12*3="))
    }

    /// Chip kết quả ở slot ĐẦU; chữ cũ dời phải.
    func testSlotFirst() {
        var set = KeyboardView.SuggestionSet()
        set.nextWords = ["a", "b", "c"]
        set.math = "36"
        XCTAssertEqual(SuggestionSlots.arrange(set), .slots([
            BarChip(label: "36", payload: KeyboardView.mathToken),
            BarChip(label: "a", payload: "a"), BarChip(label: "b", payload: "b")], emojis: []))
        XCTAssertFalse(set.isEmpty)
    }

    /// Ô mật khẩu / URL / email: không; ô số: có.
    func testFieldGate() {
        XCTAssertTrue(FieldTraits().allowsMathResults)
        XCTAssertTrue(FieldTraits(keyboardType: .decimalPad).allowsMathResults)
        XCTAssertTrue(FieldTraits(keyboardType: .numbersAndPunctuation).allowsMathResults)
        XCTAssertFalse(FieldTraits(secure: true).allowsMathResults)
        XCTAssertFalse(FieldTraits(keyboardType: .URL).allowsMathResults)
        XCTAssertFalse(FieldTraits(keyboardType: .emailAddress).allowsMathResults)
        XCTAssertFalse(FieldTraits(contentType: .password).allowsMathResults)
        XCTAssertFalse(FieldTraits(contentType: .URL).allowsMathResults)
    }

    // MARK: luồng thật (controller + proxy giả)

    @MainActor private func run(_ mathResults: Bool, _ keys: String) -> KeyboardBenchTests.Rig {
        let d = KeyboardBenchTests.makeDefaults("B")
        d.set(mathResults, forKey: "mathResults")
        UserDefaultsProvider.shared = d
        let rig = KeyboardBenchTests.Rig()
        for c in keys { rig.vc.debugHandle(.text(String(c))) }
        KeyboardBenchTests.spin(0.1)
        return rig
    }

    /// Gõ "12*3=" → chip "36" ở slot đầu; chạm → "12*3=36", chip mất.
    @MainActor func testInsertFlow() {
        let saved = UserDefaultsProvider.shared
        defer { UserDefaultsProvider.shared = saved }
        let rig = run(true, "12*3=")
        defer { rig.close() }
        let kb = rig.vc.debugKeyboard
        XCTAssertEqual(kb.debugSlotPayloads().first ?? nil, KeyboardView.mathToken)
        XCTAssertEqual(kb.debugSlotTitles().first ?? nil, "36")
        rig.vc.debugAcceptSuggestion(KeyboardView.mathToken)
        KeyboardBenchTests.spin(0.1)
        XCTAssertEqual(rig.proxy.text, "12*3=36")
        XCTAssertFalse(kb.debugSlotPayloads().contains(KeyboardView.mathToken))
    }

    /// Field report 18/10/2026 ("dấu bằng để rất sâu"): gõ "12*3" (không "=") → chip "= 36"
    /// ở slot đầu; chạm → "12*3 = 36". Công tắc tắt ⇒ không chip.
    @MainActor func testImplicitInsertFlow() {
        let saved = UserDefaultsProvider.shared
        defer { UserDefaultsProvider.shared = saved }
        let rig = run(true, "12*3")
        let kb = rig.vc.debugKeyboard
        XCTAssertEqual(kb.debugSlotPayloads().first ?? nil, KeyboardView.mathToken)
        XCTAssertEqual(kb.debugSlotTitles().first ?? nil, "= 36")
        rig.vc.debugAcceptSuggestion(KeyboardView.mathToken)
        KeyboardBenchTests.spin(0.1)
        XCTAssertEqual(rig.proxy.text, "12*3 = 36")
        XCTAssertFalse(kb.debugSlotPayloads().contains(KeyboardView.mathToken))
        rig.close()
        let off = run(false, "12*3")
        XCTAssertFalse(off.vc.debugKeyboard.debugSlotPayloads().contains(KeyboardView.mathToken))
        off.close()
        let date = run(true, "12/10")
        XCTAssertFalse(date.vc.debugKeyboard.debugSlotPayloads().contains(KeyboardView.mathToken))
        date.close()
    }

    /// Công tắc tắt ⇒ không chip; phím khác sau "=" ⇒ chip mất (chỉ ngay sau "=").
    @MainActor func testNoTrigger() {
        let saved = UserDefaultsProvider.shared
        defer { UserDefaultsProvider.shared = saved }
        let off = run(false, "12*3=")
        XCTAssertFalse(off.vc.debugKeyboard.debugSlotPayloads().contains(KeyboardView.mathToken))
        off.close()
        let later = run(true, "12*3=+")
        XCTAssertFalse(later.vc.debugKeyboard.debugSlotPayloads().contains(KeyboardView.mathToken))
        later.close()
        // "5=" không phải phép tính.
        let none = run(true, "5=")
        XCTAssertFalse(none.vc.debugKeyboard.debugSlotPayloads().contains(KeyboardView.mathToken))
        none.close()
    }

    func testSettingDefaultOn() {
        XCTAssertTrue(KeyboardSettings().mathResults)
        guard case .bool(true)? = BackupSettings.byKey["mathResults"]?.kind else {
            return XCTFail("thiếu spec sao lưu mathResults (bool, mặc định true)")
        }
    }
}
