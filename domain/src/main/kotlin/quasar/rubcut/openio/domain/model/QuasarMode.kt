package quasar.rubcut.openio.domain.model

enum class QuasarMode(val id: String, val title: String) {
    CLOCK("clock", "Цифровые часы"),
    OSCILLOSCOPE("oscilloscope", "Осциллограф"),
    /** Service-level mode: clock while silent, visualiser while media plays. */
    AUTO("auto", "Авто"),
    ANIMATION("animation", "Анимации"),
    TEST("test", "Тестовый шаблон"),
    CUSTOM("custom", "Внешний кадр"),
    OFF("off", "Выключено");

    companion object {
        fun fromId(value: String?): QuasarMode? =
            entries.firstOrNull { it.id.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
    }
}