package quasar.rubcut.openio.domain.render

/**
 * Bridge to the real io.sdk draw code.
 *
 * The stock visualisers are C++ classes inside `libquasar_daemons.so`, so a
 * pure Kotlin port can only ever approximate them. When the Android layer can
 * reach that library it renders the preset itself and returns the 25x16
 * intensity buffer; the engine then just paints it. Returning `null` means the
 * bridge is unavailable and the Kotlin fallback draws the frame instead.
 */
interface StockFrameSource {
    /**
     * @param style preset selected by the user
     * @param levels one value per band, already smoothed by the engine
     * @return `width * height` intensities in row-major order, or `null`
     */
    fun render(style: SpectrogramStyle, levels: FloatArray): FloatArray?
}