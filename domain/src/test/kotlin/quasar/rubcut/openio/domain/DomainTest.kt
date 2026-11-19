package quasar.rubcut.openio.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import quasar.rubcut.openio.domain.audio.Fft
import quasar.rubcut.openio.domain.audio.Pcm
import quasar.rubcut.openio.domain.audio.PcmRingBuffer
import quasar.rubcut.openio.domain.model.ScreenFrame
import quasar.rubcut.openio.domain.render.ClockRenderer
import quasar.rubcut.openio.domain.render.ClockSize
import quasar.rubcut.openio.domain.render.OscilloscopeRenderer
import quasar.rubcut.openio.domain.render.RingSpectrumRenderer
import quasar.rubcut.openio.domain.render.SpectrogramRenderer
import quasar.rubcut.openio.domain.render.SpectrogramStyle
import quasar.rubcut.openio.domain.render.TestPatternRenderer

class DomainTest {
    @Test
    fun screenFrameRgb888RoundTrip() {
        val frame = ScreenFrame()
        frame.set(0, 0, 0xFF112233.toInt())
        frame.set(24, 15, 0xFFAABBCC.toInt())
        val bytes = frame.toRgb888()
        assertEquals(1200, bytes.size)
        val restored = ScreenFrame.fromRgb888(bytes)
        assertEquals(0x112233, restored.get(0, 0))
        assertEquals(0xAABBCC, restored.get(24, 15))
    }

    @Test
    fun pcmRingBufferKeepsNewestSamples() {
        val ring = PcmRingBuffer(8)
        ring.write(shortArrayOf(1, 2, 3, 4, 5))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), ring.snapshot(8))
        ring.write(shortArrayOf(6, 7, 8, 9, 10))
        assertArrayEquals(shortArrayOf(4, 5, 6, 7, 8, 9, 10), ring.snapshot(7))
        assertEquals(8, ring.size())
    }

    @Test
    fun clockRendersDigits() {
        val frame = ClockRenderer().render(12, 34, 0, true)
        val lit = frame.pixels.count { it != 0 }
        assertTrue("clock must light some pixels", lit > 20)
        // Plain monochrome: every lit pixel is white and the bottom row is empty.
        assertTrue("clock must be white on black", frame.pixels.all { it == 0 || it == 0xFFFFFF })
        for (x in 0 until frame.width) {
            assertEquals("no progress bar allowed", 0, frame.get(x, frame.height - 1))
        }
    }

    @Test
    fun clockMatchesStockReferenceFrame() {
        // Pixel-exact copy of the stock asset
        // frontal_animation/25x16/25x16_Clock.gif (it displays 04:58).
        val expected = listOf(
            ".........................",
            ".........................",
            ".........................",
            ".###..#...#...#####..###.",
            "#...#.#...#...#.....#...#",
            "#...#.#...#.#.#.....#...#",
            "#...#.#...#...####..#...#",
            "#...#..####.......#..###.",
            "#...#.....#.......#.#...#",
            "#...#.....#.#.....#.#...#",
            "#...#.....#...#...#.#...#",
            ".###......#....###...###.",
            ".........................",
            ".........................",
            ".........................",
            ".........................",
        )
        val frame = ClockRenderer().render(4, 58, 0, true, ClockSize.LARGE)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val want = if (expected[y][x] == '#') 0xFFFFFF else 0
                assertEquals("pixel $x,$y", want, frame.get(x, y))
            }
        }
    }

    @Test
    fun clockColonBlinksWithoutTouchingDigits() {
        val on = ClockRenderer().render(4, 58, 0, true, ClockSize.LARGE)
        val off = ClockRenderer().render(4, 58, 0, false, ClockSize.LARGE)
        assertEquals(0xFFFFFF, on.get(12, 5))
        assertEquals(0, off.get(12, 5))
        assertEquals(0xFFFFFF, off.get(0, 5))
    }

    @Test
    fun clockRingSweepsClockwiseFromFrontCentre() {
        val red = 0xFF2200
        val atZero = ClockRenderer().renderRing(0, red)
        assertEquals(red, atZero.get(0))
        assertEquals(0, atZero.get(6))
        val atQuarter = ClockRenderer().renderRing(15, red)
        assertEquals(red, atQuarter.get(18))
        val atThreeQuarters = ClockRenderer().renderRing(45, red)
        assertEquals(red, atThreeQuarters.get(6))
    }

    @Test
    fun oscilloscopeRendersWaveform() {
        val samples = Pcm.sineTone(440.0, 16000, 50, 0.5)
        val frame = OscilloscopeRenderer().render(samples)
        val lit = frame.pixels.count { it != 0 }
        assertTrue("waveform must be visible", lit > 25)
        // Every pixel of the trace must have exactly the same colour and
        // opacity: a dimmer "connector" shade made the line look like two
        // different transparencies.
        assertTrue(
            "whole trace must be one solid colour",
            frame.pixels.all { it == 0 || it == 0xFFFFFF },
        )
        val ring = OscilloscopeRenderer().renderRing(samples)
        assertTrue("ring must light at least one pixel", ring.pixels.any { it != 0 })
    }

    @Test
    fun oscilloscopeSensitivityScalesWaveform() {
        val quiet = Pcm.sineTone(440.0, 16000, 50, 0.03)
        val renderer = OscilloscopeRenderer()
        val low = renderer.render(quiet, sensitivity = 0.25f).pixels.count { it != 0 }
        val high = renderer.render(quiet, sensitivity = 8f).pixels.count { it != 0 }
        assertTrue("higher sensitivity must light more pixels", high > low)
    }

    @Test
    fun oscilloscopeSilenceDrawsFlatLine() {
        val frame = OscilloscopeRenderer().render(ShortArray(512))
        val lit = frame.pixels.withIndex().filter { it.value != 0 }
        assertEquals("a flat line spans the whole width", 25, lit.size)
        assertTrue(
            "the flat line sits on the centre row",
            lit.all { it.index / 25 == 8 && it.value == 0xFFFFFF },
        )
    }

    @Test
    fun oscilloscopeIgnoresNoiseAndKeepsFlatLine() {
        val noise = ShortArray(512) { if (it % 2 == 0) 40 else -40 }
        val frame = OscilloscopeRenderer().render(noise)
        assertEquals("noise below the gate still shows the flat line", 25, frame.pixels.count { it != 0 })
    }

    @Test
    fun testPatternIsDeterministic() {
        val a = TestPatternRenderer().render()
        val b = TestPatternRenderer().render()
        assertArrayEquals(a.pixels, b.pixels)
        assertEquals(400, a.pixels.size)
    }

    @Test
    fun pcmResamplingAndStereo() {
        val input = ShortArray(320) { (it * 100).toShort() }
        val up = Pcm.resample(input, 16000, 48000)
        assertEquals(960, up.size)
        val stereo = Pcm.toStereo(up)
        assertEquals(1920, stereo.size)
        assertEquals(stereo[0], stereo[1])
        val bytes = Pcm.toLittleEndian(shortArrayOf(1, -1))
        assertArrayEquals(byteArrayOf(1, 0, 0xFF.toByte(), 0xFF.toByte()), bytes)
    }

    @Test
    fun fftFindsSinePeakBin() {
        val samples = Pcm.sineTone(440.0, 16000, 64, 0.5)
        val spectrum = Fft.magnitude(samples, 512)
        val peak = spectrum.indices.maxByOrNull { spectrum[it] }!!
        // 440 Hz at 16 kHz with a 512-point FFT lands in bin 14.
        assertEquals(14, peak)
        assertTrue("peak must dominate the DC bin", spectrum[peak] > spectrum[1] * 10f)
    }

    @Test
    fun ringSpectrumLightsTheToneBandOnly() {
        val renderer = RingSpectrumRenderer()
        val samples = Pcm.sineTone(440.0, 16000, 64, 0.5)
        val band = renderer.bandForFrequency(440.0, 16000, 512)
        val ring = renderer.render(samples, 0xFF0000, 1f, 512)
        assertTrue("tone band $band must be lit", ring.get(band) != 0)
        assertEquals("tone band must be the brightest", ring.pixels[band], ring.pixels.max())
        // Low and high bands stay dark for a pure 440 Hz tone: quantisation
        // noise in the 16-bit input is gated out.
        assertEquals(0, ring.get(0))
        assertEquals(0, ring.get(band - 2))
        assertEquals(0, ring.get(band + 2))
        assertEquals(0, ring.get(23))
    }

    @Test
    fun ringSpectrumDecaysToBlackOnSilence() {
        val renderer = RingSpectrumRenderer()
        renderer.render(Pcm.sineTone(440.0, 16000, 64, 0.5), 0xFF0000, 1f, 512)
        repeat(30) { renderer.render(ShortArray(512), 0xFF0000, 1f, 512) }
        val ring = renderer.render(ShortArray(512), 0xFF0000, 1f, 512)
        assertEquals(0, ring.pixels.count { it != 0 })
    }

    @Test
    fun spectrumBandsClimbCounterClockwise() {
        val renderer = RingSpectrumRenderer()
        val low = renderer.bandForFrequency(100.0, 16000, 512)
        val mid = renderer.bandForFrequency(1000.0, 16000, 512)
        val high = renderer.bandForFrequency(6000.0, 16000, 512)
        assertTrue("low < mid < high, got $low $mid $high", low < mid && mid < high)
    }

    @Test
    fun stockSpectrogramStylesRenderMonochrome() {
        val samples = Pcm.sineTone(440.0, 16000, 64, 0.5)
        val renderer = SpectrogramRenderer()
        val styles = listOf(
            SpectrogramStyle.BARS_BOTTOM,
            SpectrogramStyle.BARS_CENTER,
            SpectrogramStyle.WAVE_CENTER,
            SpectrogramStyle.FLAME,
            SpectrogramStyle.PADS,
            SpectrogramStyle.BRICKS,
            SpectrogramStyle.RANDOM,
        )
        for (style in styles) {
            renderer.reset()
            val frame = renderer.render(samples, style)
            assertTrue("${style.id} must light some pixels", frame.pixels.any { it != 0 })
            assertTrue(
                "${style.id} must stay white on black",
                frame.pixels.all { pixel ->
                    val r = (pixel ushr 16) and 0xFF
                    val g = (pixel ushr 8) and 0xFF
                    val b = pixel and 0xFF
                    r == g && g == b
                },
            )
        }
    }

    @Test
    fun stockWaveCenterIsDarkInDigitalSilence() {
        // The native WaveSpectrogram skips columns whose scaled level rounds
        // to zero (`ccmp x24, #0` / `b.eq`), so pure digital silence is black.
        // The thin flat line requirement applies to our own WAVE_TRACE.
        val frame = SpectrogramRenderer().render(ShortArray(512), SpectrogramStyle.WAVE_CENTER)
        assertEquals("no lit pixels in silence", 0, frame.pixels.count { it != 0 })
    }

    @Test
    fun stockPresetBuiltinIdsMapToStyles() {
        assertEquals(SpectrogramStyle.BARS_BOTTOM, SpectrogramStyle.fromBuiltin("spectrogram:barsBottom"))
        assertEquals(SpectrogramStyle.BARS_CENTER, SpectrogramStyle.fromBuiltin("spectrogram:barsCenter"))
        assertEquals(SpectrogramStyle.WAVE_CENTER, SpectrogramStyle.fromBuiltin("spectrogram:waveCenter"))
        assertEquals(SpectrogramStyle.FLAME, SpectrogramStyle.fromBuiltin("spectrogram:flame"))
        assertEquals(SpectrogramStyle.PADS, SpectrogramStyle.fromBuiltin("spectrogram:pads"))
        assertEquals(SpectrogramStyle.BRICKS, SpectrogramStyle.fromBuiltin("spectrogram:bricksSmall"))
        assertEquals(SpectrogramStyle.RANDOM, SpectrogramStyle.fromBuiltin("spectrogram:random"))
        assertEquals(SpectrogramStyle.WAVE_TRACE, SpectrogramStyle.fromBuiltin("oscilloscope"))
        assertEquals(null, SpectrogramStyle.fromBuiltin("clock"))
    }
}
