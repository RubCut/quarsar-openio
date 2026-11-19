package quasar.rubcut.openio.domain.render

/**
 * Original Quasar clock glyph atlases.
 *
 * Extracted from the stock io.sdk assets:
 * `frontal_animation/fonts/clock_{large,middle,small}.gif`.
 * Each source GIF has 11 frames: frame 0 is the colon, frames 1..10 are the
 * digits 0..9. Rows are bit masks with the leftmost pixel in the most
 * significant bit of the glyph width.
 */
enum class ClockSize(val digitWidth: Int, val cellHeight: Int) {
    LARGE(5, 9),
    MIDDLE(4, 7),
    SMALL(3, 5);

    companion object {
        fun fromId(value: String?): ClockSize =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LARGE
    }
}

internal object ClockFont {
    /** Colon glyph, one pixel wide for LARGE/MIDDLE and three for SMALL. */
    val colon: Map<ClockSize, IntArray> = mapOf(
        ClockSize.LARGE to intArrayOf(0, 0, 16, 0, 0, 0, 16, 0, 0),
        ClockSize.MIDDLE to intArrayOf(0, 0, 4, 0, 4, 0, 0),
        ClockSize.SMALL to intArrayOf(5, 7, 5, 7, 5),
    )

    /** Digit glyphs, index 0..9. */
    val digits: Map<ClockSize, List<IntArray>> = mapOf(
        ClockSize.LARGE to listOf(
            intArrayOf(14, 17, 17, 17, 17, 17, 17, 17, 14),  // {i-1}
            intArrayOf(4, 12, 4, 4, 4, 4, 4, 4, 14),  // {i-1}
            intArrayOf(14, 17, 1, 1, 6, 8, 16, 16, 31),  // {i-1}
            intArrayOf(14, 17, 1, 1, 14, 1, 1, 17, 14),  // {i-1}
            intArrayOf(17, 17, 17, 17, 15, 1, 1, 1, 1),  // {i-1}
            intArrayOf(31, 16, 16, 30, 1, 1, 1, 17, 14),  // {i-1}
            intArrayOf(14, 17, 16, 16, 30, 17, 17, 17, 14),  // {i-1}
            intArrayOf(31, 1, 1, 2, 2, 4, 4, 4, 4),  // {i-1}
            intArrayOf(14, 17, 17, 17, 14, 17, 17, 17, 14),  // {i-1}
            intArrayOf(14, 17, 17, 17, 15, 1, 1, 17, 14),  // {i-1}
        ),
        ClockSize.MIDDLE to listOf(
            intArrayOf(15, 9, 9, 9, 9, 9, 15),  // {i-1}
            intArrayOf(6, 2, 2, 2, 2, 2, 2),  // {i-1}
            intArrayOf(15, 1, 1, 15, 8, 8, 15),  // {i-1}
            intArrayOf(15, 1, 1, 7, 1, 1, 15),  // {i-1}
            intArrayOf(9, 9, 9, 15, 1, 1, 1),  // {i-1}
            intArrayOf(15, 8, 8, 15, 1, 1, 15),  // {i-1}
            intArrayOf(15, 8, 8, 15, 9, 9, 15),  // {i-1}
            intArrayOf(15, 1, 1, 1, 1, 1, 1),  // {i-1}
            intArrayOf(15, 9, 9, 15, 9, 9, 15),  // {i-1}
            intArrayOf(15, 9, 9, 15, 1, 1, 15),  // {i-1}
        ),
        ClockSize.SMALL to listOf(
            intArrayOf(7, 5, 5, 5, 7),  // {i-1}
            intArrayOf(3, 1, 1, 1, 1),  // {i-1}
            intArrayOf(7, 1, 7, 4, 7),  // {i-1}
            intArrayOf(7, 1, 3, 1, 7),  // {i-1}
            intArrayOf(5, 5, 7, 1, 1),  // {i-1}
            intArrayOf(7, 4, 7, 1, 7),  // {i-1}
            intArrayOf(7, 4, 7, 5, 7),  // {i-1}
            intArrayOf(7, 1, 1, 1, 1),  // {i-1}
            intArrayOf(7, 5, 7, 5, 7),  // {i-1}
            intArrayOf(7, 5, 7, 1, 7),  // {i-1}
        ),
    )
}
