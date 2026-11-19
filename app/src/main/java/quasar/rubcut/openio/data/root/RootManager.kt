package quasar.rubcut.openio.data.root

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

class RootManager(
    private val context: Context,
    private val shell: RootShell = RootShell(),
) {
    data class SdkState(
        val running: Boolean,
        val pids: List<Int>,
        val stopped: Boolean,
    ) {
        val pid: Int? get() = pids.firstOrNull()
    }

    data class ActionResult(
        val ok: Boolean,
        val message: String,
        val pids: List<Int> = emptyList(),
    )

    data class InstallResult(
        val ok: Boolean,
        val output: String,
    )

    fun isRoot(): Boolean = shell.isRoot()

    /** Runs one privileged command; used by the plugin shell API. */
    fun runShell(command: String, timeoutMs: Long = 20_000L): RootShell.Result =
        shell.run(command, timeoutMs)

    fun selinux(): String = shell.run("getenforce").output.trim().ifEmpty { "unknown" }

    fun bootCompleted(): Boolean = shell.run("getprop sys.boot_completed").output.trim() == "1"

    /** True when a package process is alive; used by the auto-mode probe. */
    fun processRunning(name: String): Boolean {
        val result = shell.run("pidof ${RootShell.shellQuote(name)}")
        return result.output.trim().isNotEmpty()
    }

    /**
     * Names of packages with a started AudioTrack, read from AudioService.
     * `AudioPlaybackConfiguration.getClientUid()` is hidden and reflection is
     * blocked on this firmware, so this is the fallback for the auto mode.
     * Only `dumpsys audio` is used; `dumpsys media.audio_flinger` wedges the
     * audio HAL on this device.
     */
    fun activeMediaPackages(): List<String> {
        val command = buildString {
            append("dumpsys audio 2>/dev/null | ")
            append("sed -n 's/.*u\\/pid:\\([0-9][0-9]*\\)\\/[0-9][0-9]* -- state:started.*/\\1/p' | ")
            append("sort -u | head -4 | while read u; do ")
            append("cmd package list packages --uid \"\$u\" 2>/dev/null | ")
            append("sed -n 's/^package:\\([^ ]*\\).*/\\1/p'; done | sort -u")
        }
        val result = shell.run(command, 15_000L)
        if (!result.ok) {
            Log.w(TAG, "activeMediaPackages failed: exit=${result.exitCode} out=${result.output.take(200)}")
            return emptyList()
        }
        val packages = result.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && PACKAGE_PATTERN.matches(it) }
            .distinct()
            .toList()
        Log.d(TAG, "activeMediaPackages: ${packages.ifEmpty { listOf("none") }}")
        return packages
    }

    fun sdkState(): SdkState {
        val pids = shell.sdkPids()
        if (pids.isEmpty()) return SdkState(false, emptyList(), false)
        val state = shell.processState(pids.first())
        return SdkState(true, pids, state == "T" || state == "t")
    }

    fun freezeSdk(): ActionResult {
        val pids = shell.sdkPids()
        if (pids.isEmpty()) {
            return ActionResult(false, "io.sdk is not running", emptyList())
        }
        val command = buildString {
            append("pids=\"")
            append(pids.joinToString(" "))
            append("\"; ")
            append("for p in \$pids; do kill -STOP \"\$p\" || exit 4; done; echo FROZEN \$pids")
        }
        val result = shell.run(command)
        return ActionResult(result.ok, result.output.ifBlank { "exit=${result.exitCode}" }, pids)
    }

    fun unfreezeSdk(): ActionResult {
        val pids = shell.sdkPids()
        if (pids.isEmpty()) {
            return ActionResult(true, "io.sdk is not running", emptyList())
        }
        val command = buildString {
            append("pids=\"")
            append(pids.joinToString(" "))
            append("\"; ")
            append("for p in \$pids; do kill -CONT \"\$p\" 2>/dev/null || true; done; echo RESUMED \$pids")
        }
        val result = shell.run(command)
        return ActionResult(result.ok, result.output.ifBlank { "exit=${result.exitCode}" }, pids)
    }

    /**
     * Takes the microphone away from io.sdk so other system apps can open
     * AudioRecord. The app-op denial alone is not enough: the already open
     * capture session survives, so the process is restarted. The platform
     * brings io.sdk back automatically shortly after the kill.
     */
    fun denySdkMicAccess(): ActionResult = setSdkMicAccess(allow = false, restart = true)

    /** Restores io.sdk's RECORD_AUDIO app-op and restarts it for a clean open. */
    fun allowSdkMicAccess(): ActionResult = setSdkMicAccess(allow = true, restart = true)

    /** Re-applies the denial without killing a healthy, already-restarted io.sdk. */
    fun refreshSdkMicDenial(): ActionResult = setSdkMicAccess(allow = false, restart = false)

    private fun setSdkMicAccess(allow: Boolean, restart: Boolean): ActionResult {
        val mode = if (allow) "allow" else "ignore"
        val command = buildString {
            append("appops set com.yandex.io.sdk RECORD_AUDIO ")
            append(mode)
            if (restart) {
                append("; pids=\$(pidof com.yandex.io.sdk); ")
                append("if [ -n \"\$pids\" ]; then kill -9 \$pids 2>/dev/null || true; fi")
            }
            append("; appops get com.yandex.io.sdk RECORD_AUDIO")
        }
        val result = shell.run(command, 15_000L)
        val ok = result.ok && result.output.contains("RECORD_AUDIO: $mode")
        return ActionResult(ok, result.output.ifBlank { "exit=${result.exitCode}" }, shell.sdkPids())
    }

    /** Grants RECORD_AUDIO and opens the framebuffer/input nodes for the app UID. */
    fun prepareRuntime(packageName: String): ActionResult {
        val command = """
            # The Visualizer effect needs RECORD_AUDIO in the client app. Grant it
            # to ourselves and make sure Alice keeps hers: the effect taps the
            # output mix inside AudioFlinger, so no microphone is involved.
            pm grant $packageName android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
            pm grant com.yandex.io.sdk android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
            chmod 0666 /dev/graphics/fb4 /dev/graphics/fb5 /dev/input/event0 /dev/input/event2 /dev/input/event4 2>&1
            APP_UID=${'$'}(stat -c %u /data/data/$packageName 2>/dev/null)
            if [ -n "${'$'}APP_UID" ] && [ "${'$'}APP_UID" != "0" ]; then
                mkdir -p /data/local/tmp/quasar-openio
                chown "${'$'}APP_UID:${'$'}APP_UID" /data/local/tmp/quasar-openio
                chmod 0755 /data/local/tmp/quasar-openio
            fi
            echo RUNTIME_OK
        """.trimIndent()
        val result = shell.run(command)
        return ActionResult(result.ok, result.output.ifBlank { "exit=${result.exitCode}" })
    }

    /**
     * The vendor audio policy prefers the BLE "Omni Remote" voice input for
     * MIC capture. Its RcuVoiceServer returns -1 unless a BLE remote is
     * actively streaming, so AudioFlinger feeds silence. Mark that input
     * unavailable; AudioPolicy then falls back to the physical Built-In Mic.
     * The root helper is a tiny app_process dex that calls the hidden
     * AudioSystem.setDeviceConnectionState() API.
     */
    /**
     * Kept for manual diagnostics only. Do not call it from the service: on
     * this firmware setDeviceConnectionState can block AudioPolicyService and
     * audioserver then aborts with "TimeCheck timeout for IAudioPolicyService".
     */
    fun disableRemoteVoiceInput(): ActionResult {
        val jarBytes = try {
            context.assets.open("audio/audio-system-debug.jar").use { it.readBytes() }
        } catch (e: Exception) {
            return ActionResult(false, "audio route helper asset missing: ${e.message}")
        }
        val jar = File(context.cacheDir, "audio-system-debug.jar")
        return try {
            jar.writeBytes(jarBytes)
            val command = "CLASSPATH=${jar.absolutePath} app_process /system/bin AudioSystemDebug " +
                "0x88000000 0 remote4 OmniRemote"
            val result = shell.run(command, 20_000)
            val ok = result.ok && result.output.contains("ok")
            ActionResult(ok, result.output.ifBlank { "exit=${result.exitCode}" })
        } catch (e: Exception) {
            ActionResult(false, "audio route helper failed: ${e.message}")
        }
    }

    fun isAutostartInstalled(): Boolean {
        val result = shell.run(
            "test -f ${AutostartConfig.RC_PATH} && test -x ${AutostartConfig.LAUNCHER_PATH} && echo PRESENT",
        )
        return result.ok && result.output.contains("PRESENT")
    }

    fun installAutostart(): InstallResult {
        val launcherBytes = try {
            context.assets.open(AutostartConfig.LAUNCHER_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            return InstallResult(false, "launcher asset missing: ${e.message}")
        }
        if (launcherBytes.size < 1024) {
            return InstallResult(false, "launcher asset looks empty (${launcherBytes.size} bytes)")
        }
        val cache = File(context.cacheDir, "autostart").apply { mkdirs() }
        val rcFile = File(cache, "quasar-openio.rc")
        val launcherFile = File(cache, "quasar-openio-launcher")
        val scriptFile = File(cache, "install-autostart.sh")
        rcFile.writeText(AutostartConfig.RC_CONTENT)
        launcherFile.writeBytes(launcherBytes)
        val expectedRc = sha256(rcFile)
        val expectedLauncher = sha256(launcherFile)
        scriptFile.writeText(
            """
            #!/system/bin/sh
            set -u
            RC_SRC='${rcFile.absolutePath}'
            LAUNCHER_SRC='${launcherFile.absolutePath}'
            LAUNCHER_DIR='${AutostartConfig.LAUNCHER_DIR}'
            LAUNCHER_DST='${AutostartConfig.LAUNCHER_PATH}'
            RC_DST='${AutostartConfig.RC_PATH}'
            echo "== quasar-openio install =="
            if [ -L "${'$'}LAUNCHER_DIR" ]; then echo REFUSE_SYMLINK_DIR; exit 21; fi
            if [ -L "${'$'}LAUNCHER_DST" ]; then echo REFUSE_SYMLINK_LAUNCHER; exit 22; fi
            if [ -L "${'$'}RC_DST" ]; then echo REFUSE_SYMLINK_RC; exit 23; fi
            mount -o rw,remount /odm || { echo REMOUNT_RW_FAILED; exit 10; }
            restore_ro() { mount -o ro,remount /odm >/dev/null 2>&1 || echo REMOUNT_RO_FAILED; }
            trap restore_ro EXIT
            mkdir -p "${'$'}LAUNCHER_DIR" || exit 11
            chown 0:0 "${'$'}LAUNCHER_DIR" || exit 12
            chmod 0700 "${'$'}LAUNCHER_DIR" || exit 13
            chcon u:object_r:shell_data_file:s0 "${'$'}LAUNCHER_DIR" || exit 14
            cp -f "${'$'}LAUNCHER_SRC" "${'$'}LAUNCHER_DST.tmp" || exit 15
            chown 0:0 "${'$'}LAUNCHER_DST.tmp" || exit 16
            chmod 0755 "${'$'}LAUNCHER_DST.tmp" || exit 17
            chcon u:object_r:shell_exec:s0 "${'$'}LAUNCHER_DST.tmp" || exit 18
            mv -f "${'$'}LAUNCHER_DST.tmp" "${'$'}LAUNCHER_DST" || exit 19
            cp -f "${'$'}RC_SRC" "${'$'}RC_DST.tmp" || exit 24
            chown 0:0 "${'$'}RC_DST.tmp" || exit 25
            chmod 0644 "${'$'}RC_DST.tmp" || exit 26
            chcon u:object_r:vendor_configs_file:s0 "${'$'}RC_DST.tmp" || exit 27
            mv -f "${'$'}RC_DST.tmp" "${'$'}RC_DST" || exit 28
            sync
            echo "RC_SHA=${'$'}(sha256sum "${'$'}RC_DST" 2>/dev/null | awk '{print ${'$'}1}')"
            echo "LAUNCHER_SHA=${'$'}(sha256sum "${'$'}LAUNCHER_DST" 2>/dev/null | awk '{print ${'$'}1}')"
            ls -lZ "${'$'}RC_DST" "${'$'}LAUNCHER_DST" 2>&1
            restore_ro
            trap - EXIT
            echo INSTALL_OK
            """.trimIndent() + "\n",
        )
        val result = shell.run("sh ${scriptFile.absolutePath}", 45_000)
        val output = result.output
        val rcHashOk = output.contains("RC_SHA=$expectedRc")
        val launcherHashOk = output.contains("LAUNCHER_SHA=$expectedLauncher")
        val ok = result.ok && output.contains("INSTALL_OK") && rcHashOk && launcherHashOk
        if (!ok) Log.w(TAG, "autostart install failed: $output")
        return InstallResult(ok, output)
    }

    fun removeAutostart(): InstallResult {
        val scriptFile = File(context.cacheDir, "remove-autostart.sh")
        scriptFile.writeText(
            """
            #!/system/bin/sh
            set -u
            if [ -L '${AutostartConfig.LAUNCHER_DIR}' ]; then echo REFUSE_SYMLINK_DIR; exit 21; fi
            mount -o rw,remount /odm || { echo REMOUNT_RW_FAILED; exit 10; }
            restore_ro() { mount -o ro,remount /odm >/dev/null 2>&1 || echo REMOUNT_RO_FAILED; }
            trap restore_ro EXIT
            rm -f '${AutostartConfig.RC_PATH}' || exit 11
            rm -rf '${AutostartConfig.LAUNCHER_DIR}' || exit 12
            sync
            restore_ro
            trap - EXIT
            echo REMOVE_OK
            """.trimIndent() + "\n",
        )
        val result = shell.run("sh ${scriptFile.absolutePath}", 30_000)
        return InstallResult(result.ok && result.output.contains("REMOVE_OK"), result.output)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "QuasarRoot"
        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    }
}