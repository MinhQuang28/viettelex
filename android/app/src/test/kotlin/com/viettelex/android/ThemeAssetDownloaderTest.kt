package com.viettelex.android

import com.viettelex.android.ime.KeyKind
import com.viettelex.android.ime.ThemeKeyRole
import com.viettelex.android.ui.ThemeAssetDownloader
import com.viettelex.android.ui.ThemeAssetFetcher
import com.viettelex.android.ui.ThemeAssetProcessor
import com.viettelex.android.ui.ThemeAssetResult
import com.viettelex.android.ui.ThemeAssetTransport
import com.viettelex.keyboard.ThemeAssetClass
import com.viettelex.keyboard.ThemeAssetError
import com.viettelex.keyboard.ThemeAssetFile
import com.viettelex.keyboard.ThemeAssetLimits
import com.viettelex.keyboard.ThemeAssetPlan
import com.viettelex.keyboard.ThemeAssetRef
import com.viettelex.keyboard.ThemeAssetSniff
import com.viettelex.keyboard.ThemeAssetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files

/**
 * Tải tệp của theme bằng transport GIẢ (không mạng): trần cỡ (Content-Length lẫn đọc thật, không đọc hết), sai
 * loại, sha256 lệch, chuyển hướng (≤ 3, chỉ https), hết giờ, 404, mất mạng; lưu nguyên tử vào kho + mục lục; lỗi
 * một tệp không chặn tệp khác. Cộng: vai phím (skin) và chốt "bàn phím không bao giờ dùng mạng".
 */
class ThemeAssetDownloaderTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100) { it.toByte() }
    private val ttf = byteArrayOf(0, 1, 0, 0) + ByteArray(60)

    /** Phản hồi theo URL; ghi lại các URL đã gọi. */
    private class FakeTransport(val routes: Map<String, () -> ThemeAssetTransport.Response>) : ThemeAssetTransport {
        val calls = ArrayList<String>()
        override fun open(url: String): ThemeAssetTransport.Response {
            calls.add(url)
            return (routes[url] ?: { ThemeAssetTransport.Response(404, null, -1, ByteArrayInputStream(ByteArray(0))) })()
        }
    }

    private fun ok(b: ByteArray, len: Long = b.size.toLong()) = { ThemeAssetTransport.Response(200, null, len, ByteArrayInputStream(b)) }
    private fun redirect(to: String, code: Int = 302) = { ThemeAssetTransport.Response(code, to, 0, ByteArrayInputStream(ByteArray(0))) }

    /** Luồng vô tận (không Content-Length) — đếm byte đã đọc. */
    private class Endless : InputStream() {
        var read = 0L
        override fun read(): Int { read++; return 0 }
        override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
    }

    private fun key(u: String, sha: String? = null) = ThemeAssetFile(ThemeAssetClass.key, ThemeAssetRef(u, sha))
    private fun font(u: String, sha: String? = null) = ThemeAssetFile(ThemeAssetClass.font, ThemeAssetRef(u, sha))

    private fun err(t: ThemeAssetTransport, f: ThemeAssetFile): ThemeAssetError? =
        (ThemeAssetFetcher(t).fetchSafely(f) as? ThemeAssetFetcher.Out.Err)?.error

    @Test fun okWithShaAndSniff() {
        val u = "https://example.com/k.png"
        val sha = ThemeAssetSniff.sha256Hex(png)
        val r = ThemeAssetFetcher(FakeTransport(mapOf(u to ok(png)))).fetchSafely(key(u, sha))
        assertTrue(r is ThemeAssetFetcher.Out.Ok)
        assertEquals("png", (r as ThemeAssetFetcher.Out.Ok).f.ext)
        assertEquals(sha, r.f.sha256)
    }

    @Test fun sizeCaps() {
        val u = "https://example.com/k.png"
        // Content-Length vượt trần ⇒ từ chối trước khi đọc.
        assertEquals(ThemeAssetError.tooLarge, err(FakeTransport(mapOf(u to ok(png, ThemeAssetLimits.IMAGE_MAX_BYTES + 1L))), key(u)))
        // Không Content-Length, luồng vô tận ⇒ dừng ngay sau trần (không đọc hết).
        val endless = Endless()
        val t = FakeTransport(mapOf(u to { ThemeAssetTransport.Response(200, null, -1, endless) }))
        assertEquals(ThemeAssetError.tooLarge, err(t, key(u)))
        assertTrue("đọc ${endless.read}", endless.read <= ThemeAssetLimits.IMAGE_MAX_BYTES + 64 * 1024)
        // Phông: trần 2 MB (Content-Length nói dối nhỏ hơn thật cũng bị chặn khi đọc).
        val big = ttf + ByteArray(ThemeAssetLimits.FONT_MAX_BYTES)
        assertEquals(ThemeAssetError.tooLarge, err(FakeTransport(mapOf("https://example.com/a.ttf" to ok(big, 10))), font("https://example.com/a.ttf")))
        // Đúng trần ⇒ được.
        val exact = ttf + ByteArray(ThemeAssetLimits.FONT_MAX_BYTES - ttf.size)
        assertTrue(ThemeAssetFetcher(FakeTransport(mapOf("https://example.com/a.ttf" to ok(exact)))).fetchSafely(font("https://example.com/a.ttf"))
            is ThemeAssetFetcher.Out.Ok)
    }

    @Test fun wrongTypeAndShaMismatch() {
        val u = "https://example.com/k.png"
        assertEquals(ThemeAssetError.wrongType, err(FakeTransport(mapOf(u to ok("GIF89a......".toByteArray()))), key(u)))
        assertEquals(ThemeAssetError.wrongType, err(FakeTransport(mapOf(u to ok(png))), font(u)))
        assertEquals(ThemeAssetError.wrongType, err(FakeTransport(mapOf(u to ok("wOF2xxxxxxxx".toByteArray()))), font(u)))
        assertEquals(ThemeAssetError.shaMismatch, err(FakeTransport(mapOf(u to ok(png))), key(u, "0".repeat(64))))
    }

    @Test fun redirects() {
        val a = "https://example.com/a.png"; val b = "https://cdn.example.com/b.png"
        // Tương đối + tuyệt đối https ⇒ theo.
        val t1 = FakeTransport(mapOf(a to redirect("/r1.png"), "https://example.com/r1.png" to redirect(b, 301), b to ok(png)))
        assertTrue(ThemeAssetFetcher(t1).fetchSafely(key(a)) is ThemeAssetFetcher.Out.Ok)
        assertEquals(listOf(a, "https://example.com/r1.png", b), t1.calls)
        // Sang http ⇒ từ chối, KHÔNG gọi URL http.
        val t2 = FakeTransport(mapOf(a to redirect("http://example.com/a.png")))
        assertEquals(ThemeAssetError.redirectInsecure, err(t2, key(a)))
        assertEquals(listOf(a), t2.calls)
        assertEquals(ThemeAssetError.redirectInsecure, err(FakeTransport(mapOf(a to redirect("file:///etc/passwd"))), key(a)))
        // > 3 lần ⇒ dừng.
        val loop = FakeTransport(mapOf(a to redirect(b), b to redirect(a)))
        assertEquals(ThemeAssetError.httpStatus, err(loop, key(a)))
        assertEquals(4, loop.calls.size)
        // 3xx thiếu Location ⇒ lỗi máy chủ.
        assertEquals(ThemeAssetError.httpStatus, err(FakeTransport(mapOf(a to { ThemeAssetTransport.Response(302, null, 0, ByteArrayInputStream(ByteArray(0))) })), key(a)))
    }

    @Test fun networkFailures() {
        val u = "https://example.com/k.png"
        assertEquals(ThemeAssetError.httpStatus, err(FakeTransport(emptyMap()), key(u)))            // 404
        assertEquals(ThemeAssetError.httpStatus, err(FakeTransport(mapOf(u to { ThemeAssetTransport.Response(500, null, 0, ByteArrayInputStream(ByteArray(0))) })), key(u)))
        val timeout = object : ThemeAssetTransport { override fun open(url: String): ThemeAssetTransport.Response = throw SocketTimeoutException() }
        assertEquals(ThemeAssetError.timeout, err(timeout, key(u)))
        val offline = object : ThemeAssetTransport { override fun open(url: String): ThemeAssetTransport.Response = throw UnknownHostException(url) }
        assertEquals(ThemeAssetError.offline, err(offline, key(u)))
        // Hết giờ GIỮA lúc đọc.
        val slow = object : InputStream() {
            var n = 0
            override fun read(): Int = throw SocketTimeoutException()
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (n++ == 0) { System.arraycopy(png, 0, b, off, 8); return 8 }; throw SocketTimeoutException() }
        }
        assertEquals(ThemeAssetError.timeout, err(FakeTransport(mapOf(u to { ThemeAssetTransport.Response(200, null, -1, slow) })), key(u)))
        // http:// ngay từ đầu ⇒ không gọi mạng.
        val t = FakeTransport(emptyMap())
        assertEquals(ThemeAssetError.notHttps, err(t, key("http://example.com/k.png")))
        assertTrue(t.calls.isEmpty())
        assertFalse(ThemeAssetFetcher.isHttps("https://user@example.com/a.png"))
        assertTrue(ThemeAssetFetcher.isHttps("HTTPS://example.com/a.png"))
    }

    /** Tải thật vào kho (processor giả): tệp + mục lục; lỗi một tệp không chặn tệp khác; tiền cảnh được đánh dấu. */
    @Test fun downloaderStoresAtomicallyAndContinuesAfterFailure() {
        val dir = Files.createTempDirectory("ta-store").toFile()
        val tmp = Files.createTempDirectory("ta-tmp").toFile()
        try {
            val good = "https://example.com/k.png"; val bad = "https://example.com/missing.png"; val fg = "https://example.com/fg.png"
            val seenFg = HashMap<String, Boolean>()
            val proc = object : ThemeAssetProcessor {
                override fun process(file: ThemeAssetFile, bytes: ByteArray, ext: String, foreground: Boolean): ThemeAssetProcessor.Result {
                    seenFg[file.ref.url] = foreground
                    return ThemeAssetProcessor.Result.Ok(ThemeAssetProcessor.Out(bytes, "png", 10, 12, 100, 120,
                        if (foreground) ThemeAssetStore.WARN_FOREGROUND_COVERAGE else null))
                }
            }
            val files = listOf(key(bad), key(good), ThemeAssetFile(ThemeAssetClass.screen, ThemeAssetRef(fg)))
            val r = ThemeAssetDownloader(dir, tmp, FakeTransport(mapOf(good to ok(png), fg to ok(png))), proc).download(files, setOf(fg))
            assertEquals(ThemeAssetError.httpStatus, (r[key(bad).key] as ThemeAssetResult.Failed).error)
            assertEquals(ThemeAssetResult.Ok(null), r[key(good).key])
            assertEquals(ThemeAssetResult.Ok(ThemeAssetStore.WARN_FOREGROUND_COVERAGE), r[files[2].key])
            assertEquals(mapOf(good to false, fg to true), seenFg)
            val idx = ThemeAssetStore.readIndex(dir)
            assertEquals(setOf(key(good).key, files[2].key), idx.keys)
            val e = idx.getValue(key(good).key)
            assertEquals(ThemeAssetStore.fileName(ThemeAssetSniff.sha256Hex(png), ThemeAssetClass.key, "png"), e.file)
            assertEquals(120, e.sh)
            assertTrue(File(dir, e.file).readBytes().contentEquals(png))
            assertTrue("không còn tệp tạm", tmp.listFiles()!!.isEmpty())
            // Sau khi tải, kế hoạch không còn thiếu tệp đó.
            val plan = ThemeAssetPlan(skins = mapOf("a" to ThemeAssetPlan.Skin(ThemeAssetRef(good))))
            assertTrue(ThemeAssetStore.missing(plan, idx, dir).isEmpty())
            // Bộ xử lý báo lỗi (phông hỏng) ⇒ không ghi gì.
            val badFont = object : ThemeAssetProcessor {
                override fun process(file: ThemeAssetFile, bytes: ByteArray, ext: String, foreground: Boolean) =
                    ThemeAssetProcessor.Result.Err(ThemeAssetError.fontInvalid)
            }
            val fu = "https://example.com/a.ttf"
            val r2 = ThemeAssetDownloader(dir, tmp, FakeTransport(mapOf(fu to ok(ttf))), badFont).download(listOf(font(fu)), emptySet())
            assertEquals(ThemeAssetError.fontInvalid, (r2.values.single() as ThemeAssetResult.Failed).error)
            assertEquals(2, ThemeAssetStore.readIndex(dir).size)
        } finally { dir.deleteRecursively(); tmp.deleteRecursively() }
    }

    // ---- vai phím (skin)

    @Test fun keyRoles() {
        assertEquals(ThemeKeyRole.LETTER_A, ThemeKeyRole.index(KeyKind.LETTER, "a"))
        assertEquals(ThemeKeyRole.LETTER_A + 25, ThemeKeyRole.index(KeyKind.LETTER, "z"))
        assertEquals("z", ThemeAssetPlan.ROLES[ThemeKeyRole.index(KeyKind.LETTER, "z")])
        assertEquals(ThemeKeyRole.DEFAULT, ThemeKeyRole.index(KeyKind.LETTER, "đ"))
        assertEquals(ThemeKeyRole.DEFAULT, ThemeKeyRole.index(KeyKind.CHAR, "1"))
        assertEquals(ThemeKeyRole.DEFAULT, ThemeKeyRole.index(KeyKind.PUNCT, ","))
        assertEquals(ThemeKeyRole.DEFAULT, ThemeKeyRole.index(KeyKind.PAD, "5", side = false))
        assertEquals(ThemeKeyRole.SPECIAL, ThemeKeyRole.index(KeyKind.PAD, "+", side = true))
        for ((k, role) in listOf(KeyKind.SPACE to "space", KeyKind.RETURN to "return", KeyKind.SHIFT to "shift", KeyKind.BACKSPACE to "delete",
            KeyKind.PLANE to "special", KeyKind.GLOBE to "special", KeyKind.EMOJI to "special", KeyKind.MORE to "special"))
            assertEquals(role, ThemeAssetPlan.ROLES[ThemeKeyRole.index(k, "x")])
    }

    // ---- bàn phím không bao giờ dùng mạng

    @Test fun imeNeverReferencesNetwork() {
        val ime = listOf("src/main/kotlin/com/viettelex/android/ime", "app/src/main/kotlin/com/viettelex/android/ime")
            .map { File(it) }.first { it.isDirectory }
        // Cả gói ime/: không chạm bộ tải của app.
        val banned = listOf("ThemeAssetDownloader", "ThemeAssetTransport", "ThemeAssetFetcher", "ThemeAssetFlow", "ui.ThemeAsset")
        // Mã vẽ theme / tệp tải về: không lớp mạng nào (mẫu câu động ở VietTelexIME là tính năng riêng, ngoài phạm vi).
        val themeFiles = setOf("ThemeAssetLoader.kt", "ImeTheme.kt", "ImeRootView.kt", "KeyboardView.kt", "BalloonView.kt")
        val net = listOf("HttpURLConnection", "HttpsURLConnection", "java.net.", "openConnection", "Socket")
        val hits = ime.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { l -> banned.any { it in l } || (f.name in themeFiles && net.any { it in l }) }
                .map { "${f.name}: ${it.trim()}" }
        }.toList()
        assertTrue("IME không được tải mạng:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
