package com.viettelex.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.viettelex.keyboard.KeyEffect
import com.viettelex.keyboard.KeyEffectParticles

/**
 * Lớp phủ "Hiệu ứng khi bấm phím" (Plus) — song sinh iOS KeyEffectOverlay. MỘT View không nhận chạm,
 * phủ cùng khung với [BalloonView] (strip + phím) và nằm NGAY DƯỚI nó (ô phóng to luôn đè lên hạt).
 *
 * Hiệu năng (gõ mượt là số 1):
 *  • Chỉ tồn tại khi bật (IME gắn/gỡ theo [com.viettelex.keyboard.KeyEffectPolicy]) — tắt ⇒ không có view.
 *  • [burst] chỉ ghi toạ độ + `post` MỘT Runnable cấp sẵn: hạt sinh ở lượt sau, SAU khi phím đã chèn chữ.
 *  • Vẽ: ≤ 20 path vector (dựng một lần mỗi kiểu) bằng biến đổi canvas — display list ghi trên UI
 *    thread, RenderThread/GPU rasterize. Mảng hạt, Path, Paint cấp sẵn ⇒ không cấp phát mỗi khung hình;
 *    hết hạt thì thôi hẹn khung hình (idle = 0 CPU).
 */
@SuppressLint("ViewConstructor")
open class KeyEffectView(context: Context, private val density: Float) : View(context) {
    private val sim = KeyEffectParticles()
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var useFill = true
    private var useStroke = false
    private var fillAlpha = 255
    private var strokeAlpha = 255
    private var color = 0

    /** Điểm chờ (gõ chồng trong cùng lượt) — giữ tối đa [MAX_PENDING] điểm mới nhất. */
    private val pendX = FloatArray(MAX_PENDING)
    private val pendY = FloatArray(MAX_PENDING)
    private var pendN = 0
    private var posted = false
    private val flushRun = Runnable { flush() }

    val effect: KeyEffect get() = sim.effect

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false

    fun configure(e: KeyEffect, accent: Int) {
        if (e == sim.effect && accent == color) return
        sim.configure(e)
        color = accent
        buildPath(e)
        val opaque = accent or (0xFF shl 24)
        fill.color = opaque; stroke.color = opaque
        when (e) {
            KeyEffect.BUBBLE -> { useFill = true; useStroke = true; fillAlpha = 46; strokeAlpha = 255; stroke.strokeWidth = 1.5f }
            KeyEffect.RIPPLE -> { useFill = false; useStroke = true; strokeAlpha = 204; stroke.strokeWidth = 2f }
            else -> { useFill = true; useStroke = false; fillAlpha = 255 }
        }
        pendN = 0
        invalidate()
    }

    /** Một phím vừa chèn chữ, tâm phím ([x], [y]) theo toạ độ view này. */
    fun burst(x: Float, y: Float) {
        if (sim.effect == KeyEffect.NONE) return
        if (pendN == MAX_PENDING) {
            for (i in 1 until MAX_PENDING) { pendX[i - 1] = pendX[i]; pendY[i - 1] = pendY[i] }
            pendN--
        }
        pendX[pendN] = x; pendY[pendN] = y; pendN++
        if (!posted) { posted = true; post(flushRun) }
    }

    /** Sinh hạt cho các điểm chờ (test gọi thẳng). */
    fun flush() {
        posted = false
        if (pendN == 0) return
        val now = SystemClock.uptimeMillis()
        for (i in 0 until pendN) sim.spawn(pendX[i], pendY[i], now)
        pendN = 0
        postInvalidateOnAnimation()
    }

    internal val pendingCount: Int get() = pendN
    internal val isPosted: Boolean get() = posted
    internal fun aliveCount(): Int = sim.aliveCount()

    override fun onDraw(canvas: Canvas) {
        if (sim.effect == KeyEffect.NONE) return
        val now = SystemClock.uptimeMillis()
        var any = false
        for (s in 0 until sim.capacity) {
            if (!sim.sample(s, now, density)) continue
            any = true
            val k = sim.sScale * density
            val save = canvas.save()
            canvas.translate(sim.sx, sim.sy)
            if (sim.sRot != 0f) canvas.rotate(sim.sRot)
            canvas.scale(k, k)
            if (useFill) { fill.alpha = (fillAlpha * sim.sAlpha).toInt(); canvas.drawPath(path, fill) }
            if (useStroke) { stroke.alpha = (strokeAlpha * sim.sAlpha).toInt(); canvas.drawPath(path, stroke) }
            canvas.restoreToCount(save)
        }
        if (any) postInvalidateOnAnimation()
    }

    /** Hình vector (dp, quanh gốc 0,0) — cùng hình iOS KeyEffectShapes. */
    private fun buildPath(e: KeyEffect) {
        path.reset()
        when (e) {
            KeyEffect.NONE -> {}
            KeyEffect.HEART -> {
                val s = 8f
                path.moveTo(0f, s * 0.9f)
                path.cubicTo(-s * 0.6f, s * 0.5f, -s, s * 0.15f, -s, -s * 0.2f)
                path.cubicTo(-s, -s * 0.85f, -s * 0.2f, -s * 0.95f, 0f, -s * 0.55f)
                path.cubicTo(s * 0.2f, -s * 0.95f, s, -s * 0.85f, s, -s * 0.2f)
                path.cubicTo(s, s * 0.15f, s * 0.6f, s * 0.5f, 0f, s * 0.9f)
                path.close()
            }
            KeyEffect.BUBBLE -> path.addCircle(0f, 0f, 7f, Path.Direction.CW)
            KeyEffect.RIPPLE -> path.addCircle(0f, 0f, 20f, Path.Direction.CW)
            KeyEffect.PETAL -> {
                val s = 8f
                path.moveTo(0f, -s)
                path.quadTo(s * 0.9f, -s * 0.1f, 0f, s)
                path.quadTo(-s * 0.9f, -s * 0.1f, 0f, -s)
                path.close()
            }
            KeyEffect.FIREWORK -> {
                for (k in 0 until 8) {
                    val a = k * Math.PI / 4 - Math.PI / 2
                    val r = if (k % 2 == 0) 5.0 else 1.6
                    val x = (Math.cos(a) * r).toFloat(); val y = (Math.sin(a) * r).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
        }
    }

    companion object { const val MAX_PENDING = 4 }
}
