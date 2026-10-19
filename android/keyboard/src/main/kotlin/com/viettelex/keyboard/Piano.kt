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
 * nhạc, không nghịch tai. Đàn tranh (theme Sen Việt) dùng chung bảng vị trí / pool / pedal, khác thang + tiếng.
 * Thuật toán + bảng Y HỆT iOS/Shared/KeySoundSynth.swift (NoteMap, PianoSynth, DanTranhSynth, KeyInstrument,
 * PianoPedal, VoicePool) — test chốt checksum chung.
 */

/**
 * Phím → nốt (MIDI), hàm thuần theo VỊ TRÍ trên QWERTY — chung mọi nhạc cụ "mỗi phím một nốt" (Piano, Đàn
 * tranh), chỉ khác thang 5 bậc + hợp âm (iOS struct NoteMap y hệt). Trái → phải đi lên ngũ cung; hàng chữ dưới
 * bắt đầu C3, hàng giữa C4, hàng trên C5 (~3 quãng tám, C3 … A6); hàng số cao thêm một quãng tám (C6 … A7).
 * Ký tự khác: bậc = mã ký tự % 10 trên nền C4.
 */
class NoteMap(
    val scale: IntArray,
    val spaceChord: List<Int>,
    val returnChord: List<Int>,
    val deleteNote: Int,
    val modifierNote: Int,
) {
    fun scaleNote(base: Int, step: Int): Int = base + 12 * (step / 5) + scale[step % 5]
    fun midi(row: Int, col: Int): Int = scaleNote(ROW_BASE[row], col)

    /** Bảng ASCII dựng sẵn (−1 = không có) ⇒ tra mỗi phím không cấp phát. */
    private val ascii = IntArray(128) { -1 }.also { t ->
        ROWS.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> t[ch.code] = midi(r, c) } }
    }

    /** Nốt cho một mã ký tự (code point). Hoa ⇒ như thường. */
    fun midi(codePoint: Int): Int {
        var x = codePoint
        if (x in 65..90) x += 32
        if (x in 0..127 && ascii[x] >= 0) return ascii[x]
        return scaleNote(FALLBACK_BASE, (x.toLong() and 0xFFFFFFFFL).rem(10).toInt())
    }

    /** Nốt cho nhãn phím ("a", "Q", "7", ","…). Rỗng ⇒ C4. */
    fun midiForKey(key: String?): Int =
        if (key.isNullOrEmpty()) FALLBACK_BASE else midi(key.codePointAt(0))

    /** Mọi nốt có thể phát (25 nốt C3 … A7), tăng dần. */
    val notes: List<Int> = buildSet {
        ROWS.forEachIndexed { r, row -> for (c in row.indices) add(midi(r, c)) }
        for (st in 0 until 10) add(scaleNote(FALLBACK_BASE, st))
        addAll(spaceChord); addAll(returnChord); add(modifierNote)
    }.sorted()

    companion object {
        val ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
        val ROW_BASE = intArrayOf(84, 72, 60, 48)
        const val FALLBACK_BASE = 60
        /** Piano: ngũ cung trưởng C D E G A; cách C3+G3+C4, Enter C4+G4+C5 (chủ + năm), ⌫ A2 tắt tiếng, chức năng C3. */
        val PIANO = NoteMap(intArrayOf(0, 2, 4, 7, 9), listOf(48, 55, 60), listOf(60, 67, 72), 45, 48)
        /**
         * Đàn tranh: ngũ cung "điệu Bắc" Hò Xự Xang Xê Cống ≈ C D F G A; cách: dây buông C3+G3+C4 rải nhẹ;
         * Enter: Hò–Xang–Hò C4+F4+C5 rải; ⌫: dây A2 bị chặn tiếng; chức năng: C3.
         */
        val DANTRANH = NoteMap(intArrayOf(0, 2, 5, 7, 9), listOf(48, 55, 60), listOf(60, 65, 72), 45, 48)
    }
}

/** Bảng Piano (giữ API cũ) — uỷ cho [NoteMap.PIANO]. */
object PianoMap {
    val ROWS: List<String> get() = NoteMap.ROWS
    val ROW_BASE: IntArray get() = NoteMap.ROW_BASE
    val SCALE: IntArray get() = NoteMap.PIANO.scale
    const val FALLBACK_BASE = NoteMap.FALLBACK_BASE
    val SPACE_CHORD: List<Int> get() = NoteMap.PIANO.spaceChord
    val RETURN_CHORD: List<Int> get() = NoteMap.PIANO.returnChord
    const val DELETE_NOTE = 45
    const val MODIFIER_NOTE = 48

    fun scaleNote(base: Int, step: Int): Int = NoteMap.PIANO.scaleNote(base, step)
    fun midi(row: Int, col: Int): Int = NoteMap.PIANO.midi(row, col)
    fun midi(codePoint: Int): Int = NoteMap.PIANO.midi(codePoint)
    fun midiForKey(key: String?): Int = NoteMap.PIANO.midiForKey(key)

    /** Mọi nốt có thể phát (ngũ cung C3 … A7 = 25 nốt), tăng dần. */
    val NOTES: List<Int> get() = NoteMap.PIANO.notes
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
 * Đàn tranh (Phil 19/10/2026, theme "Sen Việt"): dây gảy tổng hợp cộng hoạ âm — tất định, Y HỆT iOS
 * DanTranhSynth (test chốt checksum chung; công thức + lý do ghi ở file Swift). Dùng chung hạ tầng Piano:
 * 22.05 kHz (ngân 11.025 kHz 2.5 s), pool giọng, pedal, [PianoSynth.finish]. Hoạ âm 1…8 |sin(π·k·0.2)|/k
 * (tắt nhanh dần theo k), mở 1.2 ms, tắt hai tầng 0.09 / 0.42 s, "nhấn nhá" +7 cent lúc gảy + rung 5.2 Hz ±12 cent
 * từ 0.16 s; cách / Enter rải dây; pedal = vuốt 6 dây cao; ⌫ = dây chặn tiếng. Nhỏ hơn Piano.
 */
object DanTranhSynth {
    const val NOTE_DURATION = 1.0
    const val NOTE_PEAK = 0.30
    const val CHORD_PEAK = 0.26
    const val PEDAL_PEAK = 0.18
    const val PLUCK_POSITION = 0.2
    const val PARTIALS = 8
    const val VIBRATO_RATE = 5.2
    const val VIBRATO_DEPTH = 0.007
    const val VIBRATO_DELAY = 0.16
    const val VIBRATO_RISE = 0.3
    const val PLUCK_BEND = 0.004
    const val PLUCK_BEND_TAU = 0.03
    const val ATTACK = 0.0012
    const val SPACE_STRUM = 0.018
    const val RETURN_STRUM = 0.026
    const val BRUSH_STRUM = 0.022
    val BRUSH_NOTES = listOf(69, 72, 74, 77, 79, 81)

    enum class Kind { NORMAL, SUSTAIN, MUTED, BRUSH }

    fun samples(midis: List<Int>, kind: Kind, peak: Double, strum: Double = 0.0): FloatArray {
        val sr = if (kind == Kind.SUSTAIN) PianoSynth.SUSTAIN_SAMPLE_RATE.toDouble() else PianoSynth.SAMPLE_RATE.toDouble()
        val dur = when (kind) {
            Kind.NORMAL -> NOTE_DURATION
            Kind.SUSTAIN -> PianoSynth.SUSTAIN_DURATION
            Kind.MUTED -> PianoSynth.MUTED_DURATION
            Kind.BRUSH -> PianoSynth.PEDAL_DURATION
        }
        val n = (dur * sr).roundToInt()
        val out = DoubleArray(n)
        val amps = DoubleArray(PARTIALS); val e1 = DoubleArray(PARTIALS); val e2 = DoubleArray(PARTIALS)
        val d1 = DoubleArray(PARTIALS); val d2 = DoubleArray(PARTIALS)
        val vib = kind == Kind.NORMAL || kind == Kind.SUSTAIN
        midis.forEachIndexed { j, m ->
            val f = PianoSynth.freq(m)
            val s = min(1.5, max(0.6, (262 / f).pow(0.25)))
            val (t1, t2) = when (kind) {
                Kind.NORMAL -> 0.09 * s to 0.42 * s
                Kind.SUSTAIN -> 0.2 * s to 1.0 * s
                Kind.MUTED -> 0.018 to 0.04
                Kind.BRUSH -> 0.03 to 0.11
            }
            var kmax = 0
            for (k in 0 until PARTIALS) {
                val kd = (k + 1).toDouble()
                if (kd * f >= 0.45 * sr) break
                amps[k] = abs(sin(PI * kd * PLUCK_POSITION)) / kd * exp(-(kd - 1) * f / 6000)
                val damp = 1 + 0.6 * (kd - 1)
                d1[k] = exp(-damp / (sr * t1)); d2[k] = exp(-damp / (sr * t2))
                e1[k] = 0.6; e2[k] = 0.4
                kmax = k + 1
            }
            val start = (j * strum * sr).roundToInt()
            if (start >= n || kmax == 0) return@forEachIndexed
            var phase = 0.0
            for (i in start until n) {
                val tt = (i - start) / sr
                var ratio = 1 + PLUCK_BEND * exp(-tt / PLUCK_BEND_TAU)
                if (vib && tt > VIBRATO_DELAY) {
                    val u = tt - VIBRATO_DELAY
                    ratio += VIBRATO_DEPTH * (1 - exp(-u / VIBRATO_RISE)) * sin(2 * PI * VIBRATO_RATE * u)
                }
                phase += 2 * PI * f * ratio / sr
                val c2 = 2 * cos(phase)
                var prev = 0.0; var cur = sin(phase); var acc = 0.0
                for (k in 0 until kmax) {
                    if (k > 0) { val nx = c2 * cur - prev; prev = cur; cur = nx }
                    acc += amps[k] * (e1[k] + e2[k]) * cur
                    e1[k] *= d1[k]; e2[k] *= d2[k]
                }
                if (tt < ATTACK) acc *= 0.5 - 0.5 * cos(PI * tt / ATTACK)
                out[i] += acc
            }
        }
        val release = when (kind) { Kind.NORMAL -> 0.08; Kind.SUSTAIN -> 0.2; Kind.MUTED -> 0.03; Kind.BRUSH -> 0.06 }
        return PianoSynth.finish(out, sr, ATTACK, release, peak)
    }

    fun note(m: Int, sustain: Boolean = false): FloatArray =
        samples(listOf(m), if (sustain) Kind.SUSTAIN else Kind.NORMAL, NOTE_PEAK)
    fun space(): FloatArray = samples(NoteMap.DANTRANH.spaceChord, Kind.NORMAL, CHORD_PEAK, SPACE_STRUM)
    fun returnChord(): FloatArray = samples(NoteMap.DANTRANH.returnChord, Kind.NORMAL, CHORD_PEAK, RETURN_STRUM)
    fun delete(): FloatArray = samples(listOf(NoteMap.DANTRANH.deleteNote), Kind.MUTED, NOTE_PEAK)
    /** Pedal (Shift/Caps): vuốt nhẹ 6 dây cao A4 C5 D5 F5 G5 A5. */
    fun pedal(): FloatArray = samples(BRUSH_NOTES, Kind.BRUSH, PEDAL_PEAK, BRUSH_STRUM)

    /** Nghe thử trong app: G4 A4 C5 A4 G4 (điệu Bắc), cách 0.17 s, ngân chồng. */
    fun previewMelody(): FloatArray {
        val step = (0.17 * PianoSynth.SAMPLE_RATE).roundToInt()
        val notes = listOf(67, 69, 72, 69, 67).map { note(it) }
        val out = FloatArray(step * (notes.size - 1) + notes[0].size)
        notes.forEachIndexed { i, x -> for (j in x.indices) out[i * step + j] += x[j] * 0.8f }
        return out
    }

    /** Số mẫu PCM bộ nốt dựng sẵn (25 nốt + cách + Enter + ⌫ + pedal). Android lưu PCM16 (×2 byte). */
    val normalBankSamples: Int get() {
        val note = (NOTE_DURATION * PianoSynth.SAMPLE_RATE).roundToInt()
        return NoteMap.DANTRANH.notes.size * note + 2 * note + (PianoSynth.MUTED_DURATION * PianoSynth.SAMPLE_RATE).roundToInt() +
            (PianoSynth.PEDAL_DURATION * PianoSynth.SAMPLE_RATE).roundToInt()
    }
    val sustainBankSamples: Int get() =
        NoteMap.DANTRANH.notes.size * (PianoSynth.SUSTAIN_DURATION * PianoSynth.SUSTAIN_SAMPLE_RATE).roundToInt()
}

/**
 * Nhạc cụ "mỗi phím một nốt" — chọn bảng nốt + bộ tổng hợp; Feedback dùng CHUNG pool giọng, pedal, id theo MIDI
 * cho mọi nhạc cụ (không nhân bản đường Piano). [cacheTag] = tiền tố tên WAV cache. iOS KeyInstrument y hệt.
 */
enum class KeyInstrument(val style: KeySoundStyle, val map: NoteMap, val cacheTag: String) {
    PIANO(KeySoundStyle.PIANO, NoteMap.PIANO, "piano-v1"),
    DANTRANH(KeySoundStyle.DANTRANH, NoteMap.DANTRANH, "dantranh-v1");

    fun note(m: Int, sustain: Boolean = false): FloatArray =
        if (this == PIANO) PianoSynth.note(m, sustain) else DanTranhSynth.note(m, sustain)
    fun space(): FloatArray = if (this == PIANO) PianoSynth.space() else DanTranhSynth.space()
    fun returnChord(): FloatArray = if (this == PIANO) PianoSynth.returnChord() else DanTranhSynth.returnChord()
    fun delete(): FloatArray = if (this == PIANO) PianoSynth.delete() else DanTranhSynth.delete()
    fun pedal(): FloatArray = if (this == PIANO) PianoSynth.pedal() else DanTranhSynth.pedal()
    fun previewMelody(): FloatArray = if (this == PIANO) PianoSynth.previewMelody() else DanTranhSynth.previewMelody()
    val normalBankSamples: Int get() = if (this == PIANO) PianoSynth.normalBankSamples else DanTranhSynth.normalBankSamples
    val sustainBankSamples: Int get() = if (this == PIANO) PianoSynth.sustainBankSamples else DanTranhSynth.sustainBankSamples

    companion object {
        fun of(style: KeySoundStyle): KeyInstrument? = entries.firstOrNull { it.style == style }
        /** Kiểu đã lưu → nhạc cụ (null = đường 3 tiếng). */
        fun of(raw: String?): KeyInstrument? = of(KeySoundStyle.resolve(raw, true))
    }
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
