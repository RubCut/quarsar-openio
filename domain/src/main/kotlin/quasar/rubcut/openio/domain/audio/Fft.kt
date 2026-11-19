package quasar.rubcut.openio.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Dependency-free radix-2 FFT used by the ring spectrum visualiser.
 *
 * The panel needs at most a few hundred bins at ~20 Hz, so a compact iterative
 * Cooley-Tukey is more than enough and keeps the domain module free of Android
 * or JNI code.
 */
object Fft {
    /** Returns the magnitude spectrum of the first [fftSize] samples (Hann windowed). */
    fun magnitude(samples: ShortArray, fftSize: Int = 512): FloatArray {
        val size = Integer.highestOneBit(fftSize.coerceAtLeast(16))
        val real = DoubleArray(size)
        val imag = DoubleArray(size)
        val count = samples.size.coerceAtMost(size)
        for (i in 0 until count) {
            val window = 0.5 * (1.0 - cos(2.0 * PI * i / (count - 1).coerceAtLeast(1)))
            real[i] = samples[i] / 32768.0 * window
        }
        transform(real, imag)
        val out = FloatArray(size / 2)
        for (i in out.indices) {
            out[i] = hypot(real[i], imag[i]).toFloat()
        }
        return out
    }

    private fun transform(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wReal = cos(angle)
            val wImag = kotlin.math.sin(angle)
            var start = 0
            while (start < n) {
                var curReal = 1.0
                var curImag = 0.0
                for (k in 0 until len / 2) {
                    val i0 = start + k
                    val i1 = i0 + len / 2
                    val tReal = real[i1] * curReal - imag[i1] * curImag
                    val tImag = real[i1] * curImag + imag[i1] * curReal
                    real[i1] = real[i0] - tReal
                    imag[i1] = imag[i0] - tImag
                    real[i0] += tReal
                    imag[i0] += tImag
                    val nextReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                }
                start += len
            }
            len = len shl 1
        }
    }

    /** Converts a magnitude to a 0..1 level using a decibel scale. */
    fun level(magnitude: Float, floorDb: Double = -60.0): Float {
        if (magnitude <= 0f) return 0f
        val db = 20.0 * log10(magnitude.coerceAtLeast(1e-9f))
        return ((db - floorDb) / -floorDb).coerceIn(0.0, 1.0).toFloat()
    }

    /** Inverse helper kept for tests: RMS of a block in 0..1. */
    fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) {
            val value = sample / 32768.0
            sum += value * value
        }
        return sqrt(sum / samples.size).toFloat()
    }

    @Suppress("unused")
    private fun dbToGain(db: Double): Double = 10.0.pow(db / 20.0)
}