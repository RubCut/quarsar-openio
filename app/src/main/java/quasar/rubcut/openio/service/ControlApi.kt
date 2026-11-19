package quasar.rubcut.openio.service

/**
 * Transport-independent control surface used by the HTTP web UI, the TCP IPC
 * server and the local (unix) socket server. Keeping it here means all three
 * transports behave identically.
 */
interface ControlApi {
    fun statusJson(): String
    fun frameRgb(): ByteArray
    fun ringRgb(): ByteArray
    fun drawScreen(rgb888: ByteArray): Boolean
    fun drawRing(rgb888: ByteArray): Boolean
    fun setMode(mode: String): Boolean
    fun freezeSdk(): Boolean
    fun unfreezeSdk(): Boolean
    fun playTone(frequencyHz: Double, durationMs: Int): Boolean
    fun setVolume(volume: Float): Boolean
    fun setRingColor(rgb: Int): Boolean
    fun setRingEnabled(enabled: Boolean): Boolean
    fun setRingSpectrum(enabled: Boolean): Boolean
    fun setScopeFps(fps: Int): Boolean
    fun setScopeSensitivity(value: Float): Boolean
    fun setScopeSmooth(ms: Int): Boolean
    fun setScopeBand(band: String): Boolean
    fun setRingOrigin(index: Int): Boolean
    fun setClockSize(size: String): Boolean
    fun setAnimationColor(rgb: Int): Boolean
    fun playAnimation(id: String, loop: Boolean): Boolean
    fun setIdleAnimation(id: String?): Boolean
    fun stopAnimation(): Boolean
    fun clearRingOverride(): Boolean
    fun animationsJson(): String
    fun setMicPassthrough(enabled: Boolean): Boolean
    fun pluginsJson(): String
    fun modsJson(): String
    fun catalogJson(): String
    fun pluginInstall(zip: ByteArray): String
    fun pluginRemove(id: String): String
    fun pluginConfig(providerKey: String, values: Map<String, String>): String
    fun pluginActivate(providerKey: String, loop: Boolean): Boolean
    fun modsActivate(itemKey: String, loop: Boolean): String
    fun selectProvider(type: String, providerKey: String?, loop: Boolean): String
    fun setFeatures(values: Map<String, Boolean>): String
    fun setPluginEnabled(packId: String?, providerKey: String?, enabled: Boolean): String
    fun pluginStart(providerKey: String): Boolean
    fun pluginStop(providerKey: String): Boolean
    fun logsJson(scope: String, pluginKey: String?, limit: Int): String
    fun clearLogs(scope: String, pluginKey: String?): Boolean
    fun shell(command: String, timeoutMs: Int, fromLoopback: Boolean, token: String?): String
    fun restartPipeline(): Boolean
    fun clearRingPattern(): Boolean
    fun setCaptureRate(rate: Int): Boolean
    fun setAutostartEnabled(enabled: Boolean): Boolean
    fun setIconVisible(visible: Boolean): Boolean

    // -- general panel settings (master switch, screen, mic share, ring volume) --

    fun generalJson(): String
    fun mediaAnimJson(): String
    fun setMaster(enabled: Boolean): Boolean
    fun setScreen(enabled: Boolean): Boolean
    fun setMicShare(enabled: Boolean): Boolean
    fun setRingVolume(enabled: Boolean?, step: Int?, anim: Boolean?): Boolean

    // -- media-event animations and key remapping --

    fun setMediaAnimation(enabled: Boolean?, play: String?, pause: String?, stop: String?): Boolean
    fun remapJson(): String
    fun setRemapBinding(key: String, action: String): Boolean
    fun resetRemapBindings(): Boolean
    fun activateAlice(): Boolean

    fun micPcm(maxSamples: Int): ShortArray
    fun micRms(): Double
    fun addEventListener(listener: (String) -> Unit): AutoCloseable
    fun recentEvents(): List<String>
    fun isRunning(): Boolean
}