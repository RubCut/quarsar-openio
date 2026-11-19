package quasar.rubcut.openio.data.plugin

import android.content.Context
import android.util.Log
import org.json.JSONObject
import quasar.rubcut.openio.domain.plugin.PluginProvider
import quasar.rubcut.openio.domain.plugin.ProviderType
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Watchdog for [ProviderType.SERVICE] providers.
 *
 * Each enabled service is a long-running process owned by OpenIO. The runtime
 * captures stdout/stderr into the per-plugin log, restarts crashed services
 * according to the manifest policy and exposes a compact status to the UI.
 * A process gets `QUASAR_API`, `QUASAR_TOKEN`, `QUASAR_PACK_DIR` and
 * `QUASAR_CONFIG`, so it can drive the display and call the shell endpoint.
 */
class PluginServiceRuntime(
    @Suppress("unused") private val context: Context,
    private val store: PluginStore,
    private val logs: PluginLogStore,
    private val token: String,
) {
    data class Status(
        val key: String,
        val packId: String,
        val providerId: String,
        val name: String,
        val description: String,
        val running: Boolean,
        val pid: Int,
        val restarts: Int,
        val uptimeMs: Long,
        val lastExit: Int?,
        val lastError: String?,
        val restartPolicy: String,
        val autostart: Boolean,
        val manuallyStopped: Boolean,
    )

    private class Instance(
        val key: String,
        val packId: String,
        val provider: PluginProvider,
        @Volatile var process: Process? = null,
        @Volatile var startedAtMs: Long = 0L,
        @Volatile var failures: Int = 0,
        @Volatile var restarts: Int = 0,
        @Volatile var nextRestartAtMs: Long = 0L,
        @Volatile var lastExit: Int? = null,
        @Volatile var lastError: String? = null,
    )

    private val instances = ConcurrentHashMap<String, Instance>()
    private val manuallyStopped = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "quasar-plugin-svc").apply { isDaemon = true }
    }
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "quasar-plugin-watchdog").apply { isDaemon = true }
    }

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        refresh(autostart = true)
        watchdog.scheduleWithFixedDelay(
            { runCatching { tick() }.onFailure { Log.w(TAG, "watchdog failed", it) } },
            1,
            2,
            TimeUnit.SECONDS,
        )
        logs.global("watchdog", "запущен · сервисов: ${instances.size}")
    }

    fun stop() {
        started = false
        watchdog.shutdownNow()
        for (instance in instances.values) stopProcess(instance, manual = false)
        instances.clear()
        executor.shutdownNow()
        logs.global("watchdog", "остановлен")
    }

    /** Re-reads the catalog and starts autostart services. */
    fun refresh(autostart: Boolean = false) {
        val seen = HashSet<String>()
        for (located in store.enabledProviders(ProviderType.SERVICE)) {
            val key = located.key
            seen.add(key)
            val existing = instances[key]
            if (existing == null) {
                val instance = Instance(key, located.pack.id, located.provider)
                instances[key] = instance
                if (autostart && located.provider.autostart && !manuallyStopped.contains(key)) {
                    launch(instance)
                }
            } else if (existing.provider.entry != located.provider.entry ||
                existing.provider.interpreter != located.provider.interpreter
            ) {
                stopProcess(existing, manual = false)
                val replacement = Instance(key, located.pack.id, located.provider)
                instances[key] = replacement
                if (autostart && located.provider.autostart && !manuallyStopped.contains(key)) {
                    launch(replacement)
                }
            }
        }
        for (key in instances.keys.filter { it !in seen }) {
            val instance = instances.remove(key) ?: continue
            stopProcess(instance, manual = true)
            logs.global("watchdog", "сервис удалён: $key")
        }
    }

    fun startProvider(key: String): Boolean {
        refresh(autostart = false)
        val instance = instances[key] ?: return false
        manuallyStopped.remove(key)
        instance.failures = 0
        instance.nextRestartAtMs = 0
        return launch(instance)
    }

    fun stopProvider(key: String): Boolean {
        val instance = instances[key] ?: return false
        manuallyStopped.add(key)
        stopProcess(instance, manual = true)
        return true
    }

    fun restartProvider(key: String): Boolean {
        val instance = instances[key] ?: return false
        manuallyStopped.remove(key)
        stopProcess(instance, manual = false)
        instance.failures = 0
        instance.restarts++
        instance.nextRestartAtMs = 0
        return launch(instance)
    }

    fun isRunning(key: String): Boolean = instances[key]?.process?.isAlive == true

    fun statuses(): List<Status> {
        val now = nowMs()
        return instances.values.sortedBy { it.key }.map { instance ->
            val process = instance.process
            val running = process?.isAlive == true
            Status(
                key = instance.key,
                packId = instance.packId,
                providerId = instance.provider.id,
                name = instance.provider.name,
                description = instance.provider.description,
                running = running,
                pid = if (running && process != null) processPid(process) else 0,
                restarts = instance.restarts,
                uptimeMs = if (running) (now - instance.startedAtMs).coerceAtLeast(0) else 0,
                lastExit = instance.lastExit,
                lastError = instance.lastError,
                restartPolicy = instance.provider.restart,
                autostart = instance.provider.autostart,
                manuallyStopped = manuallyStopped.contains(instance.key),
            )
        }
    }

    // --------------------------------------------------------------- watchdog

    private fun tick() {
        if (!started) return
        val now = nowMs()
        for (instance in instances.values) {
            val located = store.locate(instance.packId, instance.provider.id) ?: continue
            if (!store.isEnabled(located.pack.id, located.provider.id)) {
                if (instance.process != null) {
                    logs.global("watchdog", "сервис отключён: ${instance.key}")
                    stopProcess(instance, manual = true)
                }
                continue
            }
            val process = instance.process
            if (process != null && process.isAlive) {
                if (instance.failures > 0 && now - instance.startedAtMs > STABLE_MS) {
                    instance.failures = 0
                }
                continue
            }
            if (process != null) {
                val code = runCatching { process.exitValue() }.getOrNull()
                instance.lastExit = code
                instance.process = null
                // A process that exits on its own is a crash for backoff
                // purposes even when it reports code 0: restarting it right
                // away would create a tight loop.
                instance.failures = (instance.failures + 1).coerceAtMost(6)
                val delay = backoffMs(instance.failures)
                instance.nextRestartAtMs = now + delay
                logs.plugin(
                    instance.key,
                    "процесс завершился · код ${code ?: "?"} · повтор через ${delay / 1000} с",
                )
            }
            if (manuallyStopped.contains(instance.key)) continue
            if (now < instance.nextRestartAtMs) continue
            val failed = (instance.lastExit ?: 1) != 0
            val shouldRestart = when (instance.provider.restart) {
                "always" -> true
                "never" -> false
                else -> failed
            }
            if (shouldRestart) {
                instance.restarts++
                launch(instance)
            } else if (instance.lastExit != null) {
                logs.global("watchdog", "сервис остановлен по политике ${instance.provider.restart}: ${instance.key}")
            }
        }
    }

    private fun launch(instance: Instance): Boolean {
        if (instance.process?.isAlive == true) return true
        val provider = instance.provider
        val entry = provider.entry
        if (entry.isNullOrBlank()) {
            fail(instance, "не указан entry")
            return false
        }
        val packDir = store.materializePack(instance.packId)
        if (packDir == null) {
            fail(instance, "пак ${instance.packId} недоступен")
            return false
        }
        val entryFile = try {
            File(packDir, entry).canonicalFile
        } catch (e: Exception) {
            fail(instance, "неверный путь entry: ${e.message}")
            return false
        }
        if (!entryFile.path.startsWith(packDir.canonicalPath + File.separator) || !entryFile.isFile) {
            fail(instance, "entry не найден: $entry")
            return false
        }
        val command = try {
            buildCommand(provider, packDir, entryFile)
        } catch (e: Exception) {
            fail(instance, e.message ?: "не удалось собрать команду")
            return false
        }
        return try {
            val builder = ProcessBuilder(command)
            builder.directory(packDir)
            builder.redirectErrorStream(true)
            val env = builder.environment()
            val located = store.locate(instance.packId, provider.id)
            val config = located?.let { store.config(it) } ?: emptyMap()
            env["QUASAR_API"] = API_URL
            env["QUASAR_IPC"] = IPC_ADDRESS
            env["QUASAR_TOKEN"] = token
            env["QUASAR_PACK"] = instance.packId
            env["QUASAR_PROVIDER"] = provider.id
            env["QUASAR_PACK_DIR"] = packDir.absolutePath
            env["QUASAR_CONFIG"] = JSONObject(config as Map<*, *>).toString()
            env["QUASAR_LOG_FILE"] = logs.pluginLogPath(instance.key)
            for ((key, value) in provider.env) env[key] = value
            val process = builder.start()
            instance.process = process
            instance.startedAtMs = nowMs()
            instance.lastError = null
            val pid = processPid(process)
            logs.plugin(instance.key, "запущен · pid $pid · ${command.joinToString(" ")}")
            streamOutput(instance, process)
            true
        } catch (e: Exception) {
            fail(instance, "запуск не удался: ${e.message}")
            false
        }
    }

    private fun buildCommand(provider: PluginProvider, packDir: File, entryFile: File): List<String> {
        runCatching { entryFile.setExecutable(true, false) }
        val interpreter = provider.interpreter?.trim()?.takeIf { it.isNotEmpty() }
        if (interpreter == null) {
            return when {
                entryFile.name.endsWith(".sh", ignoreCase = true) ->
                    listOf("/system/bin/sh", entryFile.absolutePath)
                entryFile.canExecute() -> listOf(entryFile.absolutePath)
                else -> listOf("/system/bin/sh", entryFile.absolutePath)
            }
        }
        val interpreterPath = when {
            interpreter.startsWith("/") -> interpreter
            interpreter.contains('/') -> {
                val file = File(packDir, interpreter).canonicalFile
                if (!file.path.startsWith(packDir.canonicalPath + File.separator)) {
                    throw IllegalArgumentException("интерпретатор вне пака: $interpreter")
                }
                if (!file.isFile) throw IllegalArgumentException("интерпретатор не найден: $interpreter")
                runCatching { file.setExecutable(true, false) }
                file.absolutePath
            }
            else -> interpreter
        }
        return listOf(interpreterPath, entryFile.absolutePath)
    }

    private fun streamOutput(instance: Instance, process: Process) {
        executor.execute {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        logs.plugin(instance.key, line.take(MAX_LINE))
                    }
                }
            }.onFailure { Log.w(TAG, "reader ${instance.key}: ${it.message}") }
        }
    }

    private fun stopProcess(instance: Instance, manual: Boolean) {
        val process = instance.process ?: return
        instance.process = null
        instance.lastExit = null
        instance.nextRestartAtMs = 0
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
            }
        }
        logs.plugin(instance.key, if (manual) "остановлен пользователем" else "остановлен")
    }

    private fun fail(instance: Instance, message: String) {
        instance.lastError = message
        instance.lastExit = 127
        instance.failures++
        val delay = backoffMs(instance.failures)
        instance.nextRestartAtMs = nowMs() + delay
        logs.pluginError(instance.key, "$message · повтор через ${delay / 1000} с")
    }

    /** 1, 2, 4, 8, 16, 30 seconds. */
    private fun backoffMs(failures: Int): Long =
        (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

    /**
     * `Process.pid()` is a Java 9 method that Android only exposes on newer
     * releases, so it is looked up reflectively. A missing method simply means
     * the status JSON shows pid 0; the process still runs.
     */
    private fun processPid(process: Process): Int =
        runCatching {
            val method = process.javaClass.getMethod("pid")
            (method.invoke(process) as? Number)?.toInt() ?: 0
        }.getOrDefault(0)

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    companion object {
        private const val TAG = "QuasarPluginSvc"
        private const val API_URL = "http://127.0.0.1:8080"
        private const val IPC_ADDRESS = "127.0.0.1:8081"
        private const val STOP_GRACE_MS = 1_500L
        private const val STABLE_MS = 15_000L
        private const val MAX_LINE = 8_000
    }
}