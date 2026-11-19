package quasar.rubcut.openio.data.plugin

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import quasar.rubcut.openio.domain.plugin.ConfigField
import quasar.rubcut.openio.domain.plugin.ConfigOption
import quasar.rubcut.openio.domain.plugin.ModItemKind
import quasar.rubcut.openio.domain.plugin.PluginPack
import quasar.rubcut.openio.domain.plugin.PluginProvider
import quasar.rubcut.openio.domain.plugin.ProviderType
import quasar.rubcut.openio.domain.plugin.ScreenSlot
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/**
 * Loads built-in packs from `assets/plugins` and user packs from
 * `filesDir/plugins`.
 *
 * A pack is a directory with `manifest.json` and optional `scripts/`, `bins/`
 * and resource folders. Installed archives use the same layout under a top
 * level `plugin/` directory:
 *
 * ```
 * plugin/manifest.json
 * plugin/led/volume.led
 * plugin/scripts/on_alice.sh
 * ```
 *
 * The store is deliberately storage-only: it parses manifests, validates
 * configuration values and reads resources. Running scripts and applying
 * providers is [PluginHost] / QuasarService territory.
 */
class PluginStore(private val context: Context) {
    data class Entry(
        val pack: PluginPack,
        val assetDir: String?,
        val dir: File?,
    )

    data class Located(
        val entry: Entry,
        val provider: PluginProvider,
    ) {
        val pack: PluginPack get() = entry.pack
        val key: String get() = "${entry.pack.id}/${provider.id}"
    }

    data class InstallResult(val ok: Boolean, val message: String, val id: String? = null)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var entries: List<Entry> = emptyList()

    fun refresh(): List<PluginPack> {
        synchronized(lock) { entries = loadAll() }
        return packs()
    }

    fun packs(): List<PluginPack> = synchronized(lock) {
        if (entries.isEmpty()) entries = loadAll()
        entries.map { it.pack }.sortedWith(compareByDescending<PluginPack> { it.builtin }.thenBy { it.name })
    }

    fun locate(packId: String, providerId: String): Located? = synchronized(lock) {
        if (entries.isEmpty()) entries = loadAll()
        val entry = entries.firstOrNull { it.pack.id == packId } ?: return null
        val provider = entry.pack.providers.firstOrNull { it.id == providerId } ?: return null
        Located(entry, provider)
    }

    fun locate(key: String): Located? {
        val slash = key.indexOf('/')
        if (slash <= 0 || slash == key.lastIndex) return null
        return locate(key.substring(0, slash), key.substring(slash + 1))
    }

    /** Effective config: manifest default overridden by the saved value. */
    fun config(located: Located): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (field in located.provider.config) {
            val stored = prefs.getString(configKey(located.key, field.key), null)
            values[field.key] = normalize(field, stored)
        }
        return values
    }

    fun saveConfig(located: Located, values: Map<String, *>): Map<String, String> {
        val effective = LinkedHashMap<String, String>()
        val editor = prefs.edit()
        for (field in located.provider.config) {
            val raw = values[field.key]?.toString()
            val value = normalize(field, raw)
            effective[field.key] = value
            editor.putString(configKey(located.key, field.key), value)
        }
        editor.apply()
        return effective
    }

    fun anyConfig(located: Located): Map<String, String> = config(located)

    // ------------------------------------------------------- enable/disable

    /** A pack is enabled unless the user switched it off explicitly. */
    fun isPackEnabled(packId: String): Boolean = prefs.getBoolean("enabled:pack:$packId", true)

    fun isProviderEnabled(providerKey: String): Boolean =
        prefs.getBoolean("enabled:provider:$providerKey", true)

    fun isEnabled(packId: String, providerId: String): Boolean =
        isPackEnabled(packId) && isProviderEnabled("$packId/$providerId")

    fun setPackEnabled(packId: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled:pack:$packId", enabled).apply()
    }

    fun setProviderEnabled(providerKey: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled:provider:$providerKey", enabled).apply()
    }

    /** Enabled providers of one type across all enabled packs. */
    fun enabledProviders(type: ProviderType): List<Located> = synchronized(lock) {
        if (entries.isEmpty()) entries = loadAll()
        entries.filter { isPackEnabled(it.pack.id) }
            .flatMap { entry ->
                entry.pack.providers(type)
                    .filter { isProviderEnabled("${entry.pack.id}/${it.id}") }
                    .map { Located(entry, it) }
            }
    }

    /**
     * A pack-relative directory with all files available on the real
     * filesystem. Installed packs already live there; built-in packs are
     * extracted once into `filesDir/plugin-cache`.
     *
     * Background services and pack scripts need this: an interpreter in
     * `bins/` and resource files cannot be loaded straight from the APK.
     */
    fun materializePack(packId: String): File? {
        val entry = synchronized(lock) {
            if (entries.isEmpty()) entries = loadAll()
            entries.firstOrNull { it.pack.id == packId }
        } ?: return null
        entry.dir?.let { return it }
        val assetDir = entry.assetDir ?: return null
        val out = File(context.filesDir, "plugin-cache/$assetDir")
        out.mkdirs()
        val assetRoot = "plugins/$assetDir"
        copyAssetTree(assetRoot, out)
        markExecutables(out)
        return out
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val children = try {
            context.assets.list(assetPath)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (children.isEmpty()) {
            // A file, not a directory. Keep the existing copy if the size
            // matches so repeated service starts are cheap.
            val bytes = try {
                context.assets.open(assetPath).use { it.readBytes() }
            } catch (_: Exception) {
                return
            }
            if (!dest.isFile || dest.length() != bytes.size.toLong()) {
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
            }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssetTree("$assetPath/$child", File(dest, child))
    }

    /** Raw bytes of a file inside the pack. `builtin:` is resolved by callers. */
    fun open(packId: String, path: String): ByteArray? {
        val clean = path.trim().removePrefix("/")
        if (clean.isEmpty() || clean.contains("..") || clean.contains('\\')) return null
        val entry = synchronized(lock) {
            if (entries.isEmpty()) entries = loadAll()
            entries.firstOrNull { it.pack.id == packId }
        } ?: return null
        return try {
            val assetDir = entry.assetDir
            if (assetDir != null) {
                context.assets.open("plugins/$assetDir/$clean").use { it.readBytes() }
            } else {
                val root = entry.dir ?: return null
                val file = File(root, clean).canonicalFile
                if (!file.path.startsWith(root.canonicalPath + File.separator)) return null
                file.takeIf { it.isFile }?.readBytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read ${entry.pack.id}/$clean: ${e.message}")
            null
        }
    }

    fun install(zipBytes: ByteArray): InstallResult {
        if (zipBytes.size > MAX_ARCHIVE) return InstallResult(false, "архив больше 64 МБ")
        val root = File(context.filesDir, "plugins").apply { mkdirs() }
        val temp = File(context.cacheDir, "plugin-${System.nanoTime()}.zip")
        var stage: File? = null
        return try {
            temp.writeBytes(zipBytes)
            ZipFile(temp).use { zip ->
                val manifestEntry = zip.getEntry("plugin/manifest.json")
                    ?: return InstallResult(false, "в архиве нет plugin/manifest.json")
                val manifestText = zip.getInputStream(manifestEntry).use {
                    it.readBytes().toString(StandardCharsets.UTF_8)
                }
                val manifest = JSONObject(manifestText)
                val id = (manifest.optString("id").ifBlank {
                    manifest.optString("name")
                }).trim().lowercase()
                if (!ID_PATTERN.matches(id)) {
                    return InstallResult(false, "id плагина: только a-z, 0-9, точка, дефис и подчёркивание")
                }
                if (builtinIds().contains(id)) {
                    return InstallResult(false, "id $id уже занят встроенным паком")
                }

                stage = File(context.cacheDir, "plugin-stage-${System.nanoTime()}").apply { mkdirs() }
                val staging = stage ?: return InstallResult(false, "cannot create staging directory")
                var count = 0
                var total = 0L
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.startsWith("plugin/")) continue
                    val rel = entry.name.removePrefix("plugin/")
                    if (rel.isBlank() || entry.isDirectory) continue
                    if (rel.startsWith("/") || rel.contains("..") || rel.contains('\\')) {
                        return InstallResult(false, "недопустимый путь в архиве: ${entry.name}")
                    }
                    count++
                    if (count > MAX_ENTRIES) return InstallResult(false, "слишком много файлов в архиве")
                    val dest = File(staging, rel).canonicalFile
                    if (!dest.path.startsWith(staging.canonicalPath + File.separator)) {
                        return InstallResult(false, "недопустимый путь в архиве: ${entry.name}")
                    }
                    dest.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    total += dest.length()
                    if (total > MAX_EXTRACTED) return InstallResult(false, "распакованный пак больше 64 МБ")
                }

                val target = File(root, id)
                if (target.exists()) target.deleteRecursively()
                if (!staging.renameTo(target)) {
                    staging.copyRecursively(target, overwrite = true)
                    staging.deleteRecursively()
                }
                stage = null
                markExecutables(target)
                refresh()
                InstallResult(true, "пак установлен: $count файлов", id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            InstallResult(false, e.message ?: "ошибка установки")
        } finally {
            runCatching { temp.delete() }
            runCatching { stage?.deleteRecursively() }
        }
    }

    fun remove(id: String): Boolean {
        val entry = synchronized(lock) {
            if (entries.isEmpty()) entries = loadAll()
            entries.firstOrNull { it.pack.id == id }
        } ?: return false
        if (entry.pack.builtin) return false
        val dir = entry.dir ?: return false
        val ok = dir.deleteRecursively()
        refresh()
        return ok
    }

    /** Extracts a built-in script/binary once so the OS can execute it. */
    fun materialize(packId: String, path: String): File? {
        val clean = path.trim().removePrefix("/")
        if (clean.isEmpty() || clean.contains("..")) return null
        val entry = synchronized(lock) {
            if (entries.isEmpty()) entries = loadAll()
            entries.firstOrNull { it.pack.id == packId }
        } ?: return null
        val dir = entry.dir
        if (dir != null) {
            val file = File(dir, clean).canonicalFile
            return if (file.path.startsWith(dir.canonicalPath + File.separator) && file.isFile) file else null
        }
        val assetDir = entry.assetDir ?: return null
        val out = File(context.filesDir, "plugin-cache/$assetDir/$clean")
        val bytes = try {
            context.assets.open("plugins/$assetDir/$clean").use { it.readBytes() }
        } catch (_: Exception) {
            return null
        }
        out.parentFile?.mkdirs()
        if (!out.exists() || out.length() != bytes.size.toLong()) out.writeBytes(bytes)
        return out
    }

    fun configKey(providerKey: String, field: String): String = "cfg:$providerKey:$field"

    // ------------------------------------------------------------- loading

    private fun loadAll(): List<Entry> {
        val result = ArrayList<Entry>()
        val builtin = builtinIds()
        for (dirName in builtinNames()) {
            val text = readAsset("plugins/$dirName/manifest.json") ?: continue
            val pack = parseManifest(text, dirName, builtin = true, dir = null, dirName = dirName)
            if (pack != null) result.add(Entry(pack, dirName, null))
        }
        val root = File(context.filesDir, "plugins")
        for (dir in root.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (!dir.isDirectory) continue
            val text = try {
                File(dir, "manifest.json").readText()
            } catch (_: Exception) {
                null
            }
            if (text == null) {
                val id = dir.name
                if (id in builtin) continue
                result.add(
                    Entry(
                        PluginPack(
                            id = id,
                            name = id,
                            version = "?",
                            author = "",
                            description = "",
                            builtin = false,
                            directory = dir.absolutePath,
                            providers = emptyList(),
                            error = "manifest.json не найден",
                        ),
                        null,
                        dir,
                    ),
                )
                continue
            }
            val pack = parseManifest(text, dir.name, builtin = false, dir = dir, dirName = dir.name)
            if (pack == null) {
                result.add(
                    Entry(
                        PluginPack(
                            id = dir.name,
                            name = dir.name,
                            version = "?",
                            author = "",
                            description = "",
                            builtin = false,
                            directory = dir.absolutePath,
                            providers = emptyList(),
                            error = "manifest.json не разобран",
                        ),
                        null,
                        dir,
                    ),
                )
                continue
            }
            if (pack.id in builtin) continue
            result.add(Entry(pack, null, dir))
        }
        return result
    }

    private fun builtinNames(): List<String> {
        val dirs = try {
            context.assets.list("plugins")?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return dirs.sorted().filter { dir ->
            try {
                context.assets.open("plugins/$dir/manifest.json").close()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun builtinIds(): Set<String> {
        val ids = HashSet<String>()
        for (dir in builtinNames()) {
            val text = readAsset("plugins/$dir/manifest.json") ?: continue
            runCatching { JSONObject(text).optString("id", dir).lowercase() }.getOrNull()?.let { ids.add(it) }
        }
        return ids
    }

    private fun readAsset(path: String): String? = try {
        context.assets.open(path).use { it.readBytes().toString(StandardCharsets.UTF_8) }
    } catch (_: Exception) {
        null
    }

    private fun parseManifest(
        text: String,
        fallbackId: String,
        builtin: Boolean,
        dir: File?,
        dirName: String,
    ): PluginPack? {
        return try {
            val json = JSONObject(text)
            val id = json.optString("id", fallbackId).trim().lowercase()
                .ifBlank { fallbackId.lowercase() }
            if (!ID_PATTERN.matches(id)) return null
            val providers = ArrayList<PluginProvider>()
            val array = json.optJSONArray("items")
                ?: json.optJSONArray("providers")
                ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val providerId = item.optString("id").trim()
                val type = resolveItemType(item) ?: continue
                if (providerId.isEmpty()) continue
                val events = ArrayList<String>()
                item.optJSONArray("events")?.let { list ->
                    for (e in 0 until list.length()) list.optString(e).takeIf { it.isNotBlank() }?.let { events.add(it) }
                }
                val config = ArrayList<ConfigField>()
                item.optJSONArray("config")?.let { list ->
                    for (c in 0 until list.length()) {
                        val field = list.optJSONObject(c) ?: continue
                        val key = field.optString("key").trim()
                        if (key.isEmpty()) continue
                        val options = ArrayList<ConfigOption>()
                        field.optJSONArray("options")?.let { opts ->
                            for (o in 0 until opts.length()) {
                                val option = opts.optJSONObject(o) ?: continue
                                val value = option.optString("value")
                                if (value.isNotBlank()) {
                                    options.add(ConfigOption(value, option.optString("label", value)))
                                }
                            }
                        }
                        config.add(
                            ConfigField(
                                key = key,
                                label = field.optString("label", key),
                                kind = field.optString("kind", "text").lowercase(),
                                default = field.optString("default", ""),
                                min = field.optDouble("min").takeIf { !it.isNaN() },
                                max = field.optDouble("max").takeIf { !it.isNaN() },
                                step = field.optDouble("step").takeIf { !it.isNaN() },
                                unit = field.optString("unit").takeIf { it.isNotBlank() },
                                options = options,
                                help = field.optString("help").takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                }
                val env = LinkedHashMap<String, String>()
                item.optJSONObject("env")?.let { objectJson ->
                    for (key in objectJson.keys()) {
                        val value = objectJson.optString(key)
                        if (key.isNotBlank() && value.isNotBlank()) env[key] = value
                    }
                }
                providers.add(
                    PluginProvider(
                        id = providerId,
                        type = type,
                        name = item.optString("name", providerId),
                        description = item.optString("description", ""),
                        group = item.optString("group", ""),
                        entry = item.optString("entry").takeIf { it.isNotBlank() },
                        resource = item.optString("resource").takeIf { it.isNotBlank() },
                        builtin = item.optString("builtin").takeIf { it.isNotBlank() },
                        events = events,
                        config = config,
                        interpreter = item.optString("interpreter").takeIf { it.isNotBlank() },
                        restart = item.optString("restart", "on-failure")
                            .lowercase()
                            .takeIf { it in RESTART_POLICIES } ?: "on-failure",
                        autostart = item.optBoolean("autostart", true),
                        env = env,
                    ),
                )
            }
            PluginPack(
                id = id,
                name = json.optString("name", id),
                version = json.optString("version", "1.0"),
                author = json.optString("author", ""),
                description = json.optString("description", ""),
                builtin = builtin,
                directory = dir?.absolutePath ?: dirName,
                providers = providers,
            )
        } catch (e: Exception) {
            Log.w(TAG, "bad manifest in ${dir?.name ?: dirName}: ${e.message}")
            null
        }
    }

    /**
     * Manifest v2 declares `items` with a user-facing `kind` (screen / ring /
     * script / service); v1 declared `providers` with a runtime `type`. Both
     * map onto [ProviderType]. A screen item resolves its slot from the
     * explicit `slot` field, falling back to `builtin`/`resource` hints, so a
     * one-item mod does not have to spell the slot out.
     */
    private fun resolveItemType(item: JSONObject): ProviderType? {
        val kind = ModItemKind.fromId(item.optString("kind"))
            ?: return ProviderType.fromId(item.optString("type"))
        return when (kind) {
            ModItemKind.RING -> ProviderType.RING
            ModItemKind.SCRIPT -> ProviderType.SCRIPT
            ModItemKind.SERVICE -> ProviderType.SERVICE
            ModItemKind.SCREEN ->
                when (screenSlotOf(item)) {
                    ScreenSlot.CLOCK -> ProviderType.CLOCK
                    ScreenSlot.VISUALIZER -> ProviderType.VISUALIZER
                    ScreenSlot.ANIMATION -> ProviderType.ANIMATION
                    null -> null
                }
        }
    }

    private fun screenSlotOf(item: JSONObject): ScreenSlot? {
        ScreenSlot.fromId(item.optString("slot"))?.let { return it }
        val builtin = item.optString("builtin").takeIf { it.isNotBlank() }
        val resource = item.optString("resource").takeIf { it.isNotBlank() }
        return when {
            builtin == null && resource == null -> null
            builtin?.startsWith("clock") == true -> ScreenSlot.CLOCK
            builtin?.startsWith("visualizer") == true ||
                builtin?.startsWith("oscilloscope") == true ||
                builtin?.startsWith("spectrogram") == true -> ScreenSlot.VISUALIZER
            builtin?.startsWith("animation") == true ||
                resource?.endsWith(".gif", ignoreCase = true) == true ||
                resource?.startsWith("anim/") == true -> ScreenSlot.ANIMATION
            else -> null
        }
    }

    private fun normalize(field: ConfigField, raw: String?): String {
        val candidate = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: field.default
        return when (field.kind) {
            "range", "number" -> {
                val parsed = candidate.toDoubleOrNull()
                    ?: field.default.toDoubleOrNull()
                    ?: 0.0
                val min = field.min
                val max = field.max
                val clamped = when {
                    min != null && parsed < min -> min
                    max != null && parsed > max -> max
                    else -> parsed
                }
                formatNumber(clamped)
            }
            "boolean" -> when (candidate.lowercase()) {
                "1", "true", "on", "yes", "да" -> "true"
                else -> "false"
            }
            "select" -> {
                if (field.options.isEmpty() || field.options.any { it.value == candidate }) {
                    candidate
                } else {
                    field.default.ifBlank { field.options.first().value }
                }
            }
            else -> candidate
        }
    }

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun markExecutables(root: File) {
        for (sub in listOf("scripts", "bins")) {
            val dir = File(root, sub)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                runCatching { file.setExecutable(true, false) }
                runCatching { file.setReadable(true, false) }
            }
        }
    }

    companion object {
        private const val TAG = "QuasarPlugins"
        private const val PREFS = "quasar_plugins"
        private const val MAX_ARCHIVE = 64L * 1024 * 1024
        private const val MAX_EXTRACTED = 64L * 1024 * 1024
        private const val MAX_ENTRIES = 5_000
        private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val RESTART_POLICIES = setOf("always", "on-failure", "never")
    }
}