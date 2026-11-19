package quasar.rubcut.openio.emulator

import quasar.rubcut.openio.domain.QuasarEngine
import quasar.rubcut.openio.domain.model.AudioChunk
import quasar.rubcut.openio.domain.model.QuasarMode
import quasar.rubcut.openio.domain.model.RingFrame
import quasar.rubcut.openio.domain.model.ScreenFrame
import quasar.rubcut.openio.domain.port.RingOutput
import quasar.rubcut.openio.domain.port.ScreenOutput
import quasar.rubcut.openio.domain.render.GifDecoder
import quasar.rubcut.openio.domain.render.GifAnimation
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Runs the real [QuasarEngine] on the desktop: fake screen/ring outputs and a
 * synthetic audio source replace the station hardware. Alice / voice features
 * are not used here (the assistant is disabled on this unit), so nothing in
 * the emulator listens, reacts or depends on them.
 */
class EngineHost {

    val screen = ScreenFrame()
    val ring = RingFrame()
    private val lock = Any()

    val engine = QuasarEngine(ScreenSink(), RingSink())

    private val scheduler = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "emu-scheduler").apply { isDaemon = true }
    }
    private val audioExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "emu-audio").apply { isDaemon = true }
    }

    val audio = ToneSource()

    fun start() {
        engine.setScopeFps(30)
        scheduler.scheduleAtFixedRate(
            { runCatching { engine.tick(LocalTime.now()) } },
            0, 250, TimeUnit.MILLISECONDS,
        )
        scheduler.scheduleAtFixedRate(
            { runCatching { engine.frameTick() } },
            0, 10, TimeUnit.MILLISECONDS,
        )
        audioExecutor.scheduleAtFixedRate(
            {
                runCatching {
                    if (audio.enabled) {
                        engine.onAudioChunk(audio.next(CHUNK_SAMPLES))
                    }
                }
            },
            0, CHUNK_MS, TimeUnit.MILLISECONDS,
        )
    }

    fun setMode(mode: QuasarMode) = engine.setMode(mode)

    fun loadIdleAnimation(file: File): Boolean {
        val animation = runCatching { GifDecoder.decode(file.readBytes()) }.getOrNull() ?: return false
        engine.setIdleAnimation(animation)
        return true
    }

    fun copyScreen(): ScreenFrame = synchronized(lock) { screen.copy() }

    fun copyRing(): RingFrame = synchronized(lock) { ring.copy() }

    private inner class ScreenSink : ScreenOutput {
        override fun drawScreen(frame: ScreenFrame): Boolean {
            synchronized(lock) { frame.pixels.copyInto(screen.pixels) }
            return true
        }
    }

    private inner class RingSink : RingOutput {
        override fun drawRing(frame: RingFrame): Boolean {
            synchronized(lock) { frame.pixels.copyInto(ring.pixels) }
            return true
        }
    }

    companion object {
        private const val CHUNK_MS = 20L
        private const val CHUNK_SAMPLES = 320
        private const val SAMPLE_RATE = 16_000
    }
}

/**
 * Synthetic 16 kHz source for the visualisers: a pure tone, white noise or a
 * simple music imitation (kick + bass + hats + chord pad) so the bands dance
 * like they do on the station.
 */
class ToneSource(private val sampleRate: Int = 16_000) {

    enum class Kind(val title: String) {
        SILENCE("Тишина"),
        TONE_440("Тон 440 Гц"),
        TONE_1000("Тон 1000 Гц"),
        NOISE("Шум"),
        MUSIC("Музыка (симуляция)"),
    }

    @Volatile
    var kind: Kind = Kind.SILENCE

    @Volatile
    var level: Double = 0.7

    val enabled: Boolean get() = kind != Kind.SILENCE

    private var phase = 0.0
    private var time = 0.0
    private val rng = Random(2026)

    fun next(numSamples: Int): AudioChunk {
        val samples = ShortArray(numSamples)
        when (kind) {
            Kind.SILENCE -> Unit
            Kind.TONE_440 -> fillSine(samples, 440.0)
            Kind.TONE_1000 -> fillSine(samples, 1000.0)
            Kind.NOISE -> {
                for (i in samples.indices) {
                    samples[i] = ((rng.nextDouble() * 2.0 - 1.0) * level * 0.5 * 32767.0).toInt().toShort()
                }
            }
            Kind.MUSIC -> fillMusic(samples)
        }
        time += numSamples.toDouble() / sampleRate
        return AudioChunk(samples, sampleRate, System.currentTimeMillis())
    }

    private fun fillSine(samples: ShortArray, frequency: Double) {
        val step = 2.0 * PI * frequency / sampleRate
        for (i in samples.indices) {
            samples[i] = (sin(phase) * level * 32767.0).toInt().toShort()
            phase += step
            if (phase > 2.0 * PI) phase -= 2.0 * PI
        }
    }

    /** Kick every 0.5 s, alternating bass notes, 8 Hz hats, slow chord pad. */
    private fun fillMusic(samples: ShortArray) {
        val bassNotes = doubleArrayOf(110.0, 146.83, 110.0, 164.81)
        val step = 1.0 / sampleRate
        for (i in samples.indices) {
            val t = time + i * step
            // Kick: decaying 60 Hz sine on every beat (0.5 s).
            val beat = t % 0.5
            val kickEnv = exp(-beat * 18.0)
            val kick = sin(2.0 * PI * (58.0 + 60.0 * kickEnv) * beat) * kickEnv * 0.9

            // Bass line: one note per bar (2 s), gentle attack.
            val bar = ((t / 2.0).toInt()).mod(bassNotes.size)
            val note = bassNotes[bar]
            val bassEnv = min(1.0, (t % 2.0) * 4.0) * (0.65 + 0.35 * sin(2.0 * PI * t / 2.0))
            val bass = sin(2.0 * PI * note * t) * bassEnv * 0.45

            // Chord pad: triad with slow tremolo.
            val chord = (
                sin(2.0 * PI * 220.0 * t) +
                    sin(2.0 * PI * 277.18 * t) * 0.8 +
                    sin(2.0 * PI * 329.63 * t) * 0.7
                ) * (0.30 + 0.18 * sin(2.0 * PI * t / 4.0)) * 0.35

            // Hats: short noise bursts on the eighths.
            val hatPhase = t % 0.25
            val hatEnv = if (hatPhase < 0.03) exp(-hatPhase * 120.0) else 0.0
            val hat = (rng.nextDouble() * 2.0 - 1.0) * hatEnv * 0.5

            val value = (kick + bass + chord + hat) * level
            samples[i] = (value.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
        }
    }
}

/**
 * Live view of the real station through the HTTP API (with
 * `adb forward tcp:18080 tcp:8080` already in place). Polls the raw frame and
 * ring endpoints; voice/Alice state is not touched at all.
 */
class LiveSource(private val baseUrl: String) {

    @Volatile
    var screen: ScreenFrame? = null

    @Volatile
    var ring: RingFrame? = null

    @Volatile
    var status: String = ""

    @Volatile
    var online: Boolean = false

    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ pollLoop() }, "emu-live").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        online = false
    }

    private fun pollLoop() {
        while (running) {
            val started = System.nanoTime()
            try {
                val frame = ScreenFrame.fromRgb888(httpBytes("$baseUrl/api/frame.rgb"))
                val ringFrame = RingFrame.fromRgb888(httpBytes("$baseUrl/api/ring.rgb"))
                synchronized(this) {
                    screen = frame
                    ring = ringFrame
                }
                online = true
            } catch (_: Exception) {
                online = false
                Thread.sleep(800)
                continue
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            Thread.sleep(maxOf(0, 60 - elapsedMs))
        }
    }

    fun pollStatusOnce() {
        try {
            val text = httpText("$baseUrl/api/status")
            val mode = Regex("\"mode\":\"([a-z]+)\"").find(text)?.groupValues?.get(1) ?: "?"
            val fps = Regex("\"scope_fps_actual\":(\\d+)").find(text)?.groupValues?.get(1) ?: "?"
            status = "станция · режим $mode · ${fps} fps"
            online = true
        } catch (e: Exception) {
            online = false
            status = "станция недоступна: ${e.message?.take(80) ?: ""}"
        }
    }

    private fun httpBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.useCaches = false
        return connection.inputStream.use { it.readBytes() }
    }

    private fun httpText(url: String): String = httpBytes(url).toString(Charsets.UTF_8)
}
