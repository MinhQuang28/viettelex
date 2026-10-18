import XCTest
@testable import TelexCore

// EAGER ENGLISH RESTORE (18/10/2026, maintainer-approved): with live spell-check on,
// "google" used to show "gôg" → "gôgle" mid-word and only snap back to "google" at the
// word boundary. Now, at the single moment the word freezes, a raw prefix that is an
// English freeze prefix (EnglishFreezePrefixes, generated from enlexicon.bin) restores
// the whole word to raw right away: "goog", then plain typing.
//
// Contract guarded here:
//  1. google/facebook/github/windows/youtube… never show a diacritic once frozen;
//  2. a raw prefix NOT in the table keeps the historical behaviour exactly;
//  3. the COMMITTED text never changes (suite + enlexicon + Vietnamese corpora);
//  4. Vietnamese words and their common typos never trigger it;
//  5. elongation ("quàaa") and cancels ("gooogle", POLICY V2 trailing cancel) unchanged;
//  6. ⌫ after an eager restore re-opens composition like before ("goo" → "gô" → "gố").
final class EagerEnglishRestoreTests: XCTestCase {

    private func engine(eager: Bool, free: Bool = true, simple: Bool = false,
                        teen: Bool = false) -> TelexEngine {
        var e = TelexEngine()
        e.liveSpellCheck = true
        e.eagerEnglishRestore = eager
        e.freeMarking = free
        e.simpleTelex = simple
        e.teencode = teen
        return e
    }

    /// Screen after every key (applies each action to a simulated screen).
    private func screens(_ keys: String, eager: Bool, free: Bool = true, simple: Bool = false) -> [String] {
        var e = engine(eager: eager, free: free, simple: simple)
        var screen: [Character] = []
        var out: [String] = []
        for ch in keys {
            switch e.feed(ch) {
            case .passthrough: screen.append(ch)
            case let .replace(bs, ins):
                screen.removeLast(min(bs, screen.count)); screen.append(contentsOf: ins)
            case .none: break
            }
            XCTAssertEqual(String(screen), e.composed, "screen desync typing \(keys)")
            out.append(String(screen))
        }
        return out
    }

    private func commit(_ keys: String, eager: Bool, free: Bool = true, simple: Bool = false,
                        teen: Bool = false) -> String {
        var e = engine(eager: eager, free: free, simple: simple, teen: teen)
        for ch in keys { _ = e.feed(ch) }
        return e.commitText(autoRestore: true)
    }

    private static func hasDiacritic(_ s: String) -> Bool { s.unicodeScalars.contains { $0.value > 0x7F } }

    // MARK: - 1. The feature

    func testGoogleRestoresAtFreeze() {
        let s = screens("google", eager: true)
        XCTAssertEqual(s, ["g", "go", "gô", "goog", "googl", "google"])
        // Historical (flag off) is unchanged: marks until the boundary.
        XCTAssertEqual(screens("google", eager: false), ["g", "go", "gô", "gôg", "gôgl", "gôgle"])
    }

    func testCommonBrandAndEnglishWordsNeverShowMarksAfterFreeze() {
        let words = ["google", "facebook", "github", "windows", "youtube", "Google", "GOOGLE",
                     "booking", "looking", "toward", "between", "wifi", "twitter", "software",
                     "password", "keyboard", "chrome", "zoom", "hello", "coffee",
                     "awesome", "downloaded", "teeth", "weekend", "seeds"]
        for simple in [false, true] {
            for w in words {
                var e = engine(eager: true, simple: simple)
                // Checked from the decision key on: the freeze key, or key 4 when the word
                // froze earlier ("ưin" shows for ONE key, then "wind").
                var frozenOnce = false
                var n = 0
                for ch in w {
                    _ = e.feed(ch); n += 1
                    if e.spellCheckFreezeAt != nil { frozenOnce = true }
                    if frozenOnce, n >= EnglishFreezePrefixes.minLength {
                        XCTAssertFalse(Self.hasDiacritic(e.composed),
                                       "\(w) (simple=\(simple)) shows \(e.composed) after the freeze")
                    }
                }
                XCTAssertEqual(e.commitText(autoRestore: true), w)
            }
        }
    }

    func testFirstRestoreIsOneReplaceAction() {
        // "gô" + g: ONE rewrite of the composed tail (ô → oog), the same .replace shape
        // every transform uses — no reset/emptying of the word.
        var e = engine(eager: true)
        for ch in "goo" { _ = e.feed(ch) }
        XCTAssertEqual(e.feed("g"), .replace(backspaces: 1, insert: "oog"))
        XCTAssertEqual(e.feed("l"), .passthrough)
        XCTAssertEqual(e.feed("e"), .passthrough)
        XCTAssertEqual(e.commitBoundary(autoRestore: true), .none)   // already raw
    }

    // MARK: - 2. Not in the table → historical behaviour

    func testNonEnglishPrefixKeepsMarksUntilBoundary() {
        // Vietnamese-looking typos / teencode: no English word starts like this.
        for keys in ["dduwowcjx", "nguowfiz", "tieengsx", "khoongb", "quafa", "hoonggg", "dduowcjj"] {
            XCTAssertEqual(screens(keys, eager: true), screens(keys, eager: false), keys)
        }
    }

    func testShortPrefixesNeverRestoreEarly() {
        // Below the 4-key minimum ("gog" … freezes at 3 keys) the table cannot match.
        XCTAssertEqual(EnglishFreezePrefixes.minLength, 4)
        for keys in ["aaz", "ooz", "dds"] {
            XCTAssertEqual(screens(keys, eager: true), screens(keys, eager: false), keys)
        }
    }

    func testFlagOffOrVNIIsInert() {
        XCTAssertEqual(screens("google", eager: false).last, "gôgle")
        var v = engine(eager: true); v.vniMode = true
        for ch in "go6og" { _ = v.feed(ch) }
        XCTAssertNotEqual(v.spellCheckFreezeAt, 0)
    }

    // MARK: - 5. Elongation and cancels unchanged

    func testElongationEscapeUnchanged() {
        for keys in ["quafaa", "quafaaa", "cosaaaa", "hoongggg", "ddepjpppp"] {
            XCTAssertEqual(screens(keys, eager: true), screens(keys, eager: false), keys)
        }
        for free in [true, false] {
            XCTAssertEqual(screens("quafaa", eager: true, free: free), screens("quafaa", eager: false, free: free))
        }
    }

    func testDeliberateCancelKeepsScreenTruth() {
        // A cancel is the user's own escape — eager restore must not fire on it.
        // "tomorrow": the rr cancel happens before the freeze — historical display.
        for keys in ["gooogle", "pass", "boss", "off", "messs", "thiss", "tomorrow", "sorry"] {
            XCTAssertEqual(screens(keys, eager: true), screens(keys, eager: false), keys)
            XCTAssertEqual(commit(keys, eager: true), commit(keys, eager: false), keys)
        }
    }

    // MARK: - 6. Backspace

    func testBackspaceAfterEagerRestoreReopensComposition() {
        var e = engine(eager: true)
        for ch in "google" { _ = e.feed(ch) }
        XCTAssertEqual(e.composed, "google")
        _ = e.backspace(); XCTAssertEqual(e.composed, "googl")
        _ = e.backspace(); XCTAssertEqual(e.composed, "goog")   // still an English prefix
        // "goo" is a valid Vietnamese prefix again ("gô"): composition re-opens exactly
        // like the historical frozen-word ⌫ (BackspaceTests.testBackspaceReopensFrozenWord).
        XCTAssertEqual(e.backspace(), .replace(backspaces: 3, insert: "ô"))
        XCTAssertEqual(e.composed, "gô")
        _ = e.feed("s"); XCTAssertEqual(e.composed, "gố")
        // …and retyping "gle" from "gô" freezes + restores again.
        var f = engine(eager: true)
        for ch in "goog" { _ = f.feed(ch) }
        _ = f.backspace()
        for ch in "gle" { _ = f.feed(ch) }
        XCTAssertEqual(f.composed, "google")
        XCTAssertEqual(f.commitText(autoRestore: true), "google")
    }

    func testBackspaceReplayMatchesForwardTyping() {
        // recomputeFreeze (⌫ twin) must reach the same freeze state as forward typing.
        for w in ["google", "facebook", "github", "windows", "youtube", "toward", "booking"] {
            for cut in 1..<w.count {
                var a = engine(eager: true)
                for ch in w { _ = a.feed(ch) }
                for _ in 0..<cut { _ = a.backspace() }
                var b = engine(eager: true)
                for ch in a.rawKeystrokes { _ = b.feed(ch) }
                XCTAssertEqual(a.composed, b.composed, "\(w) ⌫×\(cut)")
                XCTAssertEqual(a.spellCheckFreezeAt, b.spellCheckFreezeAt, "\(w) ⌫×\(cut)")
            }
        }
    }

    func testMidWordFlagFlipRecomputes() {
        // liveSpellCheck flipped on mid-word replays the freeze — eager included.
        var e = engine(eager: true); e.liveSpellCheck = false
        for ch in "googl" { _ = e.feed(ch) }
        XCTAssertEqual(e.composed, "gôgl")
        e.liveSpellCheck = true
        _ = e.feed("e")
        XCTAssertEqual(e.composed, "google")
    }

    // MARK: - 3. Committed text never changes

    private func enlexiconWords() -> [String] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("iOS/Keyboard/Resources/enlexicon.bin")
        guard let d = try? Data(contentsOf: url) else { return [] }
        let b = [UInt8](d)
        guard b.count > 8 else { return [] }
        let n = Int(b[4]) | Int(b[5]) << 8 | Int(b[6]) << 16 | Int(b[7]) << 24
        var out: [String] = [], p = 8
        for _ in 0..<n {
            let len = Int(b[p]); p += 2
            out.append(String(decoding: b[p..<(p + len)], as: UTF8.self)); p += len
        }
        return out
    }

    private func suiteInputs() throws -> [String] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "telex_test_suite", withExtension: "csv"))
        let text = try String(contentsOf: url, encoding: .utf8)
        return text.split(whereSeparator: { $0.isNewline }).dropFirst().compactMap { line in
            let f = line.split(separator: ",", omittingEmptySubsequences: false)
            return f.count >= 2 ? String(f[1]) : nil
        }
    }

    /// Words split on non-letters, like the controller does.
    private func letterRuns(_ s: String) -> [String] {
        s.split(whereSeparator: { !($0.isASCII && $0.isLetter) }).map(String.init)
    }

    func testCommittedTextUnchangedAcrossCorpora() throws {
        var corpus = enlexiconWords()
        XCTAssertGreaterThan(corpus.count, 19_000, "enlexicon.bin not found")
        for input in try suiteInputs() { corpus += letterRuns(input) }
        corpus += corpus.filter { !$0.isEmpty }.map { $0.prefix(1).uppercased() + $0.dropFirst() }
        var eagerFired = 0
        for simple in [false, true] {
            for free in [true, false] {
                for teen in [false, true] {
                    for w in corpus where !w.isEmpty && w.count <= 30 {
                        let off = commit(w, eager: false, free: free, simple: simple, teen: teen)
                        let on = commit(w, eager: true, free: free, simple: simple, teen: teen)
                        if on != off {
                            XCTFail("commit changed for \(w) (free=\(free) simple=\(simple) teen=\(teen)): \(off) → \(on)")
                        }
                        if !simple, free, !teen {
                            var e = engine(eager: true)
                            for ch in w { _ = e.feed(ch) }
                            if e.spellCheckFreezeAt == 0 { eagerFired += 1 }
                        }
                    }
                }
            }
        }
        print("EAGER fired on \(eagerFired) of \(corpus.count) corpus words (macOS defaults)")
        XCTAssertGreaterThan(eagerFired, 1000)
    }

    // MARK: - 4. Vietnamese + typos never trigger it

    private static let markExpansion: [Character: String] = [
        "â": "aa", "ă": "aw", "ê": "ee", "ô": "oo", "ơ": "ow", "ư": "uw", "đ": "dd",
    ]
    private static let toneKeys: [Character] = ["s", "f", "r", "x", "j"]

    /// Every valid toneless syllable (onset × rime) as Telex keys, with every tone key,
    /// placed at the end AND right after the nucleus' first vowel letter group.
    private func vietnameseKeySequences() -> [String] {
        var out = Set<String>()
        for onset in SyllableValidator.onsets where onset != "kr" {
            for rime in SyllableValidator.rimes {
                let word = onset + rime
                guard SyllableValidator.isValidSyllable(word, teencode: false) else { continue }
                var keys = ""
                for ch in word { keys += Self.markExpansion[ch] ?? String(ch) }
                out.insert(keys)
                for t in Self.toneKeys { out.insert(keys + String(t)) }
            }
        }
        return Array(out)
    }

    func testVietnameseTyposNeverEagerRestore() {
        let base = vietnameseKeySequences()
        XCTAssertGreaterThan(base.count, 5_000)
        let letters = Array("abcdefghijklmnopqrstuvwxyz")
        var variants = Set<String>()
        for k in base {
            let a = Array(k)
            variants.insert(k)
            for i in 0..<a.count {
                var d = a; d.remove(at: i); variants.insert(String(d))            // dropped key
                var u = a; u.insert(a[i], at: i); variants.insert(String(u))      // doubled key
                if i + 1 < a.count { var s = a; s.swapAt(i, i + 1); variants.insert(String(s)) }
            }
            for c in letters { variants.insert(k + String(c)) }                  // overshoot
        }
        var hits: [String: String] = [:]
        for simple in [false, true] {
            for v in variants where v.count >= EnglishFreezePrefixes.minLength {
                var e = engine(eager: true, simple: simple)
                var r = 0
                for ch in v {
                    _ = e.feed(ch); r += 1
                    if e.spellCheckFreezeAt == 0 {
                        let pre = String(v.prefix(r))
                        hits[pre] = hits[pre] ?? v
                        // Even then the COMMIT must not change (an invalid typo restores
                        // to raw at the boundary anyway — eager only shows it sooner).
                        XCTAssertEqual(commit(v, eager: true, simple: simple),
                                       commit(v, eager: false, simple: simple), "typo \(v)")
                        break
                    }
                }
            }
        }
        // Accepted collisions: the raw keys of these "typos" ARE the English word (or
        // its prefix) letter for letter, and a word that is a typo of no real syllable
        // restores to raw at the boundary anyway — eager restore only shows it sooner.
        let report = hits.keys.sorted().map { "\($0)←\(hits[$0]!)" }
        print("EAGER VN-typo hits (\(report.count)): \(report.joined(separator: " "))")
        XCTAssertLessThanOrEqual(hits.count, Self.acceptedTypoHits, "new Vietnamese-typo collisions: \(report)")
    }
    /// Measured 18/10/2026 with the 1001-prefix table: 98 prefixes, every one the
    /// literal spelling of an English word start (book, good, down…, i.e. a 3-key
    /// syllable + one overshoot key, or a transposition like maan→aman). A table regen
    /// that adds more must be reviewed (add to gen-freeze-prefixes' `protected`).
    private static let acceptedTypoHits = 98
}
