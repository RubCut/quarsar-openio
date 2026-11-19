package quasar.rubcut.openio.data.input

import quasar.rubcut.openio.domain.model.InputKey

/**
 * Maps the three kernel input devices of Station Max to semantic actions.
 *
 * event0 gpio_keypad  : KEY_VOICECOMMAND (582), SW_MUTE_DEVICE (14)
 * event2 rotary-vol   : KEY_PAGEUP (104), KEY_PAGEDOWN (109)
 * event4 Yandex Remote: navigation, playback, volume and power keys
 */
object InputMapper {
    const val EV_KEY = 1
    const val EV_REL = 2
    const val EV_SW = 5

    private const val KEY_MUTE = 113
    private const val KEY_VOLUMEDOWN = 114
    private const val KEY_VOLUMEUP = 115
    private const val KEY_POWER = 116
    private const val KEY_PLAYPAUSE = 164
    private const val KEY_PREVIOUSSONG = 165
    private const val KEY_NEXTSONG = 163
    private const val KEY_HOME = 102
    private const val KEY_BACK = 158
    private const val KEY_ENTER = 28
    private const val KEY_UP = 103
    private const val KEY_DOWN = 108
    private const val KEY_LEFT = 105
    private const val KEY_RIGHT = 106
    private const val KEY_PAGEUP = 104
    private const val KEY_PAGEDOWN = 109
    private const val KEY_VOICECOMMAND = 582
    private const val SW_MUTE_DEVICE = 14

    fun map(device: String, type: Int, code: Int, value: Int): InputKey? {
        return when (device) {
            "event0" -> when {
                type == EV_KEY && code == KEY_VOICECOMMAND && value == 1 -> InputKey.ALICE
                type == EV_SW && code == SW_MUTE_DEVICE -> InputKey.MUTE
                else -> null
            }
            "event2" -> when {
                type == EV_KEY && code == KEY_PAGEUP && value == 1 -> InputKey.VOLUME_UP
                type == EV_KEY && code == KEY_PAGEDOWN && value == 1 -> InputKey.VOLUME_DOWN
                type == EV_REL && code == 7 && value > 0 -> InputKey.VOLUME_UP
                type == EV_REL && code == 7 && value < 0 -> InputKey.VOLUME_DOWN
                else -> null
            }
            "event4" -> when {
                // Only key presses and switch transitions matter for the remote.
                type != EV_KEY && type != EV_SW -> null
                value != 1 && type != EV_SW -> null
                else -> when (code) {
                    KEY_HOME -> InputKey.REMOTE_HOME
                    KEY_BACK -> InputKey.REMOTE_BACK
                    KEY_ENTER -> InputKey.REMOTE_OK
                    KEY_UP -> InputKey.REMOTE_UP
                    KEY_DOWN -> InputKey.REMOTE_DOWN
                    KEY_LEFT -> InputKey.REMOTE_LEFT
                    KEY_RIGHT -> InputKey.REMOTE_RIGHT
                    KEY_PLAYPAUSE -> InputKey.REMOTE_PLAY_PAUSE
                    KEY_NEXTSONG -> InputKey.REMOTE_NEXT
                    KEY_PREVIOUSSONG -> InputKey.REMOTE_PREVIOUS
                    KEY_POWER -> InputKey.REMOTE_POWER
                    KEY_VOLUMEUP -> InputKey.VOLUME_UP
                    KEY_VOLUMEDOWN -> InputKey.VOLUME_DOWN
                    KEY_MUTE -> InputKey.MUTE
                    else -> null
                }
            }
            else -> null
        }
    }
}