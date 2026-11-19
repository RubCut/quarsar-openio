package quasar.rubcut.openio.domain.model

/**
 * A single RGB888 screen frame for the 25x16 front display.
 *
 * Pixel values are packed as 0xRRGGBB. The frame is mutable so renderers can
 * work without allocating an IntArray for every pixel; callers that hand a
 * frame to another thread must take a copy first.
 */
class ScreenFrame(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
) {
    val pixels: IntArray = IntArray(width * height)

    init {
        require(width > 0 && height > 0) { "frame must not be empty" }
    }

    fun clear(color: Int = 0): ScreenFrame {
        pixels.fill(color and 0xFFFFFF)
        return this
    }

    fun set(x: Int, y: Int, color: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        pixels[y * width + x] = color and 0xFFFFFF
    }

    fun get(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        return pixels[y * width + x]
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        val rgb = color and 0xFFFFFF
        val x0 = x.coerceAtLeast(0)
        val y0 = y.coerceAtLeast(0)
        val x1 = (x + w).coerceAtMost(width)
        val y1 = (y + h).coerceAtMost(height)
        if (x0 >= x1 || y0 >= y1) return
        for (yy in y0 until y1) {
            val row = yy * width
            for (xx in x0 until x1) pixels[row + xx] = rgb
        }
    }

    fun copy(): ScreenFrame {
        val out = ScreenFrame(width, height)
        pixels.copyInto(out.pixels)
        return out
    }

    fun copyFrom(other: ScreenFrame) {
        require(other.width == width && other.height == height) {
            "frame size mismatch: ${other.width}x${other.height} != ${width}x$height"
        }
        other.pixels.copyInto(pixels)
    }

    /** RGB888, row-major, three bytes per pixel: R, G, B. */
    fun toRgb888(): ByteArray {
        val out = ByteArray(pixels.size * 3)
        var o = 0
        for (p in pixels) {
            out[o++] = ((p ushr 16) and 0xFF).toByte()
            out[o++] = ((p ushr 8) and 0xFF).toByte()
            out[o++] = (p and 0xFF).toByte()
        }
        return out
    }

    companion object {
        const val DEFAULT_WIDTH = 25
        const val DEFAULT_HEIGHT = 16
        const val RGB888_BYTES = DEFAULT_WIDTH * DEFAULT_HEIGHT * 3

        fun fromRgb888(bytes: ByteArray, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT): ScreenFrame {
            require(bytes.size == width * height * 3) {
                "expected ${width * height * 3} RGB888 bytes, got ${bytes.size}"
            }
            val frame = ScreenFrame(width, height)
            var i = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val r = bytes[i++].toInt() and 0xFF
                    val g = bytes[i++].toInt() and 0xFF
                    val b = bytes[i++].toInt() and 0xFF
                    frame.pixels[y * width + x] = (r shl 16) or (g shl 8) or b
                }
            }
            return frame
        }
    }
}