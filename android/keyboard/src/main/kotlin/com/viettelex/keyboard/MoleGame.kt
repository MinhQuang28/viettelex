package com.viettelex.keyboard

import java.text.Normalizer
import java.util.Calendar

/**
 * "Đập chuột" (Whack-a-mole) — trò chơi khi gõ của theme "Đập chuột" (theme JSON "game":"mole", Phil 19/10/2026).
 * Song sinh iOS Keyboard/MoleGame.swift: cùng hằng số, cùng LCG [FxRandom] ⇒ cùng chuỗi chọn phím với cùng seed
 * (test chéo MoleGameTests).
 *
 * Đang gõ, thỉnh thoảng một chú chuột thò lên từ MỘT phím chữ — đúng phím người dùng SẮP gõ (chữ đang soạn + ứng
 * viên thanh gợi ý; không có thì chữ phổ biến). Gõ trúng lúc chuột đang lên ⇒ "éc" (chỉ khi Âm thanh phím bật) +
 * bẹp + sao + điểm. Không trúng trong [UP_TIME] ⇒ thụt xuống. Phím vẫn gõ bình thường (lớp vẽ không nhận chạm).
 *
 * Luật hiệu năng: theme khác ⇒ không có view (0 chi phí). Bật: mỗi phím = [key] (vài phép so + cộng, O(1)); đoán
 * phím + vẽ chỉ lúc thả (cách nhau 2–6 s gõ), sau sự kiện chạm (post). Tắt khi Trình tiết kiệm pin / TalkBack.
 */
sealed class MoleEvent {
    object None : MoleEvent() { override fun toString() = "none" }
    /** Đến lúc thả chuột: overlay đoán phím rồi gọi [MoleGame.place] (hoặc [MoleGame.cancelSpawn]). */
    object Spawn : MoleEvent() { override fun toString() = "spawn" }
    /** Đập trúng chuột trên phím này. */
    data class Hit(val key: Char) : MoleEvent()
    /** Chuột thụt xuống không bị đập (hết giờ / nghỉ gõ) — chuỗi trúng về 0. */
    object Duck : MoleEvent() { override fun toString() = "duck" }
}

/** Máy trạng thái theo mốc thời gian (giây) — thuần, test được. */
class MoleGame(seed: Int) {
    companion object {
        /** Giá trị "game" của theme JSON bật trò này. */
        const val THEME_GAME = "mole"
        const val UP_TIME = 1.5
        const val IDLE_AFTER = 3.0
        const val SPAWN_MIN = 2.0
        const val SPAWN_MAX = 6.0
        /** Khe giữa hai phím tính vào "thời gian gõ" tối đa chừng này. */
        const val GAP_CAP = 1.0
    }

    val rng = FxRandom(seed)
    /** Chữ (a–z) của chuột đang lên; null = không có. */
    var mole: Char? = null; private set
    var moleSince = 0.0; private set
    var lastKey: Double? = null; private set
    var typed = 0.0; private set
    var nextSpawn = 0.0; private set
    var pending = false; private set
    var hits = 0; private set
    var streak = 0; private set
    var bestStreak = 0; private set
    var misses = 0; private set
    var spawns = 0; private set

    init { nextSpawn = drawGap() }

    private fun drawGap(): Double = SPAWN_MIN + (SPAWN_MAX - SPAWN_MIN) * rng.next()

    /** Một phím lúc [t]. [letter] = chữ thường a–z hoặc null (phím khác — vẫn là đang gõ, không bao giờ đập). */
    fun key(letter: Char?, t: Double): MoleEvent {
        val gap = lastKey?.let { t - it } ?: 0.0
        val idle = gap >= IDLE_AFTER
        lastKey = t
        val m = mole
        if (m != null) {
            if (t - moleSince >= UP_TIME || idle) { miss(); return MoleEvent.Duck }
            if (letter == null || letter != m) return MoleEvent.None
            mole = null
            hits++; streak++
            if (streak > bestStreak) bestStreak = streak
            rearm()
            return MoleEvent.Hit(m)
        }
        if (idle) { typed = 0.0; return MoleEvent.None }
        typed += gap.coerceIn(0.0, GAP_CAP)
        if (pending || typed < nextSpawn) return MoleEvent.None
        pending = true
        return MoleEvent.Spawn
    }

    fun place(k: Char, t: Double) {
        if (!pending) return
        pending = false
        mole = k; moleSince = t; spawns++
    }

    fun cancelSpawn() { pending = false; rearm() }

    /** Đồng hồ (MỘT lượt hẹn sau [UP_TIME] mỗi con): hết giờ hoặc nghỉ gõ ⇒ thụt (trượt). */
    fun tick(t: Double): MoleEvent {
        if (mole == null) return MoleEvent.None
        val idle = lastKey?.let { t - it >= IDLE_AFTER } ?: true
        if (t - moleSince >= UP_TIME - 1e-9 || idle) { miss(); return MoleEvent.Duck }
        return MoleEvent.None
    }

    /** Đổi bảng phím / ẩn bàn phím: dẹp chuột, không tính trượt. */
    fun hide() {
        val had = mole != null || pending
        mole = null; pending = false
        if (had) rearm()
    }

    fun chooseTarget(prefix: String, candidates: List<String>, allowed: (Char) -> Boolean = { true }): Char =
        MoleTarget.next(prefix, candidates, rng, allowed)

    /** Test: coi như đến lúc thả, giữ nguyên điểm. */
    fun debugArm() { mole = null; pending = true }

    private fun miss() { mole = null; streak = 0; misses++; rearm() }
    private fun rearm() { typed = 0.0; nextSpawn = drawGap() }
}

/** Đoán phím sắp gõ (thuần) — chỉ gọi lúc thả chuột. */
object MoleTarget {
    /** Chữ phổ biến tiếng Việt (lặp = trọng số) — iOS common y hệt. */
    val COMMON: List<Char> = "nnnhhhaaattccciiggoommuuyed".toList()

    /** Bỏ dấu + thường hoá ("Đường" → "duong"); trùng iOS fold. */
    fun fold(s: String): String {
        val n = Normalizer.normalize(s.lowercase().replace('đ', 'd'), Normalizer.Form.NFD)
        val b = StringBuilder(n.length)
        for (ch in n) if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) b.append(ch)
        return b.toString()
    }

    fun isAZ(c: Char) = c in 'a'..'z'

    fun next(prefix: String, candidates: List<String>, rng: FxRandom, allowed: (Char) -> Boolean = { true }): Char {
        val p = fold(prefix)
        for (c in candidates) {
            val f = fold(c)
            if (f.length <= p.length || !f.startsWith(p)) continue
            val ch = f[p.length]
            if (isAZ(ch) && allowed(ch)) return ch
        }
        val pool = COMMON.filter(allowed)
        val from = if (pool.isEmpty()) COMMON else pool
        val i = minOf(from.size - 1, (rng.next() * from.size).toInt())
        return from[maxOf(0, i)]
    }
}

/**
 * Thống kê: số chuột đập HÔM NAY, chuỗi dài nhất, tổng — cùng khoá iOS (App Group). Android: file nhỏ [FILE_NAME]
 * (IME ghi trên worker, app đọc khi mở trang) — KHÔNG ghi vào prefs cài đặt (tránh bỏ cache cài đặt mỗi lần đập +
 * đè map giữa hai process).
 */
data class MoleStats(val day: Int = 0, val today: Int = 0, val best: Int = 0, val total: Int = 0) {
    companion object {
        const val DAY_KEY = "moleDay"
        const val TODAY_KEY = "moleToday"
        const val BEST_KEY = "moleBest"
        const val TOTAL_KEY = "moleTotal"
        const val FILE_NAME = "mole-stats.txt"

        /** "day today best total"; hỏng / thiếu ⇒ rỗng. */
        fun decode(s: String?): MoleStats {
            val p = s?.trim()?.split(' ')?.mapNotNull { it.toIntOrNull() } ?: return MoleStats()
            return if (p.size == 4) MoleStats(p[0], p[1], p[2], p[3]) else MoleStats()
        }

        fun dayNumber(c: Calendar = Calendar.getInstance()): Int =
            c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)

        fun load(get: (String) -> Any?): MoleStats {
            fun i(k: String) = (get(k) as? Number)?.toInt() ?: 0
            return MoleStats(i(DAY_KEY), i(TODAY_KEY), i(BEST_KEY), i(TOTAL_KEY))
        }
    }

    fun count(on: Int): Int = if (on == day) today else 0

    fun record(hits: Int, streak: Int, day: Int): MoleStats {
        val sameDay = day == this.day
        return MoleStats(day, (if (sameDay) today else 0) + hits, maxOf(best, streak), total + hits)
    }

    fun encode(): String = "$day $today $best $total"

    fun save(put: (String, Int) -> Unit) {
        put(DAY_KEY, day); put(TODAY_KEY, today); put(BEST_KEY, best); put(TOTAL_KEY, total)
    }
}

/** Bật / tắt (thuần): theme có "game":"mole" + Plus + không tiết kiệm pin + không TalkBack. */
object MolePolicy {
    fun active(game: String?, plus: Boolean, powerSave: Boolean, talkBack: Boolean): Boolean =
        game == MoleGame.THEME_GAME && plus && !powerSave && !talkBack

    /** "Éc" chỉ phát khi Âm thanh phím (đường âm riêng) đang bật. */
    fun shouldSqueak(keySound: Boolean): Boolean = keySound
}
