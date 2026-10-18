package com.viettelex.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import com.viettelex.keyboard.FontCmap
import com.viettelex.keyboard.ThemeAssetClass
import com.viettelex.keyboard.ThemeAssetError
import com.viettelex.keyboard.ThemeAssetFile
import com.viettelex.keyboard.ThemeAssetImageMath
import com.viettelex.keyboard.ThemeAssetLimits
import com.viettelex.keyboard.ThemeAssetSniff
import com.viettelex.keyboard.ThemeAssetStore
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/*
 * Tải tệp của theme (phông / ảnh nền / tiền cảnh / skin phím) — CHỈ app (màn cài đặt) gọi, sau khi người dùng đồng ý
 * (ThemeAssetFlow). Bàn phím (ime/) không bao giờ tham chiếu lớp này (test IME_NEVER_NETWORKS khoá).
 * An toàn: chỉ https; tự theo chuyển hướng ≤ 3 lần, chỉ sang https; chờ 20 s; trần cỡ (phông 2 MB, ảnh 5 MB) theo
 * Content-Length LẪN số byte đọc thật; không cookie / cache; sha256 (nếu có) phải khớp; loại tệp theo "số ma thuật";
 * ảnh đọc kích thước TRƯỚC khi giải (≤ 40 MP, cạnh ≤ 12000), giải có lấy mẫu rồi thu nhỏ, lưu lại JPEG/PNG không
 * metadata; phông kiểm bằng Typeface + bảng cmap (thiếu chữ Việt ⇒ cảnh báo). Lỗi ⇒ theme vẫn dùng màu / phông mặc định.
 */

/** Lớp mạng (thay bằng bản giả trong test). */
interface ThemeAssetTransport {
    class Response(val status: Int, val location: String?, val contentLength: Long, val body: InputStream,
                   private val onClose: () -> Unit = {}) : Closeable {
        override fun close() { runCatching { body.close() }; onClose() }
    }

    /** Một yêu cầu GET, KHÔNG tự theo chuyển hướng. Lỗi mạng ⇒ IOException (SocketTimeoutException = hết giờ). */
    @Throws(IOException::class)
    fun open(url: String): Response
}

/** HttpURLConnection thật: không tự chuyển hướng, không cache, không cookie (không đặt CookieHandler), chờ 20 s. */
class HttpThemeAssetTransport : ThemeAssetTransport {
    override fun open(url: String): ThemeAssetTransport.Response {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.connectTimeout = ThemeAssetLimits.TIMEOUT_MS
        c.readTimeout = ThemeAssetLimits.TIMEOUT_MS
        c.setRequestProperty("Accept-Encoding", "identity")   // Content-Length = cỡ thật
        c.setRequestProperty("User-Agent", "VietTelex")
        try {
            val code = c.responseCode
            val body = if (code in 200..299) c.inputStream else java.io.ByteArrayInputStream(ByteArray(0))
            if (code !in 200..299) runCatching { c.errorStream?.close() }
            return ThemeAssetTransport.Response(code, c.getHeaderField("Location"), c.contentLengthLong, body) { c.disconnect() }
        } catch (e: IOException) { c.disconnect(); throw e }
    }
}

/** Kết quả từng tệp (UI hiện). */
sealed class ThemeAssetResult {
    data class Ok(val warn: String?) : ThemeAssetResult()
    data class Failed(val error: ThemeAssetError, val detail: String = "") : ThemeAssetResult()
}

/** Bước tải + kiểm (thuần JVM — test bằng transport giả). */
class ThemeAssetFetcher(private val transport: ThemeAssetTransport) {
    class Fetched(val bytes: ByteArray, val sha256: String, val ext: String)

    sealed class Out {
        class Ok(val f: Fetched) : Out()
        class Err(val error: ThemeAssetError, val detail: String = "") : Out()
    }

    fun fetch(file: ThemeAssetFile): Out {
        val cap = ThemeAssetLimits.maxBytes(file.cls)
        var url = file.ref.url
        if (!isHttps(url)) return Out.Err(ThemeAssetError.notHttps)
        var redirects = 0
        while (true) {
            val r = try { transport.open(url) }
                catch (e: SocketTimeoutException) { return Out.Err(ThemeAssetError.timeout) }
                catch (e: InterruptedIOException) { return Out.Err(ThemeAssetError.timeout) }
                catch (e: IOException) { return Out.Err(ThemeAssetError.offline, e.javaClass.simpleName) }
                catch (e: RuntimeException) { return Out.Err(ThemeAssetError.offline, e.javaClass.simpleName) }
            val bytes: ByteArray = r.use { resp ->
                if (resp.status in 300..399) {
                    val next = resp.location?.let { resolve(url, it) }
                        ?: return Out.Err(ThemeAssetError.httpStatus, resp.status.toString())
                    if (!isHttps(next)) return Out.Err(ThemeAssetError.redirectInsecure)
                    if (++redirects > ThemeAssetLimits.MAX_REDIRECTS) return Out.Err(ThemeAssetError.httpStatus, "redirects")
                    url = next
                    null
                } else if (resp.status != 200) return Out.Err(ThemeAssetError.httpStatus, resp.status.toString())
                else if (resp.contentLength > cap) return Out.Err(ThemeAssetError.tooLarge)
                else when (val b = readCapped(resp.body, cap)) {
                    null -> return Out.Err(ThemeAssetError.tooLarge)
                    else -> b
                }
            } ?: continue
            val sha = ThemeAssetSniff.sha256Hex(bytes)
            if (file.ref.sha256 != null && file.ref.sha256 != sha) return Out.Err(ThemeAssetError.shaMismatch)
            val ext = ThemeAssetSniff.ext(bytes, file.cls) ?: return Out.Err(ThemeAssetError.wrongType)
            return Out.Ok(Fetched(bytes, sha, ext))
        }
    }

    /** Đọc tối đa [cap] byte; vượt ⇒ null (dừng ngay, không đọc hết). Hết giờ giữa chừng ⇒ ném ra (bắt ở [readOrTimeout]). */
    private fun readCapped(input: InputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(cap, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = try { input.read(buf) } catch (e: SocketTimeoutException) { throw ReadTimeout() }
                catch (e: InterruptedIOException) { throw ReadTimeout() }
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private class ReadTimeout : RuntimeException()

    /** Như [fetch] nhưng hết giờ / mất mạng GIỮA lúc đọc ⇒ lỗi tương ứng (không ném). */
    fun fetchSafely(file: ThemeAssetFile): Out = try { fetch(file) }
        catch (e: ReadTimeout) { Out.Err(ThemeAssetError.timeout) }
        catch (e: IOException) { Out.Err(ThemeAssetError.offline, e.javaClass.simpleName) }

    companion object {
        fun isHttps(s: String): Boolean = runCatching {
            val u = URI(s)
            u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty() && u.rawUserInfo == null
        }.getOrDefault(false)

        /** Location (tuyệt đối / tương đối) theo URL hiện tại; hỏng ⇒ null. */
        fun resolve(base: String, location: String): String? = runCatching { URI(base).resolve(location.trim()).toString() }.getOrNull()
    }
}

/** Xử lý sau khi tải (Android: Typeface / BitmapFactory — test JVM thay bằng bản giả). */
interface ThemeAssetProcessor {
    /** Tệp đã xử lý: [bytes] lưu xuống đĩa, đuôi jpg/png/ttf/otf, cỡ lưu w×h, cỡ gốc sw×sh, cảnh báo. */
    class Out(val bytes: ByteArray, val ext: String, val w: Int, val h: Int, val sw: Int, val sh: Int, val warn: String?)

    sealed class Result {
        class Ok(val out: Out) : Result()
        class Err(val error: ThemeAssetError) : Result()
    }

    fun process(file: ThemeAssetFile, bytes: ByteArray, ext: String, foreground: Boolean): Result
}

/** Bộ xử lý thật. [screenLongSidePx] = cạnh dài màn hình (ảnh nền / tiền cảnh lưu ≤ min(1600, cạnh này)). */
class AndroidThemeAssetProcessor(private val screenLongSidePx: Int, private val tmpDir: File) : ThemeAssetProcessor {
    override fun process(file: ThemeAssetFile, bytes: ByteArray, ext: String, foreground: Boolean): ThemeAssetProcessor.Result =
        if (file.cls == ThemeAssetClass.font) font(bytes, ext) else image(file.cls, bytes, foreground)

    private fun font(bytes: ByteArray, ext: String): ThemeAssetProcessor.Result {
        val tmp = File.createTempFile("font", ".$ext", tmpDir)
        try {
            tmp.writeBytes(bytes)
            runCatching { Typeface.Builder(tmp).build() }.getOrNull() ?: return ThemeAssetProcessor.Result.Err(ThemeAssetError.fontInvalid)
        } finally { tmp.delete() }
        // Bảng cmap của CHÍNH phông (Paint.hasGlyph tính cả phông dự phòng của hệ thống).
        val missing = FontCmap.missing(bytes, FontCmap.VIETNAMESE_SAMPLE)
        val warn = if (missing == null || missing.isNotEmpty()) ThemeAssetStore.WARN_MISSING_VIETNAMESE else null
        return ThemeAssetProcessor.Result.Ok(ThemeAssetProcessor.Out(bytes, ext, 0, 0, 0, 0, warn))
    }

    private fun image(cls: ThemeAssetClass, bytes: ByteArray, foreground: Boolean): ThemeAssetProcessor.Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth; val h = bounds.outHeight
        ThemeAssetImageMath.checkSource(w, h)?.let { return ThemeAssetProcessor.Result.Err(it) }
        val edge = if (cls == ThemeAssetClass.key) ThemeAssetLimits.KEY_MAX_EDGE else ThemeAssetLimits.screenEdge(screenLongSidePx)
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = ThemeAssetImageMath.sampleSize(w, h, edge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull() ?: return ThemeAssetProcessor.Result.Err(ThemeAssetError.decodeFailed)
        val t = ThemeAssetImageMath.fit(w, h, edge)
        val img = if (decoded.width == t[0] && decoded.height == t[1]) decoded
            else Bitmap.createScaledBitmap(decoded, t[0], t[1], true).also { if (it !== decoded) decoded.recycle() }
        try {
            var warn: String? = null
            if (foreground) {
                val row = IntArray(img.width)
                var covered = 0L
                for (y in 0 until img.height) {
                    img.getPixels(row, 0, img.width, 0, y, img.width, 1)
                    for (px in row) if ((px ushr 24) >= 128) covered++
                }
                if (covered.toDouble() / (img.width.toLong() * img.height) > ThemeAssetLimits.FOREGROUND_COVERAGE_WARN)
                    warn = ThemeAssetStore.WARN_FOREGROUND_COVERAGE
            }
            // Tiền cảnh luôn PNG (giữ alpha); ảnh không alpha ⇒ JPEG q85. Mã hoá lại = bỏ metadata (EXIF, GPS…).
            val png = foreground || img.hasAlpha()
            val out = ByteArrayOutputStream()
            if (!img.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 85, out))
                return ThemeAssetProcessor.Result.Err(ThemeAssetError.decodeFailed)
            return ThemeAssetProcessor.Result.Ok(ThemeAssetProcessor.Out(out.toByteArray(), if (png) "png" else "jpg",
                img.width, img.height, w, h, warn))
        } finally { img.recycle() }
    }
}

/** Tải → kiểm → xử lý → lưu nguyên tử vào kho + mục lục. Từng tệp độc lập: lỗi một tệp không chặn tệp khác. */
class ThemeAssetDownloader(
    private val dir: File,
    private val tmpDir: File,
    transport: ThemeAssetTransport,
    private val processor: ThemeAssetProcessor,
) {
    private val fetcher = ThemeAssetFetcher(transport)

    fun download(files: List<ThemeAssetFile>, foregroundUrls: Set<String>,
                 onEach: (ThemeAssetFile, ThemeAssetResult) -> Unit = { _, _ -> }): Map<String, ThemeAssetResult> {
        val out = LinkedHashMap<String, ThemeAssetResult>()
        for (f in files) {
            val r = one(f, f.cls == ThemeAssetClass.screen && f.ref.url in foregroundUrls)
            out[f.key] = r
            onEach(f, r)
        }
        return out
    }

    private fun one(f: ThemeAssetFile, foreground: Boolean): ThemeAssetResult {
        val fetched = when (val r = fetcher.fetchSafely(f)) {
            is ThemeAssetFetcher.Out.Err -> return ThemeAssetResult.Failed(r.error, r.detail)
            is ThemeAssetFetcher.Out.Ok -> r.f
        }
        val p = try { processor.process(f, fetched.bytes, fetched.ext, foreground) }
            catch (e: Exception) { return ThemeAssetResult.Failed(if (f.cls == ThemeAssetClass.font) ThemeAssetError.fontInvalid else ThemeAssetError.decodeFailed) }
            catch (e: OutOfMemoryError) { return ThemeAssetResult.Failed(ThemeAssetError.tooManyPixels) }
        val o = when (p) {
            is ThemeAssetProcessor.Result.Err -> return ThemeAssetResult.Failed(p.error)
            is ThemeAssetProcessor.Result.Ok -> p.out
        }
        return try {
            tmpDir.mkdirs()
            val tmp = File.createTempFile("asset", ".tmp", tmpDir)
            tmp.writeBytes(o.bytes)
            ThemeAssetStore.put(dir, f.key, ThemeAssetStore.Entry(ThemeAssetStore.fileName(fetched.sha256, f.cls, o.ext),
                o.w, o.h, o.sw, o.sh, o.warn), tmp)
            ThemeAssetResult.Ok(o.warn)
        } catch (e: IOException) { ThemeAssetResult.Failed(ThemeAssetError.decodeFailed, "io") }
    }
}
