package rocks.pkb.plektra.inputmethod

/**
 * Size of the hardware SYM grid pages (emoji, symbols and paged layers) from the theme's SYM page
 * size. 1x keeps the original key height. Larger sizes stop growing once the three key rows would
 * take more than [MAX_SCREEN_FRACTION] of the screen, so the page still fits on square screens.
 */
internal object SymPageSize {
    const val DEFAULT_SCALE = 1f
    const val MIN_SCALE = 0.75f
    const val MAX_SCALE = 1.75f
    val SCALE_RANGE = MIN_SCALE..MAX_SCALE
    const val MAX_SCREEN_FRACTION = 0.6f
    const val ROWS = 3

    fun keyHeightPx(baseKeyHeightPx: Int, scale: Float, rowGapPx: Int, screenHeightPx: Int): Int {
        val scaled = (baseKeyHeightPx * scale.coerceIn(MIN_SCALE, MAX_SCALE)).toInt()
        if (scaled <= baseKeyHeightPx) return scaled
        val fitting = ((screenHeightPx * MAX_SCREEN_FRACTION).toInt() - (ROWS - 1) * rowGapPx) / ROWS
        return scaled.coerceAtMost(fitting.coerceAtLeast(baseKeyHeightPx))
    }

    fun gridHeightPx(keyHeightPx: Int, rowGapPx: Int): Int = ROWS * keyHeightPx + (ROWS - 1) * rowGapPx
}
