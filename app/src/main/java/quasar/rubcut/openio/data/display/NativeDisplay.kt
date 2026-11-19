package quasar.rubcut.openio.data.display

import android.util.Log
import quasar.rubcut.openio.domain.model.RingFrame
import quasar.rubcut.openio.domain.model.ScreenFrame
import quasar.rubcut.openio.domain.port.RingOutput
import quasar.rubcut.openio.domain.port.ScreenOutput

/**
 * mmap-based output to /dev/graphics/fb4 (25x16, RGB888) and fb5 (24x1).
 * All drawing happens on a dedicated native thread; Kotlin only publishes the
 * newest frame, so a slow panel can never block the render loop.
 */
class NativeDisplay : ScreenOutput, RingOutput {
    private val loaded: Boolean = try {
        System.loadLibrary("quasar_native")
        true
    } catch (e: Throwable) {
        Log.e(TAG, "native library unavailable", e)
        false
    }

    fun start(): Boolean = loaded && runCatching { nativeStartDisplay() }.getOrDefault(false)

    fun stop() {
        if (!loaded) return
        runCatching { nativeStopDisplay() }
    }

    override fun drawScreen(frame: ScreenFrame): Boolean =
        loaded && runCatching { nativeSetScreen(frame.pixels) }.getOrDefault(false)

    override fun drawRing(frame: RingFrame): Boolean =
        loaded && runCatching { nativeSetRing(frame.pixels) }.getOrDefault(false)

    fun info(): String = if (!loaded) "native unavailable" else runCatching { nativeDisplayInfo() }.getOrDefault("unknown")

    private external fun nativeStartDisplay(): Boolean
    private external fun nativeStopDisplay()
    private external fun nativeSetScreen(pixels: IntArray): Boolean
    private external fun nativeSetRing(pixels: IntArray): Boolean
    private external fun nativeDisplayInfo(): String

    companion object {
        private const val TAG = "QuasarDisplay"
    }
}