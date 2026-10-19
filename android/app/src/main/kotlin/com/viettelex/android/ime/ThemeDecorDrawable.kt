package com.viettelex.android.ime

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.viettelex.keyboard.ThemeDecoration

/**
 * Nền theme có hoạ tiết (Tim đen / Hoa xanh): màu nền + MỘT Path chứa mọi motif. Path dựng lại
 * chỉ khi đổi cỡ (onBoundsChange); nền view nằm trong display list của view gốc nên chỉ vẽ lại
 * khi chính nền đổi — gõ phím (KeyboardView invalidate) không đụng tới. Không bitmap.
 * Hình học dùng chung [ThemeDecoration] (= iOS KeyboardDecoration).
 */
class ThemeDecorDrawable(private val bg: Int, private val decoration: ThemeDecoration, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = decoration.color; style = Paint.Style.FILL }
    private val bgPaint = Paint().apply { color = bg; style = Paint.Style.FILL }
    private val path = Path()
    /** Khe giữa các hàng phím, dp theo toạ độ view gốc (ImeRootView đặt sau mỗi lần dựng phím). */
    private var gaps: List<ThemeDecoration.Gap> = emptyList()

    /** Đổi khe ⇒ dựng lại path (không đổi ⇒ không làm gì — không chạy mỗi lần gõ). */
    fun setRowGaps(g: List<ThemeDecoration.Gap>) {
        if (g == gaps) return
        gaps = g
        rebuild(); invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    private fun rebuild() {
        decorPath(path, decoration.motif, ThemeDecoration.placements(bounds.width() / density.toDouble(), gaps, decoration.density),
            density, bounds.left.toFloat(), 0f, decoration.alt)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, bgPaint)   // theo bounds: khung thả nổi vẽ tay trong một vùng
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Dựng path hoạ tiết vào [path] từ [placements] (dp), [unitPx] px mỗi dp, gốc (left, top). Nền IME + xem trước. */
fun decorPath(path: Path, motif: String, placements: List<ThemeDecoration.Placement>, unitPx: Float,
              left: Float = 0f, top: Float = 0f, alt: String? = null) {
    path.rewind()
    val d = unitPx.toDouble()
    // Mai / đào / bông tuyết / sao / nốt nhạc / đèn lồng…: dữ liệu hình chung iOS — tính MỘT lần mỗi lần dựng path.
    // [alt]: motif thứ hai xen kẽ (vị trí lẻ) — Trung Thu đèn lồng + trăng, Halloween bí ngô + dơi.
    val motifs = arrayOf(motif, alt ?: motif)
    val shapes = arrayOf(ThemeDecoration.shape(motifs[0]), ThemeDecoration.shape(motifs[1]))
    for ((k, p) in placements.withIndex()) {
        val m = motifs[k % 2]
        val shape = shapes[k % 2]
        val s = p.size * d
        val ox = left + p.x * d; val oy = top + p.y * d
        fun x(u: Double) = (ox + u * s).toFloat()
        fun y(u: Double) = (oy + u * s).toFloat()
        when {
            m == ThemeDecoration.HEARTS -> {
                path.moveTo(x(ThemeDecoration.HEART_START[0]), y(ThemeDecoration.HEART_START[1]))
                for (c in ThemeDecoration.HEART_CUBICS) path.cubicTo(x(c[0]), y(c[1]), x(c[2]), y(c[3]), x(c[4]), y(c[5]))
                path.close()
            }
            shape.isNotEmpty() -> for (sub in shape) {
                path.moveTo(x(sub.sx), y(sub.sy))
                for (g in sub.segs) {
                    if (g.size == 2) path.lineTo(x(g[0]), y(g[1]))
                    else path.cubicTo(x(g[0]), y(g[1]), x(g[2]), y(g[3]), x(g[4]), y(g[5]))
                }
                path.close()
            }
            else -> for (c in ThemeDecoration.FLOWER_CIRCLES) {
                path.addCircle(x(c[0]), y(c[1]), (c[2] * s).toFloat(), Path.Direction.CW)
            }
        }
    }
}
