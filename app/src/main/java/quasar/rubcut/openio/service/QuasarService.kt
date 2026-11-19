package quasar.rubcut.openio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import quasar.rubcut.openio.BuildConfig
import quasar.rubcut.openio.MainActivity
import quasar.rubcut.openio.R
import quasar.rubcut.openio.data.anim.AnimationStore
import quasar.rubcut.openio.data.audio.VisualizerAudioSource
import quasar.rubcut.openio.data.audio.AudioTrackSink
import quasar.rubcut.openio.data.display.NativeDisplay
import quasar.rubcut.openio.data.display.NativeStockFrames
import quasar.rubcut.openio.data.input.NativeInput
import quasar.rubcut.openio.data.net.HttpServer
import quasar.rubcut.openio.data.net.IpcServer
import quasar.rubcut.openio.data.net.LocalIpcServer
import quasar.rubcut.openio.data.net.PcmStreamServer
import quasar.rubcut.openio.data.plugin.PluginHost
import quasar.rubcut.openio.data.plugin.PluginLogStore
import quasar.rubcut.openio.data.plugin.PluginServiceRuntime
import quasar.rubcut.openio.data.plugin.PluginStore
import quasar.rubcut.openio.data.root.RootManager
import quasar.rubcut.openio.domain.QuasarEngine
import quasar.rubcut.openio.domain.audio.ScopeBand
import quasar.rubcut.openio.domain.led.LedPattern
import quasar.rubcut.openio.domain.model.AudioChunk
import quasar.rubcut.openio.domain.model.InputEvent
import quasar.rubcut.openio.domain.model.InputKey
import quasar.rubcut.openio.domain.model.QuasarMode
import quasar.rubcut.openio.domain.model.RingFrame
import quasar.rubcut.openio.domain.plugin.ModItemKind
import quasar.rubcut.openio.domain.plugin.PluginPack
import quasar.rubcut.openio.domain.plugin.PluginProvider
import quasar.rubcut.openio.domain.plugin.ProviderType
import quasar.rubcut.openio.domain.plugin.ScreenSlot
import quasar.rubcut.openio.domain.render.ClockSize
import quasar.rubcut.openio.domain.render.GifAnimation
import quasar.rubcut.openio.domain.render.GifDecoder
import quasar.rubcut.openio.domain.render.OscilloscopeRenderer
import quasar.rubcut.openio.domain.render.SpectrogramStyle
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.LocalTime
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that owns the whole runtime: mmap display, poll() input,
 * microphone capture, AudioTrack output, HTTP/IPC transports and the io.sdk
 * SIGSTOP/SIGCONT lifecycle.
 */
class QuasarService : Service(), ControlApi {
    private lateinit var display: NativeDisplay
    private lateinit var engine: QuasarEngine
    private lateinit var input: NativeInput
    private lateinit var mic: MicHub
    private lateinit var recorder: VisualizerAudioSource
    private lateinit var speaker: AudioTrackSink
    private lateinit var root: RootManager
    private lateinit var events: EventHub
    private lateinit var http: HttpServer
    private lateinit var ipc: IpcServer
    private lateinit var unixIpc: LocalIpcServer
    private lateinit var pcmStream: PcmStreamServer
    private lateinit var animations: AnimationStore
    private lateinit var plugins: PluginStore
    private lateinit var pluginHost: PluginHost
    private lateinit var pluginLogs: PluginLogStore
    private lateinit var pluginServices: PluginServiceRuntime
    private lateinit var playback: PlaybackMonitor
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var worker: ExecutorService
    private lateinit var toneExecutor: ExecutorService
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var rootAvailable = false

    @Volatile
    private var selinux = "unknown"

    @Volatile
    private var keepSdkFrozen = false

    @Volatile
    private var autoEnabled = false

    private lateinit var inputActions: InputActions

    private lateinit var mediaAnimations: MediaAnimations

    /** Master switch for the whole panel (screen + ring + capture). */
    @Volatile
    private var masterEnabled = true

    /** When false the visualiser capture never opens; io.sdk owns the mic. */
    @Volatile
    private var micShare = true

    /**
     * When true the microphone is handed to the rest of the system: our
     * playback capture is stopped and io.sdk's RECORD_AUDIO app-op is denied
     * so other apps can open AudioRecord.
     */
    @Volatile
    private var micPassthrough = false

    /** Capture stays closed until this time after an Alice activation. */
    @Volatile
    private var alicePauseUntilMs = 0L

    @Volatile
    private var autoPlaying = false

    @Volatile
    private var autoPackages: List<String> = emptyList()

    @Volatile
    private var lastRingPatternKey: String? = null

    // Which provider is bound to every screen slot. The web UI ("Экран")
    // changes these, and both manual and auto mode follow the selection.
    @Volatile
    private var selectedClock: String? = null

    @Volatile
    private var selectedVisualizer: String? = null

    @Volatile
    private var selectedAnimation: String? = null

    @Volatile
    private var selectedRing: String? = null

    @Volatile
    private var clockEnabled = true

    @Volatile
    private var visualizerEnabled = true

    @Volatile
    private var animationEnabled = true

    @Volatile
    private var ringPatternsEnabled = true

    private var lastAutoProbeMs = 0L

    // Auto-mode hysteresis: a candidate mode must stay stable for a while
    // before it is applied, so short media pauses between tracks do not flip
    // the screen to the clock and back every watchdog tick.
    @Volatile
    private var autoPendingMode: QuasarMode? = null

    @Volatile
    private var autoPendingSinceMs = 0L

    @Volatile
    private var sdkRunning = false

    @Volatile
    private var sdkFrozen = false

    @Volatile
    private var sdkPids: List<Int> = emptyList()

    @Volatile
    private var displayStarted = false

    @Volatile
    private var inputStarted = false

    @Volatile
    private var audioInStarted = false

    @Volatile
    private var audioOutStarted = false

    private var startedAt = 0L

    @Volatile
    private var lastStatusJson: String = "{}"

    @Volatile
    private var autostartInstalled = false

    private val settingsPrefs by lazy { getSharedPreferences("quasar_settings", MODE_PRIVATE) }

    /** Boot autostart is on by default and can be toggled at runtime. */
    private var autostartEnabled: Boolean
        get() = settingsPrefs.getBoolean("autostart_enabled", true)
        set(value) {
            settingsPrefs.edit().putBoolean("autostart_enabled", value).apply()
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        serviceRunning = true
        startedAt = SystemClock.elapsedRealtime()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting)))

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "quasar-openio:service")
            .apply {
                setReferenceCounted(false)
                acquire()
            }

        events = EventHub()
        pluginLogs = PluginLogStore(this)
        mic = MicHub()
        display = NativeDisplay()
        val renderers = RendererConfig.load(this)
        engine = QuasarEngine(display, display, renderers.clock, renderers.oscilloscope, renderers.test, NativeStockFrames())
        val settings = RendererConfig.loadRuntime(this, renderers.defaults)
        animations = AnimationStore(this)
        plugins = PluginStore(this)
        loadScreenState()
        engine.setRingColor(settings.ringColor)
        engine.setRingEnabled(settings.ringEnabled)
        engine.setRingSpectrum(settings.ringSpectrum)
        engine.setScopeFps(settings.scopeFps)
        engine.setScopeSensitivity(settings.scopeSensitivity)
        engine.setScopeSmooth(settings.scopeSmooth)
        engine.setClockSize(settings.clockSize)
        engine.setAnimationColor(settings.animationColor)
        val selectedIdle = animationFromSelection()
            ?: animations.load(settings.idleAnimation)
            ?: firstAnimation()
        engine.setIdleAnimation(selectedIdle)
        engine.setRingOrigin(settings.ringOrigin)
        engine.setScopeBand(ScopeBand.fromId(settings.scopeBand))
        autoEnabled = settingsPrefs.getBoolean("auto_enabled", false)
        applySelectionConfig()
        if (!autoEnabled) engine.setMode(restoredMode())
        playback = PlaybackMonitor(this)
        playback.start()
        inputActions = InputActions(this, settingsPrefs, inputActionsHost)
        masterEnabled = settingsPrefs.getBoolean("master_enabled", true)
        micShare = settingsPrefs.getBoolean("mic_share", true)
        micPassthrough = settingsPrefs.getBoolean("mic_passthrough", false)
        // The screen switch is persisted separately from the render settings;
        // a master-off service restart must come back dark.
        engine.setScreenEnabled(settingsPrefs.getBoolean("screen_enabled", true))
        if (!masterEnabled) engine.setMode(QuasarMode.OFF)
        pluginHost = PluginHost(this, plugins, events, pluginLogs, apiToken(), pluginActions)
        pluginHost.start()
        pluginServices = PluginServiceRuntime(this, plugins, pluginLogs, apiToken())
        input = NativeInput(::onInputEvent)
        speaker = AudioTrackSink()
        recorder = VisualizerAudioSource(
            ::onAudioChunk,
        )
        root = RootManager(this)

        scheduler = Executors.newScheduledThreadPool(4) { runnable ->
            Thread(runnable, "quasar-scheduler").apply { isDaemon = true }
        }
        worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "quasar-setup").apply { isDaemon = true }
        }
        toneExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "quasar-tone").apply { isDaemon = true }
        }
        mediaAnimations = MediaAnimations(this, engine, animations, settingsPrefs, worker) { name, body ->
            emitEvent(name, body)
        }
        mediaAnimations.playbackState = { playback.currentState() }
        mediaAnimations.setMasterEnabled(masterEnabled)

        http = HttpServer(HTTP_PORT, this, this)
        ipc = IpcServer(IPC_PORT, this)
        unixIpc = LocalIpcServer(LocalIpcServer.DEFAULT_NAME, this)
        pcmStream = PcmStreamServer(mic, PCM_PORT)

        http.start()
        ipc.start()
        unixIpc.start()
        pcmStream.start()

        emitEvent("service_start") {
            put("pid", android.os.Process.myPid())
        }

        scheduler.scheduleAtFixedRate(
            { runCatching { engine.tick(LocalTime.now()) }.onFailure { Log.w(TAG, "tick failed", it) } },
            0,
            250,
            TimeUnit.MILLISECONDS,
        )
        // The visualiser repaints from the PCM ring buffer on its own
        // cadence; AUDIO arrives in HAL-sized blocks and would otherwise cap
        // the panel at ~16 fps.
        scheduler.scheduleAtFixedRate(
            { runCatching { engine.scopeTick() }.onFailure { Log.w(TAG, "scope tick failed", it) } },
            0,
            10,
            TimeUnit.MILLISECONDS,
        )
        scheduler.scheduleWithFixedDelay({ updateStatusCache() }, 2, 2, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ maintenance() }, 15, 20, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay(
            { runCatching { autoTick() }.onFailure { Log.w(TAG, "auto tick failed", it) } },
            2,
            2,
            TimeUnit.SECONDS,
        )
        // Media-event animations poll the fresh playback state every second;
        // inputActions.tick() clears the volume ring arc after idle time.
        scheduler.scheduleWithFixedDelay(
            {
                runCatching {
                    mediaAnimations.poll()
                    inputActions.tick()
                }.onFailure { Log.w(TAG, "media/input tick failed", it) }
            },
            4,
            1,
            TimeUnit.SECONDS,
        )

        worker.execute {
            setupRuntime()
            runCatching { pluginServices.start() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FREEZE -> freezeSdk()
            ACTION_UNFREEZE -> unfreezeSdk()
            ACTION_RESTART_PIPELINE -> worker.execute { restartPipelineNow() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The service is intentionally sticky even if the setup activity is swiped away.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceRunning = false
        instance = null
        runCatching { scheduler.shutdownNow() }
        runCatching { worker.shutdownNow() }
        runCatching { toneExecutor.shutdownNow() }
        if (::pluginHost.isInitialized) runCatching { pluginHost.stop() }
        if (::pluginServices.isInitialized) runCatching { pluginServices.stop() }
        if (::playback.isInitialized) runCatching { playback.stop() }
        runCatching { http.stop() }
        runCatching { ipc.stop() }
        runCatching { unixIpc.stop() }
        runCatching { pcmStream.stop() }
        runCatching { mic.close() }
        runCatching { recorder.stop() }
        runCatching { speaker.stop() }
        runCatching { input.stop() }
        runCatching { display.stop() }

        if (rootAvailable) {
            // Never leave io.sdk stopped or without microphone access after
            // our service is gone.
            val restore = Thread({
                runCatching {
                    if (micPassthrough || settingsPrefs.getBoolean("mic_passthrough_applied", false)) {
                        root.allowSdkMicAccess()
                        settingsPrefs.edit().putBoolean("mic_passthrough_applied", false).apply()
                    }
                }
                runCatching { root.unfreezeSdk() }
            }, "quasar-restore-sdk")
            restore.isDaemon = true
            restore.start()
            runCatching { restore.join(5_000) }
        }
        keepSdkFrozen = false
        emitEvent("service_stop")
        runCatching { wakeLock?.release() }
        wakeLock = null
        stopForeground(true)
        stopSelf()
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- setup

    private fun setupRuntime() {
        try {
            rootAvailable = runCatching { root.isRoot() }.getOrDefault(false)
            selinux = runCatching { root.selinux() }.getOrDefault("unknown")
            emitEvent("setup") {
                put("step", "root")
                put("ok", rootAvailable)
                put("selinux", selinux)
            }

            if (rootAvailable) {
                val prepared = root.prepareRuntime(packageName)
                emitEvent("setup") {
                    put("step", "runtime")
                    put("ok", prepared.ok)
                    put("detail", prepared.message.take(600))
                }
                autostartInstalled = root.isAutostartInstalled()
                if (autostartEnabled && !autostartInstalled) {
                    val installed = root.installAutostart()
                    autostartInstalled = installed.ok
                    emitEvent("setup") {
                        put("step", "autostart")
                        put("ok", installed.ok)
                        put("detail", installed.output.takeLast(1200))
                    }
                } else if (!autostartEnabled && autostartInstalled) {
                    // Honour the persisted "autostart off" state even if an
                    // older build left the rc/launcher behind.
                    runCatching { root.removeAutostart() }
                    autostartInstalled = false
                }
                keepSdkFrozen = false
                // io.sdk is Alice's microphone and media bridge. It must stay
                // unfrozen by default; freezing it silently broke voice input.
                val thawed = root.unfreezeSdk()
                applyStoredMicPassthrough()
                updateSdkCache()
                emitEvent("sdk_unfrozen") {
                    put("ok", thawed.ok)
                    put("detail", thawed.message.take(300))
                    put("pids", JSONArray(sdkPids))
                }
                // Keep the launcher alias enabled: hiding it makes the app
                // impossible to start from the station UI if the service dies.
            } else {
                emitEvent("setup_error") { put("error", "root unavailable; display/input need root chmod") }
            }

            if (!displayStarted) {
                displayStarted = display.start()
                emitEvent("display") {
                    put("ok", displayStarted)
                    put("info", display.info())
                }
            }
            if (!inputStarted) {
                inputStarted = input.start()
                emitEvent("input_device") { put("ok", inputStarted) }
            }
            if (!audioInStarted) {
                // The microphone is opened only while the visualiser is on
                // screen: a capture session that runs even in clock mode
                // competes with Alice for the mic and is the one thing that
                // must never outlive the feature.
                syncCaptureToMode()
            }
            // Output is opened on demand only (see playTone): an AudioTrack
            // that stays open for the whole service lifetime conflicts with
            // the loopback capture in the vendor HAL. audioOutStarted tracks
            // the real state of the sink.
            audioOutStarted = speaker.isStarted()
            updateNotification()
            updateStatusCache()
            emitEvent("ready") {
                put("display", displayStarted)
                put("input", inputStarted)
                put("audio_in", audioInStarted)
                put("audio_out", audioOutStarted)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "setup failed", e)
            emitEvent("setup_error") { put("error", e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun restartPipelineNow() {
        runCatching { input.stop() }
        runCatching { recorder.stop() }
        runCatching { speaker.stop() }
        runCatching { display.stop() }
        displayStarted = false
        inputStarted = false
        audioInStarted = false
        audioOutStarted = false
        setupRuntime()
    }

    private var audioRetryDelayMs = 0L
    private var nextAudioRetryAt = 0L

    private fun maintenance() {
        // The Visualizer effect can die when AudioFlinger recreates its output
        // thread (for example when a TV app switches streams). Retry with a
        // bounded backoff, and only while the visualiser is actually visible.
        val now = SystemClock.elapsedRealtime()
        if (engine.mode == QuasarMode.OSCILLOSCOPE && !recorder.isRunning && micShare &&
            !micPassthrough && now >= nextAudioRetryAt
        ) {
            audioInStarted = recorder.start()
            if (audioInStarted) {
                audioRetryDelayMs = 0L
                nextAudioRetryAt = 0L
                emitEvent("audio_in") {
                    put("ok", true)
                    put("source", "retry")
                }
            } else {
                audioRetryDelayMs = if (audioRetryDelayMs == 0L) {
                    15_000L
                } else {
                    (audioRetryDelayMs * 2).coerceAtMost(120_000L)
                }
                nextAudioRetryAt = now + audioRetryDelayMs
                emitEvent("audio_in") {
                    put("ok", false)
                    put("source", "retry")
                    put("error", recorder.lastError ?: "")
                    put("retry_in_ms", audioRetryDelayMs)
                }
            }
            updateStatusCache()
        } else if (audioInStarted && !recorder.isRunning) {
            // The helper died; report it and let the next pass restart it.
            audioInStarted = false
            nextAudioRetryAt = now + 2_000L
            updateStatusCache()
        }
        if (!rootAvailable) return
        runCatching {
            updateSdkCache()
            if (micPassthrough) {
                // Some system actions reset app-ops; keep io.sdk away from the
                // microphone without restarting a healthy process.
                runCatching { root.refreshSdkMicDenial() }
            }
            if (keepSdkFrozen && sdkRunning && !sdkFrozen) {
                val result = root.freezeSdk()
                updateSdkCache()
                emitEvent("sdk_refrozen") { put("ok", result.ok) }
            }
            updateNotification()
        }.onFailure { Log.w(TAG, "maintenance failed", it) }
    }

    private fun updateSdkCache() {
        val state = runCatching { root.sdkState() }.getOrNull() ?: return
        sdkRunning = state.running
        sdkFrozen = state.stopped
        sdkPids = state.pids
    }

    // ---------------------------------------------------------- screen state

    private fun loadScreenState() {
        clockEnabled = settingsPrefs.getBoolean("feature_clock", true)
        visualizerEnabled = settingsPrefs.getBoolean("feature_visualizer", true)
        animationEnabled = settingsPrefs.getBoolean("feature_animation", true)
        ringPatternsEnabled = settingsPrefs.getBoolean("feature_ring", true)
        selectedClock = settingsPrefs.getString("selected_clock", null)
            ?.takeIf { it.isNotBlank() }
            ?: defaultSelection(ProviderType.CLOCK)
        selectedVisualizer = settingsPrefs.getString("selected_visualizer", null)
            ?.takeIf { it.isNotBlank() }
            ?: defaultSelection(ProviderType.VISUALIZER)
        val idlePref = settingsPrefs.getString("idle_animation", null)
        selectedAnimation = settingsPrefs.getString("selected_animation", null)
            ?.takeIf { it.isNotBlank() }
            ?: providerKeyForIdle(idlePref)
            ?: defaultSelection(ProviderType.ANIMATION)
        selectedRing = settingsPrefs.getString("selected_ring", null)?.takeIf { it.isNotBlank() }
    }

    private fun defaultSelection(type: ProviderType): String? =
        plugins.enabledProviders(type).firstOrNull()?.key

    /** Persists one screen slot, removing the key when there is no choice. */
    private fun persistSelection(key: String, value: String?) {
        val editor = settingsPrefs.edit()
        if (value.isNullOrBlank()) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    /**
     * Forgets selections that were just disabled. Without touching prefs the
     * next service start would resurrect the disabled provider, so every
     * affected `selected_*` key is rewritten (or removed).
     */
    private fun dropSelection(predicate: (String) -> Boolean) {
        if (selectedClock?.let(predicate) == true) {
            selectedClock = null
            persistSelection("selected_clock", null)
        }
        if (selectedVisualizer?.let(predicate) == true) {
            selectedVisualizer = null
            persistSelection("selected_visualizer", null)
        }
        if (selectedAnimation?.let(predicate) == true) {
            selectedAnimation = null
            persistSelection("selected_animation", null)
        }
        if (selectedRing?.let(predicate) == true) {
            selectedRing = null
            persistSelection("selected_ring", null)
            clearRingPattern()
        }
    }

    private fun ensureSelections() {
        var animationChanged = false
        if (selectedClock?.let { plugins.locate(it) } == null) {
            selectedClock = defaultSelection(ProviderType.CLOCK)
            persistSelection("selected_clock", selectedClock)
        }
        if (selectedVisualizer?.let { plugins.locate(it) } == null) {
            selectedVisualizer = defaultSelection(ProviderType.VISUALIZER)
            persistSelection("selected_visualizer", selectedVisualizer)
        }
        if (selectedAnimation?.let { plugins.locate(it) } == null) {
            selectedAnimation = defaultSelection(ProviderType.ANIMATION)
            persistSelection("selected_animation", selectedAnimation)
            animationChanged = true
        }
        if (selectedRing?.let { plugins.locate(it) } == null) {
            selectedRing = null
            persistSelection("selected_ring", null)
        }
        if (animationChanged) {
            animationFromSelection()?.let { engine.setIdleAnimation(it) }
        }
        applySelectionConfig()
    }

    private fun providerKeyForIdle(idle: String?): String? {
        if (idle.isNullOrBlank()) return null
        return plugins.enabledProviders(ProviderType.ANIMATION).firstOrNull { located ->
            val resource = located.provider.resource ?: return@firstOrNull false
            resource == "builtin:anim/$idle" || resource == idle || resource.endsWith("/$idle")
        }?.key
    }

    private fun animationFromSelection(): GifAnimation? {
        val key = selectedAnimation ?: return null
        val located = plugins.locate(key) ?: return null
        return animationFromProvider(located)
    }

    private fun animationFromProvider(located: PluginStore.Located): GifAnimation? {
        val resource = located.provider.resource ?: return null
        return if (resource.startsWith("builtin:")) {
            animations.load(resource.removePrefix("builtin:"))
        } else {
            val bytes = plugins.open(located.pack.id, resource) ?: return null
            animations.loadBytes("${located.pack.id}/$resource", bytes)
        }
    }

    private fun firstAnimation(): GifAnimation? {
        val located = plugins.enabledProviders(ProviderType.ANIMATION).firstOrNull() ?: return null
        return animationFromProvider(located)
    }

    /** Applies the stored config of the selected clock/visualiser/animation. */
    private fun applySelectionConfig() {
        selectedClock?.let { key ->
            plugins.locate(key)?.let { applyPluginConfig(it.provider, plugins.config(it)) }
        }
        selectedVisualizer?.let { key ->
            plugins.locate(key)?.let { applyPluginConfig(it.provider, plugins.config(it)) }
        }
        selectedAnimation?.let { key ->
            plugins.locate(key)?.let { located ->
                animationFromProvider(located)?.let { engine.setIdleAnimation(it) }
            }
        }
        selectedRing?.let { key ->
            if (ringPatternsEnabled) {
                plugins.locate(key)?.let { located ->
                    located.provider.resource?.let { playRingResource(located.pack.id, it, true) }
                }
            }
        }
    }

    private fun restoredMode(): QuasarMode {
        val stored = settingsPrefs.getString("last_mode", null)
        val mode = QuasarMode.fromId(stored) ?: QuasarMode.CLOCK
        return when {
            mode == QuasarMode.ANIMATION && (!animationEnabled || !engine.hasIdleAnimation) -> fallbackMode()
            mode == QuasarMode.OSCILLOSCOPE && !visualizerEnabled -> fallbackMode()
            mode == QuasarMode.CLOCK && !clockEnabled -> fallbackMode()
            mode == QuasarMode.CUSTOM || mode == QuasarMode.TEST -> QuasarMode.CLOCK
            mode == QuasarMode.AUTO -> QuasarMode.CLOCK
            else -> mode
        }
    }

    private fun fallbackMode(): QuasarMode = when {
        clockEnabled -> QuasarMode.CLOCK
        animationEnabled && engine.hasIdleAnimation -> QuasarMode.ANIMATION
        visualizerEnabled -> QuasarMode.OSCILLOSCOPE
        else -> QuasarMode.OFF
    }

    private fun apiToken(): String {
        val existing = settingsPrefs.getString("api_token", null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = UUID.randomUUID().toString().replace("-", "")
        settingsPrefs.edit().putString("api_token", fresh).apply()
        return fresh
    }

    // ---------------------------------------------------------------- input

    private fun onInputEvent(event: InputEvent) {
        emitEvent(event.key.eventName) {
            put("type", "input")
            put("key", event.key.eventName)
            put("device", event.device)
            put("code", event.code)
            put("value", event.value)
        }
        // An explicit freeze is a maintenance action; the Alice button must
        // always be able to wake io.sdk because it owns the microphone path.
        if (event.key == InputKey.ALICE && keepSdkFrozen) {
            worker.execute { runCatching { unfreezeSdk() } }
        }
        // Mapped actions run on the worker thread: the input thread must stay
        // responsive, and some actions (root shell keyevents) take longer.
        if (::inputActions.isInitialized) {
            worker.execute { inputActions.onKey(event) }
        }
    }

    private fun onAudioChunk(chunk: AudioChunk) {
        engine.onAudioChunk(chunk)
        mic.onChunk(chunk.samples)
    }

    // ----------------------------------------------------------- ControlApi

    override fun statusJson(): String {
        val status = JSONObject()
            .put("ok", true)
            .put("package", packageName)
            .put("version", BuildConfig.VERSION_NAME)
            .put("uptime_ms", SystemClock.elapsedRealtime() - startedAt)
            .put("root", rootAvailable)
            .put("selinux", selinux)
            .put("mode", if (autoEnabled) "auto" else engine.mode.id)
            .put("mode_effective", engine.mode.id)
            .put("mode_title", if (autoEnabled) "Авто" else engine.mode.title)
            .put(
                "render",
                JSONObject()
                    .put("ring_color", RendererConfig.hex(engine.ringColor))
                    .put("ring_enabled", engine.ringEnabled)
                    .put("ring_spectrum", engine.ringSpectrum)
                    .put("ring_size", 24)
                    .put("ring_direction", "clockwise")
                    .put("scope_fps", engine.scopeFps)
                    .put("scope_fps_actual", engine.scopeFpsActual)
                    .put("scope_max_fps", QuasarEngine.MAX_SCOPE_FPS)
                    .put("scope_sensitivity", engine.scopeSensitivity.toDouble())
                    .put("scope_min_sensitivity", OscilloscopeRenderer.MIN_SENSITIVITY.toDouble())
                    .put("scope_max_sensitivity", OscilloscopeRenderer.MAX_SENSITIVITY.toDouble())
                    .put("scope_smooth", engine.scopeSmoothMs)
                    .put("scope_smooth_max", OscilloscopeRenderer.MAX_SMOOTH_MS)
                    .put("scope_band", engine.scopeBand.id)
                    .put("scope_style", engine.scopeStyle.id)
                    .put(
                        "scope_bands",
                        JSONArray(
                            ScopeBand.entries.map {
                                JSONObject().put("id", it.id).put("title", it.title)
                            },
                        ),
                    )
                    .put("ring_origin", engine.ringOrigin)
                    .put("capture_rate", recorder.captureRate)
                    .put("capture_rate_max", RendererConfig.MAX_CAPTURE_RATE)
                    .put("clock_size", engine.clockSize.name.lowercase())
                    .put("clock_sizes", JSONArray(arrayOf("large", "middle", "small")))
                    .put("animation_color", RendererConfig.hex(engine.animationColor)),
            )
            .put(
                "animation",
                JSONObject()
                    .put("idle", settingsPrefs.getString("idle_animation", null) ?: JSONObject.NULL)
                    .put("idle_loaded", engine.hasIdleAnimation)
                    .put("overlay", engine.overlayVisible)
                    .put("selected", selectedAnimation ?: JSONObject.NULL),
            )
            .put(
                "ring",
                JSONObject()
                    .put("pattern", lastRingPatternKey ?: JSONObject.NULL)
                    .put("has_pattern", engine.hasRingPattern),
            )
            .put(
                "auto",
                JSONObject()
                    .put("enabled", autoEnabled)
                    .put("playing", autoPlaying)
                    .put("effective", engine.mode.id)
                    .put("pending", autoPendingMode?.id ?: JSONObject.NULL)
                    .put("packages", JSONArray(autoPackages)),
            )
            .put(
                "plugins",
                JSONObject()
                    .put("count", plugins.packs().size)
                    .put("providers", plugins.packs().sumOf { it.providers.size })
                    .put("user", plugins.packs().count { !it.builtin })
                    .put("errors", plugins.packs().count { it.error != null })
                    .put("active_ring", lastRingPatternKey ?: JSONObject.NULL)
                    .put("enabled_packs", plugins.packs().count { plugins.isPackEnabled(it.id) }),
            )
            .put(
                "mods",
                JSONObject()
                    .put("count", plugins.packs().size)
                    .put("items", plugins.packs().sumOf { it.items.size })
                    .put(
                        "by_kind",
                        JSONObject().apply {
                            for (kind in ModItemKind.entries) {
                                put(kind.id, plugins.packs().sumOf { it.items(kind).size })
                            }
                        },
                    )
                    .put("user", plugins.packs().count { !it.builtin })
                    .put("errors", plugins.packs().count { it.error != null })
                    .put("active_ring", lastRingPatternKey ?: JSONObject.NULL)
                    .put("enabled_mods", plugins.packs().count { plugins.isPackEnabled(it.id) }),
            )
            .put(
                "selection",
                JSONObject()
                    .put("clock", selectedClock ?: JSONObject.NULL)
                    .put("visualizer", selectedVisualizer ?: JSONObject.NULL)
                    .put("animation", selectedAnimation ?: JSONObject.NULL)
                    .put("ring", selectedRing ?: JSONObject.NULL),
            )
            .put(
                "features",
                JSONObject()
                    .put("clock", clockEnabled)
                    .put("visualizer", visualizerEnabled)
                    .put("animation", animationEnabled)
                    .put("ring", ringPatternsEnabled),
            )
            .put("services", servicesJson())
            .put("keep_sdk_frozen", keepSdkFrozen)
            .put(
                "autostart",
                JSONObject()
                    .put("enabled", autostartEnabled)
                    .put("installed", autostartInstalled),
            )
            .put(
                "sdk",
                JSONObject()
                    .put("running", sdkRunning)
                    .put("frozen", sdkFrozen)
                    .put("pids", JSONArray(sdkPids)),
            )
            .put("display", JSONObject().put("started", displayStarted).put("info", display.info()))
            .put("input", JSONObject().put("started", inputStarted))
            .put(
                "audio",
                JSONObject()
                    .put("capture", recorder.isRunning)
                    .put("capture_source", "visualizer-mix")
                    .put("output", speaker.isStarted())
                    .put("volume", speaker.volume())
                    .put("input_error", recorder.lastError ?: "")
                    .put("output_error", speaker.lastError ?: ""),
            )
            .put(
                "mic",
                JSONObject()
                    .put("rms", mic.rms())
                    .put("samples", mic.size())
                    .put("sample_rate", 16_000)
                    .put("channels", 1)
                    .put("passthrough", micPassthrough),
            )
            .put(
                "ports",
                JSONObject()
                    .put("http", HTTP_PORT)
                    .put("ipc", IPC_PORT)
                    .put("pcm", PCM_PORT)
                    .put("unix", LocalIpcServer.DISPLAY_ADDRESS),
            )
            .put("ip", localIp())
            .put(
                "general",
                JSONObject()
                    .put("master", masterEnabled)
                    .put("screen", engine.screenEnabled)
                    .put("mic_share", micShare)
                    .put("mic_passthrough", micPassthrough)
                    .put(
                        "ring_volume",
                        JSONObject()
                            .put("enabled", settingsPrefs.getBoolean("ring_volume_anim", true))
                            .put("step", settingsPrefs.getInt("ring_volume_step", 1)),
                    ),
            )
            .put(
                "media_anim",
                if (::mediaAnimations.isInitialized) {
                    JSONObject(mediaAnimations.toJson())
                } else {
                    JSONObject()
                },
            )
            .put(
                "remap",
                if (::inputActions.isInitialized) {
                    JSONObject(inputActions.bindingsJson()).getJSONArray("bindings")
                } else {
                    JSONArray()
                },
            )
            .put("events", events.listenerCount())
        lastStatusJson = status.toString()
        return lastStatusJson
    }

    private fun servicesJson(): JSONObject {
        if (!::pluginServices.isInitialized) {
            return JSONObject().put("count", 0).put("running", 0).put("items", JSONArray())
        }
        val items = JSONArray()
        var running = 0
        for (service in pluginServices.statuses()) {
            if (service.running) running++
            items.put(
                JSONObject()
                    .put("key", service.key)
                    .put("pack", service.packId)
                    .put("provider", service.providerId)
                    .put("name", service.name)
                    .put("description", service.description)
                    .put("running", service.running)
                    .put("pid", service.pid)
                    .put("restarts", service.restarts)
                    .put("uptime_ms", service.uptimeMs)
                    .put("last_exit", service.lastExit ?: JSONObject.NULL)
                    .put("last_error", service.lastError ?: JSONObject.NULL)
                    .put("restart", service.restartPolicy)
                    .put("autostart", service.autostart)
                    .put("manually_stopped", service.manuallyStopped),
            )
        }
        return JSONObject()
            .put("count", items.length())
            .put("running", running)
            .put("items", items)
    }

    override fun frameRgb(): ByteArray = engine.snapshotFrame().toRgb888()

    override fun ringRgb(): ByteArray = engine.snapshotRing().toRgb888()

    override fun drawScreen(rgb888: ByteArray): Boolean {
        val ok = engine.drawCustomRgb(rgb888)
        if (ok) emitEvent("frame_custom") { put("bytes", rgb888.size) }
        return ok
    }

    override fun drawRing(rgb888: ByteArray): Boolean {
        val ok = engine.drawCustomRingRgb(rgb888)
        if (ok) emitEvent("ring_custom") { put("bytes", rgb888.size) }
        return ok
    }

    override fun setMode(mode: String): Boolean {
        // Master-off keeps the panel dark; only setMaster(true) restores it.
        if (!masterEnabled) return mode == "off"
        if (mode.equals("auto", ignoreCase = true)) {
            autoEnabled = true
            settingsPrefs.edit().putBoolean("auto_enabled", true).apply()
            autoTick(force = true)
            updateNotification()
            emitEvent("mode_changed") {
                put("mode", "auto")
                put("effective", engine.mode.id)
            }
            return true
        }
        val parsed = QuasarMode.fromId(mode) ?: return false
        if (parsed == QuasarMode.AUTO) return false
        if (!modeAllowed(parsed)) return false
        if (parsed == QuasarMode.ANIMATION && !engine.hasIdleAnimation) {
            val animation = animationFromSelection() ?: firstAnimation() ?: return false
            engine.setIdleAnimation(animation)
        }
        autoEnabled = false
        settingsPrefs.edit()
            .putBoolean("auto_enabled", false)
            .putString("last_mode", parsed.id)
            .apply()
        engine.setMode(parsed)
        worker.execute { syncCaptureToMode() }
        updateNotification()
        emitEvent("mode_changed") { put("mode", parsed.id) }
        return true
    }

    private fun modeAllowed(mode: QuasarMode): Boolean = when (mode) {
        QuasarMode.CLOCK -> clockEnabled
        QuasarMode.OSCILLOSCOPE -> visualizerEnabled
        QuasarMode.ANIMATION -> animationEnabled
        else -> true
    }

    /**
     * Auto mode: media playing -> visualiser, silence -> clock. Detection is
     * playback-callback based, never by asking other apps to stop.
     *
     * A candidate mode must persist for a settle delay before it is applied:
     * 1.5 s when music starts (skip UI clicks and short sounds) and 12 s when
     * it stops (survive inter-track gaps and radio ads). [force] from a user
     * action applies immediately.
     */
    private fun autoTick(force: Boolean = false) {
        if (!autoEnabled || !masterEnabled) return
        val now = SystemClock.elapsedRealtime()
        val probe = force || now - lastAutoProbeMs >= 15_000L
        if (probe) {
            lastAutoProbeMs = now
            // Only actual playback counts: a paused player or a loaded media
            // app keeps the clock on screen.
            autoPackages = playback.activePackages
        }
        val playing = playback.currentState() == MediaState.PLAYING
        autoPlaying = playing
        val want = if (playing) {
            when {
                visualizerEnabled -> QuasarMode.OSCILLOSCOPE
                clockEnabled -> QuasarMode.CLOCK
                animationEnabled && engine.hasIdleAnimation -> QuasarMode.ANIMATION
                else -> QuasarMode.OFF
            }
        } else {
            when {
                clockEnabled -> QuasarMode.CLOCK
                animationEnabled && engine.hasIdleAnimation -> QuasarMode.ANIMATION
                visualizerEnabled -> QuasarMode.OSCILLOSCOPE
                else -> QuasarMode.OFF
            }
        }
        if (want == engine.mode) {
            autoPendingMode = null
            return
        }
        val settleMs = when {
            force -> 0L
            want == QuasarMode.OSCILLOSCOPE -> AUTO_MUSIC_SETTLE_MS
            engine.mode == QuasarMode.OSCILLOSCOPE -> AUTO_QUIET_SETTLE_MS
            else -> AUTO_OTHER_SETTLE_MS
        }
        if (autoPendingMode != want) {
            autoPendingMode = want
            autoPendingSinceMs = now
        }
        if (now - autoPendingSinceMs < settleMs) return
        autoPendingMode = null
        applySelectionConfig()
        engine.setMode(want)
        syncCaptureToMode()
        emitEvent("auto_mode") {
            put("playing", playing)
            put("mode", want.id)
            put("packages", JSONArray(autoPackages))
        }
        updateNotification()
    }

    /**
     * The mic is opened only for the visualiser. Clock, animation, test and
     * off modes keep it closed so Alice always has a free microphone and this
     * service never competes with the platform for the capture device.
     *
     * The capture is additionally gated by the user "share mic" switch and by
     * a short pause right after an Alice activation (io.sdk needs a clean mic
     * session for the wake-up).
     */
    private fun syncCaptureToMode() {
        val wantCapture = micShare &&
            !micPassthrough &&
            engine.mode == QuasarMode.OSCILLOSCOPE &&
            System.currentTimeMillis() >= alicePauseUntilMs
        if (wantCapture && !recorder.isRunning) {
            audioInStarted = recorder.start()
            if (audioInStarted) {
                audioRetryDelayMs = 0L
                nextAudioRetryAt = 0L
            }
            emitEvent("audio_in") {
                put("ok", audioInStarted)
                put("source", "visualizer-mix")
                put("error", recorder.lastError ?: "")
            }
        } else if (!wantCapture && recorder.isRunning) {
            runCatching { recorder.stop() }
            audioInStarted = false
            val reason = when {
                !micShare -> "mic_share"
                micPassthrough -> "mic_passthrough"
                engine.mode != QuasarMode.OSCILLOSCOPE -> "mode"
                else -> "alice"
            }
            emitEvent("audio_in") {
                put("ok", false)
                put("source", "visualizer-mix")
                put("reason", reason)
            }
        }
        updateStatusCache()
    }

    override fun freezeSdk(): Boolean {
        if (!rootAvailable) return false
        keepSdkFrozen = true
        val result = root.freezeSdk()
        updateSdkCache()
        emitEvent("sdk_freeze") {
            put("ok", result.ok)
            put("detail", result.message)
            put("pids", JSONArray(sdkPids))
        }
        updateNotification()
        return result.ok
    }

    override fun unfreezeSdk(): Boolean {
        keepSdkFrozen = false
        if (!rootAvailable) return false
        // Alice is about to need the microphone: release it first so the
        // platform can hand the record session to io.sdk.
        runCatching { recorder.stop() }
        audioInStarted = false
        val result = root.unfreezeSdk()
        updateSdkCache()
        emitEvent("sdk_unfreeze") {
            put("ok", result.ok)
            put("detail", result.message)
        }
        updateNotification()
        return result.ok
    }

    override fun playTone(frequencyHz: Double, durationMs: Int): Boolean {
        toneExecutor.execute { runCatching { speaker.playTone(frequencyHz, durationMs) } }
        emitEvent("tone") {
            put("frequency", frequencyHz)
            put("ms", durationMs)
        }
        return true
    }

    override fun setVolume(volume: Float): Boolean {
        val ok = speaker.setVolume(volume)
        emitEvent("volume_changed") { put("value", speaker.volume()) }
        return ok
    }

    override fun setRingColor(rgb: Int): Boolean {
        engine.setRingColor(rgb)
        persistRenderSettings()
        emitEvent("ring_color") { put("color", RendererConfig.hex(engine.ringColor)) }
        return true
    }

    override fun setRingEnabled(enabled: Boolean): Boolean {
        engine.setRingEnabled(enabled)
        persistRenderSettings()
        emitEvent("ring_enabled") { put("enabled", engine.ringEnabled) }
        return true
    }

    override fun setRingSpectrum(enabled: Boolean): Boolean {
        engine.setRingSpectrum(enabled)
        persistRenderSettings()
        emitEvent("ring_spectrum") { put("enabled", engine.ringSpectrum) }
        return true
    }

    override fun setScopeFps(fps: Int): Boolean {
        engine.setScopeFps(fps)
        persistRenderSettings()
        emitEvent("scope_fps") { put("fps", engine.scopeFps) }
        return true
    }

    override fun setScopeSensitivity(value: Float): Boolean {
        engine.setScopeSensitivity(value)
        persistRenderSettings()
        emitEvent("scope_sensitivity") { put("value", engine.scopeSensitivity.toDouble()) }
        return true
    }

    override fun setScopeSmooth(ms: Int): Boolean {
        engine.setScopeSmooth(ms)
        persistRenderSettings()
        emitEvent("scope_smooth") { put("ms", engine.scopeSmoothMs) }
        return true
    }

    override fun setScopeBand(band: String): Boolean {
        val parsed = ScopeBand.fromId(band)
        engine.setScopeBand(parsed)
        persistRenderSettings()
        emitEvent("scope_band") { put("band", parsed.id) }
        return true
    }

    override fun setRingOrigin(index: Int): Boolean {
        engine.setRingOrigin(index)
        persistRenderSettings()
        emitEvent("ring_origin") { put("index", engine.ringOrigin) }
        return true
    }

    override fun setClockSize(size: String): Boolean {
        val parsed = ClockSize.fromId(size)
        engine.setClockSize(parsed)
        persistRenderSettings()
        emitEvent("clock_size") { put("size", parsed.name.lowercase()) }
        return true
    }

    override fun setAnimationColor(rgb: Int): Boolean {
        engine.setAnimationColor(rgb)
        persistRenderSettings()
        emitEvent("animation_color") { put("color", RendererConfig.hex(engine.animationColor)) }
        return true
    }

    override fun playAnimation(id: String, loop: Boolean): Boolean {
        val animation = animations.load(id) ?: return false
        engine.playOverlayAnimation(animation, loop)
        emitEvent("animation") {
            put("id", id)
            put("loop", loop)
            put("frames", animation.frames.size)
            put("duration_ms", GifDecoder.durationMs(animation))
        }
        return true
    }

    override fun setIdleAnimation(id: String?): Boolean {
        val clean = id?.takeIf { it.isNotBlank() }
        val animation = if (clean == null) null else animations.load(clean) ?: return false
        engine.setIdleAnimation(animation)
        settingsPrefs.edit().putString("idle_animation", clean).apply()
        emitEvent("idle_animation") { put("id", clean ?: JSONObject.NULL) }
        return true
    }

    override fun stopAnimation(): Boolean {
        engine.stopOverlayAnimation()
        emitEvent("animation_stop") { put("ok", true) }
        return true
    }

    override fun clearRingOverride(): Boolean {
        engine.clearRingOverride()
        emitEvent("ring_clear") { put("ok", true) }
        return true
    }

    override fun animationsJson(): String {
        val groups = JSONArray()
        for ((group, clips) in animations.catalog()) {
            groups.put(JSONObject().put("group", group).put("clips", JSONArray(clips)))
        }
        return JSONObject()
            .put("ok", true)
            .put("count", animations.ids.size)
            .put("groups", groups)
            .toString()
    }

    // --------------------------------------------------------------- plugins

    override fun pluginsJson(): String {
        val packs = JSONArray()
        for (pack in plugins.packs()) {
            val providers = JSONArray()
            for (provider in pack.providers) providers.put(providerJson(pack, provider))
            packs.put(
                JSONObject()
                    .put("id", pack.id)
                    .put("name", pack.name)
                    .put("version", pack.version)
                    .put("author", pack.author)
                    .put("description", pack.description)
                    .put("builtin", pack.builtin)
                    .put("enabled", plugins.isPackEnabled(pack.id))
                    .put("error", pack.error ?: JSONObject.NULL)
                    .put("providers", providers),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("count", packs.length())
            .put("packs", packs)
            .put("services", servicesJson())
            .toString()
    }

    /**
     * Mods view of the same data: mods contribute items of kind screen / ring
     * / script / service. Screen items additionally carry their slot.
     */
    override fun modsJson(): String {
        val mods = JSONArray()
        for (pack in plugins.packs()) {
            val items = JSONArray()
            for (provider in pack.items) items.put(providerJson(pack, provider))
            val byKind = JSONObject().apply {
                for (kind in ModItemKind.entries) put(kind.id, pack.items(kind).size)
            }
            mods.put(
                JSONObject()
                    .put("id", pack.id)
                    .put("name", pack.name)
                    .put("version", pack.version)
                    .put("author", pack.author)
                    .put("description", pack.description)
                    .put("builtin", pack.builtin)
                    .put("enabled", plugins.isPackEnabled(pack.id))
                    .put("error", pack.error ?: JSONObject.NULL)
                    .put("by_kind", byKind)
                    .put("items", items),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("count", mods.length())
            .put("mods", mods)
            .put("services", servicesJson())
            .toString()
    }

    private fun providerJson(pack: PluginPack, provider: PluginProvider): JSONObject {
        val located = plugins.locate(pack.id, provider.id)
        val values = located?.let { plugins.config(it) } ?: emptyMap()
        val fields = JSONArray()
        for (field in provider.config) {
            val options = JSONArray()
            for (option in field.options) {
                options.put(JSONObject().put("value", option.value).put("label", option.label))
            }
            fields.put(
                JSONObject()
                    .put("key", field.key)
                    .put("label", field.label)
                    .put("kind", field.kind)
                    .put("default", field.default)
                    .put("value", values[field.key] ?: field.default)
                    .put("min", field.min ?: JSONObject.NULL)
                    .put("max", field.max ?: JSONObject.NULL)
                    .put("step", field.step ?: JSONObject.NULL)
                    .put("unit", field.unit ?: JSONObject.NULL)
                    .put("help", field.help ?: JSONObject.NULL)
                    .put("options", options),
            )
        }
        val env = JSONArray()
        for (key in provider.env.keys) env.put(key)
        return JSONObject()
            .put("id", provider.id)
            .put("key", "${pack.id}/${provider.id}")
            .put("pack", pack.id)
            .put("pack_name", pack.name)
            .put("mod", pack.id)
            .put("mod_name", pack.name)
            .put("kind", provider.kind.id)
            .put("slot", provider.slot?.id ?: JSONObject.NULL)
            .put("type", provider.type.id)
            .put("name", provider.name)
            .put("description", provider.description)
            .put("group", provider.group)
            .put("entry", provider.entry ?: JSONObject.NULL)
            .put("resource", provider.resource ?: JSONObject.NULL)
            .put("builtin", provider.builtin ?: JSONObject.NULL)
            .put("events", JSONArray(provider.events))
            .put("config", fields)
            .put("enabled", plugins.isEnabled(pack.id, provider.id))
            .put("interpreter", provider.interpreter ?: JSONObject.NULL)
            .put("restart", provider.restart)
            .put("autostart", provider.autostart)
            .put("env", env)
    }

    // ------------------------------------------------------------- catalog

    override fun catalogJson(): String {
        val root = JSONObject().put("ok", true)
        root.put("clock", catalogArray(ProviderType.CLOCK))
        root.put("visualizer", catalogArray(ProviderType.VISUALIZER))
        root.put("animation", catalogArray(ProviderType.ANIMATION))
        root.put("ring", catalogArray(ProviderType.RING))
        root.put("selection", selectionJson())
        root.put("features", featuresJson())
        return root.toString()
    }

    private fun catalogArray(type: ProviderType): JSONArray {
        val items = JSONArray()
        for (located in plugins.enabledProviders(type)) {
            items.put(providerJson(located.pack, located.provider))
        }
        return items
    }

    private fun selectionJson(): JSONObject = JSONObject()
        .put("clock", selectedClock ?: JSONObject.NULL)
        .put("visualizer", selectedVisualizer ?: JSONObject.NULL)
        .put("animation", selectedAnimation ?: JSONObject.NULL)
        .put("ring", selectedRing ?: JSONObject.NULL)

    private fun featuresJson(): JSONObject = JSONObject()
        .put("clock", clockEnabled)
        .put("visualizer", visualizerEnabled)
        .put("animation", animationEnabled)
        .put("ring", ringPatternsEnabled)

    override fun pluginInstall(zip: ByteArray): String {
        val result = plugins.install(zip)
        if (result.ok) {
            pluginHost.start()
            ensureSelections()
            runCatching { pluginServices.refresh(autostart = true) }
            emitEvent("plugin_installed") {
                put("id", result.id ?: "")
                put("message", result.message)
            }
        }
        return JSONObject()
            .put("ok", result.ok)
            .put("id", result.id ?: JSONObject.NULL)
            .put("message", result.message)
            .put("count", plugins.packs().size)
            .toString()
    }

    override fun pluginRemove(id: String): String {
        val ok = plugins.remove(id)
        if (ok) {
            runCatching { pluginServices.refresh(autostart = false) }
            ensureSelections()
            if (lastRingPatternKey?.startsWith("$id/") == true) clearRingPattern()
            // A handler may have painted a static ring frame; do not leave it
            // frozen after its pack is gone.
            engine.clearRingOverride()
            emitEvent("plugin_removed") { put("id", id) }
            updateStatusCache()
        }
        return JSONObject().put("ok", ok).put("id", id).put("count", plugins.packs().size).toString()
    }

    override fun pluginConfig(providerKey: String, values: Map<String, String>): String {
        val located = plugins.locate(providerKey)
            ?: return JSONObject().put("ok", false).put("error", "провайдер не найден").toString()
        val effective = plugins.saveConfig(located, values)
        if (located.key == selectedClock || located.key == selectedVisualizer ||
            located.key == selectedAnimation
        ) {
            applyPluginConfig(located.provider, effective)
        }
        if (located.provider.type == ProviderType.SERVICE && pluginServices.isRunning(located.key)) {
            pluginServices.restartProvider(located.key)
        }
        emitEvent("plugin_config") {
            put("provider", providerKey)
            put("config", JSONObject(effective as Map<*, *>))
        }
        return JSONObject()
            .put("ok", true)
            .put("provider", providerKey)
            .put("config", JSONObject(effective as Map<*, *>))
            .toString()
    }

    override fun pluginActivate(providerKey: String, loop: Boolean): Boolean {
        val located = plugins.locate(providerKey) ?: return false
        val config = plugins.config(located)
        applyPluginConfig(located.provider, config)
        val provider = located.provider
        val wantsLoop = loop || config["loop"]?.toBoolean() == true
        return when (provider.type) {
            ProviderType.RING -> provider.resource?.let {
                selectedRing = providerKey
                settingsPrefs.edit().putString("selected_ring", providerKey).apply()
                playRingResource(located.pack.id, it, wantsLoop)
            } ?: false
            ProviderType.ANIMATION -> provider.resource?.let {
                playAnimationResource(located.pack.id, it, wantsLoop)
            } ?: false
            ProviderType.CLOCK -> setMode("clock")
            ProviderType.VISUALIZER -> setMode("oscilloscope")
            ProviderType.SERVICE -> pluginServices.startProvider(providerKey)
            ProviderType.SCRIPT -> pluginHost.runOnce(providerKey)
        }
    }

    /**
     * Activates a mod item by its kind: screen items are selected into their
     * slot, ring items start the pattern, services start and scripts run once
     * with a synthetic `manual` event.
     */
    override fun modsActivate(itemKey: String, loop: Boolean): String {
        val located = plugins.locate(itemKey)
            ?: return errorJson("предмет не найден: $itemKey")
        if (!plugins.isEnabled(located.pack.id, located.provider.id)) {
            return errorJson("мод или предмет отключён")
        }
        val kind = located.provider.kind
        val ok = when (kind) {
            ModItemKind.SCREEN -> {
                val slot = located.provider.slot
                    ?: return errorJson("экранный предмет без слота: $itemKey")
                JSONObject(selectProvider(slot.id, itemKey, loop)).optBoolean("ok", false)
            }
            ModItemKind.RING -> JSONObject(selectProvider("ring", itemKey, loop)).optBoolean("ok", false)
            ModItemKind.SERVICE -> pluginServices.startProvider(itemKey)
            ModItemKind.SCRIPT -> pluginHost.runOnce(itemKey)
        }
        if (ok) {
            emitEvent("mod_activate") {
                put("item", itemKey)
                put("kind", kind.id)
            }
            updateStatusCache()
        }
        return JSONObject()
            .put("ok", ok)
            .put("item", itemKey)
            .put("kind", kind.id)
            .put("mode", engine.mode.id)
            .toString()
    }

    // --------------------------------------------------------- screen slots

    override fun selectProvider(type: String, providerKey: String?, loop: Boolean): String {
        val providerType = when (type.trim().lowercase()) {
            "clock" -> ProviderType.CLOCK
            "visualizer", "oscilloscope", "scope" -> ProviderType.VISUALIZER
            "animation", "anim" -> ProviderType.ANIMATION
            "ring", "pattern" -> ProviderType.RING
            else -> return errorJson("type: clock, visualizer, animation или ring")
        }
        if (providerKey.isNullOrBlank()) {
            if (providerType == ProviderType.RING) {
                selectedRing = null
                settingsPrefs.edit().remove("selected_ring").apply()
                clearRingPattern()
            }
            return JSONObject()
                .put("ok", true)
                .put("selection", selectionJson())
                .put("mode", engine.mode.id)
                .toString()
        }
        val located = plugins.locate(providerKey)
            ?: return errorJson("провайдер не найден: $providerKey")
        if (located.provider.type != providerType) {
            return errorJson("${providerKey} не является ${providerType.id}")
        }
        if (!plugins.isEnabled(located.pack.id, located.provider.id)) {
            return errorJson("пак или провайдер отключён")
        }
        if (!featureEnabled(providerType)) {
            return errorJson("виджет отключён в настройках")
        }
        val config = plugins.config(located)
        when (providerType) {
            ProviderType.CLOCK -> {
                selectedClock = providerKey
                settingsPrefs.edit().putString("selected_clock", providerKey).apply()
                applyPluginConfig(located.provider, config)
                if (!autoEnabled) switchTo(QuasarMode.CLOCK) else autoTick(force = true)
            }
            ProviderType.VISUALIZER -> {
                selectedVisualizer = providerKey
                settingsPrefs.edit().putString("selected_visualizer", providerKey).apply()
                applyPluginConfig(located.provider, config)
                if (!autoEnabled) switchTo(QuasarMode.OSCILLOSCOPE) else autoTick(force = true)
            }
            ProviderType.ANIMATION -> {
                if (!engine.hasIdleAnimation || selectedAnimation != providerKey) {
                    val animation = animationFromProvider(located)
                        ?: return errorJson("не удалось загрузить анимацию")
                    engine.setIdleAnimation(animation)
                    located.provider.resource?.let { resource ->
                        settingsPrefs.edit().putString("idle_animation", resourceId(resource)).apply()
                    }
                }
                selectedAnimation = providerKey
                settingsPrefs.edit().putString("selected_animation", providerKey).apply()
                if (!autoEnabled) switchTo(QuasarMode.ANIMATION) else autoTick(force = true)
            }
            ProviderType.RING -> {
                selectedRing = providerKey
                settingsPrefs.edit().putString("selected_ring", providerKey).apply()
                val resource = located.provider.resource
                    ?: return errorJson("у провайдера нет ресурса кольца")
                if (!playRingResource(located.pack.id, resource, loop = true)) {
                    return errorJson("не удалось разобрать шаблон кольца")
                }
            }
            else -> return errorJson("неподдерживаемый тип")
        }
        emitEvent("screen_select") {
            put("type", providerType.id)
            put("provider", providerKey)
        }
        updateStatusCache()
        return JSONObject()
            .put("ok", true)
            .put("selection", selectionJson())
            .put("mode", engine.mode.id)
            .put("auto", autoEnabled)
            .toString()
    }

    private fun featureEnabled(type: ProviderType): Boolean = when (type) {
        ProviderType.CLOCK -> clockEnabled
        ProviderType.VISUALIZER -> visualizerEnabled
        ProviderType.ANIMATION -> animationEnabled
        ProviderType.RING -> ringPatternsEnabled
        else -> true
    }

    private fun switchTo(mode: QuasarMode) {
        autoEnabled = false
        settingsPrefs.edit()
            .putBoolean("auto_enabled", false)
            .putString("last_mode", mode.id)
            .apply()
        engine.setMode(mode)
        worker.execute { syncCaptureToMode() }
        updateNotification()
    }

    private fun resourceId(resource: String): String =
        resource.removePrefix("builtin:").removePrefix("anim/")

    override fun setFeatures(values: Map<String, Boolean>): String {
        var changed = false
        for ((key, value) in values) {
            when (key.lowercase()) {
                "clock" -> {
                    clockEnabled = value
                    settingsPrefs.edit().putBoolean("feature_clock", value).apply()
                    changed = true
                }
                "visualizer", "oscilloscope" -> {
                    visualizerEnabled = value
                    settingsPrefs.edit().putBoolean("feature_visualizer", value).apply()
                    changed = true
                }
                "animation" -> {
                    animationEnabled = value
                    settingsPrefs.edit().putBoolean("feature_animation", value).apply()
                    changed = true
                }
                "ring" -> {
                    ringPatternsEnabled = value
                    settingsPrefs.edit().putBoolean("feature_ring", value).apply()
                    if (!value) {
                        selectedRing = null
                        settingsPrefs.edit().remove("selected_ring").apply()
                        clearRingPattern()
                    }
                    changed = true
                }
            }
        }
        if (!changed) return errorJson("ожидались clock, visualizer, animation или ring")
        if (autoEnabled) {
            autoTick(force = true)
        } else if (!modeAllowed(engine.mode)) {
            switchTo(fallbackMode())
        }
        emitEvent("features") { put("features", featuresJson()) }
        updateStatusCache()
        return JSONObject().put("ok", true).put("features", featuresJson()).toString()
    }

    override fun setPluginEnabled(packId: String?, providerKey: String?, enabled: Boolean): String {
        if (!providerKey.isNullOrBlank()) {
            plugins.setProviderEnabled(providerKey, enabled)
            if (!enabled) dropSelection { it == providerKey }
        } else if (!packId.isNullOrBlank()) {
            plugins.setPackEnabled(packId, enabled)
            if (!enabled) dropSelection { it.startsWith("$packId/") }
        } else {
            return errorJson("нужен pack или provider")
        }
        ensureSelections()
        pluginServices.refresh(autostart = enabled)
        pluginHost.start()
        if (!enabled && !modeAllowed(engine.mode)) switchTo(fallbackMode())
        emitEvent("plugin_toggle") {
            put("pack", packId ?: JSONObject.NULL)
            put("provider", providerKey ?: JSONObject.NULL)
            put("enabled", enabled)
        }
        updateStatusCache()
        return JSONObject()
            .put("ok", true)
            .put("pack", packId ?: JSONObject.NULL)
            .put("provider", providerKey ?: JSONObject.NULL)
            .put("enabled", enabled)
            .toString()
    }

    override fun pluginStart(providerKey: String): Boolean = pluginServices.startProvider(providerKey)

    override fun pluginStop(providerKey: String): Boolean = pluginServices.stopProvider(providerKey)

    // ---------------------------------------------------------------- logs

    override fun logsJson(scope: String, pluginKey: String?, limit: Int): String {
        val count = limit.coerceIn(1, 2_000)
        val plugins = JSONArray()
        for (key in pluginLogs.pluginKeys()) plugins.put(key)
        val lines = JSONArray()
        if (scope.equals("plugin", true) && !pluginKey.isNullOrBlank()) {
            for (line in pluginLogs.tailPlugin(pluginKey, count)) lines.put(line)
        } else {
            for (line in pluginLogs.tailGlobal(count)) lines.put(line)
        }
        return JSONObject()
            .put("ok", true)
            .put("scope", if (scope.equals("plugin", true) && !pluginKey.isNullOrBlank()) "plugin" else "global")
            .put("plugin", pluginKey ?: JSONObject.NULL)
            .put("lines", lines)
            .put("plugins", plugins)
            .toString()
    }

    override fun clearLogs(scope: String, pluginKey: String?): Boolean {
        pluginLogs.clear(scope, pluginKey)
        emitEvent("logs_cleared") {
            put("scope", scope)
            put("plugin", pluginKey ?: JSONObject.NULL)
        }
        return true
    }

    override fun shell(command: String, timeoutMs: Int, fromLoopback: Boolean, token: String?): String {
        if (!fromLoopback && token != apiToken()) {
            return JSONObject().put("ok", false).put("error", "invalid token").toString()
        }
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return errorJson("command is required")
        if (trimmed.length > 4_096) return errorJson("command is too long")
        if (!rootAvailable) return errorJson("root is not available")
        val result = root.runShell(trimmed, timeoutMs.coerceIn(100, 30_000).toLong())
        pluginLogs.global("shell", "exit=${result.exitCode} · ${trimmed.take(300)}")
        return JSONObject()
            .put("ok", result.ok)
            .put("code", result.exitCode)
            .put("timed_out", result.timedOut)
            .put("output", result.output.take(64 * 1024))
            .toString()
    }

    override fun restartPipeline(): Boolean {
        worker.execute {
            emitEvent("pipeline_restart") { put("ok", true) }
            runCatching { restartPipelineNow() }
        }
        return true
    }

    private fun errorJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    override fun clearRingPattern(): Boolean {
        engine.clearRingPattern()
        lastRingPatternKey = null
        emitEvent("ring_pattern") { put("provider", JSONObject.NULL) }
        return true
    }

    private fun playRingResource(packId: String, resource: String, loop: Boolean): Boolean {
        val bytes = plugins.open(packId, resource) ?: return false
        val key = "$packId/$resource"
        val pattern = LedPattern.parse(key, bytes.toString(Charsets.UTF_8)) ?: return false
        engine.setRingPattern(pattern, loop)
        lastRingPatternKey = key
        emitEvent("ring_pattern") {
            put("provider", key)
            put("loop", loop)
            put("frames", pattern.frames.size)
            put("duration_ms", pattern.totalMs)
        }
        return true
    }

    private fun playAnimationResource(packId: String, resource: String, loop: Boolean): Boolean {
        val animation = if (resource.startsWith("builtin:")) {
            animations.load(resource.removePrefix("builtin:"))
        } else {
            val bytes = plugins.open(packId, resource) ?: return false
            animations.loadBytes("$packId/$resource", bytes)
        } ?: return false
        engine.playOverlayAnimation(animation, loop)
        emitEvent("animation") {
            put("id", resource)
            put("loop", loop)
            put("frames", animation.frames.size)
            put("duration_ms", GifDecoder.durationMs(animation))
        }
        return true
    }

    private fun setRingColors(colors: IntArray): Boolean {
        val frame = RingFrame(RingFrame.DEFAULT_SIZE)
        for (i in frame.pixels.indices) frame.pixels[i] = colors.getOrElse(i) { 0 } and 0xFFFFFF
        val ok = engine.drawCustomRing(frame)
        if (ok) emitEvent("ring_custom") { put("size", frame.size) }
        return ok
    }

    private fun applyPluginConfig(provider: PluginProvider, config: Map<String, String>) {
        if (provider.type == ProviderType.VISUALIZER) {
            SpectrogramStyle.fromBuiltin(provider.builtin)?.let { engine.setScopeStyle(it) }
        }
        config["clock_size"]?.let { setClockSize(it) }
        config["band"]?.let { setScopeBand(it) }
        config["fps"]?.toIntOrNull()?.let { setScopeFps(it) }
        config["sensitivity"]?.toFloatOrNull()?.let { setScopeSensitivity(it) }
        config["ring_spectrum"]?.let { setRingSpectrum(it.toBoolean()) }
        config["color"]?.let { RendererConfig.parseColor(it)?.let { color -> setRingColor(color) } }
        config["animation_color"]?.let {
            RendererConfig.parseColor(it)?.let { color -> setAnimationColor(color) }
        }
    }

    private val pluginActions = object : PluginHost.Actions {
        override fun playRingResource(packId: String, resource: String, loop: Boolean): Boolean =
            this@QuasarService.playRingResource(packId, resource, loop)

        override fun playAnimationResource(packId: String, resource: String, loop: Boolean): Boolean =
            this@QuasarService.playAnimationResource(packId, resource, loop)

        override fun setRingColors(colors: IntArray): Boolean =
            this@QuasarService.setRingColors(colors)

        override fun setMode(mode: String): Boolean = this@QuasarService.setMode(mode)

        override fun notify(message: String) {
            emitEvent("plugin_notify") { put("message", message) }
        }
    }

    override fun setCaptureRate(rate: Int): Boolean {
        // The Visualizer effect reports the mixer rate (usually 48 kHz) and the
        // pipeline decimates it to a fixed 16 kHz. The old 48 kHz AudioRecord
        // mode is gone: it was the main trigger of the AudioPolicyService
        // crash, and no direct ALSA access is used anymore.
        val normalized = RendererConfig.DEFAULT_CAPTURE_RATE
        RendererConfig.saveCaptureRate(this, normalized)
        emitEvent("capture_rate") {
            put("rate", recorder.captureRate)
            put("ok", recorder.isRunning)
            put("error", recorder.lastError ?: "")
        }
        return true
    }

    override fun setAutostartEnabled(enabled: Boolean): Boolean {
        autostartEnabled = enabled
        val result = when {
            !rootAvailable -> RootManager.InstallResult(false, "root unavailable")
            enabled -> root.installAutostart()
            else -> root.removeAutostart()
        }
        autostartInstalled = runCatching { root.isAutostartInstalled() }.getOrDefault(false)
        emitEvent("autostart") {
            put("enabled", enabled)
            put("ok", result.ok)
            put("installed", autostartInstalled)
            put("detail", result.output.takeLast(500))
        }
        updateStatusCache()
        return result.ok
    }

    private fun persistRenderSettings() {
        RendererConfig.saveRuntime(
            this,
            RendererConfig.RuntimeSettings(
                ringColor = engine.ringColor,
                ringEnabled = engine.ringEnabled,
                ringSpectrum = engine.ringSpectrum,
                scopeFps = engine.scopeFps,
                scopeSensitivity = engine.scopeSensitivity,
                scopeSmooth = engine.scopeSmoothMs,
                clockSize = engine.clockSize,
                idleAnimation = settingsPrefs.getString("idle_animation", null),
                animationColor = engine.animationColor,
                ringOrigin = engine.ringOrigin,
                scopeBand = engine.scopeBand.id,
            ),
        )
    }

    override fun setIconVisible(visible: Boolean): Boolean {
        return try {
            val component = ComponentName(this, LAUNCHER_ALIAS)
            val state = if (visible) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            emitEvent("icon") { put("visible", visible) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "cannot change launcher icon state", e)
            false
        }
    }

    // --------------------------------------------------- general panel settings

    override fun generalJson(): String {
        return JSONObject()
            .put("ok", true)
            .put("master", masterEnabled)
            .put("screen", engine.screenEnabled)
            .put("mic_share", micShare)
            .put("mic_passthrough", micPassthrough)
            .put(
                "ring_volume",
                JSONObject()
                    .put("enabled", settingsPrefs.getBoolean("ring_volume_enabled", true))
                    .put("step", settingsPrefs.getInt("ring_volume_step", 1))
                    .put("anim", settingsPrefs.getBoolean("ring_volume_anim", true)),
            )
            .put(
                "media_anim",
                if (::mediaAnimations.isInitialized) JSONObject(mediaAnimations.toJson()) else JSONObject(),
            )
            .put("input", JSONObject().put("started", inputStarted))
            .put("alice_available", rootAvailable)
            .toString()
    }

    override fun mediaAnimJson(): String =
        if (::mediaAnimations.isInitialized) {
            mediaAnimations.toJson()
        } else {
            JSONObject().put("ok", true).put("enabled", false).toString()
        }

    override fun setMaster(enabled: Boolean): Boolean {
        if (masterEnabled == enabled) {
            updateStatusCache()
            return true
        }
        masterEnabled = enabled
        settingsPrefs.edit().putBoolean("master_enabled", enabled).apply()
        if (enabled) {
            val restore = settingsPrefs.getString("master_prev_mode", null)
            settingsPrefs.edit().remove("master_prev_mode").apply()
            if (autoEnabled) {
                autoTick(force = true)
            } else {
                val target = QuasarMode.fromId(restore ?: "") ?: fallbackMode()
                setMode(target.id)
            }
        } else {
            if (engine.mode != QuasarMode.OFF) {
                // Remember the mode to restore; engine-level OFF keeps the
                // service-side last_mode untouched.
                settingsPrefs.edit().putString("master_prev_mode", engine.mode.id).apply()
            }
            engine.setMode(QuasarMode.OFF)
            syncCaptureToMode()
        }
        if (::mediaAnimations.isInitialized) mediaAnimations.setMasterEnabled(enabled)
        emitEvent("master") { put("enabled", enabled) }
        emitGeneralEvent()
        updateNotification()
        updateStatusCache()
        return true
    }

    override fun setScreen(enabled: Boolean): Boolean {
        engine.setScreenEnabled(enabled)
        settingsPrefs.edit().putBoolean("screen_enabled", enabled).apply()
        emitEvent("screen_enabled") { put("enabled", enabled) }
        emitGeneralEvent()
        updateStatusCache()
        return true
    }

    private fun persistScreenEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("screen_enabled", enabled).apply()
        emitGeneralEvent()
    }

    private fun persistRingEnabled(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("ring_enabled", enabled).apply()
        persistRenderSettings()
        emitGeneralEvent()
    }

    override fun setMicShare(enabled: Boolean): Boolean {
        micShare = enabled
        settingsPrefs.edit().putBoolean("mic_share", enabled).apply()
        syncCaptureToMode()
        emitEvent("mic_share") { put("enabled", enabled) }
        emitGeneralEvent()
        return true
    }

    /**
     * Hands the microphone to system apps by denying io.sdk RECORD_AUDIO and
     * restarting it, which drops its open capture session. Older io.sdk builds
     * keep the mic even after the app-op changes, so the kill is required.
     */
    override fun setMicPassthrough(enabled: Boolean): Boolean {
        if (micPassthrough == enabled) return true
        micPassthrough = enabled
        settingsPrefs.edit().putBoolean("mic_passthrough", enabled).apply()
        if (enabled) {
            runCatching { recorder.stop() }
            audioInStarted = false
        }
        var ok = true
        if (rootAvailable) {
            val result = if (enabled) root.denySdkMicAccess() else root.allowSdkMicAccess()
            ok = result.ok
            updateSdkCache()
            settingsPrefs.edit().putBoolean("mic_passthrough_applied", enabled).apply()
            emitEvent("mic_passthrough") {
                put("enabled", enabled)
                put("ok", result.ok)
                put("detail", result.message.take(300))
            }
        } else {
            ok = !enabled
        }
        if (enabled && !ok) {
            micPassthrough = false
            settingsPrefs.edit().putBoolean("mic_passthrough", false).apply()
        } else {
            syncCaptureToMode()
        }
        emitGeneralEvent()
        updateStatusCache()
        return ok
    }

    /** Re-applies the persisted passthrough state after a service start. */
    private fun applyStoredMicPassthrough() {
        if (!rootAvailable) return
        val applied = settingsPrefs.getBoolean("mic_passthrough_applied", false)
        when {
            micPassthrough -> {
                val result = root.denySdkMicAccess()
                settingsPrefs.edit().putBoolean("mic_passthrough_applied", result.ok).apply()
                emitEvent("mic_passthrough") {
                    put("enabled", true)
                    put("ok", result.ok)
                    put("detail", result.message.take(300))
                }
            }
            applied -> {
                // Crash recovery: never leave io.sdk without its mic.
                val result = root.allowSdkMicAccess()
                settingsPrefs.edit().putBoolean("mic_passthrough_applied", false).apply()
                emitEvent("mic_passthrough") {
                    put("enabled", false)
                    put("ok", result.ok)
                    put("detail", result.message.take(300))
                }
            }
        }
        updateSdkCache()
    }

    /** One SSE line for the web General section (it refreshes on this event). */
    private fun emitGeneralEvent() {
        emitEvent("general") {
            put("master", masterEnabled)
            put("screen", engine.screenEnabled)
            put("mic_share", micShare)
            put("mic_passthrough", micPassthrough)
            put("ring_volume_enabled", settingsPrefs.getBoolean("ring_volume_enabled", true))
            put("ring_volume_step", settingsPrefs.getInt("ring_volume_step", 1))
            put("ring_volume_anim", settingsPrefs.getBoolean("ring_volume_anim", true))
        }
    }

    override fun setRingVolume(enabled: Boolean?, step: Int?, anim: Boolean?): Boolean {
        val edit = settingsPrefs.edit()
        enabled?.let { edit.putBoolean("ring_volume_enabled", it) }
        step?.let { edit.putInt("ring_volume_step", it.coerceIn(1, 5)) }
        anim?.let { edit.putBoolean("ring_volume_anim", it) }
        edit.apply()
        emitEvent("ring_volume") {
            put("enabled", settingsPrefs.getBoolean("ring_volume_enabled", true))
            put("step", settingsPrefs.getInt("ring_volume_step", 1))
            put("anim", settingsPrefs.getBoolean("ring_volume_anim", true))
        }
        emitGeneralEvent()
        updateStatusCache()
        return true
    }

    // ------------------------------------------ media animations + key remapping

    override fun setMediaAnimation(enabled: Boolean?, play: String?, pause: String?, stop: String?): Boolean {
        if (!::mediaAnimations.isInitialized) return false
        mediaAnimations.updateConfig(enabled, play, pause, stop)
        emitEvent("media_anim_config") {
            put("config", JSONObject(mediaAnimations.toJson()))
        }
        updateStatusCache()
        return true
    }

    override fun remapJson(): String {
        if (!::inputActions.isInitialized) {
            return JSONObject()
                .put("ok", true)
                .put("bindings", JSONArray())
                .put("actions", JSONArray())
                .toString()
        }
        val base = JSONObject(inputActions.bindingsJson())
        val actions = base.getJSONArray("actions")
        for (action in dynamicRemapActions()) actions.put(action)
        return base.toString()
    }

    override fun setRemapBinding(key: String, action: String): Boolean {
        if (!::inputActions.isInitialized) return false
        val inputKey = InputKey.entries.firstOrNull { it.name == key && it != InputKey.UNKNOWN } ?: return false
        inputActions.setBinding(inputKey, action)
        emitEvent("remap") {
            put("key", key)
            put("action", action)
        }
        updateStatusCache()
        return true
    }

    override fun resetRemapBindings(): Boolean {
        if (!::inputActions.isInitialized) return false
        inputActions.resetBindings()
        emitEvent("remap_reset") { }
        updateStatusCache()
        return true
    }

    override fun activateAlice(): Boolean {
        if (!rootAvailable) {
            emitEvent("alice_activated") {
                put("ok", false)
                put("error", "root unavailable")
            }
            return false
        }
        val woke = onAliceActivated()
        if (woke) {
            worker.execute { runCatching { root.runShell("input keyevent 219", 5_000L) } }
        }
        emitEvent("alice_activated") {
            put("ok", true)
            put("woke_sdk", woke)
            put("source", "web")
        }
        return true
    }

    /**
     * Shared Alice wake-up: unfreezes io.sdk when the user froze it, pauses
     * the visualiser capture for a moment and reports whether io.sdk had to
     * be woken (true means the system never saw the physical button).
     */
    private fun onAliceActivated(): Boolean {
        val wasFrozen = keepSdkFrozen
        if (wasFrozen) worker.execute { runCatching { unfreezeSdk() } }
        alicePauseUntilMs = System.currentTimeMillis() + ALICE_CAPTURE_PAUSE_MS
        if (recorder.isRunning) {
            syncCaptureToMode()
            scheduler.schedule(
                { runCatching { syncCaptureToMode() } },
                ALICE_CAPTURE_PAUSE_MS + 1_500,
                TimeUnit.MILLISECONDS,
            )
        }
        return wasFrozen
    }

    /** Script and animation entries that extend the static action list. */
    private fun dynamicRemapActions(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        if (!::plugins.isInitialized || !::animations.isInitialized) return out
        for (pack in plugins.packs()) {
            for (provider in pack.items(ModItemKind.SCRIPT)) {
                out.add(
                    JSONObject()
                        .put("id", "${InputActions.ACTION_SCRIPT}:${pack.id}/${provider.id}")
                        .put("title", "Скрипт: ${provider.name}"),
                )
            }
        }
        for (id in animations.ids) {
            out.add(
                JSONObject()
                    .put("id", "${InputActions.ACTION_ANIM}:$id")
                    .put("title", "Анимация: ${id.removeSuffix(".gif")}"),
            )
        }
        return out
    }

    /** [InputActions.Host] implementation; created once in [onCreate]. */
    private val inputActionsHost = object : InputActions.Host {
        override val engine: QuasarEngine get() = this@QuasarService.engine
        override val animations: AnimationStore get() = this@QuasarService.animations
        override val rootAvailable: Boolean get() = this@QuasarService.rootAvailable
        override val ringColorRgb: Int get() = engine.ringColor

        override fun execSu(command: String, timeoutMs: Long): Boolean =
            root.runShell(command, timeoutMs).ok

        override fun setMode(mode: String): Boolean = this@QuasarService.setMode(mode)

        override fun runScript(key: String): Boolean =
            runCatching { pluginHost.runOnce(key) }.getOrDefault(false)

        override fun onAliceActivated(): Boolean = this@QuasarService.onAliceActivated()

        override fun persistScreenEnabled(enabled: Boolean) =
            this@QuasarService.persistScreenEnabled(enabled)

        override fun persistRingEnabled(enabled: Boolean) =
            this@QuasarService.persistRingEnabled(enabled)

        override fun emitEvent(name: String, body: (JSONObject) -> Unit) =
            this@QuasarService.emitEvent(name, body)
    }

    override fun micPcm(maxSamples: Int): ShortArray = mic.snapshot(maxSamples.coerceIn(16, 80_000))

    override fun micRms(): Double = mic.rms()

    override fun addEventListener(listener: (String) -> Unit): AutoCloseable = events.add(listener)

    override fun recentEvents(): List<String> = events.recent()

    override fun isRunning(): Boolean = true

    // -------------------------------------------------------------- helpers

    private fun updateStatusCache() {
        runCatching { statusJson() }
    }

    private fun emitEvent(name: String, block: JSONObject.() -> Unit = {}) {
        val json = JSONObject()
            .put("event", name)
            .put("ts", System.currentTimeMillis())
        json.block()
        val text = json.toString()
        if (::pluginLogs.isInitialized) runCatching { pluginLogs.event(text) }
        events.emit(text)
    }

    private fun localIp(): String {
        return try {
            for (nic in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in Collections.list(nic.inetAddresses)) {
                    if (address is Inet4Address && address.isSiteLocalAddress) {
                        return address.hostAddress ?: continue
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_quasar)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
    }

    private fun updateNotification() {
        if (!serviceRunning) return
        val sdkText = when {
            !rootAvailable -> "io.sdk: no root"
            sdkFrozen -> "io.sdk: STOP"
            sdkRunning -> "io.sdk: RUN"
            else -> "io.sdk: off"
        }
        val text = if (autoEnabled) {
            "$sdkText · Авто → ${engine.mode.title} · :$HTTP_PORT"
        } else {
            "$sdkText · ${engine.mode.title} · :$HTTP_PORT"
        }
        runCatching {
            (getSystemService(NotificationManager::class.java))
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    companion object {
        private const val TAG = "QuasarService"
        private const val CHANNEL_ID = "quasar_openio_service"
        private const val NOTIFICATION_ID = 0x51A5
        const val HTTP_PORT = 8080
        const val IPC_PORT = 8081
        const val PCM_PORT = 8082
        const val LAUNCHER_ALIAS = "quasar.rubcut.openio.Launcher"

        const val ACTION_START = "quasar.rubcut.openio.action.START"
        const val ACTION_STOP = "quasar.rubcut.openio.action.STOP"
        const val ACTION_FREEZE = "quasar.rubcut.openio.action.FREEZE"
        const val ACTION_UNFREEZE = "quasar.rubcut.openio.action.UNFREEZE"
        const val ACTION_RESTART_PIPELINE = "quasar.rubcut.openio.action.RESTART_PIPELINE"

        // Auto-mode hysteresis (see [autoTick]).
        private const val AUTO_MUSIC_SETTLE_MS = 1_500L
        private const val AUTO_QUIET_SETTLE_MS = 12_000L
        private const val AUTO_OTHER_SETTLE_MS = 3_000L

        /** Capture pause after an Alice activation so io.sdk gets a clean mic. */
        private const val ALICE_CAPTURE_PAUSE_MS = 12_000L

        @Volatile
        var serviceRunning: Boolean = false
            private set

        @Volatile
        var instance: QuasarService? = null
            private set

        fun lastStatus(): String = instance?.lastStatusJson ?: "{}"

        fun start(context: android.content.Context) {
            val intent = Intent(context, QuasarService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
