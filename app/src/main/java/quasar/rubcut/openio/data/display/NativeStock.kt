package quasar.rubcut.openio.data.display

/**
 * JNI entry point to the stock io.sdk renderers.
 *
 * The native side dlopens `libquasar_daemons.so` straight from the ServicesIosdk
 * APK and calls its own `PadsSpectrogram` / `WaveSpectrogram` /
 * `BricksSpectrogram` draw functions, so the panel shows exactly what the
 * Station draws. Every call is defensive: if the library is missing or the
 * device rejects the mapping we simply fall back to the Kotlin renderer.
 */
object NativeStock {
    const val PIXELS = 25 * 16

    private var loadAttempted = false
    private var libraryLoaded = false
    private var ready = false

    @Synchronized
    fun ensure(apkPath: String): Boolean {
        if (!loadAttempted) {
            loadAttempted = true
            libraryLoaded = runCatching { System.loadLibrary("quasar_native") }.isSuccess
        }
        if (!libraryLoaded) return false
        if (ready) return true
        ready = runCatching { nativeStockInit(apkPath) }.getOrDefault(false)
        return ready
    }

    fun render(style: Int, ip: IntArray, fp: FloatArray, levels: FloatArray, out: FloatArray): Boolean {
        if (!ready) return false
        return runCatching { nativeStockRender(style, ip, fp, levels, out) }.getOrDefault(false)
    }

    private external fun nativeStockInit(apkPath: String): Boolean

    private external fun nativeStockRender(
        style: Int,
        ip: IntArray,
        fp: FloatArray,
        levels: FloatArray,
        out: FloatArray,
    ): Boolean
}