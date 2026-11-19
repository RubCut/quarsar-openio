package quasar.rubcut.openio.data.plugin

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Two-level log used by the runtime watchdog:
 *
 * * `logs/openio.log` — global timeline of the service, plugins and events;
 * * `logs/plugins/<provider>.log` — everything a single plugin wrote.
 *
 * Every plugin line is mirrored into the global log so the UI can show one
 * timeline without losing the per-plugin view. Files are capped and rotated
 * (`*.1`) so a runaway script cannot fill the data partition.
 */
class PluginLogStore(context: Context) {
    private val root = File(context.filesDir, "logs").apply { mkdirs() }
    private val pluginDir = File(root, "plugins").apply { mkdirs() }
    private val globalFile = File(root, "openio.log")
    private val lock = Any()
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun global(source: String, message: String) {
        write(globalFile, line("I", source, message))
    }

    fun warn(source: String, message: String) {
        write(globalFile, line("W", source, message))
    }

    /** A line produced by a plugin process or its event handler. */
    fun plugin(providerKey: String, message: String) {
        val clean = providerKey.takeIf { it.isNotBlank() } ?: "unknown"
        write(pluginFile(clean), line("I", clean, message))
        write(globalFile, line("I", clean, message))
    }

    fun pluginError(providerKey: String, message: String) {
        val clean = providerKey.takeIf { it.isNotBlank() } ?: "unknown"
        write(pluginFile(clean), line("E", clean, message))
        write(globalFile, line("E", clean, message))
    }

    /**
     * Records a service event in the global log. The compact rendering keeps
     * the file readable while still preserving every field.
     */
    fun event(json: String) {
        val parsed = runCatching { JSONObject(json) }.getOrNull() ?: return
        val name = parsed.optString("event", "event")
        val details = parsed.keys().asSequence()
            .filter { it != "event" && it != "ts" }
            .map { key -> "$key=${parsed.opt(key)?.toString()?.take(160) ?: ""}" }
            .joinToString(" ")
        write(globalFile, line("E", "event", "$name${if (details.isEmpty()) "" else " · $details"}"))
    }

    fun tailGlobal(limit: Int): List<String> = tail(globalFile, limit)

    fun tailPlugin(providerKey: String, limit: Int): List<String> = tail(pluginFile(providerKey), limit)

    fun pluginLogPath(providerKey: String): String = pluginFile(providerKey).absolutePath

    fun pluginKeys(): List<String> =
        pluginDir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.map { it.name.removeSuffix(".log") }
            ?.sorted()
            ?: emptyList()

    fun clear(scope: String, providerKey: String?) {
        synchronized(lock) {
            if (scope.equals("plugin", true) && !providerKey.isNullOrBlank()) {
                pluginFile(providerKey).delete()
                return
            }
            globalFile.delete()
            pluginDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun pluginFile(providerKey: String): File {
        val safe = providerKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(pluginDir, "$safe.log")
    }

    private fun tail(file: File, limit: Int): List<String> {
        val max = limit.coerceIn(1, 2_000)
        return synchronized(lock) {
            if (!file.isFile) return emptyList()
            runCatching {
                val bytes = file.readBytes()
                val text = bytes.toString(Charsets.UTF_8)
                text.lineSequence().filter { it.isNotEmpty() }.toList().takeLast(max)
            }.getOrDefault(emptyList())
        }
    }

    private fun write(file: File, line: String) {
        synchronized(lock) {
            runCatching {
                if (file.length() > MAX_BYTES) {
                    val old = File(file.parentFile, "${file.name}.1")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(line + "\n")
            }
        }
    }

    private fun line(level: String, source: String, message: String): String =
        "${LocalDateTime.now().format(stamp)} $level/$source: $message"

    companion object {
        private const val MAX_BYTES = 512L * 1024
    }
}