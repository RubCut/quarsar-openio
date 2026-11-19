package quasar.rubcut.openio.domain.render

import quasar.rubcut.openio.domain.model.RingFrame
import quasar.rubcut.openio.domain.model.ScreenFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Minimal visualiser: one thin waveform trace on a pure black background, no
 * grid, no centre line, no labels.
 *
 * A real oscilloscope shows roughly one screen of the signal at a chosen time
 * base. On a 25-column panel a 512-sample window (32 ms at 16 kHz) packs too
 * many cycles into too few columns, which turns the trace into solid blocks;
 * [DEFAULT_WINDOW] is ~4 ms, so the curve moves smoothly from column to
 * column. Every pixel of the trace uses the same colour and opacity — there is
 * no dimmer "connector" shade — so the line reads as a single thin phosphor
 * stroke on the LED matrix.
 *
 * There is deliberately no zero-crossing trigger: aligning every frame to a
 * rising edge made periodic material look like one frozen picture. The trace
 * now follows the live stream frame by frame.
 *
 * With no signal the screen shows a straight horizontal line through the
 * centre instead of going completely black.
 */
class OscilloscopeRenderer(
    private val background: Int = 0x000000,
    private val waveColor: Int = 0xFFFFFF,
) {
    /** Last full-amplitude trace, kept so silence can fade it out smoothly. */
    private var lastValues: FloatArray? = null
    private var lastDrawMs = 0L

    /** Fade-out time after the signal disappears; 0 restores the instant line. */
    @Volatile
    var smoothMs: Int = 0
        private set

    /** True while the trace is still visibly fading after the last audio. */
    @Volatile
    var isDecaying: Boolean = false
        private set

    fun setSmoothMs(value: Int) {
        smoothMs = value.coerceIn(0, MAX_SMOOTH_MS)
        if (smoothMs == 0) reset()
    }

    /** Drops the retained trace, e.g. when the visualiser is re-entered. */
    fun reset() {
        lastValues = null
        isDecaying = false
    }

    fun render(
        samples: ShortArray,
        windowSamples: Int = DEFAULT_WINDOW,
        sensitivity: Float = 1f,
    ): ScreenFrame {
        val frame = ScreenFrame()
        frame.clear(background)
        val width = frame.width
        val height = frame.height
        val centerY = height / 2
        if (samples.isEmpty()) {
            drawStill(frame, centerY)
            return frame
        }

        val gain = sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        val effective = if (windowSamples in 1..samples.size) {
            samples.copyOfRange(samples.size - windowSamples, samples.size)
        } else {
            samples
        }
        val n = effective.size
        val amplitude = (height / 2 - 1).coerceAtLeast(1)

        var rawPeak = 0
        for (v in effective) {
            val magnitude = abs(v.toInt())
            if (magnitude > rawPeak) rawPeak = magnitude
        }
        // Silence (or the loopback's digital dither) fades the previous trace
        // instead of snapping to a straight line.
        if (rawPeak < SILENCE_PEAK) {
            drawStill(frame, centerY)
            return frame
        }

        // One point per column. Taking a short average instead of a single
        // sample suppresses ADC noise without smearing the waveform shape.
        val points = FloatArray(width)
        for (x in 0 until width) {
            val from = x * n / width
            val to = max(from + 1, (x + 1) * n / width).coerceAtMost(n)
            var sum = 0L
            for (i in from until to) sum += effective[i]
            points[x] = sum.toFloat() / (to - from)
        }

        var pointPeak = 0f
        for (p in points) {
            val magnitude = abs(p)
            if (magnitude > pointPeak) pointPeak = magnitude
        }
        // Normalise the window to a fixed target so quiet material still fills
        // the display, then apply the user sensitivity on top.
        val autoGain = (AGC_TARGET_PEAK / pointPeak.coerceAtLeast(1f)).coerceIn(1f, MAX_AUTO_GAIN)
        val effectiveGain = gain * autoGain

        val values = FloatArray(width)
        for (x in 0 until width) {
            // Preserve the vertical scale: the trace shrinks toward the centre
            // during the fade instead of being re-normalised every frame.
            values[x] = (points[x] * effectiveGain).coerceIn(-32768f, 32767f)
        }
        drawValues(frame, values, centerY, amplitude)
        lastValues = values
        lastDrawMs = nowMs()
        isDecaying = false
        return frame
    }

    private fun drawStill(frame: ScreenFrame, centerY: Int) {
        val base = lastValues
        val fade = smoothMs
        if (base == null || fade <= 0) {
            lastValues = null
            isDecaying = false
            drawFlatLine(frame, centerY)
            return
        }
        val elapsed = nowMs() - lastDrawMs
        val factor = 1f - elapsed.toFloat() / fade
        if (factor <= 0.01f) {
            lastValues = null
            isDecaying = false
            drawFlatLine(frame, centerY)
            return
        }
        val amplitude = (frame.height / 2 - 1).coerceAtLeast(1)
        val values = FloatArray(base.size) { base[it] * factor }
        drawValues(frame, values, centerY, amplitude)
        isDecaying = true
    }

    private fun drawValues(frame: ScreenFrame, values: FloatArray, centerY: Int, amplitude: Int) {
        val height = frame.height
        var prevY = -1
        for (x in values.indices) {
            val value = values[x].coerceIn(-32768f, 32767f)
            val y = (centerY - (value * amplitude / 32768.0)).roundToInt().coerceIn(0, height - 1)
            // One-pixel trace: every pixel of the curve has exactly the same
            // colour, including the vertical run that joins two columns.
            // Shading the joins dimmer made the line look like it had two
            // different opacities, which is not how a real phosphor trace
            // behaves on this panel.
            val from = if (prevY < 0) y else minOf(prevY, y)
            val to = if (prevY < 0) y else maxOf(prevY, y)
            for (lineY in from..to) {
                frame.set(x, lineY, waveColor)
            }
            prevY = y
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun drawFlatLine(frame: ScreenFrame, y: Int) {
        for (x in 0 until frame.width) frame.set(x, y, waveColor)
    }

    fun renderRing(samples: ShortArray, color: Int = 0xFFFFFF, sensitivity: Float = 1f): RingFrame {
        val ring = RingFrame()
        if (samples.isEmpty()) return ring
        var sum = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / samples.size) / 32768.0 *
            sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY) * RING_GAIN
        return renderRingRms(rms, ring.size, color)
    }

    fun renderRingRms(rms: Double, size: Int = RingFrame.DEFAULT_SIZE, color: Int = 0xFFFFFF): RingFrame {
        val ring = RingFrame(size)
        val level = (rms.coerceIn(0.0, 1.0) * size).roundToInt().coerceIn(0, size)
        if (level == 0) return ring
        for (i in 0 until level) {
            val fade = 1f - (i.toFloat() / size) * 0.55f
            ring.set(i, ColorUtil.scale(color, fade))
        }
        return ring
    }

    companion object {
        const val MIN_SENSITIVITY = 0.25f
        const val MAX_SENSITIVITY = 8f

        /** ~4 ms at 16 kHz: about four cycles of a 1 kHz tone across the panel. */
        const val DEFAULT_WINDOW = 64

        /** Peaks below this are treated as silence and draw a flat line. */
        private const val SILENCE_PEAK = 160f

        /** Peak the automatic gain stage aims for, ~80 % of full scale. */
        private const val AGC_TARGET_PEAK = 26_000f

        /** Maximum extra gain for quiet streams. */
        private const val MAX_AUTO_GAIN = 96f

        /** Longest configurable fade-out after the signal disappears. */
        const val MAX_SMOOTH_MS = 5_000

        /** The ring VU is driven by RMS, which is much lower than the peak. */
        private const val RING_GAIN = 8f
    }
}