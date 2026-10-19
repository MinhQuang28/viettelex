package com.viettelex.android.ime

import com.viettelex.keyboard.KeySoundCustom
import com.viettelex.keyboard.KeySoundStyle
import com.viettelex.keyboard.KeySoundSynth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Âm thanh phím riêng: loại phím → tiếng như stock; WAV cache tất định, ghi một lần. */
class FeedbackSoundTest {
    @Test fun kindMapping() {
        assertEquals(KeySoundSynth.Kind.LETTER, Feedback.soundKind(Feedback.LETTER))
        assertEquals(KeySoundSynth.Kind.DELETE, Feedback.soundKind(Feedback.DELETE))
        for (k in listOf(Feedback.MODIFIER, Feedback.SPACE, Feedback.RETURN))
            assertEquals(KeySoundSynth.Kind.MODIFIER, Feedback.soundKind(k))
    }

    /** Kiểu "Hệ thống" (19/10/2026) không dựng SoundPool — 0 chi phí như TẮT; kiểu tổng hợp mới vẫn dựng. */
    @Test fun systemStyleBuildsNoPool() {
        assertFalse(Feedback.needsPool(true, 50, "system"))
        assertFalse(Feedback.needsPool(false, 50, "subtle"))
        assertFalse(Feedback.needsPool(true, 0, "subtle"))
        assertTrue(Feedback.needsPool(true, 50, "subtle"))
        for (id in listOf("rock", "pop", "water", "vintage", "custom", "loud")) assertTrue(id, Feedback.needsPool(true, 50, id))
    }

    /** Piano: chỉ kiểu piano đi đường nốt; loại phím không nhãn ⇒ sự kiện piano; kiểu khác giữ 3 tiếng. */
    @Test fun pianoRouting() {
        assertTrue(Feedback.isPiano("piano"))
        assertTrue("đàn tranh đi chung đường nốt", Feedback.isPiano("dantranh"))
        for (id in listOf("subtle", "wood", "phao", "jingle", "system", "custom", "loud")) assertFalse(id, Feedback.isPiano(id))
        assertEquals(com.viettelex.keyboard.PianoEvent.SPACE, Feedback.pianoEvent(Feedback.SPACE))
        assertEquals(com.viettelex.keyboard.PianoEvent.RETURN, Feedback.pianoEvent(Feedback.RETURN))
        assertEquals(com.viettelex.keyboard.PianoEvent.DELETE, Feedback.pianoEvent(Feedback.DELETE))
        assertEquals(60, Feedback.pianoEvent(Feedback.LETTER))
        assertEquals(48, Feedback.pianoEvent(Feedback.MODIFIER))
        assertTrue(Feedback.needsPool(true, 50, "piano"))
        // Kiểu 3 tiếng: đường cũ không đổi.
        assertEquals(KeySoundSynth.Route.CUSTOM, KeySoundSynth.route(true, 50, true, "wood"))
        assertEquals(KeySoundSynth.Kind.LETTER, Feedback.soundKind(Feedback.LETTER))
    }

    /** Máy chữ: Enter có WAV "ding" riêng (ghi một lần, đúng mẫu tổng hợp); kiểu khác / custom ⇒ không có. */
    @Test fun typewriterReturnDingFile() {
        val dir = Files.createTempDirectory("keysound").toFile()
        try {
            val f = Feedback.returnFile(dir, "typewriter")!!
            val (s, rate) = KeySoundSynth.parseWav(f.readBytes())!!
            assertEquals(KeySoundSynth.SAMPLE_RATE.toDouble(), rate, 0.0)
            assertEquals(KeySoundSynth.returnSamples(KeySoundStyle.TYPEWRITER)!!.size, s.size)
            val stamp = f.lastModified()
            assertEquals(f, Feedback.returnFile(dir, "typewriter")); assertEquals(stamp, f.lastModified())
            for (id in listOf("subtle", "wood", "scifi", "custom", "system", "piano", "loud")) assertEquals(id, null, Feedback.returnFile(dir, id))
        } finally { dir.deleteRecursively() }
    }

    /** WAV nốt piano: tất định, đúng tần số mẫu (thường 22.05 kHz, ngân 11.025 kHz), ghi một lần. */
    @Test fun pianoWavCache() {
        val dir = Files.createTempDirectory("keysound").toFile()
        try {
            val f = Feedback.pianoNoteFile(dir, 60, sustain = false)!!
            val (s, rate) = KeySoundSynth.parseWav(f.readBytes())!!
            assertEquals(22_050.0, rate, 0.0)
            assertEquals(com.viettelex.keyboard.PianoSynth.note(60).size, s.size)
            val stamp = f.lastModified()
            assertEquals(f, Feedback.pianoNoteFile(dir, 60, sustain = false)); assertEquals(stamp, f.lastModified())
            assertEquals(11_025.0, KeySoundSynth.parseWav(Feedback.pianoNoteFile(dir, 60, sustain = true)!!.readBytes())!!.second, 0.0)
            assertEquals(4, Feedback.pianoExtraFiles(dir).filterNotNull().size)
        } finally { dir.deleteRecursively() }
    }

    /** Đàn tranh: WAV cache riêng (tên khác Piano — đổi kiểu không dùng nhầm nốt piano), cùng tần số mẫu. */
    @Test fun danTranhWavCache() {
        val dir = Files.createTempDirectory("keysound").toFile()
        try {
            val inst = com.viettelex.keyboard.KeyInstrument.DANTRANH
            val f = Feedback.pianoNoteFile(dir, inst, 65, sustain = false)!!
            assertEquals("keysound-dantranh-v1-n-65.wav", f.name)
            assertEquals("keysound-piano-v1-n-65.wav", Feedback.pianoNoteFile(dir, 65, sustain = false)!!.name)
            val (s, rate) = KeySoundSynth.parseWav(f.readBytes())!!
            assertEquals(22_050.0, rate, 0.0)
            assertEquals(com.viettelex.keyboard.DanTranhSynth.note(65).size, s.size)
            assertEquals(11_025.0, KeySoundSynth.parseWav(Feedback.pianoNoteFile(dir, inst, 65, sustain = true)!!.readBytes())!!.second, 0.0)
            val extras = Feedback.pianoExtraFiles(dir, inst)
            assertEquals(4, extras.filterNotNull().size)
            assertEquals("keysound-dantranh-v1-pedal.wav", extras[3]!!.name)
        } finally { dir.deleteRecursively() }
    }

    @Test fun wavCacheWrittenOnce() {
        val dir = Files.createTempDirectory("keysound").toFile()
        try {
            val f = Feedback.wavFile(dir, KeySoundSynth.Kind.DELETE)!!
            assertArrayEquals(KeySoundSynth.wav(KeySoundSynth.Kind.DELETE), f.readBytes())
            val stamp = f.lastModified()
            assertEquals(f, Feedback.wavFile(dir, KeySoundSynth.Kind.DELETE))
            assertEquals(stamp, f.lastModified())
        } finally { dir.deleteRecursively() }
    }

    /** Kiểu custom: file hợp lệ ⇒ cả 3 loại dùng file đó; vắng/hỏng ⇒ WAV "Nhẹ nhàng". Kiểu khác ⇒ WAV kiểu đó. */
    @Test fun soundFilesStyleAndCustomFallback() {
        val dir = Files.createTempDirectory("keysound").toFile()
        try {
            val custom = java.io.File(dir, KeySoundCustom.FILE_NAME)
            val miss = Feedback.soundFiles(dir, "custom", custom)
            assertEquals(Feedback.wavFile(dir, KeySoundStyle.SUBTLE, KeySoundSynth.Kind.LETTER), miss[0])
            custom.writeText("hỏng")
            assertEquals(Feedback.wavFile(dir, KeySoundStyle.SUBTLE, KeySoundSynth.Kind.DELETE), Feedback.soundFiles(dir, "custom", custom)[1])
            custom.writeBytes(KeySoundSynth.wav(floatArrayOf(0f, 0.5f, -0.5f, 0f)))
            assertEquals(listOf(custom, custom, custom), Feedback.soundFiles(dir, "custom", custom))
            val wood = Feedback.soundFiles(dir, "wood", custom)
            assertArrayEquals(KeySoundSynth.wav(KeySoundStyle.WOOD, KeySoundSynth.Kind.MODIFIER), wood[2]!!.readBytes())
            assertTrue(wood[0]!!.name.contains("-wood-"))
            val rock = Feedback.soundFiles(dir, "vintage", custom)
            assertArrayEquals(KeySoundSynth.wav(KeySoundStyle.VINTAGE, KeySoundSynth.Kind.LETTER), rock[0]!!.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
