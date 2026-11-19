package quasar.rubcut.openio.domain.audio

import kotlin.math.min
import kotlin.math.sqrt

/** Lock-protected ring buffer holding the most recent PCM samples. */
class PcmRingBuffer(private val capacity: Int = 8192) {
    private val buffer = ShortArray(capacity)
    private var cursor = 0
    private var count = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun write(samples: ShortArray, length: Int = samples.size) {
        val n = min(length, samples.size)
        if (n <= 0) return
        if (n >= capacity) {
            samples.copyInto(buffer, 0, n - capacity, n)
            cursor = 0
            count = capacity
            return
        }
        val first = min(n, capacity - cursor)
        samples.copyInto(buffer, cursor, 0, first)
        if (n > first) {
            samples.copyInto(buffer, 0, first, n)
        }
        cursor = (cursor + n) % capacity
        count = min(capacity, count + n)
    }

    @Synchronized
    fun snapshot(maxSamples: Int = capacity): ShortArray {
        val n = min(maxSamples, count)
        if (n <= 0) return ShortArray(0)
        val out = ShortArray(n)
        var start = cursor - n
        if (start < 0) start += capacity
        val first = min(n, capacity - start)
        buffer.copyInto(out, 0, start, start + first)
        if (n > first) {
            buffer.copyInto(out, first, 0, n - first)
        }
        return out
    }

    /** Root-mean-square of the newest samples in the range 0.0..1.0. */
    @Synchronized
    fun rms(maxSamples: Int = 2048): Double {
        val n = min(maxSamples, count)
        if (n <= 0) return 0.0
        var sum = 0.0
        var index = cursor - n
        if (index < 0) index += capacity
        for (i in 0 until n) {
            val s = buffer[index].toDouble()
            sum += s * s
            index++
            if (index == capacity) index = 0
        }
        return sqrt(sum / n) / 32768.0
    }

    @Synchronized
    fun size(): Int = count

    @Synchronized
    fun clear() {
        cursor = 0
        count = 0
    }
}