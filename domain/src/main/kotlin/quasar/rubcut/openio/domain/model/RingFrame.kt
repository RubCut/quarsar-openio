package quasar.rubcut.openio.domain.model

/** A frame for the 24-pixel light ring. Pixel values are 0xRRGGBB. */
class RingFrame(val size: Int = DEFAULT_SIZE) {
    val pixels: IntArray = IntArray(size)

    init {
        require(size > 0) { "ring must not be empty" }
    }

    fun clear(color: Int = 0): RingFrame {
        pixels.fill(color and 0xFFFFFF)
        return this
    }

    fun set(index: Int, color: Int) {
        if (index < 0 || index >= size) return
        pixels[index] = color and 0xFFFFFF
    }

    fun get(index: Int): Int = if (index < 0 || index >= size) 0 else pixels[index]

    fun fill(from: Int, to: Int, color: Int) {
        val rgb = color and 0xFFFFFF
        val a = from.coerceIn(0, size)
        val b = to.coerceIn(0, size)
        if (a >= b) return
        for (i in a until b) pixels[i] = rgb
    }

    fun copy(): RingFrame {
        val out = RingFrame(size)
        pixels.copyInto(out.pixels)
        return out
    }

    /**
     * Moves logical LED 0 to physical [offset]. The UI uses this as the
     * "front side" marker: patterns keep their own orientation while the
     * station can be rotated.
     */
    fun rotated(offset: Int): RingFrame {
        val shift = ((offset % size) + size) % size
        if (shift == 0) return copy()
        val out = RingFrame(size)
        for (i in 0 until size) {
            out.pixels[(i + shift) % size] = pixels[i]
        }
        return out
    }

    fun toRgb888(): ByteArray {
        val out = ByteArray(size * 3)
        var o = 0
        for (p in pixels) {
            out[o++] = ((p ushr 16) and 0xFF).toByte()
            out[o++] = ((p ushr 8) and 0xFF).toByte()
            out[o++] = (p and 0xFF).toByte()
        }
        return out
    }

    companion object {
        const val DEFAULT_SIZE = 24
        const val RGB888_BYTES = DEFAULT_SIZE * 3

        fun fromRgb888(bytes: ByteArray, size: Int = DEFAULT_SIZE): RingFrame {
            require(bytes.size == size * 3) {
                "expected ${size * 3} RGB888 bytes, got ${bytes.size}"
            }
            val frame = RingFrame(size)
            var i = 0
            for (index in 0 until size) {
                val r = bytes[i++].toInt() and 0xFF
                val g = bytes[i++].toInt() and 0xFF
                val b = bytes[i++].toInt() and 0xFF
                frame.pixels[index] = (r shl 16) or (g shl 8) or b
            }
            return frame
        }
    }
}