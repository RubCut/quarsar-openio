package quasar.rubcut.openio.domain.audio

import kotlin.math.min
import kotlin.math.roundToInt

/** Small, dependency-free PCM helpers used by the audio bridge and tests. */
object Pcm {
    /** Linear resampling of mono 16-bit PCM. */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (input.isEmpty() || fromRate <= 0 || toRate <= 0) return ShortArray(0)
        if (fromRate == toRate) return input.copyOf()
        val outputLength = (input.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outputLength)
        val last = input.size - 1
        for (i in 0 until outputLength) {
            val position = i.toDouble() * fromRate / toRate
            val i0 = position.toInt().coerceIn(0, last)
            val i1 = min(i0 + 1, last)
            val fraction = position - i0
            val value = input[i0] + (input[i1] - input[i0]) * fraction
            out[i] = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** Duplicate mono samples into interleaved stereo. */
    fun toStereo(mono: ShortArray): ShortArray {
        val out = ShortArray(mono.size * 2)
        var o = 0
        for (s in mono) {
            out[o++] = s
            out[o++] = s
        }
        return out
    }

    fun toLittleEndian(samples: ShortArray, length: Int = samples.size): ByteArray {
        val n = min(length, samples.size)
        val out = ByteArray(n * 2)
        var o = 0
        for (i in 0 until n) {
            val v = samples[i].toInt()
            out[o++] = (v and 0xFF).toByte()
            out[o++] = ((v ushr 8) and 0xFF).toByte()
        }
        return out
    }

    fun sineTone(frequencyHz: Double, sampleRate: Int, durationMs: Int, amplitude: Double = 0.25): ShortArray {
        val count = (sampleRate.toLong() * durationMs / 1000L).toInt()
        val out = ShortArray(count)
        val peak = (Short.MAX_VALUE * amplitude).roundToInt()
        for (i in 0 until count) {
            val angle = 2.0 * Math.PI * frequencyHz * i / sampleRate
            out[i] = (kotlin.math.sin(angle) * peak).roundToInt().toShort()
        }
        return out
    }
}