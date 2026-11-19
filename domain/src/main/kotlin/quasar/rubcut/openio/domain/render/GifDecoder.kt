package quasar.rubcut.openio.domain.render

import java.io.ByteArrayOutputStream

/**
 * Minimal GIF87a/89a decoder that produces full-colour frames for the 25x16
 * front panel. The stock animations use up to ~120 shades of grey (anti-aliased
 * edges, soft LED fades), so pixel values are kept as real RGB instead of being
 * reduced to on/off. Pure Kotlin on purpose: the same decoder serves the stock
 * animations shipped in the APK assets and user supplied packs.
 */
class GifAnimation(
    val width: Int,
    val height: Int,
    val frames: List<Frame>,
    val loop: Boolean,
) {
    /** [pixels] holds 0xRRGGBB per pixel; 0 means fully off/transparent. */
    class Frame(val pixels: IntArray, val delayMs: Int)

    val isEmpty: Boolean get() = frames.isEmpty()
}

object GifDecoder {
    /** Delay used when a GIF frame declares zero delay. */
    private const val DEFAULT_DELAY_MS = 100

    fun decode(data: ByteArray): GifAnimation? = runCatching { Decoder(data).decode() }.getOrNull()

    private class Decoder(private val data: ByteArray) {
        private var pos = 0

        fun decode(): GifAnimation? {
            if (data.size < 13) return null
            val signature = String(data, 0, 6, Charsets.US_ASCII)
            if (signature != "GIF87a" && signature != "GIF89a") return null
            pos = 6
            val width = readShort()
            val height = readShort()
            if (width <= 0 || height <= 0 || width > 1024 || height > 1024) return null
            val packed = readByte()
            readByte() // background colour index
            readByte() // pixel aspect ratio
            val globalPalette = if (packed and 0x80 != 0) readPalette(packed and 0x07) else null

            val canvas = IntArray(width * height) { TRANSPARENT }
            val frames = ArrayList<GifAnimation.Frame>()
            var loop = false
            var delayMs = DEFAULT_DELAY_MS
            var transparentIndex = -1
            var disposal = 0

            while (pos < data.size) {
                when (val block = readByte()) {
                    0x3B -> break // trailer
                    0x21 -> {
                        val label = readByte()
                        when (label) {
                            0xF9 -> {
                                val size = readByte()
                                if (size >= 4) {
                                    val flags = readByte()
                                    val delayHundredths = readShort()
                                    val transparent = readByte()
                                    delayMs = if (delayHundredths <= 0) DEFAULT_DELAY_MS else delayHundredths * 10
                                    disposal = (flags shr 2) and 0x07
                                    transparentIndex = if (flags and 0x01 != 0) transparent else -1
                                    skipSubBlocks()
                                } else {
                                    skipSubBlocks()
                                }
                            }
                            0xFF -> {
                                val size = readByte()
                                val app = String(data, pos, size.coerceAtMost(data.size - pos), Charsets.US_ASCII)
                                pos += size
                                if (app.startsWith("NETSCAPE")) loop = readNetscapeLoop()
                                else skipSubBlocks()
                            }
                            else -> {
                                val size = readByte()
                                pos += size
                                skipSubBlocks()
                            }
                        }
                    }
                    0x2C -> {
                        val left = readShort()
                        val top = readShort()
                        val imageWidth = readShort()
                        val imageHeight = readShort()
                        if (imageWidth <= 0 || imageHeight <= 0) return null
                        val imagePacked = readByte()
                        val palette = if (imagePacked and 0x80 != 0) {
                            readPalette(imagePacked and 0x07)
                        } else {
                            globalPalette ?: return null
                        }
                        val interlaced = imagePacked and 0x40 != 0
                        val minCodeSize = readByte()
                        val compressed = readSubBlocks()
                        val indices = Lzw(minCodeSize).decode(compressed, imageWidth * imageHeight) ?: return null
                        if (indices.size < imageWidth * imageHeight) return null

                        val previous = canvas.copyOf()
                        drawImage(
                            canvas, indices, imageWidth, imageHeight, left, top,
                            interlaced, palette, transparentIndex, width, height,
                        )
                        frames.add(
                            GifAnimation.Frame(
                                pixels = toRgb(canvas),
                                delayMs = delayMs,
                            ),
                        )
                        when (disposal) {
                            2 -> clearRect(canvas, left, top, imageWidth, imageHeight, width, height)
                            3 -> previous.copyInto(canvas)
                        }
                        delayMs = DEFAULT_DELAY_MS
                        disposal = 0
                        transparentIndex = -1
                    }
                    else -> return null
                }
            }
            if (frames.isEmpty()) return null
            return GifAnimation(width, height, frames, loop)
        }

        private fun drawImage(
            canvas: IntArray,
            indices: ByteArray,
            imageWidth: Int,
            imageHeight: Int,
            left: Int,
            top: Int,
            interlaced: Boolean,
            palette: IntArray,
            transparentIndex: Int,
            canvasWidth: Int,
            canvasHeight: Int,
        ) {
            val order = if (interlaced) interlaceRows(imageHeight) else IntArray(imageHeight) { it }
            var source = 0
            for (row in order) {
                val y = top + row
                for (x in 0 until imageWidth) {
                    val index = indices[source++].toInt() and 0xFF
                    val targetX = left + x
                    if (targetX < 0 || targetX >= canvasWidth || y < 0 || y >= canvasHeight) continue
                    if (index == transparentIndex) continue
                    val color = palette.getOrElse(index) { TRANSPARENT }
                    if (color == TRANSPARENT) continue
                    canvas[y * canvasWidth + targetX] = color
                }
            }
        }

        private fun clearRect(
            canvas: IntArray,
            left: Int,
            top: Int,
            imageWidth: Int,
            imageHeight: Int,
            canvasWidth: Int,
            canvasHeight: Int,
        ) {
            for (y in top until (top + imageHeight).coerceAtMost(canvasHeight)) {
                for (x in left until (left + imageWidth).coerceAtMost(canvasWidth)) {
                    if (x >= 0 && y >= 0) canvas[y * canvasWidth + x] = TRANSPARENT
                }
            }
        }

        private fun toRgb(canvas: IntArray): IntArray {
            val out = IntArray(canvas.size)
            for (i in canvas.indices) {
                val color = canvas[i]
                // -1 is transparent, and pure black is visually off on the LED
                // panel, so both map to 0 and can be skipped by the renderer.
                out[i] = if (color == TRANSPARENT) 0 else color and 0xFFFFFF
            }
            return out
        }

        private fun readNetscapeLoop(): Boolean {
            var loop = false
            while (true) {
                val size = readByte()
                if (size == 0) break
                if (size >= 3 && pos + 2 < data.size) {
                    val sub = readByte()
                    val count = readShort()
                    if (sub == 0x01 && count != 0) loop = true
                    pos += (size - 3).coerceAtLeast(0)
                } else {
                    pos += size
                }
            }
            return loop
        }

        private fun readPalette(bits: Int): IntArray {
            val entries = 1 shl (bits + 1)
            val palette = IntArray(entries)
            for (i in 0 until entries) {
                if (pos + 2 >= data.size) return palette
                val r = readByte()
                val g = readByte()
                val b = readByte()
                palette[i] = (r shl 16) or (g shl 8) or b
            }
            return palette
        }

        private fun skipSubBlocks() {
            while (true) {
                val size = readByte()
                if (size == 0) return
                pos += size
            }
        }

        private fun readSubBlocks(): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readByte()
                if (size == 0) break
                val end = (pos + size).coerceAtMost(data.size)
                out.write(data, pos, end - pos)
                pos = end
            }
            return out.toByteArray()
        }

        private fun readByte(): Int {
            if (pos >= data.size) throw IndexOutOfBoundsException("gif truncated at $pos")
            return data[pos++].toInt() and 0xFF
        }

        private fun readShort(): Int {
            val lo = readByte()
            val hi = readByte()
            return lo or (hi shl 8)
        }
    }

    /** GIF LZW decompression (variable width codes, LSB first). */
    private class Lzw(private val minCodeSize: Int) {
        fun decode(data: ByteArray, expected: Int): ByteArray? {
            if (minCodeSize < 2 || minCodeSize > 11) return null
            val clearCode = 1 shl minCodeSize
            val endCode = clearCode + 1
            var codeSize = minCodeSize + 1
            var codeMask = (1 shl codeSize) - 1

            val prefix = IntArray(4096)
            val suffix = IntArray(4096)
            val first = IntArray(4096)
            for (i in 0 until clearCode) {
                prefix[i] = -1
                suffix[i] = i
                first[i] = i
            }
            var next = clearCode + 2
            var code = 0
            var bits = 0
            var bitPos = 0
            var old = -1
            val out = ByteArray(expected.coerceAtMost(4 * 1024 * 1024))
            var outPos = 0
            val stack = IntArray(4096)
            var stackPos = 0

            fun nextCode(): Int {
                while (bits < codeSize) {
                    if (bitPos >= data.size) return endCode
                    code = code or ((data[bitPos++].toInt() and 0xFF) shl bits)
                    bits += 8
                }
                val value = code and codeMask
                code = code ushr codeSize
                bits -= codeSize
                return value
            }

            while (true) {
                val current = nextCode()
                if (current == endCode) break
                if (current == clearCode) {
                    codeSize = minCodeSize + 1
                    codeMask = (1 shl codeSize) - 1
                    next = clearCode + 2
                    old = -1
                    continue
                }
                if (old == -1) {
                    if (current >= next) return null
                    if (outPos < out.size) out[outPos++] = current.toByte()
                    old = current
                    continue
                }
                var entry = current
                stackPos = 0
                if (entry >= next) {
                    if (entry != next) return null
                    stack[stackPos++] = first[old]
                    entry = old
                }
                while (entry >= clearCode) {
                    stack[stackPos++] = suffix[entry]
                    entry = prefix[entry]
                    if (stackPos >= stack.size) return null
                }
                stack[stackPos++] = suffix[entry]
                if (next < 4096) {
                    prefix[next] = old
                    suffix[next] = suffix[stack[stackPos - 1]]
                    first[next] = first[old]
                    next++
                    if (next == (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                        codeMask = (1 shl codeSize) - 1
                    }
                }
                old = current
                while (stackPos > 0) {
                    if (outPos >= out.size) return out
                    out[outPos++] = stack[--stackPos].toByte()
                }
            }
            return if (outPos == out.size) out else out.copyOf(outPos)
        }
    }

    private const val TRANSPARENT = -1

    private fun interlaceRows(height: Int): IntArray {
        val rows = IntArray(height)
        var index = 0
        for (pass in 0 until 4) {
            val start = when (pass) {
                0 -> 0
                1 -> 4
                2 -> 2
                else -> 1
            }
            val step = when (pass) {
                0 -> 8
                1 -> 8
                2 -> 4
                else -> 2
            }
            var row = start
            while (row < height) {
                rows[index++] = row
                row += step
            }
        }
        return rows
    }

    /** Renders the frame that is active at [elapsedMs]. */
    fun frameAt(
        animation: GifAnimation,
        elapsedMs: Long,
        loop: Boolean = animation.loop,
    ): GifAnimation.Frame = animation.frames[frameIndexAt(animation, elapsedMs, loop)]

    /**
     * Index of the frame that is active at [elapsedMs].
     *
     * [loop] is an explicit override: an idle screen animation must loop even
     * when the GIF itself does not carry a NETSCAPE loop block (many stock
     * Yandex clips are one-shot). One-shot overlays pass `false`.
     */
    fun frameIndexAt(
        animation: GifAnimation,
        elapsedMs: Long,
        loop: Boolean = animation.loop,
    ): Int {
        if (animation.frames.size == 1) return 0
        val total = animation.frames.sumOf { it.delayMs.toLong() }.coerceAtLeast(1)
        var t = if (loop) elapsedMs % total else elapsedMs.coerceAtMost(total - 1)
        for (index in animation.frames.indices) {
            val delay = animation.frames[index].delayMs.toLong()
            if (t < delay) return index
            t -= delay
        }
        return animation.frames.lastIndex
    }

    /** Total duration of one pass, used by the caller to schedule repaints. */
    fun durationMs(animation: GifAnimation): Int =
        animation.frames.sumOf { it.delayMs }.coerceAtLeast(1)
}