package com.viettelex.keyboard

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * "Bạn đồng hành" (Keyboard buddy, Plus, Phil 19/10/2026): nhân vật nhỏ ở góc phải thanh gợi ý — gõ thì
 * nhảy (gõ càng nhanh càng sung), ngừng ≥ 3 s thì ngủ; phản ứng theo từ vừa chốt (giận / thả tim / cười /
 * ngạc nhiên / buồn), hoảng khi xoá dồn; trang phục mùa lễ. Phần THUẦN — song sinh iOS/Keyboard/KeyboardBuddy.swift:
 * cùng khoá [Keys.KEYBOARD_BUDDY] (`keyboardBuddy`), cùng id, cùng máy trạng thái, cùng bậc, cùng hàm dáng, cùng
 * bảng hình (test số chung [BuddyRig.checksum]).
 *
 * Luật hiệu năng: Tắt (mặc định) / chưa Plus ⇒ IME không tạo view (0 chi phí). Bật: mỗi phím chỉ ghi giờ vào vòng
 * 16 ô + đếm (không cấp phát — trạng thái là object dựng sẵn); ranh giới từ tra bảng O(1). View
 * (app/.../ime/BuddyView) chỉ vẽ khung hình khi có chuyển động; ngủ yên / đứng yên = 0 khung hình.
 * Trình tiết kiệm pin ⇒ chỉ dáng tĩnh.
 */

// MARK: cài đặt

enum class BuddyCharacter(val id: String, val viTitle: String) {
    OFF("off", "Tắt"), // l10n-key
    GIRL("girl", "Cô gái"), // l10n-key
    BOY("boy", "Chàng trai"), // l10n-key
    CAT("cat", "Mèo"), // l10n-key
    CHICK("chick", "Gà con"); // l10n-key

    val title: String get() = tr(viTitle)
    val isPlus: Boolean get() = this != OFF

    companion object {
        val DEFAULT = OFF
        val IDS: Set<String> = entries.map { it.id }.toSet()
        fun of(raw: Any?): BuddyCharacter = entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}

/** Cổng Plus: đi cùng quyền giao diện cao cấp (như hiệu ứng khi bấm phím). */
object BuddyGate {
    val allows: Boolean get() = PlusGate.isUnlocked(PlusFeature.PREMIUM_THEMES)
    fun allows(c: BuddyCharacter): Boolean = !c.isPlus || allows
}

object BuddyPolicy {
    /** Tắt / hết Plus ⇒ [BuddyCharacter.OFF] (không tạo view). */
    fun active(setting: BuddyCharacter, plus: Boolean): BuddyCharacter =
        if (setting == BuddyCharacter.OFF || !plus) BuddyCharacter.OFF else setting

    /** Trạng thái HIỂN THỊ: tiết kiệm pin ⇒ chỉ dáng tĩnh (không nhảy / phản ứng / "z z"). */
    fun visual(p: BuddyPhase, lowPower: Boolean): BuddyPhase {
        if (!lowPower) return p
        return when (p) {
            is BuddyPhase.Asleep -> BuddyPhase.Asleep.QUIET
            else -> BuddyPhase.Awake
        }
    }

    fun animates(p: BuddyPhase): Boolean = when (p) {
        is BuddyPhase.Dancing, BuddyPhase.Cheering, is BuddyPhase.React -> true
        is BuddyPhase.Asleep -> p.zzz
        BuddyPhase.Awake -> false
    }
}

// MARK: tốc độ gõ → bậc

/** = iOS BuddyTempo: số phím trong 2 s → bậc 0…3 (ngưỡng như Lửa combo), trễ xuống 2. */
object BuddyTempo {
    const val WINDOW = 2.0
    val THRESHOLDS = intArrayOf(6, 10, 14)
    const val HYSTERESIS = 2
    const val TIERS = 4

    fun raw(n: Int, slack: Int = 0): Int { var lv = 0; for (th in THRESHOLDS) if (n >= th - slack) lv++; return lv }
    fun tier(count: Int, previous: Int): Int = max(raw(count), min(previous, raw(count, HYSTERESIS)))
}

// MARK: máy trạng thái

/** Trạng thái — object dựng sẵn (đường phím không cấp phát). */
sealed class BuddyPhase {
    class Asleep private constructor(val zzz: Boolean) : BuddyPhase() {
        override fun toString() = "Asleep(zzz=$zzz)"
        companion object { val QUIET = Asleep(false); val ZZZ = Asleep(true); fun of(z: Boolean) = if (z) ZZZ else QUIET }
    }
    object Awake : BuddyPhase() { override fun toString() = "Awake" }
    class Dancing private constructor(val tier: Int) : BuddyPhase() {
        override fun toString() = "Dancing($tier)"
        companion object { private val all = Array(BuddyTempo.TIERS) { Dancing(it) }; fun of(t: Int) = all[t.coerceIn(0, BuddyTempo.TIERS - 1)] }
    }
    object Cheering : BuddyPhase() { override fun toString() = "Cheering" }
    class React private constructor(val reaction: BuddyReaction) : BuddyPhase() {
        override fun toString() = "React($reaction)"
        companion object { private val all = BuddyReaction.entries.map { React(it) }; fun of(r: BuddyReaction) = all[r.ordinal] }
    }
}

/** = iOS BuddyBrain (cùng hằng, cùng luật). */
class BuddyBrain {
    companion object {
        const val TAIL = 0.6
        const val SLEEP_AFTER = 3.0
        const val ZZZ = 2.0
        const val CHEER = 0.6
        const val CAPACITY = 16
        const val PANIC_AFTER = 8
        const val DELETE_GAP = 0.5
        private const val NONE = -Double.MAX_VALUE
    }

    private val times = DoubleArray(CAPACITY) { NONE }
    private var head = 0
    var lastKey = NONE; private set
    var tier = 0; private set
    var cheerUntil = NONE; private set
    var reaction: BuddyReaction? = null; private set
    var reactUntil = NONE; private set
    /** Tăng mỗi lần BẮT ĐẦU một phản ứng — liên tiếp ⇒ diễn lại từ đầu (không chồng). */
    var reactSerial = 0; private set
    var deleteRun = 0; private set
    private var lastDelete = NONE

    fun press(t: Double) { deleteRun = 0; tap(t) }

    private fun tap(t: Double) {
        if (t - lastKey > BuddyTempo.WINDOW) resetRing()
        lastKey = t
        times[head] = t
        head = (head + 1) % CAPACITY
        tier = BuddyTempo.tier(count(t), tier)
    }

    fun cheer(t: Double) {
        if (t - lastKey > BuddyTempo.WINDOW) resetRing()
        lastKey = max(lastKey, t)
        cheerUntil = t + CHEER
    }

    /** Phản ứng theo từ — không tính là phím (đang ngủ: thức, diễn xong ngủ tiếp). Mới nhất thắng. */
    fun react(r: BuddyReaction, t: Double) {
        reaction = r
        reactUntil = t + r.duration
        reactSerial++
    }

    /** Phím ⌫ (cả lặp / xoá theo từ): quá [PANIC_AFTER] liên tiếp ⇒ hoảng tới 0,8 s sau phím cuối. */
    fun delete(t: Double) {
        if (t - lastDelete > DELETE_GAP) deleteRun = 0
        lastDelete = t
        deleteRun++
        tap(t)
        if (deleteRun <= PANIC_AFTER) return
        if (!(reaction == BuddyReaction.PANIC && t < reactUntil)) reactSerial++
        reaction = BuddyReaction.PANIC
        reactUntil = t + BuddyReaction.PANIC.duration
    }

    fun count(t: Double): Int { var n = 0; for (x in times) if (x <= t && t - x <= BuddyTempo.WINDOW) n++; return n }

    fun phase(t: Double): BuddyPhase {
        val r = reaction
        if (t < reactUntil && r != null) return BuddyPhase.React.of(r)
        if (lastKey <= NONE) return BuddyPhase.Asleep.QUIET
        if (t < cheerUntil) return BuddyPhase.Cheering
        val dt = t - lastKey
        if (dt < TAIL) return BuddyPhase.Dancing.of(tier)
        if (dt < SLEEP_AFTER) return BuddyPhase.Awake
        return BuddyPhase.Asleep.of(dt < SLEEP_AFTER + ZZZ)
    }

    /** Lúc trạng thái tự đổi (không có phím mới); null = ngủ yên, khỏi hẹn. */
    fun nextDeadline(t: Double): Double? {
        var best: Double? = if (reactUntil > t) reactUntil else null
        if (lastKey <= NONE) return best
        for (d in doubleArrayOf(cheerUntil, lastKey + TAIL, lastKey + SLEEP_AFTER, lastKey + SLEEP_AFTER + ZZZ))
            if (d > t) best = if (best == null) d else min(best, d)
        return best
    }

    private fun resetRing() { times.fill(NONE); head = 0; tier = 0 }
}

// MARK: dáng

/** Kênh chuyển động (đơn vị thiết kế + radian) — = iOS BuddyPose. Biến được (tái dùng mỗi khung hình). */
class BuddyPose(
    var dx: Double = 0.0, var dy: Double = 0.0, var rot: Double = 0.0, var sx: Double = 1.0,
    var head: Double = 0.0, var armL: Double = 0.0, var armR: Double = 0.0, var legL: Double = 0.0,
    var legR: Double = 0.0, var tail: Double = 0.0,
) {
    fun set(o: BuddyPose): BuddyPose {
        dx = o.dx; dy = o.dy; rot = o.rot; sx = o.sx; head = o.head; armL = o.armL; armR = o.armR
        legL = o.legL; legR = o.legR; tail = o.tail; return this
    }
    fun reset(): BuddyPose { dx = 0.0; dy = 0.0; rot = 0.0; sx = 1.0; head = 0.0; armL = 0.0; armR = 0.0; legL = 0.0; legR = 0.0; tail = 0.0; return this }
    fun isRest(eps: Double = 1e-9): Boolean = abs(dx) < eps && abs(dy) < eps && abs(rot) < eps && abs(sx - 1) < eps &&
        abs(head) < eps && abs(armL) < eps && abs(armR) < eps && abs(legL) < eps && abs(legR) < eps && abs(tail) < eps
    override fun equals(other: Any?): Boolean = other is BuddyPose && dx == other.dx && dy == other.dy && rot == other.rot &&
        sx == other.sx && head == other.head && armL == other.armL && armR == other.armR && legL == other.legL &&
        legR == other.legR && tail == other.tail
    override fun hashCode(): Int = (dx + dy * 3 + armL * 7).hashCode()
    override fun toString() = "Pose(dx=$dx dy=$dy rot=$rot sx=$sx head=$head armL=$armL armR=$armR legL=$legL legR=$legR tail=$tail)"
}

object BuddyDance {
    val BEATS = doubleArrayOf(0.62, 0.5, 0.42, 0.36)
    val ENERGY = doubleArrayOf(0.35, 0.6, 0.85, 1.0)
    const val BEATS_PER_LOOP = 4.0
    const val BLEND = 0.25

    fun clamp(t: Int) = t.coerceIn(0, BuddyTempo.TIERS - 1)
    fun loopLength(t: Int) = BEATS[clamp(t)] * BEATS_PER_LOOP

    data class Style(val armL: Double, val armR: Double, val armAmp: Double, val alternate: Boolean,
                     val kick: Double, val bounce: Double, val step: Double)

    fun pose(s: Style, tier: Int, p: Double, out: BuddyPose = BuddyPose()): BuddyPose {
        val t = clamp(tier)
        val e = ENERGY[t]
        val pp = p - floor(p)
        val beats = pp * BEATS_PER_LOOP
        val bi = floor(beats).toInt()
        val bf = beats - bi
        val two = 2 * Math.PI
        val s2 = sin(two * pp * 2)
        out.dy = -s.bounce * e * abs(sin(Math.PI * bf))
        out.dx = s.step * e * sin(two * pp)
        out.rot = 0.1 * e * s2
        out.sx = if (t == BuddyTempo.TIERS - 1 && bi == 3) cos(two * bf) else 1.0
        out.head = 0.12 * e * s2
        out.armL = s.armL * e + s.armAmp * e * s2
        out.armR = -(s.armR * e + s.armAmp * e * sin(two * pp * 2 + (if (s.alternate) Math.PI else 0.0)))
        out.legL = s.kick * e * max(0.0, s2)
        out.legR = -s.kick * e * max(0.0, -s2)
        out.tail = 0.35 * e * sin(two * pp * 4)
        return out
    }

    fun cheer(s: Style, f: Double, out: BuddyPose = BuddyPose()): BuddyPose {
        val k = sin(Math.PI * f.coerceIn(0.0, 1.0))
        out.reset()
        out.dy = -s.bounce * 1.4 * k
        out.armL = 2.4 * k; out.armR = -2.4 * k
        out.legL = 0.4 * k; out.legR = -0.4 * k
        out.tail = 0.5 * k
        return out
    }

    /** = iOS BuddyDance.react. */
    fun react(r: BuddyReaction, s: Style, f: Double, out: BuddyPose = BuddyPose()): BuddyPose {
        val ff = f.coerceIn(0.0, 1.0)
        val pi = Math.PI
        val a = if (r == BuddyReaction.PANIC) min(1.0, ff / 0.15 + 1) else minOf(1.0, ff / 0.15, (1 - ff) / 0.15)
        out.reset()
        when (r) {
            BuddyReaction.LOVE -> {
                out.dy = -s.bounce * 0.5 * a * abs(sin(3 * pi * ff))
                out.armL = -0.55 * a; out.armR = 0.55 * a
                out.head = 0.18 * a * sin(2 * pi * ff)
                out.tail = 0.4 * a * sin(4 * pi * ff)
            }
            BuddyReaction.ANGRY -> {
                val st = sin(6 * pi * ff)
                out.dy = -s.bounce * 0.25 * a * abs(st)
                out.legL = 0.35 * a * max(0.0, st); out.legR = -0.35 * a * max(0.0, -st)
                out.armL = 0.35 * a; out.armR = -0.35 * a
                out.head = 0.12 * a * sin(10 * pi * ff)
                out.tail = -0.4 * a
            }
            BuddyReaction.LAUGH -> {
                out.dx = 2 * a * sin(16 * pi * ff)
                out.dy = -s.bounce * 0.2 * a * abs(sin(8 * pi * ff))
                out.armL = -0.7 * a; out.armR = 0.7 * a
                out.head = 0.1 * a * sin(8 * pi * ff)
                out.tail = 0.3 * a * sin(8 * pi * ff)
            }
            BuddyReaction.SURPRISED -> {
                out.dy = if (ff < 0.4) -s.bounce * 1.6 * sin(pi * ff / 0.4) else 0.0
                out.armL = 1.3 * a; out.armR = -1.3 * a
                out.legL = 0.3 * a; out.legR = -0.3 * a
                out.tail = -0.5 * a
            }
            BuddyReaction.SAD -> {
                out.dy = s.bounce * 0.6 * a
                out.head = 0.3 * a
                out.armL = -0.35 * a; out.armR = 0.35 * a
                out.tail = 0.6 * a
            }
            BuddyReaction.PANIC -> {
                out.dx = 1.5 * a * sin(20 * pi * ff)
                out.armL = 2.6 * a; out.armR = -2.6 * a
                out.head = 0.08 * a * sin(20 * pi * ff)
                out.dy = -s.bounce * 0.15 * a * abs(sin(10 * pi * ff))
            }
        }
        return out
    }

    fun happyEyes(r: BuddyReaction) = r == BuddyReaction.LOVE || r == BuddyReaction.LAUGH
}

// MARK: phản ứng theo từ

/** Thứ tự = ƯU TIÊN khi một từ khớp nhiều bảng: giận > thả tim > cười > ngạc nhiên > buồn. = iOS BuddyReaction. */
enum class BuddyReaction(val duration: Double) {
    ANGRY(1.2), LOVE(1.2), LAUGH(1.3), SURPRISED(1.0), SAD(1.5), PANIC(0.8)
}

/** = iOS BuddyWords: cùng bảng, cùng luật strict / gộp chữ lặp / mẫu cười-buồn. */
object BuddyWords {
    private fun e(f: String, strict: Boolean = false) = f to strict
    val LOVE = listOf(
        e("yêu"), e("iu"), e("thương"), e("nhớ", true), e("love"), e("luv"), e("lub"), e("miss"), e("cưng", true),
        e("thích"), e("crush"), e("tim"), e("hun"), e("thơm", true), e("honey"), e("baby"), e("anh yêu"), e("em yêu"),
        e("thương thương"), e("nhớ nhớ", true), e("muah"), e("chụt", true), e("xoxo"), e("darling"), e("lovely"),
    )
    val LAUGH = listOf(
        e("haha"), e("hihi"), e("hehe"), e("hoho"), e("kkk"), e("kaka"), e("keke"), e("hjhj"), e("lol"), e("lmao"),
        e("rofl"), e("=))"), e(":))"), e("😂"), e("🤣"), e("cười", true),
    )
    val SURPRISED = listOf(
        e("wow"), e("ôi", true), e("trời ơi"), e("omg"), e("ủa", true), e("hả", true), e("what"), e("wtf"),
        e("ồ", true), e("á", true), e("woa"), e("whoa"),
    )
    val SAD = listOf(
        e("buồn"), e("khóc"), e("huhu"), e("hic"), e("hix"), e("sad"), e(":("), e("chán", true), e("mệt mỏi"),
        e("😢"), e("😭"),
    )

    private class Table(e: List<Pair<String, Boolean>>) {
        val exact = e.map { it.first }.toHashSet()
        val folded = e.filter { !it.second }.map { fold(it.first) }.toHashSet()
        val squeezed = folded.map { squeeze(it) }.filter { it.length >= 3 }.toHashSet()
        fun has(k: String): Boolean {
            if (k.isEmpty()) return false
            if (k in exact) return true
            val f = fold(k)
            if (f in folded) return true
            val q = squeeze(f)
            return q.length >= 3 && q in squeezed
        }
    }
    private val tables: List<Pair<BuddyReaction, Table>> by lazy {
        listOf(BuddyReaction.LOVE to Table(LOVE), BuddyReaction.LAUGH to Table(LAUGH),
            BuddyReaction.SURPRISED to Table(SURPRISED), BuddyReaction.SAD to Table(SAD))
    }

    fun fold(s: String): String = EmojiWords.fold(s)

    fun squeeze(s: String): String {
        val b = StringBuilder(s.length)
        var last: Int = -1
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp != last) b.appendCodePoint(cp)
            last = cp
            i += Character.charCount(cp)
        }
        return b.toString()
    }

    fun clean(s: String): String {
        val t = s.trim().lowercase()
        val letters = t.trim { !it.isLetter() }
        return letters.ifEmpty { t }
    }

    fun isLaughPattern(f: String): Boolean {
        if (f.length >= 3 && f.all { it == 'k' }) return true
        if (f.length >= 3 && (f.startsWith("=)") || f.startsWith(":)")) && f.drop(2).all { it == ')' }) return true
        val units = setOf("ha", "he", "hi", "ho", "hj", "ka", "ke")
        if (f.length < 4) return false
        var i = 0; var n = 0
        while (i + 1 < f.length && f.substring(i, i + 2) in units) { i += 2; n++ }
        return n >= 2 && f.length - i <= 1
    }

    fun isSadPattern(f: String): Boolean {
        if (f.length >= 2 && f.startsWith(":(") && f.drop(2).all { it == '(' }) return true
        for (u in listOf("hu", "hic", "hix")) {
            if (f.length < u.length * 2 || f.length % u.length != 0) continue
            if ((f.indices step u.length).all { f.startsWith(u, it) }) return true
        }
        return false
    }

    /** Phản ứng cho từ vừa chốt — tục / cặp / bộ ba ([SensitiveWords], cùng bảng bộ lọc gợi ý) ⇒ giận, KHÔNG
     *  phụ thuộc công tắc lọc. Null = không. */
    fun reaction(word: String, prev: String?, prev2: String? = null): BuddyReaction? {
        val w = clean(word)
        if (w.isEmpty() || w.length > 24) return null
        val p = prev?.let(::clean)?.ifEmpty { null }
        val p2 = prev2?.let(::clean)?.ifEmpty { null }
        if (w in SensitiveWords.set || SensitiveWords.isPair(p, w) || SensitiveWords.isTriple(p2, p, w)) return BuddyReaction.ANGRY
        val f = fold(w)
        for ((r, t) in tables) {
            if (p != null && t.has("$p $w")) return r
            if (t.has(w)) return r
            if (r == BuddyReaction.LAUGH && isLaughPattern(f)) return r
            if (r == BuddyReaction.SAD && isSadPattern(f)) return r
        }
        return null
    }

    val GRAWLIX_SYMBOLS = charArrayOf('#', '$', '%', '&', '*', '!', '@')
    fun grawlix(seed: Int, count: Int = 4): String {
        val r = FxRandom(seed)
        val b = StringBuilder()
        var last = ' '
        while (b.length < count) {
            val c = GRAWLIX_SYMBOLS[min((r.next() * GRAWLIX_SYMBOLS.size).toInt(), GRAWLIX_SYMBOLS.size - 1)]
            if (c == last) continue
            b.append(c); last = c
        }
        return b.toString()
    }
}

/** = iOS BuddyFX: hạt (≤ 8) + "biển" cố định, toạ độ px/pt trong ô nhân vật — luôn trọn trong ô. */
object BuddyFX {
    const val MAX_PARTICLES = 8
    const val PARTICLE_SIZE = 7.0
    const val PARTICLE_DURATION = 1.0
    const val HEART_COLOR = 0xFF5C8A
    const val TEAR_COLOR = 0x6EC6FF
    const val RAIN_COLOR = 0x7FA7D9
    const val CLOUD_COLOR = 0xA9B1BD
    const val ANGRY_RED = 0xFF3B30
    val PANIC_GLYPHS = listOf("a", "b", "c", "x", "?")

    class Particle(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val delay: Double) {
        override fun toString() = "($x0,$y0)->($x1,$y1)+$delay"
    }

    fun count(r: BuddyReaction): Int = when (r) {
        BuddyReaction.LOVE -> 5
        BuddyReaction.LAUGH -> 4
        BuddyReaction.SAD -> 3
        BuddyReaction.PANIC -> 3
        BuddyReaction.ANGRY, BuddyReaction.SURPRISED -> 0
    }

    fun particle(r: BuddyReaction, i: Int, w: Double, h: Double): Particle {
        val n = count(r)
        val rad = PARTICLE_SIZE / 2
        val t = if (n > 1) i.toDouble() / (n - 1) else 0.5
        val x0: Double; val y0: Double; val x1: Double; val y1: Double; val delay: Double
        when (r) {
            BuddyReaction.LOVE -> {
                x0 = w / 2 + (t - 0.5) * w * 0.3; y0 = h * 0.55
                x1 = rad + (w - 2 * rad) * (0.1 + 0.8 * t); y1 = rad + (h * 0.35) * ((i * 7) % 3) / 2.0
                delay = i * 0.08
            }
            BuddyReaction.LAUGH -> {
                val side = if (i % 2 == 0) -1.0 else 1.0
                x0 = w / 2 + side * w * 0.12; y0 = h * 0.25
                x1 = w / 2 + side * w * (0.36 + 0.06 * (i / 2)); y1 = h * (0.35 + 0.12 * (i / 2))
                delay = i * 0.15
            }
            BuddyReaction.SAD -> {
                x0 = w * (0.3 + 0.2 * i); y0 = h * 0.18; x1 = x0; y1 = h * 0.5; delay = i * 0.3
            }
            BuddyReaction.PANIC -> {
                x0 = w / 2; y0 = h * 0.6; x1 = rad + (w - 2 * rad) * t; y1 = rad + h * 0.1 * (i % 2); delay = i * 0.1
            }
            BuddyReaction.ANGRY, BuddyReaction.SURPRISED -> { x0 = w / 2; y0 = h / 2; x1 = x0; y1 = y0; delay = 0.0 }
        }
        fun cx(x: Double) = min(max(x, rad), w - rad)
        fun cy(y: Double) = min(max(y, rad), h - rad)
        return Particle(cx(x0), cy(y0), cx(x1), cy(y1), delay)
    }

    fun badge(r: BuddyReaction, w: Double, h: Double): BuddyRect? = when (r) {
        BuddyReaction.ANGRY -> BuddyRect(0.0, 0.0, w, min(13.0, h * 0.32))
        BuddyReaction.SURPRISED -> BuddyRect(w - 9, 1.0, 8.0, min(14.0, h * 0.36))
        BuddyReaction.SAD -> BuddyRect(w * 0.18, 0.0, w * 0.64, min(10.0, h * 0.24))
        else -> null
    }

    private fun f(v: Double) = String.format(Locale.US, "%.2f", v)

    fun heartPath(cx: Double, cy: Double, k: Double): String =
        "M${f(cx)} ${f(cy + k * 0.9)} Q${f(cx - k * 1.4)} ${f(cy + k * 0.1)} ${f(cx - k * 0.8)} ${f(cy - k * 0.6)} " +
            "Q${f(cx - k * 0.3)} ${f(cy - k * 1.1)} ${f(cx)} ${f(cy - k * 0.4)} " +
            "Q${f(cx + k * 0.3)} ${f(cy - k * 1.1)} ${f(cx + k * 0.8)} ${f(cy - k * 0.6)} " +
            "Q${f(cx + k * 1.4)} ${f(cy + k * 0.1)} ${f(cx)} ${f(cy + k * 0.9)} Z"

    fun dropPath(cx: Double, cy: Double, k: Double): String =
        "M${f(cx)} ${f(cy - k)} Q${f(cx + k * 0.9)} ${f(cy + k * 0.2)} ${f(cx)} ${f(cy + k * 0.8)} " +
            "Q${f(cx - k * 0.9)} ${f(cy + k * 0.2)} ${f(cx)} ${f(cy - k)} Z"

    fun cloudPath(b: BuddyRect): String {
        val x = b.x; val y = b.y; val w = b.w; val h = b.h
        return "M${f(x + w * 0.1)} ${f(y + h)} Q${f(x - w * 0.05)} ${f(y + h * 0.5)} ${f(x + w * 0.2)} ${f(y + h * 0.45)} " +
            "Q${f(x + w * 0.3)} ${f(y)} ${f(x + w * 0.55)} ${f(y + h * 0.25)} " +
            "Q${f(x + w * 0.85)} ${f(y)} ${f(x + w * 0.85)} ${f(y + h * 0.5)} " +
            "Q${f(x + w * 1.05)} ${f(y + h * 0.6)} ${f(x + w * 0.9)} ${f(y + h)} Z"
    }
}

// MARK: hình (dữ liệu)

/** Bộ phận — thứ tự vẽ = thứ tự (sau đè trước). = iOS BuddyPart (rawValue = ordinal). */
enum class BuddyPart { TAIL, LEG_L, LEG_R, BODY, ARM_L, ARM_R, HEAD, SLEEP }

sealed class Shape {
    class Path(val d: String) : Shape()
    class Circle(val cx: Double, val cy: Double, val r: Double) : Shape() {
        constructor(cx: Number, cy: Number, r: Number) : this(cx.toDouble(), cy.toDouble(), r.toDouble())
    }
    class Ellipse(val cx: Double, val cy: Double, val rx: Double, val ry: Double) : Shape()
    class RRect(val x: Double, val y: Double, val w: Double, val h: Double, val r: Double) : Shape()
}

sealed class Stroke {
    object None : Stroke()
    /** Viền nhân vật: nâu đậm ở nền sáng, kem ở nền tối. */
    object Outline : Stroke()
    /** Nét mặt: luôn đậm. */
    object Ink : Stroke()
    class Color(val rgb: Int) : Stroke()
}

enum class Show { ALWAYS, REST, DANCE }

class BuddyPiece(
    val part: BuddyPart, val shape: Shape, val fill: Int? = null, val alpha: Double = 1.0,
    val stroke: Stroke = Stroke.Outline, val width: Double = BuddyRig.OUTLINE_WIDTH, val show: Show = Show.ALWAYS,
    /** Xoay mảnh (độ, quanh cx, cy) — chỉ dáng ngủ. */
    val turn: Triple<Double, Double, Double>? = null,
) {
    constructor(part: BuddyPart, shape: Shape, fill: Int? = null, stroke: Stroke = Stroke.Outline, width: Number) :
        this(part, shape, fill, 1.0, stroke, width.toDouble())
}

enum class BuddyFraming { BAR, FULL }

class BuddyRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    constructor(x: Number, y: Number, w: Number, h: Number) : this(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())
    val maxY: Double get() = y + h
    override fun toString() = "Rect($x,$y,$w,$h)"
}

class P2(val x: Double, val y: Double) { constructor(x: Number, y: Number) : this(x.toDouble(), y.toDouble()) }
class P3(val x: Double, val y: Double, val size: Double) {
    constructor(x: Number, y: Number, s: Number) : this(x.toDouble(), y.toDouble(), s.toDouble())
}
class P4(val x: Double, val y: Double, val rx: Double, val ry: Double) {
    constructor(x: Number, y: Number, rx: Number, ry: Number) : this(x.toDouble(), y.toDouble(), rx.toDouble(), ry.toDouble())
}

/** Bộ khung một nhân vật — toàn bộ là dữ liệu (đơn vị thiết kế, y xuống). = iOS BuddyRig. */
class BuddyRig(
    val pieces: List<BuddyPiece>,
    val pivots: Map<BuddyPart, P2>,
    val full: BuddyRect,
    val barBottom: Double?,
    val feet: P2,
    val sleepBottom: Double,
    val zzz: List<P3>,
    val style: BuddyDance.Style,
    val heart: P3 = P3(0, 46, 11),
    val face: P4 = P4(0, -6, 24, 18),
    val brows: String = "M-16 -25 L-6 -21 M16 -25 L6 -21",
    val hat: P3 = P3(0, -42, 34),
    val sleepHat: P3 = P3(-34, -2, 26),
    val neck: P2 = P2(0, 18),
    val hand: P2 = P2(23, 64),
) {
    constructor(pieces: List<BuddyPiece>, pivots: Map<BuddyPart, P2>, full: BuddyRect, barBottom: Double?, feet: P2,
                sleepBottom: Int, zzz: List<P3>, style: BuddyDance.Style, heart: P3 = P3(0, 46, 11),
                face: P4 = P4(0, -6, 24, 18), brows: String = "M-16 -25 L-6 -21 M16 -25 L6 -21",
                hat: P3 = P3(0, -42, 34), sleepHat: P3 = P3(-34, -2, 26), neck: P2 = P2(0, 18), hand: P2 = P2(23, 64)) :
        this(pieces, pivots, full, barBottom, feet, sleepBottom.toDouble(), zzz, style, heart, face, brows, hat, sleepHat, neck, hand)

    val hasTail: Boolean get() = pieces.any { it.part == BuddyPart.TAIL }

    fun viewport(f: BuddyFraming): BuddyRect {
        val b = barBottom
        if (f != BuddyFraming.BAR || b == null) return full
        return BuddyRect(full.x, full.y, full.w, b - full.y)
    }

    fun sleepOffset(f: BuddyFraming): Double = viewport(f).maxY - 4 - sleepBottom

    companion object {
        const val OUTLINE_WIDTH = 6.0
        const val Z_COLOR = 0x8AA6E8
        const val OUTLINE_LIGHT = 0x3A2A2A
        const val OUTLINE_DARK = 0x4A3A38
        const val INK = 0x3A2A2A
        private const val ink = INK

        fun zPath(x: Double, y: Double, size: Double): String {
            fun n(v: Double) = if (v == floor(v)) v.toLong().toString() + ".0" else v.toString()
            return "M${n(x)} ${n(y)} h${n(size)} l${n(-size)} ${n(size)} h${n(size)}"
        }

        fun rig(c: BuddyCharacter): BuddyRig? = when (c) {
            BuddyCharacter.OFF -> null
            BuddyCharacter.GIRL -> GIRL
            BuddyCharacter.BOY -> BOY
            BuddyCharacter.CAT -> CAT
            BuddyCharacter.CHICK -> CHICK
        }

        /** = iOS BuddyRig.checksum (cùng công thức) — sửa hình một bên mà quên bên kia thì test đỏ. */
        fun checksum(c: BuddyCharacter): Double {
            val r = rig(c) ?: return 0.0
            var s = 0.0
            for (p in r.pieces) {
                s += p.part.ordinal + p.width + p.alpha + (p.fill ?: 0) / 65536.0
                s += when (val k = p.stroke) {
                    Stroke.None -> 1.0
                    Stroke.Outline -> 2.0
                    Stroke.Ink -> 3.0
                    is Stroke.Color -> 4 + k.rgb / 65536.0
                }
                s += when (p.show) { Show.ALWAYS -> 0.0; Show.REST -> 5.0; Show.DANCE -> 7.0 }
                p.turn?.let { s += it.first + it.second + it.third }
                s += when (val sh = p.shape) {
                    is Shape.Path -> BuddySVG.numbers(sh.d).sum()
                    is Shape.Circle -> sh.cx + sh.cy + sh.r
                    is Shape.Ellipse -> sh.cx + sh.cy + sh.rx + sh.ry
                    is Shape.RRect -> sh.x + sh.y + sh.w + sh.h + sh.r
                }
            }
            for (part in BuddyPart.entries) r.pivots[part]?.let { s += it.x * 3 + it.y * 5 }
            s += r.full.x + r.full.y + r.full.w + r.full.h + (r.barBottom ?: 0.0) + r.feet.x + r.feet.y + r.sleepBottom
            for (z in r.zzz) s += z.x + z.y + z.size
            s += r.heart.x + r.heart.y * 2 + r.heart.size * 3
            s += r.face.x + r.face.y + r.face.rx + r.face.ry + BuddySVG.numbers(r.brows).sum()
            s += r.hat.x + r.hat.y + r.hat.size + r.sleepHat.x + r.sleepHat.y + r.sleepHat.size
            s += r.neck.x + r.neck.y + r.hand.x * 2 + r.hand.y * 2
            return s
        }

        private fun P(part: BuddyPart, d: String, fill: Int? = null, alpha: Double = 1.0, stroke: Stroke = Stroke.Outline,
                      width: Number = OUTLINE_WIDTH, show: Show = Show.ALWAYS, turn: Triple<Double, Double, Double>? = null) =
            BuddyPiece(part, Shape.Path(d), fill, alpha, stroke, width.toDouble(), show, turn)
        private fun C(part: BuddyPart, cx: Number, cy: Number, r: Number, fill: Int, stroke: Stroke = Stroke.Outline,
                      alpha: Double = 1.0, show: Show = Show.ALWAYS) =
            BuddyPiece(part, Shape.Circle(cx.toDouble(), cy.toDouble(), r.toDouble()), fill, alpha, stroke, OUTLINE_WIDTH, show)
        private fun E(part: BuddyPart, cx: Number, cy: Number, rx: Number, ry: Number, fill: Int,
                      stroke: Stroke = Stroke.Outline, alpha: Double = 1.0) =
            BuddyPiece(part, Shape.Ellipse(cx.toDouble(), cy.toDouble(), rx.toDouble(), ry.toDouble()), fill, alpha, stroke)
        private fun R(part: BuddyPart, x: Number, y: Number, w: Number, h: Number, r: Number, fill: Int) =
            BuddyPiece(part, Shape.RRect(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), r.toDouble()), fill)
        private fun L(part: BuddyPart, d: String, stroke: Stroke, w: Number, show: Show = Show.ALWAYS) =
            BuddyPiece(part, Shape.Path(d), null, 1.0, stroke, w.toDouble(), show)
        private fun limb(part: BuddyPart, d: String, color: Int, w: Number): List<BuddyPiece> =
            listOf(L(part, d, Stroke.Outline, w.toDouble() + 5), L(part, d, Stroke.Color(color), w))

        // Cô gái — chibi thon, chân dài: tóc dài nâu, băng đô hồng tai mèo trắng (lòng hồng), đuôi mèo nâu, váy hồng, giày hồng.
        val GIRL: BuddyRig = run {
            val skin = 0xFFE0C7; val dress = 0xFFB3C7; val shoe = 0xFF6F91; val hair = 0x5B3A29
            val earIn = 0xFFC2D4; val blush = 0xFF9EAA
            // Đuôi mèo sau váy: vẫy khi nhảy.
            val p = mutableListOf<BuddyPiece>(); p += limb(BuddyPart.TAIL, "M14 66 Q50 70 52 40 Q54 20 40 18", hair, 5)
            p += listOf(
                L(BuddyPart.TAIL, "M44 22 Q40 17 38 19", Stroke.Color(0xFFFFFF), 4),
            )
            p += limb(BuddyPart.LEG_L, "M-8 72 L-10 140", skin, 11)
            p += listOf(
                E(BuddyPart.LEG_L, -11, 142, 8, 4.5, fill = shoe),
            )
            p += limb(BuddyPart.LEG_R, "M8 72 L10 140", skin, 11)
            p += listOf(
                E(BuddyPart.LEG_R, 11, 142, 8, 4.5, fill = shoe),
                P(BuddyPart.BODY, "M-14 24 Q0 18 14 24 L24 76 Q0 84 -24 76 Z", fill = dress),
                L(BuddyPart.BODY, "M-20 70 Q0 78 20 70", Stroke.Color(0xFFFFFF), 4),
            )
            p += limb(BuddyPart.ARM_L, "M-12 30 L-22 62", skin, 11)
            p += listOf(
                C(BuddyPart.ARM_L, -23, 64, 6, fill = skin),
            )
            p += limb(BuddyPart.ARM_R, "M12 30 L22 62", skin, 11)
            p += listOf(
                C(BuddyPart.ARM_R, 23, 64, 6, fill = skin),
                C(BuddyPart.HEAD, 0, -16, 32, fill = skin),
                P(BuddyPart.HEAD, "M-30 -36 L-26 -72 L-8 -48 Z", fill = 0xFFFFFF),
                P(BuddyPart.HEAD, "M-25 -42 L-23 -62 L-13 -48 Z", fill = earIn, stroke = Stroke.None),
                P(BuddyPart.HEAD, "M30 -36 L26 -72 L8 -48 Z", fill = 0xFFFFFF),
                P(BuddyPart.HEAD, "M25 -42 L23 -62 L13 -48 Z", fill = earIn, stroke = Stroke.None),
                P(BuddyPart.HEAD, "M-34 -14 Q-38 -52 0 -52 Q38 -52 34 -14 Q36 18 26 30 Q26 -6 18 -26 Q4 -18 -6 -30 "
                    + "Q-18 -20 -26 -26 Q-30 -6 -26 30 Q-36 18 -34 -14 Z", fill = hair),
                L(BuddyPart.HEAD, "M-30 -42 Q0 -62 30 -42", Stroke.Color(shoe), 5),
                C(BuddyPart.HEAD, -10, -13, 3.5, fill = ink, stroke = Stroke.None, show = Show.REST),
                C(BuddyPart.HEAD, 10, -13, 3.5, fill = ink, stroke = Stroke.None, show = Show.REST),
                L(BuddyPart.HEAD, "M-14 -14 q4 -5 8 0 M6 -14 q4 -5 8 0", Stroke.Ink, 4.5, show = Show.DANCE),
                E(BuddyPart.HEAD, -18, -2, 6, 3.5, fill = blush, stroke = Stroke.None, alpha = 0.6),
                E(BuddyPart.HEAD, 18, -2, 6, 3.5, fill = blush, stroke = Stroke.None, alpha = 0.6),
                P(BuddyPart.HEAD, "M-6 0 Q0 8 6 0 Z", fill = 0xE84D6A, stroke = Stroke.Ink, width = 2.5),
            )
            // Ngủ: nằm cuộn tròn, tai mèo, đuôi quấn quanh.
            p += listOf(
                E(BuddyPart.SLEEP, 14, 40, 56, 17, fill = dress),
                E(BuddyPart.SLEEP, 74, 46, 8, 4.5, fill = shoe),
                C(BuddyPart.SLEEP, -34, 22, 28, fill = skin),
                P(BuddyPart.SLEEP, "M-58 4 L-66 -24 L-46 -10 Z", fill = 0xFFFFFF),
                P(BuddyPart.SLEEP, "M-58 0 L-62 -16 L-50 -8 Z", fill = earIn, stroke = Stroke.None),
                P(BuddyPart.SLEEP, "M-26 -8 L-18 -34 L-8 -10 Z", fill = 0xFFFFFF),
                P(BuddyPart.SLEEP, "M-22 -10 L-18 -26 L-12 -12 Z", fill = earIn, stroke = Stroke.None),
                P(BuddyPart.SLEEP, "M-62 22 Q-66 -12 -34 -12 Q-2 -12 -6 22 Q-12 6 -22 6 Q-30 -2 -40 6 Q-54 2 -62 22 Z", fill = hair),
                L(BuddyPart.SLEEP, "M-46 24 q4 4 8 0 M-28 24 q4 4 8 0", Stroke.Ink, 4),
                E(BuddyPart.SLEEP, -48, 34, 5, 3, fill = blush, stroke = Stroke.None, alpha = 0.6),
                E(BuddyPart.SLEEP, -16, 34, 5, 3, fill = blush, stroke = Stroke.None, alpha = 0.6),
            )
            // Đuôi mèo cuộn quanh người khi ngủ.
            p += limb(BuddyPart.SLEEP, "M66 36 Q96 30 86 60 Q70 76 30 64", hair, 5)
            BuddyRig(
                pieces = p,
                pivots = mapOf(BuddyPart.TAIL to P2(14, 66), BuddyPart.LEG_L to P2(-8, 72), BuddyPart.LEG_R to P2(8, 72), BuddyPart.BODY to P2(0, 72), BuddyPart.ARM_L to P2(-12, 30), BuddyPart.ARM_R to P2(12, 30), BuddyPart.HEAD to P2(0, 18)),
                full = BuddyRect(-96, -84, 196, 234), barBottom = null, feet = P2(0, 146), sleepBottom = 72,
                zzz = listOf(P3(0, -30, 12), P3(16, -46, 15), P3(36, -64, 19)),
                style = BuddyDance.Style(armL = 2.3, armR = 2.3, armAmp = 0.3, alternate = true, kick = 0.6, bounce = 9.0, step = 8.0))
        }

        // Chàng trai — chibi thon hiphop: mũ lưỡi trai đội ngược sọc đỏ, kính râm, headphone đeo cổ, áo vàng "69",
        // dây chuyền vàng to có mặt dây, quần baggy xanh đen, sneaker trắng dày.
        val BOY: BuddyRig = run {
            val skin = 0xFFE0C7; val tee = 0xF2B134; val pants = 0x3B4252; val cap = 0x2E2A3A
            val red = 0xE2453C; val lens = 0x1B1B22; val blush = 0xFF9EAA
            val gold = 0xFFD54F; val gold2 = 0xB8860B
            val p = mutableListOf(
                P(BuddyPart.LEG_L, "M-16 64 L-26 132 L-12 134 L-2 76 Z", fill = pants),
                P(BuddyPart.LEG_L, "M-32 132 q-2 10 8 10 h16 q4 -9 -4 -10 Z", fill = 0xFFFFFF),
                L(BuddyPart.LEG_L, "M-30 139 h20", Stroke.Color(red), 3),
                P(BuddyPart.LEG_R, "M2 76 L14 134 L28 130 L16 64 Z", fill = pants),
                P(BuddyPart.LEG_R, "M10 132 q-2 10 8 10 h16 q4 -9 -4 -10 Z", fill = 0xFFFFFF),
                L(BuddyPart.LEG_R, "M12 139 h20", Stroke.Color(red), 3),
                P(BuddyPart.BODY, "M-20 20 Q0 12 20 20 L24 70 Q0 76 -24 70 Z", fill = tee),
                L(BuddyPart.BODY, "M-3 40 Q-11 40 -11 50 Q-11 56 -7 56 Q-3 56 -3 52 Q-3 47 -7 47 Q-11 47 -11 51 "
                    + "M3 56 Q11 56 11 46 Q11 40 7 40 Q3 40 3 44 Q3 49 7 49 Q11 49 11 45", Stroke.Ink, 3.5),
                // Dây chuyền vàng to + mặt dây.
                L(BuddyPart.BODY, "M-11 21 Q0 40 11 21", Stroke.Color(gold2), 6),
                L(BuddyPart.BODY, "M-11 21 Q0 40 11 21", Stroke.Color(gold), 3.5),
                BuddyPiece(BuddyPart.BODY, Shape.Circle(0, 34, 4.5), fill = gold, stroke = Stroke.Color(gold2), width = 2.5),
            )
            p += limb(BuddyPart.ARM_L, "M-18 26 L-26 58", skin, 11)
            p += listOf(
                C(BuddyPart.ARM_L, -27, 60, 6, fill = skin),
            )
            p += limb(BuddyPart.ARM_R, "M18 26 L26 58", skin, 11)
            p += listOf(
                C(BuddyPart.ARM_R, 27, 60, 6, fill = skin),
                C(BuddyPart.HEAD, 0, -16, 32, fill = skin),
                P(BuddyPart.HEAD, "M-32 -24 Q-30 -54 2 -54 Q32 -52 32 -24 Z", fill = cap),
                P(BuddyPart.HEAD, "M-30 -30 Q-46 -30 -48 -22 Q-40 -18 -28 -22 Z", fill = cap),
                L(BuddyPart.HEAD, "M-20 -46 h40", Stroke.Color(red), 3.5),
                P(BuddyPart.HEAD, "M-22 -18 h16 q1 11 -8 11 q-8 0 -8 -11 Z", fill = lens, width = 4),
                P(BuddyPart.HEAD, "M6 -18 h16 q0 11 -8 11 q-9 0 -8 -11 Z", fill = lens, width = 4),
                L(BuddyPart.HEAD, "M-6 -16 h12", Stroke.Ink, 3),
                L(BuddyPart.HEAD, "M-18 -15 l5 -1 M10 -15 l5 -1", Stroke.Color(0x7FB8FF), 2.5),
                L(BuddyPart.HEAD, "M-7 6 Q2 13 9 4", Stroke.Ink, 4),
                L(BuddyPart.HEAD, "M-20 14 Q0 24 20 14", Stroke.Color(red), 5),
                R(BuddyPart.HEAD, -28, 6, 10, 14, 4, fill = 0x2A2A33),
                R(BuddyPart.HEAD, 18, 6, 10, 14, 4, fill = 0x2A2A33),
            )
            // Ngủ: kéo mũ che mắt, ngáy.
            p += listOf(
                E(BuddyPart.SLEEP, 14, 40, 56, 17, fill = tee),
                P(BuddyPart.SLEEP, "M62 40 h16 v8 h-18 Z", fill = 0xFFFFFF),
                C(BuddyPart.SLEEP, -34, 22, 28, fill = skin),
                P(BuddyPart.SLEEP, "M-62 16 Q-62 -14 -34 -14 Q-6 -14 -6 16 Z", fill = cap),
                P(BuddyPart.SLEEP, "M-64 14 Q-34 24 -4 14 Q-34 30 -64 14 Z", fill = cap),
                L(BuddyPart.SLEEP, "M-52 2 h36", Stroke.Color(red), 3.5),
                E(BuddyPart.SLEEP, -34, 38, 4, 3, fill = ink, stroke = Stroke.None),
                E(BuddyPart.SLEEP, -56, 32, 5, 3, fill = blush, stroke = Stroke.None, alpha = 0.6),
                E(BuddyPart.SLEEP, -12, 32, 5, 3, fill = blush, stroke = Stroke.None, alpha = 0.6),
                L(BuddyPart.SLEEP, "M-50 50 Q-34 58 -18 50", Stroke.Color(red), 4),
                L(BuddyPart.SLEEP, "M-44 44 Q-30 54 -14 44", Stroke.Color(gold2), 5.5),
                L(BuddyPart.SLEEP, "M-44 44 Q-30 54 -14 44", Stroke.Color(gold), 3.5),
                BuddyPiece(BuddyPart.SLEEP, Shape.Circle(-28, 50, 3.5), fill = gold, stroke = Stroke.Color(gold2), width = 2),
            )
            BuddyRig(
                pieces = p,
                pivots = mapOf(BuddyPart.LEG_L to P2(-9, 68), BuddyPart.LEG_R to P2(9, 68), BuddyPart.BODY to P2(0, 70), BuddyPart.ARM_L to P2(-18, 26), BuddyPart.ARM_R to P2(18, 26), BuddyPart.HEAD to P2(0, 16)),
                full = BuddyRect(-92, -76, 184, 226), barBottom = null, feet = P2(0, 142), sleepBottom = 57,
                zzz = listOf(P3(0, -30, 12), P3(16, -46, 15), P3(36, -64, 19)),
                style = BuddyDance.Style(armL = -1.3, armR = 2.3, armAmp = 0.2, alternate = false, kick = 0.3, bounce = 8.0, step = 10.0),
                brows = "M-20 -24 L-8 -20 M20 -24 L8 -20", hat = P3(2, -46, 36), hand = P2(27, 60))
        }

        // Mèo (chibi): cam mướp, bụng kem, đuôi vẫy.
        val CAT: BuddyRig = run {
            val fur = 0xF6A94E; val cream = 0xFFF1DC; val stripe = 0xD9822B; val pink = 0xFFB3C7
            val nose = 0xF27A8A; val blush = 0xFF9EAA
            val p = mutableListOf<BuddyPiece>(); p += limb(BuddyPart.TAIL, "M18 80 Q50 82 48 50 Q47 36 56 30", fur, 10)
            p += limb(BuddyPart.LEG_L, "M-14 88 L-14 106", fur, 11)
            p += listOf(E(BuddyPart.LEG_L, -16, 110, 10, 6, fill = cream))
            p += limb(BuddyPart.LEG_R, "M14 88 L14 106", fur, 11)
            p += listOf(E(BuddyPart.LEG_R, 16, 110, 10, 6, fill = cream),
                  E(BuddyPart.BODY, 0, 72, 30, 28, fill = fur),
                  E(BuddyPart.BODY, 0, 78, 17, 18, fill = cream, stroke = Stroke.None))
            p += limb(BuddyPart.ARM_L, "M-22 52 L-30 74", fur, 10)
            p += listOf(C(BuddyPart.ARM_L, -31, 77, 6.5, fill = cream))
            p += limb(BuddyPart.ARM_R, "M22 52 L30 74", fur, 10)
            p += listOf(C(BuddyPart.ARM_R, 31, 77, 6.5, fill = cream),
                  P(BuddyPart.HEAD, "M-36 -16 L-32 -58 L-6 -40 Z", fill = fur),
                  P(BuddyPart.HEAD, "M36 -16 L32 -58 L6 -40 Z", fill = fur),
                  P(BuddyPart.HEAD, "M-30 -26 L-28 -48 L-14 -38 Z M30 -26 L28 -48 L14 -38 Z", fill = pink, stroke = Stroke.None),
                  E(BuddyPart.HEAD, 0, -4, 42, 36, fill = fur),
                  L(BuddyPart.HEAD, "M-7 -38 L-4 -28 M7 -38 L4 -28 M0 -40 L0 -29", Stroke.Color(stripe), 4),
                  E(BuddyPart.HEAD, 0, 12, 13, 9, fill = cream, stroke = Stroke.None),
                  C(BuddyPart.HEAD, -16, -6, 4.5, fill = ink, stroke = Stroke.None, show = Show.REST),
                  C(BuddyPart.HEAD, 16, -6, 4.5, fill = ink, stroke = Stroke.None, show = Show.REST),
                  L(BuddyPart.HEAD, "M-21 -4 q5 -6 10 0 M11 -4 q5 -6 10 0", Stroke.Ink, 5, show = Show.DANCE),
                  P(BuddyPart.HEAD, "M-4 5 L4 5 L0 10 Z", fill = nose, stroke = Stroke.None),
                  L(BuddyPart.HEAD, "M-6 13 q3 4 6 0 q3 4 6 0", Stroke.Ink, 3),
                  L(BuddyPart.HEAD, "M-26 8 L-44 4 M-26 14 L-44 16 M26 8 L44 4 M26 14 L44 16", Stroke.Ink, 3),
                  E(BuddyPart.HEAD, -26, 6, 6, 3.5, fill = blush, stroke = Stroke.None, alpha = 0.6),
                  E(BuddyPart.HEAD, 26, 6, 6, 3.5, fill = blush, stroke = Stroke.None, alpha = 0.6))
            // Ngủ: cuộn tròn, đuôi quấn trước.
            p += listOf(E(BuddyPart.SLEEP, 10, 40, 52, 24, fill = fur),
                  L(BuddyPart.SLEEP, "M10 18 L12 28 M26 18 L26 28 M42 22 L40 31", Stroke.Color(stripe), 4))
            p += limb(BuddyPart.SLEEP, "M58 46 Q72 66 40 66 Q10 66 -8 60", fur, 10)
            p += listOf(P(BuddyPart.SLEEP, "M-58 10 L-62 -14 L-42 0 Z", fill = fur),
                  P(BuddyPart.SLEEP, "M-26 0 L-10 -14 L-12 8 Z", fill = fur),
                  C(BuddyPart.SLEEP, -34, 26, 30, fill = fur),
                  E(BuddyPart.SLEEP, -34, 38, 9, 6, fill = cream, stroke = Stroke.None),
                  L(BuddyPart.SLEEP, "M-48 26 q5 5 10 0 M-30 26 q5 5 10 0", Stroke.Ink, 4.5),
                  P(BuddyPart.SLEEP, "M-37 33 L-31 33 L-34 37 Z", fill = nose, stroke = Stroke.None),
                  E(BuddyPart.SLEEP, -52, 36, 5, 3, fill = blush, stroke = Stroke.None, alpha = 0.6),
                  E(BuddyPart.SLEEP, -16, 36, 5, 3, fill = blush, stroke = Stroke.None, alpha = 0.6))
            BuddyRig(
                pieces = p,
                pivots = mapOf(BuddyPart.TAIL to P2(18, 80), BuddyPart.LEG_L to P2(-14, 88), BuddyPart.LEG_R to P2(14, 88), BuddyPart.BODY to P2(0, 96), BuddyPart.ARM_L to P2(-22, 52), BuddyPart.ARM_R to P2(22, 52), BuddyPart.HEAD to P2(0, 32)),
                full = BuddyRect(-80, -66, 160, 188), barBottom = null, feet = P2(0, 116), sleepBottom = 68,
                zzz = listOf(P3(0, -30, 12), P3(16, -46, 15), P3(36, -64, 19)),
                style = BuddyDance.Style(armL = 1.2, armR = 1.2, armAmp = 0.6, alternate = true, kick = 0.2, bounce = 10.0, step = 8.0),
                heart = P3(0, 68, 13), face = P4(0, 6, 30, 18), brows = "M-23 -16 L-11 -11 M23 -16 L11 -11",
                hat = P3(0, -36, 36), sleepHat = P3(-34, -2, 28), neck = P2(0, 34), hand = P2(31, 77))
        }

        // Gà con (chibi): tròn vàng, cánh vỗ.
        val CHICK: BuddyRig = run {
            val yellow = 0xFFD43B; val wing = 0xF5BE2A; val orange = 0xF08A24; val blush = 0xFF9EAA
            val p = mutableListOf<BuddyPiece>(); p += limb(BuddyPart.LEG_L, "M-12 86 L-12 104", orange, 9)
            p += limb(BuddyPart.LEG_L, "M-22 108 L-12 103 L-2 108", orange, 7)
            p += limb(BuddyPart.LEG_R, "M12 86 L12 104", orange, 9)
            p += limb(BuddyPart.LEG_R, "M2 108 L12 103 L22 108", orange, 7)
            p += listOf(E(BuddyPart.BODY, 0, 64, 38, 30, fill = yellow),
                  E(BuddyPart.BODY, 0, 70, 22, 18, fill = 0xFFE68A, stroke = Stroke.None),
                  P(BuddyPart.ARM_L, "M-32 50 Q-58 62 -44 84 Q-34 74 -28 62 Z", fill = wing),
                  P(BuddyPart.ARM_R, "M32 50 Q58 62 44 84 Q34 74 28 62 Z", fill = wing),
                  P(BuddyPart.HEAD, "M-4 -38 Q-12 -62 2 -56 Q8 -68 14 -54 Q10 -44 4 -39 Z", fill = yellow),
                  C(BuddyPart.HEAD, 0, -2, 38, fill = yellow),
                  C(BuddyPart.HEAD, -14, -4, 4.5, fill = ink, stroke = Stroke.None, show = Show.REST),
                  C(BuddyPart.HEAD, 14, -4, 4.5, fill = ink, stroke = Stroke.None, show = Show.REST),
                  L(BuddyPart.HEAD, "M-19 -2 q5 -6 10 0 M9 -2 q5 -6 10 0", Stroke.Ink, 5, show = Show.DANCE),
                  P(BuddyPart.HEAD, "M-9 8 L9 8 L0 18 Z", fill = orange),
                  E(BuddyPart.HEAD, -24, 8, 6, 3.5, fill = blush, stroke = Stroke.None, alpha = 0.6),
                  E(BuddyPart.HEAD, 24, 8, 6, 3.5, fill = blush, stroke = Stroke.None, alpha = 0.6))
            // Ngủ: tròn xoe, đầu gục.
            p += listOf(E(BuddyPart.SLEEP, 6, 42, 46, 26, fill = yellow),
                  P(BuddyPart.SLEEP, "M18 30 Q42 32 38 54 Q26 50 18 40 Z", fill = wing))
            p += limb(BuddyPart.SLEEP, "M44 64 L54 66", orange, 5)
            p += listOf(P(BuddyPart.SLEEP, "M-34 -1 Q-42 -18 -30 -14 Q-26 -24 -18 -10 Z", fill = yellow),
                  C(BuddyPart.SLEEP, -30, 26, 28, fill = yellow),
                  P(BuddyPart.SLEEP, "M-56 30 L-68 36 L-56 42 Z", fill = orange),
                  L(BuddyPart.SLEEP, "M-44 24 q5 5 10 0 M-28 24 q5 5 10 0", Stroke.Ink, 4.5),
                  E(BuddyPart.SLEEP, -46, 34, 5, 3, fill = blush, stroke = Stroke.None, alpha = 0.6),
                  E(BuddyPart.SLEEP, -16, 36, 5, 3, fill = blush, stroke = Stroke.None, alpha = 0.6))
            BuddyRig(
                pieces = p,
                pivots = mapOf(BuddyPart.LEG_L to P2(-12, 86), BuddyPart.LEG_R to P2(12, 86), BuddyPart.BODY to P2(0, 94), BuddyPart.ARM_L to P2(-32, 50), BuddyPart.ARM_R to P2(32, 50), BuddyPart.HEAD to P2(0, 34)),
                full = BuddyRect(-80, -66, 160, 188), barBottom = null, feet = P2(0, 110), sleepBottom = 68,
                zzz = listOf(P3(0, -34, 12), P3(16, -50, 15), P3(36, -68, 19)),
                style = BuddyDance.Style(armL = 1.0, armR = 1.0, armAmp = 0.7, alternate = false, kick = 0.3, bounce = 12.0, step = 8.0),
                heart = P3(0, 66, 13), face = P4(0, 6, 28, 18), brows = "M-21 -13 L-9 -9 M21 -13 L9 -9",
                hat = P3(0, -36, 34), sleepHat = P3(-30, 0, 26), neck = P2(0, 36), hand = P2(44, 84))
        }
    }
}

/**
 * Quầng sáng / tối quanh CẢ dáng (vẽ dưới mọi bộ phận) + màu viền nét — theo nền thanh gợi ý, chọn MỘT lần lúc đổi
 * theme: bên nào tương phản WCAG cao hơn. = iOS BuddyHalo (cùng số).
 */
object BuddyHalo {
    const val LIGHT = 0xF4F4F4
    const val DARK = 0x3A2A2A
    /** Bề rộng quầng (dp) ngoài nét viền. */
    const val WIDTH = 1.75
    const val SYSTEM_LIGHT = 0xD1D3D9
    const val SYSTEM_DARK = 0x2C2C2E

    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val x = (v and 0xFF) / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c shr 16) + 0.7152 * ch(c shr 8) + 0.0722 * ch(c)
    }
    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    data class Colors(val halo: Int, val outline: Int, val darkBar: Boolean)

    /** Màu quầng + viền cho nền thanh [bar] (RGB đục). */
    fun colors(bar: Int): Colors {
        val darkBar = contrast(LIGHT, bar) >= contrast(DARK, bar)
        return Colors(if (darkBar) LIGHT else DARK, if (darkBar) BuddyRig.OUTLINE_DARK else BuddyRig.OUTLINE_LIGHT, darkBar)
    }

    /** Nền thanh (ARGB, có thể trong suốt) trộn lên xám bàn phím hệ thống ⇒ RGB đục. */
    fun opaque(argb: Int, dark: Boolean): Int {
        val a = ((argb ushr 24) and 0xFF) / 255.0
        val sys = if (dark) SYSTEM_DARK else SYSTEM_LIGHT
        fun ch(sh: Int): Int = Math.round(((argb shr sh) and 0xFF) * a + ((sys shr sh) and 0xFF) * (1 - a)).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Khoá nhóm quầng của một mảnh: (bề rộng nét thiết kế, có tô). */
    fun key(p: BuddyPiece): Pair<Double, Boolean> = if (p.fill != null) BuddyRig.OUTLINE_WIDTH to true else p.width to false
}

/** Nghệ thuật: khung vector (mặc định) hoặc bộ khung hình vẽ sẵn (hoạ sĩ — chưa có). = iOS BuddyArt. */
sealed class BuddyArt {
    class Rig(val rig: BuddyRig) : BuddyArt()
    class Sprites(val sprites: BuddySprites) : BuddyArt()
}

/** Bộ khung hình (tên ảnh) — mỗi bậc nhảy một chuỗi khung chạy hết trong một vòng. */
class BuddySprites(val sleep: String, val rest: String, val dance: List<List<String>>, val cheer: List<String>) {
    companion object {
        fun frameIndex(p: Double, count: Int): Int {
            if (count <= 0) return 0
            val pp = p - floor(p)
            return min((pp * count).toInt(), count - 1)
        }
    }
}

// MARK: trang phục theo mùa lễ

/** = iOS BuddySeason: theme lễ thắng, không thì cửa sổ ngày (âm lịch tra bảng nhúng 2026–2035, không mạng). */
enum class BuddySeason {
    TET, CHRISTMAS, HALLOWEEN, MID_AUTUMN;

    companion object {
        fun ofTheme(themeId: String?): BuddySeason? = when (themeId) {
            "tet", "tetMai" -> TET
            "xmas", "xmasSnow" -> CHRISTMAS
            "halloween" -> HALLOWEEN
            "trungthu" -> MID_AUTUMN
            else -> null
        }

        /** Mùng 1 Tết — LƯU Ý: bảng nhúng theo lịch âm VN, cần đối chiếu nguồn chính thức trước khi phát hành. */
        val LUNAR_NEW_YEAR: Map<Int, Pair<Int, Int>> = mapOf(
            2026 to (2 to 17), 2027 to (2 to 6), 2028 to (1 to 26), 2029 to (2 to 13), 2030 to (2 to 3),
            2031 to (1 to 23), 2032 to (2 to 11), 2033 to (1 to 31), 2034 to (2 to 19), 2035 to (2 to 8),
        )
        /** Rằm tháng Tám. */
        val MID_AUTUMN_DAY: Map<Int, Pair<Int, Int>> = mapOf(
            2026 to (9 to 25), 2027 to (9 to 15), 2028 to (10 to 3), 2029 to (9 to 22), 2030 to (9 to 12),
            2031 to (10 to 1), 2032 to (9 to 19), 2033 to (9 to 8), 2034 to (9 to 27), 2035 to (9 to 16),
        )

        fun dayNumber(y: Int, m: Int, d: Int): Int {
            val a = (14 - m) / 12
            val yy = y + 4800 - a
            val mm = m + 12 * a - 3
            val jdn = d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
            return jdn - 2_440_588
        }

        fun ofDate(y: Int, m: Int, d: Int): BuddySeason? {
            val today = dayNumber(y, m, d)
            for (yy in intArrayOf(y, y + 1)) {
                val t = LUNAR_NEW_YEAR[yy] ?: continue
                val n = dayNumber(yy, t.first, t.second)
                if (today >= n - 10 && today <= n + 7) return TET
            }
            if (m == 12 && d in 15..26) return CHRISTMAS
            if ((m == 10 && d >= 25) || (m == 11 && d == 1)) return HALLOWEEN
            MID_AUTUMN_DAY[y]?.let { t -> if (abs(today - dayNumber(y, t.first, t.second)) <= 5) return MID_AUTUMN }
            return null
        }

        fun resolve(themeId: String?, y: Int, m: Int, d: Int): BuddySeason? = ofTheme(themeId) ?: ofDate(y, m, d)
    }
}

/** = iOS BuddyOutfit: mảnh thêm + đổi màu. */
class BuddyOutfit(val pieces: List<BuddyPiece> = emptyList(), val recolor: Map<Int, Int> = emptyMap()) {
    companion object {
        const val RED = 0xD7263D
        const val GOLD = 0xF2C14E
        const val WHITE = 0xFFFFFF

        fun make(s: BuddySeason?, c: BuddyCharacter): BuddyOutfit {
            val r = BuddyRig.rig(c) ?: return BuddyOutfit()
            if (s == null) return BuddyOutfit()
            val h = r.hat; val sh = r.sleepHat
            return when (s) {
                BuddySeason.TET -> when (c) {
                    BuddyCharacter.GIRL -> BuddyOutfit(listOf(
                        BuddyPiece(BuddyPart.BODY, Shape.Circle(-6, 44, 2.5), GOLD, 1.0, Stroke.None),
                        BuddyPiece(BuddyPart.BODY, Shape.Circle(6, 56, 2.5), GOLD, 1.0, Stroke.None),
                        BuddyPiece(BuddyPart.BODY, Shape.Circle(-4, 66, 2.5), GOLD, 1.0, Stroke.None)),
                        mapOf(0xFFB3C7 to RED, 0xFF6F91 to GOLD))
                    BuddyCharacter.BOY -> BuddyOutfit(khanDong(BuddyPart.HEAD, h) + khanDong(BuddyPart.SLEEP, sh), mapOf(0xF2B134 to RED))
                    else -> BuddyOutfit(bow(BuddyPart.HEAD, r.neck))
                }
                BuddySeason.CHRISTMAS -> BuddyOutfit(santa(BuddyPart.HEAD, h) + santa(BuddyPart.SLEEP, sh))
                BuddySeason.HALLOWEEN -> BuddyOutfit(
                    if (c == BuddyCharacter.GIRL) witch(BuddyPart.HEAD, h) + witch(BuddyPart.SLEEP, sh)
                    else pumpkin(BuddyPart.HEAD, h) + pumpkin(BuddyPart.SLEEP, sh))
                BuddySeason.MID_AUTUMN -> BuddyOutfit(lantern(r.hand))
            }
        }

        private fun f(v: Double) = String.format(Locale.US, "%.2f", v)

        fun santa(part: BuddyPart, a: P3): List<BuddyPiece> {
            val x = a.x; val y = a.y; val k = a.size
            return listOf(
                BuddyPiece(part, Shape.Path("M${f(x - k / 2)} ${f(y)} Q${f(x - k * 0.1)} ${f(y - k * 1.1)} ${f(x + k * 0.55)} ${f(y - k * 0.75)} L${f(x + k / 2)} ${f(y)} Z"), 0xD62828),
                BuddyPiece(part, Shape.RRect(x - k * 0.55, y - k * 0.12, k * 1.1, k * 0.24, k * 0.12), WHITE),
                BuddyPiece(part, Shape.Circle(x + k * 0.55, y - k * 0.75, k * 0.13), WHITE),
            )
        }

        fun witch(part: BuddyPart, a: P3): List<BuddyPiece> {
            val x = a.x; val y = a.y; val k = a.size
            return listOf(
                BuddyPiece(part, Shape.Ellipse(x, y, k * 0.75, k * 0.13), 0x3B2B4F),
                BuddyPiece(part, Shape.Path("M${f(x - k * 0.35)} ${f(y)} L${f(x + k * 0.2)} ${f(y - k * 1.2)} L${f(x + k * 0.35)} ${f(y)} Z"), 0x3B2B4F),
                BuddyPiece(part, Shape.RRect(x - k * 0.33, y - k * 0.22, k * 0.66, k * 0.12, 0.0), 0xF28C28, 1.0, Stroke.None),
            )
        }

        fun pumpkin(part: BuddyPart, a: P3): List<BuddyPiece> {
            val x = a.x; val y = a.y; val k = a.size
            return listOf(
                BuddyPiece(part, Shape.RRect(x - k * 0.06, y - k * 0.72, k * 0.12, k * 0.18, k * 0.04), 0x4E7A2C),
                BuddyPiece(part, Shape.Ellipse(x, y - k * 0.28, k * 0.5, k * 0.33), 0xF28C28),
                BuddyPiece(part, Shape.Path("M${f(x)} ${f(y - k * 0.58)} L${f(x)} ${f(y + k * 0.02)} M${f(x - k * 0.25)} ${f(y - k * 0.52)} " +
                    "Q${f(x - k * 0.34)} ${f(y - k * 0.28)} ${f(x - k * 0.25)} ${f(y - k * 0.02)}"), null, 1.0, Stroke.Color(0xC75F12), 3.0),
            )
        }

        fun khanDong(part: BuddyPart, a: P3): List<BuddyPiece> {
            val x = a.x; val y = a.y; val k = a.size
            return listOf(
                BuddyPiece(part, Shape.Path("M${f(x - k * 0.55)} ${f(y)} Q${f(x)} ${f(y - k * 0.55)} ${f(x + k * 0.55)} ${f(y)} Z"), RED),
                BuddyPiece(part, Shape.Path("M${f(x - k * 0.45)} ${f(y - k * 0.12)} Q${f(x)} ${f(y - k * 0.32)} ${f(x + k * 0.45)} ${f(y - k * 0.12)}"),
                    null, 1.0, Stroke.Color(GOLD), 3.0),
            )
        }

        fun bow(part: BuddyPart, a: P2): List<BuddyPiece> {
            val x = a.x; val y = a.y
            return listOf(
                BuddyPiece(part, Shape.Path("M${f(x)} ${f(y)} L${f(x - 11)} ${f(y - 7)} L${f(x - 11)} ${f(y + 7)} Z M${f(x)} ${f(y)} L${f(x + 11)} ${f(y - 7)} L${f(x + 11)} ${f(y + 7)} Z"), RED),
                BuddyPiece(part, Shape.Circle(x, y, 3.5), GOLD),
            )
        }

        fun lantern(hnd: P2): List<BuddyPiece> {
            val x = hnd.x; val y = hnd.y
            return listOf(
                BuddyPiece(BuddyPart.ARM_R, Shape.Path("M${f(x)} ${f(y)} L${f(x + 2)} ${f(y + 12)}"), null, 1.0, Stroke.Ink, 2.5),
                BuddyPiece(BuddyPart.ARM_R, Shape.Ellipse(x + 2, y + 21, 9.0, 8.0), 0xE63946),
                BuddyPiece(BuddyPart.ARM_R, Shape.RRect(x - 3, y + 11, 10.0, 3.0, 1.0), GOLD),
                BuddyPiece(BuddyPart.ARM_R, Shape.RRect(x - 3, y + 28, 10.0, 3.0, 1.0), GOLD),
            )
        }
    }
}

// MARK: đường SVG rút gọn

/** M/m L/l H/h V/v Q/q Z/z — = iOS BuddySVG. */
object BuddySVG {
    sealed class Cmd {
        data class Move(val x: Double, val y: Double) : Cmd()
        data class Line(val x: Double, val y: Double) : Cmd()
        data class Quad(val cx: Double, val cy: Double, val x: Double, val y: Double) : Cmd()
        object Close : Cmd() { override fun toString() = "Close" }
    }

    fun numbers(s: String): List<Double> {
        val out = ArrayList<Double>()
        val cur = StringBuilder()
        fun flush() { cur.toString().toDoubleOrNull()?.let { out.add(it) }; cur.setLength(0) }
        for (ch in s) {
            when {
                ch.isDigit() || ch == '.' -> cur.append(ch)
                ch == '-' -> { flush(); cur.append('-') }
                else -> flush()
            }
        }
        flush()
        return out
    }

    fun parse(s: String): List<Cmd> {
        val out = ArrayList<Cmd>()
        var x = 0.0; var y = 0.0; var sx = 0.0; var sy = 0.0
        fun emit(cmd: Char, nums: List<Double>) {
            val rel = cmd.isLowerCase()
            var i = 0
            fun take(n: Int): List<Double>? = if (i + n <= nums.size) nums.subList(i, i + n).also { i += n } else null
            when (cmd.uppercaseChar()) {
                'Z' -> { out.add(Cmd.Close); x = sx; y = sy }
                'M' -> {
                    var first = true
                    while (true) {
                        val v = take(2) ?: break
                        x = if (rel) x + v[0] else v[0]; y = if (rel) y + v[1] else v[1]
                        if (first) { out.add(Cmd.Move(x, y)); sx = x; sy = y; first = false } else out.add(Cmd.Line(x, y))
                    }
                }
                'L' -> while (true) { val v = take(2) ?: break; x = if (rel) x + v[0] else v[0]; y = if (rel) y + v[1] else v[1]; out.add(Cmd.Line(x, y)) }
                'H' -> while (true) { val v = take(1) ?: break; x = if (rel) x + v[0] else v[0]; out.add(Cmd.Line(x, y)) }
                'V' -> while (true) { val v = take(1) ?: break; y = if (rel) y + v[0] else v[0]; out.add(Cmd.Line(x, y)) }
                'Q' -> while (true) {
                    val v = take(4) ?: break
                    val cx = if (rel) x + v[0] else v[0]; val cy = if (rel) y + v[1] else v[1]
                    x = if (rel) x + v[2] else v[2]; y = if (rel) y + v[3] else v[3]
                    out.add(Cmd.Quad(cx, cy, x, y))
                }
            }
        }
        var cmd = 'M'
        val token = StringBuilder()
        var started = false
        for (ch in s) {
            if (ch in "MmLlHhVvQqZz") {
                if (started) emit(cmd, numbers(token.toString()))
                cmd = ch; token.setLength(0); started = true
            } else token.append(ch)
        }
        if (started) emit(cmd, numbers(token.toString()))
        return out
    }
}

// MARK: bộ điều khiển (thuần) cho view Android

/**
 * Máy trạng thái + dáng đang hiện tại từng thời điểm cho [app/.../ime/BuddyView]: view gọi [keyPressed] / [deletePressed]
 * / [wordCommitted] (ghi giờ, không cấp phát), [update] mỗi khung hình hoặc ở mốc hẹn, rồi [poseAt] để vẽ.
 * [animating] = còn cần khung hình kế không (ngủ yên / đứng yên = false ⇒ view thôi vẽ). Nối mượt giữa trạng thái
 * = bù (cũ − mới) giảm dần trong [BuddyDance.BLEND] giây — y như hoạt ảnh bù cộng dồn của iOS.
 */
class BuddyDriver(val rig: BuddyRig, var lowPower: Boolean = false) {
    val brain = BuddyBrain()
    var shown: BuddyPhase = BuddyPhase.Asleep.QUIET; private set
    var phaseStart = 0.0; private set
    var shownSerial = 0; private set
    private var loopEpoch = 0.0
    private var loopLen = 1.0
    private val blendDelta = BuddyPose()
    private var blendStart = -1e9
    private val tmp = BuddyPose()
    private val tmp2 = BuddyPose()

    fun keyPressed(t: Double) { brain.press(t) }
    fun deletePressed(t: Double) { brain.delete(t) }
    fun cheer(t: Double) { brain.cheer(t) }
    fun react(r: BuddyReaction, t: Double) { brain.react(r, t) }
    /** Ranh giới từ: tra bảng O(1); true = có phản ứng. */
    fun wordCommitted(word: String, prev: String?, prev2: String?, t: Double): Boolean {
        val r = BuddyWords.reaction(word, prev, prev2) ?: return false
        brain.react(r, t)
        return true
    }

    /** Trạng thái hiển thị tại [t] có khác đang hiện không (gọi trên đường phím — không cấp phát). */
    fun needsUpdate(t: Double): Boolean {
        val v = BuddyPolicy.visual(brain.phase(t), lowPower)
        return v !== shown || (v is BuddyPhase.React && brain.reactSerial != shownSerial)
    }

    /** Áp trạng thái tại [t]; true = vừa đổi. */
    fun update(t: Double): Boolean {
        if (!needsUpdate(t)) return false
        val v = BuddyPolicy.visual(brain.phase(t), lowPower)
        val old = displayed(t, tmp2)
        val prev = shown
        shown = v
        phaseStart = t
        val target: BuddyPose? = when (v) {
            is BuddyPhase.Asleep -> null
            BuddyPhase.Awake -> tmp.reset()
            is BuddyPhase.Dancing -> {
                val p = if (prev is BuddyPhase.Dancing) loopPhase(t) else 0.0
                loopLen = BuddyDance.loopLength(v.tier)
                loopEpoch = t - p * loopLen
                BuddyDance.pose(rig.style, v.tier, p, tmp)
            }
            BuddyPhase.Cheering -> tmp.reset()
            is BuddyPhase.React -> { shownSerial = brain.reactSerial; BuddyDance.react(v.reaction, rig.style, 0.0, tmp) }
        }
        if (target == null || lowPower) { blendStart = -1e9; blendDelta.reset() }
        else {
            val o = old ?: tmp2.reset()
            blendDelta.dx = o.dx - target.dx; blendDelta.dy = o.dy - target.dy; blendDelta.rot = o.rot - target.rot
            blendDelta.head = o.head - target.head; blendDelta.armL = o.armL - target.armL; blendDelta.armR = o.armR - target.armR
            blendDelta.legL = o.legL - target.legL; blendDelta.legR = o.legR - target.legR; blendDelta.tail = o.tail - target.tail
            blendDelta.sx = 0.0
            blendStart = t
        }
        return true
    }

    private fun loopPhase(t: Double): Double { val x = (t - loopEpoch) / loopLen; return x - floor(x) }

    /** Độ dài vòng phản ứng (hoảng: lặp 0,5 s). */
    fun reactLength(r: BuddyReaction) = if (r == BuddyReaction.PANIC) 0.5 else r.duration

    private fun displayed(t: Double, out: BuddyPose): BuddyPose? = when (val s = shown) {
        is BuddyPhase.Asleep -> null
        BuddyPhase.Awake -> out.reset()
        is BuddyPhase.Dancing -> BuddyDance.pose(rig.style, s.tier, loopPhase(t), out)
        BuddyPhase.Cheering -> BuddyDance.cheer(rig.style, (t - phaseStart) / BuddyBrain.CHEER, out)
        is BuddyPhase.React -> {
            var f = (t - phaseStart) / reactLength(s.reaction)
            if (s.reaction == BuddyReaction.PANIC) f -= floor(f)
            BuddyDance.react(s.reaction, rig.style, f, out)
        }
    }

    /** Dáng vẽ tại [t] (ghi vào [out]); null = đang ngủ (vẽ nhóm ngủ). */
    fun poseAt(t: Double, out: BuddyPose): BuddyPose? {
        val p = displayed(t, out) ?: return null
        val k = (t - blendStart) / BuddyDance.BLEND
        if (k in 0.0..1.0) {
            val e = 1 - (1 - (1 - k) * (1 - k))   // easeOut: còn lại (1−k)²
            p.dx += blendDelta.dx * e; p.dy += blendDelta.dy * e; p.rot += blendDelta.rot * e
            p.head += blendDelta.head * e; p.armL += blendDelta.armL * e; p.armR += blendDelta.armR * e
            p.legL += blendDelta.legL * e; p.legR += blendDelta.legR * e; p.tail += blendDelta.tail * e
        }
        return p
    }

    /** Còn cần vẽ khung kế tại [t] không — ngủ yên / đứng yên (hết nối) = false. */
    fun animating(t: Double): Boolean {
        if (lowPower) return false
        return when (val s = shown) {
            is BuddyPhase.Asleep -> s.zzz
            BuddyPhase.Awake -> t - blendStart < BuddyDance.BLEND
            else -> true
        }
    }

    /** Mốc tự đổi kế (để hẹn một lần gọi [update]); null = ngủ yên. */
    fun nextDeadline(t: Double): Double? = brain.nextDeadline(t)
}
