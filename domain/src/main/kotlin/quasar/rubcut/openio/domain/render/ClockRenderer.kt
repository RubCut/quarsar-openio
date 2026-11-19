package quasar.rubcut.openio.domain.render

import quasar.rubcut.openio.domain.model.RingFrame
import quasar.rubcut.openio.domain.model.ScreenFrame
import kotlin.math.roundToInt

/**
 * Stock Quasar clock: plain white HH:MM on black, drawn with the original
 * io.sdk glyph atlases (see [ClockFont]).
 *
 * The stock layout is reproduced exactly: glyph cells are [ClockSize.digitWidth]
 * wide, every glyph is separated by one blank pixel and the colon advances by
 * its real ink width. For [ClockSize.LARGE] that fills the panel edge to edge
 * (5+1+5+1+1+1+5+1+5 = 25 px), which matches the reference frame
 * `frontal_animation/25x16/25x16_Clock.gif`.
 *
 * The ring is a separate 24-LED indicator and is allowed to be colourful;
 * physical LED 0 sits at the back/bottom and indexes grow counter-clockwise,
 * so the clock sweep runs clockwise from the front LED.
 */
class ClockRenderer(
    private val digitsColor: Int = 0xFFFFFF,
    private val colonColor: Int = 0xFFFFFF,
    private val background: Int = 0x000000,
) {
    fun render(
        hour: Int,
        minute: Int,
        second: Int = 0,
        colonOn: Boolean = true,
        size: ClockSize = ClockSize.LARGE,
    ): ScreenFrame {
        val frame = ScreenFrame()
        frame.clear(background)
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val glyphs = glyphsFor(h, m, size)
        val advances = glyphs.map { if (it.colon) it.inkWidth else size.digitWidth }
        val total = advances.sum() + (glyphs.size - 1)
        var cursor = (frame.width - total) / 2
        val top = (frame.height - size.cellHeight) / 2

        for ((index, glyph) in glyphs.withIndex()) {
            val skip = glyph.colon && !colonOn
            if (!skip) {
                drawGlyph(frame, glyph, cursor - glyph.inkLeft, top, if (glyph.colon) colonColor else digitsColor, size.digitWidth)
            }
            cursor += advances[index]
            if (index != glyphs.lastIndex) cursor += 1
        }
        // Deliberately no progress bar, tick marks or other decoration.
        return frame
    }

    fun renderNow(
        second: Int,
        colonOn: Boolean = true,
        size: ClockSize = ClockSize.LARGE,
    ): ScreenFrame {
        val now = java.time.LocalTime.now()
        return render(now.hour, now.minute, second, colonOn, size)
    }

    private data class Glyph(val rows: IntArray, val inkLeft: Int, val inkWidth: Int, val colon: Boolean)

    private fun glyphsFor(hour: Int, minute: Int, size: ClockSize): List<Glyph> = listOf(
        digit(hour / 10, size),
        digit(hour % 10, size),
        colon(size),
        digit(minute / 10, size),
        digit(minute % 10, size),
    )

    private fun digit(value: Int, size: ClockSize): Glyph {
        val rows = ClockFont.digits.getValue(size).getOrElse(value.coerceIn(0, 9)) { IntArray(size.cellHeight) }
        return Glyph(rows, inkLeft = 0, inkWidth = size.digitWidth, colon = false)
    }

    private fun colon(size: ClockSize): Glyph {
        val rows = ClockFont.colon.getValue(size)
        val (left, right) = inkBounds(rows, size.digitWidth)
        return Glyph(rows, inkLeft = left, inkWidth = (right - left + 1).coerceAtLeast(1), colon = true)
    }

    private fun inkBounds(rows: IntArray, width: Int): Pair<Int, Int> {
        var left = width
        var right = -1
        for (row in rows) {
            for (bit in 0 until width) {
                if (row and (1 shl (width - 1 - bit)) != 0) {
                    if (bit < left) left = bit
                    if (bit > right) right = bit
                }
            }
        }
        return if (right < left) 0 to 0 else left to right
    }

    private fun drawGlyph(frame: ScreenFrame, glyph: Glyph, x: Int, y: Int, color: Int, width: Int) {
        for ((rowIndex, row) in glyph.rows.withIndex()) {
            for (bit in 0 until width) {
                if (row and (1 shl (width - 1 - bit)) != 0) {
                    frame.set(x + bit, y + rowIndex, color)
                }
            }
        }
    }

    /**
     * One lit LED per displayed second, sweeping clockwise from index 0 at
     * the front centre. Physical LED 0 is the back/bottom of the ring, so the
     * front marker is LED 12 and is applied through `ring_origin`. Two
     * neighbours fade so the direction is readable.
     */
    fun renderRing(
        second: Int,
        color: Int = 0xFFFFFF,
        dim: Int = 0x000000,
        offset: Int = 0,
        direction: Int = -1,
    ): RingFrame {
        val ring = RingFrame()
        ring.clear(dim)
        val s = second.coerceIn(0, 59)
        val step = s * ring.size / 60
        val dir = if (direction < 0) -1 else 1
        val index = (((step * dir + offset) % ring.size) + ring.size) % ring.size
        ring.set(index, color)
        val prev = (((index - dir) % ring.size) + ring.size) % ring.size
        val next = (((index + dir) % ring.size) + ring.size) % ring.size
        ring.set(prev, ColorUtil.scale(color, 0.35f))
        ring.set(next, ColorUtil.scale(color, 0.35f))
        return ring
    }
}

object ColorUtil {
    fun scale(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        val r = (((color ushr 16) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        val g = (((color ushr 8) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * f).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val r = (((a ushr 16) and 0xFF) + ((((b ushr 16) and 0xFF) - ((a ushr 16) and 0xFF)) * f)).roundToInt().coerceIn(0, 255)
        val g = (((a ushr 8) and 0xFF) + ((((b ushr 8) and 0xFF) - ((a ushr 8) and 0xFF)) * f)).roundToInt().coerceIn(0, 255)
        val bl = ((a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * f)).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or bl
    }

    fun hsv(hue: Float, saturation: Float = 1f, value: Float = 1f): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).roundToInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }
}