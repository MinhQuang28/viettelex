package com.viettelex.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.viettelex.keyboard.SwipeTrailFx
import com.viettelex.keyboard.SwipeTrailState
import com.viettelex.keyboard.SwipeTrailStroke
import com.viettelex.keyboard.SwipeTrailStyle

/**
 * Vệt gõ vuốt: overlay phủ đúng vùng phím (cùng toạ độ [KeyboardView]), không nhận touch. Kiểu ("Hiệu ứng nét
 * vuốt", [SwipeTrailStyle]) chỉ đổi các Paint / shader dựng MỘT lần ở [configure]; hình học + hạt ở
 * [SwipeTrailState] (mảng cấp sẵn). Mỗi khung: MỘT Path từ mảng điểm, vẽ qua các Paint. onDraw không cấp
 * phát; không hẹn khung khi không còn gì để vẽ (0 animation khi nghỉ). Kiểu Tắt ⇒ IME gỡ view khỏi
 * KeyboardView (không gọi gì).
 */
@SuppressLint("ViewConstructor")
open class SwipeTrailView(context: Context, private val theme: ImeTheme) : View(context) {
    var style: SwipeTrailStyle = SwipeTrailStyle.NONE
        private set
    protected var state = SwipeTrailState(SwipeTrailStyle.NONE, theme.density)
        private set
    private val path = Path()
    private var paints: Array<Paint> = emptyArray()
    private var baseAlpha = IntArray(0)
    private var fillLast = false
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var colors = IntArray(0)
    private val star = Path()
    private val pOut = FloatArray(5)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        configure(SwipeTrailStyle.SIMPLE)
    }

    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    /** Dựng Paint / shader / pool cho kiểu (gọi lúc hiện bàn phím). Cùng kiểu ⇒ 0 việc. */
    fun configure(s: SwipeTrailStyle) {
        if (s == style) return
        style = s
        state = SwipeTrailState(s, theme.density)
        // Đơn giản = vệt cũ: 5dp, màu vệt theme × 3/4 độ đậm.
        val tr = theme.trail
        val simple = ((android.graphics.Color.alpha(tr) * 3 / 4) shl 24) or (tr and 0xFFFFFF)
        val specs = s.strokes(5.0, simple, theme.action, theme.dark)
        paints = Array(specs.size) { i -> paintFor(specs[i]) }
        baseAlpha = IntArray(specs.size) { android.graphics.Color.alpha(specs[it].argb) }
        fillLast = specs.lastOrNull()?.kind == SwipeTrailStroke.Kind.FILL
        updateShader()
        colors = s.particleColors(theme.action).map { (0xFF shl 24) or it }.toIntArray()
        star.reset()
        if (s == SwipeTrailStyle.STARDUST) {
            for (k in 0 until 8) {
                val r = (if (k % 2 == 0) 4.5f else 1.5f) * theme.density
                val a = k * Math.PI / 4 - Math.PI / 2
                val x = (Math.cos(a) * r).toFloat(); val y = (Math.sin(a) * r).toFloat()
                if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
        }
        invalidate()
    }

    private fun paintFor(sp: SwipeTrailStroke) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = if (sp.kind == SwipeTrailStroke.Kind.FILL) Paint.Style.FILL else Paint.Style.STROKE
        strokeWidth = theme.dp(sp.width.toFloat())
        color = sp.argb
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    /** Cầu vồng: MỘT LinearGradient chéo theo cỡ view (đặt lúc cấu hình / đổi cỡ, không mỗi khung). */
    private fun updateShader() {
        if (style != SwipeTrailStyle.RAINBOW) return
        val p = paints.firstOrNull() ?: return
        val w = width.coerceAtLeast(1).toFloat(); val h = height.coerceAtLeast(1).toFloat()
        val c = IntArray(SwipeTrailFx.RAINBOW.size) { (0xFF shl 24) or SwipeTrailFx.RAINBOW[it] }
        p.shader = LinearGradient(0f, h * 0.2f, w, h * 0.8f, c, null, Shader.TileMode.CLAMP)
    }

    fun begin() { state.begin(); postInvalidateOnAnimation() }

    fun add(x: Float, y: Float, tMs: Long) {
        if (!state.active) return
        state.add(x, y, tMs)
        postInvalidateOnAnimation()
    }

    /** Nhấc tay (mờ dần) hoặc huỷ ([fade] = false: xoá nét ngay). */
    fun end(fade: Boolean) {
        state.end(fade, SystemClock.uptimeMillis())
        postInvalidateOnAnimation()
    }

    /** Đã chốt từ vuốt ⇒ "Pháo hoa cuối nét" (kiểu khác: 0 việc). */
    fun commitBurst() { if (state.commitBurst(SystemClock.uptimeMillis())) postInvalidateOnAnimation() }

    /** Ẩn bàn phím / gỡ: dọn hết, không hẹn khung nào. */
    fun clear() { state.clear(); invalidate() }

    override fun onDraw(c: Canvas) { drawTrail(c, SystemClock.uptimeMillis()) }

    /** Vẽ khung lúc [now]; true nếu còn phải vẽ khung sau (đã hẹn). */
    protected fun drawTrail(c: Canvas, now: Long): Boolean {
        val st = state
        if (paints.isNotEmpty() && st.build(now)) {
            path.rewind()
            val n = st.outN
            path.moveTo(st.outX[0], st.outY[0])
            for (i in 1 until n) path.lineTo(st.outX[i], st.outY[i])
            if (fillLast) path.close()
            for (i in paints.indices) {
                val p = paints[i]
                p.alpha = (baseAlpha[i] * st.strokeAlpha).toInt()
                c.drawPath(path, p)
            }
        }
        if (st.particleCount > 0 && colors.isNotEmpty()) {
            val starShape = style == SwipeTrailStyle.STARDUST
            val r = (if (style == SwipeTrailStyle.FIRE) 2.6f else 2.2f) * theme.density
            for (i in 0 until st.particleCount) {
                if (!st.particle(i, now, pOut)) continue
                dotPaint.color = colors[i % colors.size]
                dotPaint.alpha = (255 * pOut[3]).toInt().coerceIn(0, 255)
                if (starShape) {
                    val save = c.save()
                    c.translate(pOut[0], pOut[1])
                    c.rotate(Math.toDegrees(pOut[4].toDouble()).toFloat())
                    c.scale(pOut[2], pOut[2])
                    c.drawPath(star, dotPaint)
                    c.restoreToCount(save)
                } else c.drawCircle(pOut[0], pOut[1], r * pOut[2], dotPaint)
            }
        }
        val more = st.isAnimating(now)
        if (more) postInvalidateOnAnimation()
        return more
    }
}
