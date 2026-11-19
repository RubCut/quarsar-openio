package quasar.rubcut.openio.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import quasar.rubcut.openio.domain.audio.Pcm
import quasar.rubcut.openio.domain.model.AudioChunk
import quasar.rubcut.openio.domain.port.AudioSink

/**
 * Direct 48 kHz stereo output. This is the production fallback while io.sdk is
 * frozen: the GStreamer appsrc inside libquasar_daemons.so is process-local and
 * has no external PCM input protocol, so a normal AudioTrack is the only
 * reliable way to play audio from our process.
 */
class AudioTrackSink(
    private val sampleRate: Int = 48_000,
) : AudioSink {
    override val name: String = "audiotrack-48k-stereo"

    private val lock = Any()
    private var track: AudioTrack? = null

    @Volatile
    private var volume: Float = 0.8f

    @Volatile
    var lastError: String? = null
        private set

    fun isStarted(): Boolean = synchronized(lock) { track != null }

    override fun start(): Boolean {
        synchronized(lock) {
            if (track != null) return true
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                lastError = "getMinBufferSize failed: $minBuffer"
                Log.e(TAG, lastError ?: "audio output error")
                return false
            }
            val bufferBytes = maxOf(minBuffer * 2, sampleRate / 5 * 2 * 2)
            val created = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            } catch (e: Exception) {
                lastError = "AudioTrack build failed: ${e.message}"
                Log.e(TAG, lastError ?: "audio output error", e)
                return false
            }
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                lastError = "AudioTrack not initialized"
                created.release()
                return false
            }
            created.setVolume(volume)
            try {
                created.play()
            } catch (e: Exception) {
                lastError = "AudioTrack.play failed: ${e.message}"
                Log.e(TAG, lastError ?: "audio output error", e)
                created.release()
                return false
            }
            track = created
            Log.i(TAG, "AudioTrack started: ${sampleRate}Hz stereo buffer=$bufferBytes")
            return true
        }
    }

    /** Blocks until the samples are queued. Call from a worker, not an audio input thread. */
    override fun enqueue(chunk: AudioChunk) {
        val active = synchronized(lock) { track } ?: return
        val mono48 = if (chunk.sampleRate == sampleRate) {
            chunk.samples
        } else {
            Pcm.resample(chunk.samples, chunk.sampleRate, sampleRate)
        }
        writeStereo(active, Pcm.toStereo(mono48))
    }

    fun playMono(samples: ShortArray, rate: Int) {
        val active = synchronized(lock) { track } ?: return
        val mono48 = if (rate == sampleRate) samples else Pcm.resample(samples, rate, sampleRate)
        writeStereo(active, Pcm.toStereo(mono48))
    }

    fun playTone(frequencyHz: Double, durationMs: Int, amplitude: Double = 0.25): Boolean {
        val ms = durationMs.coerceIn(20, 5_000)
        // Open the output only for the duration of the tone. Keeping an
        // AudioTrack open for the whole service lifetime blocks the vendor
        // HAL while the loopback capture is running and can wedge playback
        // for every other app.
        if (!start()) return false
        return try {
            val mono = Pcm.sineTone(frequencyHz, sampleRate, ms, amplitude)
            playMono(mono, sampleRate)
            // Let the hardware drain before releasing the track.
            Thread.sleep(ms.toLong() + 150L)
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            stop()
        }
    }

    private fun writeStereo(active: AudioTrack, stereo: ShortArray) {
        var offset = 0
        while (offset < stereo.size) {
            val written = try {
                active.write(stereo, offset, stereo.size - offset, AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                lastError = "AudioTrack.write failed: ${e.message}"
                Log.e(TAG, lastError ?: "audio output error", e)
                return
            }
            if (written <= 0) {
                lastError = "AudioTrack.write returned $written"
                Log.w(TAG, lastError ?: "audio write returned no data")
                return
            }
            offset += written
        }
    }

    fun setVolume(value: Float): Boolean {
        volume = value.coerceIn(0f, 1f)
        synchronized(lock) { track?.setVolume(volume) }
        return true
    }

    fun volume(): Float = volume

    override fun stop() {
        val active = synchronized(lock) {
            val current = track
            track = null
            current
        } ?: return
        runCatching { active.stop() }
        runCatching { active.release() }
        Log.i(TAG, "AudioTrack stopped")
    }

    companion object {
        private const val TAG = "QuasarAudioOut"

        fun streamType(): Int = AudioManager.STREAM_MUSIC
    }
}