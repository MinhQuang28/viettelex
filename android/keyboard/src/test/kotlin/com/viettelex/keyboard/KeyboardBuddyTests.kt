package com.viettelex.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.Locale

/**
 * "Bạn đồng hành" ([KeyboardBuddy]): máy trạng thái theo dấu giờ, bậc tốc độ, dáng, phản ứng theo từ, hoảng khi xoá
 * dồn, mùa lễ, cổng Plus, 0 khung hình khi ngủ, 500 phím không cấp phát / không rò, chi phí mỗi phím. Số tham chiếu
 * (bậc, mốc giờ, checksum bảng hình, grawlix, quỹ đạo hạt) GIỐNG HỆT iOS KeyboardBuddyTests.
 */
class KeyboardBuddyTests {
    private var savedPaywall = PlusGate.paywallEnabled

    @Before fun setUp() { savedPaywall = PlusGate.paywallEnabled }

    @After fun tearDown() { PlusGate.paywallEnabled = savedPaywall; PlusGate.install({ null }, debugBuild = false) }

    // MARK: cài đặt

    @Test fun idsMatchIOSAndDefaultIsOff() {
        assertEquals(listOf("off", "girl", "boy", "cat", "chick"), BuddyCharacter.IDS.toList())
        assertEquals("keyboardBuddy", Keys.KEYBOARD_BUDDY)
        assertEquals(BuddyCharacter.OFF, BuddyCharacter.DEFAULT)
        assertEquals(BuddyCharacter.OFF, BuddyCharacter.of("lạ"))
        assertEquals(BuddyCharacter.OFF, BuddyCharacter.of(null))
        assertEquals(BuddyCharacter.CHICK, BuddyCharacter.of("chick"))
        assertTrue(BackupSettings.all.any { it.key == Keys.KEYBOARD_BUDDY })
    }

    @Test fun settingsLoad() {
        assertEquals(BuddyCharacter.OFF, KeyboardSettings.load { null }.buddy)
        assertEquals(BuddyCharacter.CAT, KeyboardSettings.load { if (it == Keys.KEYBOARD_BUDDY) "cat" else null }.buddy)
    }

    @Test fun plusFallback() {
        assertEquals(BuddyCharacter.OFF, BuddyPolicy.active(BuddyCharacter.CAT, plus = false))
        assertEquals(BuddyCharacter.CAT, BuddyPolicy.active(BuddyCharacter.CAT, plus = true))
        assertEquals(BuddyCharacter.OFF, BuddyPolicy.active(BuddyCharacter.OFF, plus = true))
        assertFalse(BuddyCharacter.OFF.isPlus)
        assertTrue(BuddyCharacter.entries.filter { it != BuddyCharacter.OFF }.all { it.isPlus })
        assertTrue(BuddyGate.allows(BuddyCharacter.OFF))
        // Bật thu phí, chưa mua ⇒ khoá; mua ⇒ mở.
        PlusGate.paywallEnabled = true
        PlusGate.install({ null }, debugBuild = false)
        assertFalse(BuddyGate.allows(BuddyCharacter.CAT))
        assertEquals(BuddyCharacter.OFF, BuddyPolicy.active(BuddyCharacter.CAT, BuddyGate.allows(BuddyCharacter.CAT)))
        PlusGate.install({ if (it == Keys.PLUS_UNLOCKED) true else null }, debugBuild = false)
        assertTrue(BuddyGate.allows(BuddyCharacter.CAT))
    }

    // MARK: bậc

    @Test fun tierFunction() {
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3), (0..16).map { BuddyTempo.tier(it, 0) })
        assertEquals(3, BuddyTempo.tier(13, 3)); assertEquals(3, BuddyTempo.tier(12, 3)); assertEquals(2, BuddyTempo.tier(11, 3))
        assertEquals(2, BuddyTempo.tier(8, 2)); assertEquals(1, BuddyTempo.tier(7, 2))
        assertEquals(1, BuddyTempo.tier(4, 1)); assertEquals(0, BuddyTempo.tier(3, 1))
    }

    @Test fun tierFromInterKeyInterval() {
        fun tierAfter(dt: Double): Int { val b = BuddyBrain(); var t = 0.0; repeat(40) { b.press(t); t += dt }; return b.tier }
        assertEquals(0, tierAfter(0.5)); assertEquals(1, tierAfter(0.3)); assertEquals(2, tierAfter(0.18)); assertEquals(3, tierAfter(0.11))
    }

    // MARK: máy trạng thái

    @Test fun stateMachineTimeline() {
        val b = BuddyBrain()
        assertSame(BuddyPhase.Asleep.QUIET, b.phase(0.0))
        assertNull(b.nextDeadline(0.0))
        var t = 10.0
        b.press(t)
        assertSame(BuddyPhase.Dancing.of(0), b.phase(t))
        assertEquals(t + BuddyBrain.TAIL, b.nextDeadline(t)!!, 1e-9)
        val seen = ArrayList<Int>()
        repeat(30) {
            t += 0.11; b.press(t)
            (b.phase(t) as? BuddyPhase.Dancing)?.let { if (seen.lastOrNull() != it.tier) seen += it.tier }
        }
        assertEquals(listOf(0, 1, 2, 3), seen)
        val last = t
        assertSame(BuddyPhase.Dancing.of(3), b.phase(last + 0.59))
        assertSame(BuddyPhase.Awake, b.phase(last + 0.61))
        assertEquals(last + 3, b.nextDeadline(last + 0.61)!!, 1e-9)
        assertSame(BuddyPhase.Awake, b.phase(last + 2.99))
        assertSame(BuddyPhase.Asleep.ZZZ, b.phase(last + 3.01))
        assertEquals(last + 5, b.nextDeadline(last + 3.01)!!, 1e-9)
        assertSame(BuddyPhase.Asleep.QUIET, b.phase(last + 5.01))
        assertNull(b.nextDeadline(last + 5.01))
        b.press(last + 20)
        assertSame(BuddyPhase.Dancing.of(0), b.phase(last + 20))
    }

    @Test fun lowPowerStaticOnly() {
        for (p in listOf(BuddyPhase.Dancing.of(3), BuddyPhase.Cheering, BuddyPhase.Awake, BuddyPhase.React.of(BuddyReaction.LOVE)))
            assertSame(BuddyPhase.Awake, BuddyPolicy.visual(p, true))
        assertSame(BuddyPhase.Asleep.QUIET, BuddyPolicy.visual(BuddyPhase.Asleep.ZZZ, true))
        assertFalse(BuddyPolicy.animates(BuddyPhase.Awake)); assertFalse(BuddyPolicy.animates(BuddyPhase.Asleep.QUIET))
    }

    // MARK: dáng

    @Test fun danceLoopsAndEnergyRises() {
        for (c in BuddyCharacter.entries.filter { it != BuddyCharacter.OFF }) {
            val s = BuddyRig.rig(c)!!.style
            val peaks = (0..3).map { t ->
                assertEquals(BuddyDance.pose(s, t, 0.0), BuddyDance.pose(s, t, 1.0))
                (0 until 200).maxOf { -BuddyDance.pose(s, t, it / 200.0).dy }
            }
            assertEquals(peaks.sorted(), peaks)
            assertTrue((0 until 100).all { BuddyDance.pose(s, 2, it / 100.0).sx == 1.0 })
            assertTrue((0 until 100).any { BuddyDance.pose(s, 3, it / 100.0).sx < 0 })
            assertTrue(BuddyDance.cheer(s, 0.0).isRest())
        }
        assertEquals(2.48, BuddyDance.loopLength(0), 1e-9)
        assertEquals(1.44, BuddyDance.loopLength(3), 1e-9)
    }

    /** = iOS testPoseParityNumbers. */
    @Test fun poseParityNumbers() {
        val p = BuddyDance.pose(BuddyRig.GIRL.style, 2, 0.3)
        assertEquals(-4.4966, p.dy, 1e-3); assertEquals(6.4672, p.dx, 1e-3)
        assertEquals(1.8051, p.armL, 1e-3); assertEquals(-2.1049, p.armR, 1e-3); assertEquals(0.2829, p.tail, 1e-3)
    }

    // MARK: hình

    @Test fun svgParse() {
        assertEquals(listOf(BuddySVG.Cmd.Move(1.0, 2.0), BuddySVG.Cmd.Line(4.0, 6.0), BuddySVG.Cmd.Line(9.0, 6.0),
            BuddySVG.Cmd.Line(9.0, -6.0), BuddySVG.Cmd.Quad(10.0, -5.0, 11.0, -4.0), BuddySVG.Cmd.Close),
            BuddySVG.parse("M1 2 l3 4 h5 V-6 q1 1 2 2 Z"))
        assertEquals(listOf(-8.0, 72.0, -10.0, 140.0), BuddySVG.numbers("M-8 72 L-10 140"))
        assertEquals(listOf(1.5, -2.0), BuddySVG.numbers("h1.5-2"))
    }

    /** Bảng hình giống hệt iOS (KeyboardBuddyTests.checksums). */
    @Test fun rigChecksumParity() {
        val sums = BuddyCharacter.entries.map { String.format(Locale.US, "%.4f", BuddyRig.checksum(it)) }
        assertEquals(listOf("0.0000", "12053.6237", "12013.9930", "15286.2573", "12005.5890"), sums)
    }

    @Test fun everyRigComplete() {
        for (c in BuddyCharacter.entries.filter { it != BuddyCharacter.OFF }) {
            val r = BuddyRig.rig(c)!!
            for (p in listOf(BuddyPart.LEG_L, BuddyPart.LEG_R, BuddyPart.BODY, BuddyPart.ARM_L, BuddyPart.ARM_R, BuddyPart.HEAD, BuddyPart.SLEEP))
                assertTrue("$c thiếu $p", r.pieces.any { it.part == p })
            for (p in r.pieces) if (p.part != BuddyPart.SLEEP) assertNotNull("$c ${p.part}", r.pivots[p.part])
        }
        assertTrue(BuddyRig.GIRL.hasTail && BuddyRig.CAT.hasTail)
        assertFalse(BuddyRig.BOY.hasTail || BuddyRig.CHICK.hasTail)
        assertEquals(7, BuddySprites.frameIndex(0.99, 8)); assertEquals(0, BuddySprites.frameIndex(1.0, 8))
    }

    // MARK: phản ứng

    @Test fun reactionMatching() {
        for (w in listOf("yêu", "Yêu", "YÊU", "yeu", "iu", "thương", "thuong", "love", "LOVE!", "luv", "miss", "thích", "thich",
            "crush", "tim", "hun", "honey", "baby", "nhớ", "cưng", "thơm", "muah"))
            assertEquals(w, BuddyReaction.LOVE, BuddyWords.reaction(w, null))
        for (w in listOf("haha", "hahaha", "HAHAHAHA", "hahah", "hihi", "hihihi", "hehe", "kkk", "kkkkkkk", "kaka", "lol", "lmao",
            "=))", "=)))))", ":))", "😂", "hjhjhj"))
            assertEquals(w, BuddyReaction.LAUGH, BuddyWords.reaction(w, null))
        for (w in listOf("wow", "woooow", "WOW!", "ôi", "omg", "omggg", "ủa", "hả", "what", "ồ", "á"))
            assertEquals(w, BuddyReaction.SURPRISED, BuddyWords.reaction(w, null))
        assertEquals(BuddyReaction.SURPRISED, BuddyWords.reaction("ơi", "trời"))
        for (w in listOf("buồn", "buon", "khóc", "huhu", "huhuhu", "hic", "hichic", "hix", "sad", ":(", ":(((", "chán", "😭"))
            assertEquals(w, BuddyReaction.SAD, BuddyWords.reaction(w, null))
        assertEquals(BuddyReaction.SAD, BuddyWords.reaction("mỏi", "mệt"))
        for (w in listOf("nho", "nhỏ", "cung", "thom", "chan", "a", "o", "ha", "an", "anh", "bàn", "trời", "k", "kk", "hah", ""))
            assertNull(w, BuddyWords.reaction(w, null))
        assertNull(BuddyWords.reaction("a".repeat(30), null))
    }

    /** Giận dùng ĐÚNG bảng của bộ lọc gợi ý (từ / cặp / bộ ba), không phụ thuộc công tắc lọc. */
    @Test fun angryUsesSensitiveTables() {
        val bad = SensitiveWords.set.sorted().first()
        assertEquals(BuddyReaction.ANGRY, BuddyWords.reaction(bad, null))
        assertEquals(BuddyReaction.ANGRY, BuddyWords.reaction(bad.uppercase() + "!", null))
        val pair = SensitiveWords.pairs.entries.first { it.value.isNotEmpty() }
        val w2 = pair.value.sorted().first()
        if (w2 !in SensitiveWords.set) {
            assertEquals(BuddyReaction.ANGRY, BuddyWords.reaction(w2, pair.key))
            assertNotEquals(BuddyReaction.ANGRY, BuddyWords.reaction(w2, "xyz"))
        }
        assertEquals(BuddyReaction.ANGRY, BuddyWords.reaction("wtf", null))
        assertEquals(BuddyReaction.LOVE, BuddyWords.reaction("yêu", "anh"))
    }

    @Test fun priorityOrder() {
        assertEquals(listOf(BuddyReaction.ANGRY, BuddyReaction.LOVE, BuddyReaction.LAUGH, BuddyReaction.SURPRISED, BuddyReaction.SAD),
            BuddyReaction.entries.take(5))
        for (r in BuddyReaction.entries) assertTrue(r.duration in 0.8..1.5)
    }

    @Test fun grawlixDeterministicSymbolsOnly() {
        assertEquals(BuddyWords.grawlix(7), BuddyWords.grawlix(7))
        val distinct = HashSet<String>()
        for (s in 0 until 50) {
            val g = BuddyWords.grawlix(s)
            assertEquals(4, g.length)
            assertTrue(g, g.all { it in BuddyWords.GRAWLIX_SYMBOLS })
            distinct += g
        }
        assertTrue(distinct.size > 10)
        assertEquals("%&*#", BuddyWords.grawlix(1))   // = iOS
    }

    @Test fun reactionStateMachine() {
        val b = BuddyBrain()
        b.press(0.0)
        assertSame(BuddyPhase.Asleep.QUIET, b.phase(10.0))
        b.react(BuddyReaction.LOVE, 10.0)
        assertSame(BuddyPhase.React.of(BuddyReaction.LOVE), b.phase(10.5))
        assertEquals(11.2, b.nextDeadline(10.5)!!, 1e-9)
        assertSame(BuddyPhase.Asleep.QUIET, b.phase(11.21))
        assertNull(b.nextDeadline(11.21))
        var t = 20.0
        repeat(12) { b.press(t); t += 0.15 }
        b.react(BuddyReaction.ANGRY, t)
        assertSame(BuddyPhase.React.of(BuddyReaction.ANGRY), b.phase(t + 0.1))
        for (k in 0 until 10) b.press(t + 0.15 * (k + 1))
        assertSame(BuddyPhase.Dancing.of(b.tier), b.phase(t + 1.21))
        val s0 = b.reactSerial
        b.react(BuddyReaction.LOVE, 50.0); b.react(BuddyReaction.LOVE, 50.5)
        assertEquals(s0 + 2, b.reactSerial)
        assertSame(BuddyPhase.React.of(BuddyReaction.LOVE), b.phase(51.6))
        b.react(BuddyReaction.ANGRY, 51.65)
        assertSame(BuddyPhase.React.of(BuddyReaction.ANGRY), b.phase(51.7))
        b.react(BuddyReaction.LOVE, 51.8)
        assertSame(BuddyPhase.React.of(BuddyReaction.LOVE), b.phase(51.9))
        assertNotEquals(BuddyPhase.React.of(BuddyReaction.LOVE), b.phase(53.01))
        val c = BuddyBrain()
        c.react(BuddyReaction.SAD, 1.0)
        assertSame(BuddyPhase.React.of(BuddyReaction.SAD), c.phase(1.1))
        assertSame(BuddyPhase.Asleep.QUIET, c.phase(2.6))
        for (r in BuddyReaction.entries.filter { it != BuddyReaction.PANIC }) for (s in listOf(BuddyRig.GIRL.style, BuddyRig.CAT.style)) {
            assertTrue("$r", BuddyDance.react(r, s, 0.0).isRest())
            assertEquals(0.0, BuddyDance.react(r, s, 1.0).armL, 1e-9)
        }
        assertTrue(BuddyDance.react(BuddyReaction.LOVE, BuddyRig.GIRL.style, 0.5).armL < 0)
        assertTrue(BuddyDance.react(BuddyReaction.PANIC, BuddyRig.GIRL.style, 0.5).armL > 2)
    }

    @Test fun deletePanicCounter() {
        val b = BuddyBrain()
        var t = 5.0
        repeat(8) { b.delete(t); assertNotEquals(BuddyPhase.React.of(BuddyReaction.PANIC), b.phase(t)); t += 0.09 }
        b.delete(t)
        assertSame(BuddyPhase.React.of(BuddyReaction.PANIC), b.phase(t))
        val serial = b.reactSerial
        repeat(20) { t += 0.09; b.delete(t) }
        assertEquals(serial, b.reactSerial)
        assertSame(BuddyPhase.React.of(BuddyReaction.PANIC), b.phase(t + 0.79))
        assertNotEquals(BuddyPhase.React.of(BuddyReaction.PANIC), b.phase(t + 0.81))
        t += 5
        repeat(6) { b.delete(t); t += 0.09 }
        b.press(t); t += 0.09
        repeat(6) { b.delete(t); t += 0.09 }
        assertNotEquals(BuddyPhase.React.of(BuddyReaction.PANIC), b.phase(t - 0.09))
        t += 1
        repeat(6) { b.delete(t); t += 0.09 }
        t += 0.6
        repeat(6) { b.delete(t); t += 0.09 }
        assertNotEquals(BuddyPhase.React.of(BuddyReaction.PANIC), b.phase(t - 0.09))
    }

    @Test fun fxParticlesCappedAndConfined() {
        val rad = BuddyFX.PARTICLE_SIZE / 2
        for (re in BuddyReaction.entries) {
            assertTrue(BuddyFX.count(re) <= BuddyFX.MAX_PARTICLES)
            for ((w, h) in listOf(34.0 to 30.0, 34.0 to 40.0, 34.0 to 46.0, 120.0 to 96.0)) {
                for (i in 0 until BuddyFX.count(re)) {
                    val p = BuddyFX.particle(re, i, w, h)
                    for ((x, y) in listOf(p.x0 to p.y0, p.x1 to p.y1))
                        assertTrue("$re $w×$h #$i $p", x - rad >= 0 && x + rad <= w && y - rad >= 0 && y + rad <= h)
                }
                BuddyFX.badge(re, w, h)?.let { b -> assertTrue("$re $b", b.x >= 0 && b.y >= 0 && b.x + b.w <= w && b.maxY <= h) }
            }
        }
        assertTrue(BuddyFX.MAX_PARTICLES <= 8)
        assertTrue(BuddyFX.count(BuddyReaction.LOVE) + BuddyFX.count(BuddyReaction.PANIC) <= 8)
        val p = BuddyFX.particle(BuddyReaction.LOVE, 3, 34.0, 40.0)
        assertEquals(22.4, p.x1, 0.01); assertEquals(3.5, p.y1, 0.01)   // = iOS
    }

    // MARK: mùa lễ

    @Test fun seasonResolution() {
        assertEquals(BuddySeason.TET, BuddySeason.ofDate(2026, 2, 17))
        assertEquals(BuddySeason.TET, BuddySeason.ofDate(2026, 2, 7))
        assertNull(BuddySeason.ofDate(2026, 2, 6))
        assertEquals(BuddySeason.TET, BuddySeason.ofDate(2026, 2, 24))
        assertNull(BuddySeason.ofDate(2026, 2, 25))
        assertEquals(BuddySeason.TET, BuddySeason.ofDate(2028, 1, 16))
        assertEquals(BuddySeason.MID_AUTUMN, BuddySeason.ofDate(2026, 9, 25))
        assertEquals(BuddySeason.MID_AUTUMN, BuddySeason.ofDate(2026, 9, 20))
        assertNull(BuddySeason.ofDate(2026, 9, 19))
        assertEquals(BuddySeason.MID_AUTUMN, BuddySeason.ofDate(2028, 10, 8))
        assertEquals(BuddySeason.CHRISTMAS, BuddySeason.ofDate(2026, 12, 15))
        assertEquals(BuddySeason.CHRISTMAS, BuddySeason.ofDate(2026, 12, 26))
        assertNull(BuddySeason.ofDate(2026, 12, 27))
        assertEquals(BuddySeason.HALLOWEEN, BuddySeason.ofDate(2026, 10, 25))
        assertEquals(BuddySeason.HALLOWEEN, BuddySeason.ofDate(2026, 11, 1))
        assertNull(BuddySeason.ofDate(2026, 11, 2))
        assertNull(BuddySeason.ofDate(2026, 7, 1))
        assertEquals(10, BuddySeason.LUNAR_NEW_YEAR.size); assertEquals(10, BuddySeason.MID_AUTUMN_DAY.size)
        assertEquals(0, BuddySeason.dayNumber(1970, 1, 1)); assertEquals(20745, BuddySeason.dayNumber(2026, 10, 19))
    }

    @Test fun outfitByThemeThenDate() {
        assertEquals(BuddySeason.TET, BuddySeason.resolve("tetMai", 2026, 7, 1))
        assertEquals(BuddySeason.CHRISTMAS, BuddySeason.resolve("xmasSnow", 2026, 2, 17))
        assertEquals(BuddySeason.HALLOWEEN, BuddySeason.resolve("halloween", 2026, 7, 1))
        assertEquals(BuddySeason.MID_AUTUMN, BuddySeason.resolve("trungthu", 2026, 7, 1))
        assertEquals(BuddySeason.CHRISTMAS, BuddySeason.resolve("system", 2026, 12, 20))
        assertNull(BuddySeason.resolve("oled", 2026, 7, 1))
        // id theme trùng KeyboardTheme.
        for (id in listOf("tet", "tetMai", "xmas", "xmasSnow", "halloween", "trungthu"))
            assertTrue(id, KeyboardTheme.entries.any { it.id == id })
        for (c in BuddyCharacter.entries.filter { it != BuddyCharacter.OFF }) {
            assertTrue(BuddyOutfit.make(null, c).pieces.isEmpty())
            for (s in BuddySeason.entries) { val o = BuddyOutfit.make(s, c); assertFalse("$c $s", o.pieces.isEmpty() && o.recolor.isEmpty()) }
        }
        assertEquals(BuddyOutfit.RED, BuddyOutfit.make(BuddySeason.TET, BuddyCharacter.GIRL).recolor[0xFFB3C7])
        assertTrue(BuddyOutfit.make(BuddySeason.MID_AUTUMN, BuddyCharacter.BOY).pieces.all { it.part == BuddyPart.ARM_R })
    }

    // MARK: driver (logic view Android)

    @Test fun driverZeroFramesWhenAsleep() {
        for (c in BuddyCharacter.entries.filter { it != BuddyCharacter.OFF }) {
            val d = BuddyDriver(BuddyRig.rig(c)!!)
            var t = 100.0
            d.update(t)
            assertFalse("$c mới hiện: ngủ yên", d.animating(t))
            assertNull(d.poseAt(t, BuddyPose()))
            repeat(20) { t += 0.1; d.keyPressed(t) }
            assertTrue(d.update(t))
            assertTrue(d.shown is BuddyPhase.Dancing)
            assertTrue(d.animating(t))
            assertNotNull(d.poseAt(t, BuddyPose()))
            t += 1; d.update(t)
            assertSame(BuddyPhase.Awake, d.shown)
            assertFalse("$c đứng yên sau nối", d.animating(t + BuddyDance.BLEND + 0.01))
            t += 2.5; d.update(t)
            assertSame(BuddyPhase.Asleep.ZZZ, d.shown)
            assertTrue(d.animating(t))
            t += 2.1; d.update(t)
            assertSame(BuddyPhase.Asleep.QUIET, d.shown)
            assertFalse("$c ngủ yên ⇒ 0 khung hình", d.animating(t))
            assertNull(d.nextDeadline(t))
        }
    }

    @Test fun driverLowPowerNeverAnimates() {
        val d = BuddyDriver(BuddyRig.GIRL, lowPower = true)
        var t = 0.0
        repeat(30) { t += 0.08; d.keyPressed(t); d.update(t); assertFalse(d.animating(t)) }
        assertSame(BuddyPhase.Awake, d.shown)
        d.wordCommitted("yêu", null, null, t); d.update(t)
        assertSame(BuddyPhase.Awake, d.shown)
        t += 3.5; d.update(t)
        assertSame(BuddyPhase.Asleep.QUIET, d.shown)
        assertFalse(d.animating(t))
    }

    /** Nối mượt: đổi bậc không nhảy dáng (sai khác giữa hai khung liền nhau nhỏ). */
    @Test fun driverTierChangeIsContinuous() {
        val d = BuddyDriver(BuddyRig.CAT)
        var t = 0.0
        val a = BuddyPose(); val b = BuddyPose()
        var prevTier = -1
        repeat(60) {
            t += 0.1; d.keyPressed(t)
            d.poseAt(t - 1e-4, a)
            val changed = d.update(t)
            val tier = (d.shown as? BuddyPhase.Dancing)?.tier ?: -1
            if (changed && prevTier >= 0 && tier != prevTier) {
                d.poseAt(t, b)
                assertEquals("armL liền mạch khi đổi bậc $prevTier→$tier", a.armL, b.armL, 0.05)
                assertEquals(a.dy, b.dy, 0.5)
            }
            prevTier = tier
        }
        assertEquals(3, prevTier)
    }

    @Test fun driverReactionRestartWakeReturn() {
        val d = BuddyDriver(BuddyRig.CHICK)
        var t = 50.0
        assertFalse(d.wordCommitted("bàn", null, null, t))
        assertTrue(d.wordCommitted("yêu", null, null, t))
        assertTrue(d.update(t))
        assertSame(BuddyPhase.React.of(BuddyReaction.LOVE), d.shown)
        t += 0.3
        assertTrue(d.wordCommitted("yêu", null, null, t))
        assertTrue("diễn lại từ đầu", d.update(t))
        assertEquals(t, d.phaseStart, 1e-9)
        t += 1.3; d.update(t)
        assertSame("ngủ tiếp", BuddyPhase.Asleep.QUIET, d.shown)
        assertFalse(d.animating(t))
    }

    /** 500 phím: đường phím (ghi giờ + so trạng thái) KHÔNG cấp phát; chi phí mỗi phím. */
    @Test fun burstOf500KeysNoAllocationAndCost() {
        val d = BuddyDriver(BuddyRig.BOY)
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().id
        var t = 0.0
        // làm nóng (JIT, class load)
        repeat(2000) { i -> t += if (i % 50 < 40) 0.07 else 0.35; d.keyPressed(t); d.needsUpdate(t) }
        val a0 = bean.getThreadAllocatedBytes(tid)
        val n0 = System.nanoTime()
        repeat(500) { i ->
            t += if (i % 50 < 40) 0.07 else 0.35
            if (i % 9 == 0) d.deletePressed(t) else d.keyPressed(t)
            d.needsUpdate(t)
        }
        val ns = (System.nanoTime() - n0) / 500.0
        val bytes = bean.getThreadAllocatedBytes(tid) - a0
        // Lượt áp + dáng mỗi khung hình (view) — tái dùng BuddyPose, không cấp phát.
        val pose = BuddyPose()
        repeat(500) { t += 0.016; d.update(t); d.poseAt(t, pose) }
        val a1 = bean.getThreadAllocatedBytes(tid)
        val n1 = System.nanoTime()
        repeat(500) { i -> t += 0.016; if (i % 20 == 0) d.keyPressed(t); d.update(t); d.poseAt(t, pose) }
        val frameNs = (System.nanoTime() - n1) / 500.0
        val frameBytes = bean.getThreadAllocatedBytes(tid) - a1
        println(String.format(Locale.US, "BUDDY android keyPressed+needsUpdate=%.4f ms/phím (%d byte / 500 phím) | update+pose=%.4f ms/khung (%d byte / 500 khung)",
            ns / 1e6, bytes, frameNs / 1e6, frameBytes))
        assertTrue("cấp phát trên đường phím: $bytes byte", bytes < 1024)
        assertTrue("cấp phát mỗi khung: $frameBytes byte", frameBytes < 4096)
        assertTrue("chi phí mỗi phím ${ns / 1e6} ms", ns / 1e6 < 0.1)
    }
}
