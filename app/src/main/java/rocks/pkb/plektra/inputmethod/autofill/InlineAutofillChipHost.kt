package rocks.pkb.plektra.inputmethod.autofill

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.autofill.R as AutofillR
import rocks.pkb.plektra.R
import kotlin.math.ceil
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
    private var leadingChip: FrameLayout? = null
    val hasLocalChip get() = leadingChip != null
    private val minTargetPx = ceil(48 * resources.displayMetrics.density).toInt()
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
            if (value != null) leadingChip?.let { applyStyle(it, value) }
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

    fun setCode(code: String?, onClick: () -> Unit) {
        leadingChip?.let { old ->
            old.setOnClickListener(null)
            old.contentDescription = null
            old.findViewById<TextView>(AutofillR.id.autofill_inline_suggestion_title).text = null
            pinnedRow.removeView(old)
        }
        minimumHeight = if (code == null) 0 else minTargetPx
        leadingChip = code?.let {
            // Use the inline renderer's title template locally (also works before API 30).
            val themed = ContextThemeWrapper(context, AutofillR.style.Theme_AutofillInlineSuggestion)
            FrameLayout(context).apply {
                id = R.id.otp_chip
                minimumWidth = minTargetPx
                val chip = LayoutInflater.from(themed).inflate(AutofillR.layout.autofill_inline_suggestion, this, false)
                chip.findViewById<TextView>(AutofillR.id.autofill_inline_suggestion_title).apply {
                    text = code
                    textDirection = View.TEXT_DIRECTION_LTR
                    visibility = View.VISIBLE
                }
                chip.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(chip, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                theme?.let { applyStyle(this, it) }
                contentDescription = context.getString(R.string.otp_chip_description, code)
                setOnClickListener { onClick() }
                pinnedRow.addView(this, 0, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { marginEnd = gapPx })
            }
        }
    }

    private fun applyStyle(target: FrameLayout, colors: KeyboardThemeColors) {
        val chip = target.getChildAt(0)
        InlineAutofillStyle.apply(chip, chip.findViewById(AutofillR.id.autofill_inline_suggestion_title), colors)
    }

    /** Paint at the provider-request height, even when the surrounding hit area is taller. */
    fun sizeLocalChip(heightPx: Int) {
        val chip = leadingChip?.getChildAt(0) ?: return
        if (chip.layoutParams.height != heightPx) chip.layoutParams = chip.layoutParams.apply { height = heightPx }
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

    private companion object {
        const val CHIP_GAP_DP = 4f
    }
}
