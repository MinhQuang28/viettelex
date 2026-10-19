package com.viettelex.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.viettelex.keyboard.ComboMeter
import com.viettelex.keyboard.EmojiWords
import com.viettelex.keyboard.FlashPulse
import com.viettelex.keyboard.GlowSprite
import com.viettelex.keyboard.InkBloom
import com.viettelex.keyboard.KeyEffect
import com.viettelex.keyboard.KeyEffectParticles
import com.viettelex.keyboard.LightningBolt
import com.viettelex.keyboard.LightningShards
import com.viettelex.keyboard.MatrixClock
import com.viettelex.keyboard.MatrixRain
import com.viettelex.keyboard.MeteorStreak
import com.viettelex.keyboard.StringPluck
import com.viettelex.keyboard.ThemeDecoration
import com.viettelex.keyboard.WaveRings

/**
 * Lớp phủ "Hiệu ứng khi bấm phím" (Plus) — song sinh iOS KeyEffectOverlay. MỘT View không nhận chạm.
 * Dải TRÊN phím (đa số kiểu): cùng khung với [BalloonView], ngay DƯỚI nó. Dải DƯỚI phím (Matrix / Mực loang /
 * Sóng nước, [KeyEffect.band]): ImeRootView chèn ngay TRƯỚC KeyboardView (trên nền / ảnh nền vẽ trong
 * onDraw của view gốc, dưới phím).
 *
 * Hiệu năng (gõ mượt là số 1):
 *  • Chỉ tồn tại khi bật (IME gắn/gỡ theo [com.viettelex.keyboard.KeyEffectPolicy]) — tắt ⇒ không có view.
 *  • [burst] chỉ ghi khung phím + `post` MỘT Runnable cấp sẵn: vẽ ở lượt sau, SAU khi phím đã chèn chữ.
 *  • Vẽ: path vector / bitmap dựng sẵn (dải Matrix, emoji, đốm mực — dựng NGOÀI main / một lần) bằng biến
 *    đổi canvas — display list ghi trên UI thread, RenderThread/GPU rasterize. Mảng trạng thái, Path, Paint
 *    cấp sẵn ⇒ không cấp phát mỗi khung hình; hết hiệu ứng thì thôi hẹn khung hình (idle = 0 CPU). Tự đếm
 *    giờ (SystemClock) — tắt hiệu ứng động hệ thống không tắt hiệu ứng phím (Phil 19/10/2026).
 */
@SuppressLint("ViewConstructor")
open class KeyEffectView(context: Context, private val density: Float) : View(context) {
    private val sim = KeyEffectParticles()
    private val paths = Array(3) { Path() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val mtx = Matrix()
    private var useFill = true
    private var useStroke = false
    private var fillAlpha = 255
    private var strokeAlpha = 255
    private var color = 0
    private var keyFill = 0xFFFFFFFF.toInt()
    private var dark = false
    private var fillColors = IntArray(0)

    /** Khung chờ (gõ chồng trong cùng lượt) — giữ tối đa [MAX_PENDING] khung mới nhất. */
    private val pendX = FloatArray(MAX_PENDING)
    private val pendY = FloatArray(MAX_PENDING)
    private val pendW = FloatArray(MAX_PENDING)
    private val pendH = FloatArray(MAX_PENDING)
    private var pendN = 0
    private var posted = false
    private val flushRun = Runnable { flush() }

    // Nháy sáng
    private val flash = FlashPulse()
    private var flashStart = Long.MIN_VALUE / 2
    // Lửa combo
    private val combo = ComboMeter()
    // Tia sét — pool 3 cú đánh, Path cấp sẵn
    private val strikeStart = LongArray(STRIKES) { Long.MIN_VALUE / 2 }
    private val strikeX = FloatArray(STRIKES)
    private val strikeY = FloatArray(STRIKES)
    private val boltPaths = Array(STRIKES) { Path() }
    private val glowRects = Array(STRIKES) { RectF() }
    private val shardPaths = Array(STRIKES) { Array(LightningShards.COUNT) { Path() } }
    private val shardCx = Array(STRIKES) { FloatArray(LightningShards.COUNT) }
    private val shardCy = Array(STRIKES) { FloatArray(LightningShards.COUNT) }
    private var strikeCursor = 0
    private var strikeSeq = 0
    private val boltPts = DoubleArray(2 * (LightningBolt.SEGMENTS + 1))
    private val shardCache = HashMap<Int, List<LightningShards.Piece>>()
    // Emoji / mực / sóng — các ô xoay vòng
    private val slotStart = LongArray(SLOTS) { Long.MIN_VALUE / 2 }
    private val slotX = FloatArray(SLOTS)
    private val slotY = FloatArray(SLOTS)
    private val slotBmp = arrayOfNulls<Bitmap>(SLOTS)
    private var slotCursor = 0
    private var pendingEmoji: String? = null
    @Volatile private var emojiBitmaps: Map<String, Bitmap> = emptyMap()
    private var blob: Bitmap? = null
    /** Đèn lồng / Sương ma: một bitmap đốm sáng mỗi màu cố định (dựng lúc đổi kiểu). */
    private var glows: Array<Bitmap> = emptyArray()
    // Rung dây — pool đoạn dây (mảng cấp sẵn, một Path dùng lại); hết 0,3 s thì thôi hẹn khung hình (idle = 0).
    /** Khe hàng phím (px, toạ độ view này) — ImeRootView đặt lúc layout, chỉ khi kiểu là Rung dây. */
    internal var stringGaps: List<ThemeDecoration.Gap> = emptyList()
        private set
    fun setStringGaps(g: List<ThemeDecoration.Gap>) { if (g != stringGaps) stringGaps = g }
    private val strStart = LongArray(StringPluck.POOL) { Long.MIN_VALUE / 2 }
    private val strX = FloatArray(StringPluck.POOL)
    private val strY = FloatArray(StringPluck.POOL)
    private val strLen = FloatArray(StringPluck.POOL)
    private val strWidth = FloatArray(StringPluck.POOL)
    private var strCursor = 0
    private val strPath = Path()
    // Matrix
    internal val matrixClock = MatrixClock()
    @Volatile private var strips: Array<Bitmap>? = null
    private var columns: Array<MatrixRain.Column> = emptyArray()

    val effect: KeyEffect get() = sim.effect

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false

    fun configure(e: KeyEffect, accent: Int, keyFill: Int = this.keyFill, dark: Boolean = this.dark) {
        if (e == sim.effect && accent == color && keyFill == this.keyFill && dark == this.dark) return
        val changed = e != sim.effect
        if (changed) halt()
        sim.configure(e)
        color = accent; this.keyFill = keyFill; this.dark = dark
        for (i in paths.indices) buildPath(e, i, paths[i])
        val opaque = accent or (0xFF shl 24)
        fill.color = opaque; stroke.color = opaque
        fillColors = e.fixedColors.map { it or (0xFF shl 24) }.toIntArray()
        when (e) {
            KeyEffect.BUBBLE -> { useFill = true; useStroke = true; fillAlpha = 46; strokeAlpha = 255; stroke.strokeWidth = 1.5f }
            KeyEffect.RIPPLE -> { useFill = false; useStroke = true; strokeAlpha = 204; stroke.strokeWidth = 2f }
            KeyEffect.METEOR -> {
                useFill = true; useStroke = !dark; fillAlpha = 255; strokeAlpha = 255; stroke.strokeWidth = 0.6f
                stroke.color = MeteorStreak.EDGE or (0xFF shl 24)
            }
            KeyEffect.SNOW -> {
                useFill = true; useStroke = !dark; fillAlpha = 255; strokeAlpha = 255; stroke.strokeWidth = 0.6f
                fillColors = intArrayOf(if (dark) 0xFFFFFFFF.toInt() else 0xFFDCEBFF.toInt())
                stroke.color = 0xFF8FB3E0.toInt()
            }
            else -> { useFill = true; useStroke = false; fillAlpha = 255 }
        }
        blob = if (e == KeyEffect.INK) renderBlob(opaque) else null
        glows = if (e.usesGlow) Array(e.fixedColors.size) { renderGlow(e, e.fixedColors[it]) } else emptyArray()
        if (changed) { shardCache.clear(); combo.reset(); prewarm(e) }
        pendN = 0
        invalidate()
    }

    /** "Emoji theo chữ": IME tra bảng ở ranh giới từ rồi xếp emoji — lần vẽ kế (phím ranh giới) bắn nó. */
    fun queueEmoji(e: String) { if (sim.effect == KeyEffect.EMOJIWORD) pendingEmoji = e }

    /** Một phím vừa chèn chữ, tâm phím ([x], [y]), cỡ [w]×[h] (px) theo toạ độ view này. */
    fun burst(x: Float, y: Float, w: Float = 0f, h: Float = 0f) {
        if (sim.effect == KeyEffect.NONE) return
        if (sim.effect == KeyEffect.EMOJIWORD && pendingEmoji == null) return   // phím chữ thường: không hẹn gì
        if (pendN == MAX_PENDING) {
            for (i in 1 until MAX_PENDING) { pendX[i - 1] = pendX[i]; pendY[i - 1] = pendY[i]; pendW[i - 1] = pendW[i]; pendH[i - 1] = pendH[i] }
            pendN--
        }
        pendX[pendN] = x; pendY[pendN] = y; pendW[pendN] = w; pendH[pendN] = h; pendN++
        if (!posted) { posted = true; post(flushRun) }
    }

    /** Bắn các khung chờ (test gọi thẳng). */
    fun flush() {
        posted = false
        if (pendN == 0) return
        val now = SystemClock.uptimeMillis()
        when (sim.effect) {
            KeyEffect.EMOJIWORD -> {
                val em = pendingEmoji
                pendingEmoji = null
                val b = em?.let { emojiBitmaps[it] ?: renderEmoji(it).also { r -> emojiBitmaps = emojiBitmaps + (it to r) } }
                if (b != null) fireSlot(pendX[pendN - 1], pendY[pendN - 1], now, b)
            }
            else -> for (i in 0 until pendN) fire(pendX[i], pendY[i], pendW[i], pendH[i], now)
        }
        pendN = 0
        postInvalidateOnAnimation()
    }

    private fun fire(x: Float, y: Float, w: Float, h: Float, now: Long) {
        when (sim.effect) {
            KeyEffect.NONE, KeyEffect.EMOJIWORD -> {}
            KeyEffect.FLASH -> if (flash.trigger(now)) flashStart = now
            KeyEffect.LIGHTNING -> fireLightning(x, y, if (w > 0f) w else DEFAULT_W * density, if (h > 0f) h else DEFAULT_H * density, now)
            KeyEffect.MATRIX -> pokeMatrix(now)
            KeyEffect.INK, KeyEffect.WAVE -> fireSlot(x, y, now, null)
            KeyEffect.STRING -> fireString(x, y, if (w > 0f) w else DEFAULT_W * density, if (h > 0f) h else DEFAULT_H * density, now)
            KeyEffect.COMBO -> {
                val lv = combo.press(now)
                if (lv > 0) sim.spawn(x, y, now, lv, ComboMeter.SCALES[minOf(lv, 3)].toFloat())
            }
            else -> sim.spawn(x, y, now)
        }
    }

    private fun fireSlot(x: Float, y: Float, now: Long, b: Bitmap?) {
        val cap = when (sim.effect) { KeyEffect.INK -> InkBloom.SLOTS; KeyEffect.WAVE -> WaveRings.SLOTS; else -> 3 }
        val s = slotCursor % cap
        slotCursor = (s + 1) % cap
        slotStart[s] = now; slotX[s] = x; slotY[s] = y; slotBmp[s] = b
    }

    private fun fireString(x: Float, y: Float, w: Float, h: Float, now: Long) {
        val s = strCursor
        strCursor = (s + 1) % StringPluck.POOL
        val (gy, gi) = if (stringGaps.isEmpty()) (y + h / 2 + StringPluck.FALLBACK_OFFSET * density).toDouble() to 0
            else StringPluck.target((y - h / 2).toDouble(), (y + h / 2).toDouble(), stringGaps)
        strStart[s] = now; strX[s] = x; strY[s] = gy.toFloat()
        strLen[s] = (StringPluck.length((w / density).toDouble()) * density).toFloat()
        strWidth[s] = ((ThemeDecoration.stringThickness(gi) + 0.4) * density).toFloat()
    }

    /** Test: số đoạn dây đang rung lúc [now] + vị trí y (px) đoạn thứ [i]. */
    internal fun stringsAlive(now: Long): Int = strStart.count { now - it in 0 until STRING_MS }
    internal fun stringY(i: Int): Float = strY[i]

    private fun fireLightning(x: Float, y: Float, w: Float, h: Float, now: Long) {
        val s = strikeCursor
        strikeCursor = (s + 1) % STRIKES
        strikeSeq++
        strikeStart[s] = now; strikeX[s] = x; strikeY[s] = y
        // Tia: từ đỉnh view (y = 0) xuống tâm phím — điểm tất định theo lần bấm (dp → px).
        LightningBolt.points((y / density).toDouble(), strikeSeq % LightningBolt.VARIANTS, boltPts)
        val p = boltPaths[s]
        p.reset()
        for (i in 0..LightningBolt.SEGMENTS) {
            val px = (boltPts[2 * i] * density).toFloat(); val py = (boltPts[2 * i + 1] * density).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        glowRects[s].set(-w / 2, -h / 2, w / 2, h / 2)
        val key = Math.round(w) * 4096 + Math.round(h)
        val pieces = shardCache[key] ?: LightningShards.pieces(w.toDouble(), h.toDouble()).also {
            if (shardCache.size > 16) shardCache.clear()
            shardCache[key] = it
        }
        for (i in 0 until LightningShards.COUNT) {
            val pc = pieces[i]; val sp = shardPaths[s][i]
            sp.reset()
            for (k in pc.xs.indices) {
                val qx = (pc.xs[k] - pc.cx).toFloat(); val qy = (pc.ys[k] - pc.cy).toFloat()
                if (k == 0) sp.moveTo(qx, qy) else sp.lineTo(qx, qy)
            }
            sp.close()
            shardCx[s][i] = pc.cx.toFloat(); shardCy[s][i] = pc.cy.toFloat()
        }
    }

    private fun pokeMatrix(now: Long) {
        val started = matrixClock.poke(now)
        if (started && columns.isEmpty() && width > 0) {
            columns = Array(MatrixRain.columnCount((width / density).toDouble())) { MatrixRain.column(it) }
        }
    }

    /** Dừng mọi thứ đang chạy (ẩn bàn phím / đổi kiểu / gỡ) — sau đó không còn khung hình nào. */
    fun halt() {
        matrixClock.stop()
        sim.clear()
        strikeStart.fill(Long.MIN_VALUE / 2)
        slotStart.fill(Long.MIN_VALUE / 2)
        strStart.fill(Long.MIN_VALUE / 2)
        flashStart = Long.MIN_VALUE / 2
        pendingEmoji = null
        pendN = 0
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        halt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (sim.effect == KeyEffect.MATRIX && w > 0) columns = Array(MatrixRain.columnCount((w / density).toDouble())) { MatrixRain.column(it) }
    }

    internal val pendingCount: Int get() = pendN
    internal val isPosted: Boolean get() = posted
    internal fun aliveCount(): Int = sim.aliveCount()
    internal val glowCount: Int get() = glows.size

    override fun onDraw(canvas: Canvas) {
        if (drawEffects(canvas)) postInvalidateOnAnimation()
    }

    /** Vẽ hiệu ứng lúc này; true = còn đang chạy (hẹn khung hình kế). */
    protected fun drawEffects(canvas: Canvas): Boolean {
        val e = sim.effect
        if (e == KeyEffect.NONE) return false
        val now = SystemClock.uptimeMillis()
        return when (e) {
            KeyEffect.FLASH -> drawFlash(canvas, now)
            KeyEffect.LIGHTNING -> drawLightning(canvas, now)
            KeyEffect.MATRIX -> drawMatrix(canvas, now)
            KeyEffect.INK -> drawInk(canvas, now)
            KeyEffect.WAVE -> drawWave(canvas, now)
            KeyEffect.STRING -> drawStrings(canvas, now)
            KeyEffect.EMOJIWORD -> drawEmoji(canvas, now)
            else -> drawParticles(canvas, now)
        }
    }

    private fun drawParticles(canvas: Canvas, now: Long): Boolean {
        var any = false
        for (s in 0 until sim.capacity) {
            if (!sim.sample(s, now, density)) continue
            any = true
            val i = sim.sIndex
            if (glows.isNotEmpty()) {
                // Đốm sáng: bitmap dựng sẵn (đã theo mật độ điểm ảnh) — chỉ dời / co giãn / mờ.
                val b = glows[i % glows.size]
                bmpPaint.alpha = (255 * sim.sAlpha).toInt().coerceIn(0, 255)
                mtx.setTranslate(-b.width / 2f, -b.height / 2f)
                mtx.postScale(sim.sScale, sim.sScale)
                mtx.postTranslate(sim.sx, sim.sy)
                canvas.drawBitmap(b, mtx, bmpPaint)
                continue
            }
            val k = sim.sScale * density
            val save = canvas.save()
            canvas.translate(sim.sx, sim.sy)
            if (sim.sRot != 0f) canvas.rotate(sim.sRot)
            canvas.scale(k, k)
            val path = paths[i % paths.size]
            if (useFill) {
                if (fillColors.isNotEmpty()) fill.color = fillColors[i % fillColors.size]
                fill.alpha = (fillAlpha * sim.sAlpha).toInt(); canvas.drawPath(path, fill)
            }
            if (useStroke) { stroke.alpha = (strokeAlpha * sim.sAlpha).toInt(); canvas.drawPath(path, stroke) }
            canvas.restoreToCount(save)
        }
        return any
    }

    private fun drawFlash(canvas: Canvas, now: Long): Boolean {
        val a = FlashPulse.alpha(now - flashStart)
        if (a <= 0.0) return false
        canvas.drawColor((color and 0xFFFFFF) or ((a * 255).toInt() shl 24))
        return true
    }

    private fun drawLightning(canvas: Canvas, now: Long): Boolean {
        var any = false
        for (s in 0 until STRIKES) {
            val t = now - strikeStart[s]
            if (t < 0 || t > LightningShards.DELAY_MS + LightningShards.DURATION_MS) continue
            any = true
            val save = canvas.save()
            canvas.translate(strikeX[s], strikeY[s])
            // Mảnh vỡ (màu phím) bay ra + xoay + mờ — chỉ trên overlay.
            val ts = t - LightningShards.DELAY_MS
            if (ts >= 0) {
                val f = ts.toFloat() / LightningShards.DURATION_MS
                val ease = 1f - (1f - f) * (1f - f)
                val a = if (f < 0.25f) 0.9f else 0.9f * (1f - (f - 0.25f) / 0.75f)
                fill.color = keyFill
                fill.alpha = (255 * a * (keyFill ushr 24) / 255f).toInt()
                stroke.color = 0xFFFFFFFF.toInt(); stroke.strokeWidth = 0.5f * density
                stroke.alpha = (255 * 0.35f * a).toInt()
                for (i in 0 until LightningShards.COUNT) {
                    val sv = canvas.save()
                    canvas.translate(shardCx[s][i] + (LightningShards.dirX(i) * LightningShards.FLIGHT * ease * density).toFloat(),
                        shardCy[s][i] + ((LightningShards.dirY(i) * LightningShards.FLIGHT + 8) * ease * density).toFloat())
                    canvas.rotate(Math.toDegrees(LightningShards.rotation(i) * f).toFloat())
                    canvas.drawPath(shardPaths[s][i], fill)
                    canvas.drawPath(shardPaths[s][i], stroke)
                    canvas.restoreToCount(sv)
                }
            }
            // Ánh chớp trắng + viền nhấn trên phím lúc tia chạm.
            val tg = t - 40
            if (tg in 0..220) {
                val f = tg / 220f
                val a = if (f < 0.25f) f / 0.25f else 1f - (f - 0.25f) / 0.75f
                fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (255 * 0.6f * a).toInt()
                val r = 6 * density
                canvas.drawRoundRect(glowRects[s], r, r, fill)
                stroke.color = color or (0xFF shl 24); stroke.strokeWidth = 2 * density; stroke.alpha = (255 * a).toInt()
                canvas.drawRoundRect(glowRects[s], r, r, stroke)
            }
            // Tia: quầng nhấn + lõi trắng, mờ trong ~180 ms.
            if (t <= LightningBolt.DURATION_MS) {
                val f = t.toFloat() / LightningBolt.DURATION_MS
                val a = if (f < 0.35f) 1f else 1f - (f - 0.35f) / 0.65f
                stroke.strokeJoin = Paint.Join.ROUND; stroke.strokeCap = Paint.Cap.ROUND
                stroke.color = color or (0xFF shl 24); stroke.strokeWidth = 5 * density; stroke.alpha = (255 * 0.45f * a).toInt()
                canvas.drawPath(boltPaths[s], stroke)
                stroke.color = 0xFFFFFFFF.toInt(); stroke.strokeWidth = 1.6f * density; stroke.alpha = (255 * a).toInt()
                canvas.drawPath(boltPaths[s], stroke)
            }
            canvas.restoreToCount(save)
        }
        return any
    }

    private fun drawMatrix(canvas: Canvas, now: Long): Boolean {
        if (!matrixClock.running) return false
        val fade = matrixClock.fade(now)
        if (fade <= 0.0) { matrixClock.stop(); return false }           // nghỉ: dừng hẳn, không hẹn khung hình
        val st = strips ?: return true
        val a = MatrixRain.maxOpacity(dark) * MatrixRain.intensity(matrixClock.presses) * fade
        bmpPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
        val hDp = (height / density).toDouble()
        for (i in columns.indices) {
            val c = columns[i]
            val top = (MatrixRain.columnTop(c, now, hDp) * density).toFloat()
            val x = (((i + 0.5) * MatrixRain.SPACING + c.jitter - MatrixRain.COLUMN_WIDTH / 2) * density).toFloat()
            canvas.drawBitmap(st[c.strip % st.size], x, top, bmpPaint)
        }
        return true
    }

    /** Đoạn dây rung: polyline sóng dừng (17 điểm) theo biên độ tắt dần — Path cấp sẵn, hết 0,3 s ⇒ false (thôi vẽ). */
    private fun drawStrings(canvas: Canvas, now: Long): Boolean {
        var any = false
        stroke.color = color or (0xFF shl 24)
        stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        for (s in 0 until StringPluck.POOL) {
            val t = now - strStart[s]
            if (t < 0 || t >= STRING_MS) continue
            any = true
            val dt = t / 1000.0
            val a = (StringPluck.amplitudeAt(dt) * density).toFloat()
            stroke.alpha = (255 * StringPluck.alphaAt(dt)).toInt().coerceIn(0, 255)
            stroke.strokeWidth = strWidth[s]
            strPath.rewind()
            val len = strLen[s]
            for (i in 0..StringPluck.POINTS) {
                val u = i.toFloat() / StringPluck.POINTS
                val px = strX[s] + (u - 0.5f) * len
                val py = strY[s] + a * Math.sin(Math.PI * u).toFloat()
                if (i == 0) strPath.moveTo(px, py) else strPath.lineTo(px, py)
            }
            canvas.drawPath(strPath, stroke)
        }
        return any
    }

    private fun drawInk(canvas: Canvas, now: Long): Boolean {
        val b = blob ?: return false
        var any = false
        for (s in 0 until InkBloom.SLOTS) {
            val t = now - slotStart[s]
            if (t < 0 || t >= InkBloom.DURATION_MS) continue
            any = true
            val f = t.toFloat() / InkBloom.DURATION_MS
            val ease = 1f - (1f - f) * (1f - f)
            val sc = (InkBloom.SCALE_FROM + (InkBloom.SCALE_TO - InkBloom.SCALE_FROM) * ease).toFloat()
            val pk = InkBloom.PEAK_ALPHA.toFloat()
            val a = if (f < 0.4f) pk - (pk * 0.3f) * f / 0.4f else pk * 0.7f * (1f - (f - 0.4f) / 0.6f)
            bmpPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
            mtx.setTranslate(-b.width / 2f, -b.height / 2f)
            mtx.postScale(sc, sc)
            mtx.postTranslate(slotX[s], slotY[s])
            canvas.drawBitmap(b, mtx, bmpPaint)
        }
        return any
    }

    private fun drawWave(canvas: Canvas, now: Long): Boolean {
        var any = false
        stroke.color = color or (0xFF shl 24)
        for (s in 0 until WaveRings.SLOTS) {
            for (k in 0 until WaveRings.RINGS) {
                val t = now - slotStart[s] - k * WaveRings.STAGGER_MS
                if (t < -WaveRings.STAGGER_MS * WaveRings.RINGS || t >= WaveRings.DURATION_MS) continue
                any = true
                if (t < 0) continue
                val f = t.toFloat() / WaveRings.DURATION_MS
                val ease = 1f - (1f - f) * (1f - f)
                val sc = (WaveRings.SCALE_FROM + (WaveRings.SCALE_TO - WaveRings.SCALE_FROM) * ease).toFloat()
                stroke.strokeWidth = 0.9f * sc * density
                stroke.alpha = (255 * 0.55f * 0.85f * (1f - f)).toInt()
                canvas.drawCircle(slotX[s], slotY[s], (WaveRings.RADIUS * sc * density).toFloat(), stroke)
            }
        }
        return any
    }

    private fun drawEmoji(canvas: Canvas, now: Long): Boolean {
        var any = false
        for (s in 0 until 3) {
            val b = slotBmp[s] ?: continue
            val t = now - slotStart[s]
            if (t < 0 || t >= EMOJI_MS) continue
            any = true
            val f = t.toFloat() / EMOJI_MS
            val ease = 1f - (1f - f) * (1f - f)
            val sc = 0.6f + 0.55f * f
            bmpPaint.alpha = (255 * (if (f < 0.6f) 1f else 1f - (f - 0.6f) / 0.4f)).toInt()
            mtx.setTranslate(-b.width / 2f, -b.height / 2f)
            mtx.postScale(sc, sc)
            mtx.postTranslate(slotX[s], slotY[s] - 64 * density * ease)
            canvas.drawBitmap(b, mtx, bmpPaint)
        }
        return any
    }

    // MARK: dựng sẵn

    /** Ảnh dựng NGOÀI main (dải Matrix, emoji) — một lần mỗi lần đổi kiểu. */
    private fun prewarm(e: KeyEffect) {
        when (e) {
            KeyEffect.MATRIX -> if (strips == null) Thread({
                val s = Array(MatrixRain.STRIP_COUNT) { renderStrip(it) }
                post { if (sim.effect == KeyEffect.MATRIX) strips = s }
            }, "vt-fx-matrix").start()
            KeyEffect.EMOJIWORD -> if (emojiBitmaps.isEmpty()) Thread({
                val m = EmojiWords.distinctEmojis.associateWith { renderEmoji(it) }
                post { if (sim.effect == KeyEffect.EMOJIWORD) emojiBitmaps = m + emojiBitmaps }
            }, "vt-fx-emoji").start()
            else -> {}
        }
        if (e != KeyEffect.MATRIX) strips = null
        if (e != KeyEffect.EMOJIWORD) emojiBitmaps = emptyMap()
        if (e == KeyEffect.MATRIX && width > 0) columns = Array(MatrixRain.columnCount((width / density).toDouble())) { MatrixRain.column(it) }
    }

    private fun renderStrip(strip: Int): Bitmap {
        val w = Math.ceil(MatrixRain.COLUMN_WIDTH * density).toInt()
        val h = Math.ceil(MatrixRain.STRIP_HEIGHT * density).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = (MatrixRain.GLYPH_SIZE * density).toFloat()
            textAlign = Paint.Align.CENTER
        }
        for (r in 0 until MatrixRain.ROWS) {
            val head = r == MatrixRain.ROWS - 1
            p.color = (if (head) MatrixRain.HEAD else MatrixRain.GREEN) or (0xFF shl 24)
            p.alpha = (255 * MatrixRain.rowAlpha(r)).toInt()
            val baseline = ((r + 1) * MatrixRain.LINE_HEIGHT * density - 3 * density).toFloat()
            c.drawText(MatrixRain.glyph(strip, r).toString(), w / 2f, baseline, p)
        }
        return b
    }

    private fun renderEmoji(e: String): Bitmap {
        val size = Math.ceil(30.0 * density).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24 * density; textAlign = Paint.Align.CENTER }
        val fm = p.fontMetrics
        Canvas(b).drawText(e, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, p)
        return b
    }

    /** Đốm mực tròn mép mềm (gradient tâm) — một lần mỗi màu. */
    private fun renderBlob(c: Int): Bitmap {
        val size = Math.ceil(InkBloom.SIZE * density).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val r = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(r, r, r, intArrayOf(c, (c and 0xFFFFFF) or (0x99 shl 24), c and 0xFFFFFF),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        Canvas(b).drawCircle(r, r, r, p)
        return b
    }

    /** Đốm sáng mềm (Đèn lồng: lõi ấm + quầng màu; Sương ma: mờ tan mép) — một lần mỗi màu. = iOS renderGlow. */
    private fun renderGlow(e: KeyEffect, rgb: Int): Bitmap {
        val size = Math.ceil(GlowSprite.SIZE * density).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val r = size / 2f
        val pos = GlowSprite.positions(e); val al = GlowSprite.alphas(e)
        val cols = IntArray(pos.size) { i ->
            val c = if (e == KeyEffect.LANTERN && i == 0) GlowSprite.CORE else rgb
            (c and 0xFFFFFF) or ((al[i] * 255).toInt() shl 24)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(r, r, r, cols, FloatArray(pos.size) { pos[it].toFloat() }, Shader.TileMode.CLAMP)
        }
        Canvas(b).drawCircle(r, r, r, p)
        return b
    }

    /** Hình vector hạt thứ [i] (dp, quanh gốc 0,0) — cùng hình iOS KeyEffectShapes. */
    private fun buildPath(e: KeyEffect, i: Int, path: Path) {
        path.reset()
        when (e) {
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
            KeyEffect.PETAL -> petal(path, 8f)
            KeyEffect.FIREWORK -> {
                for (k in 0 until 8) {
                    val a = k * Math.PI / 4 - Math.PI / 2
                    val r = if (k % 2 == 0) 5.0 else 1.6
                    val x = (Math.cos(a) * r).toFloat(); val y = (Math.sin(a) * r).toFloat()
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            KeyEffect.BLOSSOM -> if (i % 2 == 0) {
                val r = 7f; val pr = r * 0.48f
                for (k in 0 until 5) {
                    val a = k * 2 * Math.PI / 5 - Math.PI / 2
                    path.addCircle((Math.cos(a) * (r - pr)).toFloat(), (Math.sin(a) * (r - pr)).toFloat(), pr, Path.Direction.CW)
                }
            } else petal(path, 6f)
            KeyEffect.COMBO -> {
                val s = 9f
                path.moveTo(0f, -s)
                path.cubicTo(s * 0.9f, -s * 0.1f, s * 0.75f, s * 0.7f, 0f, s * 0.7f)
                path.cubicTo(-s * 0.75f, s * 0.7f, -s * 0.9f, -s * 0.1f, 0f, -s)
                path.close()
            }
            KeyEffect.METEOR -> {
                val pts = MeteorStreak.polygon()
                path.moveTo(pts[0].toFloat(), pts[1].toFloat())
                for (k in 1 until pts.size / 2) path.lineTo(pts[2 * k].toFloat(), pts[2 * k + 1].toFloat())
                path.close()
            }
            KeyEffect.SNOW -> {
                // 6 nhánh, mỗi nhánh 1 thanh + 2 ngạnh (hình chữ nhật mảnh xoay) — như iOS snowflake(6).
                val r = 6f; val w = 0.7f
                val m = Matrix(); val tmp = Path()
                for (k in 0 until 6) {
                    tmp.reset(); tmp.addRect(-w / 2, -r, w / 2, 0f, Path.Direction.CW)
                    m.setRotate(k * 60f); tmp.transform(m); path.addPath(tmp)
                    for (side in intArrayOf(-1, 1)) {
                        tmp.reset(); tmp.addRect(-w / 2, -r * 0.32f, w / 2, 0f, Path.Direction.CW)
                        m.setRotate(side * 45f); m.postTranslate(0f, -r * 0.6f); m.postRotate(k * 60f)
                        tmp.transform(m); path.addPath(tmp)
                    }
                }
            }
            else -> {}
        }
    }

    private fun petal(path: Path, s: Float) {
        path.moveTo(0f, -s)
        path.quadTo(s * 0.9f, -s * 0.1f, 0f, s)
        path.quadTo(-s * 0.9f, -s * 0.1f, 0f, -s)
        path.close()
    }

    companion object {
        const val MAX_PENDING = 4
        const val STRIKES = 3
        const val SLOTS = 4
        const val EMOJI_MS = 1100L
        const val DEFAULT_W = 34f
        /** Rung dây: thời lượng (ms) = StringPluck.DURATION. */
        val STRING_MS = Math.round(StringPluck.DURATION * 1000)
        const val DEFAULT_H = 42f
    }
}
