package quasar.rubcut.openio.service

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Coarse media playback state derived from the audio playback configurations. */
enum class MediaState { PLAYING, PAUSED, STOPPED }

/**
 * Detects whether any media app is actually playing.
 *
 * `AudioManager.isMusicActive()` alone misses some TV apps that use a remote
 * playback configuration, so this class also subscribes to
 * [AudioManager.AudioPlaybackCallback] and maps the playing client UIDs back to
 * package names. The original Yandex Music app shows up as
 * `com.yandex.tv.music` in [activePackages].
 *
 * Output is used only for the auto mode and the media-event animations; the
 * service never stops or pauses another app.
 */
class PlaybackMonitor(private val context: Context) {
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    @Volatile
    var playing: Boolean = false
        private set

    @Volatile
    var activePackages: List<String> = emptyList()
        private set

    /** Packages that have a registered (possibly paused) playback configuration. */
    @Volatile
    var presentPackages: List<String> = emptyList()
        private set

    @Volatile
    var registered: Boolean = false
        private set

    /** Called on the main thread when the playing/not-playing state changes. */
    @Volatile
    var listener: ((Boolean) -> Unit)? = null

    /** Called on the main thread when the coarse media state changes. */
    @Volatile
    var stateListener: ((MediaState) -> Unit)? = null

    @Volatile
    var state: MediaState = MediaState.STOPPED
        private set

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            update(configs)
        }
    }

    fun start(): Boolean {
        val manager = audio ?: return false
        return runCatching {
            update(manager.activePlaybackConfigurations)
            manager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
            registered = true
            true
        }.onFailure { Log.w(TAG, "cannot register playback callback: ${it.message}") }
            .getOrDefault(false)
    }

    fun stop() {
        val manager = audio ?: return
        runCatching { manager.unregisterAudioPlaybackCallback(callback) }
        registered = false
    }

    /** Cheap, thread-safe probe used by the auto-mode scheduler. */
    fun isMusicPlaying(): Boolean {
        val musicActive = runCatching { audio?.isMusicActive == true }.getOrDefault(false)
        return musicActive || playing
    }

    /**
     * Fresh state probe: active configs that are started mean PLAYING, configs
     * that exist but are silent mean PAUSED (the player object stays registered
     * on this firmware), and no configs at all mean STOPPED. Reflection into
     * `getPlayerState()` is blocked on this firmware, so [isMusicActive] is the
     * tie-breaker between PAUSED and PLAYING.
     */
    fun currentState(): MediaState {
        val manager = audio ?: return MediaState.STOPPED
        val configs = runCatching { manager.activePlaybackConfigurations }.getOrDefault(emptyList())
        val musicActive = runCatching { manager.isMusicActive }.getOrDefault(false)
        return when {
            musicActive -> MediaState.PLAYING
            configs.isNotEmpty() -> MediaState.PAUSED
            else -> MediaState.STOPPED
        }
    }

    private fun update(configs: List<AudioPlaybackConfiguration>) {
        val started = configs.filter { isStarted(it) }
        val packages = LinkedHashSet<String>()
        for (config in started) {
            val uid = clientUid(config)
            if (uid <= 0) continue
            val resolved = runCatching { context.packageManager.getNameForUid(uid) }.getOrNull()
            val name = resolved ?: continue
            if (name == context.packageName || name == "android") continue
            packages.add(name)
        }
        val present = LinkedHashSet<String>()
        for (config in configs) {
            val uid = clientUid(config)
            if (uid <= 0) continue
            val resolved = runCatching { context.packageManager.getNameForUid(uid) }.getOrNull() ?: continue
            if (resolved == context.packageName || resolved == "android") continue
            present.add(resolved)
        }
        val was = playing
        val wasState = state
        playing = started.isNotEmpty()
        activePackages = packages.toList().sorted()
        presentPackages = present.toList().sorted()
        state = when {
            playing -> MediaState.PLAYING
            // Reflection is blocked on this firmware: isMusicActive() is the
            // only reliable "actually playing" signal, so consult it here too.
            runCatching { audio?.isMusicActive == true }.getOrDefault(false) -> MediaState.PLAYING
            configs.isNotEmpty() -> MediaState.PAUSED
            else -> MediaState.STOPPED
        }
        if (was != playing) listener?.invoke(playing)
        if (wasState != state) stateListener?.invoke(state)
    }

    /**
     * `AudioPlaybackConfiguration.getPlayerState()` is marked hidden in the
     * public SDK, so it is read reflectively. If the platform blocks the call
     * the auto mode still falls back to `AudioManager.isMusicActive()`.
     */
    private fun isStarted(config: AudioPlaybackConfiguration): Boolean = runCatching {
        val method = config.javaClass.getMethod("getPlayerState")
        (method.invoke(config) as Int) == PLAYER_STATE_STARTED
    }.getOrDefault(false)

    private fun clientUid(config: AudioPlaybackConfiguration): Int = runCatching {
        val method = config.javaClass.getMethod("getClientUid")
        method.invoke(config) as Int
    }.getOrDefault(-1)

    companion object {
        private const val TAG = "QuasarPlayback"
        private const val PLAYER_STATE_STARTED = 2
    }
}