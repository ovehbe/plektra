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
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
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
    private var leadingChip: View? = null
    private val scrollingRow = chipRow(context)
    // Known limitation: on Android 11 the chips' surfaces ignore this clip, so scrolled chips draw over the pinned ones.
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
            (leadingChip as? TextView)?.let { InlineAutofillStyle.styleLocalChip(it, value) }
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

    /** A chip of Plektra's own (one-time code), pinned before the service's chips. */
    fun setLeadingChip(chip: View?) {
        if (chip === leadingChip) return
        leadingChip?.let(pinnedRow::removeView)
        leadingChip = chip
        if (chip != null) {
            (chip.parent as? ViewGroup)?.removeView(chip)
            // The full row height: the chip is drawn inset, but all of the row takes the tap.
            pinnedRow.addView(chip, 0, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                .apply { marginEnd = gapPx })
        }
    }

    fun clearChips() {
        while (pinnedRow.childCount > (if (leadingChip == null) 0 else 1)) {
            pinnedRow.removeViewAt(pinnedRow.childCount - 1)
        }
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

    companion object {
        private const val CHIP_GAP_DP = 4f

        /** A chip styled like the ones the autofill service renders from [InlineAutofillStyle.build]. */
        fun localChip(context: Context, title: String, description: String, @DrawableRes icon: Int,
            theme: KeyboardThemeColors?): TextView = TextView(context).apply {
            text = title
            contentDescription = description
            textDirection = View.TEXT_DIRECTION_LTR
            setCompoundDrawablesRelative(AppCompatResources.getDrawable(context, icon), null, null, null)
            InlineAutofillStyle.styleLocalChip(this, theme)
        }
    }
}
