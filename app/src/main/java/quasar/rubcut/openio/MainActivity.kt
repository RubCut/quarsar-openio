package quasar.rubcut.openio

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import quasar.rubcut.openio.data.root.RootManager
import quasar.rubcut.openio.service.QuasarService
import java.util.concurrent.Executors

/**
 * Visible setup screen. After a successful setup the launcher alias is
 * disabled and the app is controlled from the web UI or via am/IPC.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var statusRoot: TextView
    private lateinit var statusAutostart: TextView
    private lateinit var statusService: TextView
    private lateinit var statusSdk: TextView
    private lateinit var statusDisplay: TextView
    private lateinit var statusAudio: TextView
    private lateinit var statusWeb: TextView
    private lateinit var statusPorts: TextView
    private lateinit var logView: TextView
    private lateinit var hideIcon: CheckBox
    private lateinit var rootManager: RootManager

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "quasar-setup-ui").apply { isDaemon = true }
    }

    @Volatile
    private var rootCached = false

    @Volatile
    private var autostartCached = false

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2_000)
        }
    }

    private val probeTask = object : Runnable {
        override fun run() {
            val isRoot = runCatching { rootManager.isRoot() }.getOrDefault(false)
            val autostart = runCatching { rootManager.isAutostartInstalled() }.getOrDefault(false)
            rootCached = isRoot
            autostartCached = autostart
            ui { refreshStatus() }
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootManager = RootManager(this)

        statusRoot = findViewById(R.id.status_root)
        statusAutostart = findViewById(R.id.status_autostart)
        statusService = findViewById(R.id.status_service)
        statusSdk = findViewById(R.id.status_sdk)
        statusDisplay = findViewById(R.id.status_display)
        statusAudio = findViewById(R.id.status_audio)
        statusWeb = findViewById(R.id.status_web)
        statusPorts = findViewById(R.id.status_ports)
        logView = findViewById(R.id.log)
        hideIcon = findViewById(R.id.hide_icon)

        findViewById<Button>(R.id.button_setup).setOnClickListener { startSetup() }
        findViewById<Button>(R.id.button_start).setOnClickListener {
            QuasarService.start(this)
            log("Сервис запущен (startForegroundService)")
        }
        findViewById<Button>(R.id.button_stop).setOnClickListener {
            stopService(Intent(this, QuasarService::class.java))
            log("Сервис остановлен, io.sdk должен получить SIGCONT")
        }
        findViewById<Button>(R.id.button_web).setOnClickListener { openWeb() }
        findViewById<Button>(R.id.button_show_icon).setOnClickListener {
            setLauncherVisible(true)
            log("Иконка лаунчера включена")
        }

        log("Quasar OpenIO ${BuildConfig.VERSION_NAME}")
        handler.post(refreshTask)
        handler.postDelayed(probeTask, 300)
        if (intent?.getBooleanExtra(EXTRA_SETUP, false) == true) {
            log("Получен запрос автонастройки из install.sh")
            startSetup()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        executor.execute {
            rootCached = runCatching { rootManager.isRoot() }.getOrDefault(false)
            autostartCached = runCatching { rootManager.isAutostartInstalled() }.getOrDefault(false)
            ui { refreshStatus() }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        handler.removeCallbacks(probeTask)
        // Let an in-flight setup finish: interrupting su/Thread.join aborts the
        // privileged preparation half-way. The task is short and root-only.
        executor.shutdown()
        super.onDestroy()
    }

    private fun startSetup() {
        val hide = hideIcon.isChecked
        log("== настройка ==")
        executor.execute {
            val isRoot = runCatching { rootManager.isRoot() }.getOrDefault(false)
            rootCached = isRoot
            ui { log(if (isRoot) "root: OK" else "root: НЕТ (su недоступен или отклонён)") }
            if (isRoot) {
                val selinux = runCatching { rootManager.selinux() }.getOrDefault("unknown")
                ui { log("SELinux: $selinux") }
                val prepared = rootManager.prepareRuntime(packageName)
                ui { log("runtime/chmod: ${if (prepared.ok) "OK" else "ОШИБКА"} ${prepared.message.take(300)}") }
                val autostartWanted = getSharedPreferences("quasar_settings", MODE_PRIVATE)
                    .getBoolean("autostart_enabled", true)
                if (!autostartWanted) {
                    ui { log("автозапуск: отключён в настройках") }
                } else if (!rootManager.isAutostartInstalled()) {
                    val installed = rootManager.installAutostart()
                    ui { log("автозапуск: ${if (installed.ok) "OK" else "ОШИБКА"}") }
                    if (!installed.ok) ui { log(installed.output.takeLast(600)) }
                } else {
                    ui { log("автозапуск: уже установлен") }
                }
            }

            try {
                QuasarService.start(this)
                ui { log("foreground-сервис: запрос отправлен") }
            } catch (e: Exception) {
                ui { log("ошибка запуска сервиса: ${e.message}") }
            }

            try {
                Thread.sleep(2_500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val sdk = runCatching { rootManager.sdkState() }.getOrNull()
            if (sdk != null) {
                ui {
                    log(
                        "io.sdk: " + if (!sdk.running) "не запущен" else
                            "pid=${sdk.pids.joinToString(",")}" + if (sdk.stopped) " [STOP]" else " [RUN]",
                    )
                }
            }

            autostartCached = runCatching { rootManager.isAutostartInstalled() }.getOrDefault(false)
            if (hide && isRoot) {
                setLauncherVisible(false)
                ui { log("иконка скрыта; вход: am start -n quasar.rubcut.openio/.MainActivity") }
            }
            ui {
                refreshStatus()
                log("готово; веб-интерфейс: http://${localIp()}:${QuasarService.HTTP_PORT}")
            }
        }
    }

    private fun refreshStatus() {
        statusService.text = "Сервис: " + if (QuasarService.serviceRunning) "работает" else "остановлен"
        statusAutostart.text = "Автозапуск: " + if (autostartCached) "установлен" else "нет"
        statusRoot.text = "Root: " + if (rootCached) "OK" else "нет"
        statusWeb.text = "Веб: http://${localIp()}:${QuasarService.HTTP_PORT}"
        statusPorts.text = "Порты: ${QuasarService.HTTP_PORT} HTTP · ${QuasarService.IPC_PORT} IPC · ${QuasarService.PCM_PORT} PCM"

        val raw = QuasarService.lastStatus()
        if (raw == "{}" || raw.isBlank()) {
            statusSdk.text = "io.sdk: сервис не публикует статус"
            statusDisplay.text = "Дисплей: неизвестно"
            statusAudio.text = "Аудио: неизвестно"
            return
        }
        runCatching {
            val json = JSONObject(raw)
            val sdk = json.optJSONObject("sdk")
            statusSdk.text = "io.sdk: " + when {
                sdk == null -> "неизвестно"
                !sdk.optBoolean("running") -> "не запущен"
                sdk.optBoolean("frozen") -> "STOP " + sdk.optJSONArray("pids")
                else -> "RUN " + sdk.optJSONArray("pids")
            }
            val display = json.optJSONObject("display")
            statusDisplay.text = "Дисплей: " + if (display?.optBoolean("started") == true) {
                display.optString("info", "mmap OK")
            } else {
                "не запущен"
            }
            val audio = json.optJSONObject("audio")
            statusAudio.text = "Аудио: вход=" + if (audio?.optBoolean("capture") == true) "OK" else "нет" +
                " · выход=" + if (audio?.optBoolean("output") == true) "OK" else "нет"
            statusWeb.text = "Веб: http://${json.optString("ip", localIp())}:${QuasarService.HTTP_PORT}"
        }.onFailure {
            log("не удалось разобрать статус: ${it.message}")
        }
    }

    private fun openWeb() {
        val url = "http://127.0.0.1:${QuasarService.HTTP_PORT}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            log("На устройстве нет браузера. Откройте с другого устройства: http://${localIp()}:${QuasarService.HTTP_PORT}")
        }
    }

    private fun setLauncherVisible(visible: Boolean) {
        val component = ComponentName(this, QuasarService.LAUNCHER_ALIAS)
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }.onFailure { log("не удалось изменить состояние иконки: ${it.message}") }
    }

    private fun localIp(): String {
        return try {
            for (nic in java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in java.util.Collections.list(nic.inetAddresses)) {
                    if (address is java.net.Inet4Address && address.isSiteLocalAddress) {
                        return address.hostAddress ?: continue
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun log(line: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val text = "[$timestamp] $line\n"
        logView.append(text)
    }

    private fun ui(block: () -> Unit) {
        handler.post(block)
    }

    companion object {
        const val EXTRA_SETUP = "setup"
    }
}
