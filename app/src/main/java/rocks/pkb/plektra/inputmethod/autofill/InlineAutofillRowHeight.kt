package rocks.pkb.plektra.inputmethod.autofill

/**
 * The autofill chips row takes the height of the bar row that hosts it, scaled by the theme's
 * autofill row height. 1x is the host row itself, so themes without the setting look unchanged.
 */
internal object InlineAutofillRowHeight {
    const val DEFAULT_SCALE = 1f
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 2.5f
    val SCALE_RANGE = MIN_SCALE..MAX_SCALE

    fun rowHeightDp(hostRowHeightDp: Float, scale: Float): Float = hostRowHeightDp * scale.coerceIn(MIN_SCALE, MAX_SCALE)
}
