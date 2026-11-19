package quasar.rubcut.openio.domain.render

import quasar.rubcut.openio.domain.model.ScreenFrame

/**
 * Paints a decoded GIF onto the LED panel, preserving the original shades.
 *
 * Stock animations are 25x16, i.e. exactly the panel size. Smaller animations
 * are centred and larger ones are cropped, so a user supplied pack can never
 * draw outside the visible area.
 *
 * [color] is a tint applied on top of the animation. White leaves the artwork
 * untouched (stock grey levels stay grey); a coloured tint multiplies the GIF
 * pixels, e.g. blue turns a greyscale clip into a blue one.
 */
class AnimationRenderer(
    private val background: Int = 0x000000,
) {
    fun render(
        animation: GifAnimation,
        elapsedMs: Long,
        color: Int = 0xFFFFFF,
        target: ScreenFrame = ScreenFrame(),
        loop: Boolean = animation.loop,
    ): ScreenFrame {
        target.clear(background)
        if (animation.isEmpty) return target
        val frame = GifDecoder.frameAt(animation, elapsedMs, loop)
        val offsetX = (target.width - animation.width) / 2
        val offsetY = (target.height - animation.height) / 2
        val tint = color and 0xFFFFFF
        val white = tint == 0xFFFFFF
        for (y in 0 until animation.height) {
            val dy = offsetY + y
            if (dy < 0 || dy >= target.height) continue
            for (x in 0 until animation.width) {
                val pixel = frame.pixels[y * animation.width + x]
                if (pixel == 0) continue
                val dx = offsetX + x
                if (dx < 0 || dx >= target.width) continue
                target.set(dx, dy, if (white) pixel else multiply(pixel, tint))
            }
        }
        return target
    }

    private fun multiply(base: Int, tint: Int): Int {
        val r = (((base shr 16) and 0xFF) * ((tint shr 16) and 0xFF)) / 255
        val g = (((base shr 8) and 0xFF) * ((tint shr 8) and 0xFF)) / 255
        val b = ((base and 0xFF) * (tint and 0xFF)) / 255
        return (r shl 16) or (g shl 8) or b
    }
}