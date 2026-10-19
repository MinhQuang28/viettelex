package com.viettelex.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tệp tải về của theme (iOS ThemeAssetsTests cùng số): URL, kế hoạch (thứ tự giải từng phím → nhóm),
 * 9-slice, mục lục + dọn rác, nhận dạng loại / sha256 / cmap phông, toán giải ảnh + ngân sách RAM, câu hỏi
 * trước khi tải, quy tắc gộp phông người dùng / theme, chuỗi chuẩn bản vá có tệp, phông có sẵn.
 */
class ThemeAssetsTests {
    private var savedPaywall = false
    @Before fun setUp() { savedPaywall = PlusGate.paywallEnabled; PlusGate.paywallEnabled = false }
    @After fun tearDown() { PlusGate.paywallEnabled = savedPaywall; L10n.lang = L10n.DEFAULT }

    private fun repoFile(rel: String): File = listOf("../..", "..", ".").map { File(it, rel) }.firstOrNull { it.exists() }
        ?: error("không thấy $rel")

    private val sha = "a".repeat(64)
    private fun ref(u: String, s: String? = null) = ThemeAssetRef(u, s)

    // ---- URL

    @Test fun urlValidator() {
        val ok = listOf("https://viettelex.com/fonts/A.ttf", "https://cdn.example.com/f/A.OTF?v=2", "https://a-b.c0.io/x.ttf#f")
        for (u in ok) assertTrue(u, ThemeAssetURL.isValid(u, font = true))
        val badFont = listOf("https://example.com/a.woff2", "https://example.com/a.ttc", "https://example.com/ttf",
            "https://example.com/a.ttf.png")
        for (u in badFont) { assertFalse(u, ThemeAssetURL.isValid(u, font = true)); assertTrue(u, ThemeAssetURL.isValid(u, font = false)) }
        val bad = listOf("http://example.com/a.png", "https://user@example.com/a.png", "https://example.com:8443/a.png",
            "https://192.168.1.1/a.png", "https://localhost/a.png", "https://Example.com/a.png", "https://example.com/a b.png",
            "https://example.com", "https://example.com?x=/a", "javascript:alert(1)", "data:image/png;base64,AAAA",
            "file:///etc/passwd", "https://exämple.com/a.png", "https://.example.com/a.png", "https://example.com./a.png",
            "https://-example.com/a.png", "https://example.com/a\\b.png", "https://example.com/\"a.png",
            "https://[::1]/a.png", "https://example.com/" + "a".repeat(200), "HTTPS://example.com/a.png")
        for (u in bad) assertFalse(u, ThemeAssetURL.isValid(u, font = false))
        assertEquals("cdn.example.com", ThemeAssetURL.host("https://cdn.example.com/f/A.OTF?v=2"))
        assertEquals(sha, ThemeAssetURL.sha256(sha.uppercase()))
        assertNull(ThemeAssetURL.sha256("abc")); assertNull(ThemeAssetURL.sha256("g".repeat(64)))
    }

    // ---- kế hoạch

    @Test fun resolutionOrderKeyThenGroup() {
        val k = ref("https://example.com/k.png"); val s = ref("https://example.com/s.png")
        val a = ref("https://example.com/a.png"); val sp = ref("https://example.com/sp.png")
        val keys = mapOf("default" to ThemeKeySkin(k), "special" to ThemeKeySkin(s), "a" to ThemeKeySkin(a), "space" to ThemeKeySkin(sp))
        assertEquals(a, ThemeAssetPlan.resolve("a", keys)?.image)
        assertEquals(k, ThemeAssetPlan.resolve("b", keys)?.image)
        assertEquals(sp, ThemeAssetPlan.resolve("space", keys)?.image)
        assertEquals(s, ThemeAssetPlan.resolve("return", keys)?.image)
        assertEquals(s, ThemeAssetPlan.resolve("delete", keys)?.image)
        assertEquals(k, ThemeAssetPlan.resolve("default", keys)?.image)
        assertNull(ThemeAssetPlan.resolve("enter", keys))
        // Chỉ "special" ⇒ phím chữ không skin (màu theme); chỉ "a" ⇒ chữ khác không skin.
        assertNull(ThemeAssetPlan.resolve("b", mapOf("special" to ThemeKeySkin(s))))
        assertNull(ThemeAssetPlan.resolve("b", mapOf("a" to ThemeKeySkin(a))))
        val plan = ThemeAssetPlan.make(ThemeAssetSpec(keys = keys))
        assertEquals(32, plan.skins.size)
        assertEquals(4, plan.files.size)
        assertTrue(ThemeAssetPlan.make(ThemeAssetSpec()).isEmpty)
        assertEquals("files=0|images=0", ThemeAssetPlan.make(ThemeAssetSpec()).description)
        assertEquals(32, ThemeAssetPlan.ROLES.size)
        // Ảnh nền theme: ảnh nền tự đặt của người dùng thắng.
        assertTrue(ThemeAssetPlan.showsThemeBackground(userWallpaperActive = false, themeBackgroundReady = true))
        assertFalse(ThemeAssetPlan.showsThemeBackground(userWallpaperActive = true, themeBackgroundReady = true))
        assertFalse(ThemeAssetPlan.showsThemeBackground(userWallpaperActive = false, themeBackgroundReady = false))
    }

    /** Theme mẫu docs/themes/demo: hợp lệ, không cảnh báo, sha256 khớp tệp trong repo, loại tệp đúng. */
    @Test fun demoThemeIsValidAndHashesMatch() {
        val out = ThemeSpecParser.parse(repoFile("docs/themes/demo/bien-dem.json").readText())
        val s = out.spec ?: error("${out.issues}")
        assertTrue("${out.issues}", out.issues.isEmpty())
        assertNotNull(s.assets.font); assertNotNull(s.assets.background); assertNotNull(s.assets.foreground)
        assertTrue(s.assets.keys.size >= 8)
        for (f in ThemeAssetPlan.make(s.assets).files) {
            val path = f.ref.url.replace("https://viettelex.com/", "docs/")
            val b = repoFile(path).readBytes()
            assertEquals(path, f.ref.sha256, ThemeAssetSniff.sha256Hex(b))
            assertNotNull(path, ThemeAssetSniff.ext(b, f.cls))
            assertTrue(path, b.size <= ThemeAssetLimits.maxBytes(f.cls))
        }
    }

    // ---- 9-slice (cùng số iOS)

    @Test fun nineSliceScale() {
        assertEquals(listOf(15, 15, 17, 15), NineSlice.scale(listOf(30, 30, 34, 30), 120, 140, 60, 70))
        // Vượt chỗ ⇒ thu cả hai theo tỉ lệ (phần giữa còn ≥ 1 px): 50+50 > 69 ⇒ ⌊50×0,69⌋ = 34.
        assertEquals(listOf(34, 5, 34, 5), NineSlice.scale(listOf(100, 10, 100, 10), 120, 140, 60, 70))
        assertEquals(listOf(0, 0, 0, 0), NineSlice.scale(listOf(1, 2, 3), 10, 10, 5, 5))
        assertEquals(listOf(74, 0, 74, 0), NineSlice.scale(listOf(100, 0, 100, 0), 100, 150, 100, 150))   // iOS cùng số
        assertEquals(listOf(0, 0, 0, 0), NineSlice.scale(listOf(1, 2, 3, 4), 0, 10, 5, 5))
        // Làm tròn nửa lên (x,5 ⇒ lên) như Swift .rounded().
        assertEquals(listOf(2, 2, 2, 2), NineSlice.scale(listOf(3, 3, 3, 3), 10, 10, 5, 5))
    }

    @Test fun nineSliceDestInsetsAndShrink() {
        val s = listOf(30, 30, 34, 30)
        val d = NineSlice.destInsets(s, 140, 40.0, 46.0)
        val f = 46.0 / 140
        for (i in 0..3) assertEquals(s[i] * f, d[i], 1e-9)
        assertEquals(1.0, NineSlice.shrink(s, 140, 40.0, 46.0), 1e-12)
        // Phím hẹp 15 px: l+r = 60×46/140 = 19,71 > 15 ⇒ thu đều k = 15/19,71.
        val k = 15.0 / (60 * f)
        assertEquals(k, NineSlice.shrink(s, 140, 15.0, 46.0), 1e-9)
        val n = NineSlice.destInsets(s, 140, 15.0, 46.0)
        for (i in 0..3) assertEquals(s[i] * f * k, n[i], 1e-9)
        assertEquals(15.0, n[1] + n[3], 1e-9)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), NineSlice.destInsets(s, 0, 15.0, 46.0))
        // iOS cùng số: góc 60 px × 46/140 = 19,71 > 30/2 ⇒ còn 15 mỗi cạnh.
        for (v in NineSlice.destInsets(listOf(60, 60, 60, 60), 140, 30.0, 46.0)) assertEquals(15.0, v, 1e-9)
        assertEquals(30.0 / (120.0 * 46 / 140), NineSlice.shrink(listOf(60, 60, 60, 60), 140, 30.0, 46.0), 1e-9)
    }

    @Test fun nineSlicePatches() {
        val ins = listOf(15, 15, 17, 15)
        val d = NineSlice.destInsets(listOf(30, 30, 34, 30), 140, 40.0, 46.0)
        val ps = NineSlice.patches(60, 70, ins, 40.0, 46.0, d)
        assertEquals(9, ps.size)
        assertEquals(NineSlice.R(0.0, 0.0, 15.0, 15.0), ps[0].src)
        assertEquals(NineSlice.R(15.0, 15.0, 30.0, 38.0), ps[4].src)
        assertEquals(40.0 - d[1] - d[3], ps[4].dst.w, 1e-9)
        assertEquals(46.0, ps[6].dst.y + ps[6].dst.h, 1e-9)
        // iOS cùng số: 9 mảnh phủ kín đích, không chồng.
        val dd = NineSlice.destInsets(listOf(30, 30, 34, 30), 140, 200.0, 46.0)
        val p = NineSlice.patches(120, 140, listOf(30, 30, 34, 30), 200.0, 46.0, dd)
        assertEquals(9, p.size)
        assertEquals(200.0 * 46, p.sumOf { it.dst.w * it.dst.h }, 1e-6)
        assertEquals(120.0 * 140, p.sumOf { it.src.w * it.src.h }, 1e-6)
        assertEquals(NineSlice.R(30.0, 30.0, 60.0, 76.0), p[4].src)
        assertEquals(1, NineSlice.patches(10, 10, listOf(0, 0, 0, 0), 50.0, 40.0, listOf(0.0, 0.0, 0.0, 0.0)).size)
        // Phím cách: chỉ cắt trái/phải ⇒ 3 mảnh (hàng giữa).
        assertEquals(3, NineSlice.patches(100, 50, listOf(0, 40, 0, 40), 300.0, 40.0,
            NineSlice.destInsets(listOf(0, 40, 0, 40), 50, 300.0, 40.0)).size)
    }

    // ---- kho

    @Test fun storeIndexValidationAndRoundTrip() {
        val f = "$sha.key.png"
        assertTrue(ThemeAssetStore.validFileName(f))
        assertTrue(ThemeAssetStore.validFileName("$sha.font.otf"))
        for (bad in listOf("../$sha.key.png", "$sha.key.gif", "$sha.font.png", "${sha.uppercase()}.key.png", "$sha.key", "x.key.png",
            "$sha.key.png.png", "index.json"))
            assertFalse(bad, ThemeAssetStore.validFileName(bad))
        val m = mapOf("key|https://example.com/k.png|-" to ThemeAssetStore.Entry(f, 100, 120, 400, 480),
            "font|https://example.com/a.ttf|$sha" to ThemeAssetStore.Entry("$sha.font.ttf", 0, 0, 0, 0, ThemeAssetStore.WARN_MISSING_VIETNAMESE))
        assertEquals(m, ThemeAssetStore.parseIndex(ThemeAssetStore.serializeIndex(m)))
        // Mục trỏ ra ngoài / số âm / sai phiên bản ⇒ bỏ.
        assertEquals(emptyMap<String, ThemeAssetStore.Entry>(), ThemeAssetStore.parseIndex(
            """{"v":1,"entries":{"a":{"file":"../../etc/passwd","w":1,"h":1,"sw":1,"sh":1},"b":{"file":"$f","w":-1,"h":1,"sw":1,"sh":1}}}"""))
        assertEquals(emptyMap<String, ThemeAssetStore.Entry>(), ThemeAssetStore.parseIndex("""{"v":2,"entries":{}}"""))
        assertEquals(emptyMap<String, ThemeAssetStore.Entry>(), ThemeAssetStore.parseIndex("rác"))
    }

    @Test fun gcPlanAndCollectGarbage() {
        val f1 = "${"1".repeat(64)}.key.png"; val f2 = "${"2".repeat(64)}.screen.jpg"
        val idx = mapOf("k1" to ThemeAssetStore.Entry(f1, 1, 1, 1, 1), "k2" to ThemeAssetStore.Entry(f2, 1, 1, 1, 1))
        val p = ThemeAssetStore.gcPlan(idx, setOf("k1"), listOf("index.json", f1, f2, "junk.bin"))
        assertEquals(setOf("k1"), p.index.keys)
        assertEquals(listOf(f2, "junk.bin"), p.delete)

        val dir = Files.createTempDirectory("ta").toFile()
        try {
            val tmp = File.createTempFile("taa", ".tmp"); tmp.writeBytes(byteArrayOf(1, 2, 3))
            ThemeAssetStore.put(dir, "k1", ThemeAssetStore.Entry(f1, 1, 1, 1, 1), tmp)
            val tmp2 = File.createTempFile("tab", ".tmp"); tmp2.writeBytes(byteArrayOf(4))
            ThemeAssetStore.put(dir, "k2", ThemeAssetStore.Entry(f2, 1, 1, 1, 1), tmp2)
            File(dir, "stray.txt").writeText("x")
            assertEquals(setOf("k1", "k2"), ThemeAssetStore.readIndex(dir).keys)
            val plan = ThemeAssetPlan(skins = mapOf("a" to ThemeAssetPlan.Skin(ref("https://example.com/k.png"))))
            assertEquals(1, ThemeAssetStore.missing(plan, ThemeAssetStore.readIndex(dir), dir).size)
            val deleted = ThemeAssetStore.collectGarbage(dir, setOf("k1"))
            assertEquals(listOf(f2, "stray.txt"), deleted)
            assertTrue(File(dir, f1).exists()); assertFalse(File(dir, f2).exists())
            assertEquals(setOf("k1"), ThemeAssetStore.readIndex(dir).keys)
            // Tệp mất ⇒ "thiếu" dù mục lục còn.
            val key = ThemeAssetFile(ThemeAssetClass.key, ref("https://example.com/k.png")).key
            ThemeAssetStore.put(dir, key, ThemeAssetStore.Entry(f1, 1, 1, 1, 1), File.createTempFile("tac", ".tmp"))
            assertTrue(ThemeAssetStore.missing(plan, ThemeAssetStore.readIndex(dir), dir).isEmpty())
            File(dir, f1).delete()
            assertEquals(1, ThemeAssetStore.missing(plan, ThemeAssetStore.readIndex(dir), dir).size)
        } finally { dir.deleteRecursively() }
        assertEquals(emptyMap<String, ThemeAssetStore.Entry>(), ThemeAssetStore.readIndex(null))
    }

    /** Thiếu tệp / mục lục ⇒ bàn phím không có skin / phông (vẽ màu theme, phông mặc định) — không crash. */
    @Test fun locateFallsBackWhenFileMissing() {
        val dir = Files.createTempDirectory("tl").toFile()
        try {
            val r = ref("https://example.com/k.png")
            val key = ThemeAssetFile(ThemeAssetClass.key, r).key
            val f = "${"3".repeat(64)}.key.png"
            val idx = mapOf(key to ThemeAssetStore.Entry(f, 10, 10, 20, 20))
            assertNull(ThemeAssetStore.locate(ThemeAssetClass.key, r, idx, dir))           // tệp mất
            assertNull(ThemeAssetStore.locate(ThemeAssetClass.key, r, emptyMap(), dir))    // chưa tải
            assertNull(ThemeAssetStore.locate(ThemeAssetClass.screen, r, idx, dir))        // khác lớp
            assertNull(ThemeAssetStore.locate(ThemeAssetClass.key, null, idx, dir))
            File(dir, f).writeBytes(byteArrayOf(1))
            assertEquals(20, ThemeAssetStore.locate(ThemeAssetClass.key, r, idx, dir)?.second?.sh)
            assertNull("sha khác ⇒ khoá khác", ThemeAssetStore.locate(ThemeAssetClass.key, ref(r.url, sha), idx, dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun referencedKeysCoverSpecsAndStyles() {
        val spec = ThemeSpecParser.parse(fixture("all")).spec!!
        val st = KeyStyle(assets = ThemeAssetSpec(font = BuiltinFonts.list.first().asset))
        val keys = ThemeAssetStore.referencedKeys(listOf(spec), listOf(st, KeyStyle()))
        assertEquals(7, keys.size)
        assertTrue(keys.contains(ThemeAssetFile(ThemeAssetClass.font, BuiltinFonts.list.first().ref).key))
    }

    private fun fixture(name: String): String = repoFile("iOS/KeyboardTests/Fixtures/theme-json-cases.txt").readLines()
        .map { it.split('\t') }.first { it[0] == "CASE" && it[1] == name }[4]

    // ---- loại tệp / sha / cmap

    @Test fun sniffMagicBytes() {
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() } + ByteArray(16)
        fun a(s: String) = s.toByteArray(Charsets.ISO_8859_1) + ByteArray(16)
        val F = ThemeAssetClass.font; val K = ThemeAssetClass.key
        assertEquals("ttf", ThemeAssetSniff.ext(b(0, 1, 0, 0), F))
        assertEquals("ttf", ThemeAssetSniff.ext(a("true"), F))
        assertEquals("otf", ThemeAssetSniff.ext(a("OTTO"), F))
        assertNull(ThemeAssetSniff.ext(a("ttcf"), F)); assertNull(ThemeAssetSniff.ext(a("wOFF"), F)); assertNull(ThemeAssetSniff.ext(a("wOF2"), F))
        assertNull(ThemeAssetSniff.ext(b(0xFF, 0xD8, 0xFF), F))
        assertEquals("jpg", ThemeAssetSniff.ext(b(0xFF, 0xD8, 0xFF, 0xE0), K))
        assertEquals("png", ThemeAssetSniff.ext(b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), K))
        assertEquals("webp", ThemeAssetSniff.ext(a("RIFF\u0000\u0000\u0000\u0000WEBPVP8 "), K))
        assertEquals("heic", ThemeAssetSniff.ext(a("\u0000\u0000\u0000\u0018ftypheic"), K))
        assertEquals("heic", ThemeAssetSniff.ext(a("\u0000\u0000\u0000\u0018ftypmif1"), ThemeAssetClass.screen))
        assertNull(ThemeAssetSniff.ext(a("\u0000\u0000\u0000\u0018ftypavif"), K))
        assertNull(ThemeAssetSniff.ext(a("GIF89a"), K)); assertNull(ThemeAssetSniff.ext(a("<svg "), K))
        assertNull(ThemeAssetSniff.ext(a("OTTO"), K)); assertNull(ThemeAssetSniff.ext(ByteArray(0), K))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ThemeAssetSniff.sha256Hex(ByteArray(0)))
    }

    /** Phông xem trước (chỉ "V", "t") ⇒ thiếu chữ Việt; cmap đọc được; rác ⇒ null. */
    @Test fun fontCmapCoverage() {
        val dir = repoFile("android/app/src/main/assets/fontpreview")
        val files = dir.listFiles { f -> f.extension == "ttf" }!!.sortedBy { it.name }
        assertEquals(13, files.size)
        for (f in files) {
            val b = f.readBytes()
            assertNotNull(f.name, ThemeAssetSniff.ext(b, ThemeAssetClass.font))
            assertEquals(f.name, emptyList<Int>(), FontCmap.missing(b, "Vt"))
            assertEquals(f.name, FontCmap.VIETNAMESE_SAMPLE.codePoints().toArray().toList(), FontCmap.missing(b, FontCmap.VIETNAMESE_SAMPLE))
        }
        assertNull(FontCmap.missing(ByteArray(40), "a"))
        assertNull(FontCmap.missing(ByteArray(3), "a"))
    }

    // ---- toán giải ảnh + ngân sách RAM

    @Test fun imageDecodeMath() {
        assertNull(ThemeAssetImageMath.checkSource(6000, 6000))
        assertEquals(ThemeAssetError.tooManyPixels, ThemeAssetImageMath.checkSource(7000, 6000))
        assertEquals(ThemeAssetError.tooManyPixels, ThemeAssetImageMath.checkSource(12001, 10))
        assertEquals(ThemeAssetError.decodeFailed, ThemeAssetImageMath.checkSource(0, 10))
        // Không bao giờ giải cả ảnh to: 4000 px → cạnh 1600 ⇒ sample 2 (2000 ≥ 1600, 1000 < 1600).
        assertEquals(2, ThemeAssetImageMath.sampleSize(4000, 3000, 1600))
        assertEquals(8, ThemeAssetImageMath.sampleSize(4096, 4096, 512))
        assertEquals(1, ThemeAssetImageMath.sampleSize(500, 300, 512))
        assertEquals(16, ThemeAssetImageMath.sampleSize(12000, 3000, 512))
        assertEquals(listOf(512, 384), ThemeAssetImageMath.fit(4000, 3000, 512).toList())
        assertEquals(listOf(300, 200), ThemeAssetImageMath.fit(300, 200, 512).toList())
        assertEquals(listOf(1600, 1), ThemeAssetImageMath.fit(12000, 3, 1600).toList())
        assertEquals(1600, ThemeAssetLimits.screenEdge(2400)); assertEquals(1280, ThemeAssetLimits.screenEdge(1280))
        assertEquals(64, ThemeAssetLimits.screenEdge(10))
        assertEquals(512, ThemeAssetLimits.keyDecodeEdge(setOf("space")))
        assertEquals(192, ThemeAssetLimits.keyDecodeEdge(setOf("a", "default")))
        assertEquals(2, ThemeAssetImageMath.coverSample(1600, 1200, 700, 500))
        assertEquals(1, ThemeAssetImageMath.coverSample(1600, 1200, 1080, 800))
        assertEquals(listOf(0, 150, 1600, 900), ThemeAssetImageMath.fillCrop(1600, 1200, 1600, 900).toList())
        assertEquals(listOf(400, 0, 800, 1200), ThemeAssetImageMath.fillCrop(1600, 1200, 400, 600).toList())
        // Ảnh nền: chỉ vùng thấy, ≤ khung; tiền cảnh (căng): ≤ khung và ≤ ảnh lưu.
        assertEquals(listOf(1080, 800), ThemeAssetImageMath.screenDecodeSize(1600, 1200, 1080, 800, fill = true).toList())
        assertEquals(listOf(1600, 688), ThemeAssetImageMath.screenDecodeSize(1600, 1200, 2560, 1100, fill = true).toList())
        assertEquals(listOf(1080, 800), ThemeAssetImageMath.screenDecodeSize(1600, 1200, 1080, 800, fill = false).toList())
        assertEquals(listOf(1600, 1100), ThemeAssetImageMath.screenDecodeSize(1600, 1200, 2560, 1100, fill = false).toList())
    }

    /**
     * Ngân sách RAM bàn phím khi theme có đủ: 16 skin (2 phím rộng giải 512, còn lại 192) + ảnh nền + tiền cảnh
     * (giải theo cỡ bàn phím, ảnh lưu ≤ 1600) < 12 MB.
     */
    @Test fun decodeMemoryBudget() {
        fun budget(viewW: Int, viewH: Int, storedW: Int, storedH: Int): Long {
            var n = 0L
            for (i in 0 until 16) {
                val e = ThemeAssetLimits.keyDecodeEdge(if (i < 2) setOf("space") else setOf("a"))
                val (w, h) = ThemeAssetImageMath.fit(ThemeAssetLimits.KEY_MAX_EDGE, ThemeAssetLimits.KEY_MAX_EDGE, e).let { it[0] to it[1] }
                n += ThemeAssetImageMath.bytes(w, h)
            }
            for (fill in listOf(true, false)) {
                val (sw, sh) = ThemeAssetImageMath.screenDecodeSize(storedW, storedH, viewW, viewH, fill).let { it[0] to it[1] }
                n += ThemeAssetImageMath.bytes(sw, sh)
            }
            return n
        }
        val cap = 12L * 1024 * 1024
        // Điện thoại dọc 1080×(phím 825 + strip) — ảnh nền đã lưu 1600×1200.
        val phone = budget(1080, 950, 1600, 1200)
        assertTrue("điện thoại $phone", phone < cap)
        // Điện thoại ngang 2400×700 — ảnh lưu ≤ 1600 ⇒ bitmap ≤ ảnh lưu.
        val land = budget(2400, 700, 1600, 900)
        assertTrue("ngang $land", land < cap)
        println("theme asset RAM: phone=${phone / 1024} KB, landscape=${land / 1024} KB")
    }

    // ---- câu hỏi trước khi tải

    @Test fun promptText() {
        val nunito = BuiltinFonts.list.first { it.id == "nunito" }
        val font = ThemeAssetFile(ThemeAssetClass.font, nunito.ref)
        assertEquals("Giao diện này cần tải 1 tệp (≈ 45 KB) từ viettelex.com. Tải?", ThemeAssetPrompt.text(listOf(font)))
        val bg = ThemeAssetFile(ThemeAssetClass.screen, ref("https://example.com/bg.jpg"))
        val key = ThemeAssetFile(ThemeAssetClass.key, ref("https://cdn.example.org/k.png"))
        assertEquals("Giao diện này cần tải 3 tệp (tối đa 12 MB) từ cdn.example.org, example.com, viettelex.com. Tải?",
            ThemeAssetPrompt.text(listOf(font, bg, key)))
        // Phông ngoài danh sách có sẵn: chưa biết cỡ ⇒ trần 2 MB.
        assertEquals("Giao diện này cần tải 1 tệp (tối đa 2 MB) từ example.com. Tải?",
            ThemeAssetPrompt.text(listOf(ThemeAssetFile(ThemeAssetClass.font, ref("https://example.com/a.ttf")))))
        assertNull(ThemeAssetPrompt.text(emptyList()))
        // Cùng ca iOS testPromptText: 2 skin + 1 ảnh nền ⇒ 15 MB, tên miền sắp xếp.
        assertEquals("Giao diện này cần tải 3 tệp (tối đa 15 MB) từ a.com, b.com. Tải?", ThemeAssetPrompt.text(listOf(
            ThemeAssetFile(ThemeAssetClass.key, ref("https://b.com/a.png")), ThemeAssetFile(ThemeAssetClass.screen, ref("https://a.com/b.jpg")),
            ThemeAssetFile(ThemeAssetClass.key, ref("https://b.com/c.png")))))
        L10n.lang = L10n.EN
        assertEquals("This theme needs to download 1 files (≈ 45 KB) from viettelex.com. Download?", ThemeAssetPrompt.text(listOf(font)))
        assertEquals("This theme needs to download 1 files (up to 5 MB) from example.com. Download?", ThemeAssetPrompt.text(listOf(bg)))
    }

    // ---- gộp phông / chuỗi chuẩn

    @Test fun effectiveStyleFontMergeRules() {
        val themeFont = ThemeFontAsset("T", ref("https://example.com/t.ttf"))
        val userFont = ThemeFontAsset("U", ref("https://example.com/u.ttf"))
        val skins = mapOf("default" to ThemeKeySkin(ref("https://example.com/k.png")))
        val spec = ThemeSpecParser.parse(fixture("a")).spec!!.copy(id = "mine",
            assets = ThemeAssetSpec(font = themeFont, keys = skins, background = ref("https://example.com/bg.jpg")))
        fun eff(st: KeyStyle?) = ThemeSettings(keyStyles = st?.let { mapOf("mine" to it) } ?: emptyMap())
            .effectiveStyleFor("mine", spec.typography)
        // Chưa chỉnh ⇒ phông + skin + nền của theme.
        assertEquals(themeFont, eff(null).assets.font); assertEquals(skins, eff(null).assets.keys)
        // Người dùng chọn phông HỆ THỐNG ⇒ thắng phông tải về của theme.
        val sys = eff(KeyStyle(font = KeyLabelFont.SERIF))
        assertNull(sys.assets.font); assertEquals(KeyLabelFont.SERIF, sys.font)
        assertEquals(skins, sys.assets.keys)
        // Người dùng chọn phông TẢI VỀ ⇒ thắng phông theme (cả phông hệ thống của theme).
        val up = eff(KeyStyle(assets = ThemeAssetSpec(font = userFont)))
        assertEquals(userFont, up.assets.font); assertEquals(KeyLabelFont.DEFAULT, up.font)
        val specSerif = spec.copy(font = KeyLabelFont.SERIF, assets = ThemeAssetSpec())
        val up2 = ThemeSettings(keyStyles = mapOf("mine" to KeyStyle(assets = ThemeAssetSpec(font = userFont))))
            .effectiveStyleFor("mine", specSerif.typography)
        assertEquals(userFont, up2.assets.font); assertEquals(KeyLabelFont.DEFAULT, up2.font)
        // Bộ skin: có bộ thì thay CẢ bộ.
        val mySkins = mapOf("a" to ThemeKeySkin(ref("https://example.com/a.png")))
        assertEquals(mySkins, eff(KeyStyle(assets = ThemeAssetSpec(keys = mySkins))).assets.keys)
        assertTrue(eff(KeyStyle(assets = ThemeAssetSpec(font = userFont))).customizesLabels)
        // Hết Plus ⇒ bản vá bỏ qua, theme vẫn có tệp của nó.
        PlusGate.paywallEnabled = true; PlusGate.install({ null }, debugBuild = false)
        try { assertEquals(themeFont, eff(KeyStyle(assets = ThemeAssetSpec(font = userFont))).assets.font) }
        finally { PlusGate.paywallEnabled = false; PlusGate.install({ null }, debugBuild = false) }
    }

    @Test fun overrideWithAssetsRoundTripsAndExports() {
        val st = KeyStyle(accentColor = 0xFF9500, assets = ThemeAssetSpec(font = ThemeFontAsset("N", ref("https://viettelex.com/fonts/N.ttf", sha)),
            foreground = ref("https://example.com/fg.png"),
            keys = mapOf("space" to ThemeKeySkin(ref("https://example.com/sp.png"), listOf(0, 40, 0, 40), ref("https://example.com/spp.png")))))
        val text = KeyStyleStore.serialize(mapOf("mint" to st))
        assertEquals("""{"mint":{"accent":"#FF9500","font":{"name":"N","sha256":"$sha","url":"https://viettelex.com/fonts/N.ttf"},""" +
            """"foreground":{"url":"https://example.com/fg.png"},"keys":{"space":{"pressed":{"url":"https://example.com/spp.png"},""" +
            """"slice":[0,40,0,40],"url":"https://example.com/sp.png"}}}}""", text)
        assertEquals(st, KeyStyleStore.parse(text)["mint"])
        // Bản vá có URL sai ⇒ bỏ cả mục.
        assertNull(KeyStyleStore.parse("""{"mint":{"background":{"url":"http://x.com/a.png"}}}""")["mint"])
        // "Chép JSON" mang tệp của bản vá; sửa lại ⇒ bản vá giữ tệp.
        val s = ThemeSettings(theme = KeyboardTheme.MINT).withKeyStyle(st)
        val exported = s.exportSpec(KeyboardTheme.MINT, KeyboardTheme.MINT.palette(false)!!, KeyboardTheme.MINT.palette(true)!!)
        assertEquals(st.assets, exported.assets)
        assertNull(exported.font)
        val back = ThemeSpecParser.parse(exported.json.pretty).spec!!
        assertEquals(exported.canonical, back.canonical)
        val (edited, _) = KeyStyle.fromEdited(back, KeyboardTheme.MINT.spec!!.light, KeyboardTheme.MINT.spec!!.slots(true))
        assertEquals(st.assets, edited.assets)
    }

    // ---- phông có sẵn

    @Test fun builtinFontsSourceMatchesEmbeddedAndParses() {
        val src = repoFile("themes/builtin-fonts.json").readText().trim()
        assertEquals("chạy python3 Scripts/gen-builtin-themes.py", src, BuiltinFontsData.JSON.trim())
        assertEquals(13, BuiltinFonts.list.size)
        for (e in BuiltinFonts.list) {
            assertTrue(e.id, ThemeAssetURL.isValid(e.ref.url, font = true))
            assertNotNull(e.ref.sha256)
            assertTrue(e.id, e.bytes in 1..ThemeAssetLimits.FONT_MAX_BYTES)
            assertTrue(e.previewAsset, repoFile("android/app/src/main/assets/" + e.previewAsset).exists())
            assertEquals(e.bytes, BuiltinFonts.knownBytes(e.ref))
        }
        assertNull(BuiltinFonts.knownBytes(ref("https://example.com/a.ttf")))
        assertEquals(emptyList<BuiltinFonts.Entry>(), BuiltinFonts.parse("""{"fonts":[{"id":"x","name":"x","url":"http://a.com/a.ttf","sha256":"$sha","bytes":1}]}"""))
    }
}
