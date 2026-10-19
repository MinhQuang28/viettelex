package com.viettelex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * 12 phông có sẵn (themes/builtin-fonts.json): tên trong danh sách = họ phông trong tệp (bản đầy đủ docs/fonts — tệp
 * viettelex.com phục vụ — và bản xem trước "Vt" trong assets), tên PostScript = tên bỏ dấu cách + "-Regular"/"-Medium";
 * 4 phông đổi tên vì Reserved Font Name (OFL) mang tiền tố "VT ". Đọc bảng `name` trực tiếp (JVM, không Typeface).
 */
class BuiltinFontNamesTest {
    private fun repoFile(rel: String): File = listOf("../..", "..", ".").map { File(it, rel) }.firstOrNull { it.exists() }
        ?: error("không thấy $rel")

    /** Bảng `name` (Windows, Unicode BMP, en-US): nameID → chuỗi. */
    private fun names(b: ByteArray): Map<Int, String> {
        val buf = ByteBuffer.wrap(b)
        val numTables = buf.getShort(4).toInt() and 0xFFFF
        var off = -1
        for (i in 0 until numTables) {
            val rec = 12 + 16 * i
            if (String(b, rec, 4, Charsets.US_ASCII) == "name") off = buf.getInt(rec + 8)
        }
        require(off >= 0) { "không có bảng name" }
        val count = buf.getShort(off + 2).toInt() and 0xFFFF
        val strings = off + (buf.getShort(off + 4).toInt() and 0xFFFF)
        val out = HashMap<Int, String>()
        for (i in 0 until count) {
            val r = off + 6 + 12 * i
            val platform = buf.getShort(r).toInt(); val enc = buf.getShort(r + 2).toInt(); val lang = buf.getShort(r + 4).toInt()
            if (platform != 3 || enc != 1 || lang != 0x409) continue
            val id = buf.getShort(r + 6).toInt() and 0xFFFF
            val len = buf.getShort(r + 8).toInt() and 0xFFFF
            val o = buf.getShort(r + 10).toInt() and 0xFFFF
            out[id] = String(b, strings + o, len, Charsets.UTF_16BE)
        }
        return out
    }

    @Test fun builtinFontNamesMatchFiles() {
        // docs/fonts/manifest.json: mỗi mục có "id" … "renamed": true|false (không cần thư viện JSON).
        val renamed = Regex(""""id":\s*"([^"]+)"[^}]*?"renamed":\s*(true|false)""")
            .findAll(repoFile("docs/fonts/manifest.json").readText()).associate { it.groupValues[1] to (it.groupValues[2] == "true") }
        assertEquals(12, BuiltinFonts.list.size)
        assertEquals(4, renamed.values.count { it })
        for (e in BuiltinFonts.list) {
            assertEquals(e.id, renamed[e.id], e.name.startsWith("VT "))
            val files = listOf(repoFile("docs/fonts/" + e.ref.url.substringAfterLast('/')),
                repoFile("android/app/src/main/assets/" + e.previewAsset))
            for (f in files) {
                val n = names(f.readBytes())
                assertEquals(f.path, e.name, n[16] ?: n[1])
                val ps = n[6]
                val stem = e.name.replace(" ", "")
                assertTrue("${f.path}: $ps", ps == "$stem-Regular" || ps == "$stem-Medium")
            }
        }
    }
}
