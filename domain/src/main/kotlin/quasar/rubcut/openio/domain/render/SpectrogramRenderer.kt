package quasar.rubcut.openio.domain.render

import quasar.rubcut.openio.domain.audio.Fft
import quasar.rubcut.openio.domain.model.ScreenFrame
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Screen styles that io.sdk ships inside `libquasar_daemons.so`.
 *
 * The stock `SpectrogramAnimation` is not a file in `/data`: the daemon builds
 * it from a named preset (`barsBottom`, `barsCenter`, `waveCenter`, `flame`,
 * `pads`, `bricksSmall`, `random`). The values below mirror the preset JSON
 * that the Station Max reports to the cloud, so the 25x16 panel gets the same
 * family of looks instead of only our oscilloscope trace.
 *
 * Everything stays monochrome: the front matrix is a white LED panel, shading
 * comes from brightness only.
 */
enum class SpectrogramStyle(
    val id: String,
    /** Value stored in a plugin manifest's `builtin` field. */
    val builtin: String,
    val kind: Kind,
    val bandCount: Int,
    val frequencyMin: Double,
    val frequencyMax: Double,
    val origin: Origin = Origin.BOTTOM,
    val barWidth: Int = 1,
    val brightnessFading: Float = 0.2f,
    val bandSmoothingFactor: Float = 0.8f,
    val splineOrder: Int = 0,
    val cols: Int = 0,
    val rows: Int = 0,
    val padSize: Int = 3,
    val padSpacing: Int = 4,
    val brickWidth: Int = 2,
    val brickHeight: Int = 1,
    /** Exponent of the vertical brightness fade, 0 means flat. */
    val brightnessGamma: Float = 1f,
    val randomPresets: List<String> = emptyList(),
) {
    /** Our original one-line oscilloscope trace. */
    WAVE_TRACE(
        id = "wave",
        builtin = "oscilloscope",
        kind = Kind.TRACE,
        bandCount = 0,
        frequencyMin = 20.0,
        frequencyMax = 8000.0,
    ),

    /** `barsBottom`: 25 one-pixel bars growing from the bottom edge. */
    BARS_BOTTOM(
        id = "barsBottom",
        builtin = "spectrogram:barsBottom",
        kind = Kind.BARS,
        bandCount = 25,
        frequencyMin = 40.0,
        frequencyMax = 3000.0,
        origin = Origin.BOTTOM,
        barWidth = 1,
        brightnessFading = 0.2f,
    ),

    /** `barsCenter`: symmetric bars around the middle row. */
    BARS_CENTER(
        id = "barsCenter",
        builtin = "spectrogram:barsCenter",
        kind = Kind.BARS,
        bandCount = 26,
        frequencyMin = 40.0,
        frequencyMax = 2000.0,
        origin = Origin.CENTER,
        barWidth = 1,
        brightnessFading = 0.2f,
        bandSmoothingFactor = 0.8f,
        splineOrder = 5,
    ),

    /** `waveCenter`: smooth 12-band curve mirrored around the middle row. */
    WAVE_CENTER(
        id = "waveCenter",
        builtin = "spectrogram:waveCenter",
        kind = Kind.WAVE,
        bandCount = 12,
        frequencyMin = 30.0,
        frequencyMax = 3200.0,
        origin = Origin.CENTER,
        barWidth = 0,
        brightnessFading = 0.25f,
        bandSmoothingFactor = 0.8f,
        splineOrder = 5,
    ),

    /** `flame`: flickering bars from the bottom edge. */
    FLAME(
        id = "flame",
        builtin = "spectrogram:flame",
        kind = Kind.FLAME,
        bandCount = 25,
        frequencyMin = 40.0,
        frequencyMax = 4000.0,
        origin = Origin.BOTTOM,
        barWidth = 0,
    ),

    /** `pads`: 6x4 grid of square pads. */
    PADS(
        id = "pads",
        builtin = "spectrogram:pads",
        kind = Kind.PADS,
        bandCount = 24,
        frequencyMin = 100.0,
        frequencyMax = 3000.0,
        bandSmoothingFactor = 0.9f,
        brightnessFading = 0f,
        cols = 6,
        rows = 4,
        padSize = 3,
        padSpacing = 4,
        brightnessGamma = 0.5f,
    ),

    /** `bricksSmall`: 2x1 bricks with a falling top brick. */
    BRICKS(
        id = "bricksSmall",
        builtin = "spectrogram:bricksSmall",
        kind = Kind.BRICKS,
        bandCount = 8,
        frequencyMin = 40.0,
        frequencyMax = 3000.0,
        bandSmoothingFactor = 0.8f,
        brightnessFading = 0.2f,
        brickWidth = 2,
        brickHeight = 1,
    ),

    /** `random`: rotates the stock presets every few seconds. */
    RANDOM(
        id = "random",
        builtin = "spectrogram:random",
        kind = Kind.RANDOM,
        bandCount = 0,
        frequencyMin = 0.0,
        frequencyMax = 0.0,
        randomPresets = listOf("flame", "waveCenter", "pads", "barsBottom", "barsCenter", "bricksSmall"),
    );

    enum class Kind { TRACE, BARS, WAVE, FLAME, PADS, BRICKS, RANDOM }

    enum class Origin { BOTTOM, CENTER }

    companion object {
        /** Maps a manifest `builtin` value to a screen style. */
        fun fromBuiltin(builtin: String?): SpectrogramStyle? {
            val value = builtin?.trim().orEmpty()
            if (value.isEmpty()) return null
            entries.firstOrNull { it.builtin.equals(value, true) }?.let { return it }
            val plain = value.removePrefix("spectrogram:").removePrefix("visualizer.")
            return entries.firstOrNull { it.id.equals(plain, true) }
        }

        fun fromId(id: String?): SpectrogramStyle? =
            id?.let { value -> entries.firstOrNull { it.id.equals(value.trim(), true) } }
    }
}

/**
 * Renders the stock io.sdk screen presets on the 25x16 matrix.
 *
 * Bands are logarithmically spaced inside each preset's frequency window and
 * mapped through the same decibel scale as [RingSpectrumRenderer], with instant
 * attack and the preset's smoothing factor on the way down. That keeps a
 * stationary tone from flickering while still reacting to a beat.
 */
class SpectrogramRenderer(
    private val sampleRate: Int = 16_000,
    private val stock: StockFrameSource? = null,
) {
    private var levels = FloatArray(MAX_BANDS)
    private var activeId = ""
    private var frameCounter = 0L

    private var randomStyle: SpectrogramStyle? = null
    private var randomStartNs = 0L

    fun render(
        samples: ShortArray,
        style: SpectrogramStyle,
        sensitivity: Float = 1f,
        fftSize: Int = DEFAULT_FFT_SIZE,
    ): ScreenFrame {
        frameCounter++
        val active = resolve(style)
        if (active.id != activeId) {
            activeId = active.id
            levels.fill(0f)
        }
        computeLevels(samples, active, sensitivity, fftSize)
        val frame = ScreenFrame()
        val stockSource = stock
        if (stockSource != null && active.kind in STOCK_KINDS) {
            // The real io.sdk renderer paints its own intensity buffer, so the
            // result matches the Station instead of a Kotlin approximation.
            val native = runCatching { stockSource.render(active, levels) }.getOrNull()
            if (native != null && native.size >= frame.width * frame.height) {
                for (index in 0 until frame.width * frame.height) {
                    frame.pixels[index] = shade(native[index])
                }
                return frame
            }
        }
        when (active.kind) {
            SpectrogramStyle.Kind.TRACE,
            SpectrogramStyle.Kind.RANDOM,
            -> Unit // TRACE is handled by OscilloscopeRenderer; RANDOM is resolved above.
            SpectrogramStyle.Kind.BARS -> drawBars(frame, active)
            SpectrogramStyle.Kind.WAVE -> drawWave(frame, active)
            SpectrogramStyle.Kind.FLAME -> drawFlame(frame)
            SpectrogramStyle.Kind.PADS -> drawPads(frame, active)
            SpectrogramStyle.Kind.BRICKS -> drawBricks(frame, active)
        }
        return frame
    }

    /** Drops smoothing state, e.g. when switching the selected visualiser. */
    fun reset() {
        levels.fill(0f)
        activeId = ""
        randomStyle = null
        randomStartNs = 0L
    }

    private fun resolve(style: SpectrogramStyle): SpectrogramStyle {
        if (style.kind != SpectrogramStyle.Kind.RANDOM) return style
        val now = System.nanoTime()
        if (randomStyle == null || now - randomStartNs >= RANDOM_INTERVAL_NS) {
            val pool = style.randomPresets
                .mapNotNull { id -> SpectrogramStyle.entries.firstOrNull { it.id == id } }
                .filter { it.kind != SpectrogramStyle.Kind.TRACE }
            if (pool.isNotEmpty()) {
                val next = if (pool.size == 1) 0 else Random(now).nextInt(pool.size)
                randomStyle = pool[next]
                randomStartNs = now
            }
        }
        return randomStyle ?: SpectrogramStyle.BARS_BOTTOM
    }

    private fun computeLevels(
        samples: ShortArray,
        style: SpectrogramStyle,
        sensitivity: Float,
        fftSize: Int,
    ) {
        val count = style.bandCount.coerceIn(1, MAX_BANDS)
        if (levels.size != count) levels = FloatArray(count)
        if (samples.isEmpty()) {
            decay(style)
            return
        }
        val spectrum = Fft.magnitude(samples, fftSize)
        if (spectrum.size < 4) {
            decay(style)
            return
        }
        val size = Integer.highestOneBit(fftSize.coerceAtLeast(16))
        val binHz = sampleRate.toDouble() / size
        val lastBin = spectrum.size - 1
        val low = (style.frequencyMin / binHz).toInt().coerceIn(1, lastBin)
        val high = (style.frequencyMax / binHz).toInt().coerceIn(low + 1, lastBin)
        val edges = logEdges(low, high, count, lastBin)
        val sensitivityValue = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)

        val peaks = FloatArray(count)
        var framePeak = 0f
        for (band in 0 until count) {
            var peak = 0f
            val from = edges[band]
            val to = edges[band + 1].coerceAtLeast(from + 1).coerceAtMost(spectrum.size)
            for (bin in from until to) {
                if (spectrum[bin] > peak) peak = spectrum[bin]
            }
            peaks[band] = peak
            if (peak > framePeak) framePeak = peak
        }
        val gate = framePeak * GATE_RATIO
        val gain = sensitivityValue * BASE_GAIN

        // Absolute decibel scale with a small fixed boost, same as
        // [RingSpectrumRenderer]: loud material fills most of the range while
        // a band 30 dB down stays clearly shorter. The earlier relative
        // mapping (shape vs the loudest band) flattened every band that was
        // within ~36 dB of the peak, which made pads/wave light up as a
        // solid wall of cubes instead of dancing per band.
        for (band in 0 until count) {
            val peak = peaks[band]
            val target = if (peak < gate || peak < MIN_MAGNITUDE || framePeak <= 0f) {
                0f
            } else {
                Fft.level(peak * gain, FLOOR_DB)
            }
            val old = levels[band]
            levels[band] = if (target > old) {
                target
            } else {
                max(0f, old * style.bandSmoothingFactor.coerceIn(0.05f, 0.99f))
            }
            if (levels[band] < CUTOFF) levels[band] = 0f
        }
    }

    private fun decay(style: SpectrogramStyle) {
        for (i in levels.indices) {
            levels[i] = if (levels[i] * style.bandSmoothingFactor < CUTOFF) 0f else levels[i] * style.bandSmoothingFactor
        }
    }

    /** Geometric band edges over [low, high] inclusive: [count + 1] bins. */
    private fun logEdges(low: Int, high: Int, count: Int, lastBin: Int): IntArray {
        val out = IntArray(count + 1)
        val span = ln(high.toDouble()) - ln(low.toDouble())
        for (i in 0..count) {
            val ratio = i.toDouble() / count
            out[i] = (low * kotlin.math.exp(span * ratio)).toInt().coerceIn(low, lastBin)
        }
        for (i in 1..count) {
            if (out[i] <= out[i - 1]) out[i] = (out[i - 1] + 1).coerceAtMost(lastBin)
        }
        out[count] = high
        return out
    }

    /**
     * Stock fill: a bar starts at full brightness at its origin and fades
     * towards the tip with `brightnessFading` as the exponent. The native
     * code does `powf(1 - i / height, brightnessFading)`, so a preset with
     * `0.2` is bright over almost the whole bar and only dims near the tip.
     */
    private fun fadeExponent(index: Int, height: Int, exponent: Float): Float {
        if (height <= 0) return 0f
        val t = (index.toFloat() / height).coerceIn(0f, 1f)
        val base = (1f - t).coerceIn(0f, 1f)
        return if (exponent <= 0f) 1f else base.pow(exponent)
    }

    private fun drawBars(frame: ScreenFrame, style: SpectrogramStyle) {
        val width = frame.width
        val height = frame.height
        val center = height / 2
        val maxHeight = if (style.origin == SpectrogramStyle.Origin.CENTER) center else height
        if (maxHeight <= 0) return
        val bars = width
        for (bar in 0 until bars) {
            val level = levels[bar * levels.size / bars].coerceIn(0f, 1f)
            if (level <= 0f) continue
            val h = (level * maxHeight).roundToInt().coerceIn(0, maxHeight)
            if (h <= 0) continue
            for (i in 0 until h) {
                val color = shade(fadeExponent(i, h, style.brightnessFading))
                if (style.origin == SpectrogramStyle.Origin.CENTER) {
                    frame.set(bar, center - 1 - i, color)
                    frame.set(bar, center + i, color)
                } else {
                    frame.set(bar, height - 1 - i, color)
                }
            }
        }
    }

    /**
     * `waveCenter` is a *filled* two-sided wave: every column is a vertical
     * gradient from the middle row to the interpolated band height. The
     * native `WaveSpectrogram` mirrors the same column above and below the
     * centre, so the line-only trace used before looked nothing like it.
     */
    private fun drawWave(frame: ScreenFrame, style: SpectrogramStyle) {
        val height = frame.height
        val width = frame.width
        val center = height / 2
        val amp = center.coerceAtLeast(1)
        val points = spline(levels, width)
        for (x in 0 until width) {
            val value = points[x].coerceIn(0f, 1f)
            val h = (value * amp).roundToInt().coerceIn(0, amp)
            if (h <= 0) continue
            for (i in 0 until h) {
                val color = shade(fadeExponent(i, h, style.brightnessFading))
                frame.set(x, center - 1 - i, color)
                frame.set(x, center + i, color)
            }
        }
    }

    /** Catmull-Rom through the band values; 12 bands spread over 25 columns. */
    private fun spline(values: FloatArray, width: Int): FloatArray {
        val out = FloatArray(width)
        val n = values.size
        if (n == 0) return out
        if (n == 1) {
            out.fill(values[0])
            return out
        }
        for (x in 0 until width) {
            val t = if (width == 1) 0f else x.toFloat() / (width - 1)
            val pos = t * (n - 1)
            val i = pos.toInt().coerceIn(0, n - 2)
            val f = pos - i
            val p0 = values[(i - 1).coerceAtLeast(0)]
            val p1 = values[i]
            val p2 = values[i + 1]
            val p3 = values[(i + 2).coerceAtMost(n - 1)]
            // Catmull-Rom keeps the stock "splineOrder" look without a matrix.
            out[x] = 0.5f * (
                (2f * p1) +
                    (-p0 + p2) * f +
                    (2f * p0 - 5f * p1 + 4f * p2 - p3) * f * f +
                    (-p0 + 3f * p1 - 3f * p2 + p3) * f * f * f
                )
        }
        return out
    }

    private fun drawFlame(frame: ScreenFrame) {
        val height = frame.height
        val bars = frame.width.coerceAtMost(levels.size)
        for (bar in 0 until bars) {
            val level = levels[bar * levels.size / bars].coerceIn(0f, 1f)
            if (level <= 0f) continue
            val random = Random(frameCounter * 31 + bar)
            val jitter = if (level > 0.05f) random.nextInt(-1, 2) else 0
            val h = (level * height).roundToInt().plus(jitter).coerceIn(1, height)
            for (i in 0 until h) {
                val y = height - h + i
                var fade = 0.30f + 0.70f * (i.toFloat() / h.coerceAtLeast(1))
                if (i == h - 1 && random.nextFloat() < 0.25f) fade *= 0.55f
                frame.set(bar, y, shade(fade))
            }
        }
    }

    /**
     * `pads`: a 6x4 grid of 3x3 pads with a 1px gap. The native code anchors
     * the grid to the panel edges: the lowest row sits flush on the bottom
     * edge (`y = height - cell - rowAbove * pitch`, i.e. y 13..15 on a
     * 16-tall panel; anchoring it from `offsetY` instead made the bottom row
     * overflow and clip), and `offsetX = 2` centers the 24px-wide grid in
     * the 25px row. The native code lights every cell with
     * `pow(level, brightnessGamma)`; `brightnessFading` is the exponent of
     * an optional diagonal fade inside each pad and is `0` for this preset,
     * so the pads are flat squares.
     */
    private fun drawPads(frame: ScreenFrame, style: SpectrogramStyle) {
        val cols = style.cols.coerceAtLeast(1)
        val rows = style.rows.coerceAtLeast(1)
        val cell = style.padSize.coerceAtLeast(1)
        val pitch = style.padSpacing.coerceAtLeast(cell)
        val gamma = style.brightnessGamma
        val offsetX = 2
        for (col in 0 until cols) {
            val x0 = offsetX + col * pitch
            for (row in 0 until rows) {
                val band = col * rows + row
                if (band >= levels.size) continue
                val level = levels[band].coerceIn(0f, 1f)
                if (level <= 0f) continue
                val lit = if (gamma == 1f) level else level.pow(gamma)
                // row 0 sits on the bottom edge of the panel: the native
                // code computes y = height - rowOffset - cell with
                // rowOffset growing by `pitch` per row, so low bands stay at
                // the bottom and the lowest row is y (height-cell)..height-1
                val y0 = frame.height - cell - row * pitch
                for (i in 0 until cell) {
                    for (j in 0 until cell) {
                        val distance = kotlin.math.hypot(
                            (cell - 1 - i).toDouble(),
                            (cell - 1 - j).toDouble(),
                        ).toFloat() / cell
                        val spatial = if (style.brightnessFading <= 0f) {
                            1f
                        } else {
                            (1f - distance.coerceIn(0f, 1f)).pow(style.brightnessFading)
                        }
                        val value = lit * spatial
                        if (value <= 0f) continue
                        frame.set(x0 + i, y0 + j, shade(value))
                    }
                }
            }
        }
    }

    /**
     * `bricksSmall`: 2x1 bricks separated by one black pixel. With a 25x16
     * panel that is an 8x8 grid, exactly as `BricksSpectrogram` computes
     * (`(size + 1) / (brick + 1)`), and the vertical fade uses
     * `pow(1 - row / rows, brightnessFading)`. `falloffSpeed` is `0` in the
     * stock preset, so there is no falling peak brick.
     */
    private fun drawBricks(frame: ScreenFrame, style: SpectrogramStyle) {
        val brickWidth = style.brickWidth.coerceAtLeast(1)
        val brickHeight = style.brickHeight.coerceAtLeast(1)
        val cols = (frame.width + 1) / (brickWidth + 1)
        val rows = (frame.height + 1) / (brickHeight + 1)
        if (cols <= 0 || rows <= 0) return
        val maxRows = (rows - 1).coerceAtLeast(0)
        for (col in 0 until cols) {
            val level = levels[col * levels.size / cols].coerceIn(0f, 1f)
            val h = (level * maxRows).roundToInt().coerceIn(0, maxRows)
            if (h <= 0) continue
            val x = col * (brickWidth + 1)
            for (row in 0 until h) {
                val color = shade(fadeExponent(row, h, style.brightnessFading))
                val y = frame.height - 1 - row * (brickHeight + 1) - (brickHeight - 1)
                frame.fillRect(x, y, brickWidth, brickHeight, color)
            }
        }
    }

    private fun shade(factor: Float): Int = ColorUtil.scale(0xFFFFFF, factor.coerceIn(0f, 1f))

    companion object {
        const val DEFAULT_FFT_SIZE = 512
        const val MIN_SENSITIVITY = 0.25f
        const val MAX_SENSITIVITY = 8f

        /** Presets whose draw code lives in `libquasar_daemons.so`. */
        private val STOCK_KINDS = setOf(
            SpectrogramStyle.Kind.PADS,
            SpectrogramStyle.Kind.WAVE,
            SpectrogramStyle.Kind.BRICKS,
        )

        private const val MAX_BANDS = 32
        private const val FLOOR_DB = -60.0
        private const val GATE_RATIO = 0.0056f
        private const val MIN_MAGNITUDE = 1e-4f
        private const val CUTOFF = 0.02f

        /** Same boost as [RingSpectrumRenderer] so music fills the bands. */
        private const val BASE_GAIN = 6f

        /** A stock-looking preset stays on screen for 12 seconds. */
        private const val RANDOM_INTERVAL_NS = 12_000_000_000L
    }
}
