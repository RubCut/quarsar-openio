package quasar.rubcut.openio.data.root

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin, explicit wrapper around KernelSU's su. Every privileged action is a
 * separate Runtime.exec-like call; there are no shell loops in native code and
 * no long-lived root shell.
 */
class RootShell(private val defaultTimeoutMs: Long = 20_000L) {
    data class Result(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false,
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }

    fun run(command: String, timeoutMs: Long = defaultTimeoutMs): Result {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            return Result(127, "su unavailable: ${e.message}")
        }
        val output = StringBuilder()
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) { output.append(line).append('\n') }
                    }
                }
            } catch (_: IOException) {
                // Process was killed or the stream closed; the exit code is authoritative.
            }
        }, "quasar-root-reader")
        reader.isDaemon = true
        reader.start()
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            try {
                process.waitFor(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            reader.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val text = synchronized(output) { output.toString().trimEnd() }
        return Result(if (finished) process.exitValue() else -1, text, !finished)
    }

    fun runScript(script: String, timeoutMs: Long = defaultTimeoutMs): Result =
        run("sh -c ${shellQuote(script)}", timeoutMs)

    fun isRoot(): Boolean = run("id").let { it.ok && it.output.contains("uid=0(root)") }

    fun sdkPids(): List<Int> {
        val result = run("pidof com.yandex.io.sdk")
        if (!result.ok) return emptyList()
        return result.output.trim().split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
    }

    fun processState(pid: Int): String? {
        val result = run("cat /proc/$pid/stat 2>/dev/null")
        if (!result.ok || result.output.isBlank()) return null
        // Field 3 is the state character, after the parenthesized command name.
        val end = result.output.lastIndexOf(')')
        if (end < 0 || end + 2 >= result.output.length) return null
        return result.output.substring(end + 2, end + 3)
    }

    companion object {
        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}