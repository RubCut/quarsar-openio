package quasar.rubcut.openio.domain.render

import quasar.rubcut.openio.domain.model.RingFrame
import quasar.rubcut.openio.domain.model.ScreenFrame

class TestPatternRenderer {
    private val bars = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFFFFE066.toInt(),
        0xFF66FF88.toInt(),
        0xFF66D9FF.toInt(),
        0xFF7A8CFF.toInt(),
        0xFFFF66CC.toInt(),
        0xFFFF6655.toInt(),
    )

    fun render(): ScreenFrame {
        val frame = ScreenFrame()
        for (x in 0 until frame.width) {
            val bar = (x * bars.size / frame.width).coerceIn(0, bars.lastIndex)
            for (y in 0 until frame.height - 1) {
                frame.set(x, y, bars[bar])
            }
            // Bottom row: black-to-white ramp for checking channel order.
            val value = (x * 255 / (frame.width - 1)).coerceIn(0, 255)
            frame.set(x, frame.height - 1, (value shl 16) or (value shl 8) or value)
        }
        // White corner markers make it obvious if the panel is rotated or mirrored.
        for (i in 0 until 3) {
            frame.set(i, 0, 0xFFFFFFFF.toInt())
            frame.set(frame.width - 1 - i, 0, 0xFFFFFFFF.toInt())
            frame.set(i, frame.height - 1, 0xFFFFFFFF.toInt())
            frame.set(frame.width - 1 - i, frame.height - 1, 0xFFFFFFFF.toInt())
        }
        return frame
    }

    fun renderRing(): RingFrame {
        val ring = RingFrame()
        for (i in 0 until ring.size) {
            val hue = i * 360f / ring.size
            ring.set(i, ColorUtil.hsv(hue, 1f, 1f))
        }
        return ring
    }

    fun chaseRing(step: Int): RingFrame {
        val ring = RingFrame()
        val head = ((step % ring.size) + ring.size) % ring.size
        for (i in 0 until ring.size) {
            val distance = (head - i + ring.size) % ring.size
            when (distance) {
                0 -> ring.set(i, 0xFFFFFFFF.toInt())
                1, 2 -> ring.set(i, ColorUtil.scale(0xFF66D9FF.toInt(), 1f - distance * 0.3f))
                3 -> ring.set(i, ColorUtil.scale(0xFF66D9FF.toInt(), 0.15f))
            }
        }
        return ring
    }
}