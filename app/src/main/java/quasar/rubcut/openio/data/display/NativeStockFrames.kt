package quasar.rubcut.openio.data.display

import quasar.rubcut.openio.domain.render.SpectrogramStyle
import quasar.rubcut.openio.domain.render.StockFrameSource

/**
 * [StockFrameSource] backed by the real io.sdk draw code inside
 * `libquasar_daemons.so` (see [NativeStock] for the JNI side).
 *
 * [SpectrogramStyle] carries the same preset fields the stock JSON uses, so
 * the mapping below is a direct translation: the native side rebuilds the
 * matching C++ object and calls its `render` entry point, and the engine
 * paints whatever the station itself would have drawn.
 *
 * Every call is defensive — if the stock APK is missing or the mapping fails,
 * `null` falls back to the Kotlin approximation in the domain renderer.
 */
class NativeStockFrames(
    private val apkPath: String = DEFAULT_APK,
) : StockFrameSource {

    override fun render(style: SpectrogramStyle, levels: FloatArray): FloatArray? {
        if (!NativeStock.ensure(apkPath)) return null
        val ip = IntArray(8)
        val fp = FloatArray(8)
        val nativeStyle = when (style.kind) {
            SpectrogramStyle.Kind.PADS -> {
                // PadsSpectrogram: cols x rows grid of square pads; the stock
                // preset uses xOffset = 2, yOffset = 2 and pitch = padSize + 1.
                ip[0] = style.cols
                ip[1] = style.rows
                ip[2] = PAD_OFFSET
                ip[3] = PAD_OFFSET
                ip[4] = style.padSize
                ip[5] = style.padSpacing
                fp[0] = style.brightnessFading
                fp[1] = style.brightnessGamma
                0
            }
            SpectrogramStyle.Kind.WAVE -> {
                // WaveSpectrogram: mirrored curve, one column per panel pixel.
                ip[0] = style.bandCount
                ip[1] = SCREEN_WIDTH
                ip[2] = SCREEN_HEIGHT / 2
                fp[0] = 0f // minimum amplitude: rely on the zero-level check
                fp[1] = style.brightnessFading
                1
            }
            SpectrogramStyle.Kind.BRICKS -> {
                // BricksSpectrogram: 2x1 bricks; falloffSpeed is 0 in the
                // stock preset, so there is no falling peak brick.
                ip[0] = style.brickWidth
                ip[1] = style.brickHeight
                ip[2] = style.brickHeight
                ip[3] = 0
                fp[0] = 1f // brick colour
                fp[1] = style.brightnessFading
                fp[2] = 1f // falloff colour
                fp[3] = 0f // falloff speed
                2
            }
            else -> return null
        }
        val out = FloatArray(NativeStock.PIXELS)
        if (!NativeStock.render(nativeStyle, ip, fp, levels, out)) return null
        return out
    }

    companion object {
        /** io.sdk ships `libquasar_daemons.so` uncompressed in this APK. */
        const val DEFAULT_APK = "/system/priv-app/ServicesIosdk/ServicesIosdk.apk"

        private const val SCREEN_WIDTH = 25
        private const val SCREEN_HEIGHT = 16
        private const val PAD_OFFSET = 2
    }
}
