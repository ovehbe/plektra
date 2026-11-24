package rocks.pkb.plektra.inputmethod.autofill

import android.view.View

/** One suggestion: [inflate] reports its chip, or null when the autofill service rendered none. */
internal class PendingInlineChip(val pinned: Boolean, val inflate: ((View?) -> Unit) -> Unit)

/**
 * Turns autofill responses into chip batches for the bar. Inflation is asynchronous, so every
 * response and every [clear] starts a new generation and batches of an older one are dropped.
 */
internal class InlineAutofillChips(
    private val canHost: () -> Boolean,
    private val show: (views: List<View>, pinnedFlags: List<Boolean>) -> Boolean,
    private val clearSurfaces: () -> Unit
) {
    private var generation = 0

    /**
     * True when a visible host takes this batch now. Android needs the answer before inflation
     * ends, and it does not resend the response when the bar later can show chips, so a batch
     * without a visible host is still handed to the bar, which shows it once a row allows.
     */
    fun onResponse(chips: List<PendingInlineChip>): Boolean {
        val batch = ++generation
        if (chips.isEmpty()) {
            clearSurfaces()
            return false
        }
        val views = arrayOfNulls<View>(chips.size)
        var pending = chips.size
        chips.forEachIndexed { index, chip ->
            chip.inflate { view ->
                if (batch != generation) return@inflate
                views[index] = view
                if (--pending == 0) attach(chips, views)
            }
        }
        return canHost()
    }

    fun clear() {
        generation++
        clearSurfaces()
    }

    private fun attach(chips: List<PendingInlineChip>, views: Array<View?>) {
        val inflated = chips.indices.filter { views[it] != null }
        if (inflated.isEmpty()) {
            clearSurfaces()
            return
        }
        show(inflated.map { views[it]!! }, inflated.map { chips[it].pinned })
    }
}
