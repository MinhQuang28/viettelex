package com.viettelex.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.viettelex.keyboard.RainbowBorder
import com.viettelex.keyboard.RainbowClock

/**
 * Viền phím cầu vồng — theme "Gaming RGB" (iOS RainbowBorderView song sinh). Chỉ tồn tại khi theme có
 * "borderFx":"rainbow" (ImeRootView tạo lúc dựng); theme khác ⇒ không có view, 0 chi phí.
 *
 * MỘT dải gradient ([Strip], rộng gấp đôi bàn phím, cầu vồng lặp 2 chu kỳ) bị che bởi MỘT Path là viền mọi phím:
 * display list của view này = saveLayer · viền (đục) · dải (SRC_IN) · restore — ghi MỘT lần (dựng lại chỉ khi
 * khung phím đổi). Trượt màu = đổi translationX của dải: chỉ là thuộc tính RenderNode ⇒ không ghi lại display list,
 * RenderThread / GPU vẽ. Không animation từng phím.
 *
 * Chỉ chạy KHI ĐANG GÕ: phím đầu hẹn khung hình (postOnAnimation, tự đếm giờ — tắt hiệu ứng động hệ thống không
 * làm đứng); nghỉ [RainbowBorder.IDLE_AFTER] giây ⇒ thôi hẹn, đứng yên ở cầu vồng tĩnh (0 khung hình, 0 CPU).
 */
@SuppressLint("ViewConstructor")
class RainbowBorderView(context: Context, private val density: Float) : ViewGroup(context) {
    /** Dải gradient — onDraw MỘT lần (SRC_IN lên viền trong lớp của cha). */
    private class Strip(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val (cols, pos) = RainbowBorder.stops()
            paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, IntArray(cols.size) { cols[it] or (0xFF shl 24) },
                FloatArray(pos.size) { pos[it].toFloat() }, Shader.TileMode.CLAMP)
        }
        override fun onDraw(canvas: Canvas) { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
    }

    private val strip = Strip(context)
    private val outline = Path()
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = (RainbowBorder.LINE_WIDTH * density).toFloat(); color = 0xFF000000.toInt()
    }
    private val clock = RainbowClock()
    /** Độ lệch (0…1 bề ngang) khi đứng yên. */
    var phase = 0.0
        private set
    private var runStart = 0.0
    private var framePosted = false
    private val frame = Runnable { onFrame() }

    /** Đo: tổng thời gian main thread trong [poke] (ns) — test cộng dồn. */
    internal var costNs = 0L
    /** Số khung hình đã hẹn kể từ lúc tạo (test: nghỉ ⇒ không tăng). */
    internal var frames = 0
        private set

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setWillNotDraw(false)
        addView(strip)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false
    override fun dispatchTouchEvent(ev: MotionEvent?) = false

    /** Viền mọi phím (toạ độ view này, đã thụt nửa nét) — gọi khi dựng / đổi khung phím. */
    fun setOutline(p: Path) {
        outline.set(p)
        invalidate()
    }

    val lineWidthPx: Float get() = outlinePaint.strokeWidth

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        strip.measure(MeasureSpec.makeMeasureSpec(2 * w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        strip.layout(0, 0, 2 * (r - l), b - t)
        applyPhase()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (outline.isEmpty) return
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawPath(outline, outlinePaint)
        super.dispatchDraw(canvas)                 // dải (RenderNode con, translationX) SRC_IN ⇒ chỉ còn viền
        canvas.restoreToCount(sc)
    }

    /** Một phím vừa tới IME (chữ đã chèn). Nghỉ → chạy: hẹn khung hình. Đang chạy: chỉ ghi giờ. */
    fun poke() {
        val t0 = System.nanoTime()
        val now = SystemClock.uptimeMillis() / 1000.0
        if (clock.poke(now)) {
            runStart = now
            if (!framePosted) { framePosted = true; postOnAnimation(frame) }
        }
        costNs += System.nanoTime() - t0
    }

    /** Ẩn bàn phím: đứng yên ngay. */
    fun halt() {
        if (clock.running) freeze(SystemClock.uptimeMillis() / 1000.0)
        removeCallbacks(frame); framePosted = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        halt()
    }

    val running: Boolean get() = clock.running

    private fun onFrame() {
        framePosted = false
        if (!clock.running) return
        val now = SystemClock.uptimeMillis() / 1000.0
        if (clock.idleRemaining(now) <= 0) { freeze(now); return }
        frames++
        setOffset(RainbowBorder.offset(phase, now - runStart))
        framePosted = true
        postOnAnimation(frame)
    }

    private fun freeze(now: Double) {
        phase = RainbowBorder.offset(phase, now - runStart)
        clock.stop()
        applyPhase()
    }

    private fun applyPhase() = setOffset(phase)

    /** Dịch dải (thuộc tính RenderNode — không vẽ lại / không ghi lại display list). */
    private fun setOffset(o: Double) {
        val tx = (-o * width).toFloat()
        if (strip.translationX != tx) strip.translationX = tx
    }

    /** Test: chỉnh thời gian nghỉ. */
    internal fun setIdleForTest(s: Double) { clock.idleAfter = s }
}
