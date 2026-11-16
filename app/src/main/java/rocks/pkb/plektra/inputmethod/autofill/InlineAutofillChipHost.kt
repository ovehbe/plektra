package rocks.pkb.plektra.inputmethod.autofill

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import rocks.pkb.plektra.inputmethod.ui.KeyboardThemeColors

/**
 * Bar row for autofill chips: pinned chips stay at the start, the others scroll. The chips are
 * opaque views rendered by the autofill service; the host only arranges them.
 */
@SuppressLint("ViewConstructor")
internal class InlineAutofillChipHost(context: Context) : LinearLayout(context) {
    private val gapPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, CHIP_GAP_DP, context.resources.displayMetrics
    ).toInt()
    private val pinnedRow = chipRow(context)
    private val scrollingRow = chipRow(context)
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(scrollingRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    var theme: KeyboardThemeColors? = null
        set(value) {
            if (field == value) return
            field = value
            setBackgroundColor(InlineAutofillStyle.hostColor(value))
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(gapPx, 0, 0, 0)
        setBackgroundColor(InlineAutofillStyle.hostColor(null))
        // Chips are announced by their own content, never as word suggestions.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        addView(pinnedRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    fun setChips(chips: List<View>, pinned: List<Boolean>) {
        clearChips()
        scroller.scrollTo(0, 0)
        chips.forEachIndexed { index, chip ->
            (chip.parent as? ViewGroup)?.removeView(chip)
            val row = if (pinned.getOrElse(index) { false }) pinnedRow else scrollingRow
            // Inflated chips carry the size the autofill service rendered them at.
            val size = chip.layoutParams
            row.addView(chip, LayoutParams(
                size?.width ?: LayoutParams.WRAP_CONTENT,
                size?.height ?: LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = gapPx })
        }
    }

    fun clearChips() {
        pinnedRow.removeAllViews()
        scrollingRow.removeAllViews()
    }

    // The suggestions row moves the cursor on horizontal drags; scrolling chips must win.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(event)
    }

    private fun chipRow(context: Context) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private companion object {
        const val CHIP_GAP_DP = 4f
    }
}
