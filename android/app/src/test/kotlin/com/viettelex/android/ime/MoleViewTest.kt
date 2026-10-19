package com.viettelex.android.ime

import com.viettelex.keyboard.KeySoundSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Đập chuột phía IME: lớp chuột không nhận chạm, "éc" theo Âm thanh phím, WAV "éc" ghi một lần. iOS MoleGameTests. */
class MoleViewTest {
    @Test fun overlayNeverConsumesTouches() {
        assertFalse(MoleView.CONSUMES_TOUCH)
    }

    /** "Éc" CHỈ khi đường âm riêng phát: tắt Âm thanh phím / kiểu Hệ thống / âm lượng 0 / máy im ⇒ không. */
    @Test fun squeakRespectsKeySound() {
        assertTrue(Feedback.squeakAllowed(KeySoundSynth.route(true, 50, true, "bop")))
        assertFalse(Feedback.squeakAllowed(KeySoundSynth.route(false, 50, true, "bop")))
        assertFalse(Feedback.squeakAllowed(KeySoundSynth.route(true, 50, true, "system")))
        assertFalse(Feedback.squeakAllowed(KeySoundSynth.route(true, 0, true, "bop")))
        assertFalse(Feedback.squeakAllowed(KeySoundSynth.route(true, 50, false, "bop")))
    }

    @Test fun squeakWavCachedOnce() {
        val dir = Files.createTempDirectory("squeak").toFile()
        try {
            val f = Feedback.squeakFile(dir)!!
            val (s, rate) = KeySoundSynth.parseWav(f.readBytes())!!
            assertEquals(KeySoundSynth.SAMPLE_RATE.toDouble(), rate, 0.0)
            assertEquals(KeySoundSynth.squeakSamples().size, s.size)
            val stamp = f.lastModified()
            assertEquals(f, Feedback.squeakFile(dir)); assertEquals(stamp, f.lastModified())
        } finally { dir.deleteRecursively() }
    }
}
