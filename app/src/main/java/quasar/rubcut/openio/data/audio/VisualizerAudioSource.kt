package quasar.rubcut.openio.data.audio

import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import quasar.rubcut.openio.domain.model.AudioChunk
import quasar.rubcut.openio.domain.port.AudioCapture

/**
 * Playback capture through the platform Visualizer effect.
 *
 * The firmware ships `/vendor/lib/soundfx/libvisualizer.so`, so Android can
 * tap the final audio mix inside AudioFlinger. That is exactly the signal a
 * visualiser wants: music, video and Alice are all included, while the
 * microphone stays free and nothing opens an ALSA PCM directly.
 *
 * This replaces both earlier backends. `tinycap2` on `pcmC0D0c` bypassed the
 * vendor HAL and shared the TDM-A clock with playback: any hard stop left the
 * clock disabled and wedged playback DMA until a reboot. `AudioRecord` had
 * the same non-issue-free policy path and additionally competed with Alice
 * for the microphone.
 *
 * The effect is opened once per visualiser session, with waveform capture at
 * the mixer rate and decimation to 16 kHz for the render engine.
 */
class VisualizerAudioSource(
    private val onChunk: (AudioChunk) -> Unit,
    private val targetRate: Int = 16_000,
) : AudioCapture {
    private val lifecycle = Any()

    @Volatile
    private var running = false

    @Volatile
    private var lastChunkAt = 0L

    private var effect: Visualizer? = null

    private var callbacks = 0

    @Volatile
    var captureRate = targetRate
        private set

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean
        get() = running && effect != null

    override fun start(): Boolean = synchronized(lifecycle) {
        if (isRunning) return true
        shutdownLocked()
        lastError = null

        val created = try {
            // Session 0 attaches to the global output mix on this platform.
            Visualizer(0)
        } catch (e: Exception) {
            lastError = "Visualizer: ${e.message}"
            Log.e(TAG, lastError ?: "visualizer create failed", e)
            return false
        }
        val maxSize = Visualizer.getCaptureSizeRange().last()
        val requested = Integer.highestOneBit(minOf(DEFAULT_CAPTURE_SIZE, maxSize))
        try {
            created.captureSize = requested
        } catch (e: Exception) {
            lastError = "captureSize: ${e.message}"
            runCatching { created.release() }
            return false
        }
        captureRate = normalizeRate(created.samplingRate).takeIf { it > 0 } ?: DEFAULT_MIX_RATE

        try {
            val maxRate = Visualizer.getMaxCaptureRate()
            Log.i(
                TAG,
                "mix=${captureRate}Hz maxCaptureRate=$maxRate mHz capture=$requested",
            )
            created.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (!running || waveform == null || waveform.isEmpty()) return
                        if (callbacks++ % 50 == 0) {
                            Log.d(TAG, "waveform #$callbacks size=${waveform.size} rate=$samplingRate")
                        }
                        emit(waveform, if (samplingRate > 0) normalizeRate(samplingRate) else captureRate)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) = Unit
                },
                maxRate,
                true,
                false,
            )
            running = true
            effect = created
            created.enabled = true
        } catch (e: Exception) {
            running = false
            effect = null
            lastError = "start: ${e.message}"
            runCatching { created.release() }
            Log.e(TAG, lastError ?: "visualizer start failed", e)
            return false
        }

        lastChunkAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "Visualizer started: mix=${captureRate}Hz capture=$requested -> ${targetRate}Hz")
        return true
    }

    /** Visualizer reports rates in milliHertz (44100000 = 44.1 kHz). */
    private fun normalizeRate(raw: Int): Int = if (raw > 1000) raw / 1000 else raw

    /** Called on the AudioFlinger effect thread: keep it allocation-light. */
    private fun emit(waveform: ByteArray, samplingRate: Int) {
        val rate = samplingRate.coerceAtLeast(1)
        val step = rate.toDouble() / targetRate
        val count = maxOf(1, (waveform.size / step).toInt())
        val out = ShortArray(count)
        for (i in 0 until count) {
            val index = (i * step).toInt().coerceIn(0, waveform.lastIndex)
            // Visualizer waveforms are unsigned 8-bit PCM centred on 128.
            val value = ((waveform[index].toInt() and 0xFF) - 128) shl 8
            out[i] = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val now = SystemClock.elapsedRealtime()
        lastChunkAt = now
        onChunk(AudioChunk(samples = out, sampleRate = targetRate, timestampMs = now))
    }

    override fun stop() = synchronized(lifecycle) { shutdownLocked() }

    private fun shutdownLocked() {
        running = false
        val active = effect
        effect = null
        if (active != null) {
            runCatching { active.enabled = false }
            runCatching { active.release() }
        }
    }

    companion object {
        private const val TAG = "QuasarVisualizer"

        /** 1024 samples per callback at 48 kHz is ~21 ms, or ~47 updates/s. */
        private const val DEFAULT_CAPTURE_SIZE = 1024

        private const val DEFAULT_MIX_RATE = 48_000
    }
}