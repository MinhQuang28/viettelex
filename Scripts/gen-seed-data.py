#!/usr/bin/env python3
"""gen-seed-data.py — sinh iOS/Keyboard/SeedData.swift (seed ban đầu của UserLangModel) từ corpus
hội thoại + lớp chọn tay Scripts/seed-curated.txt. Chỉ python3 stdlib.

Đầu vào (dữ liệu thô KHÔNG commit — lệnh đầy đủ: docs/DATA-SOURCES.md mục "SeedData"):
  --unigrams U.tsv  "âm_tiết<TAB>số đếm có trọng số"   (gen-syllable-lm.py unigram, nguồn hội thoại)
  --bigrams  B.tsv  "a b<TAB>số đếm có trọng số"       (gen-syllable-lm.py unigram --bigram-out)
  --curated  Scripts/seed-curated.txt                  (lớp chọn tay: teencode, từ tiếng Anh, tên riêng…)

Phần corpus:
  - unigram: --n-uni âm tiết phổ biến nhất (bỏ từ của SensitiveWords.swift — chúng chỉ đến từ lớp
    chọn tay); trọng số w = round(50·(c/c_max)^K), sàn 2 — K = 0,3 khớp bảng cũ (OpenSubtitles2018:
    tôi 711.535 → 48, chờ 16.120 → 17, sữa 1.208 → 6). Hợp đồng: max ≤ 50 (SeedDataTests).
  - bigram: --n-bi cặp có số đếm lớn nhất trong các cặp có PMI = ln(c(ab)·N / (c(a)·c(b))) ≥
    --min-pmi (bỏ cặp "chỉ vì hai từ đều phổ biến": của tôi, tôi không…) và hai âm tiết đều không
    nhạy cảm; trọng số cùng công thức theo c_max của cặp.
Gộp: chọn tay [unigram]/[bigram] lấy max với corpus; [pin] ép đúng trọng số (ô trống: em > anh >
tôi). Thứ tự ra: khối corpus (trọng số giảm dần, rồi theo từ), rồi các nhóm chọn tay theo thứ tự file
(bỏ mục đã có ở khối corpus).

Usage:
  python3 Scripts/gen-seed-data.py --unigrams U.tsv --bigrams B.tsv [--out iOS/Keyboard/SeedData.swift]
  python3 Scripts/gen-seed-data.py --corpus-from-seed   (chỉ sửa seed-curated.txt / SensitiveWords, không có
       U.tsv/B.tsv: giữ nguyên khối corpus của SeedData.swift hiện có; dừng nếu từ mới lọc nằm trong khối đó)
  python3 Scripts/gen-seed-data.py --corpus-from-seed --check   (so với file đã commit; khác ⇒ exit 1)
  Cũng ghi bảng SensitivePairs (khối [sensitive-bigram]) vào iOS/Keyboard/SensitiveWords.swift,
  android/…/SensitiveWords.kt (giữa dòng BEGIN/END GENERATED) và windows/app/core/sensitive_pairs.inc.
  rồi: python3 android/keyboard/scripts/export-ios-data.py . (seed.tsv), Scripts/gen-seed-next.sh,
       python3 windows/app/tools/gen_seed_unigrams.py, windows/app/tools/gen_prediction_golden.sh
"""
import argparse, math, os, re, sys, unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
K = 0.3
WMAX = 50


def sensitive_words():
    src = open(os.path.join(ROOT, "iOS/Keyboard/SensitiveWords.swift"), encoding="utf-8").read()
    m = re.search(r"static let set: Set<String> = \[(.*?)\]", src, re.S)
    body = m.group(1) if m else src
    return set(re.findall(r'"([^"]+)"', body))


def weight(c, cmax, k=K):
    return max(2, min(WMAX, round(WMAX * (c / cmax) ** k)))


def read_tsv(path, n_key):
    out = []
    for line in open(path, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) != 2: continue
        key = tuple(unicodedata.normalize("NFC", parts[0]).split(" "))
        if len(key) != n_key: continue
        out.append((key, float(parts[1])))
    return out


SENSITIVE_PAIRS = "sensitive-bigram"


def read_curated(path):
    """→ pins {w: c}, groups [(kind, title|None, [(key tuple, c)])], loại {từ | "a b"}, cặp nhạy cảm
    [(a, b)] (khối [sensitive-bigram]: vào seed như [bigram] + bảng SensitivePairs)."""
    pins, groups, kind, excl, spairs = {}, [], None, set(), []
    for raw in open(path, encoding="utf-8"):
        line = raw.strip()
        if not line or (line.startswith("#") and not line.startswith("##")): continue
        gk = "bigram" if kind == SENSITIVE_PAIRS else kind
        if line.startswith("["):
            kind = line.strip("[]")
            groups.append(("bigram" if kind == SENSITIVE_PAIRS else kind, None, [])); continue
        if line.startswith("##"):
            groups.append((gk, line[2:].strip(), [])); continue
        *ws, c = unicodedata.normalize("NFC", line).split()
        if kind == "pin": pins[ws[0]] = int(c)
        elif kind == "exclude": excl.add(" ".join(ws + [c]))
        else:
            groups[-1][2].append((tuple(ws), int(c)))
            if kind == SENSITIVE_PAIRS:
                assert len(ws) == 2 and all(w == w.lower() for w in ws), line
                spairs.append(tuple(ws))
    return pins, groups, excl, spairs


def swift_lines(items, per_line, fmt):
    out = []
    for i in range(0, len(items), per_line):
        out.append("        " + " ".join(fmt(x) for x in items[i:i + per_line]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--unigrams")
    ap.add_argument("--bigrams")
    ap.add_argument("--corpus-from-seed", action="store_true",
                    help="lấy khối corpus từ SeedData.swift hiện có (khi chỉ sửa seed-curated.txt và không "
                         "có U.tsv/B.tsv); chỉ đúng khi từ mới thêm vào SensitiveWords/[exclude] không nằm "
                         "trong khối corpus")
    ap.add_argument("--check", action="store_true",
                    help="chỉ so với file đã commit (SeedData.swift + bảng SensitivePairs); khác ⇒ exit 1")
    ap.add_argument("--curated", default=os.path.join(ROOT, "Scripts/seed-curated.txt"))
    ap.add_argument("--out", default=os.path.join(ROOT, "iOS/Keyboard/SeedData.swift"))
    ap.add_argument("--n-uni", type=int, default=500)
    ap.add_argument("--n-bi", type=int, default=380)
    ap.add_argument("--min-pmi", type=float, default=1.0)
    ap.add_argument("--k", type=float, default=K)
    a = ap.parse_args()

    sens = sensitive_words()
    pins, groups, excl, spairs = read_curated(a.curated)
    # từ SAU là từ thường (từ tục đã lọc theo từ); từ TRƯỚC có thể tục ("fuck boy": người dùng tự gõ "fuck",
    # lọc theo từ không chặn được "boy" sau nó)
    bad = [p for p in spairs if p[1] in sens]
    assert not bad, f"[sensitive-bigram]: từ sau đã lọc theo từ (SensitiveWords) — để ở [bigram]: {bad}"
    assert len(set(spairs)) == len(spairs), "cặp nhạy cảm trùng"
    if a.corpus_from_seed:
        corpus_uni, corpus_bi = corpus_from_seed(a.out)
        bad = [w for w in corpus_uni if w in sens or w in excl] + \
              [k for k in corpus_bi if k[0] in sens or k[1] in sens or " ".join(k) in excl]
        assert not bad, f"từ nhạy cảm/loại nằm trong khối corpus — cần chạy lại từ U.tsv/B.tsv: {bad}"
    else:
        assert a.unigrams and a.bigrams, "cần --unigrams/--bigrams (hoặc --corpus-from-seed)"
        corpus_uni, corpus_bi = corpus_from_tsv(a, sens, pins, excl)
    outs = {a.out: seed_swift(a, pins, groups, corpus_uni, corpus_bi)}
    outs.update(sensitive_pair_outputs(spairs))
    stale = []
    for path, text in outs.items():
        cur = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if cur == text: continue
        stale.append(os.path.relpath(path, ROOT))
        if not a.check: open(path, "w", encoding="utf-8").write(text)
    if a.check:
        if stale:
            print("lỗi thời — chạy lại Scripts/gen-seed-data.py: " + ", ".join(stale), file=sys.stderr); sys.exit(1)
        print("SeedData + SensitivePairs khớp seed-curated.txt", file=sys.stderr)
    else:
        print(f"SensitivePairs: {len(spairs)} cặp; ghi lại: {', '.join(stale) or '(không đổi)'}", file=sys.stderr)


BEGIN = "BEGIN GENERATED SensitivePairs"
END = "END GENERATED SensitivePairs"


def splice(path, body, comment):
    """Thay khối giữa dòng chú thích BEGIN/END (giữ nguyên phần còn lại của file viết tay)."""
    src = open(path, encoding="utf-8").read()
    lines = src.split("\n")
    b = next(i for i, l in enumerate(lines) if BEGIN in l)
    e = next(i for i, l in enumerate(lines) if END in l)
    indent = lines[b][:len(lines[b]) - len(lines[b].lstrip())]
    head = f"{indent}{comment} {BEGIN} — Scripts/gen-seed-data.py từ Scripts/seed-curated.txt [sensitive-bigram]; KHÔNG sửa tay."
    return "\n".join(lines[:b] + [head] + [indent + x if x else x for x in body] + [lines[e]] + lines[e + 1:])


def sensitive_pair_outputs(spairs):
    by = {}
    for x, y in spairs: by.setdefault(x, []).append(y)
    keys = sorted(by, key=lambda k: k.encode("utf-8"))
    def q(s): return '"' + s + '"'
    sw = ["static let pairs: [String: Set<String>] = ["]
    sw += [f"    {q(k)}: [{', '.join(q(v) for v in sorted(by[k]))}]," for k in keys]
    sw += ["]"]
    kt = ["val pairs: Map<String, Set<String>> = hashMapOf("]
    kt += [f"    {q(k)} to hashSetOf({', '.join(q(v) for v in sorted(by[k]))})," for k in keys]
    kt += [")"]
    rows = sorted((f"{x} {y}" for x, y in spairs), key=lambda r: r.encode("utf-8"))
    inc = ["// GENERATED by Scripts/gen-seed-data.py from Scripts/seed-curated.txt [sensitive-bigram]. Do not edit.",
           f"// SensitivePairs: {len(rows)} \"prev word\" pairs of two ordinary words, sorted by UTF-8 bytes —",
           "// with \"Lọc từ nhạy cảm\" on, `word` is never suggested after `prev` (isSensitivePair).",
           "const char* const kSensitivePairs[] = {"]
    inc += [f"    {q(r)}," for r in rows] + ["};", ""]
    sw_path = os.path.join(ROOT, "iOS/Keyboard/SensitiveWords.swift")
    kt_path = os.path.join(ROOT, "android/keyboard/src/main/kotlin/com/viettelex/keyboard/SensitiveWords.kt")
    return {
        sw_path: splice(sw_path, sw, "//"),
        kt_path: splice(kt_path, kt, "//"),
        os.path.join(ROOT, "windows/app/core/sensitive_pairs.inc"): "\n".join(inc),
    }


def corpus_from_seed(path):
    """Khối corpus (trước dòng chú thích nhóm chọn tay đầu tiên) của SeedData.swift đã sinh."""
    src = open(path, encoding="utf-8").read()
    def block(name):
        body = src.split(f"static var {name}:", 1)[1].split("] }", 1)[0]
        return body.split("\n        //", 1)[0]
    uni = {w: int(c) for w, c in re.findall(r'"([^"]+)": (\d+),', block("unigrams"))}
    bi = {(x, y): int(c) for x, y, c in re.findall(r'\("([^"]+)", "([^"]+)", (\d+)\),', block("bigrams"))}
    return uni, bi


def corpus_from_tsv(a, sens, pins, excl):
    uni_all = {k[0]: c for k, c in read_tsv(a.unigrams, 1)}
    N = sum(uni_all.values())
    uni_sorted = sorted(((w, c) for w, c in uni_all.items() if w not in sens and w not in excl),
                        key=lambda x: (-x[1], x[0]))
    top = uni_sorted[:a.n_uni]
    cmax = top[0][1]
    cap = min(pins.values()) - 1 if pins else WMAX        # chỉ từ [pin] đứng đầu ô trống
    corpus_uni = {w: min(cap, weight(c, cmax, a.k)) for w, c in top}

    bis = []
    for (x, y), c in read_tsv(a.bigrams, 2):
        if x in sens or y in sens or x in excl or y in excl or f"{x} {y}" in excl: continue
        cx, cy = uni_all.get(x, 0), uni_all.get(y, 0)
        if not cx or not cy: continue
        if math.log(c * N / (cx * cy)) < a.min_pmi: continue
        bis.append(((x, y), c))
    bis.sort(key=lambda t: (-t[1], t[0]))
    bis = bis[:a.n_bi]
    bmax = bis[0][1]
    corpus_bi = {k: weight(c, bmax, a.k) for k, c in bis}
    return corpus_uni, corpus_bi


def seed_swift(a, pins, groups, corpus_uni, corpus_bi):
    # gộp max với corpus
    for kind, _, items in groups:
        for key, c in items:
            if kind == "unigram" and key[0] in corpus_uni:
                corpus_uni[key[0]] = max(corpus_uni[key[0]], c)
            if kind == "bigram" and key in corpus_bi:
                corpus_bi[key] = max(corpus_bi[key], c)
    for w, c in pins.items(): corpus_uni[w] = c

    def q(s): return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'
    uni_lines = swift_lines(sorted(corpus_uni.items(), key=lambda t: (-t[1], t[0])), 8,
                            lambda t: f"{q(t[0])}: {t[1]},")
    bi_lines = swift_lines(sorted(corpus_bi.items(), key=lambda t: (-t[1], t[0])), 5,
                           lambda t: f"({q(t[0][0])}, {q(t[0][1])}, {t[1]}),")
    seen_u, seen_b = set(corpus_uni), set(corpus_bi)
    n_cu = n_cb = 0
    for kind, title, items in groups:
        if kind == "pin": continue
        if kind == "unigram":
            new = [(k[0], c) for k, c in items if k[0] not in seen_u]
            for w, _ in new:
                assert w not in seen_u, w
                seen_u.add(w)
            if title: uni_lines.append(f"        // {title}")
            uni_lines += swift_lines(new, 8, lambda t: f"{q(t[0])}: {t[1]},")
            n_cu += len(new)
        elif kind == "bigram":
            new = [(k, c) for k, c in items if k not in seen_b]
            for k, _ in new: seen_b.add(k)
            if title: bi_lines.append(f"        // {title}")
            bi_lines += swift_lines(new, 5, lambda t: f"({q(t[0][0])}, {q(t[0][1])}, {t[1]}),")
            n_cb += len(new)
    n_u, n_b = len(seen_u), len(seen_b)
    for w in seen_u: assert unicodedata.normalize("NFC", w) == w and w == w.lower(), w
    swift = f"""// SeedData.swift — GENERATED bởi Scripts/gen-seed-data.py (KHÔNG sửa tay — sửa
// Scripts/seed-curated.txt rồi chạy lại). Seed ban đầu cho UserLangModel (lần đầu chạy / sau khi
// user bấm "Xoá từ đã học"). {len(corpus_uni) - len(pins)} âm tiết + {len(corpus_bi)} cặp từ corpus hội thoại
// (OpenSubtitles v2024 + lát diễn đàn FineWeb-2 + Tatoeba — docs/DATA-SOURCES.md mục "SeedData") +
// lớp chọn tay {n_cu} từ / {n_cb} cặp (chat/teencode, từ tiếng Anh cap ≤ 18, tên riêng, từ nhạy cảm).
// Weight log-scale 50·(c/c_max)^{a.k:g}, max 50 — user gõ thật (+1/lần, accepted +2) sẽ vượt seed sau
// vài ngày; decay tuần làm seed mờ dần.
import Foundation

// PERF: computed var (không phải stored let) — literal ~{(n_u + n_b) // 100 * 100} entries chỉ build
// khi thật sự seed (store trống), giải phóng ngay sau đó thay vì găm
// ~150-250KB suốt đời keyboard; seedIfEmpty nhận @autoclosure nên store đã có
// dữ liệu thì literal không bao giờ được build.
enum SeedData {{
    static var unigrams: [String: Int] {{ [
{chr(10).join(uni_lines)}
    ] }}

    static var bigrams: [(String, String, Int)] {{ [
{chr(10).join(bi_lines)}
    ] }}
}}
"""
    print(f"{os.path.relpath(a.out, ROOT)}: {n_u} unigram ({len(corpus_uni)} corpus+pin, {n_cu} chọn tay), "
          f"{n_b} bigram ({len(corpus_bi)} corpus, {n_cb} chọn tay)", file=sys.stderr)
    return swift


if __name__ == "__main__":
    main()
