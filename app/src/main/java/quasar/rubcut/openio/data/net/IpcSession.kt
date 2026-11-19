package quasar.rubcut.openio.data.net

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import quasar.rubcut.openio.domain.audio.Pcm
import quasar.rubcut.openio.service.ControlApi
import quasar.rubcut.openio.service.RendererConfig
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * One line-delimited JSON IPC connection. Commands are answered with
 * {"event":"result","cmd":...}; input events and other asynchronous messages
 * are pushed to the same stream as soon as they happen.
 */
class IpcSession(
    private val api: ControlApi,
    input: InputStream,
    output: OutputStream,
    private val label: String,
) {
    private val reader: BufferedReader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
    private val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
    private val writeLock = Any()

    @Volatile
    private var closed = false

    fun run() {
        val subscription = api.addEventListener { event -> send(event) }
        send(JSONObject().put("event", "hello").put("protocol", "quasar-ipc").put("version", 1).toString())
        try {
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                handle(line.trim())
            }
        } catch (_: Exception) {
            // Broken pipe / client shutdown.
        } finally {
            subscription.close()
            closed = true
            Log.i(TAG, "ipc client disconnected: $label")
        }
    }

    fun close() {
        closed = true
        runCatching { reader.close() }
        runCatching { writer.close() }
    }

    private fun handle(line: String) {
        val request = try {
            JSONObject(line)
        } catch (_: Exception) {
            sendError("", "invalid json")
            return
        }
        val command = request.optString("cmd").trim()
        if (command.isEmpty()) {
            sendError("", "cmd is required")
            return
        }
        when (command) {
            "ping" -> reply(command, true) { put("event", "pong") }
            "status" -> reply(command, true) { put("data", JSONObject(api.statusJson())) }
            "draw_screen" -> {
                val bytes = decode(request.optString("data"))
                if (bytes == null || bytes.size != 25 * 16 * 3) {
                    sendError(command, "data must be base64 RGB888 of 1200 bytes")
                } else {
                    reply(command, api.drawScreen(bytes))
                }
            }
            "draw_ring" -> {
                val bytes = decode(request.optString("data"))
                if (bytes == null || bytes.size != 24 * 3) {
                    sendError(command, "data must be base64 RGB888 of 72 bytes")
                } else {
                    reply(command, api.drawRing(bytes))
                }
            }
            "set_mode" -> {
                val mode = request.optString("mode")
                if (mode.isBlank()) sendError(command, "mode is required") else reply(command, api.setMode(mode)) {
                    put("mode", mode)
                }
            }
            "set_render" -> {
                var touched = false
                var ok = true
                if (request.has("ring_color")) {
                    val rgb = RendererConfig.parseColor(request.optString("ring_color"))
                    if (rgb == null) {
                        sendError(command, "ring_color must be #RRGGBB")
                        return
                    }
                    ok = api.setRingColor(rgb) && ok
                    touched = true
                }
                if (request.has("ring_enabled")) {
                    ok = api.setRingEnabled(request.optBoolean("ring_enabled", true)) && ok
                    touched = true
                }
                if (request.has("scope_fps")) {
                    ok = api.setScopeFps(request.optInt("scope_fps", 30)) && ok
                    touched = true
                }
                if (request.has("scope_sensitivity")) {
                    ok = api.setScopeSensitivity(request.optDouble("scope_sensitivity", 1.0).toFloat()) && ok
                    touched = true
                }
                if (request.has("scope_smooth")) {
                    ok = api.setScopeSmooth(request.optInt("scope_smooth", 1500)) && ok
                    touched = true
                }
                if (request.has("capture_rate")) {
                    val rate = request.optInt("capture_rate", 16_000)
                    if (rate != 16_000 && rate != 48_000) {
                        sendError(command, "capture_rate must be 16000 or 48000")
                        return
                    }
                    api.setCaptureRate(rate)
                    touched = true
                }
                if (!touched) {
                    sendError(
                        command,
                        "ring_color, ring_enabled, scope_fps, scope_sensitivity or capture_rate is required",
                    )
                } else {
                    reply(command, ok) {
                        put("render", JSONObject(api.statusJson()).optJSONObject("render"))
                    }
                }
            }
            "freeze_sdk", "stop_sdk" -> reply(command, api.freezeSdk())
            "resume_sdk", "unfreeze_sdk" -> reply(command, api.unfreezeSdk())
            "set_autostart" -> {
                val current = runCatching {
                    JSONObject(api.statusJson()).getJSONObject("autostart").optBoolean("enabled")
                }.getOrDefault(false)
                val action = request.optString("action").lowercase()
                val enabled = when {
                    request.has("enabled") -> request.optBoolean("enabled")
                    action in setOf("enable", "on", "true") -> true
                    action in setOf("disable", "off", "false") -> false
                    else -> !current
                }
                reply(command, api.setAutostartEnabled(enabled)) {
                    put("autostart", JSONObject(api.statusJson()).optJSONObject("autostart"))
                }
            }
            "get_mic_pcm" -> {
                val count = request.optInt("samples", 1_600).coerceIn(16, 80_000)
                val samples = api.micPcm(count)
                reply(command, true) {
                    put("event", "mic_pcm")
                    put("sample_rate", 16_000)
                    put("channels", 1)
                    put("encoding", "s16le")
                    put("samples", samples.size)
                    put("data", Base64.encodeToString(Pcm.toLittleEndian(samples), Base64.NO_WRAP))
                }
            }
            "mic_level" -> reply(command, true) { put("rms", api.micRms()) }
            "play_tone" -> {
                val frequency = request.optDouble("frequency", 440.0)
                val duration = request.optInt("ms", 500)
                reply(command, api.playTone(frequency, duration)) {
                    put("frequency", frequency)
                    put("ms", duration)
                }
            }
            "set_volume" -> {
                val value = request.optDouble("value", -1.0).toFloat()
                if (value < 0f || value > 1f) sendError(command, "value must be in 0..1") else reply(command, api.setVolume(value)) {
                    put("value", value)
                }
            }
            "show_icon" -> reply(command, api.setIconVisible(true))
            "hide_icon" -> reply(command, api.setIconVisible(false))
            "events" -> {
                val array = JSONArray()
                api.recentEvents().forEach { array.put(JSONObject(it)) }
                reply(command, true) { put("events", array) }
            }
            else -> sendError(command, "unknown cmd")
        }
    }

    private fun decode(data: String): ByteArray? {
        if (data.isBlank()) return null
        val clean = data.substringAfter("base64,", data)
        return runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull()
    }

    private fun reply(command: String, ok: Boolean, block: JSONObject.() -> Unit = {}) {
        val json = JSONObject().put("event", "result").put("cmd", command).put("ok", ok)
        json.block()
        send(json.toString())
    }

    private fun sendError(command: String, message: String) {
        send(
            JSONObject()
                .put("event", "result")
                .put("cmd", command)
                .put("ok", false)
                .put("error", message)
                .toString(),
        )
    }

    private fun send(text: String) {
        synchronized(writeLock) {
            if (closed) return
            try {
                writer.write(text)
                writer.write("\n")
                writer.flush()
            } catch (_: Exception) {
                closed = true
            }
        }
    }

    companion object {
        private const val TAG = "QuasarIpc"
    }
}