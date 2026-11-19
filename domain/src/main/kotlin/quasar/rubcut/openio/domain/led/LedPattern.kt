package quasar.rubcut.openio.domain.led

import quasar.rubcut.openio.domain.model.RingFrame
import kotlin.math.abs

/**
 * Yandex `*.led` ring pattern.
 *
 * The format is a small text script used by io.sdk. One block is either a
 * literal frame (`RRGGBB RRGGBB ... <durationMs>`) or a gradient directive:
 *
 * ```
 * gammacorrection
 * loop
 * gradient 0 0b011d 1 1a000f 523
 * smooth 40
 * 000000 000000 ... 133
 * ```
 *
 * Older devices (nebula set) use one 6-bit brightness token instead of a
 * duration, in which case [defaultFrameMs] is used. Patterns written for a
 * different LED count are resampled to the panel's ring so one pack can hold
 * mini and max animations together.
 */
class LedPattern internal constructor(
    val id: String,
    val frames: List<LedFrame>,
    val loop: Boolean,
    val gamma: Boolean,
    val defaultFrameMs: Int,
    val sourceLedCount: Int,
    /** The `rotate` directive spins the frame one LED per frame duration. */
    val rotate: Boolean = false,
) {
    val totalMs: Long = frames.sumOf { it.holdMs.toLong() }

    /** Colour at [index] for [elapsedMs], with the configured smooth fades. */
    fun frameAt(elapsedMs: Long, size: Int = RingFrame.DEFAULT_SIZE): RingFrame {
        val out = RingFrame(size)
        if (frames.isEmpty()) return out
        val time = if (loop && totalMs > 0) {
            ((elapsedMs % totalMs) + totalMs) % totalMs
        } else {
            elapsedMs.coerceIn(0, totalMs)
        }
        var start = 0L
        var index = frames.lastIndex
        for (i in frames.indices) {
            val frame = frames[i]
            if (time < start + frame.holdMs) {
                index = i
                break
            }
            start += frame.holdMs
        }
        val frame = frames[index]
        val target = resample(frame.colors, size)
        val fade = frame.transitionMs.coerceAtMost(frame.holdMs)
        if (fade <= 0 || index == 0) {
            target.copyInto(out.pixels)
            return applyRotation(elapsedMs, index, out)
        }
        val previous = resample(frames[index - 1].colors, size)
        val local = (time - start).coerceIn(0L, fade.toLong())
        val t = local.toFloat() / fade
        for (i in 0 until size) {
            out.pixels[i] = mix(previous[i], target[i], t)
        }
        return applyRotation(elapsedMs, index, out)
    }

    private fun applyRotation(elapsedMs: Long, index: Int, frame: RingFrame): RingFrame {
        if (!rotate || frames.isEmpty()) return frame
        val hold = frames[index].holdMs.coerceAtLeast(1)
        val steps = ((elapsedMs / hold) % frame.size).toInt()
        return if (steps == 0) frame else frame.rotated(steps)
    }

    private fun resample(colors: IntArray, size: Int): IntArray {
        if (colors.size == size) return colors
        val out = IntArray(size)
        for (i in 0 until size) {
            val pos = i.toFloat() * colors.size / size
            out[i] = colors[pos.toInt().coerceIn(0, colors.lastIndex)]
        }
        return out
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val ar = (a ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or bl
    }

    companion object {
        const val DEFAULT_FRAME_MS = 40

        fun parse(id: String, text: String, defaultFrameMs: Int = DEFAULT_FRAME_MS): LedPattern? =
            LedPatternParser.parse(id, text, defaultFrameMs)
    }
}

data class LedFrame(
    /** 0xRRGGBB, already gamma-corrected if the pattern asked for it. */
    val colors: IntArray,
    val holdMs: Int,
    val transitionMs: Int,
)

object LedPatternParser {
    fun parse(id: String, text: String, defaultFrameMs: Int = LedPattern.DEFAULT_FRAME_MS): LedPattern? {
        var loop = false
        var gamma = false
        var rotate = false
        var pendingSmooth = 0
        var sourceCount = 0
        val frames = ArrayList<LedFrame>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val tokens = line.split(Regex("\\s+"))
            when {
                tokens[0].equals("loop", true) -> loop = true
                tokens[0].equals("gammacorrection", true) -> gamma = true
                tokens[0].equals("smooth", true) -> {
                    pendingSmooth = tokens.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 10_000) ?: 0
                }
                tokens[0].equals("rotate", true) -> {
                    val frame = parseFrame(tokens.drop(1), defaultFrameMs) ?: continue
                    rotate = true
                    sourceCount = maxOf(sourceCount, frame.colors.size)
                    frames.add(
                        LedFrame(
                            colors = if (gamma) gamma(frame.colors) else frame.colors,
                            holdMs = frame.holdMs,
                            transitionMs = pendingSmooth,
                        ),
                    )
                    pendingSmooth = 0
                }
                tokens[0].equals("gradient", true) -> {
                    val frame = parseGradient(tokens) ?: continue
                    sourceCount = maxOf(sourceCount, frame.colors.size)
                    frames.add(
                        LedFrame(
                            colors = if (gamma) gamma(frame.colors) else frame.colors,
                            holdMs = frame.holdMs,
                            transitionMs = pendingSmooth,
                        ),
                    )
                    pendingSmooth = 0
                }
                else -> {
                    val frame = parseFrame(tokens, defaultFrameMs) ?: continue
                    sourceCount = maxOf(sourceCount, frame.colors.size)
                    frames.add(
                        LedFrame(
                            colors = if (gamma) gamma(frame.colors) else frame.colors,
                            holdMs = frame.holdMs,
                            transitionMs = pendingSmooth,
                        ),
                    )
                    pendingSmooth = 0
                }
            }
        }
        if (frames.isEmpty()) return null
        return LedPattern(id, frames, loop, gamma, defaultFrameMs, sourceCount, rotate)
    }

    private fun parseGradient(tokens: List<String>): LedFrame? {
        if (tokens.size < 6) return null
        val p0 = tokens[1].toFloatOrNull() ?: return null
        val c0 = parseColor(tokens[2]) ?: return null
        val p1 = tokens[3].toFloatOrNull() ?: return null
        val c1 = parseColor(tokens[4]) ?: return null
        val hold = tokens[5].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val count = STOCK_LED_COUNT
        val colors = IntArray(count)
        val lo = minOf(p0, p1)
        val hi = maxOf(p0, p1)
        val span = (hi - lo).takeIf { abs(it) > 1e-4f } ?: 1f
        for (i in 0 until count) {
            val p = i.toFloat() / count
            val t = ((p - lo) / span).coerceIn(0f, 1f)
            val from = if (p0 <= p1) c0 else c1
            val to = if (p0 <= p1) c1 else c0
            colors[i] = blend(from, to, t)
        }
        return LedFrame(colors, hold, 0)
    }

    private fun parseFrame(tokens: List<String>, defaultFrameMs: Int): LedFrame? {
        if (tokens.size < 2) return null
        val colors = ArrayList<Int>(tokens.size)
        var duration: Int? = null
        var brightness: Int? = null
        for ((index, token) in tokens.withIndex()) {
            val last = index == tokens.lastIndex
            if (last) {
                // Old nebula patterns: 24 colours + one/two digit brightness.
                if (token.length <= 2) {
                    brightness = token.toIntOrNull(16)
                } else {
                    duration = token.toIntOrNull()
                }
                continue
            }
            val color = parseColor(token) ?: return null
            colors.add(color)
        }
        if (colors.size < 3) return null
        val scale = brightness?.let { (it.coerceIn(0, 255) / 255f).coerceIn(0f, 1f) } ?: 1f
        val scaled = if (scale < 1f) {
            IntArray(colors.size) { blend(0, colors[it], scale) }
        } else {
            colors.toIntArray()
        }
        return LedFrame(scaled, (duration ?: defaultFrameMs).coerceAtLeast(1), 0)
    }

    private fun parseColor(token: String): Int? {
        val value = token.toIntOrNull(16) ?: return null
        if (token.length > 6) return null
        return value and 0xFFFFFF
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val ar = (a ushr 16) and 0xFF
        val ag = (a ushr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b ushr 16) and 0xFF
        val bg = (b ushr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or bl
    }

    /**
     * Yandex applies a 2.2 display gamma to the pattern files. The panel is
     * driven in linear-ish RGB, so lifting the values keeps the original look
     * and avoids the washed-out mid-tones of a plain copy.
     */
    private fun gamma(colors: IntArray): IntArray {
        val lut = IntArray(256) { i ->
            (Math.pow(i / 255.0, 1 / 2.2) * 255.0).toInt().coerceIn(0, 255)
        }
        return IntArray(colors.size) { index ->
            val c = colors[index]
            (lut[(c ushr 16) and 0xFF] shl 16) or
                (lut[(c ushr 8) and 0xFF] shl 8) or
                lut[c and 0xFF]
        }
    }

    private const val STOCK_LED_COUNT = 24
}