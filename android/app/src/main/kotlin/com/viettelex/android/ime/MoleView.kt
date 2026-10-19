package com.viettelex.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.viettelex.keyboard.MoleEvent
import com.viettelex.keyboard.MoleGame
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lớp vẽ "Đập chuột" ([MoleGame]) — chỉ tồn tại khi theme đang dùng có "game":"mole" (IME gắn lúc hiện); theme khác ⇒
 * không có view (0 chi phí). iOS MoleOverlay.swift cùng hình / cùng nhịp.
 *
 * • KHÔNG nhận chạm (dispatchTouchEvent = false), ẩn với TalkBack: phím dưới chuột vẫn gõ như thường.
 * • Hình vector tự vẽ (tai, đầu, mắt, mũi, má, râu + hố + mép hố, 3 sao, nhãn điểm) vẽ bằng Canvas tăng tốc phần
 *   cứng; path dựng lại chỉ khi cỡ phím đổi. Chỉ invalidate trong lúc có chuyển động (trồi lên ~0,24 s, thụt
 *   ~0,18 s, bị đập ~0,5 s); chuột đứng yên ⇒ không vẽ lại khung nào.
 * • Mỗi phím: [typed] = [MoleGame.key] (O(1)). Thả / đập / thụt dồn sang post() (sau khi phím đã tới IME). Đồng hồ:
 *   MỘT postDelayed mỗi con.
 */
@SuppressLint("ViewConstructor")
class MoleView(context: Context, private val density: Float, seed: Int = SystemClock.uptimeMillis().toInt()) : View(context) {
    /** Khung phím chữ [c] (toạ độ view này) vào [out] + trả bán kính bo; null ⇒ không ở bảng chữ. */
    var keyFrame: ((c: Char, out: RectF) -> Float?)? = null
    /** Chữ đang soạn + ứng viên thanh gợi ý — chỉ gọi lúc thả chuột. */
    var context: (() -> Pair<String, List<String>>)? = null
    /** Đập trúng (sau chạm): IME phát "éc" + ghi thống kê; trả số chuột hôm nay cho nhãn. */
    var onHit: ((streak: Int) -> Int)? = null

    val game = MoleGame(seed)
    private var generation = 0

    private enum class Phase { NONE, UP, DUCK, BONK }
    private var phase = Phase.NONE
    private var phaseStart = 0L
    private val key = RectF()
    private var keyRadius = 0f
    private var badgeText = ""

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = 0xFFFFFFFF.toInt(); isFakeBoldText = true
        setShadowLayer(1.5f * density, 0f, 1f * density, 0x8C000000.toInt())
    }
    private val clip = Path()
    private val star = Path()
    private var builtW = 0f
    private var builtH = 0f
    private var d = 0f
    private var upY = 0f
    private var downY = 0f

    /** Test: số lần thả / đập / chuột thực vẽ. */
    var debugSpawnKey: Char? = null; private set
    var debugBonks = 0; private set
    val isMoleUp: Boolean get() = game.mole != null
    val isDrawing: Boolean get() = phase != Phase.NONE
    /** Test: thời gian (ns) của lần [typed] gần nhất. */
    var debugCostNs = 0L; private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = CONSUMES_TOUCH
    override fun dispatchTouchEvent(ev: MotionEvent?) = CONSUMES_TOUCH

    private val spawnRun = Runnable { spawn() }
    private val duckRun = Runnable { duck() }

    /** Mỗi phím (KeyboardView.emit). [letter] = chữ thường a–z hoặc null. */
    fun typed(letter: Char?, tSec: Double) {
        val t0 = System.nanoTime()
        when (val ev = game.key(letter, tSec)) {
            MoleEvent.None -> {}
            MoleEvent.Spawn -> post(spawnRun)
            is MoleEvent.Hit -> post { bonk(ev.key) }
            MoleEvent.Duck -> post(duckRun)
        }
        debugCostNs = System.nanoTime() - t0
    }

    private fun now() = SystemClock.uptimeMillis() / 1000.0

    fun spawn() {
        if (!game.pending) return
        val (prefix, cands) = context?.invoke() ?: ("" to emptyList())
        val probe = RectF()
        val k = game.chooseTarget(prefix, cands) { c -> keyFrame?.invoke(c, probe) != null }
        val r = keyFrame?.invoke(k, key)
        if (r == null || key.width() < 4 || key.height() < 4) { game.cancelSpawn(); return }
        keyRadius = r
        game.place(k, now())
        debugSpawnKey = k
        if (key.width() != builtW || key.height() != builtH) build(key.width(), key.height())
        setPhase(Phase.UP)
        val g = ++generation
        postDelayed({
            if (generation == g && game.tick(now()) == MoleEvent.Duck) duck()
        }, ((MoleGame.UP_TIME + 0.02) * 1000).toLong())
    }

    fun duck() {
        generation++
        if (phase == Phase.NONE) return
        setPhase(Phase.DUCK)
    }

    fun bonk(k: Char) {
        generation++
        val today = onHit?.invoke(game.streak) ?: game.hits
        debugBonks++
        badgeText = "🐭 $today"
        if (phase == Phase.NONE) return
        setPhase(Phase.BONK)
    }

    /** Đổi bảng phím / xoay: dẹp ngay, không tính trượt. */
    fun hideNow() {
        generation++
        game.hide()
        removeCallbacks(spawnRun); removeCallbacks(duckRun)
        if (phase != Phase.NONE) { phase = Phase.NONE; invalidate() }
    }

    fun halt() = hideNow()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        halt()
    }

    private fun setPhase(p: Phase) {
        phase = p
        phaseStart = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun build(w: Float, h: Float) {
        builtW = w; builtH = h
        d = min(w * 0.92f, h * 0.86f)
        upY = h - h * 0.08f
        downY = h + d * 1.05f
        star.reset()
        val rs = max(4f * density, d * 0.16f); val ri = rs * 0.45f
        for (i in 0 until 10) {
            val a = i * Math.PI / 5 - Math.PI / 2
            val rr = if (i % 2 == 0) rs else ri
            val x = (cos(a) * rr).toFloat(); val y = (sin(a) * rr).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        text.textSize = 13f * density
    }

    override fun onDraw(canvas: Canvas) {
        if (phase == Phase.NONE) return
        val el = (SystemClock.uptimeMillis() - phaseStart) / 1000f
        var y = upY
        var sx = 1f; var sy = 1f
        var pitAlpha = 1f
        var animating = false
        var starT = -1f
        when (phase) {
            Phase.UP -> {
                val p = (el / 0.24f).coerceIn(0f, 1f)
                y = downY + (upY - downY) * backOut(p)
                pitAlpha = (el / 0.12f).coerceIn(0f, 1f)
                animating = p < 1f
            }
            Phase.DUCK -> {
                val p = (el / 0.18f).coerceIn(0f, 1f)
                y = upY + (downY - upY) * p * p
                pitAlpha = 1f - p
                if (p >= 1f) { phase = Phase.NONE; return }
                animating = true
            }
            Phase.BONK -> {
                val q = (el / 0.34f).coerceIn(0f, 1f)
                if (q < 0.35f) { val u = q / 0.35f; sx = 1f + 0.28f * u; sy = 1f - 0.45f * u }
                else { val u = (q - 0.35f) / 0.65f; sx = 1.28f - 0.13f * u; sy = 0.55f + 0.15f * u }
                val dp = ((el - 0.16f) / 0.18f).coerceIn(0f, 1f)
                y = upY + (downY - upY) * dp * dp
                pitAlpha = 1f - dp
                starT = (el / 0.5f).coerceIn(0f, 1f)
                if (el >= 0.9f) { phase = Phase.NONE; return }
                animating = true
            }
            Phase.NONE -> return
        }
        val w = key.width(); val h = key.height()
        val cx = w / 2
        // Hộp cắt theo khung phím: chuột trồi lên từ đáy phím như từ hang.
        canvas.save()
        canvas.translate(key.left, key.top)
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, keyRadius, keyRadius, Path.Direction.CW)
        canvas.clipPath(clip)
        val hw = w * 0.86f; val hh = max(6f * density, h * 0.24f)
        fill.color = argb(pitAlpha * 0.9f, HOLE)
        canvas.drawOval(cx - hw / 2, upY - hh * 0.55f, cx + hw / 2, upY + hh * 0.45f, fill)
        // Chuột: gốc = đáy giữa (bẹp quanh đáy).
        canvas.save()
        canvas.translate(cx, y)
        canvas.scale(sx, sy)
        canvas.translate(-d / 2, -d)
        fun circ(x: Float, yy: Float, r: Float, c: Int) { fill.color = c; canvas.drawCircle(x * d, yy * d, r * d, fill) }
        circ(0.22f, 0.27f, 0.22f, FUR_DARK); circ(0.78f, 0.27f, 0.22f, FUR_DARK)
        circ(0.23f, 0.29f, 0.13f, PINK); circ(0.77f, 0.29f, 0.13f, PINK)
        fill.color = FUR
        canvas.drawOval(0.13f * d, 0.26f * d, 0.87f * d, 0.98f * d, fill)
        circ(0.29f, 0.73f, 0.065f, argb(0.55f, PINK)); circ(0.71f, 0.73f, 0.065f, argb(0.55f, PINK))
        circ(0.37f, 0.56f, 0.058f, EYE); circ(0.63f, 0.56f, 0.058f, EYE)
        circ(0.385f, 0.54f, 0.02f, 0xFFFFFFFF.toInt()); circ(0.645f, 0.54f, 0.02f, 0xFFFFFFFF.toInt())
        fill.color = NOSE
        canvas.drawOval(0.445f * d, 0.658f * d, 0.555f * d, 0.742f * d, fill)
        stroke.color = argb(0.8f, 0xFF4A4452.toInt()); stroke.strokeWidth = max(0.8f * density, d * 0.02f)
        for (s in WHISKERS) canvas.drawLine(s[0] * d, s[1] * d, s[2] * d, s[3] * d, stroke)
        canvas.restore()
        fill.color = argb(pitAlpha, LIP)
        canvas.drawOval(cx - hw * 0.52f, upY - hh * 0.2f, cx + hw * 0.52f, upY + hh * 0.7f, fill)
        canvas.restore()
        // Sao + nhãn điểm (ngoài hộp cắt).
        if (starT >= 0f) {
            val top = key.top + h * 0.3f
            val dd = min(w, h)
            val e = 1f - (1f - starT) * (1f - starT)
            val a = if (starT < 0.6f) 1f else 1f - (starT - 0.6f) / 0.4f
            for (i in 0 until 3) {
                val ang = Math.toRadians(-150.0 + i * 60.0)
                val px = key.centerX() + (cos(ang) * dd * 0.75 * e).toFloat()
                val py = top + (sin(ang) * dd * 0.75 * e).toFloat()
                val sc = if (starT < 0.4f) 0.4f + 0.75f * starT / 0.4f else 1.15f - 0.25f * (starT - 0.4f) / 0.6f
                canvas.save()
                canvas.translate(px, py)
                canvas.rotate(180f * starT * if (i == 1) -1 else 1)
                canvas.scale(sc, sc)
                fill.color = argb(a, STAR); canvas.drawPath(star, fill)
                stroke.color = argb(a, STAR_EDGE); stroke.strokeWidth = density; canvas.drawPath(star, stroke)
                canvas.restore()
            }
            val bt = (el / 0.9f).coerceIn(0f, 1f)
            val ba = when { bt < 0.15f -> bt / 0.15f; bt < 0.7f -> 1f; else -> 1f - (bt - 0.7f) / 0.3f }
            text.alpha = (255 * ba).toInt()
            canvas.drawText(badgeText, key.centerX(), key.top - 8f * density + (4f - 12f * bt) * density, text)
        }
        if (animating) postInvalidateOnAnimation()
    }

    private fun backOut(p: Float): Float { val c = 1.70158f; val x = p - 1; return 1 + (c + 1) * x * x * x + c * x * x }

    companion object {
        /** Lớp chuột KHÔNG BAO GIỜ nhận chạm: phím dưới chuột vẫn gõ (test chốt). */
        const val CONSUMES_TOUCH = false
        const val FUR = 0xFFA9A3B5.toInt()
        const val FUR_DARK = 0xFF8C8699.toInt()
        const val PINK = 0xFFF4A6B8.toInt()
        const val NOSE = 0xFFEE7396.toInt()
        const val EYE = 0xFF1E1A22.toInt()
        const val HOLE = 0xFF2A170B.toInt()
        const val LIP = 0xFF5A3A22.toInt()
        const val STAR = 0xFFFFD54F.toInt()
        const val STAR_EDGE = 0xFFE0A000.toInt()
        private val WHISKERS = arrayOf(floatArrayOf(0.42f, 0.72f, 0.14f, 0.67f), floatArrayOf(0.42f, 0.75f, 0.16f, 0.8f),
            floatArrayOf(0.58f, 0.72f, 0.86f, 0.67f), floatArrayOf(0.58f, 0.75f, 0.84f, 0.8f))
        fun argb(a: Float, c: Int): Int = ((a.coerceIn(0f, 1f) * ((c ushr 24) and 0xFF)).toInt() shl 24) or (c and 0xFFFFFF)
    }
}

/** File thống kê Đập chuột (filesDir) — IME ghi, app đọc ("Hôm nay đập N con chuột 🐭"). */
object MoleStatsFile {
    fun file(ctx: Context) = java.io.File(ctx.filesDir, com.viettelex.keyboard.MoleStats.FILE_NAME)
    fun read(ctx: Context): com.viettelex.keyboard.MoleStats =
        com.viettelex.keyboard.MoleStats.decode(runCatching { file(ctx).readText() }.getOrNull())
    fun write(ctx: Context, s: com.viettelex.keyboard.MoleStats) {
        runCatching {
            val f = file(ctx)
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(s.encode())
            tmp.renameTo(f)
        }
    }
}
