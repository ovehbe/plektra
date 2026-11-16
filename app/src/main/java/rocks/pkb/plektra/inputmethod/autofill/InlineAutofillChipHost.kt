package rocks.pkb.plektra.inputmethod.autofill

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * Pinned chips stay at the start. The rest scroll. The views themselves stay opaque to Plektra.
 */
internal class InlineAutofillChipHost(context: Context) : LinearLayout(context) {
    val pinnedRow: LinearLayout
    val scroller: HorizontalScrollView
    private val scrollingRow: LinearLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        pinnedRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        scrollingRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = true
            addView(scrollingRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(pinnedRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    fun setChips(views: List<View>, pinnedFlags: List<Boolean>) {
        pinnedRow.removeAllViews()
        scrollingRow.removeAllViews()
        views.forEachIndexed { index, view ->
            (view.parent as? ViewGroup)?.removeView(view)
            val target = if (pinnedFlags.getOrNull(index) == true) pinnedRow else scrollingRow
            target.addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun pinnedChips(): List<View> = childrenOf(pinnedRow)

    fun scrollingChips(): List<View> = childrenOf(scrollingRow)

    fun applyTheme(suggestionColor: Int) {
        setBackgroundColor(suggestionColor)
    }

    private fun childrenOf(group: ViewGroup): List<View> =
        List(group.childCount) { group.getChildAt(it) }
}
