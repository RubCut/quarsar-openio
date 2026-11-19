package quasar.rubcut.openio.domain.model

enum class InputKey(val eventName: String) {
    ALICE("alice_pressed"),
    VOLUME_UP("volume_up"),
    VOLUME_DOWN("volume_down"),
    MUTE("mute_toggled"),
    REMOTE_HOME("remote_home"),
    REMOTE_BACK("remote_back"),
    REMOTE_OK("remote_ok"),
    REMOTE_UP("remote_up"),
    REMOTE_DOWN("remote_down"),
    REMOTE_LEFT("remote_left"),
    REMOTE_RIGHT("remote_right"),
    REMOTE_PLAY_PAUSE("remote_play_pause"),
    REMOTE_NEXT("remote_next"),
    REMOTE_PREVIOUS("remote_previous"),
    REMOTE_POWER("remote_power"),
    HEADPHONES("headphones"),
    UNKNOWN("unknown"),
}

data class InputEvent(
    val key: InputKey,
    val device: String,
    val type: Int,
    val code: Int,
    val value: Int,
    val timestampMs: Long,
)

data class AudioChunk(
    val samples: ShortArray,
    val sampleRate: Int,
    val timestampMs: Long,
)