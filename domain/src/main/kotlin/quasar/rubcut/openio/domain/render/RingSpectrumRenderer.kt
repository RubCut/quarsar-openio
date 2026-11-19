package quasar.rubcut.openio.domain.render

import quasar.rubcut.openio.domain.audio.Fft
import quasar.rubcut.openio.domain.model.RingFrame
import kotlin.math.exp
import kotlin.math.ln

/**
 * Stock-style spectrum visualiser for the 24-LED ring.
 *
 * The ring is split into [bands] logarithmically spaced frequency bands, so the
 * low end (where most music energy lives) gets as many LEDs as the treble. Each
 * band shows the loudest bin inside its range on a decibel scale, with instant
 * attack and a short decay so the bars fall smoothly instead of flickering.
 *
 * LED 0 is the centre of the front edge and indexes grow counter-clockwise,
 * matching the rest of the ring renderers. Low frequencies start at the front
 * and climb counter-clockwise around the ring.
 */
class RingSpectrumRenderer(
    val bands: Int = RingFrame.DEFAULT_SIZE,
) {
    init {
        require(bands > 0) { "bands must be positive" }
    }

    private val levels = FloatArray(bands)

    /**
     * @param samples mono PCM in [-32768, 32767]
     * @param color base ring colour; each band keeps the hue and is dimmed by level
     * @param sensitivity multiplier applied to the raw magnitude before the dB scale
     */
    fun render(
        samples: ShortArray,
        color: Int = 0xFFFFFF,
        sensitivity: Float = 1f,
        fftSize: Int = DEFAULT_FFT_SIZE,
    ): RingFrame {
        val ring = RingFrame(bands)
        if (samples.isEmpty()) {
            decay()
            return ring
        }
        val spectrum = Fft.magnitude(samples, fftSize)
        val binCount = spectrum.size
        if (binCount < 4) {
            decay()
            return ring
        }
        val gain = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY) * BASE_GAIN
        val edges = edges(binCount)
        val peaks = FloatArray(bands)

        for (band in 0 until bands) {
            val from = edges[band]
            val to = edges[band + 1].coerceAtLeast(from + 1).coerceAtMost(binCount)
            var peak = 0f
            for (bin in from until to) {
                if (spectrum[bin] > peak) peak = spectrum[bin]
            }
            peaks[band] = peak
        }

        // 16-bit quantisation puts a broadband floor ~100 dB below the signal
        // peak. Bars that sit that far down are noise, not music, so they are
        // gated relative to the loudest band of the same frame.
        var framePeak = 0f
        for (peak in peaks) if (peak > framePeak) framePeak = peak
        val gate = framePeak * GATE_RATIO

        for (band in 0 until bands) {
            val peak = peaks[band]
            val target = if (peak < gate || peak < MIN_MAGNITUDE) 0f else Fft.level(peak * gain, FLOOR_DB)
            levels[band] = if (target >= levels[band]) target else levels[band] * DECAY
            if (levels[band] < CUTOFF) levels[band] = 0f
            ring.set(band, if (levels[band] == 0f) 0 else ColorUtil.scale(color, 0.10f + 0.90f * levels[band]))
        }
        return ring
    }

    /** Drops the smoothing state, e.g. when switching away from the visualiser. */
    fun reset() {
        levels.fill(0f)
    }

    /** Index of the band that contains [hz] for the given stream parameters. */
    fun bandForFrequency(hz: Double, sampleRate: Int, fftSize: Int = DEFAULT_FFT_SIZE): Int {
        if (hz <= 0.0 || sampleRate <= 0) return 0
        val size = Integer.highestOneBit(fftSize.coerceAtLeast(16))
        val binCount = size / 2
        if (binCount < 2) return 0
        val bin = (hz * size / sampleRate).toInt().coerceIn(1, binCount - 1)
        val edges = edges(binCount)
        for (band in 0 until bands) {
            if (bin >= edges[band] && bin < edges[band + 1]) return band
        }
        return bands - 1
    }

    /** Geometric band edges over the usable spectrum: [bands + 1] bin indexes. */
    private fun edges(binCount: Int): IntArray {
        val high = (binCount - 1).toDouble()
        val span = ln(high) - ln(1.0)
        val out = IntArray(bands + 1)
        for (i in 0..bands) {
            val ratio = i.toDouble() / bands
            out[i] = exp(span * ratio).toInt().coerceIn(1, binCount - 1)
        }
        // Make sure every band owns at least one bin.
        for (i in 1..bands) {
            if (out[i] <= out[i - 1]) out[i] = (out[i - 1] + 1).coerceAtMost(binCount - 1)
        }
        out[bands] = binCount
        return out
    }

    private fun decay() {
        for (band in levels.indices) {
            levels[band] = if (levels[band] * DECAY < CUTOFF) 0f else levels[band] * DECAY
        }
    }

    companion object {
        const val DEFAULT_FFT_SIZE = 512
        const val MIN_SENSITIVITY = 0.25f
        const val MAX_SENSITIVITY = 8f

        /** Extra gain so normal speech/music still fills several LEDs. */
        private const val BASE_GAIN = 6f

        /** Bars fall by this factor per repaint (~20 Hz). */
        private const val DECAY = 0.72f

        /** Levels below 2 % are rendered as off. */
        private const val CUTOFF = 0.02f

        /** Bands more than ~45 dB below the frame peak are noise. */
        private const val GATE_RATIO = 0.0056f

        /** Absolute floor: below this a band is silence even in a quiet frame. */
        private const val MIN_MAGNITUDE = 1e-4f

        private const val FLOOR_DB = -60.0
    }
}