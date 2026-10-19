package com.viettelex.keyboard

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Piano (Phil 19/10/2026): mỗi phím một nốt, ngũ cung trưởng C D E G A — gõ lung tung vẫn thành
 * nhạc, không nghịch tai. Thuật toán + bảng Y HỆT iOS/Shared/KeySoundSynth.swift (PianoMap,
 * PianoSynth, PianoPedal, VoicePool) — test chốt checksum chung.
 */

/**
 * Phím → nốt (MIDI), hàm thuần theo VỊ TRÍ trên QWERTY: trái → phải đi lên ngũ cung; hàng chữ dưới
 * bắt đầu C3, hàng giữa C4, hàng trên C5 (~3 quãng tám, C3 … A6); hàng số cao thêm một quãng tám
 * (C6 … A7). Ký tự khác: bậc = mã ký tự % 10 trên nền C4. Cách: C3+G3+C4 nhẹ; Enter: C4+G4+C5
 * (chủ + năm); ⌫: A2 tắt tiếng; phím chức năng khác: C3.
 */
object PianoMap {
    val ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
    val ROW_BASE = intArrayOf(84, 72, 60, 48)
    val SCALE = intArrayOf(0, 2, 4, 7, 9)
    const val FALLBACK_BASE = 60
    val SPACE_CHORD = listOf(48, 55, 60)
    val RETURN_CHORD = listOf(60, 67, 72)
    const val DELETE_NOTE = 45
    const val MODIFIER_NOTE = 48

    fun scaleNote(base: Int, step: Int): Int = base + 12 * (step / 5) + SCALE[step % 5]
    fun midi(row: Int, col: Int): Int = scaleNote(ROW_BASE[row], col)

    /** Bảng ASCII dựng sẵn (−1 = không có) ⇒ tra mỗi phím không cấp phát. */
    private val ASCII = IntArray(128) { -1 }.also { t ->
        ROWS.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> t[ch.code] = midi(r, c) } }
    }

    /** Nốt cho một mã ký tự (code point). Hoa ⇒ như thường. */
    fun midi(codePoint: Int): Int {
        var x = codePoint
        if (x in 65..90) x += 32
        if (x in 0..127 && ASCII[x] >= 0) return ASCII[x]
        return scaleNote(FALLBACK_BASE, (x.toLong() and 0xFFFFFFFFL).rem(10).toInt())
    }

    /** Nốt cho nhãn phím ("a", "Q", "7", ","…). Rỗng ⇒ C4. */
    fun midiForKey(key: String?): Int =
        if (key.isNullOrEmpty()) FALLBACK_BASE else midi(key.codePointAt(0))

    /** Mọi nốt có thể phát (ngũ cung C3 … A7 = 25 nốt), tăng dần. */
    val NOTES: List<Int> = buildSet {
        ROWS.forEachIndexed { r, row -> for (c in row.indices) add(midi(r, c)) }
        for (st in 0 until 10) add(scaleNote(FALLBACK_BASE, st))
        addAll(SPACE_CHORD); addAll(RETURN_CHORD); add(MODIFIER_NOTE)
    }.sorted()
}

/**
 * Sự kiện âm Piano dạng Int (không cấp phát mỗi phím): 0…127 = nốt MIDI, số âm = âm đặc biệt.
 * iOS: enum PianoEvent (giá trị, cũng không cấp phát).
 */
object PianoEvent {
    const val SPACE = -1
    const val RETURN = -2
    const val DELETE = -3
    const val PEDAL = -4
    /** Chỉ số trong bộ âm đặc biệt (cách, Enter, ⌫, pedal). */
    fun extraIndex(ev: Int): Int = -ev - 1
}

/**
 * Tổng hợp nốt piano: hoạ âm 1–5 (biên độ giảm, phi điều hoà B = 0.00035), mở 2.5 ms, tắt hai tầng,
 * hoạ âm cao tắt nhanh hơn, nốt trầm ngân lâu hơn; chuẩn hoá đỉnh mỗi nốt. Thường: 22.05 kHz 0.8 s;
 * ngân (pedal): 11.025 kHz 2.5 s.
 */
object PianoSynth {
    const val SAMPLE_RATE = 22_050
    const val SUSTAIN_SAMPLE_RATE = 11_025
    const val NOTE_DURATION = 0.8
    const val SUSTAIN_DURATION = 2.5
    const val MUTED_DURATION = 0.15
    const val PEDAL_DURATION = 0.6
    const val NOTE_PEAK = 0.34
    const val CHORD_PEAK = 0.30
    const val PEDAL_PEAK = 0.30
    const val INHARMONICITY = 0.00035
    val PARTIAL_AMPS = doubleArrayOf(1.0, 0.45, 0.28, 0.16, 0.09)
    /** Số giọng (polyphony): bấm nhiều phím cùng lúc ⇒ hợp âm. */
    const val VOICES = 8

    enum class Kind { NORMAL, SUSTAIN, MUTED }

    fun freq(m: Int): Double = 440 * 2.0.pow((m - 69) / 12.0)

    fun samples(midis: List<Int>, kind: Kind, peak: Double): FloatArray {
        val sr = if (kind == Kind.SUSTAIN) SUSTAIN_SAMPLE_RATE.toDouble() else SAMPLE_RATE.toDouble()
        val dur = when (kind) { Kind.SUSTAIN -> SUSTAIN_DURATION; Kind.MUTED -> MUTED_DURATION; Kind.NORMAL -> NOTE_DURATION }
        val n = (dur * sr).roundToInt()
        val out = DoubleArray(n)
        for (m in midis) {
            val f = freq(m)
            val s = min(1.6, max(0.5, (262 / f).pow(0.3)))
            val (t1, t2) = when (kind) {
                Kind.NORMAL -> 0.10 * s to 0.42 * s
                Kind.SUSTAIN -> 0.22 * s to 1.0 * s
                Kind.MUTED -> 0.025 to 0.05
            }
            for (k in 1..PARTIAL_AMPS.size) {
                val kd = k.toDouble()
                val fk = kd * f * sqrt(1 + INHARMONICITY * kd * kd)
                if (fk >= 0.45 * sr) break
                val a = PARTIAL_AMPS[k - 1] * exp(-(kd - 1) * f / 4000)
                val d1 = exp(-1 / (sr * t1 / sqrt(kd)))
                val d2 = exp(-1 / (sr * t2 / sqrt(kd)))
                val w = 2 * PI * fk / sr
                var e1 = 0.65; var e2 = 0.35
                for (i in 0 until n) {
                    out[i] += a * (e1 + e2) * sin(w * i)
                    e1 *= d1; e2 *= d2
                }
            }
        }
        return finish(out, sr, 0.0025, if (kind == Kind.SUSTAIN) 0.2 else 0.06, peak)
    }

    /** Mở/đóng raised-cosine + chuẩn hoá đỉnh. */
    fun finish(x: DoubleArray, sr: Double, attack: Double, release: Double, peak: Double): FloatArray {
        val n = x.size
        val ai = (attack * sr).toInt(); val rel = min(n, (release * sr).toInt())
        var pk = 0.0
        for (i in 0 until n) {
            var v = x[i]
            if (i < ai) v *= 0.5 - 0.5 * cos(PI * i / ai)
            val fe = n - 1 - i
            if (fe < rel) v *= 0.5 - 0.5 * cos(PI * fe / rel)
            x[i] = v
            pk = max(pk, abs(v))
        }
        val g = if (pk > 0) peak / pk else 0.0
        return FloatArray(n) { (x[it] * g).toFloat() }
    }

    fun note(m: Int, sustain: Boolean = false): FloatArray =
        samples(listOf(m), if (sustain) Kind.SUSTAIN else Kind.NORMAL, NOTE_PEAK)
    fun space(): FloatArray = samples(PianoMap.SPACE_CHORD, Kind.NORMAL, CHORD_PEAK)
    fun returnChord(): FloatArray = samples(PianoMap.RETURN_CHORD, Kind.NORMAL, CHORD_PEAK)
    fun delete(): FloatArray = samples(listOf(PianoMap.DELETE_NOTE), Kind.MUTED, NOTE_PEAK)

    /** "Đạp pedal": thịch trầm 75→55 Hz tắt nhanh + cộng hưởng mờ C4 G4 C5 G5 dâng chậm rồi tắt. */
    fun pedal(): FloatArray {
        val sr = SAMPLE_RATE.toDouble()
        val n = (PEDAL_DURATION * sr).roundToInt()
        val out = DoubleArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / sr
            phase += 2 * PI * (55 + 20 * exp(-t / 0.02)) / sr
            out[i] += sin(phase) * exp(-t / 0.045)
        }
        for (m in intArrayOf(60, 67, 72, 79)) {
            val w = 2 * PI * freq(m) / sr
            for (i in 0 until n) {
                val t = i / sr
                out[i] += 0.06 * sin(w * i) * (1 - exp(-t / 0.12)) * exp(-t / 0.35)
            }
        }
        return finish(out, sr, 0.003, 0.06, PEDAL_PEAK)
    }

    /** Nghe thử trong app: C4 E4 G4 C5 nối nhau (cách 0.16 s, ngân chồng). */
    fun previewMelody(): FloatArray {
        val step = (0.16 * SAMPLE_RATE).roundToInt()
        val notes = listOf(60, 64, 67, 72).map { note(it) }
        val out = FloatArray(step * (notes.size - 1) + notes[0].size)
        notes.forEachIndexed { i, x -> for (j in x.indices) out[i * step + j] += x[j] * 0.8f }
        return out
    }

    /** Số mẫu PCM của bộ nốt dựng sẵn (25 nốt + cách + Enter + ⌫ + pedal). Android lưu PCM16 (×2 byte). */
    val normalBankSamples: Int get() {
        val note = (NOTE_DURATION * SAMPLE_RATE).roundToInt()
        return PianoMap.NOTES.size * note + 2 * note + (MUTED_DURATION * SAMPLE_RATE).roundToInt() +
            (PEDAL_DURATION * SAMPLE_RATE).roundToInt()
    }
    val sustainBankSamples: Int get() = PianoMap.NOTES.size * (SUSTAIN_DURATION * SUSTAIN_SAMPLE_RATE).roundToInt()
}

/**
 * Pedal ngân (Shift / Caps Lock) — máy trạng thái thuần (giống iOS PianoPedal). Shift một-lần ⇒ nốt
 * KẾ TIẾP ngân; Caps ⇒ giữ tới khi tắt (damper). Shift tự bật đầu câu không tính.
 */
class PianoPedal {
    enum class State { OFF, ONCE, CAPS }
    enum class Shift { OFF, ON, CAPS }
    enum class Action { NONE, PEDAL_DOWN, DAMPER }

    var state = State.OFF
        private set
    val isDown: Boolean get() = state != State.OFF

    /** Bàn phím hiện lại / đổi kiểu: pedal nhả. */
    fun reset() { state = State.OFF }

    /** Người dùng bấm Shift; [result] = trạng thái Shift SAU khi bấm. */
    fun shiftPressed(result: Shift): Action = when (result) {
        Shift.ON -> { state = State.ONCE; Action.PEDAL_DOWN }
        Shift.CAPS -> { state = State.CAPS; Action.PEDAL_DOWN }
        Shift.OFF -> { val was = state; state = State.OFF; if (was == State.CAPS) Action.DAMPER else Action.NONE }
    }

    /** Gõ một nốt: true ⇒ nốt này ngân. Pedal một-lần nhả sau nốt. */
    fun noteTyped(): Boolean {
        val s = state != State.OFF
        if (state == State.ONCE) state = State.OFF
        return s
    }

    /** Shift đổi không do bấm: Caps tắt ⇒ damper. */
    fun shiftChanged(now: Shift): Action {
        if (state == State.CAPS && now != Shift.CAPS) { state = State.OFF; return Action.DAMPER }
        return Action.NONE
    }
}

/** Pool giọng xoay vòng, không cấp phát; giọng cùng cửa sổ hợp âm hạ 1/√n (giống iOS VoicePool). */
class VoicePool(val size: Int) {
    var next = 0
        private set
    private val starts = DoubleArray(size) { Double.NEGATIVE_INFINITY }

    fun allocate(now: Double): Int {
        val v = next
        next = (next + 1) % size
        starts[v] = now
        return v
    }

    fun isRecent(v: Int, now: Double): Boolean = now - starts[v] <= CHORD_WINDOW

    fun simultaneous(now: Double): Int {
        var c = 0
        for (v in 0 until size) if (isRecent(v, now)) c++
        return c
    }

    companion object {
        const val CHORD_WINDOW = 0.06
        fun chordScale(n: Int): Float = 1f / sqrt(max(1, n).toFloat())
    }
}
