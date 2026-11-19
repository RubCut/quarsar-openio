package quasar.rubcut.openio.data.input

import android.os.SystemClock
import android.util.Log
import quasar.rubcut.openio.domain.model.InputEvent
import quasar.rubcut.openio.domain.model.InputKey
import quasar.rubcut.openio.domain.port.InputSource

/**
 * Thin JNI wrapper around the poll() loop for event0/event2/event4. The native
 * thread reads struct input_event and calls [onNativeInput] with the raw tuple.
 */
class NativeInput(
    private val listener: (InputEvent) -> Unit,
) : InputSource {
    private val loaded: Boolean = try {
        System.loadLibrary("quasar_native")
        true
    } catch (e: Throwable) {
        Log.e(TAG, "native library unavailable", e)
        false
    }

    override fun start(): Boolean = loaded && runCatching { nativeStartInput() }.getOrDefault(false)

    override fun stop() {
        if (!loaded) return
        runCatching { nativeStopInput() }
    }

    /** Called from the native input thread. Keep this method name/signature in sync with JNI. */
    @Suppress("unused")
    fun onNativeInput(device: String, type: Int, code: Int, value: Int) {
        val key = InputMapper.map(device, type, code, value) ?: return
        listener(
            InputEvent(
                key = key,
                device = device,
                type = type,
                code = code,
                value = value,
                timestampMs = SystemClock.elapsedRealtime(),
            ),
        )
    }

    private external fun nativeStartInput(): Boolean
    private external fun nativeStopInput()

    companion object {
        private const val TAG = "QuasarInput"

        val knownKeys: Set<InputKey> = InputKey.entries.toSet()
    }
}