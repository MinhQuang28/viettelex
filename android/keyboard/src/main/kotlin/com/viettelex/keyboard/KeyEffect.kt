package com.viettelex.keyboard

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Hiệu ứng khi bấm phím" (Plus, Phil 19/10/2026 — kiểu Laban Key) — THUẦN, song sinh
 * iOS/Keyboard/KeyEffects.swift: cùng khoá [Keys.KEY_EFFECT] (`keyEffect`), cùng id, cùng quỹ đạo.
 * "Không" (mặc định) ⇒ IME không tạo overlay (0 chi phí). Bật: chữ chèn TRƯỚC, overlay
 * (app/.../ime/KeyEffectView) vẽ ở lượt sau; ≤ [KeyEffectPool.CAP] hạt, mảng cấp sẵn — không cấp
 * phát mỗi khung hình. Tự tắt khi Trình tiết kiệm pin / tắt hiệu ứng động ([KeyEffectPolicy]).
 */
enum class KeyEffect(val id: String, val viTitle: String) {
    NONE("none", "Không"), // l10n-key
    HEART("heart", "Trái tim"), // l10n-key
    BUBBLE("bubble", "Bong bóng"), // l10n-key
    RIPPLE("ripple", "Gợn sóng"), // l10n-key
    PETAL("petal", "Cánh hoa"), // l10n-key
    FIREWORK("firework", "Pháo hoa"); // l10n-key

    val title: String get() = tr(viTitle)

    /** Mọi kiểu trừ "Không" thuộc Plus. */
    val isPlus: Boolean get() = this != NONE

    /** Số hạt mỗi lần bấm. */
    val particlesPerTap: Int get() = when (this) {
        NONE -> 0
        HEART, BUBBLE, PETAL -> 3
        RIPPLE -> 1
        FIREWORK -> 6
    }

    /** Số quỹ đạo dựng sẵn (bội của [particlesPerTap]) — xem [variant]. */
    val variantCount: Int get() = particlesPerTap * 2

    /** Quỹ đạo thứ [v] (dp, giây, radian) — y hệt iOS KeyEffect.trajectory. */
    fun trajectory(v: Int): KeyEffectTrajectory {
        val n = maxOf(variantCount, 1)
        val t = if (n > 1) (v - (n - 1) / 2.0) / ((n - 1) / 2.0) else 0.0
        return when (this) {
            NONE -> KeyEffectTrajectory(0.0, 0.0, 1.0, 1.0, 0.0, 0.0)
            HEART -> KeyEffectTrajectory(t * 24, -(46 + (v % 3) * 7.0), 0.5, 1.0 + (v % 2) * 0.15, t * 0.35,
                0.8 + (v % 3) * 0.05)
            BUBBLE -> KeyEffectTrajectory(t * 16, -(52 + (v % 3) * 8.0), 0.4, 1.0 + (v % 2) * 0.2, 0.0,
                0.9 + (v % 2) * 0.1)
            RIPPLE -> KeyEffectTrajectory(0.0, 0.0, 0.35, 1.6 + (v % 2) * 0.2, 0.0, 0.45)
            PETAL -> KeyEffectTrajectory(t * 34, 12 + (v % 3) * 8.0, 0.7, 1.0, t * 2.4 + 0.6, 0.95)
            FIREWORK -> {
                val a = v * 2 * Math.PI / n
                val d = 30.0 + (v % 2) * 6
                KeyEffectTrajectory(cos(a) * d, sin(a) * d, 1.0, 0.4, 0.0, 0.55)
            }
        }
    }

    /** Quỹ đạo của hạt [i] trong lần bấm số [seed]: trải đều, mỗi lần lệch chút. */
    fun variant(i: Int, seed: Int): Int {
        val stride = maxOf(variantCount / maxOf(particlesPerTap, 1), 1)
        return (i * stride + abs(seed) % stride) % maxOf(variantCount, 1)
    }

    companion object {
        val DEFAULT = NONE
        val IDS: Set<String> = entries.map { it.id }.toSet()
        /** Giá trị lạ / thiếu ⇒ [DEFAULT]. */
        fun of(raw: Any?): KeyEffect = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

data class KeyEffectTrajectory(
    val dx: Double, val dy: Double,
    val scaleFrom: Double, val scaleTo: Double,
    val rotation: Double,
    val duration: Double,
)

/** Cổng Plus: đi cùng quyền theme cao cấp (như ảnh nền / tuỳ chỉnh nâng cao). */
object KeyEffectGate {
    val allows: Boolean get() = PlusGate.isUnlocked(PlusFeature.PREMIUM_THEMES)
    fun allows(e: KeyEffect): Boolean = !e.isPlus || allows
}

/** Kiểu thực chạy — tắt / hết Plus / tiết kiệm pin / tắt hiệu ứng động ⇒ [KeyEffect.NONE]. */
object KeyEffectPolicy {
    fun active(setting: KeyEffect, plus: Boolean, powerSave: Boolean, animationsOff: Boolean): KeyEffect =
        if (setting == KeyEffect.NONE || !plus || powerSave || animationsOff) KeyEffect.NONE else setting
}

/** Ô pool xoay vòng: đủ [capacity] hạt thì hạt cũ nhất bị thay (gõ nhanh không dồn hạt). */
class KeyEffectPool(capacity: Int = CAP) {
    val capacity: Int = maxOf(1, capacity)
    var cursor = 0
        private set
    fun next(): Int { val i = cursor; cursor = (cursor + 1) % capacity; return i }

    companion object { const val CAP = 20 }
}

/**
 * Mô phỏng hạt cho overlay Android: mảng cấp sẵn theo [KeyEffectPool.CAP], quỹ đạo tính sẵn lúc
 * [configure] ⇒ [spawn] / [sample] không cấp phát. Sau [sample] đọc kết quả ở [sx]…[sRot].
 */
class KeyEffectParticles(capacity: Int = KeyEffectPool.CAP) {
    val capacity = maxOf(1, capacity)
    private val pool = KeyEffectPool(this.capacity)
    private val x0 = FloatArray(this.capacity)
    private val y0 = FloatArray(this.capacity)
    private val start = LongArray(this.capacity)
    private val variant = IntArray(this.capacity)
    private val alive = BooleanArray(this.capacity)
    private var trajs: Array<KeyEffectTrajectory> = emptyArray()
    private var seed = 0

    var effect: KeyEffect = KeyEffect.NONE
        private set

    fun configure(e: KeyEffect) {
        if (e == effect && trajs.size == e.variantCount) return
        effect = e
        trajs = Array(e.variantCount) { e.trajectory(it) }
        clear()
    }

    fun clear() { alive.fill(false) }

    /** Một lần bấm tại ([x], [y]) (px) lúc [nowMs]. */
    fun spawn(x: Float, y: Float, nowMs: Long) {
        seed++
        for (i in 0 until effect.particlesPerTap) {
            val s = pool.next()
            x0[s] = x; y0[s] = y; start[s] = nowMs
            variant[s] = effect.variant(i, seed)
            alive[s] = true
        }
    }

    fun aliveCount(): Int = alive.count { it }

    var sx = 0f; private set
    var sy = 0f; private set
    var sScale = 1f; private set
    var sAlpha = 1f; private set
    /** Độ quay (độ). */
    var sRot = 0f; private set

    /** Trạng thái hạt [slot] lúc [nowMs] ([density]: px mỗi dp); false = không còn bay (đã tắt). */
    fun sample(slot: Int, nowMs: Long, density: Float): Boolean {
        if (!alive[slot]) return false
        val v = variant[slot]
        if (v >= trajs.size) { alive[slot] = false; return false }
        val t = trajs[v]
        var f = ((nowMs - start[slot]) / (t.duration * 1000.0)).toFloat()
        if (f >= 1f) { alive[slot] = false; return false }
        if (f < 0f) f = 0f
        val e = 1f - (1f - f) * (1f - f)          // easeOut như iOS
        sx = x0[slot] + (t.dx * e).toFloat() * density
        sy = y0[slot] + (t.dy * e).toFloat() * density
        sScale = (t.scaleFrom + (t.scaleTo - t.scaleFrom) * f).toFloat()
        sAlpha = if (f < 0.5f) 1f else 1f - (f - 0.5f) / 0.5f
        sRot = Math.toDegrees(t.rotation * f).toFloat()
        return true
    }
}
