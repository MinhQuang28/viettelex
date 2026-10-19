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
}
