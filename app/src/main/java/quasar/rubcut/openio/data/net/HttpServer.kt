package quasar.rubcut.openio.data.net

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import quasar.rubcut.openio.domain.audio.Pcm
import quasar.rubcut.openio.service.ControlApi
import quasar.rubcut.openio.service.RendererConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small HTTP/1.1 server: web UI, REST control, bitmap previews, WAV/raw mic
 * snapshots and an SSE event stream. There is intentionally no framework
 * dependency, which keeps the APK tiny and the service self-contained.
 */
class HttpServer(
    private val port: Int,
    private val api: ControlApi,
    private val context: Context,
) {
    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val loopback: Boolean,
    )

    private sealed class Result {
        class Full(
            val status: Int = 200,
            val contentType: String = "text/plain; charset=utf-8",
            val body: ByteArray = ByteArray(0),
            val extraHeaders: Map<String, String> = emptyMap(),
        ) : Result()

        class Stream(
            val contentType: String,
            val writer: (OutputStream, Socket) -> Unit,
        ) : Result()
    }

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "quasar-http-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    fun start(): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = socket
            running = true
            acceptThread = Thread({ acceptLoop(socket) }, "quasar-http-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "HTTP listening on 0.0.0.0:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "cannot bind HTTP port $port: ${e.message}", e)
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        executor.shutdownNow()
        Log.i(TAG, "HTTP stopped")
    }

    fun isRunning(): Boolean = running

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
            clients.add(client)
            executor.execute {
                try {
                    handleClient(client)
                } catch (e: Exception) {
                    // A browser navigating away mid-response is routine noise,
                    // not a service fault: log it at debug level.
                    if (isClientDisconnect(e)) {
                        Log.d(TAG, "client disconnected: ${e.message}")
                    } else {
                        Log.w(TAG, "client failed: ${e.message}")
                    }
                } finally {
                    clients.remove(client)
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 15_000
        socket.tcpNoDelay = true
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream().buffered()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return
        val requestLine = lines.first().split(' ')
        if (requestLine.size < 2) return
        val method = requestLine[0].uppercase()
        val target = requestLine[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val headers = buildMap {
            for (line in lines.drop(1)) {
                val index = line.indexOf(':')
                if (index > 0) {
                    put(line.substring(0, index).trim().lowercase(), line.substring(index + 1).trim())
                }
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > MAX_BODY) {
            writeResponse(output, Result.Full(413, body = "body too large".toByteArray()), socket)
            return
        }
        val body = readExactly(input, contentLength)
        val loopback = runCatching { socket.inetAddress?.isLoopbackAddress == true }.getOrDefault(false)
        val request = Request(method, path, query, headers, body, loopback)
        val result = try {
            route(request)
        } catch (e: Exception) {
            Log.e(TAG, "route ${request.path} failed: ${e.message}", e)
            Result.Full(500, "application/json; charset=utf-8", errorJson(e.message ?: "internal error"))
        }
        writeResponse(output, result, socket)
        output.flush()
    }

    private fun route(request: Request): Result {
        if (request.method == "OPTIONS") {
            return Result.Full(204, extraHeaders = CORS)
        }
        val path = request.path
        if (path.startsWith("/api/")) {
            return apiRoute(request)
        }
        return staticAsset(path)
    }

    private fun apiRoute(request: Request): Result {
        val path = request.path
        val query = request.query
        return when {
            path == "/api/status" -> json(api.statusJson())
            path == "/api/health" -> Result.Full(body = "ok".toByteArray())
            path == "/api/events" -> sse()
            path == "/api/frame" && request.method == "GET" -> frameJson()
            path == "/api/frame.rgb" -> Result.Full(
                contentType = "application/octet-stream",
                body = api.frameRgb(),
            )
            path == "/api/frame.bmp" -> Result.Full(
                contentType = "image/bmp",
                body = Bmp.encodeRgb888(api.frameRgb(), 25, 16),
            )
            path == "/api/ring" && request.method == "GET" -> ringJson()
            path == "/api/ring.rgb" -> Result.Full(
                contentType = "application/octet-stream",
                body = api.ringRgb(),
            )
            path == "/api/ring.bmp" -> Result.Full(
                contentType = "image/bmp",
                body = Bmp.encodeRgb888(api.ringRgb(), 24, 1),
            )
            path == "/api/mic.pcm" -> micRaw(query)
            path == "/api/mic.wav" -> micWav(query)
            path == "/api/animations" && request.method == "GET" -> json(api.animationsJson())
            path == "/api/animations" -> postAnimation(request)
            path == "/api/plugins" && request.method == "GET" -> json(api.pluginsJson())
            path == "/api/mods" && request.method == "GET" -> json(api.modsJson())
            path == "/api/catalog" -> json(api.catalogJson())
            path == "/api/screen" && request.method == "GET" -> screenState()
            path == "/api/screen" -> postScreen(request)
            path == "/api/features" -> postFeatures(request)
            path == "/api/plugins/install" -> postPluginInstall(request)
            path == "/api/plugins/remove" -> postPluginRemove(request)
            path == "/api/plugins/config" -> postPluginConfig(request)
            path == "/api/plugins/activate" -> postPluginActivate(request)
            path == "/api/plugins/toggle" -> postPluginToggle(request)
            path == "/api/plugins/service" -> postPluginService(request)
            // Mods endpoints: the new naming for the same operations.
            path == "/api/mods/install" -> postPluginInstall(request)
            path == "/api/mods/remove" -> postPluginRemove(request)
            path == "/api/mods/config" -> postPluginConfig(request)
            path == "/api/mods/activate" -> postModActivate(request)
            path == "/api/mods/enable" -> postPluginToggle(request)
            path == "/api/mods/service" -> postPluginService(request)
            path == "/api/logs" -> getLogs(request)
            path == "/api/logs/clear" -> clearLogs(request)
            path == "/api/shell" -> postShell(request)
            path == "/api/pipeline/restart" -> json(
                JSONObject().put("ok", api.restartPipeline()).toString(),
            )
            path == "/api/ring/pattern" -> postRingPattern(request)
            path == "/api/animations/stop" -> json(
                JSONObject().put("ok", api.stopAnimation()).toString(),
            )
            path == "/api/clock" -> postClock(request)
            path == "/api/ring" && request.method == "POST" && query["action"] == "clear" -> json(
                JSONObject().put("ok", api.clearRingOverride()).toString(),
            )
            path == "/api/frame" && request.method == "POST" -> postFrame(request)
            path == "/api/ring" && request.method == "POST" -> postRing(request)
            path == "/api/mode" -> postMode(request)
            path == "/api/render" && request.method == "GET" -> renderJson()
            path == "/api/render" -> postRender(request)
            path == "/api/autostart" -> postAutostart(request)
            path == "/api/freeze" || path == "/api/sdk" -> postFreeze(request)
            path == "/api/tone" -> postTone(request)
            path == "/api/volume" -> postVolume(request)
            path == "/api/icon" -> postIcon(request)
            path == "/api/general" && request.method == "GET" -> json(api.generalJson())
            path == "/api/general" -> postGeneral(request)
            path == "/api/media-anim" -> postMediaAnim(request)
            path == "/api/remap" && request.method == "GET" -> json(api.remapJson())
            path == "/api/remap" -> postRemap(request)
            path == "/api/alice" -> postAlice(request)
            else -> Result.Full(404, body = errorJson("unknown endpoint $path"))
        }
    }

    private fun staticAsset(path: String): Result {
        val normalized = when (path) {
            "/", "/index.html" -> "index.html"
            "/app.js" -> "app.js"
            "/style.css" -> "style.css"
            "/favicon.ico" -> return Result.Full(204)
            else -> path.removePrefix("/")
        }
        if (normalized.contains("..") || normalized.startsWith("/")) {
            return Result.Full(404, body = "not found".toByteArray())
        }
        val bytes = try {
            context.assets.open("web/$normalized").use { it.readBytes() }
        } catch (_: Exception) {
            return Result.Full(404, body = "not found".toByteArray())
        }
        val type = when {
            normalized.endsWith(".html") -> "text/html; charset=utf-8"
            normalized.endsWith(".js") -> "application/javascript; charset=utf-8"
            normalized.endsWith(".css") -> "text/css; charset=utf-8"
            normalized.endsWith(".json") -> "application/json; charset=utf-8"
            normalized.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
        return Result.Full(contentType = type, body = bytes)
    }

    private fun frameJson(): Result {
        val json = JSONObject()
            .put("width", 25)
            .put("height", 16)
            .put("encoding", "rgb888")
            .put("data", Base64.encodeToString(api.frameRgb(), Base64.NO_WRAP))
        return json(json.toString())
    }

    private fun ringJson(): Result {
        val json = JSONObject()
            .put("size", 24)
            .put("encoding", "rgb888")
            .put("data", Base64.encodeToString(api.ringRgb(), Base64.NO_WRAP))
        return json(json.toString())
    }

    private fun micRaw(query: Map<String, String>): Result {
        val samples = api.micPcm(sampleCount(query))
        return Result.Full(
            contentType = "application/octet-stream",
            body = Pcm.toLittleEndian(samples),
            extraHeaders = mapOf("X-Sample-Rate" to "16000", "X-Channels" to "1"),
        )
    }

    private fun micWav(query: Map<String, String>): Result {
        val samples = api.micPcm(sampleCount(query))
        return Result.Full(
            contentType = "audio/wav",
            body = Wav.encodePcm16(samples, 16_000, 1),
            extraHeaders = mapOf("Content-Disposition" to "inline; filename=\"quasar-mic.wav\""),
        )
    }

    private fun sampleCount(query: Map<String, String>): Int {
        val ms = query["ms"]?.toIntOrNull()?.coerceIn(20, 5_000) ?: 500
        return ms * 16
    }

    private fun postFrame(request: Request): Result {
        val bytes = when {
            request.body.size == 1200 -> request.body
            else -> decodeJsonData(request.body) ?: return Result.Full(400, body = errorJson("expected 1200 RGB888 bytes"))
        }
        val ok = api.drawScreen(bytes)
        return json(JSONObject().put("ok", ok).put("command", "draw_screen").toString())
    }

    private fun postRing(request: Request): Result {
        val bytes = when {
            request.body.size == 72 -> request.body
            else -> decodeJsonData(request.body) ?: return Result.Full(400, body = errorJson("expected 72 RGB888 bytes"))
        }
        val ok = api.drawRing(bytes)
        return json(JSONObject().put("ok", ok).put("command", "draw_ring").toString())
    }

    private fun postMode(request: Request): Result {
        val mode = request.query["mode"]
            ?: runCatching { JSONObject(request.body.toString(StandardCharsets.UTF_8)).optString("mode") }.getOrNull()
            ?: request.body.toString(StandardCharsets.UTF_8).trim()
        if (mode.isBlank()) return Result.Full(400, body = errorJson("mode is required"))
        val ok = api.setMode(mode)
        return json(JSONObject().put("ok", ok).put("mode", mode).toString())
    }

    private fun renderJson(): Result {
        val render = JSONObject(api.statusJson()).optJSONObject("render") ?: JSONObject()
        return json(render.put("ok", true).toString())
    }

    /**
     * Updates the user-tunable look of the display. All fields are optional:
     * ring_color (#RRGGBB), ring_enabled (true/false), scope_fps (1..50),
     * scope_sensitivity (0.25..8).
     */
    private fun postClock(request: Request): Result {
        val body = runCatching {
            JSONObject(request.body.toString(StandardCharsets.UTF_8))
        }.getOrNull()
        val size = request.query["size"]
            ?: body?.optString("size", "")
            ?: ""
        if (size.isBlank()) {
            return Result.Full(400, body = errorJson("size must be large, middle or small"))
        }
        val ok = api.setClockSize(size)
        val status = JSONObject(api.statusJson()).optJSONObject("render") ?: JSONObject()
        return json(status.put("ok", ok).toString())
    }

    private fun postAnimation(request: Request): Result {
        val body = runCatching {
            JSONObject(request.body.toString(StandardCharsets.UTF_8))
        }.getOrNull()
        val id = request.query["id"] ?: body?.optString("id", "")?.takeIf { it.isNotBlank() }
        val idle = (request.query["idle"] ?: body?.optString("idle"))?.lowercase()
        if (id == null) {
            // Stopping the overlay, or clearing the idle clip when idle=true.
            if (idle == "true" || idle == "1") {
                return json(JSONObject().put("ok", api.setIdleAnimation(null)).toString())
            }
            return json(JSONObject().put("ok", api.stopAnimation()).toString())
        }
        return when (idle) {
            "true", "1" -> json(
                JSONObject().put("ok", api.setIdleAnimation(id)).put("idle", id).toString(),
            )
            else -> {
                val loop = when ((request.query["loop"] ?: body?.optString("loop"))?.lowercase()) {
                    "1", "true", "on" -> true
                    else -> false
                }
                val ok = api.playAnimation(id, loop)
                val status = JSONObject(api.statusJson()).optJSONObject("animation") ?: JSONObject()
                json(status.put("ok", ok).put("id", id).toString())
            }
        }
    }

    private fun postPluginInstall(request: Request): Result {
        if (request.body.isEmpty()) {
            return Result.Full(400, body = errorJson("empty archive"))
        }
        val first = request.body.firstOrNull()?.toInt()?.toChar()
        val bytes = if (first == '{') {
            decodeJsonData(request.body)
                ?: return Result.Full(400, body = errorJson("expected a zip archive or base64 data"))
        } else {
            request.body
        }
        return json(api.pluginInstall(bytes))
    }

    private fun postPluginRemove(request: Request): Result {
        val body = bodyJson(request)
        val id = request.query["id"] ?: body?.optString("id", "") ?: ""
        if (id.isBlank()) return Result.Full(400, body = errorJson("id is required"))
        return json(api.pluginRemove(id))
    }

    private fun postPluginConfig(request: Request): Result {
        val body = bodyJson(request)
            ?: return Result.Full(400, body = errorJson("expected JSON body"))
        val provider = request.query["provider"] ?: body.optString("provider", "")
        if (provider.isBlank()) return Result.Full(400, body = errorJson("provider is required"))
        val source = body.optJSONObject("values") ?: body.optJSONObject("config") ?: JSONObject()
        val values = LinkedHashMap<String, String>()
        for (key in source.keys()) values[key] = source.optString(key)
        return json(api.pluginConfig(provider, values))
    }

    private fun postPluginActivate(request: Request): Result {
        val body = bodyJson(request)
        val provider = request.query["provider"] ?: body?.optString("provider", "") ?: ""
        if (provider.isBlank()) return Result.Full(400, body = errorJson("provider is required"))
        val loop = boolParam(
            request.query["loop"] ?: body?.optString("loop"),
            default = false,
        )
        val ok = api.pluginActivate(provider, loop)
        return json(JSONObject().put("ok", ok).put("provider", provider).put("loop", loop).toString())
    }

    private fun postModActivate(request: Request): Result {
        val body = bodyJson(request)
        val item = request.query["item"]
            ?: body?.optString("item")?.takeIf { it.isNotBlank() }
            ?: body?.optString("provider")?.takeIf { it.isNotBlank() }
            ?: ""
        if (item.isBlank()) return Result.Full(400, body = errorJson("item is required"))
        val loop = boolParam(
            request.query["loop"] ?: body?.opt("loop")?.toString(),
            default = true,
        )
        return jsonResult(api.modsActivate(item, loop))
    }

    private fun screenState(): Result {
        val status = JSONObject(api.statusJson())
        return json(
            JSONObject()
                .put("ok", true)
                .put("selection", status.optJSONObject("selection") ?: JSONObject())
                .put("features", status.optJSONObject("features") ?: JSONObject())
                .put("mode", status.optString("mode"))
                .put("mode_effective", status.optString("mode_effective"))
                .toString(),
        )
    }

    private fun postScreen(request: Request): Result {
        val body = bodyJson(request)
        val type = request.query["type"] ?: body?.optString("type") ?: ""
        if (type.isBlank()) return Result.Full(400, body = errorJson("type is required"))
        val provider = request.query["provider"]
            ?: body?.optString("provider")?.takeIf { it.isNotBlank() }
        val loop = boolParam(request.query["loop"] ?: body?.opt("loop")?.toString(), default = true)
        return jsonResult(api.selectProvider(type, provider, loop))
    }

    private fun postFeatures(request: Request): Result {
        val body = bodyJson(request)
        val source = body?.optJSONObject("features") ?: body ?: JSONObject()
        val values = LinkedHashMap<String, Boolean>()
        for (key in listOf("clock", "visualizer", "animation", "ring")) {
            val raw = request.query[key] ?: if (source.has(key)) source.opt(key).toString() else null
            if (raw != null) values[key] = boolParam(raw, default = false)
        }
        if (values.isEmpty()) {
            return Result.Full(400, body = errorJson("clock, visualizer, animation or ring is required"))
        }
        return jsonResult(api.setFeatures(values))
    }

    private fun postPluginToggle(request: Request): Result {
        val body = bodyJson(request)
        val pack = request.query["pack"] ?: body?.optString("pack")?.takeIf { it.isNotBlank() }
        val provider = request.query["provider"] ?: body?.optString("provider")?.takeIf { it.isNotBlank() }
        val enabledText = request.query["enabled"] ?: body?.opt("enabled")?.toString()
        if (enabledText == null || (pack == null && provider == null)) {
            return Result.Full(400, body = errorJson("pack/provider and enabled are required"))
        }
        return jsonResult(api.setPluginEnabled(pack, provider, boolParam(enabledText, default = true)))
    }

    private fun postPluginService(request: Request): Result {
        val body = bodyJson(request)
        val provider = request.query["provider"] ?: body?.optString("provider") ?: ""
        if (provider.isBlank()) return Result.Full(400, body = errorJson("provider is required"))
        val action = (request.query["action"] ?: body?.optString("action") ?: "start").lowercase()
        val ok = when (action) {
            "start" -> api.pluginStart(provider)
            "stop" -> api.pluginStop(provider)
            "restart" -> {
                api.pluginStop(provider)
                api.pluginStart(provider)
            }
            else -> return Result.Full(400, body = errorJson("action must be start, stop or restart"))
        }
        return json(
            JSONObject()
                .put("ok", ok)
                .put("provider", provider)
                .put("action", action)
                .toString(),
        )
    }

    private fun getLogs(request: Request): Result {
        val scope = request.query["scope"] ?: "global"
        val plugin = request.query["plugin"]
        val limit = request.query["limit"]?.toIntOrNull() ?: 300
        return json(api.logsJson(scope, plugin, limit))
    }

    private fun clearLogs(request: Request): Result {
        val body = bodyJson(request)
        val scope = request.query["scope"] ?: body?.optString("scope") ?: "global"
        val plugin = request.query["plugin"] ?: body?.optString("plugin")?.takeIf { it.isNotBlank() }
        return json(JSONObject().put("ok", api.clearLogs(scope, plugin)).toString())
    }

    private fun postShell(request: Request): Result {
        val body = bodyJson(request)
        val command = request.query["command"] ?: body?.optString("command") ?: ""
        if (command.isBlank()) return Result.Full(400, body = errorJson("command is required"))
        val token = request.headers["x-quasar-token"]
            ?: request.query["token"]
            ?: body?.optString("token")?.takeIf { it.isNotBlank() }
        val timeout = request.query["timeout_ms"]?.toIntOrNull()
            ?: body?.optInt("timeout_ms", 10_000)
            ?: 10_000
        val text = api.shell(command, timeout, request.loopback, token)
        val parsed = runCatching { JSONObject(text) }.getOrNull()
        if (parsed?.optBoolean("ok") == false && parsed.optString("error") == "invalid token") {
            return Result.Full(403, "application/json; charset=utf-8", text.toByteArray())
        }
        return Result.Full(200, "application/json; charset=utf-8", text.toByteArray())
    }

    private fun jsonResult(text: String): Result =
        Result.Full(200, "application/json; charset=utf-8", text.toByteArray())

    private fun postRingPattern(request: Request): Result {
        val body = bodyJson(request)
        val provider = request.query["provider"] ?: body?.optString("provider", "") ?: ""
        val loop = boolParam(request.query["loop"] ?: body?.optString("loop"), default = true)
        if (provider.isBlank()) {
            return json(JSONObject().put("ok", api.clearRingPattern()).put("provider", JSONObject.NULL).toString())
        }
        val ok = api.pluginActivate(provider, loop)
        return json(JSONObject().put("ok", ok).put("provider", provider).put("loop", loop).toString())
    }

    private fun bodyJson(request: Request): JSONObject? = runCatching {
        JSONObject(request.body.toString(StandardCharsets.UTF_8))
    }.getOrNull()

    private fun boolParam(value: String?, default: Boolean): Boolean = when (value?.lowercase()) {
        "1", "true", "on", "yes" -> true
        "0", "false", "off", "no" -> false
        else -> default
    }

    private fun postRender(request: Request): Result {
        val body = runCatching {
            JSONObject(request.body.toString(StandardCharsets.UTF_8))
        }.getOrNull()
        var touched = false
        var ok = true

        val colorText = request.query["ring_color"]
            ?: body?.takeIf { it.has("ring_color") }?.optString("ring_color")
        if (colorText != null) {
            val rgb = RendererConfig.parseColor(colorText)
                ?: return Result.Full(400, body = errorJson("ring_color must be #RRGGBB"))
            ok = api.setRingColor(rgb) && ok
            touched = true
        }

        val ringEnabledText = request.query["ring_enabled"]
            ?: body?.takeIf { it.has("ring_enabled") }?.optString("ring_enabled")
        if (ringEnabledText != null) {
            val enabled = when (ringEnabledText.lowercase()) {
                "1", "true", "on", "enable", "enabled" -> true
                "0", "false", "off", "disable", "disabled" -> false
                else -> return Result.Full(400, body = errorJson("ring_enabled must be true or false"))
            }
            ok = api.setRingEnabled(enabled) && ok
            touched = true
        }

        val ringSpectrumText = request.query["ring_spectrum"]
            ?: body?.takeIf { it.has("ring_spectrum") }?.optString("ring_spectrum")
        if (ringSpectrumText != null) {
            val enabled = when (ringSpectrumText.lowercase()) {
                "1", "true", "on", "enable", "enabled" -> true
                "0", "false", "off", "disable", "disabled" -> false
                else -> return Result.Full(400, body = errorJson("ring_spectrum must be true or false"))
            }
            ok = api.setRingSpectrum(enabled) && ok
            touched = true
        }

        val fpsText = request.query["scope_fps"]
            ?: body?.takeIf { it.has("scope_fps") }?.optString("scope_fps")
        if (fpsText != null) {
            val fps = fpsText.toIntOrNull()
                ?: return Result.Full(400, body = errorJson("scope_fps must be an integer"))
            ok = api.setScopeFps(fps) && ok
            touched = true
        }

        val sensitivityText = request.query["scope_sensitivity"]
            ?: body?.takeIf { it.has("scope_sensitivity") }?.optString("scope_sensitivity")
        if (sensitivityText != null) {
            val sensitivity = sensitivityText.toFloatOrNull()
                ?: return Result.Full(400, body = errorJson("scope_sensitivity must be a number"))
            ok = api.setScopeSensitivity(sensitivity) && ok
            touched = true
        }

        val smoothText = request.query["scope_smooth"]
            ?: body?.takeIf { it.has("scope_smooth") }?.optString("scope_smooth")
        if (smoothText != null) {
            val smooth = smoothText.toIntOrNull()
                ?: return Result.Full(400, body = errorJson("scope_smooth must be milliseconds"))
            if (smooth < 0) return Result.Full(400, body = errorJson("scope_smooth must be 0 or more"))
            ok = api.setScopeSmooth(smooth) && ok
            touched = true
        }

        val captureRateText = request.query["capture_rate"]
            ?: body?.takeIf { it.has("capture_rate") }?.optString("capture_rate")
        if (captureRateText != null) {
            val rate = captureRateText.toIntOrNull()
                ?: return Result.Full(400, body = errorJson("capture_rate must be 16000 or 48000"))
            if (rate != 48_000 && rate != 16_000) {
                return Result.Full(400, body = errorJson("capture_rate must be 16000 or 48000"))
            }
            api.setCaptureRate(rate)
            touched = true
        }

        val clockSizeText = request.query["clock_size"]
            ?: body?.takeIf { it.has("clock_size") }?.optString("clock_size")
        if (clockSizeText != null) {
            ok = api.setClockSize(clockSizeText) && ok
            touched = true
        }

        val ringOriginText = request.query["ring_origin"]
            ?: body?.takeIf { it.has("ring_origin") }?.optString("ring_origin")
        if (ringOriginText != null) {
            val index = ringOriginText.toIntOrNull()
                ?: return Result.Full(400, body = errorJson("ring_origin must be 0..23"))
            if (index !in 0..23) return Result.Full(400, body = errorJson("ring_origin must be 0..23"))
            ok = api.setRingOrigin(index) && ok
            touched = true
        }

        val scopeBandText = request.query["scope_band"]
            ?: body?.takeIf { it.has("scope_band") }?.optString("scope_band")
        if (scopeBandText != null) {
            ok = api.setScopeBand(scopeBandText) && ok
            touched = true
        }

        val autoText = request.query["auto"]
            ?: body?.takeIf { it.has("auto") }?.optString("auto")
        if (autoText != null) {
            val enabled = boolParam(autoText, default = false)
            ok = api.setMode(if (enabled) "auto" else "clock") && ok
            touched = true
        }

        val animationColorText = request.query["animation_color"]
            ?: body?.takeIf { it.has("animation_color") }?.optString("animation_color")
        if (animationColorText != null) {
            val rgb = RendererConfig.parseColor(animationColorText)
                ?: return Result.Full(400, body = errorJson("animation_color must be #RRGGBB"))
            ok = api.setAnimationColor(rgb) && ok
            touched = true
        }

        if (!touched) {
            return Result.Full(
                400,
                body = errorJson(
                    "ring_color, ring_enabled, ring_spectrum, scope_fps, scope_sensitivity, scope_smooth, scope_band, ring_origin, capture_rate, clock_size, animation_color or auto is required",
                ),
            )
        }
        val render = JSONObject(api.statusJson()).optJSONObject("render") ?: JSONObject()
        return json(render.put("ok", ok).toString())
    }

    private fun postAutostart(request: Request): Result {
        val action = request.query["enabled"]
            ?: request.query["action"]
            ?: request.query["state"]
            ?: runCatching {
                val json = JSONObject(request.body.toString(StandardCharsets.UTF_8))
                json.optString("enabled", json.optString("action", json.optString("state")))
            }.getOrNull()
            ?: "toggle"
        val current = runCatching {
            JSONObject(api.statusJson()).getJSONObject("autostart").optBoolean("enabled")
        }.getOrDefault(false)
        val enabled = when (action.lowercase()) {
            "1", "true", "on", "enable", "enabled" -> true
            "0", "false", "off", "disable", "disabled" -> false
            "toggle" -> !current
            else -> return Result.Full(400, body = errorJson("enabled must be true or false"))
        }
        val ok = api.setAutostartEnabled(enabled)
        val status = JSONObject(api.statusJson()).optJSONObject("autostart") ?: JSONObject()
        return json(status.put("ok", ok).toString())
    }

    private fun postFreeze(request: Request): Result {
        val action = request.query["action"]
            ?: request.query["state"]
            ?: runCatching {
                val json = JSONObject(request.body.toString(StandardCharsets.UTF_8))
                json.optString("action", json.optString("state"))
            }.getOrNull()
            ?: "toggle"
        val frozenBefore = stateIsFrozen()
        val freeze = when (action.lowercase()) {
            "freeze", "stop", "on", "1", "true" -> true
            "unfreeze", "resume", "cont", "off", "0", "false" -> false
            "toggle" -> !frozenBefore
            else -> return Result.Full(400, body = errorJson("action must be freeze, unfreeze or toggle"))
        }
        val ok = if (freeze) api.freezeSdk() else api.unfreezeSdk()
        return json(
            JSONObject()
                .put("ok", ok)
                .put("action", if (freeze) "freeze" else "unfreeze")
                .toString(),
        )
    }

    private fun stateIsFrozen(): Boolean = runCatching {
        JSONObject(api.statusJson()).getJSONObject("sdk").optBoolean("frozen")
    }.getOrDefault(false)

    private fun postTone(request: Request): Result {
        val json = runCatching { JSONObject(request.body.toString(StandardCharsets.UTF_8)) }.getOrNull()
        val frequency = request.query["frequency"]?.toDoubleOrNull()
            ?: json?.optDouble("frequency", 440.0)
            ?: 440.0
        val duration = request.query["ms"]?.toIntOrNull()
            ?: json?.optInt("ms", 500)
            ?: 500
        val ok = api.playTone(frequency, duration)
        return json(JSONObject().put("ok", ok).put("frequency", frequency).put("ms", duration).toString())
    }

    private fun postVolume(request: Request): Result {
        val value = request.query["value"]?.toFloatOrNull()
            ?: runCatching { JSONObject(request.body.toString(StandardCharsets.UTF_8)).optDouble("value", -1.0).toFloat() }.getOrNull()
        if (value == null || value < 0f || value > 1f) {
            return Result.Full(400, body = errorJson("value must be in 0..1"))
        }
        val ok = api.setVolume(value)
        return json(JSONObject().put("ok", ok).put("value", value).toString())
    }

    private fun postIcon(request: Request): Result {
        val visible = when (request.query["visible"]?.lowercase()) {
            "1", "true", "on", "show" -> true
            "0", "false", "off", "hide" -> false
            else -> return Result.Full(400, body = errorJson("visible must be true or false"))
        }
        val ok = api.setIconVisible(visible)
        return json(JSONObject().put("ok", ok).put("visible", visible).toString())
    }

    /** Boolean field lookup: query param first, then a JSON body field (bool or string). */
    private fun optBoolField(request: Request, body: JSONObject?, key: String): Boolean? {
        if (request.query.containsKey(key)) return boolParam(request.query[key], true)
        if (body?.has(key) == true) {
            return when (val raw = body.opt(key)) {
                is Boolean -> raw
                is String -> boolParam(raw, true)
                is Number -> raw.toDouble() != 0.0
                else -> null
            }
        }
        return null
    }

    private fun postGeneral(request: Request): Result {
        val body = bodyJson(request)
        var touched = false
        optBoolField(request, body, "master")?.let { api.setMaster(it); touched = true }
        optBoolField(request, body, "screen")?.let { api.setScreen(it); touched = true }
        optBoolField(request, body, "mic_share")?.let { api.setMicShare(it); touched = true }
        optBoolField(request, body, "mic_passthrough")?.let { api.setMicPassthrough(it); touched = true }
        val ringVolumeEnabled = optBoolField(request, body, "ring_volume_enabled")
        val ringVolumeAnim = optBoolField(request, body, "ring_volume_anim")
        val ringVolumeStep = when {
            request.query.containsKey("ring_volume_step") -> request.query["ring_volume_step"]?.toIntOrNull()
            body?.has("ring_volume_step") == true -> body.optInt("ring_volume_step", -1).takeIf { it >= 0 }
            else -> null
        }
        if (ringVolumeEnabled != null || ringVolumeStep != null || ringVolumeAnim != null) {
            api.setRingVolume(ringVolumeEnabled, ringVolumeStep, ringVolumeAnim)
            touched = true
        }
        if (!touched) {
            return Result.Full(400, body = errorJson(
                "expected one of master, screen, mic_share, mic_passthrough, ring_volume_enabled, ring_volume_step, ring_volume_anim",
            ))
        }
        return json(api.generalJson())
    }

    private fun postMediaAnim(request: Request): Result {
        val body = bodyJson(request)
        val enabled = optBoolField(request, body, "enabled")
        fun clip(key: String): String? =
            request.query[key] ?: body?.takeIf { it.has(key) }?.optString(key)
        val ok = api.setMediaAnimation(enabled, clip("play"), clip("pause"), clip("stop"))
        return json(JSONObject(api.mediaAnimJson()).put("ok", ok).toString())
    }

    private fun postRemap(request: Request): Result {
        val body = bodyJson(request)
        val reset = request.query["reset"]?.let { boolParam(it, true) }
            ?: body?.takeIf { it.has("reset") }?.optBoolean("reset")
            ?: false
        if (reset) {
            val ok = api.resetRemapBindings()
            return json(JSONObject(api.remapJson()).put("ok", ok).put("reset", true).toString())
        }
        val key = request.query["key"] ?: body?.optString("key") ?: ""
        val action = request.query["action"] ?: body?.optString("action") ?: ""
        if (key.isBlank()) return Result.Full(400, body = errorJson("key is required"))
        if (action.isBlank()) return Result.Full(400, body = errorJson("action is required"))
        val ok = api.setRemapBinding(key, action)
        return json(JSONObject(api.remapJson()).put("ok", ok).toString())
    }

    private fun postAlice(request: Request): Result {
        val ok = api.activateAlice()
        return json(JSONObject().put("ok", ok).toString())
    }

    private fun sse(): Result = Result.Stream("text/event-stream") { output, socket ->
        val lock = Any()
        val subscription = api.addEventListener { event ->
            synchronized(lock) {
                runCatching {
                    output.write("data: $event\n\n".toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            }
        }
        try {
            synchronized(lock) {
                output.write(": connected\n\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
            while (running && !socket.isClosed) {
                Thread.sleep(15_000)
                synchronized(lock) {
                    output.write(": ping\n\n".toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            }
        } catch (_: Exception) {
            // Client closed the tab; normal end of an SSE stream.
        } finally {
            subscription.close()
        }
    }

    private fun writeResponse(output: OutputStream, result: Result, socket: Socket) {
        when (result) {
            is Result.Full -> {
                val headers = StringBuilder()
                headers.append("HTTP/1.1 ${result.status} ${reason(result.status)}\r\n")
                headers.append("Content-Type: ${result.contentType}\r\n")
                headers.append("Content-Length: ${result.body.size}\r\n")
                headers.append("Connection: close\r\n")
                headers.append("Cache-Control: no-store\r\n")
                for ((key, value) in CORS) headers.append("$key: $value\r\n")
                for ((key, value) in result.extraHeaders) headers.append("$key: $value\r\n")
                headers.append("\r\n")
                output.write(headers.toString().toByteArray(StandardCharsets.ISO_8859_1))
                output.write(result.body)
            }
            is Result.Stream -> {
                val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: ${result.contentType}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: keep-alive\r\n" +
                    "X-Accel-Buffering: no\r\n" +
                    "\r\n"
                output.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
                output.flush()
                // The socket is needed to detect a closed SSE client. The caller
                // closes it after the writer returns.
                result.writer(output, socket)
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var match = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (buffer.size() < 16_384) {
            val value = input.read()
            if (value < 0) return null
            buffer.write(value)
            if (value.toByte() == terminator[match]) {
                match++
                if (match == terminator.size) {
                    return buffer.toByteArray().toString(StandardCharsets.ISO_8859_1)
                }
            } else {
                match = if (value.toByte() == terminator[0]) 1 else 0
            }
        }
        return null
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) out else out.copyOf(offset)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isBlank()) continue
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            result[decode(key)] = decode(value)
        }
        return result
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    /** True when the failure is just the peer going away mid-request. */
    private fun isClientDisconnect(error: Exception): Boolean {
        val message = error.message?.lowercase() ?: return false
        return "broken pipe" in message || "connection reset" in message || "epipe" in message
    }

    private fun decodeJsonData(body: ByteArray): ByteArray? {
        val text = body.toString(StandardCharsets.UTF_8).trim()
        if (text.isEmpty()) return null
        val data = runCatching { JSONObject(text).optString("data") }.getOrNull().orEmpty()
        if (data.isEmpty()) return null
        val clean = data.substringAfter("base64,", data)
        return runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull()
    }

    private fun json(body: String): Result = Result.Full(200, "application/json; charset=utf-8", body.toByteArray())

    private fun errorJson(message: String): ByteArray =
        JSONObject().put("ok", false).put("error", message).toString().toByteArray()

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    companion object {
        private const val TAG = "QuasarHttp"
        private const val MAX_BODY = 64 * 1024 * 1024
        private val counter = AtomicInteger()

        private val CORS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type",
        )
    }
}