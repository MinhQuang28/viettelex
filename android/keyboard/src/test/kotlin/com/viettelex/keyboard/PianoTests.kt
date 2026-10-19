package com.viettelex.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Kiểu "Piano" (19/10/2026) — giống iOS KeySoundTests (testPiano*, testVoicePoolChord). */
class PianoTests {
    /** Ánh xạ phím → nốt (chung iOS testPianoMapping). */
    @Test fun mapping() {
        val rows = PianoMap.ROWS.mapIndexed { r, row -> row.indices.map { PianoMap.midi(r, it) } }
        assertEquals(listOf(listOf(84, 86, 88, 91, 93, 96, 98, 100, 103, 105),
            listOf(72, 74, 76, 79, 81, 84, 86, 88, 91, 93),
            listOf(60, 62, 64, 67, 69, 72, 74, 76, 79),
            listOf(48, 50, 52, 55, 57, 60, 62)), rows)
        assertEquals(72, PianoMap.midiForKey("q"))
        assertEquals(72, PianoMap.midiForKey("Q"))
        assertEquals(62, PianoMap.midiForKey("m"))
        assertEquals(105, PianoMap.midiForKey("0"))
        assertEquals(69, PianoMap.midiForKey(","))
        assertEquals(67, PianoMap.midiForKey("đ"))
        assertEquals(60, PianoMap.midiForKey(null))
        assertEquals(25, PianoMap.NOTES.size)
        for (m in PianoMap.NOTES + PianoMap.SPACE_CHORD + PianoMap.RETURN_CHORD)
            assertTrue("$m ngoài ngũ cung", m % 12 in setOf(0, 2, 4, 7, 9))
        for (c in "1234567890qwertyuiopasdfghjklzxcvbnm,.?!'đ") assertTrue("$c", PianoMap.midiForKey(c.toString()) in PianoMap.NOTES)
        assertEquals(7, PianoMap.RETURN_CHORD[1] - PianoMap.RETURN_CHORD[0])
    }

    private fun sum(a: FloatArray) = a.sumOf { abs(it).toDouble() }

    /** Checksum chung iOS testPianoSynthParity. */
    @Test fun synthChecksums() {
        assertArrayEquals(PianoSynth.note(60), PianoSynth.note(60), 0f)
        val golden = listOf(Triple(48, 850.5462, 1019.8846), Triple(60, 726.9604, 875.0490), Triple(72, 644.0318, 751.4913),
            Triple(93, 523.8961, 599.4355), Triple(105, 481.8828, 593.3767))
        for ((m, n, sus) in golden) {
            val a = PianoSynth.note(m)
            assertEquals(17_640, a.size)
            assertEquals("$m", n, sum(a), 0.02)
            assertEquals("$m ngân", sus, sum(PianoSynth.note(m, true)), 0.02)
            assertEquals(PianoSynth.NOTE_PEAK.toFloat(), a.maxOf { abs(it) }, 1e-4f)
            assertEquals(0f, a.first(), 1e-6f); assertEquals(0f, a.last(), 1e-6f)
            val r = sqrt(a.copyOfRange(0, 1102).sumOf { (it * it).toDouble() } / 1102)
            assertEquals("$m", 0.175, r, 0.03)
        }
        assertEquals(447.8193, sum(PianoSynth.space()), 0.02)
        assertEquals(375.5216, sum(PianoSynth.returnChord()), 0.02)
        assertEquals(159.2696, sum(PianoSynth.delete()), 0.02)
        assertEquals(268.6398, sum(PianoSynth.pedal()), 0.02)
        assertEquals(27_563, PianoSynth.note(60, true).size)
    }

    /** Bộ nhớ: bộ thường 492 818 mẫu (iOS Float32 1.97 MB ≤ 2 MB; Android PCM16 ≈ 0.99 MB); bộ ngân lười. */
    @Test fun memoryBound() {
        assertEquals(492_818, PianoSynth.normalBankSamples)
        assertTrue(PianoSynth.normalBankSamples * 4 <= 2 * 1024 * 1024)
        assertEquals(689_075, PianoSynth.sustainBankSamples)
        val real = PianoMap.NOTES.sumOf { PianoSynth.note(it).size } + PianoSynth.space().size +
            PianoSynth.returnChord().size + PianoSynth.delete().size + PianoSynth.pedal().size
        assertEquals(PianoSynth.normalBankSamples, real)
    }

    @Test fun pedalStateMachine() {
        val p = PianoPedal()
        assertFalse(p.noteTyped())
        assertEquals(PianoPedal.Action.PEDAL_DOWN, p.shiftPressed(PianoPedal.Shift.ON))
        assertTrue(p.noteTyped())
        assertFalse("một-lần nhả sau một nốt", p.noteTyped())
        assertEquals(PianoPedal.Action.PEDAL_DOWN, p.shiftPressed(PianoPedal.Shift.ON))
        assertEquals(PianoPedal.Action.PEDAL_DOWN, p.shiftPressed(PianoPedal.Shift.CAPS))
        assertTrue(p.noteTyped()); assertTrue(p.noteTyped())
        assertEquals(PianoPedal.Action.DAMPER, p.shiftPressed(PianoPedal.Shift.OFF))
        assertFalse(p.noteTyped())
        assertEquals(PianoPedal.Action.PEDAL_DOWN, p.shiftPressed(PianoPedal.Shift.ON))
        assertEquals(PianoPedal.Action.NONE, p.shiftPressed(PianoPedal.Shift.OFF))
        assertFalse(p.isDown)
        p.shiftPressed(PianoPedal.Shift.CAPS)
        assertEquals(PianoPedal.Action.DAMPER, p.shiftChanged(PianoPedal.Shift.ON))
        assertEquals(PianoPedal.Action.NONE, p.shiftChanged(PianoPedal.Shift.OFF))
        assertEquals(PianoPedal.State.OFF, p.state)
        p.shiftPressed(PianoPedal.Shift.CAPS); p.reset()
        assertFalse(p.isDown)
    }

    /** 4 ngón cùng lúc (ACTION_POINTER_DOWN) ⇒ 4 giọng khác nhau, hạ 1/√4; xoay vòng về 0 sau 8. */
    @Test fun voicePoolChord() {
        val pool = VoicePool(8)
        val v = (0 until 4).map { pool.allocate(10.0) }
        assertEquals(4, v.toSet().size)
        assertEquals(4, pool.simultaneous(10.0))
        assertEquals(0.5f, VoicePool.chordScale(4), 1e-6f)
        assertEquals(1f, VoicePool.chordScale(1), 0f)
        assertEquals(0, pool.simultaneous(10.5))
        repeat(4) { pool.allocate(11.0) }
        assertEquals("round-robin", 0, pool.allocate(12.0))
        assertTrue(VoicePool.chordScale(8) * 8 * PianoSynth.NOTE_PEAK <= 1.0)
    }

    @Test fun eventCodes() {
        assertEquals(0, PianoEvent.extraIndex(PianoEvent.SPACE))
        assertEquals(1, PianoEvent.extraIndex(PianoEvent.RETURN))
        assertEquals(2, PianoEvent.extraIndex(PianoEvent.DELETE))
        assertEquals(3, PianoEvent.extraIndex(PianoEvent.PEDAL))
    }

    // ---- Đàn tranh (theme Sen Việt) — chung iOS testDanTranh*

    /** Ánh xạ phím → nốt: ngũ cung điệu Bắc C D F G A, cùng vị trí hàng/cột như Piano. */
    @Test fun danTranhMapping() {
        val m = NoteMap.DANTRANH
        val rows = NoteMap.ROWS.mapIndexed { r, row -> row.indices.map { m.midi(r, it) } }
        assertEquals(listOf(listOf(84, 86, 89, 91, 93, 96, 98, 101, 103, 105),
            listOf(72, 74, 77, 79, 81, 84, 86, 89, 91, 93),
            listOf(60, 62, 65, 67, 69, 72, 74, 77, 79),
            listOf(48, 50, 53, 55, 57, 60, 62)), rows)
        assertEquals(65, m.midiForKey("d"))
        assertEquals(77, m.midiForKey("E"))
        assertEquals(105, m.midiForKey("0"))
        assertEquals(69, m.midiForKey(","))
        assertEquals(67, m.midiForKey("đ"))
        assertEquals(60, m.midiForKey(null))
        assertEquals(25, m.notes.size)
        for (x in m.notes + m.spaceChord + m.returnChord + listOf(m.deleteNote, m.modifierNote))
            assertTrue("$x ngoài ngũ cung điệu Bắc", x % 12 in listOf(0, 2, 5, 7, 9))
        for (c in "1234567890qwertyuiopasdfghjklzxcvbnm,.?!'đ") assertTrue("$c", m.midiForKey(c.toString()) in m.notes)
        for (c in "qwertyuiopasdfghjklzxcvbnm") {
            val a = PianoMap.midiForKey(c.toString()); val b = m.midiForKey(c.toString())
            assertTrue("$c", a == b || (a % 12 == 4 && b == a + 1))
        }
        assertEquals(PianoMap.NOTES, NoteMap.PIANO.notes)
        assertEquals(KeyInstrument.DANTRANH, KeyInstrument.of("dantranh"))
        assertEquals(KeyInstrument.PIANO, KeyInstrument.of("piano"))
        for (id in listOf("wood", "system", "custom", null)) assertEquals(null, KeyInstrument.of(id))
    }

    /** Checksum chung iOS testDanTranhSynthParity. */
    @Test fun danTranhChecksums() {
        assertArrayEquals(DanTranhSynth.note(60), DanTranhSynth.note(60), 0f)
        fun sum(a: FloatArray) = a.sumOf { abs(it).toDouble() }
        for ((m, n, sus) in listOf(Triple(48, 637.1004, 721.6308), Triple(60, 556.5538, 650.7014), Triple(72, 500.1500, 588.8047),
            Triple(93, 397.5509, 488.6524), Triple(105, 449.1060, 656.5890))) {
            val a = DanTranhSynth.note(m)
            assertEquals(22_050, a.size)
            assertEquals("$m", n, sum(a), 0.02)
            assertEquals("$m ngân", sus, sum(DanTranhSynth.note(m, true)), 0.02)
            assertEquals(DanTranhSynth.NOTE_PEAK.toFloat(), a.maxOf { abs(it) }, 1e-4f)
            assertEquals(0f, a.first(), 1e-6f); assertEquals(0f, a.last(), 1e-6f)
        }
        assertEquals(434.8580, sum(DanTranhSynth.space()), 0.02)
        assertEquals(367.0031, sum(DanTranhSynth.returnChord()), 0.02)
        assertEquals(72.3706, sum(DanTranhSynth.delete()), 0.02)
        assertEquals(189.2533, sum(DanTranhSynth.pedal()), 0.02)
        assertEquals(1353.9346, sum(DanTranhSynth.previewMelody()), 0.05)
        assertEquals(27_563, DanTranhSynth.note(60, true).size)
        assertFalse(DanTranhSynth.note(60).contentEquals(PianoSynth.note(60)))
    }

    /** Không to hơn Piano (đỉnh + RMS); "nhấn nhá" chỉ rung nhẹ — đuôi A4 vẫn ≈ 440 Hz. */
    @Test fun danTranhNotLouderThanPiano() {
        fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
        assertTrue(DanTranhSynth.NOTE_PEAK <= PianoSynth.NOTE_PEAK)
        assertTrue(DanTranhSynth.CHORD_PEAK <= PianoSynth.CHORD_PEAK)
        assertTrue(DanTranhSynth.PEDAL_PEAK <= PianoSynth.PEDAL_PEAK)
        for (m in listOf(48, 60, 72, 93, 105)) assertTrue("$m", rms(DanTranhSynth.note(m)) < rms(PianoSynth.note(m)))
        assertTrue(rms(DanTranhSynth.space()) < rms(PianoSynth.space()))
        assertTrue(rms(DanTranhSynth.returnChord()) < rms(PianoSynth.returnChord()))
        assertTrue(rms(DanTranhSynth.pedal()) < rms(PianoSynth.pedal()))
        assertTrue(rms(DanTranhSynth.delete()) < rms(PianoSynth.delete()))
        assertTrue(VoicePool.chordScale(8) * 8 * DanTranhSynth.NOTE_PEAK <= 1.0)
        val a4 = DanTranhSynth.note(69)
        var crossings = 0
        for (i in 13_000 until 17_400) if ((a4[i] <= 0f) != (a4[i + 1] <= 0f)) crossings++
        assertEquals(440.0, crossings / 2.0 / (4_400.0 / PianoSynth.SAMPLE_RATE), 440 * 0.015)
    }

    /** Bộ nốt thường 611 888 mẫu (PCM16 ≈ 1.22 MB, iOS Float32 ≈ 2.45 MB); bộ ngân cùng cỡ Piano. */
    @Test fun danTranhMemoryBound() {
        assertEquals(611_888, DanTranhSynth.normalBankSamples)
        assertTrue(DanTranhSynth.normalBankSamples * 4 <= 5 * 1024 * 1024 / 2)
        assertEquals(PianoSynth.sustainBankSamples, DanTranhSynth.sustainBankSamples)
        val i = KeyInstrument.DANTRANH
        val real = i.map.notes.sumOf { i.note(it).size } + i.space().size + i.returnChord().size + i.delete().size + i.pedal().size
        assertEquals(i.normalBankSamples, real)
        assertEquals(PianoSynth.normalBankSamples, KeyInstrument.PIANO.normalBankSamples)
    }

    /** Pedal dùng chung: đàn tranh không có máy trạng thái riêng — cùng PianoPedal (một-lần / Caps / damper). */
    @Test fun danTranhReusesPedalAndPool() {
        val p = PianoPedal()
        assertEquals(PianoPedal.Action.PEDAL_DOWN, p.shiftPressed(PianoPedal.Shift.ON))
        assertTrue(p.noteTyped()); assertFalse(p.noteTyped())
        p.shiftPressed(PianoPedal.Shift.CAPS)
        assertTrue(p.noteTyped()); assertTrue(p.noteTyped())
        assertEquals(PianoPedal.Action.DAMPER, p.shiftPressed(PianoPedal.Shift.OFF))
        // 3 dây hợp âm cách gõ cùng lúc ⇒ 3 giọng khác nhau trong cùng pool, hạ 1/√3.
        val pool = VoicePool(PianoSynth.VOICES)
        val v = NoteMap.DANTRANH.spaceChord.map { pool.allocate(5.0) }
        assertEquals(3, v.toSet().size)
        assertEquals(1f / sqrt(3f), VoicePool.chordScale(pool.simultaneous(5.0)), 1e-6f)
        for (inst in KeyInstrument.entries) assertTrue("${inst.style}", inst.style in KeySoundStyle.ALL_PRESETS)
    }
}
