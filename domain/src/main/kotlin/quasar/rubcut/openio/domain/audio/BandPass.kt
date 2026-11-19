package quasar.rubcut.openio.domain.audio

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Frequency window the oscilloscope listens to. On a 25-column panel one
 * screen can only show a few cycles, so "the range" is both a band-pass filter
 * and a time base: low bands get a longer sample window (slower sweep), high
 * bands a shorter one.
 */
enum class ScopeBand(
    val id: String,
    val title: String,
    val lowHz: Double,
    val highHz: Double,
) {
    FULL("full", "Весь диапазон", 20.0, 8000.0),
    BASS("bass", "Бас 20–200 Гц", 20.0, 200.0),
    LOW_MID("low_mid", "Нижняя середина 200–800 Гц", 200.0, 800.0),
    MID("mid", "Середина 0.8–3 кГц", 800.0, 3000.0),
    HIGH("high", "Верх 3–8 кГц", 3000.0, 8000.0);

    val centerHz: Double get() = (lowHz + highHz) / 2.0

    /** Samples per screen: ~3 cycles of the band centre, clamped to the panel. */
    fun windowSamples(sampleRate: Int): Int =
        (sampleRate / centerHz * 3.0).toInt().coerceIn(24, 512)

    companion object {
        fun fromId(id: String?): ScopeBand =
            entries.firstOrNull { it.id.equals(id, true) || it.name.equals(id, true) } ?: FULL
    }
}

/**
 * RBJ biquad band-pass. A short window is filtered from scratch on every
 * repaint, so a transient at the start of the buffer is acceptable — the
 * displayed samples come from the tail.
 */
class BandPass(
    private val sampleRate: Int,
    private val band: ScopeBand,
) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var z1 = 0.0
    private var z2 = 0.0

    init {
        val low = band.lowHz.coerceIn(10.0, sampleRate / 2.0 - 100.0)
        val high = band.highHz.coerceIn(low + 20.0, sampleRate / 2.0 - 10.0)
        val center = sqrt(low * high)
        val q = (center / (high - low)).coerceIn(0.4, 12.0)
        val w0 = 2.0 * Math.PI * center / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = alpha / a0
        b1 = 0.0
        b2 = -alpha / a0
        a1 = -2.0 * cos(w0) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(input: ShortArray): ShortArray {
        if (band == ScopeBand.FULL) return input
        val out = ShortArray(input.size)
        for (i in input.indices) {
            val x = input[i].toDouble()
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            out[i] = y.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }
}