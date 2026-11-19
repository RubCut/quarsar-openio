package quasar.rubcut.openio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import quasar.rubcut.openio.domain.render.AnimationRenderer
import quasar.rubcut.openio.domain.render.ClockRenderer
import quasar.rubcut.openio.domain.render.ClockSize
import quasar.rubcut.openio.domain.render.GifDecoder

class GifDecoderTest {
    private fun resource(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream(name)
        assertNotNull("$name test resource is missing", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun stock(): ByteArray = resource("/stock-clock.gif")

    @Test
    fun decodesStockGif() {
        val animation = GifDecoder.decode(stock())
        assertNotNull("stock gif must decode", animation)
        animation!!
        assertEquals(25, animation.width)
        assertEquals(16, animation.height)
        assertEquals(2, animation.frames.size)
        assertTrue("frames must contain lit pixels", animation.frames.all { frame -> frame.pixels.any { it != 0 } })
    }

    @Test
    fun frameSelectionFollowsDelays() {
        val animation = GifDecoder.decode(stock())!!
        assertEquals(2, animation.frames.size)
        assertEquals(0, GifDecoder.frameIndexAt(animation, 0))
        // The stock clock frames carry a zero delay, so a fixed default applies.
        val total = GifDecoder.durationMs(animation)
        assertTrue("duration must be positive", total > 0)
        assertTrue(GifDecoder.frameIndexAt(animation, total.toLong()) in animation.frames.indices)
    }

    @Test
    fun idleScreenCanForceLoopingEvenWhenGifHasNoLoopBlock() {
        val animation = GifDecoder.decode(stock())!!
        val total = GifDecoder.durationMs(animation).toLong()
        assertEquals("explicit loop restarts the clip", 0, GifDecoder.frameIndexAt(animation, total, loop = true))
        assertEquals(
            "one-shot clip holds the last frame",
            animation.frames.lastIndex,
            GifDecoder.frameIndexAt(animation, total, loop = false),
        )
    }

    @Test
    fun animationRendererReproducesStockClockFrame() {
        val animation = GifDecoder.decode(stock())!!
        val rendered = AnimationRenderer().render(animation, 0)
        // The stock clock animation shows 04:58 and must match the glyph atlas.
        val reference = ClockRenderer().render(4, 58, 0, true, ClockSize.LARGE)
        for (y in 0 until rendered.height) {
            for (x in 0 until rendered.width) {
                assertEquals("pixel $x,$y", reference.get(x, y), rendered.get(x, y))
            }
        }
    }

    @Test
    fun keepsOriginalGreyShades() {
        val animation = GifDecoder.decode(resource("/stock-shades.gif"))
        assertNotNull("shades gif must decode", animation)
        val shades = HashSet<Int>()
        for (frame in animation!!.frames) {
            for (pixel in frame.pixels) if (pixel != 0) shades.add(pixel)
        }
        // The stock clip is anti-aliased and uses well over a hundred grey
        // levels; a thresholded decoder would report two.
        assertTrue("expected many grey levels, got ${shades.size}", shades.size > 50)
        assertTrue("shades must be neutral grey", shades.all { (it shr 16 and 0xFF) == (it and 0xFF) })
    }

    @Test
    fun tintMultipliesAnimationShades() {
        val animation = GifDecoder.decode(resource("/stock-shades.gif"))!!
        val frame = animation.frames[0]
        var index = -1
        for (i in frame.pixels.indices) {
            if (frame.pixels[i] != 0) {
                index = i
                break
            }
        }
        assertTrue("clip must have lit pixels", index >= 0)
        val base = frame.pixels[index]
        val grey = base and 0xFF
        val rendered = AnimationRenderer().render(animation, 0, 0x0000FF)
        val x = index % animation.width
        val y = index / animation.width
        // 25x16 clips are drawn 1:1, so the pixel keeps its coordinates.
        assertEquals(0, rendered.get(x, y) shr 16 and 0xFF)
        assertEquals(0, rendered.get(x, y) shr 8 and 0xFF)
        assertEquals("blue level follows the source grey", grey, rendered.get(x, y) and 0xFF)
    }
}