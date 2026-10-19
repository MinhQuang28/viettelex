package com.viettelex.android

import com.viettelex.android.ui.PreviewLabelFont
import com.viettelex.android.ui.ThemeAssetDownloader
import com.viettelex.android.ui.ThemeAssetProcessor
import com.viettelex.android.ui.ThemeAssetResult
import com.viettelex.android.ui.ThemeAssetTransport
import com.viettelex.keyboard.BuiltinFonts
import com.viettelex.keyboard.KeyLabelFont
import com.viettelex.keyboard.KeyStyle
import com.viettelex.keyboard.ThemeAssetClass
import com.viettelex.keyboard.ThemeAssetFile
import com.viettelex.keyboard.ThemeAssetPlan
import com.viettelex.keyboard.ThemeAssetSniff
import com.viettelex.keyboard.ThemeAssetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * Bug 19/10/2026 (Phil, iPhone 13 mini — Android cùng lỗi): Tuỳ chỉnh nâng cao → Phông chữ → chọn phông "Vt" tải về →
 * đồng ý tải ⇒ ô xem trước KHÔNG đổi phông. Android: KeyboardPreview chỉ vẽ chấm, không đọc phông tải về. Khoá: tải xong
 * (transport giả, tệp phông thật docs/fonts) ⇒ ô xem trước tìm được đúng tệp vừa tải (trước đó: null ⇒ phông hệ thống),
 * và bản lớn vẽ nhãn chữ khi chữ được tuỳ chỉnh.
 */
class PreviewLabelFontTest {
    private fun repoFile(rel: String): File = listOf("../..", "..", ".").map { File(it, rel) }.firstOrNull { it.exists() }
        ?: error("không thấy $rel")

    private fun withFont(e: BuiltinFonts.Entry) = KeyStyle(font = KeyLabelFont.DEFAULT, assets = KeyStyle.DEFAULT.assets.copy(font = e.asset))

    @Test fun showsLabelsOnlyWhenLargeAndCustomized() {
        assertFalse("mặc định giữ chấm như cũ", PreviewLabelFont.showsLabels(true, KeyStyle.DEFAULT))
        val st = withFont(BuiltinFonts.list.first())
        assertTrue(PreviewLabelFont.showsLabels(true, st))
        assertFalse("ô nhỏ (thẻ theme) không vẽ chữ", PreviewLabelFont.showsLabels(false, st))
        assertTrue(PreviewLabelFont.showsLabels(true, KeyStyle(font = KeyLabelFont.SERIF)))
    }

    @Test fun downloadCompletionMakesPreviewResolveDownloadedFont() {
        val e = BuiltinFonts.list.first { it.id == "lobster" }       // phông đã đổi tên "VT …"
        val bytes = repoFile("docs/fonts/" + e.ref.url.substringAfterLast('/')).readBytes()
        val dir = Files.createTempDirectory("plf-store").toFile()
        val tmp = Files.createTempDirectory("plf-tmp").toFile()
        try {
            val style = withFont(e)
            // Trước khi tải: thiếu ⇒ ô xem trước dùng phông hệ thống.
            assertNull(PreviewLabelFont.downloadedFile(style, dir))
            val missing = ThemeAssetStore.missing(ThemeAssetPlan.make(style.assets), ThemeAssetStore.readIndex(dir), dir)
            assertEquals(listOf(ThemeAssetFile(ThemeAssetClass.font, e.ref)), missing)

            val transport = object : ThemeAssetTransport {
                override fun open(url: String) =
                    if (url == e.ref.url) ThemeAssetTransport.Response(200, null, bytes.size.toLong(), ByteArrayInputStream(bytes))
                    else ThemeAssetTransport.Response(404, null, -1, ByteArrayInputStream(ByteArray(0)))
            }
            // Bộ xử lý thật dùng Typeface (không chạy trên JVM) — phông lưu nguyên tệp như bản thật.
            val proc = object : ThemeAssetProcessor {
                override fun process(file: ThemeAssetFile, bytes: ByteArray, ext: String, foreground: Boolean) =
                    ThemeAssetProcessor.Result.Ok(ThemeAssetProcessor.Out(bytes, ext, 0, 0, 0, 0, null))
            }
            val r = ThemeAssetDownloader(dir, tmp, transport, proc).download(missing, emptySet())
            assertTrue(r.values.single() is ThemeAssetResult.Ok)

            // Tải xong: ô xem trước tìm được đúng tệp (cùng tệp bàn phím nạp — ThemeAssetStore.locate).
            val f = PreviewLabelFont.downloadedFile(style, dir)
            assertNotNull("tải xong ⇒ phông xem trước có tệp", f)
            assertEquals(e.ref.sha256, ThemeAssetSniff.sha256Hex(f!!.readBytes()))
            assertTrue(ThemeAssetStore.missing(ThemeAssetPlan.make(style.assets), ThemeAssetStore.readIndex(dir), dir).isEmpty())
            // Chọn phông hệ thống ⇒ không còn tệp tải về.
            assertNull(PreviewLabelFont.downloadedFile(KeyStyle(font = KeyLabelFont.SERIF), dir))
        } finally {
            dir.deleteRecursively(); tmp.deleteRecursively()
        }
    }
}
