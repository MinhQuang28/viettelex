package com.viettelex.android.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.viettelex.keyboard.CustomThemeStore
import com.viettelex.keyboard.KeyStyleStore
import com.viettelex.keyboard.Keys
import com.viettelex.keyboard.ThemeAssetClass
import com.viettelex.keyboard.ThemeAssetFile
import com.viettelex.keyboard.ThemeAssetPlan
import com.viettelex.keyboard.ThemeAssetPrompt
import com.viettelex.keyboard.ThemeAssetSpec
import com.viettelex.keyboard.ThemeAssetStore
import com.viettelex.keyboard.ThemeGate
import com.viettelex.keyboard.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/*
 * Luồng "tệp của theme" ở APP: hỏi trước khi tải (khi dán / lưu / chọn / khôi phục theme có tệp còn thiếu), tải trong
 * phạm vi app (không huỷ khi rời trang), báo lỗi / cảnh báo từng tệp, dọn tệp không còn theme nào dùng (sau lưu /
 * xoá / dán / khôi phục và lúc mở app). Dán JSON KHÔNG bao giờ tự gửi yêu cầu mạng. Tải thuộc Plus; chép JSON miễn phí.
 */
object ThemeAssetFlow {
    fun dir(ctx: Context): File = ThemeAssetStore.dir(ctx.filesDir)
    private fun tmpDir(ctx: Context): File = File(ctx.cacheDir, "theme_asset_tmp")

    /** Kết quả lần tải gần nhất theo khoá tệp (lỗi / cảnh báo) — UI hiện. */
    val results = mutableStateMapOf<String, ThemeAssetResult>()
    /** Tăng mỗi lần kho đổi (tải xong / dọn) — trạng thái tệp vẽ lại. */
    var storeVersion by mutableIntStateOf(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gcExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "theme-asset-gc").apply { isDaemon = true } }

    /** Tệp kế hoạch của [a] còn thiếu (mục lục + tệp). Không có tệp ⇒ rỗng, không đọc đĩa. */
    fun missing(ctx: Context, a: ThemeAssetSpec): List<ThemeAssetFile> {
        if (a.isEmpty) return emptyList()
        val d = dir(ctx)
        return ThemeAssetStore.missing(ThemeAssetPlan.make(a), ThemeAssetStore.readIndex(d), d)
    }

    /** Mọi tệp còn được tham chiếu: kho theme tự tạo + bản vá MỌI theme (kể cả khi hết Plus — dữ liệu giữ). */
    fun referenced(ctx: Context): Set<String> {
        val all = Prefs.of(ctx).all
        return ThemeAssetStore.referencedKeys(CustomThemeStore.parse(all[Keys.CUSTOM_THEMES] as? String).values,
            KeyStyleStore.parse(all[Keys.KEY_STYLES] as? String).values)
    }

    /** Dọn (luồng nền, tuần tự): xoá tệp không theme nào dùng + tệp tạm. */
    fun gcAsync(ctx: Context) {
        val app = ctx.applicationContext
        gcExecutor.execute {
            runCatching {
                val deleted = ThemeAssetStore.collectGarbage(dir(app), referenced(app))
                tmpDir(app).listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
                if (deleted.isNotEmpty()) android.os.Handler(android.os.Looper.getMainLooper()).post { storeVersion++ }
            }
        }
    }

    /** Tải [files] (đã được đồng ý) trong phạm vi app; [onDone] trên main. Báo IME dựng lại qua [Keys.THEME_ASSETS_STAMP]. */
    fun start(ctx: Context, files: List<ThemeAssetFile>, foregroundUrls: Set<String>,
              onDone: (Map<String, ThemeAssetResult>) -> Unit) {
        val app = ctx.applicationContext
        scope.launch {
            val dm = app.resources.displayMetrics
            val downloader = ThemeAssetDownloader(dir(app), tmpDir(app), HttpThemeAssetTransport(),
                AndroidThemeAssetProcessor(maxOf(dm.widthPixels, dm.heightPixels), tmpDir(app).apply { mkdirs() }))
            val r = downloader.download(files, foregroundUrls)
            if (r.values.any { it is ThemeAssetResult.Ok })
                Prefs.of(app).edit().putLong(Keys.THEME_ASSETS_STAMP, System.currentTimeMillis()).apply()
            withContext(Dispatchers.Main) {
                results.putAll(r)
                storeVersion++
                onDone(r)
            }
        }
    }

    fun warnText(w: String?): String? = when (w) {
        ThemeAssetStore.WARN_MISSING_VIETNAMESE -> tr("Phông thiếu chữ tiếng Việt (ạ ế ữ đ Ơ Ư) — chữ đó sẽ dùng phông hệ thống.")
        ThemeAssetStore.WARN_FOREGROUND_COVERAGE -> tr("Ảnh tiền cảnh che nhiều phím — chữ có thể khó đọc.")
        else -> null
    }

    /** Nhãn tệp cho người đọc: phông / ảnh nền / tiền cảnh / phím (nhóm) — khi bấm. */
    fun label(f: ThemeAssetFile, a: ThemeAssetSpec): String = when (f.cls) {
        ThemeAssetClass.font -> tr("Phông") + (a.font?.name?.let { " $it" } ?: "")
        ThemeAssetClass.screen -> if (a.foreground == f.ref && a.background != f.ref) tr("Tiền cảnh") else tr("Ảnh nền theme")
        ThemeAssetClass.key -> {
            val role = a.keys.entries.firstOrNull { it.value.image == f.ref }?.key
            if (role != null) tr("Phím %s", role)
            else tr("Phím %s (khi bấm)", a.keys.entries.firstOrNull { it.value.pressed == f.ref }?.key ?: "")
        }
    }
}

/**
 * Phông nhãn của ô xem trước trong app (thuần, test JVM). Bug 19/10/2026: chọn phông "Vt" tải về, tải xong ô xem trước
 * vẫn y cũ — KeyboardPreview không vẽ chữ (chỉ chấm) và không đọc phông tải về. Nay: bản lớn vẽ chữ khi chữ được tuỳ
 * chỉnh, phông = tệp trong kho (đọc lại khi [ThemeAssetFlow.storeVersion] đổi), cùng luật bàn phím ([keyLabelTypeface]).
 */
object PreviewLabelFont {
    /** Vẽ nhãn chữ: chỉ bản lớn và khi chữ được tuỳ chỉnh (phông / độ đậm / cỡ / phông tải về); mặc định giữ chấm. */
    fun showsLabels(large: Boolean, style: com.viettelex.keyboard.KeyStyle): Boolean = large && style.customizesLabels

    /** Tệp phông tải về của [style] đã có trong kho (mục lục + tệp); chưa tải / thiếu ⇒ null (phông hệ thống). */
    fun downloadedFile(style: com.viettelex.keyboard.KeyStyle, dir: File): File? {
        val f = style.assets.font ?: return null
        return ThemeAssetStore.locate(ThemeAssetClass.font, f.ref, ThemeAssetStore.readIndex(dir), dir)?.first
    }
}

/** Câu hỏi trước khi tải: [offer] khi theme có tệp còn thiếu; host hiện hộp thoại + tiến trình. */
class AssetPromptState {
    var files by mutableStateOf<List<ThemeAssetFile>>(emptyList())
    var foreground: Set<String> = emptySet()
    var busy by mutableStateOf(false)
    var note by mutableStateOf<String?>(null)

    fun offer(ctx: Context, a: ThemeAssetSpec) {
        val m = ThemeAssetFlow.missing(ctx, a)
        if (m.isEmpty() || busy) return
        foreground = setOfNotNull(a.foreground?.url)
        files = m
    }
}

@Composable
fun rememberAssetPrompt(): AssetPromptState = remember { AssetPromptState() }

@Composable
fun ThemeAssetPromptHost(state: AssetPromptState, onOpenPlus: () -> Unit) {
    val c = LocalVT.current
    val ctx = LocalContext.current
    val files = state.files
    if (files.isNotEmpty()) {
        val allowed = ThemeGate.allowsWallpaper
        AlertDialog(onDismissRequest = { state.files = emptyList() },
            title = { Text(tr("Tệp của giao diện")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ThemeAssetPrompt.text(files) ?: "")
                    if (!allowed) Text(tr("Tải tệp của giao diện thuộc VietTelex Plus."), color = c.secondary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val fg = state.foreground
                    state.files = emptyList()
                    if (!allowed) { onOpenPlus(); return@TextButton }
                    state.busy = true
                    state.note = tr("Đang tải tệp của giao diện…")
                    ThemeAssetFlow.start(ctx, files, fg) { r ->
                        state.busy = false
                        val failed = r.values.filterIsInstance<ThemeAssetResult.Failed>()
                        state.note = if (failed.isEmpty()) tr("Đã tải xong tệp của giao diện.")
                            else tr("Lỗi tải tệp: %s", failed.first().error.message)
                    }
                }) { Text(if (allowed) tr("Tải") else "VietTelex Plus") }
            },
            dismissButton = { TextButton(onClick = { state.files = emptyList() }) { Text(tr("Để sau")) } })
    }
    state.note?.let { Text(it, style = VTType.footnote, color = c.secondary, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
}

/** Trạng thái từng tệp của theme: ✓ đã tải (+ cảnh báo) · ✕ lỗi · • chưa tải; còn thiếu ⇒ "Tải lại tệp". */
@Composable
fun ThemeAssetStatusList(assets: ThemeAssetSpec, onRetry: (() -> Unit)?) {
    if (assets.isEmpty) return
    val c = LocalVT.current
    val ctx = LocalContext.current
    val version = ThemeAssetFlow.storeVersion
    val plan = remember(assets) { ThemeAssetPlan.make(assets) }
    val dir = remember { ThemeAssetFlow.dir(ctx) }
    val index = remember(assets, version) { ThemeAssetStore.readIndex(dir) }
    var anyMissing = false
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(tr("Tệp của giao diện"), style = VTType.subheadline, color = c.label)
        for (f in plan.files) {
            val e = index[f.key]?.takeIf { File(dir, it.file).exists() }
            val res = ThemeAssetFlow.results[f.key]
            val name = ThemeAssetFlow.label(f, assets) + " · " + com.viettelex.keyboard.ThemeAssetURL.host(f.ref.url)
            when {
                e != null -> {
                    Text("✓ $name — " + tr("đã tải"), style = VTType.footnote, color = c.secondary)
                    ThemeAssetFlow.warnText(e.warn)?.let { Text("⚠ $it", style = VTType.footnote, color = Color(0xFFE08600)) }
                }
                res is ThemeAssetResult.Failed -> {
                    anyMissing = true
                    Text("✕ $name — " + tr("Lỗi tải tệp: %s", res.error.message), style = VTType.footnote, color = c.red)
                }
                else -> { anyMissing = true; Text("• $name — " + tr("chưa tải"), style = VTType.footnote, color = c.secondary) }
            }
        }
        if (anyMissing && onRetry != null) TextButton(onClick = onRetry) { Text(tr("Tải lại tệp")) }
    }
}
