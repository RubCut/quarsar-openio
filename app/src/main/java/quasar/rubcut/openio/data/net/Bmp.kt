package quasar.rubcut.openio.data.net

import java.io.ByteArrayOutputStream

/** Minimal 24-bit BMP encoder, used to show the 25x16 viewport in a browser. */
object Bmp {
    fun encodeRgb888(rgb: ByteArray, width: Int, height: Int): ByteArray {
        require(rgb.size == width * height * 3) { "unexpected RGB888 size ${rgb.size}" }
        val rowSize = ((width * 3 + 3) / 4) * 4
        val pixelBytes = rowSize * height
        val fileSize = 54 + pixelBytes
        val out = ByteArrayOutputStream(fileSize)

        fun le16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        fun le32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        out.write('B'.code)
        out.write('M'.code)
        le32(fileSize)
        le16(0)
        le16(0)
        le32(54)
        le32(40)
        le32(width)
        le32(height)
        le16(1)
        le16(24)
        le32(0)
        le32(pixelBytes)
        le32(2835)
        le32(2835)
        le32(0)
        le32(0)

        val padding = rowSize - width * 3
        for (y in height - 1 downTo 0) {
            var index = y * width * 3
            for (x in 0 until width) {
                val r = rgb[index++].toInt() and 0xFF
                val g = rgb[index++].toInt() and 0xFF
                val b = rgb[index++].toInt() and 0xFF
                out.write(b)
                out.write(g)
                out.write(r)
            }
            repeat(padding) { out.write(0) }
        }
        return out.toByteArray()
    }
}