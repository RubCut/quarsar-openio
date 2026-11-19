package quasar.rubcut.openio.domain

import quasar.rubcut.openio.domain.audio.BandPass
import quasar.rubcut.openio.domain.audio.PcmRingBuffer
import quasar.rubcut.openio.domain.audio.ScopeBand
import quasar.rubcut.openio.domain.led.LedPattern
import quasar.rubcut.openio.domain.model.AudioChunk
import quasar.rubcut.openio.domain.model.QuasarMode
import quasar.rubcut.openio.domain.model.RingFrame
import quasar.rubcut.openio.domain.model.ScreenFrame
import quasar.rubcut.openio.domain.port.RingOutput
import quasar.rubcut.openio.domain.port.ScreenOutput
import quasar.rubcut.openio.domain.render.AnimationRenderer
import quasar.rubcut.openio.domain.render.ClockRenderer
import quasar.rubcut.openio.domain.render.ClockSize
import quasar.rubcut.openio.domain.render.GifAnimation
import quasar.rubcut.openio.domain.render.GifDecoder
import quasar.rubcut.openio.domain.render.OscilloscopeRenderer
import quasar.rubcut.openio.domain.render.SpectrogramStyle
import quasar.rubcut.openio.domain.render.StockFrameSource
import quasar.rubcut.openio.domain.render.TestPatternRenderer
import java.time.LocalTime

/**
 * Pure render/audio orchestration. It has no Android dependencies, which keeps
 * the frame logic testable on the JVM.
 */
class QuasarEngine(
    private val screen: ScreenOutput,
    private val ring: RingOutput,
    private val clockRenderer: ClockRenderer = ClockRenderer(),
    private val scopeRenderer: OscilloscopeRenderer = OscilloscopeRenderer(),
    private val testRenderer: TestPatternRenderer = TestPatternRenderer(),
    @Suppress("UNUSED_PARAMETER") stockFrames: StockFrameSource? = null,
) {
    private val pcm = PcmRingBuffer(8192)
    private val renderLock = Any()

    @Volatile
    var mode: QuasarMode = QuasarMode.CLOCK
        private set

    /** Colour of the clock ring and the visualiser level ring. Colour is allowed here. */
    @Volatile
    var ringColor: Int = 0xFF66D9FF.toInt()
        private set

    /** Upper bound is the microphone chunk rate: 20 ms chunks => 50 fps. */
    @Volatile
    var scopeFps: Int = MAX_SCOPE_FPS
        private set

    @Volatile
    var scopeSensitivity: Float = 1f
        private set

    /** When false the 24-LED ring is cleared and stays dark. */
    @Volatile
    var ringEnabled: Boolean = true
        private set

    /**
     * When false the screen output is blanked (black frame) no matter what is
     * rendered: clock, visualiser, animations, overlays and custom frames are
     * all suppressed. The ring keeps running — it has its own [ringEnabled].
     */
    var screenEnabled: Boolean = true
        private set

    /** Stock clock glyph size. */
    @Volatile
    var clockSize: ClockSize = ClockSize.LARGE
        private set

    /** External ring frame that keeps priority over the generated one. */
    @Volatile
    private var ringOverride: RingFrame? = null

    @Volatile
    private var lastScopeMs = 0L

    /** Incremented for every captured block, so the scope only repaints fresh audio. */
    @Volatile
    private var pcmSeq = 0L

    @Volatile
    private var lastPcmMs = 0L

    /** Fade-out time of the waveform after audio stops. */
    @Volatile
    var scopeSmoothMs: Int = 1500
        private set

    private var renderedPcmSeq = -1L
    private var lastSilenceRenderMs = 0L
    private var fpsWindowStartMs = 0L
    private var fpsWindowFrames = 0

    /** Measured repaint rate of the visualiser over the last second. */
    @Volatile
    var scopeFpsActual: Int = 0
        private set

    private val animationRenderer = AnimationRenderer()

    /** The visualiser is a single waveform; stock spectrum presets are gone. */
    @Volatile
    var scopeStyle: SpectrogramStyle = SpectrogramStyle.WAVE_TRACE
        private set

    /** Kept in the status API for older clients; always false. */
    @Volatile
    var ringSpectrum: Boolean = false
        private set

    @Volatile
    var animationColor: Int = 0xFFFFFF
        private set

    @Volatile
    private var idleAnimation: GifAnimation? = null

    /** Physical LED that logical index 0 is drawn on ("front side"). */
    @Volatile
    var ringOrigin: Int = 12
        private set

    /** Frequency window of the oscilloscope. */
    @Volatile
    var scopeBand: ScopeBand = ScopeBand.FULL
        private set

    private var bandPass: BandPass? = null

    /** `*.led` pattern currently driving the ring (pack-provided). */
    @Volatile
    private var ringPattern: LedPattern? = null
    private var ringPatternLoop = false
    private var ringPatternStartNs = 0L

    @Volatile
    private var overlayAnimation: GifAnimation? = null
    private var overlayLoop = false
    private var overlayStartNs = 0L

    private var idleStartNs = 0L
    private var lastAnimationFrameIndex = -1
    private var lastAnimationKey = ""

    private var lastFrame = ScreenFrame()
    private var lastRing = RingFrame()
    private var testStep = 0

    init {
        scopeRenderer.setSmoothMs(scopeSmoothMs)
    }

    fun setMode(newMode: QuasarMode) {
        mode = newMode
        testStep = 0
        lastScopeMs = 0L
        ringOverride = null
        overlayAnimation = null
        idleStartNs = System.nanoTime()
        lastAnimationKey = ""
        if (newMode != QuasarMode.OSCILLOSCOPE) {
            // Start the next visualiser session from a clean trace.
            scopeRenderer.reset()
        }
        if (newMode == QuasarMode.CUSTOM) return
        if (newMode == QuasarMode.OFF) {
            synchronized(renderLock) {
                lastFrame = ScreenFrame()
                lastRing = RingFrame()
                screen.drawScreen(lastFrame.copy())
                ring.drawRing(lastRing.copy())
            }
            return
        }
        tick(LocalTime.now())
    }

    fun setClockSize(size: ClockSize) {
        clockSize = size
        if (mode == QuasarMode.CLOCK) tick(LocalTime.now())
    }

    /** Looping animation shown by [QuasarMode.ANIMATION]. */
    fun setIdleAnimation(animation: GifAnimation?) {
        idleAnimation = animation
        idleStartNs = System.nanoTime()
        lastAnimationKey = ""
        if (mode == QuasarMode.ANIMATION) frameTick()
    }

    fun setAnimationColor(rgb: Int) {
        animationColor = rgb and 0xFFFFFF
        lastAnimationKey = ""
        if (mode == QuasarMode.ANIMATION) frameTick()
    }

    /**
     * Plays a one-shot (or looping) animation on top of the current mode, for
     * example the stock volume or play/pause clips. The idle screen returns by
     * itself once a one-shot clip is finished.
     */
    fun playOverlayAnimation(animation: GifAnimation, loop: Boolean = false) {
        synchronized(renderLock) {
            overlayAnimation = animation
            overlayLoop = loop
            overlayStartNs = System.nanoTime()
            lastAnimationKey = ""
        }
    }

    fun stopOverlayAnimation() {
        synchronized(renderLock) {
            if (overlayAnimation != null) {
                overlayAnimation = null
                lastAnimationKey = ""
                renderIdleLocked()
            }
        }
    }

    val overlayVisible: Boolean get() = overlayAnimation != null

    val hasIdleAnimation: Boolean get() = idleAnimation != null

    fun setRingColor(rgb: Int) {
        ringColor = rgb and 0xFFFFFF
        if (mode == QuasarMode.CLOCK || mode == QuasarMode.OSCILLOSCOPE) {
            lastScopeMs = 0L
            if (mode == QuasarMode.CLOCK) {
                tick(LocalTime.now())
            } else {
                synchronized(renderLock) { renderScopeLocked() }
            }
        }
    }

    fun setScopeFps(fps: Int) {
        scopeFps = fps.coerceIn(1, MAX_SCOPE_FPS)
        lastScopeMs = 0L
        if (mode == QuasarMode.OSCILLOSCOPE) {
            synchronized(renderLock) { renderScopeLocked() }
        }
    }

    fun setScopeSensitivity(value: Float) {
        scopeSensitivity = value.coerceIn(OscilloscopeRenderer.MIN_SENSITIVITY, OscilloscopeRenderer.MAX_SENSITIVITY)
        lastScopeMs = 0L
        if (mode == QuasarMode.OSCILLOSCOPE) {
            synchronized(renderLock) { renderScopeLocked() }
        }
    }

    fun setRingSpectrum(enabled: Boolean) {
        // Spectrum presets were removed: the ring always shows the VU level.
        // The setter stays so old clients and saved settings keep working.
        ringSpectrum = false
        lastScopeMs = 0L
        if (mode == QuasarMode.OSCILLOSCOPE) synchronized(renderLock) { renderScopeLocked() }
    }

    fun setScopeSmooth(ms: Int) {
        scopeSmoothMs = ms.coerceIn(0, OscilloscopeRenderer.MAX_SMOOTH_MS)
        scopeRenderer.setSmoothMs(scopeSmoothMs)
        lastScopeMs = 0L
        if (mode == QuasarMode.OSCILLOSCOPE) {
            synchronized(renderLock) { renderScopeLocked() }
        }
    }

    fun setRingEnabled(enabled: Boolean) {
        ringEnabled = enabled
        lastScopeMs = 0L
        if (mode == QuasarMode.CLOCK) {
            tick(LocalTime.now())
        } else if (mode == QuasarMode.OSCILLOSCOPE) {
            synchronized(renderLock) { renderScopeLocked() }
        } else if (!enabled) {
            synchronized(renderLock) {
                lastRing = RingFrame()
                ring.drawRing(lastRing.copy())
            }
        }
    }

    /** Blanks or restores the 25×16 screen output; the render mode is kept. */
    fun setScreenEnabled(enabled: Boolean) {
        screenEnabled = enabled
        lastScopeMs = 0L
        if (!enabled) {
            synchronized(renderLock) {
                lastFrame = ScreenFrame()
                screen.drawScreen(lastFrame.copy())
            }
            return
        }
        repaintNow()
    }

    /** Binds logical LED 0 (front marker) to physical LED [offset]. */
    fun setRingOrigin(offset: Int) {
        ringOrigin = ((offset % RingFrame.DEFAULT_SIZE) + RingFrame.DEFAULT_SIZE) % RingFrame.DEFAULT_SIZE
        repaintNow()
    }

    fun setScopeBand(band: ScopeBand) {
        scopeBand = band
        bandPass = null
        lastScopeMs = 0L
        if (mode == QuasarMode.OSCILLOSCOPE) {
            synchronized(renderLock) { renderScopeLocked() }
        }
    }

    /** Kept for older callers: the screen always renders the waveform. */
    fun setScopeStyle(style: SpectrogramStyle) {
        scopeStyle = SpectrogramStyle.WAVE_TRACE
        lastScopeMs = 0L
        if (mode == QuasarMode.OSCILLOSCOPE) {
            synchronized(renderLock) { renderScopeLocked() }
        }
    }

    /**
     * Drives the ring from a Yandex `*.led` pattern. Passing null returns the
     * ring to the mode's generated output.
     */
    fun setRingPattern(pattern: LedPattern?, loop: Boolean = true) {
        synchronized(renderLock) {
            ringPattern = pattern
            ringPatternLoop = loop
            ringPatternStartNs = System.nanoTime()
        }
        repaintNow()
    }

    /** One-shot pattern, e.g. the stock "listen"/"speak" reaction. */
    fun playRingPatternOnce(pattern: LedPattern) = setRingPattern(pattern, loop = false)

    fun clearRingPattern() = setRingPattern(null)

    val hasRingPattern: Boolean get() = ringPattern != null

    private fun repaintNow() {
        when (mode) {
            QuasarMode.CLOCK -> tick(LocalTime.now())
            QuasarMode.OSCILLOSCOPE -> synchronized(renderLock) { renderScopeLocked() }
            QuasarMode.ANIMATION -> frameTick()
            else -> synchronized(renderLock) { ringTickLocked() }
        }
    }

    fun tick(now: LocalTime) {
        synchronized(renderLock) {
            if (overlayAnimation != null) {
                frameTickLocked()
                return
            }
            when (mode) {
                QuasarMode.CLOCK -> renderClock(now)
                QuasarMode.AUTO -> renderClock(now)
                QuasarMode.ANIMATION -> renderIdleLocked()
                QuasarMode.TEST -> publish(testRenderer.render(), testRenderer.chaseRing(testStep++))
                QuasarMode.CUSTOM, QuasarMode.OFF -> Unit
                // The visualiser is driven by scopeTick() from a 10 ms timer.
                QuasarMode.OSCILLOSCOPE -> Unit
            }
        }
    }

    /**
     * Timer-driven refresh for everything that animates. The HAL may deliver
     * audio in blocks slower than the configured FPS, so the panel is repainted
     * from the ring buffer on a fixed cadence instead of waiting for the next
     * chunk; animations need the same cadence to hit their frame delays.
     */
    fun frameTick() {
        synchronized(renderLock) {
            frameTickLocked()
        }
    }

    private fun frameTickLocked() {
        val overlay = overlayAnimation
        if (overlay != null) {
            val elapsed = (System.nanoTime() - overlayStartNs) / 1_000_000L
            if (!overlayLoop && elapsed >= GifDecoder.durationMs(overlay)) {
                overlayAnimation = null
                lastAnimationKey = ""
                renderIdleLocked()
            } else {
                publishAnimation(overlay, elapsed, overlayLoop)
            }
            return
        }
        when (mode) {
            QuasarMode.OSCILLOSCOPE -> if (scopeDue()) renderScopeIfDirtyLocked()
            QuasarMode.ANIMATION -> renderIdleLocked()
            QuasarMode.AUTO -> if (ringPattern != null) ringTickLocked() else renderClock(LocalTime.now())
            QuasarMode.CLOCK, QuasarMode.TEST ->
                if (ringPattern != null) ringTickLocked()
            else -> Unit
        }
    }

    /** Advances a pack-provided ring pattern without touching the screen. */
    private fun ringTickLocked() {
        val frame = ringPatternFrameLocked()
        if (frame == null) {
            // A one-shot pattern just finished: put the mode's own ring back
            // instead of leaving the last frame frozen.
            if (mode == QuasarMode.CLOCK || mode == QuasarMode.AUTO) {
                renderClock(LocalTime.now())
            } else {
                publish(lastFrame.copy(), clockRenderer.renderRing(LocalTime.now().second, ringColor))
            }
            return
        }
        val out = if (ringEnabled) frame.rotated(ringOrigin) else RingFrame(frame.size)
        lastRing = out
        ring.drawRing(out.copy())
    }

    private fun ringPatternFrameLocked(): RingFrame? {
        val pattern = ringPattern ?: return null
        val elapsed = (System.nanoTime() - ringPatternStartNs) / 1_000_000L
        if (!ringPatternLoop && elapsed >= pattern.totalMs) {
            ringPattern = null
            return null
        }
        return pattern.frameAt(elapsed)
    }

    /**
     * Repaints the scope only when the capture delivered a new block. The old
     * implementation rendered from both the timer and the audio thread, so
     * duplicate frames alternated with fresh ones and the panel looked like it
     * was speeding up and slowing down.
     *
     * After playback stops the ring buffer still holds the last samples. They
     * are treated as stale and fed to the renderer as silence, which is what
     * starts the configured fade-out instead of freezing the last frame.
     */
    private fun renderScopeIfDirtyLocked() {
        val nowMs = System.nanoTime() / 1_000_000L
        val seq = pcmSeq
        val stale = lastPcmMs == 0L || nowMs - lastPcmMs > PCM_STALE_MS
        if (seq != renderedPcmSeq) {
            renderedPcmSeq = seq
            if (stale) {
                renderScopeLocked(silent = true)
            } else {
                lastSilenceRenderMs = 0L
                renderScopeLocked()
            }
            return
        }
        // While the trace fades, repaint at the visualiser cadence so the
        // decay is smooth rather than a once-a-second snap.
        if (scopeRenderer.isDecaying) {
            renderScopeLocked(silent = true)
            return
        }
        // No audio at all: refresh the flat trace once a second so a stopped
        // stream never leaves a stale waveform on the panel.
        if (nowMs - lastSilenceRenderMs >= SILENCE_REFRESH_MS) {
            lastSilenceRenderMs = nowMs
            renderScopeLocked(silent = true)
        }
    }

    /** Kept for callers that only need the visualiser cadence. */
    fun scopeTick() = frameTick()

    /** Called from the audio capture thread. */
    fun onAudioChunk(chunk: AudioChunk) {
        pcm.write(chunk.samples)
        pcmSeq++
        lastPcmMs = System.nanoTime() / 1_000_000L
        // Rendering happens on the timer thread (scopeTick) so the panel gets
        // a stable cadence and never two identical frames in a row.
    }

    fun drawCustomRgb(rgb888: ByteArray): Boolean {
        if (rgb888.size != ScreenFrame.RGB888_BYTES) return false
        val frame = ScreenFrame.fromRgb888(rgb888)
        return drawCustomFrame(frame)
    }

    fun drawCustomFrame(frame: ScreenFrame): Boolean {
        if (frame.width != ScreenFrame.DEFAULT_WIDTH || frame.height != ScreenFrame.DEFAULT_HEIGHT) return false
        synchronized(renderLock) {
            mode = QuasarMode.CUSTOM
            lastFrame = frame.copy()
            screen.drawScreen(if (screenEnabled) frame.copy() else ScreenFrame())
        }
        return true
    }

    fun snapshotFrame(): ScreenFrame = synchronized(renderLock) { lastFrame.copy() }

    fun drawCustomRingRgb(rgb888: ByteArray): Boolean {
        if (rgb888.size != RingFrame.RGB888_BYTES) return false
        return drawCustomRing(RingFrame.fromRgb888(rgb888))
    }

    fun drawCustomRing(ringFrame: RingFrame): Boolean {
        if (ringFrame.size != RingFrame.DEFAULT_SIZE) return false
        synchronized(renderLock) {
            val frame = if (ringEnabled) ringFrame.copy() else RingFrame(ringFrame.size)
            ringOverride = frame
            lastRing = frame.copy()
            ring.drawRing(lastRing.copy())
        }
        return true
    }

    /** Drops the externally supplied ring frame and lets the mode draw again. */
    fun clearRingOverride() {
        synchronized(renderLock) {
            ringOverride = null
            lastScopeMs = 0L
        }
    }

    fun snapshotRing(): RingFrame = synchronized(renderLock) { lastRing.copy() }

    fun latestPcm(maxSamples: Int = 4096): ShortArray = pcm.snapshot(maxSamples)

    fun audioRms(): Double = pcm.rms(2048)

    fun clearAudio() = pcm.clear()

    private fun renderClock(now: LocalTime) {
        val frame = clockRenderer.render(now.hour, now.minute, now.second, now.second % 2 == 0, clockSize)
        val ringFrame = clockRenderer.renderRing(now.second, ringColor)
        publish(frame, ringFrame)
    }

    private fun renderScopeLocked(silent: Boolean = false) {
        val nowMs = System.nanoTime() / 1_000_000L
        fpsWindowFrames++
        if (fpsWindowStartMs == 0L) {
            fpsWindowStartMs = nowMs
        } else if (nowMs - fpsWindowStartMs >= 1_000L) {
            scopeFpsActual = (fpsWindowFrames * 1_000L / (nowMs - fpsWindowStartMs)).toInt()
            fpsWindowStartMs = nowMs
            fpsWindowFrames = 0
        }
        val samples = if (silent) ShortArray(0) else pcm.snapshot(OscilloscopeRenderer.DEFAULT_WINDOW * 8)
        // A pack-provided ring pattern is an explicit user choice, so it wins
        // over the generated clock/VU ring while it is playing.
        val ringFrame = activeRingPatternLocked()
            ?: scopeRenderer.renderRing(samples, ringColor, scopeSensitivity)
        val filtered = if (silent) samples else filterForBand(samples)
        val window = scopeBand.windowSamples(PCM_RATE)
        val frame = scopeRenderer.render(filtered, window, scopeSensitivity)
        publish(frame, ringFrame)
    }

    private fun filterForBand(samples: ShortArray): ShortArray {
        val pass = bandPass ?: BandPass(PCM_RATE, scopeBand).also { bandPass = it }
        pass.reset()
        return pass.process(samples)
    }

    private fun renderIdleLocked() {
        val animation = idleAnimation
        if (animation == null) {
            publish(ScreenFrame(), activeRingPatternLocked() ?: clockRenderer.renderRing(LocalTime.now().second, ringColor))
            return
        }
        val elapsed = (System.nanoTime() - idleStartNs) / 1_000_000L
        // The idle screen always loops: several stock clips have no NETSCAPE
        // loop block, and a frozen last frame looked like "animation stopped".
        publishAnimation(animation, elapsed, loop = true)
    }

    private fun publishAnimation(animation: GifAnimation, elapsedMs: Long, loop: Boolean) {
        val frameIndex = GifDecoder.frameIndexAt(animation, elapsedMs, loop)
        val key = "${System.identityHashCode(animation)}:$frameIndex:$animationColor:$loop"
        if (key == lastAnimationKey) return
        lastAnimationKey = key
        val frame = animationRenderer.render(animation, elapsedMs, animationColor, loop = loop)
        publish(frame, activeRingPatternLocked() ?: clockRenderer.renderRing(LocalTime.now().second, ringColor))
    }

    /** Rotates a running ring pattern into the physical orientation. */
    private fun activeRingPatternLocked(): RingFrame? {
        val frame = ringPatternFrameLocked() ?: return null
        return if (ringEnabled) frame.rotated(ringOrigin) else RingFrame(frame.size)
    }

    /** Returns true at most [scopeFps] times per second. Caller holds renderLock. */
    private fun scopeDue(): Boolean {
        // Monotonic clock: a wall-clock jump must not stall the visualiser.
        val now = System.nanoTime() / 1_000_000L
        val interval = 1000L / scopeFps.coerceIn(1, MAX_SCOPE_FPS)
        if (now - lastScopeMs < interval) return false
        lastScopeMs = now
        return true
    }

    private fun publish(frame: ScreenFrame, ringFrame: RingFrame) {
        lastFrame = frame
        val override = ringOverride
        val pattern = if (override == null) ringPatternFrameLocked() else null
        val base = override ?: pattern ?: ringFrame
        lastRing = if (ringEnabled) base.rotated(ringOrigin) else RingFrame(base.size)
        screen.drawScreen(if (screenEnabled) frame else ScreenFrame())
        ring.drawRing(lastRing)
    }

    companion object {
        /** 50 fps is smooth on the panel and above the HAL block rate. */
        const val MAX_SCOPE_FPS = 50

        /** Pipeline rate of the capture path (Visualizer is decimated to this). */
        const val PCM_RATE = 16_000

        /** Refresh the flat trace at least this often when no audio arrives. */
        private const val SILENCE_REFRESH_MS = 1_000L

        /** PCM older than this is treated as stopped playback, not a signal. */
        private const val PCM_STALE_MS = 180L
    }
}