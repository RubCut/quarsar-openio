package quasar.rubcut.openio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import quasar.rubcut.openio.domain.audio.BandPass
import quasar.rubcut.openio.domain.audio.Pcm
import quasar.rubcut.openio.domain.audio.ScopeBand
import quasar.rubcut.openio.domain.led.LedPattern
import quasar.rubcut.openio.domain.led.LedPatternParser
import quasar.rubcut.openio.domain.model.RingFrame
import kotlin.math.sqrt

class PluginDomainTest {
    private val frameColors: String =
        (0 until 24).joinToString(" ") { i -> if (i == 12) "ff0000" else "000000" }

    private val sample: String = """
        gammacorrection
        loop
        gradient 0 000000 1 000000 100
        smooth 40
        $frameColors 200
    """.trimIndent()

    @Test
    fun parsesGradientFramesAndLoop() {
        val pattern = LedPatternParser.parse("test", sample)
        assertNotNull(pattern)
        pattern!!
        assertTrue(pattern.loop)
        assertTrue(pattern.gamma)
        assertEquals(2, pattern.frames.size)
        assertEquals(300, pattern.totalMs.toInt())
        assertEquals(24, pattern.frames[0].colors.size)
    }

    @Test
    fun gradientIsInterpolatedAlongTheRing() {
        val pattern = LedPatternParser.parse(
            "g",
            "gradient 0 000000 1 ff0000 500",
        )!!
        val ring = pattern.frameAt(0, 24)
        assertEquals(0x000000, ring.get(0))
        // LED 0 and LED 1 of the gradient are the same point on a ring, so
        // the last LED sits one step before the end colour.
        val lastRed = (ring.get(23) ushr 16) and 0xFF
        assertEquals(0, (ring.get(23) ushr 8) and 0xFF)
        assertTrue("gradient end should be almost full red, got $lastRed", lastRed >= 240)
        val middle = ring.get(12)
        val red = (middle ushr 16) and 0xFF
        assertTrue("gradient should be part-way, got $red", red in 100..200)
    }

    @Test
    fun frameAtLoopsAndFades() {
        val pattern = LedPatternParser.parse("f", sample)!!
        val start = pattern.frameAt(0, 24)
        val second = pattern.frameAt(150, 24)
        assertEquals(0x000000, start.get(12))
        // The red LEDs are fading in after the smooth directive.
        assertTrue((second.get(12) ushr 16) and 0xFF > 0)
        val wrapped = pattern.frameAt(300, 24)
        assertEquals(0x000000, wrapped.get(12))
    }

    @Test
    fun oldNebulaFramesUseBrightnessToken() {
        val colors = (0 until 24).joinToString(" ") { "0d0000" }
        val pattern = LedPatternParser.parse("old", "$colors 20")!!
        assertEquals(1, pattern.frames.size)
        assertEquals(40, pattern.frames[0].holdMs)
        // 0x0d scaled by the 0x20 brightness token.
        val red = (pattern.frames[0].colors[0] ushr 16) and 0xFF
        assertTrue("brightness scaling, got $red", red in 1..2)
    }

    @Test
    fun ringRotationMovesFrontLed() {
        val ring = RingFrame(24)
        ring.set(0, 0xFF0000)
        ring.set(1, 0x00FF00)
        val moved = ring.rotated(5)
        assertEquals(0xFF0000, moved.get(5))
        assertEquals(0x00FF00, moved.get(6))
        assertEquals(0xFF0000, moved.rotated(19).get(0))
    }

    @Test
    fun rotateDirectiveSpinsTheFrame() {
        val colors = (0 until 24).joinToString(" ") { if (it == 0) "ff0000" else "000000" }
        val pattern = LedPatternParser.parse("spin", "loop\nrotate $colors 100")!!
        assertTrue(pattern.rotate)
        assertEquals(0xFF0000, pattern.frameAt(0).get(0))
        assertEquals(0xFF0000, pattern.frameAt(100).get(1))
        assertEquals(0xFF0000, pattern.frameAt(200).get(2))
    }

    @Test
    fun scopeBandWindowsFollowFrequency() {
        assertTrue(ScopeBand.BASS.windowSamples(16000) > ScopeBand.MID.windowSamples(16000))
        assertTrue(ScopeBand.MID.windowSamples(16000) > ScopeBand.HIGH.windowSamples(16000))
        assertTrue(ScopeBand.HIGH.windowSamples(16000) >= 24)
    }

    @Test
    fun bandPassKeepsSelectedBand() {
        val low = Pcm.sineTone(100.0, 16000, 4800, 0.6)
        val high = Pcm.sineTone(3000.0, 16000, 4800, 0.6)
        val pass = BandPass(16000, ScopeBand.BASS)

        fun rmsAfter(samples: ShortArray): Double {
            pass.reset()
            val out = pass.process(samples)
            var sum = 0.0
            for (v in out) sum += v.toDouble() * v
            return sqrt(sum / out.size)
        }

        val lowRms = rmsAfter(low)
        val highRms = rmsAfter(high)
        assertTrue("bass band must pass 100 Hz ($lowRms) and cut 3 kHz ($highRms)", lowRms > highRms * 4)
    }

    @Test
    fun fullBandIsTransparent() {
        val samples = Pcm.sineTone(440.0, 16000, 128, 0.5)
        val out = BandPass(16000, ScopeBand.FULL).process(samples)
        assertTrue(samples.contentEquals(out))
    }
}