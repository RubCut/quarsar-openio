package quasar.rubcut.openio.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import quasar.rubcut.openio.data.anim.AnimationStore
import quasar.rubcut.openio.domain.QuasarEngine
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * The stock-io behaviour the app never had: short overlay animations that
 * react to media state transitions. When music starts, pauses or stops, a
 * bundled clip from the original io (`media/25x16_Resume.gif`,
 * `media/25x16_Pause.gif`, …) plays once over whatever the panel currently
 * shows and then disappears. Every event is individually configurable or
 * switchable off; the whole system has its own master switch and is disabled
 * by default.
 *
 * State transitions come from [PlaybackMonitor] (2 s poll + playback
 * callbacks). A short settle delay filters UI click sounds from real
 * play/pause transitions.
 */
class MediaAnimations(
    context: Context,
    private val engine: QuasarEngine,
    private val animations: AnimationStore,
    private val prefs: SharedPreferences,
    private val worker: Executor,
    private val emitEvent: (String, (org.json.JSONObject) -> Unit) -> Unit,
) {
    data class Config(
        val enabled: Boolean,
        val play: String?,
        val pause: String?,
        val stop: String?,
    )

    private var masterEnabled: () -> Boolean = { true }
    private val pendingState = AtomicReference<MediaState?>(null)
    private var pendingSinceMs = 0L
    private var lastFiredState: MediaState? = null

    var config: Config = loadConfig()
        private set

    fun setMasterEnabled(enabled: Boolean) {
        masterEnabled = { enabled }
    }

    fun updateConfig(enabled: Boolean? = null, play: String? = null, pause: String? = null, stop: String? = null) {
        val next = Config(
            enabled = enabled ?: config.enabled,
            play = play?.takeIf { it.isNotBlank() } ?: (play ?: config.play),
            pause = pause?.takeIf { it.isNotBlank() } ?: (pause ?: config.pause),
            stop = stop?.takeIf { it.isNotBlank() } ?: (stop ?: config.stop),
        )
        config = next
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_PLAY, next.play)
            .putString(KEY_PAUSE, next.pause)
            .putString(KEY_STOP, next.stop)
            .apply()
        if (!next.enabled) {
            pendingState.set(null)
            lastFiredState = null
        }
    }

    private fun loadConfig(): Config = Config(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        play = prefs.getString(KEY_PLAY, DEFAULT_PLAY),
        pause = prefs.getString(KEY_PAUSE, DEFAULT_PAUSE),
        stop = prefs.getString(KEY_STOP, null),
    )

    /**
     * Called ~every second by the scheduler. Reads the fresh media state and
     * fires the configured overlay after the settle delay. Cheap when the
     * system is off.
     */
    fun poll() {
        if (!config.enabled || !masterEnabled()) return
        val state = playbackState?.invoke() ?: return
        if (state == lastFiredState && pendingState.get() == null) return
        val now = System.currentTimeMillis()
        val settleMs = when (state) {
            MediaState.PLAYING -> PLAY_SETTLE_MS
            MediaState.PAUSED -> PAUSE_SETTLE_MS
            MediaState.STOPPED -> STOP_SETTLE_MS
        }
        if (pendingState.get() != state) {
            pendingState.set(state)
            pendingSinceMs = now
        }
        if (now - pendingSinceMs < settleMs) return
        pendingState.set(null)
        lastFiredState = state
        val id = when (state) {
            MediaState.PLAYING -> config.play
            MediaState.PAUSED -> config.pause
            MediaState.STOPPED -> config.stop
        }
        val clip = id?.takeIf { it.isNotBlank() } ?: return
        worker.execute {
            try {
                val animation = animations.load(clip) ?: return@execute
                engine.playOverlayAnimation(animation, loop = false)
                emitEvent("media_anim") {
                    it.put("state", state.name.lowercase())
                    it.put("id", clip)
                }
            } catch (e: Exception) {
                Log.w(TAG, "media animation ${clip}: ${e.message}")
            }
        }
    }

    /** Wired by the service so the controller does not own the monitor. */
    var playbackState: (() -> MediaState?)? = null

    fun toJson(): String {
        val value = org.json.JSONObject()
            .put("enabled", config.enabled)
            .put("play", config.play ?: org.json.JSONObject.NULL)
            .put("pause", config.pause ?: org.json.JSONObject.NULL)
            .put("stop", config.stop ?: org.json.JSONObject.NULL)
        return value.toString()
    }

    companion object {
        private const val TAG = "QuasarMediaAnim"
        private const val KEY_ENABLED = "media_anim_enabled"
        private const val KEY_PLAY = "media_anim_play"
        private const val KEY_PAUSE = "media_anim_pause"
        private const val KEY_STOP = "media_anim_stop"

        /** Stock io clips that correspond to resume / pause transitions. */
        const val DEFAULT_PLAY = "media/25x16_Resume.gif"
        const val DEFAULT_PAUSE = "media/25x16_Pause.gif"

        private const val PLAY_SETTLE_MS = 800L
        private const val PAUSE_SETTLE_MS = 800L
        private const val STOP_SETTLE_MS = 3000L
    }
}
