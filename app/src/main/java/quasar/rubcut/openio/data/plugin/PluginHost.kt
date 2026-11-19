package quasar.rubcut.openio.data.plugin

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import quasar.rubcut.openio.domain.plugin.PluginPack
import quasar.rubcut.openio.domain.plugin.PluginProvider
import quasar.rubcut.openio.domain.plugin.ProviderType
import quasar.rubcut.openio.service.EventHub
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs pack event handlers.
 *
 * A `script` provider declares `entry` (a file in `scripts/` or `bins/`) and
 * the `events` it subscribes to. For every matching service event the entry is
 * executed with the event JSON on stdin and `QUASAR_API`, `QUASAR_PACK`,
 * `QUASAR_PROVIDER` and `QUASAR_CONFIG` in the environment.
 *
 * Output lines are JSON commands:
 *
 * ```
 * {"ring":"#3366ff"}                  // fill all 24 LEDs
 * {"ring":["#000000", ... 24 items]}  // exact frame
 * {"pattern":"led/alice.led","loop":true}
 * {"animation":"builtin:anim/media/25x16_Pause.gif"}
 * {"mode":"oscilloscope"}
 * {"notify":"текст"}
 * ```
 *
 * Scripts are short-lived (5 s timeout) and never run on the event producer
 * thread, so a broken handler cannot stall audio, input or the HTTP server.
 */
class PluginHost(
    @Suppress("unused") private val context: Context,
    private val store: PluginStore,
    private val events: EventHub,
    private val logs: PluginLogStore,
    private val token: String,
    private val actions: Actions,
) {
    interface Actions {
        fun playRingResource(packId: String, resource: String, loop: Boolean): Boolean
        fun playAnimationResource(packId: String, resource: String, loop: Boolean): Boolean
        fun setRingColors(colors: IntArray): Boolean
        fun setMode(mode: String): Boolean
        fun notify(message: String)
    }

    @Volatile
    private var subscription: AutoCloseable? = null

    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "quasar-plugin").apply { isDaemon = true }
    }

    fun start() {
        if (subscription != null) return
        runCatching { store.refresh() }
        subscription = events.add(::onEvent)
    }

    fun stop() {
        subscription?.close()
        subscription = null
        executor.shutdownNow()
    }

    /**
     * Runs one script item immediately with a synthetic `manual` event. Used
     * by the mods UI ("Запустить") to test a handler without waiting for a
     * real event; requires the item to be enabled.
     */
    fun runOnce(providerKey: String): Boolean {
        val ref = quasar.rubcut.openio.domain.plugin.ProviderRef.parse(providerKey) ?: return false
        val pack = store.packs().firstOrNull { it.id == ref.packId } ?: return false
        val provider = pack.providers.firstOrNull { it.id == ref.providerId } ?: return false
        if (provider.type != ProviderType.SCRIPT || provider.entry.isNullOrBlank()) return false
        if (!store.isEnabled(pack.id, provider.id)) return false
        executor.execute { run(pack, provider, """{"event":"manual"}""") }
        return true
    }

    private fun onEvent(json: String) {
        val name = runCatching { JSONObject(json).optString("event") }.getOrNull() ?: return
        // Never let plugin-generated notifications re-enter the handlers.
        if (name.startsWith("plugin_")) return
        for (pack in store.packs()) {
            if (!store.isPackEnabled(pack.id)) continue
            for (provider in pack.providers) {
                if (provider.type != ProviderType.SCRIPT) continue
                if (provider.entry.isNullOrBlank()) continue
                if (provider.events.isEmpty() || !provider.events.contains(name)) continue
                if (!store.isProviderEnabled("${pack.id}/${provider.id}")) continue
                val event = json
                executor.execute { run(pack, provider, event) }
            }
        }
    }

    private fun run(pack: PluginPack, provider: PluginProvider, eventJson: String) {
        val entry = provider.entry ?: return
        val key = "${pack.id}/${provider.id}"
        val packDir = store.materializePack(pack.id)
        if (packDir == null) {
            logs.pluginError(key, "пак недоступен")
            return
        }
        val file = try {
            File(packDir, entry).canonicalFile
        } catch (e: Exception) {
            logs.pluginError(key, "неверный entry: ${e.message}")
            return
        }
        if (!file.path.startsWith(packDir.canonicalPath + File.separator) || !file.isFile) {
            logs.pluginError(key, "entry не найден: $entry")
            return
        }
        if (!file.canExecute()) runCatching { file.setExecutable(true, false) }
        val interpreter = provider.interpreter?.trim()?.takeIf { it.isNotEmpty() }
        val command = when {
            interpreter != null -> {
                val resolved = if (interpreter.contains('/') && !interpreter.startsWith("/")) {
                    File(packDir, interpreter).canonicalFile.absolutePath
                } else {
                    interpreter
                }
                listOf(resolved, file.absolutePath)
            }
            file.name.endsWith(".sh", ignoreCase = true) -> listOf("/system/bin/sh", file.absolutePath)
            file.canExecute() -> listOf(file.absolutePath)
            else -> listOf("/system/bin/sh", file.absolutePath)
        }
        val located = store.locate(pack.id, provider.id)
        val config = located?.let { store.config(it) } ?: emptyMap()
        try {
            logs.plugin(key, "событие ${runCatching { JSONObject(eventJson).optString("event") }.getOrDefault("?")}")
            val builder = ProcessBuilder(command)
            builder.directory(packDir)
            builder.redirectErrorStream(true)
            val env = builder.environment()
            env["QUASAR_API"] = "http://127.0.0.1:8080"
            env["QUASAR_IPC"] = "127.0.0.1:8081"
            env["QUASAR_TOKEN"] = token
            env["QUASAR_PACK"] = pack.id
            env["QUASAR_PROVIDER"] = provider.id
            env["QUASAR_PACK_DIR"] = packDir.absolutePath
            env["QUASAR_CONFIG"] = JSONObject(config as Map<*, *>).toString()
            env["QUASAR_LOG_FILE"] = logs.pluginLogPath(key)
            for ((envKey, envValue) in provider.env) env[envKey] = envValue
            val process = builder.start()
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(eventJson)
                writer.newLine()
            }

            val output = StringBuilder()
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (output.length >= MAX_OUTPUT) break
                            output.appendLine(line)
                        }
                    }
                }
            }, "quasar-plugin-read").apply { isDaemon = true }
            reader.start()

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logs.pluginError(key, "таймаут ${TIMEOUT_SECONDS} с")
            }
            reader.join(500)
            val text = output.toString()
            if (text.isNotBlank()) logs.plugin(key, "stdout: ${text.trim().take(MAX_OUTPUT)}")
            applyOutput(pack, text)
        } catch (e: Exception) {
            logs.pluginError(key, "ошибка: ${e.message}")
        }
    }

    private fun applyOutput(pack: PluginPack, text: String) {
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue
            val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
            if (json.has("ring")) {
                parseRing(json.opt("ring"))?.let { actions.setRingColors(it) }
            }
            if (json.has("pattern")) {
                actions.playRingResource(
                    pack.id,
                    json.optString("pattern"),
                    json.optBoolean("loop", false),
                )
            }
            if (json.has("animation")) {
                actions.playAnimationResource(
                    pack.id,
                    json.optString("animation"),
                    json.optBoolean("loop", false),
                )
            }
            if (json.has("mode")) {
                actions.setMode(json.optString("mode"))
            }
            if (json.has("notify")) {
                actions.notify(json.optString("notify"))
            }
        }
    }

    private fun parseRing(value: Any?): IntArray? {
        return when (value) {
            is String -> {
                val color = parseColor(value) ?: return null
                IntArray(RING_SIZE) { color }
            }
            is JSONArray -> {
                if (value.length() == 1) {
                    val color = parseColor(value.optString(0)) ?: return null
                    IntArray(RING_SIZE) { color }
                } else {
                    val colors = IntArray(minOf(value.length(), RING_SIZE))
                    for (i in colors.indices) {
                        colors[i] = parseColor(value.optString(i)) ?: 0
                    }
                    colors
                }
            }
            else -> null
        }
    }

    private fun parseColor(value: String): Int? {
        val text = value.trim().removePrefix("#").removePrefix("0x")
        if (text.length != 6) return null
        return text.toIntOrNull(16)?.and(0xFFFFFF)
    }

    companion object {
        private const val TAG = "QuasarPluginHost"
        private const val TIMEOUT_SECONDS = 5L
        private const val MAX_OUTPUT = 64 * 1024
        private const val RING_SIZE = 24
    }
}