package com.viettelex.keyboard

import java.io.File
import java.security.MessageDigest

/*
 * "Giao diện JSON" — tệp tải về (phông / ảnh nền / tiền cảnh / skin phím). iOS y hệt
 * (iOS/Keyboard/ThemeAssets.swift): cùng kế hoạch (ThemeAssetPlan — chuỗi mô tả trùng từng ký tự, fixture chung
 * theme-json-cases.txt dòng PLAN), cùng giới hạn, cùng toán 9-slice, cùng tên tệp / mục lục.
 *
 * Luồng: JSON chỉ là chỉ dẫn. APP (app ui/ThemeAssetDownloader.kt) hỏi người dùng rồi mới tải, kiểm (https, cỡ,
 * loại, sha256), giải mã có thu nhỏ, lưu lại bản NHỎ không metadata vào filesDir/theme_assets (tên = sha256 của
 * tệp tải về ⇒ trùng nội dung dùng chung) + mục lục index.json. Bàn phím (cùng process) KHÔNG BAO GIỜ dùng mạng:
 * chỉ đọc mục lục + tệp đã xử lý (app ime/ThemeAssetLoader.kt), giải mỗi ảnh MỘT lần mỗi lần nạp theme, dùng
 * chung Bitmap cho mọi phím cùng nhóm; thiếu tệp / hỏng ⇒ màu theme / phông mặc định (không crash).
 * Theme không có tệp tải về ⇒ không đọc gì (0 chi phí).
 */

// ---- Giới hạn (chung hai nền tảng)

@Suppress("EnumEntryName")
enum class ThemeAssetClass {
    /** Phông .ttf/.otf (lưu nguyên). */
    font,
    /** Ảnh nền / tiền cảnh: thu ≤ cỡ bàn phím. */
    screen,
    /** Skin phím: thu ≤ 512 px. */
    key;

    val raw: String get() = name

    companion object {
        fun of(raw: String): ThemeAssetClass? = entries.firstOrNull { it.name == raw }
    }
}

object ThemeAssetLimits {
    const val FONT_MAX_BYTES = 2 * 1024 * 1024
    const val IMAGE_MAX_BYTES = 5 * 1024 * 1024
    /** Cạnh dài tối đa đã lưu. */
    const val KEY_MAX_EDGE = 512
    const val SCREEN_MAX_EDGE = 1600
    /** Chặn "bom giải nén": đọc kích thước từ header TRƯỚC khi giải. */
    const val MAX_SOURCE_PIXELS = 40_000_000L
    const val MAX_SOURCE_EDGE = 12_000
    const val TIMEOUT_MS = 20_000
    const val MAX_REDIRECTS = 3
    /** Tiền cảnh phủ (alpha ≥ 50 %) quá tỉ lệ này ⇒ cảnh báo khó đọc chữ. */
    const val FOREGROUND_COVERAGE_WARN = 0.35
    /** Cạnh dài khi BÀN PHÍM giải skin phím thường (space / return: [KEY_MAX_EDGE]). */
    const val KEY_DECODE_EDGE = 192

    /** Cạnh dài lưu cho ảnh nền / tiền cảnh: ≤ cạnh dài màn hình (px) và ≤ 1600. */
    fun screenEdge(screenLongSidePx: Int): Int = maxOf(64, minOf(SCREEN_MAX_EDGE, screenLongSidePx))

    fun maxBytes(c: ThemeAssetClass): Int = if (c == ThemeAssetClass.font) FONT_MAX_BYTES else IMAGE_MAX_BYTES

    /**
     * Cạnh dài khi BÀN PHÍM giải skin: phím rộng (space / return) 512, phím thường 192 — đủ nét ở xxhdpi,
     * 16 skin × 192² × 4 B ≈ 2,4 MB thay vì 16 MB.
     */
    fun keyDecodeEdge(roles: Set<String>): Int =
        if ("space" in roles || "return" in roles) KEY_MAX_EDGE else KEY_DECODE_EDGE
}

// ---- Kế hoạch (thuần — chuỗi mô tả trùng iOS)

/** Tệp cần có: lớp + URL + sha256. Khoá mục lục = "lớp|url|sha256 hoặc -". */
data class ThemeAssetFile(val cls: ThemeAssetClass, val ref: ThemeAssetRef) : Comparable<ThemeAssetFile> {
    val key: String get() = "${cls.raw}|${ref.url}|${ref.sha256 ?: "-"}"
    override fun compareTo(other: ThemeAssetFile): Int = key.compareTo(other.key)
}

data class ThemeAssetPlan(
    val fontName: String? = null,
    val font: ThemeAssetRef? = null,
    val background: ThemeAssetRef? = null,
    val foreground: ThemeAssetRef? = null,
    /** Vai phím → skin ĐÃ GIẢI (từng phím → nhóm). Không có ⇒ màu theme. */
    val skins: Map<String, Skin> = emptyMap(),
) {
    data class Skin(val image: ThemeAssetRef, val pressed: ThemeAssetRef? = null, val slice: List<Int>? = null)

    val isEmpty: Boolean get() = font == null && background == null && foreground == null && skins.isEmpty()

    fun skin(role: String): Skin? = skins[role]

    /** Mọi tệp khác nhau cần có (sắp xếp theo khoá — so sánh chuỗi UTF-16 như Swift `<` trên ASCII). */
    val files: List<ThemeAssetFile> get() {
        val s = HashSet<ThemeAssetFile>()
        font?.let { s.add(ThemeAssetFile(ThemeAssetClass.font, it)) }
        background?.let { s.add(ThemeAssetFile(ThemeAssetClass.screen, it)) }
        foreground?.let { s.add(ThemeAssetFile(ThemeAssetClass.screen, it)) }
        for (k in skins.values) {
            s.add(ThemeAssetFile(ThemeAssetClass.key, k.image))
            k.pressed?.let { s.add(ThemeAssetFile(ThemeAssetClass.key, it)) }
        }
        return s.sorted()
    }

    /** Chuỗi mô tả CHUẨN (fixture PLAN, iOS trùng từng ký tự). */
    val description: String get() {
        fun r(x: ThemeAssetRef) = x.url + "#" + (x.sha256 ?: "-")
        val parts = ArrayList<String>()
        font?.let { parts.add("font=" + r(it)) }
        background?.let { parts.add("bg=" + r(it)) }
        foreground?.let { parts.add("fg=" + r(it)) }
        for (role in ROLES) {
            val k = skins[role] ?: continue
            val sb = StringBuilder("key.").append(role).append('=').append(r(k.image))
            k.pressed?.let { sb.append('>').append(r(it)) }
            k.slice?.let { sb.append('@').append(it.joinToString(",")) }
            parts.add(sb.toString())
        }
        val fs = files
        val images = fs.filter { it.cls != ThemeAssetClass.font }.map { it.ref.url }.toSet().size
        parts.add("files=${fs.size}")
        parts.add("images=$images")
        return parts.joinToString("|")
    }

    /** Chữ ký để cache (đổi URL / sha / slice ⇒ nạp lại). */
    val signature: String get() = description

    companion object {
        /** Thứ tự vai (chuỗi mô tả + vòng lặp + chỉ số mảng skin của bàn phím). */
        val ROLES: List<String> = ThemeAssetSpec.GROUP_KEYS + ThemeAssetSpec.NAMED_KEYS + ThemeAssetSpec.LETTER_KEYS

        /**
         * Thứ tự giải: chữ a–z ⇒ "a" → "default"; space / return / shift / delete ⇒ chính nó → "special";
         * "default" / "special" ⇒ chính nó (phím số / ký hiệu dùng "default", 123 / 🌐 / emoji dùng "special").
         */
        fun resolve(role: String, keys: Map<String, ThemeKeySkin>): ThemeKeySkin? {
            keys[role]?.let { return it }
            if (role in ThemeAssetSpec.NAMED_KEYS) return keys["special"]
            if (role in ThemeAssetSpec.LETTER_KEYS) return keys["default"]
            return null
        }

        fun make(a: ThemeAssetSpec): ThemeAssetPlan {
            val skins = LinkedHashMap<String, Skin>()
            for (r in ROLES) resolve(r, a.keys)?.let { skins[r] = Skin(it.image, it.pressed, it.slice) }
            return ThemeAssetPlan(a.font?.name, a.font?.ref, a.background, a.foreground, skins)
        }

        /** Ảnh nền theme chỉ hiện khi đã tải xong VÀ ảnh nền tự đặt của người dùng không bật (ảnh người dùng thắng). */
        fun showsThemeBackground(userWallpaperActive: Boolean, themeBackgroundReady: Boolean): Boolean =
            themeBackgroundReady && !userWallpaperActive

        /** URL ảnh khác nhau (giới hạn 16) — tính trên đặc tả (cả skin không vai nào dùng). */
        fun imageURLs(a: ThemeAssetSpec): Set<String> {
            val s = HashSet<String>()
            a.background?.let { s.add(it.url) }
            a.foreground?.let { s.add(it.url) }
            for (k in a.keys.values) {
                s.add(k.image.url)
                k.pressed?.let { s.add(it.url) }
            }
            return s
        }
    }
}

// ---- 9-slice (thuần — iOS cùng công thức)

object NineSlice {
    /**
     * Đổi insets [t,l,b,r] từ px ảnh `from` sang px ảnh `to` (theo từng trục, làm tròn), kẹp để phần giữa
     * còn ≥ 1 px: t+b ≤ h−1, l+r ≤ w−1 (vượt ⇒ thu cả hai theo tỉ lệ).
     */
    fun scale(s: List<Int>, fromW: Int, fromH: Int, toW: Int, toH: Int): List<Int> {
        if (s.size != 4 || fromW <= 0 || fromH <= 0 || toW <= 0 || toH <= 0) return listOf(0, 0, 0, 0)
        val ky = toH.toDouble() / fromH; val kx = toW.toDouble() / fromW
        val (t, b) = fit(Math.round(s[0] * ky).toInt(), Math.round(s[2] * ky).toInt(), toH - 1)
        val (l, r) = fit(Math.round(s[1] * kx).toInt(), Math.round(s[3] * kx).toInt(), toW - 1)
        return listOf(t, l, b, r)
    }

    private fun fit(a: Int, b: Int, room0: Int): Pair<Int, Int> {
        val room = maxOf(room0, 0)
        if (a + b <= room || a + b <= 0) return a to b
        val k = room.toDouble() / (a + b)
        return Math.floor(a * k).toInt() to Math.floor(b * k).toInt()
    }

    /**
     * Insets ĐÍCH (dp / px) trên phím W×H: ảnh gốc cao `srcH` px phủ đúng chiều cao phím ⇒ hệ số H/srcH;
     * góc rộng hơn phím (l+r > W hoặc t+b > H) ⇒ thu ĐỀU cả bốn (giữ hình góc).
     */
    fun destInsets(slice: List<Int>, srcH: Int, width: Double, height: Double): List<Double> {
        if (slice.size != 4) return listOf(0.0, 0.0, 0.0, 0.0)
        val out = DoubleArray(4)
        destInsetsInto(slice[0], slice[1], slice[2], slice[3], srcH, width, height, out)
        return out.toList()
    }

    /** Như [destInsets] nhưng ghi vào [out] (không cấp phát — bàn phím gọi lúc vẽ). */
    fun destInsetsInto(t: Int, l: Int, b: Int, r: Int, srcH: Int, width: Double, height: Double, out: DoubleArray) {
        if (srcH <= 0 || width <= 0 || height <= 0) { out.fill(0.0); return }
        val f = height / srcH
        out[0] = t * f; out[1] = l * f; out[2] = b * f; out[3] = r * f
        val k = minOf(1.0, width / maxOf(out[1] + out[3], 1e-9), height / maxOf(out[0] + out[2], 1e-9))
        if (k < 1) for (i in 0..3) out[i] *= k
    }

    /** Hệ số thu đều (≤ 1) của destInsets. */
    fun shrink(slice: List<Int>, srcH: Int, width: Double, height: Double): Double {
        if (slice.size != 4 || srcH <= 0 || width <= 0 || height <= 0) return 1.0
        val f = height / srcH
        return minOf(1.0, width / maxOf((slice[1] + slice[3]) * f, 1e-9), height / maxOf((slice[0] + slice[2]) * f, 1e-9))
    }

    /** Hình chữ nhật (x, y, rộng, cao). */
    data class R(val x: Double, val y: Double, val w: Double, val h: Double)
    data class Patch(val src: R, val dst: R)

    /**
     * Lưới 4 mốc mỗi trục: nguồn [xs]/[ys] (px ảnh w×h, insets px) và đích [dx]/[dy] (khung W×H, insets đích d).
     * Mảnh (i, j) = [xs[i], xs[i+1]) × [ys[j], ys[j+1]) → [dx[i], dx[i+1]) × [dy[j], dy[j+1]). Không cấp phát.
     */
    fun grid(w: Int, h: Int, t: Int, l: Int, b: Int, r: Int, width: Double, height: Double, d: DoubleArray,
             xs: DoubleArray, ys: DoubleArray, dx: DoubleArray, dy: DoubleArray) {
        xs[0] = 0.0; xs[1] = l.toDouble(); xs[2] = (w - r).toDouble(); xs[3] = w.toDouble()
        ys[0] = 0.0; ys[1] = t.toDouble(); ys[2] = (h - b).toDouble(); ys[3] = h.toDouble()
        dx[0] = 0.0; dx[1] = d[1]; dx[2] = width - d[3]; dx[3] = width
        dy[0] = 0.0; dy[1] = d[0]; dy[2] = height - d[2]; dy[3] = height
    }

    /** 9 mảnh (ảnh w×h px, insets px) → khung đích W×H với insets đích. Mảnh rỗng bỏ. Bàn phím vẽ đúng các mảnh này. */
    fun patches(imageW: Int, imageH: Int, insets: List<Int>, width: Double, height: Double, dest: List<Double>): List<Patch> {
        if (insets.size != 4 || dest.size != 4) return emptyList()
        val xs = DoubleArray(4); val ys = DoubleArray(4); val dx = DoubleArray(4); val dy = DoubleArray(4)
        grid(imageW, imageH, insets[0], insets[1], insets[2], insets[3], width, height, dest.toDoubleArray(), xs, ys, dx, dy)
        val out = ArrayList<Patch>()
        for (j in 0 until 3) for (i in 0 until 3) {
            val src = R(xs[i], ys[j], xs[i + 1] - xs[i], ys[j + 1] - ys[j])
            val dst = R(dx[i], dy[j], dx[i + 1] - dx[i], dy[j + 1] - dy[j])
            if (src.w > 0 && src.h > 0 && dst.w > 0 && dst.h > 0) out.add(Patch(src, dst))
        }
        return out
    }
}

// ---- Kho (filesDir/theme_assets + index.json)

object ThemeAssetStore {
    const val DIR_NAME = "theme_assets"
    const val INDEX_NAME = "index.json"
    const val INDEX_MAX_BYTES = 256 * 1024

    /** Khoá tiến trình: tải (ghi tệp + mục lục) và dọn không chen nhau. */
    val LOCK = Any()

    data class Entry(
        /** Tên tệp đã xử lý: "<sha256 tệp tải về>.<lớp>.<đuôi>". */
        val file: String,
        /** Cỡ đã lưu (px; phông = 0). */
        val w: Int,
        val h: Int,
        /** Cỡ ảnh gốc (px) — slice tính theo cỡ này. */
        val sw: Int,
        val sh: Int,
        /** Cảnh báo lưu kèm ("missingVietnamese", "foregroundCoverage") — trình sửa hiện. */
        val warn: String? = null,
    )

    const val WARN_MISSING_VIETNAMESE = "missingVietnamese"
    const val WARN_FOREGROUND_COVERAGE = "foregroundCoverage"

    val FILE_EXTS: Map<ThemeAssetClass, Set<String>> = mapOf(ThemeAssetClass.font to setOf("ttf", "otf"),
        ThemeAssetClass.screen to setOf("jpg", "png"), ThemeAssetClass.key to setOf("jpg", "png"))

    fun dir(filesDir: File): File = File(filesDir, DIR_NAME)

    fun fileName(sha256: String, cls: ThemeAssetClass, ext: String): String = "$sha256.${cls.raw}.$ext"

    /** Chỉ tên do app đặt (64 hex . lớp . đuôi) — mục lục hỏng / bị sửa không trỏ ra ngoài thư mục được. */
    fun validFileName(n: String): Boolean {
        val p = n.split('.')
        if (p.size != 3 || ThemeAssetURL.sha256(p[0]) != p[0]) return false
        val c = ThemeAssetClass.of(p[1]) ?: return false
        return FILE_EXTS[c]?.contains(p[2]) == true
    }

    private val LIMITS = StrictJson.Limits(maxBytes = INDEX_MAX_BYTES, maxDepth = 4, maxString = 400, maxMembers = 4096)

    /** Đọc mục lục CHẶT; hỏng / quá lớn / sai phiên bản ⇒ rỗng; mục sai ⇒ bỏ. */
    fun parseIndex(text: String): Map<String, Entry> {
        val root = (StrictJson.parse(text, LIMITS) as? StrictJson.Result.Ok)?.v as? SJ.Obj ?: return emptyMap()
        if ((root["v"] as? SJ.Num)?.v != 1) return emptyMap()
        val es = root["entries"] as? SJ.Obj ?: return emptyMap()
        val out = HashMap<String, Entry>()
        for ((k, v) in es.m) {
            val o = v as? SJ.Obj ?: continue
            val file = (o["file"] as? SJ.Str)?.s ?: continue
            fun num(n: String) = (o[n] as? SJ.Num)?.v
            val w = num("w") ?: continue; val h = num("h") ?: continue
            val sw = num("sw") ?: continue; val sh = num("sh") ?: continue
            val warn = when (val x = o["warn"]) { null -> null; is SJ.Str -> x.s; else -> continue }
            if (!validFileName(file) || w < 0 || h < 0 || sw < 0 || sh < 0) continue
            out[k] = Entry(file, w, h, sw, sh, warn)
        }
        return out
    }

    fun serializeIndex(m: Map<String, Entry>): String = SJ.Obj(listOf("v" to SJ.Num(1), "entries" to SJ.Obj(m.map { (k, e) ->
        val f = ArrayList<Pair<String, SJ>>()
        f.add("file" to SJ.Str(e.file)); f.add("w" to SJ.Num(e.w)); f.add("h" to SJ.Num(e.h))
        f.add("sw" to SJ.Num(e.sw)); f.add("sh" to SJ.Num(e.sh))
        e.warn?.let { f.add("warn" to SJ.Str(it)) }
        k to SJ.Obj(f)
    }))).canonical

    fun readIndex(dir: File?): Map<String, Entry> {
        if (dir == null) return emptyMap()
        val f = File(dir, INDEX_NAME)
        return try {
            if (!f.isFile || f.length() > INDEX_MAX_BYTES) emptyMap() else parseIndex(f.readText(Charsets.UTF_8))
        } catch (e: Exception) { emptyMap() }
    }

    /** Ghi nguyên tử (tệp tạm + đổi tên). */
    fun writeIndex(m: Map<String, Entry>, dir: File) {
        dir.mkdirs()
        val tmp = File(dir, ".$INDEX_NAME.tmp")
        tmp.writeText(serializeIndex(m), Charsets.UTF_8)
        if (!tmp.renameTo(File(dir, INDEX_NAME))) { tmp.delete(); throw java.io.IOException("rename index") }
    }

    /**
     * Thêm một tệp đã xử lý: [tmp] (ngoài thư mục kho) đổi tên thành [Entry.file] trong [dir] rồi ghi mục lục —
     * cả hai trong [LOCK] (dọn rác không thấy tệp chưa có mục lục).
     */
    fun put(dir: File, key: String, entry: Entry, tmp: File) {
        require(validFileName(entry.file))
        synchronized(LOCK) {
            dir.mkdirs()
            val dst = File(dir, entry.file)
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true); tmp.delete()
            }
            val m = HashMap(readIndex(dir))
            m[key] = entry
            writeIndex(m, dir)
        }
    }

    /**
     * Tệp đã xử lý cho [ref] lớp [cls] (bàn phím nạp): có mục lục VÀ tệp còn trên đĩa; không ⇒ null (phím vẽ màu theme,
     * phông mặc định).
     */
    fun locate(cls: ThemeAssetClass, ref: ThemeAssetRef?, index: Map<String, Entry>, dir: File): Pair<File, Entry>? {
        ref ?: return null
        val e = index[ThemeAssetFile(cls, ref).key] ?: return null
        val f = File(dir, e.file)
        return if (f.isFile) f to e else null
    }

    /** Tệp kế hoạch cần mà chưa có (mục lục thiếu hoặc tệp mất). */
    fun missing(plan: ThemeAssetPlan, index: Map<String, Entry>, dir: File?): List<ThemeAssetFile> =
        plan.files.filter { f ->
            val e = index[f.key]
            e == null || dir == null || !File(dir, e.file).exists()
        }

    /** Khoá mục lục mọi theme còn tham chiếu (kho theme tự tạo + bản vá theme có sẵn — kể cả theme không chọn). */
    fun referencedKeys(specs: Collection<ThemeSpec>, styles: Collection<KeyStyle>): Set<String> {
        val s = HashSet<String>()
        for (a in specs.map { it.assets } + styles.map { it.assets }) {
            if (a.isEmpty) continue
            for (f in ThemeAssetPlan.make(a).files) s.add(f.key)
        }
        return s
    }

    data class Gc(val index: Map<String, Entry>, val delete: List<String>)

    /** Dọn (thuần): bỏ mục lục không còn tham chiếu; xoá tệp không mục nào trỏ tới (kể cả tệp lạ). */
    fun gcPlan(index: Map<String, Entry>, referenced: Set<String>, files: List<String>): Gc {
        val keep = index.filterKeys { it in referenced }
        val live = keep.values.map { it.file }.toSet()
        return Gc(keep, files.filter { it != INDEX_NAME && it !in live }.sorted())
    }

    /** Dọn thật (APP gọi sau lưu / xoá / dán / khôi phục, và lúc khởi động). Trả tên tệp đã xoá. */
    fun collectGarbage(dir: File?, referenced: Set<String>): List<String> {
        if (dir == null) return emptyList()
        synchronized(LOCK) {
            val files = dir.list()?.toList() ?: return emptyList()
            val idx = readIndex(dir)
            val p = gcPlan(idx, referenced, files)
            if (p.index != idx) runCatching { writeIndex(p.index, dir) }
            for (f in p.delete) File(dir, f).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
            return p.delete
        }
    }
}

// ---- Tải về: lỗi, nhận dạng loại, sha256, kích thước giải (thuần — app ui/ThemeAssetDownloader.kt dùng)

/** Mã lỗi tải / xử lý (iOS cùng tên). */
@Suppress("EnumEntryName")
enum class ThemeAssetError {
    notHttps, redirectInsecure, httpStatus, tooLarge, wrongType, shaMismatch, timeout, offline, decodeFailed,
    fontInvalid, tooManyPixels;

    val message: String get() = when (this) {
        notHttps -> tr("Chỉ tải qua https.")
        redirectInsecure -> tr("Máy chủ chuyển hướng sang địa chỉ không an toàn.")
        httpStatus -> tr("Máy chủ báo lỗi (không tìm thấy tệp?).")
        tooLarge -> tr("Tệp quá lớn.")
        wrongType -> tr("Sai loại tệp.")
        shaMismatch -> tr("Tệp không khớp mã sha256.")
        timeout -> tr("Hết thời gian chờ.")
        offline -> tr("Không có kết nối mạng.")
        decodeFailed -> tr("Không đọc được ảnh.")
        fontInvalid -> tr("Không đọc được phông.")
        tooManyPixels -> tr("Ảnh quá nhiều điểm ảnh.")
    }
}

object ThemeAssetSniff {
    /**
     * Đuôi theo "số ma thuật" (không tin Content-Type / đuôi URL): phông 00010000 / "OTTO" / "true" (từ chối
     * ttcf, woff); ảnh JPEG, PNG, WebP (RIFF....WEBP), HEIC (ftyp heic/heix/hevc/hevx/mif1/msf1). Sai ⇒ null.
     */
    fun ext(b: ByteArray, cls: ThemeAssetClass): String? {
        fun at(i: Int, vararg v: Int) = b.size >= i + v.size && v.indices.all { (b[i + it].toInt() and 0xFF) == v[it] }
        fun ascii(i: Int, s: String) = at(i, *s.map { it.code }.toIntArray())
        return if (cls == ThemeAssetClass.font) when {
            at(0, 0x00, 0x01, 0x00, 0x00) || ascii(0, "true") -> "ttf"
            ascii(0, "OTTO") -> "otf"
            else -> null
        } else when {
            at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "webp"
            ascii(4, "ftyp") && listOf("heic", "heix", "hevc", "hevx", "mif1", "msf1").any { ascii(8, it) } -> "heic"
            else -> null
        }
    }

    fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
}

/** Bảng cmap của phông sfnt (định dạng 4 / 12) — kiểm phông có đủ chữ Việt (thuần, không tin hệ thống fallback). */
object FontCmap {
    /** Mẫu chữ Việt bắt buộc (thiếu ⇒ cảnh báo "missingVietnamese"). */
    const val VIETNAMESE_SAMPLE = "ạếữđƠƯ"

    /** Ký tự trong [chars] mà phông KHÔNG có glyph; null = không đọc được cmap. */
    fun missing(font: ByteArray, chars: String): List<Int>? = runCatching {
        val cps = chars.codePoints().toArray()
        val covered = BooleanArray(cps.size)
        fun u16(o: Int) = ((font[o].toInt() and 0xFF) shl 8) or (font[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        val numTables = u16(4)
        var cmap = -1
        for (i in 0 until numTables) {
            val rec = 12 + i * 16
            if (String(font, rec, 4, Charsets.US_ASCII) == "cmap") cmap = u32(rec + 8).toInt()
        }
        if (cmap < 0) return@runCatching null
        val n = u16(cmap + 2)
        var found = false
        for (i in 0 until n) {
            val rec = cmap + 4 + i * 8
            val pid = u16(rec); val eid = u16(rec + 2)
            if (!(pid == 0 || (pid == 3 && (eid == 1 || eid == 10)))) continue
            val sub = cmap + u32(rec + 4).toInt()
            when (u16(sub)) {
                4 -> {
                    found = true
                    val segX2 = u16(sub + 6)
                    val ends = sub + 14; val starts = ends + segX2 + 2; val deltas = starts + segX2; val offs = deltas + segX2
                    for ((ci, cp) in cps.withIndex()) {
                        if (cp > 0xFFFF || covered[ci]) continue
                        for (s in 0 until segX2 / 2) {
                            val end = u16(ends + 2 * s); val start = u16(starts + 2 * s)
                            if (cp > end) continue
                            if (cp < start) break
                            val ro = u16(offs + 2 * s)
                            val glyph = if (ro == 0) (cp + u16(deltas + 2 * s)) and 0xFFFF else {
                                val g = u16(offs + 2 * s + ro + 2 * (cp - start))
                                if (g == 0) 0 else (g + u16(deltas + 2 * s)) and 0xFFFF
                            }
                            if (glyph != 0) covered[ci] = true
                            break
                        }
                    }
                }
                12 -> {
                    found = true
                    val groups = u32(sub + 12).toInt()
                    for ((ci, cp) in cps.withIndex()) {
                        if (covered[ci]) continue
                        for (g in 0 until groups) {
                            val o = sub + 16 + g * 12
                            if (cp >= u32(o) && cp <= u32(o + 4)) { if (u32(o + 8) + (cp - u32(o)) != 0L) covered[ci] = true; break }
                        }
                    }
                }
            }
        }
        if (!found) null else cps.filterIndexed { i, _ -> !covered[i] }
    }.getOrNull()
}

/** Toán kích thước giải ảnh (thuần — app tải về + bàn phím nạp dùng chung). */
object ThemeAssetImageMath {
    /** Ảnh gốc w×h có được giải không: ≤ 40 MP và cạnh ≤ 12000 ([ThemeAssetError.tooManyPixels]); 0 ⇒ decodeFailed. */
    fun checkSource(w: Int, h: Int): ThemeAssetError? = when {
        w <= 0 || h <= 0 -> ThemeAssetError.decodeFailed
        w > ThemeAssetLimits.MAX_SOURCE_EDGE || h > ThemeAssetLimits.MAX_SOURCE_EDGE ||
            w.toLong() * h > ThemeAssetLimits.MAX_SOURCE_PIXELS -> ThemeAssetError.tooManyPixels
        else -> null
    }

    /** inSampleSize (lũy thừa 2) lớn nhất mà cạnh dài sau giải vẫn ≥ [maxEdge] (không bao giờ giải cả ảnh to). */
    fun sampleSize(w: Int, h: Int, maxEdge: Int): Int {
        val long = maxOf(w, h)
        var s = 1
        while (long / (s * 2) >= maxEdge) s *= 2
        return s
    }

    /** Cỡ đích giữ tỉ lệ, cạnh dài ≤ [maxEdge] (không phóng to). */
    fun fit(w: Int, h: Int, maxEdge: Int): IntArray {
        val long = maxOf(w, h)
        if (long <= maxEdge || long <= 0) return intArrayOf(maxOf(w, 1), maxOf(h, 1))
        val k = maxEdge.toDouble() / long
        return intArrayOf(maxOf(1, Math.round(w * k).toInt()), maxOf(1, Math.round(h * k).toInt()))
    }

    /** inSampleSize để ảnh w×h giải ra vẫn PHỦ khung vw×vh (≥ mỗi cạnh, như WallpaperMath). */
    fun coverSample(w: Int, h: Int, vw: Int, vh: Int): Int {
        if (vw <= 0 || vh <= 0) return 1
        var s = 1
        while (w / (s * 2) >= vw && h / (s * 2) >= vh) s *= 2
        return s
    }

    /** Vùng giữa (x, y, rộng, cao) của ảnh w×h cùng tỉ lệ khung vw×vh (aspect-fill: phần thừa cắt đều hai bên). */
    fun fillCrop(w: Int, h: Int, vw: Int, vh: Int): IntArray {
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return intArrayOf(0, 0, maxOf(w, 0), maxOf(h, 0))
        return if (w.toLong() * vh > h.toLong() * vw) {
            val cw = maxOf(1, Math.round(h.toDouble() * vw / vh).toInt()); intArrayOf((w - cw) / 2, 0, cw, h)
        } else {
            val ch = maxOf(1, Math.round(w.toDouble() * vh / vw).toInt()); intArrayOf(0, (h - ch) / 2, w, ch)
        }
    }

    /**
     * Cỡ bitmap ảnh nền ([fill] — aspect-fill, chỉ giữ vùng thấy) / tiền cảnh (căng) trong bàn phím: không lớn hơn
     * khung vw×vh, không lớn hơn ảnh đã lưu ⇒ ≤ vw×vh×4 byte mỗi ảnh.
     */
    fun screenDecodeSize(w: Int, h: Int, vw: Int, vh: Int, fill: Boolean): IntArray {
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return intArrayOf(0, 0)
        if (!fill) return intArrayOf(minOf(vw, w), minOf(vh, h))
        val c = fillCrop(w, h, vw, vh)
        val k = minOf(1.0, vw.toDouble() / c[2])
        return intArrayOf(maxOf(1, minOf(vw, Math.round(c[2] * k).toInt())), maxOf(1, minOf(vh, Math.round(c[3] * k).toInt())))
    }

    /** Byte bitmap ARGB_8888. */
    fun bytes(w: Int, h: Int): Long = w.toLong() * h * 4
}

// ---- Câu hỏi trước khi tải (thuần)

object ThemeAssetPrompt {
    /**
     * "Giao diện này cần tải <n> tệp (≈ <KB> KB) từ <domains>. Tải?" — mọi tệp biết trước cỡ (phông có sẵn) ⇒ ≈ KB;
     * không thì "(tối đa <MB> MB)" = tổng trần từng lớp (không gửi yêu cầu nào để hỏi cỡ). iOS y hệt. Rỗng ⇒ null.
     */
    fun text(files: List<ThemeAssetFile>, knownBytes: (ThemeAssetRef) -> Int? = BuiltinFonts::knownBytes): String? {
        if (files.isEmpty()) return null
        val hosts = files.map { ThemeAssetURL.host(it.ref.url) }.toSortedSet().joinToString(", ")
        val known = files.map { knownBytes(it.ref) }
        if (known.all { it != null }) {
            val kb = maxOf(1L, (known.sumOf { it!!.toLong() } + 1023) / 1024)
            return tr("Giao diện này cần tải %s tệp (≈ %s KB) từ %s. Tải?", files.size, kb, hosts)
        }
        val mb = files.sumOf { ThemeAssetLimits.maxBytes(it.cls).toLong() } / (1024 * 1024)
        return tr("Giao diện này cần tải %s tệp (tối đa %s MB) từ %s. Tải?", files.size, mb, hosts)
    }
}

// ---- Phông tải về có sẵn (themes/builtin-fonts.json → BuiltinFontsData.kt)

/** ~12 phông OFL có đủ tiếng Việt, host viettelex.com/fonts kèm sha256 (lưới chọn phông). Đọc một lần. */
object BuiltinFonts {
    data class Entry(val id: String, val name: String, val ref: ThemeAssetRef, val bytes: Int) {
        val asset: ThemeFontAsset get() = ThemeFontAsset(name, ref)
        /** Phông xem trước tí hon (chỉ "V", "t") trong assets của app. */
        val previewAsset: String get() = "fontpreview/" + id.replace('-', '_') + ".ttf"
    }

    val list: List<Entry> by lazy { parse(BuiltinFontsData.JSON) }

    fun parse(text: String): List<Entry> {
        val root = (StrictJson.parse(text, StrictJson.Limits(maxBytes = 64 * 1024)) as? StrictJson.Result.Ok)?.v as? SJ.Obj
            ?: return emptyList()
        val arr = root["fonts"] as? SJ.Arr ?: return emptyList()
        return arr.a.mapNotNull { v ->
            val d = v as? SJ.Obj ?: return@mapNotNull null
            val id = (d["id"] as? SJ.Str)?.s ?: return@mapNotNull null
            val name = (d["name"] as? SJ.Str)?.s ?: return@mapNotNull null
            val url = (d["url"] as? SJ.Str)?.s?.takeIf { ThemeAssetURL.isValid(it, font = true) } ?: return@mapNotNull null
            val sha = (d["sha256"] as? SJ.Str)?.s?.let { ThemeAssetURL.sha256(it) } ?: return@mapNotNull null
            val bytes = (d["bytes"] as? SJ.Num)?.v ?: return@mapNotNull null
            Entry(id, name, ThemeAssetRef(url, sha), bytes)
        }
    }

    /** Cỡ đã biết của tệp (phông có sẵn) — câu hỏi trước khi tải ước lượng KB. */
    fun knownBytes(r: ThemeAssetRef): Int? = list.firstOrNull { it.ref == r }?.bytes
}
