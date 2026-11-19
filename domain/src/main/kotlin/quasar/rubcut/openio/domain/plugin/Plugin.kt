package quasar.rubcut.openio.domain.plugin

/**
 * User-facing kind of a mod item. A mod (formerly "pack") contributes one or
 * more items; [SCREEN] covers every screen widget (clock, visualizer or
 * animation), which is refined by [ScreenSlot].
 */
enum class ModItemKind(val id: String) {
    SCREEN("screen"),
    RING("ring"),
    SCRIPT("script"),
    SERVICE("service");

    companion object {
        fun fromId(value: String?): ModItemKind? =
            entries.firstOrNull { it.id.equals(value, true) || it.name.equals(value, true) }
    }
}

/** Which screen slot a [ModItemKind.SCREEN] item occupies. */
enum class ScreenSlot(val id: String) {
    CLOCK("clock"),
    VISUALIZER("visualizer"),
    ANIMATION("animation");

    companion object {
        fun fromId(value: String?): ScreenSlot? =
            entries.firstOrNull { it.id.equals(value, true) || it.name.equals(value, true) }
    }
}

/** What a pack can contribute to the runtime. */
enum class ProviderType(val id: String) {
    CLOCK("clock"),
    VISUALIZER("visualizer"),
    ANIMATION("animation"),
    /** A `*.led` ring pattern (Yandex stock visualisers, clocks, events). */
    RING("ring"),
    /** Executable script/binary that reacts to service events. */
    SCRIPT("script"),
    /**
     * Long-running background process (Python, shell, native) that talks to
     * OpenIO over HTTP and is supervised by the service watchdog.
     */
    SERVICE("service");

    companion object {
        fun fromId(value: String?): ProviderType? =
            entries.firstOrNull { it.id.equals(value, true) || it.name.equals(value, true) }
    }
}

/**
 * One control in the web parameter editor. [kind] maps to an HTML input:
 * range / number / boolean / select / color / text.
 */
data class ConfigField(
    val key: String,
    val label: String,
    val kind: String = "text",
    val default: String = "",
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val unit: String? = null,
    val options: List<ConfigOption> = emptyList(),
    val help: String? = null,
)

data class ConfigOption(val value: String, val label: String)

/**
 * A selectable element of a pack. `entry` is a path inside the pack for
 * scripts/binaries, `resource` is a file reference such as `led/volume.led`
 * or `builtin:anim/media/25x16_Pause.gif`.
 */
data class PluginProvider(
    val id: String,
    val type: ProviderType,
    val name: String,
    val description: String = "",
    val group: String = "",
    val entry: String? = null,
    val resource: String? = null,
    /** Built-in behaviour id, e.g. `oscilloscope` or `clock`; null for data-only. */
    val builtin: String? = null,
    /** Events a [ProviderType.SCRIPT] subscribes to (empty = not subscribed). */
    val events: List<String> = emptyList(),
    val config: List<ConfigField> = emptyList(),
    /**
     * Interpreter used for [ProviderType.SERVICE], e.g. `python3` or a
     * pack-relative `bins/python3`. Null means the entry is executed directly.
     */
    val interpreter: String? = null,
    /** Service restart policy: `always`, `on-failure` (default) or `never`. */
    val restart: String = "on-failure",
    /** Start the background service automatically when the runtime starts. */
    val autostart: Boolean = true,
    /** Extra environment variables for a background service process. */
    val env: Map<String, String> = emptyMap(),
) {
    /** User-facing kind: every screen widget collapses into [ModItemKind.SCREEN]. */
    val kind: ModItemKind
        get() = when (type) {
            ProviderType.CLOCK, ProviderType.VISUALIZER, ProviderType.ANIMATION -> ModItemKind.SCREEN
            ProviderType.RING -> ModItemKind.RING
            ProviderType.SCRIPT -> ModItemKind.SCRIPT
            ProviderType.SERVICE -> ModItemKind.SERVICE
        }

    /** Screen slot for [ModItemKind.SCREEN] items, null otherwise. */
    val slot: ScreenSlot?
        get() = when (type) {
            ProviderType.CLOCK -> ScreenSlot.CLOCK
            ProviderType.VISUALIZER -> ScreenSlot.VISUALIZER
            ProviderType.ANIMATION -> ScreenSlot.ANIMATION
            else -> null
        }
}

data class PluginPack(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val builtin: Boolean,
    val directory: String,
    val providers: List<PluginProvider>,
    val error: String? = null,
) {
    /** Items the mod contributes; alias kept for the mods terminology. */
    val items: List<PluginProvider> get() = providers

    fun providers(type: ProviderType): List<PluginProvider> = providers.filter { it.type == type }

    fun items(kind: ModItemKind): List<PluginProvider> = providers.filter { it.kind == kind }
}

/** Fully qualified provider key used by the web API and persistence. */
data class ProviderRef(val packId: String, val providerId: String) {
    val key: String get() = "$packId/$providerId"

    companion object {
        fun parse(value: String?): ProviderRef? {
            if (value.isNullOrBlank()) return null
            val slash = value.indexOf('/')
            if (slash <= 0 || slash == value.lastIndex) return null
            return ProviderRef(value.substring(0, slash), value.substring(slash + 1))
        }
    }
}