package quasar.rubcut.openio.service

import quasar.rubcut.openio.domain.audio.PcmRingBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Microphone fan-out. AudioRecord produces mono 16 kHz PCM; the ring keeps
 * the last 30 seconds for snapshots and listeners receive live chunks for the
 * raw PCM socket.
 *
 * Listeners run on a dedicated worker, never on the capture thread: a slow
 * socket client must not stall AudioRecord.read(). When the worker falls far
 * behind, new chunks are dropped instead of growing an unbounded queue; both
 * the PCM stream and the visualiser already tolerate gaps.
 */
class MicHub(historySamples: Int = 16_000 * 30) {
    private val ring = PcmRingBuffer(historySamples)
    private val listeners = CopyOnWriteArrayList<(ShortArray) -> Unit>()
    private val queue = ArrayBlockingQueue<ShortArray>(MAX_PENDING)

    @Volatile
    private var running = true

    private val worker = Thread({
        while (running) {
            val samples = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            for (listener in listeners) {
                runCatching { listener(samples) }
            }
        }
    }, "quasar-mic-fanout").apply {
        isDaemon = true
        start()
    }

    fun onChunk(samples: ShortArray) {
        ring.write(samples)
        // Drop the newest chunk when the consumer lags; blocking here would
        // stop AudioRecord.read() and eventually the HAL record thread.
        queue.offer(samples)
    }

    fun snapshot(maxSamples: Int): ShortArray = ring.snapshot(maxSamples)

    fun rms(): Double = ring.rms(4096)

    fun size(): Int = ring.size()

    fun subscribe(listener: (ShortArray) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    fun listenerCount(): Int = listeners.size

    /** Stops the worker and drops queued chunks. */
    fun close() {
        running = false
        listeners.clear()
        queue.clear()
        worker.interrupt()
    }

    companion object {
        /** At 16 kHz a chunk is 64 ms, so 16 chunks ≈ 1 s of backlog. */
        private const val MAX_PENDING = 16
    }
}