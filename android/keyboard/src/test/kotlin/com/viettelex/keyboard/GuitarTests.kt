package com.viettelex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Vòng hợp âm phím cách ([ChordProgression] — Piano / Ghi-ta) + âm "Ghi-ta mộc" ([GuitarSynth]) — số chung iOS
 * KeySoundTests (testChordProgressionPure, testProgressionTables, testGuitar*).
 */
class GuitarTests {
    private fun sum(a: FloatArray) = a.sumOf { abs(it).toDouble() }
    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    /** Tiến mỗi lần cách, vòng lại; nghỉ > 3 s ⇒ về đầu; kết câu ⇒ về đầu; Enter ⇒ chủ rồi về đầu; gõ chữ không reset. */
    @Test fun chordProgressionPure() {
        val p = ChordProgression(4)
        assertEquals(listOf(0, 1, 2, 3, 0, 1), (0 until 6).map { p.space(1 + it * 0.5) })
        assertEquals(2, p.space(6.4))
        assertEquals("nghỉ 3.1 s ⇒ về hợp âm đầu", 0, p.space(9.5))
        for (k in 1..8) p.typed(9.5 + k * 0.6, false)
        assertEquals("đang gõ ⇒ vòng tiếp tục", 1, p.space(14.5))
        p.typed(14.8, true)
        assertEquals(0, p.space(15.0))
        assertEquals(1, p.space(15.2))
        p.returnKey(15.4)
        assertEquals(0, p.index)
        assertEquals(0, p.space(15.6))
        p.typed(30.0, false)
        assertEquals(0, p.space(30.2))
        p.reset()
        assertEquals(ChordProgression(4).index, p.index)
        assertEquals(Double.NEGATIVE_INFINITY, p.lastTime, 0.0)
        val z = ChordProgression(0)
        assertEquals(listOf(0, 0), listOf(z.space(0.0), z.space(0.1)))
        for (k in listOf(".", "!", "?")) assertTrue(k, ChordProgression.isSentenceEnd(k))
        for (k in listOf(",", "a", "", "..", ";", null)) assertFalse("$k", ChordProgression.isSentenceEnd(k))
        assertEquals(3.0, ChordProgression.IDLE_RESET, 0.0)
    }

    @Test fun progressionTables() {
        fun classes(c: List<Int>) = c.map { it % 12 }.toSet()
        assertEquals(listOf(setOf(0, 4, 7), setOf(9, 0, 4), setOf(5, 9, 0), setOf(7, 11, 2)), NoteMap.PIANO.progression.map(::classes))
        assertEquals(listOf(setOf(9, 0, 4), setOf(5, 9, 0), setOf(0, 4, 7), setOf(7, 11, 2)), NoteMap.GUITAR.progression.map(::classes))
        assertEquals(listOf(4, 5, 7, 7), NoteMap.GUITAR.progression.map { it.first() % 12 })
        for (c in NoteMap.GUITAR.progression) { assertEquals(6, c.size); assertEquals("quạt trầm → cao", c.sorted(), c) }
        assertEquals(setOf(0, 4, 7), classes(NoteMap.GUITAR.returnChord))
        assertEquals(6, NoteMap.GUITAR.returnChord.size)
        assertTrue(NoteMap.DANTRANH.progression.isEmpty())
        assertTrue(KeyInstrument.PIANO.hasProgression); assertTrue(KeyInstrument.GUITAR.hasProgression)
        assertFalse(KeyInstrument.DANTRANH.hasProgression)
        assertEquals(NoteMap.PIANO.notes, NoteMap.GUITAR.notes)
        assertEquals(25, NoteMap.PIANO.notes.size)
        assertEquals("Enter Piano giữ hợp âm chủ", listOf(60, 67, 72), PianoMap.RETURN_CHORD)
        assertEquals(PianoEvent.MAX_CHORDS, NoteMap.GUITAR.progression.size)
        assertEquals(PianoEvent.MAX_CHORDS, NoteMap.PIANO.progression.size)
    }

    @Test fun guitarMapping() {
        for (c in "1234567890qwertyuiopasdfghjklzxcvbnm,.?!'đ") assertEquals("$c", PianoMap.midiForKey("$c"), NoteMap.GUITAR.midiForKey("$c"))
        assertEquals(KeyInstrument.GUITAR, KeyInstrument.of("guitar"))
        assertEquals(KeySoundStyle.GUITAR, KeyInstrument.GUITAR.style)
        assertEquals("Ghi-ta mộc", KeySoundStyle.GUITAR.viTitle)
        assertEquals("Acoustic guitar", L10nTable.EN["Ghi-ta mộc"])
        assertEquals("guitar-v1", KeyInstrument.GUITAR.cacheTag)
    }

    /** Checksum chung iOS testGuitarSynthParity. */
    @Test fun guitarChecksums() {
        val golden = listOf(Triple(48, 691.3709, 675.6130), Triple(60, 700.1426, 708.1190), Triple(72, 558.9103, 561.0567),
            Triple(93, 446.2891, 575.5285), Triple(105, 593.3481, 840.8058))
        for ((m, n, sus) in golden) {
            val a = GuitarSynth.note(m)
            assertEquals(26_460, a.size)
            assertEquals("$m", n, sum(a), 0.02)
            assertEquals("$m ngân", sus, sum(GuitarSynth.note(m, true)), 0.02)
            assertEquals(GuitarSynth.NOTE_PEAK, a.maxOf { abs(it) }.toDouble(), 1e-4)
            assertEquals(0f, a.first(), 1e-6f); assertEquals(0f, a.last(), 1e-6f)
        }
        listOf(651.0677, 618.7805, 683.5506, 505.9570).forEachIndexed { i, c ->
            assertEquals("hợp âm $i", c, sum(GuitarSynth.chord(i)), 0.02)
        }
        assertTrue(GuitarSynth.space().contentEquals(GuitarSynth.chord(0)))
        assertEquals(620.5448, sum(GuitarSynth.returnChord()), 0.02)
        assertEquals(69.6841, sum(GuitarSynth.delete()), 0.02)
        assertEquals(140.4053, sum(GuitarSynth.pedal()), 0.02)
        assertEquals(1959.4907, sum(GuitarSynth.previewMelody()), 0.05)
        assertFalse(GuitarSynth.note(60).contentEquals(DanTranhSynth.note(60)))
        listOf(411.0733, 514.2972, 461.2571, 445.9170).forEachIndexed { i, c ->
            assertEquals("Piano hợp âm $i", c, sum(PianoSynth.chord(i)), 0.02)
        }
    }

    /** Quạt 6 dây 25 ms (Enter 35 ms): dây j bắt đầu ở round(j·strum·22050); trước dây 2 chỉ có dây đầu. */
    @Test fun strumTiming() {
        val sr = PianoSynth.SAMPLE_RATE.toDouble()
        assertEquals(listOf(0, 551, 1103, 1654, 2205, 2756), (0 until 6).map { GuitarSynth.onset(it, GuitarSynth.STRUM, sr) })
        assertEquals(listOf(0, 772, 1544, 2315, 3087, 3859), (0 until 6).map { GuitarSynth.onset(it, GuitarSynth.TONIC_STRUM, sr) })
        val chord = NoteMap.GUITAR.progression[0]
        val a = GuitarSynth.samples(chord, GuitarSynth.Kind.NORMAL, 1.0, GuitarSynth.STRUM)
        val one = GuitarSynth.samples(listOf(chord[0]), GuitarSynth.Kind.NORMAL, 1.0)
        val k = a[300].toDouble() / one[300]
        for (i in 40 until 548 step 7) if (abs(one[i]) > 1e-4) assertEquals("mẫu $i", k, a[i].toDouble() / one[i], 1e-3)
        var diff = 0.0
        for (i in 560 until 1100) diff += abs(a[i] - k * one[i])
        assertTrue(diff > 1)
    }

    /** Không to hơn Piano; không vibrato (đuôi A4 ≈ 440 Hz ±0.5 %). */
    @Test fun guitarNotLouderThanPiano() {
        assertTrue(GuitarSynth.NOTE_PEAK <= PianoSynth.NOTE_PEAK)
        assertTrue(GuitarSynth.CHORD_PEAK <= PianoSynth.CHORD_PEAK)
        assertTrue(GuitarSynth.PEDAL_PEAK <= PianoSynth.PEDAL_PEAK)
        assertTrue(GuitarSynth.DELETE_PEAK <= PianoSynth.NOTE_PEAK)
        for (m in listOf(48, 60, 72, 93, 105)) assertTrue("$m", rms(GuitarSynth.note(m)) < rms(PianoSynth.note(m)))
        val pianoChordMax = (0 until 4).maxOf { rms(PianoSynth.chord(it)) }
        for (i in 0 until 4) assertTrue("hợp âm $i", rms(GuitarSynth.chord(i)) <= pianoChordMax)
        assertTrue(rms(GuitarSynth.returnChord()) <= rms(PianoSynth.returnChord()))
        assertTrue(rms(GuitarSynth.delete()) < rms(PianoSynth.delete()))
        assertTrue(rms(GuitarSynth.pedal()) < rms(PianoSynth.pedal()))
        assertTrue(VoicePool.chordScale(8) * 8 * GuitarSynth.NOTE_PEAK <= 1.0)
        val a4 = GuitarSynth.note(69)
        var crossings = 0
        for (i in 13_000 until 17_400) if ((a4[i] <= 0) != (a4[i + 1] <= 0)) crossings++
        val hz = crossings / 2.0 / (4_400.0 / PianoSynth.SAMPLE_RATE)
        assertEquals("A4 lệch cao độ / có rung", 440.0, hz, 440 * 0.005)
    }

    /** 810 338 mẫu (PCM16 ≈ 1.62 MB; iOS Float32 3.24 MB ≤ 1.5 × Piano); bộ ngân cùng cỡ Piano. */
    @Test fun guitarMemoryBound() {
        assertEquals(810_338, GuitarSynth.normalBankSamples)
        assertTrue(GuitarSynth.normalBankSamples * 2 <= 2 * 1024 * 1024)
        assertTrue(GuitarSynth.normalBankSamples.toDouble() / PianoSynth.normalBankSamples <= 1.5)
        assertEquals(PianoSynth.sustainBankSamples, GuitarSynth.sustainBankSamples)
        val i = KeyInstrument.GUITAR
        val real = i.map.notes.sumOf { i.note(it).size } + (0 until 4).sumOf { i.chord(it).size } + i.returnChord().size +
            i.delete().size + i.pedal().size
        assertEquals(i.normalBankSamples, real)
    }

    /** Rung dây: số tham chiếu chung iOS testStringPluckModel. */
    @Test fun stringPluckParity() {
        assertEquals(KeyEffectBand.BELOW_KEYS, KeyEffect.STRING.band)
        assertFalse(KeyEffect.STRING.isParticle)
        assertEquals(0, KeyEffect.STRING.fixedColors.size)
        assertEquals("String vibration", L10nTable.EN["Rung dây"])
        val golden = listOf(3.0, 0.4329, -1.9465, -0.8606, 1.0957, 0.9104, -0.4938, -0.7711, 0.1184, 0.5666, 0.0818, -0.3676,
            -0.1626, 0.2069, 0.1720, -0.0933, -0.1456, 0.0224, 0.0)
        golden.forEachIndexed { f, g -> assertEquals("khung $f", g, StringPluck.amplitude(f), 1e-4) }
        for (f in 1 until StringPluck.FRAMES) {
            val t = f.toDouble() / StringPluck.FRAMES * StringPluck.DURATION
            assertTrue(abs(StringPluck.amplitude(f)) <= StringPluck.AMPLITUDE * exp(-t / StringPluck.DECAY) + 1e-9)
        }
        val s = StringPluck.shape(0)
        assertEquals(StringPluck.POINTS + 1, s.size)
        assertEquals(-50.0, s.first()[0], 0.0); assertEquals(50.0, s.last()[0], 0.0)
        assertEquals(0.0, s.first()[1], 1e-12); assertEquals(0.0, s.last()[1], 1e-12)
        assertEquals(StringPluck.AMPLITUDE, s[StringPluck.POINTS / 2][1], 1e-12)
        assertEquals(72.6, StringPluck.length(33.0), 1e-9)
        assertEquals(StringPluck.MIN_LENGTH, StringPluck.length(10.0), 0.0)
        assertEquals(StringPluck.MAX_LENGTH, StringPluck.length(200.0), 0.0)
        val gaps = listOf(ThemeDecoration.Gap(93.5, 11.0), ThemeDecoration.Gap(146.5, 11.0), ThemeDecoration.Gap(199.5, 11.0))
        assertEquals(0, StringPluck.target(46.0, 88.0, gaps).second)
        assertEquals("hoà ⇒ khe dưới", 1, StringPluck.target(99.0, 141.0, gaps).second)
        assertEquals(2, StringPluck.target(205.0, 247.0, gaps).second)
        assertEquals(199.5, StringPluck.target(205.0, 247.0, gaps).first, 0.0)
        assertEquals(54.0 to 0, StringPluck.target(10.0, 50.0, emptyList()))
        assertEquals(0.35, StringPluck.sweepDelay(0.0), 1e-9)
        // Android vẽ theo thời gian: hết 0,3 s ⇒ biên độ 0, vô hình (thôi hẹn khung hình).
        assertEquals(0.0, StringPluck.amplitudeAt(0.3), 0.0); assertEquals(0.0, StringPluck.alphaAt(0.3), 0.0)
        assertEquals(1.0, StringPluck.alphaAt(0.1), 0.0)
        assertEquals(StringPluck.amplitude(9), StringPluck.amplitudeAt(0.15), 1e-9)
        assertNotEquals(0.0, StringPluck.amplitudeAt(0.01))
    }
}
