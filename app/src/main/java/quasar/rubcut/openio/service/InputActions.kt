package quasar.rubcut.openio.service

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import quasar.rubcut.openio.data.anim.AnimationStore
import quasar.rubcut.openio.data.input.InputMapper
import quasar.rubcut.openio.domain.QuasarEngine
import quasar.rubcut.openio.domain.model.InputEvent
import quasar.rubcut.openio.domain.model.InputKey

/**
 * Hardware-key actions: the rotary volume encoder on the station, the on-device
 * buttons and the remote keys. Every [InputKey] can be bound to an action;
 * bindings live in the `quasar_settings` prefs as a JSON object
 * (`{"ALICE":"alice","REMOTE_PLAY_PAUSE":"play_pause", ...}`); a key missing
 * from the object uses its default.
 *
 * Volume actions change the system media volume ([AudioManager.setStreamVolume]
 * first, root `input keyevent 24/25` fallback) and reproduce the stock io
 * feedback: a short volume clip on the screen (`rcu_volume/...`) plus an arc of
 * lit LEDs on the ring that clears itself after the user stops turning.
 */
class InputActions(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val host: Host,
) {
    interface Host {
        val engine: QuasarEngine
        val animations: AnimationStore
        val rootAvailable: Boolean
        val ringColorRgb: Int
        fun execSu(command: String, timeoutMs: Long = 5_000): Boolean
        fun setMode(mode: String): Boolean
        fun runScript(key: String): Boolean

        /** Called for the alice action; returns true when io.sdk was frozen. */
        fun onAliceActivated(): Boolean
        fun persistScreenEnabled(enabled: Boolean)
        fun persistRingEnabled(enabled: Boolean)
        fun emitEvent(name: String, body: (JSONObject) -> Unit)
    }

    data class Binding(val key: InputKey, val title: String, val device: String, val action: String)

    /** Timestamp of the last applied root volume keyevent (shell rate limit). */
    private var lastVolumeApplyMs = 0L

    /** Timestamp of the last ring-arc feedback; [tick] clears it after idle. */
    private var lastVolumeFeedbackMs = 0L

    /** Cached last applied volume percent (0..100) for the shell-only fallback. */
    private var cachedVolumePercent = -1

    fun bindings(): List<Binding> {
        val stored = parseBindings(prefs.getString(KEY_BINDINGS, null))
        return keysInOrder.map { key ->
            Binding(
                key = key,
                title = keyTitles[key] ?: key.name,
                device = keyDevices[key] ?: "",
                action = stored[key] ?: defaultBinding(key),
            )
        }
    }

    fun setBinding(key: InputKey, action: String) {
        val stored = parseBindings(prefs.getString(KEY_BINDINGS, null)).toMutableMap()
        val valid = action == ACTION_NONE || knownActions.containsKey(action) ||
            action.startsWith("$ACTION_SCRIPT:") || action.startsWith("$ACTION_ANIM:")
        if (!valid) return
        val normalized = action.takeIf { it != defaultBinding(key) }
        if (normalized == null) stored.remove(key) else stored[key] = normalized
        prefs.edit().putString(KEY_BINDINGS, JSONObject(stored as Map<*, *>).toString()).apply()
    }

    fun resetBindings() {
        prefs.edit().remove(KEY_BINDINGS).apply()
    }

    /** Dispatches one mapped input event to its bound action. */
    fun onKey(event: InputEvent) {
        val action = parseBindings(prefs.getString(KEY_BINDINGS, null))[event.key]
            ?: defaultBinding(event.key)
        if (action == ACTION_NONE || action == ACTION_MUTE) return
        if ((action == ACTION_VOLUME_UP || action == ACTION_VOLUME_DOWN) &&
            !prefs.getBoolean(KEY_VOLUME_ENABLED, true)
        ) {
            return
        }
        try {
            apply(event, action)
        } catch (e: Exception) {
            Log.w(TAG, "action $action failed: ${e.message}")
        }
    }

    /** Scheduler hook: clears the volume ring arc after the idle timeout. */
    fun tick() {
        if (lastVolumeFeedbackMs == 0L) return
        if (System.currentTimeMillis() - lastVolumeFeedbackMs < VOLUME_IDLE_MS) return
        lastVolumeFeedbackMs = 0L
        runCatching { host.engine.clearRingOverride() }
    }

    private fun apply(event: InputEvent, action: String) {
        when {
            action == ACTION_VOLUME_UP -> changeVolume(event, +1)
            action == ACTION_VOLUME_DOWN -> changeVolume(event, -1)
            action == ACTION_PLAY_PAUSE -> mediaKey(85)
            action == ACTION_NEXT -> mediaKey(87)
            action == ACTION_PREVIOUS -> mediaKey(88)
            action == ACTION_STOP_MEDIA -> mediaKey(86)
            action == ACTION_ALICE -> activateAlice()
            action == ACTION_TOGGLE_SCREEN -> toggleScreen()
            action == ACTION_TOGGLE_RING -> toggleRing()
            action == ACTION_CLOCK -> host.setMode("clock")
            action == ACTION_VISUALIZER -> host.setMode("oscilloscope")
            action == ACTION_ANIMATION -> host.setMode("animation")
            action.startsWith("$ACTION_SCRIPT:") -> host.runScript(action.removePrefix("$ACTION_SCRIPT:"))
            action.startsWith("$ACTION_ANIM:") -> playClip(action.removePrefix("$ACTION_ANIM:"))
        }
    }

    // ------------------------------------------------------------------ volume

    private fun audioManager(): AudioManager? = context.getSystemService(AudioManager::class.java)

    /** Levels added per detent click (1..5), user-configurable. */
    private fun volumeStep(): Int = prefs.getInt(KEY_VOLUME_STEP, 1).coerceIn(1, 5)

    private fun changeVolume(event: InputEvent, direction: Int) {
        // The rotary reports EV_REL value = detents moved (1..5 on a fast
        // spin); plain key presses report value = 1. Both scale by the
        // configured step.
        val detents = when (event.type) {
            InputMapper.EV_REL -> event.value.coerceIn(-5, 5)
            else -> direction
        }
        val delta = detents * volumeStep()
        val now = System.currentTimeMillis()
        val manager = audioManager()
        var percent = -1
        if (manager != null) {
            runCatching {
                val stream = AudioManager.STREAM_MUSIC
                val max = manager.getStreamMaxVolume(stream)
                val current = manager.getStreamVolume(stream)
                val next = (current + delta).coerceIn(0, max)
                if (next != current) manager.setStreamVolume(stream, next, 0)
                percent = if (max > 0) manager.getStreamVolume(stream) * 100 / max else -1
            }.onFailure { Log.w(TAG, "setStreamVolume: ${it.message}") }
        }
        if (percent < 0) {
            percent = (cachedVolumePercent.takeIf { it >= 0 } ?: 50) + delta * 5
            percent = percent.coerceIn(0, 100)
            if (host.rootAvailable && now - lastVolumeApplyMs >= 120) {
                lastVolumeApplyMs = now
                val code = if (delta > 0) 24 else 25
                host.execSu("input keyevent $code")
            }
        }
        cachedVolumePercent = percent
        host.emitEvent("volume_changed") {
            it.put("source", if (event.device == "event2") "rotary" else event.device)
            it.put("delta", delta)
            it.put("volume", percent / 100.0)
        }
        showVolumeFeedback(event.device, delta, percent)
    }

    /** Stock io feedback: volume clip on the screen + arc on the ring. */
    private fun showVolumeFeedback(device: String, delta: Int, percent: Int) {
        if (!prefs.getBoolean(KEY_VOLUME_ANIM, true)) return
        if (percent < 0) return
        val engine = host.engine
        val level = ((percent + 5) / 10).coerceIn(0, 10)
        val kind = if (device == "event2") "volume_rotation" else "volume_pulse"
        val dir = if (delta >= 0) "up" else "down"
        val id = "rcu_volume/$kind/$dir/$level.gif"
        runCatching {
            host.animations.load(id)?.let { engine.playOverlayAnimation(it, loop = false) }
        }
        runCatching {
            val lit = ((percent * RingLeds + 50) / 100).coerceIn(0, RingLeds)
            val color = host.ringColorRgb
            val bytes = ByteArray(RingLeds * 3)
            for (i in 0 until lit) {
                bytes[i * 3] = ((color shr 16) and 0xFF).toByte()
                bytes[i * 3 + 1] = ((color shr 8) and 0xFF).toByte()
                bytes[i * 3 + 2] = (color and 0xFF).toByte()
            }
            engine.drawCustomRingRgb(bytes)
        }
        lastVolumeFeedbackMs = System.currentTimeMillis()
    }

    private fun mediaKey(keyCode: Int) {
        if (!host.rootAvailable) return
        host.execSu("input keyevent $keyCode")
    }

    // ------------------------------------------------------------------- alice

    private fun activateAlice() {
        // Returns true only when io.sdk was frozen and had to be woken up; in
        // that case the system never saw the physical button, so we inject a
        // VOICE_ASSIST keyevent. When io.sdk is alive it handles the button
        // natively and injecting would double-trigger the assistant.
        val woke = host.onAliceActivated()
        if (woke && host.rootAvailable) {
            host.execSu("input keyevent 219") // KEYCODE_VOICE_ASSIST
        }
        host.emitEvent("alice_activated") {
            it.put("ok", true)
            it.put("woke_sdk", woke)
        }
    }

    private fun toggleScreen() {
        val enabled = !host.engine.screenEnabled
        host.engine.setScreenEnabled(enabled)
        host.persistScreenEnabled(enabled)
        host.emitEvent("screen_enabled") { it.put("enabled", enabled) }
    }

    private fun toggleRing() {
        val enabled = !host.engine.ringEnabled
        host.engine.setRingEnabled(enabled)
        host.persistRingEnabled(enabled)
        host.emitEvent("ring_enabled") { it.put("enabled", enabled) }
    }

    private fun playClip(id: String) {
        val animation = host.animations.load(id) ?: return
        host.engine.playOverlayAnimation(animation, loop = false)
    }

    // ------------------------------------------------------------------- json

    fun bindingsJson(): String {
        val array = JSONArray()
        for (binding in bindings()) {
            array.put(
                JSONObject()
                    .put("key", binding.key.name)
                    .put("title", binding.title)
                    .put("device", binding.device)
                    .put("action", binding.action),
            )
        }
        val actions = JSONArray()
        for ((id, title) in actionList()) {
            actions.put(JSONObject().put("id", id).put("title", title))
        }
        return JSONObject()
            .put("ok", true)
            .put("bindings", array)
            .put("actions", actions)
            .toString()
    }

    /** Static action catalogue; dynamic script:/anim: entries are appended by the service. */
    fun actionList(): List<Pair<String, String>> = listOf(
        ACTION_NONE to "Ничего",
        ACTION_VOLUME_UP to "Громкость +",
        ACTION_VOLUME_DOWN to "Громкость −",
        ACTION_PLAY_PAUSE to "Play / Pause",
        ACTION_NEXT to "Следующий трек",
        ACTION_PREVIOUS to "Предыдущий трек",
        ACTION_STOP_MEDIA to "Остановить воспроизведение",
        ACTION_ALICE to "Алиса",
        ACTION_MUTE to "Событие мьюта (только журнал)",
        ACTION_TOGGLE_SCREEN to "Экран вкл/выкл",
        ACTION_TOGGLE_RING to "Кольцо вкл/выкл",
        ACTION_CLOCK to "Показать часы",
        ACTION_VISUALIZER to "Показать визуализатор",
        ACTION_ANIMATION to "Показать анимацию",
    )

    companion object {
        private const val TAG = "QuasarInputActions"
        private const val KEY_BINDINGS = "input_bindings"
        private const val KEY_VOLUME_ENABLED = "ring_volume_enabled"
        private const val KEY_VOLUME_ANIM = "ring_volume_anim"
        private const val KEY_VOLUME_STEP = "ring_volume_step"
        private const val RingLeds = 24
        private const val VOLUME_IDLE_MS = 1600L

        const val ACTION_NONE = "none"
        const val ACTION_VOLUME_UP = "volume_up"
        const val ACTION_VOLUME_DOWN = "volume_down"
        const val ACTION_PLAY_PAUSE = "play_pause"
        const val ACTION_NEXT = "next"
        const val ACTION_PREVIOUS = "previous"
        const val ACTION_STOP_MEDIA = "stop_media"
        const val ACTION_ALICE = "alice"
        const val ACTION_MUTE = "mute"
        const val ACTION_TOGGLE_SCREEN = "toggle_screen"
        const val ACTION_TOGGLE_RING = "toggle_ring"
        const val ACTION_CLOCK = "clock"
        const val ACTION_VISUALIZER = "visualizer"
        const val ACTION_ANIMATION = "animation"
        const val ACTION_SCRIPT = "script"
        const val ACTION_ANIM = "anim"

        val knownActions: Map<String, String> = mapOf(
            ACTION_VOLUME_UP to "Громкость +",
            ACTION_VOLUME_DOWN to "Громкость −",
            ACTION_PLAY_PAUSE to "Play / Pause",
            ACTION_NEXT to "Следующий трек",
            ACTION_PREVIOUS to "Предыдущий трек",
            ACTION_STOP_MEDIA to "Остановить воспроизведение",
            ACTION_ALICE to "Алиса",
            ACTION_MUTE to "Событие мьюта (только журнал)",
            ACTION_TOGGLE_SCREEN to "Экран вкл/выкл",
            ACTION_TOGGLE_RING to "Кольцо вкл/выкл",
            ACTION_CLOCK to "Показать часы",
            ACTION_VISUALIZER to "Показать визуализатор",
            ACTION_ANIMATION to "Показать анимацию",
        )

        /** Stable display order, matching the physical devices. */
        private val keysInOrder = listOf(
            InputKey.ALICE,
            InputKey.MUTE,
            InputKey.VOLUME_UP,
            InputKey.VOLUME_DOWN,
            InputKey.REMOTE_PLAY_PAUSE,
            InputKey.REMOTE_NEXT,
            InputKey.REMOTE_PREVIOUS,
            InputKey.REMOTE_POWER,
            InputKey.REMOTE_HOME,
            InputKey.REMOTE_BACK,
            InputKey.REMOTE_OK,
            InputKey.REMOTE_UP,
            InputKey.REMOTE_DOWN,
            InputKey.REMOTE_LEFT,
            InputKey.REMOTE_RIGHT,
            InputKey.HEADPHONES,
        )

        private val keyTitles = mapOf(
            InputKey.ALICE to "Кнопка Алисы",
            InputKey.MUTE to "Переключатель мьюта",
            InputKey.VOLUME_UP to "Кольцо · вращение вверх",
            InputKey.VOLUME_DOWN to "Кольцо · вращение вниз",
            InputKey.REMOTE_PLAY_PAUSE to "Пульт · Play/Pause",
            InputKey.REMOTE_NEXT to "Пульт · следующий трек",
            InputKey.REMOTE_PREVIOUS to "Пульт · предыдущий трек",
            InputKey.REMOTE_POWER to "Пульт · питание",
            InputKey.REMOTE_HOME to "Пульт · домой",
            InputKey.REMOTE_BACK to "Пульт · назад",
            InputKey.REMOTE_OK to "Пульт · OK",
            InputKey.REMOTE_UP to "Пульт · вверх",
            InputKey.REMOTE_DOWN to "Пульт · вниз",
            InputKey.REMOTE_LEFT to "Пульт · влево",
            InputKey.REMOTE_RIGHT to "Пульт · вправо",
            InputKey.HEADPHONES to "Разъём наушников",
        )

        private val keyDevices = mapOf(
            InputKey.ALICE to "станция",
            InputKey.MUTE to "станция",
            InputKey.VOLUME_UP to "энкодер громкости",
            InputKey.VOLUME_DOWN to "энкодер громкости",
            InputKey.REMOTE_PLAY_PAUSE to "пульт",
            InputKey.REMOTE_NEXT to "пульт",
            InputKey.REMOTE_PREVIOUS to "пульт",
            InputKey.REMOTE_POWER to "пульт",
            InputKey.REMOTE_HOME to "пульт",
            InputKey.REMOTE_BACK to "пульт",
            InputKey.REMOTE_OK to "пульт",
            InputKey.REMOTE_UP to "пульт",
            InputKey.REMOTE_DOWN to "пульт",
            InputKey.REMOTE_LEFT to "пульт",
            InputKey.REMOTE_RIGHT to "пульт",
            InputKey.HEADPHONES to "станция",
        )

        fun defaultBinding(key: InputKey): String = when (key) {
            InputKey.ALICE -> ACTION_ALICE
            InputKey.VOLUME_UP -> ACTION_VOLUME_UP
            InputKey.VOLUME_DOWN -> ACTION_VOLUME_DOWN
            InputKey.MUTE -> ACTION_MUTE
            InputKey.REMOTE_PLAY_PAUSE -> ACTION_PLAY_PAUSE
            InputKey.REMOTE_NEXT -> ACTION_NEXT
            InputKey.REMOTE_PREVIOUS -> ACTION_PREVIOUS
            else -> ACTION_NONE
        }

        private fun parseBindings(raw: String?): Map<InputKey, String> {
            val out = mutableMapOf<InputKey, String>()
            if (raw.isNullOrBlank()) return out
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return out
            for (key in keysInOrder) {
                val value = json.optString(key.name, "").takeIf { it.isNotBlank() } ?: continue
                out[key] = value
            }
            return out
        }
    }
}
