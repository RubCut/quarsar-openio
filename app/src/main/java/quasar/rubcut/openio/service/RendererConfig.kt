package quasar.rubcut.openio.service

import android.content.Context
import android.graphics.Color
import android.util.Log
import org.json.JSONObject
import quasar.rubcut.openio.domain.QuasarEngine
import quasar.rubcut.openio.domain.audio.ScopeBand
import quasar.rubcut.openio.domain.render.ClockRenderer
import quasar.rubcut.openio.domain.render.ClockSize
import quasar.rubcut.openio.domain.render.OscilloscopeRenderer
import quasar.rubcut.openio.domain.render.TestPatternRenderer
import java.util.Locale

/**
 * Renderers read their palettes and algorithm parameters from bundled assets.
 * User-tunable values (ring colour, visualiser FPS and sensitivity) are stored
 * in SharedPreferences so they survive a service or device restart.
 */
object RendererConfig {
    private const val TAG = "QuasarRendererConfig"
    private const val CLOCK_ASSET = "render/clock.json"
    private const val SCOPE_ASSET = "render/oscilloscope.json"
    private const val PREFS = "quasar_settings"
    private const val KEY_RING_COLOR = "ring_color"
    private const val KEY_RING_ENABLED = "ring_enabled"
    private const val KEY_RING_SPECTRUM = "ring_spectrum"
    private const val KEY_SCOPE_FPS = "scope_fps"
    private const val KEY_SCOPE_SENSITIVITY = "scope_sensitivity"
    private const val KEY_CAPTURE_RATE = "capture_rate"
    private const val KEY_CLOCK_SIZE = "clock_size"
    private const val KEY_IDLE_ANIMATION = "idle_animation"
    private const val KEY_ANIMATION_COLOR = "animation_color"
    private const val KEY_RING_ORIGIN = "ring_origin"
    private const val KEY_SCOPE_BAND = "scope_band"
    private const val KEY_SCOPE_SMOOTH = "scope_smooth"
    private const val KEY_RING_ORIGIN_VERSION = "ring_origin_version"
    private const val RING_ORIGIN_VERSION = 2
    private const val KEY_AUTO_ENABLED = "auto_enabled"

    const val DEFAULT_RING_COLOR = 0xFF66D9FF.toInt()
    const val DEFAULT_CAPTURE_RATE = 16_000
    const val MAX_CAPTURE_RATE = 48_000
    const val DEFAULT_RING_ORIGIN = 12
    const val DEFAULT_SCOPE_SMOOTH_MS = 1_500

    data class RuntimeSettings(
        val ringColor: Int = DEFAULT_RING_COLOR,
        val ringEnabled: Boolean = true,
        val ringSpectrum: Boolean = false,
        val scopeFps: Int = QuasarEngine.MAX_SCOPE_FPS,
        val scopeSensitivity: Float = 1f,
        val scopeSmooth: Int = DEFAULT_SCOPE_SMOOTH_MS,
        val clockSize: ClockSize = ClockSize.LARGE,
        val idleAnimation: String? = null,
        val animationColor: Int = 0xFFFFFF,
        val ringOrigin: Int = DEFAULT_RING_ORIGIN,
        val scopeBand: String = "full",
    )

    data class Renderers(
        val clock: ClockRenderer,
        val oscilloscope: OscilloscopeRenderer,
        val test: TestPatternRenderer,
        val defaults: RuntimeSettings,
    )

    fun load(context: Context): Renderers {
        val clockJson = readJson(context, CLOCK_ASSET)
        val scopeJson = readJson(context, SCOPE_ASSET)

        val clockColors = clockJson?.optJSONObject("colors")
        val clock = ClockRenderer(
            digitsColor = color(clockColors, "digits", 0xFFFFFF),
            colonColor = color(clockColors, "colon", 0xFFFFFF),
            background = color(clockColors, "background", 0x000000),
        )

        val scopeColors = scopeJson?.optJSONObject("colors")
        val oscilloscope = OscilloscopeRenderer(
            background = color(scopeColors, "background", 0x000000),
            waveColor = color(scopeColors, "wave", 0xFFFFFF),
        )

        val ring = clockJson?.optJSONObject("ring")
        val controls = scopeJson?.optJSONObject("controls")
        val sizeConfig = clockJson?.optJSONObject("size")
        val defaults = normalize(
            RuntimeSettings(
                ringColor = color(ring, "active", DEFAULT_RING_COLOR),
                ringEnabled = ring?.optBoolean("enabled", true) ?: true,
                ringSpectrum = false,
                scopeFps = controls?.optInt("default_fps", QuasarEngine.MAX_SCOPE_FPS)
                    ?: QuasarEngine.MAX_SCOPE_FPS,
                scopeSensitivity = controls?.optDouble("default_sensitivity", 1.0)?.toFloat() ?: 1f,
                scopeSmooth = DEFAULT_SCOPE_SMOOTH_MS,
                clockSize = ClockSize.fromId(sizeConfig?.optString("default")),
                idleAnimation = sizeConfig?.optString("idle_animation")?.takeIf { it.isNotBlank() },
                animationColor = color(sizeConfig, "animation", 0xFFFFFF),
                ringOrigin = DEFAULT_RING_ORIGIN,
                scopeBand = "full",
            ),
        )
        return Renderers(clock, oscilloscope, TestPatternRenderer(), defaults)
    }

    fun loadRuntime(context: Context, defaults: RuntimeSettings): RuntimeSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The ring used to be oriented from physical LED 0; the correct front
        // marker on this panel is LED 12. Migrate once so existing installs
        // stop showing the clock mirrored.
        val origin = if (prefs.getInt(KEY_RING_ORIGIN_VERSION, 1) < RING_ORIGIN_VERSION) {
            DEFAULT_RING_ORIGIN
        } else {
            prefs.getInt(KEY_RING_ORIGIN, defaults.ringOrigin)
        }
        return normalize(
            RuntimeSettings(
                ringColor = prefs.getInt(KEY_RING_COLOR, defaults.ringColor),
                ringEnabled = prefs.getBoolean(KEY_RING_ENABLED, defaults.ringEnabled),
                // Spectrum presets are gone; never re-enable them from prefs.
                ringSpectrum = false,
                scopeFps = prefs.getInt(KEY_SCOPE_FPS, defaults.scopeFps),
                scopeSensitivity = prefs.getFloat(KEY_SCOPE_SENSITIVITY, defaults.scopeSensitivity),
                scopeSmooth = prefs.getInt(KEY_SCOPE_SMOOTH, defaults.scopeSmooth),
                clockSize = ClockSize.fromId(prefs.getString(KEY_CLOCK_SIZE, defaults.clockSize.name)),
                idleAnimation = prefs.getString(KEY_IDLE_ANIMATION, defaults.idleAnimation),
                animationColor = prefs.getInt(KEY_ANIMATION_COLOR, defaults.animationColor),
                ringOrigin = origin,
                scopeBand = prefs.getString(KEY_SCOPE_BAND, defaults.scopeBand) ?: defaults.scopeBand,
            ),
        )
    }

    fun saveRuntime(context: Context, settings: RuntimeSettings) {
        val normalized = normalize(settings)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_RING_COLOR, normalized.ringColor)
            .putBoolean(KEY_RING_ENABLED, normalized.ringEnabled)
            .putBoolean(KEY_RING_SPECTRUM, false)
            .putInt(KEY_SCOPE_FPS, normalized.scopeFps)
            .putFloat(KEY_SCOPE_SENSITIVITY, normalized.scopeSensitivity)
            .putInt(KEY_SCOPE_SMOOTH, normalized.scopeSmooth)
            .putString(KEY_CLOCK_SIZE, normalized.clockSize.name)
            .putString(KEY_IDLE_ANIMATION, normalized.idleAnimation)
            .putInt(KEY_ANIMATION_COLOR, normalized.animationColor)
            .putInt(KEY_RING_ORIGIN, normalized.ringOrigin)
            .putInt(KEY_RING_ORIGIN_VERSION, RING_ORIGIN_VERSION)
            .putString(KEY_SCOPE_BAND, normalized.scopeBand)
            .apply()
    }

    fun normalize(settings: RuntimeSettings): RuntimeSettings = RuntimeSettings(
        ringColor = settings.ringColor and 0xFFFFFF,
        ringEnabled = settings.ringEnabled,
        ringSpectrum = false,
        scopeFps = settings.scopeFps.coerceIn(1, QuasarEngine.MAX_SCOPE_FPS),
        scopeSensitivity = settings.scopeSensitivity.coerceIn(
            OscilloscopeRenderer.MIN_SENSITIVITY,
            OscilloscopeRenderer.MAX_SENSITIVITY,
        ),
        scopeSmooth = settings.scopeSmooth.coerceIn(0, OscilloscopeRenderer.MAX_SMOOTH_MS),
        clockSize = settings.clockSize,
        idleAnimation = settings.idleAnimation?.takeIf { it.isNotBlank() },
        animationColor = settings.animationColor and 0xFFFFFF,
        ringOrigin = ((settings.ringOrigin % 24) + 24) % 24,
        scopeBand = ScopeBand.fromId(settings.scopeBand).id,
    )

    /** Accepts #RRGGBB, 0xRRGGBB, bare RRGGBB and the Android #RGB shorthand. */
    fun parseColor(text: String?): Int? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        return try {
            when {
                value.startsWith("0x", ignoreCase = true) -> value.substring(2).toLong(16).toInt() and 0xFFFFFF
                value.startsWith("#") && value.length == 7 -> value.substring(1).toLong(16).toInt() and 0xFFFFFF
                value.startsWith("#") -> Color.parseColor(value) and 0xFFFFFF
                else -> value.toLong(16).toInt() and 0xFFFFFF
            }
        } catch (_: Exception) {
            null
        }
    }

    fun hex(color: Int): String = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

    /**
     * Capture rate is kept out of [RuntimeSettings] because changing it needs
     * the recorder to be reopened, not just a repaint.
     */
    fun captureRate(context: Context): Int {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CAPTURE_RATE, DEFAULT_CAPTURE_RATE)
        return if (stored >= MAX_CAPTURE_RATE) MAX_CAPTURE_RATE else DEFAULT_CAPTURE_RATE
    }

    fun saveCaptureRate(context: Context, rate: Int) {
        val normalized = if (rate >= MAX_CAPTURE_RATE) MAX_CAPTURE_RATE else DEFAULT_CAPTURE_RATE
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CAPTURE_RATE, normalized)
            .apply()
    }

    private fun readJson(context: Context, name: String): JSONObject? = try {
        context.assets.open(name).use { stream ->
            JSONObject(stream.readBytes().toString(Charsets.UTF_8))
        }
    } catch (e: Exception) {
        Log.w(TAG, "asset $name unavailable, using built-in defaults: ${e.message}")
        null
    }

    private fun color(json: JSONObject?, key: String, fallback: Int): Int = parseColor(json?.optString(key)) ?: fallback
}