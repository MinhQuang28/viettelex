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

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        decorPath(path, decoration.motif, bounds.width() / density, bounds.height() / density, density,
            bounds.left.toFloat(), bounds.top.toFloat())
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

/**
 * Dựng path hoạ tiết vào [path] cho vùng [wUnits]×[hUnits] (dp), [unitPx] px mỗi dp, gốc (left, top).
 * Dùng chung nền IME + bản xem trước trong app.
 */
fun decorPath(path: Path, motif: String, wUnits: Float, hUnits: Float, unitPx: Float, left: Float = 0f, top: Float = 0f) {
    path.rewind()
    val d = unitPx.toDouble()
    val pts = ThemeDecoration.placements(wUnits.toDouble(), hUnits.toDouble())
    val s = ThemeDecoration.MOTIF_SIZE * d
    var i = 0
    while (i < pts.size) {
        val ox = left + pts[i] * d; val oy = top + pts[i + 1] * d
        fun x(u: Double) = (ox + u * s).toFloat()
        fun y(u: Double) = (oy + u * s).toFloat()
        if (motif == ThemeDecoration.HEARTS) {
            path.moveTo(x(ThemeDecoration.HEART_START[0]), y(ThemeDecoration.HEART_START[1]))
            for (c in ThemeDecoration.HEART_CUBICS) path.cubicTo(x(c[0]), y(c[1]), x(c[2]), y(c[3]), x(c[4]), y(c[5]))
            path.close()
        } else for (c in ThemeDecoration.FLOWER_CIRCLES) {
            path.addCircle(x(c[0]), y(c[1]), (c[2] * s).toFloat(), Path.Direction.CW)
        }
        i += 2
    }
}
