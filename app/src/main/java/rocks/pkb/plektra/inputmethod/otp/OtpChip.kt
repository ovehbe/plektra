package rocks.pkb.plektra.inputmethod.otp

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import rocks.pkb.plektra.R
import rocks.pkb.plektra.inputmethod.autofill.InlineAutofillStyle
import rocks.pkb.plektra.inputmethod.ui.KeyboardThemeColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Local inline chip, in its own wrap-content row so neither predictions nor remote chips move hosts. */
@SuppressLint("ViewConstructor")
internal class OtpChip(context: Context, code: String, insert: () -> Unit, dismiss: () -> Unit) : FrameLayout(context) {
    private val visual = InlineAutofillStyle.localChip(this, code)
    private val close = TextView(context)
    private var onDismiss: (() -> Unit)? = dismiss
    private val swipeThreshold = 2 * ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var swallowingSwipe = false
    private var colors: KeyboardThemeColors? = null

    init {
        id = R.id.otp_chip
        val touchSize = (48 * resources.displayMetrics.density).roundToInt()
        val inset = (4 * resources.displayMetrics.density).roundToInt()
        minimumHeight = touchSize
        layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = inset
            bottomMargin = inset
        }
        contentDescription = context.getString(R.string.otp_chip_description, code)
        setOnClickListener { insert() }
        // Keep the digits themselves centered, independently of the close target and layout direction.
        addView(visual, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            marginStart = touchSize
            marginEnd = touchSize
        })
        close.apply {
            setText(R.string.otp_close_symbol)
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.otp_dismiss)
            setOnClickListener { onDismiss?.invoke() }
        }
        addView(close, LayoutParams(touchSize, touchSize, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    fun applyTheme(theme: KeyboardThemeColors) {
        if (colors == theme) return
        colors = theme
        InlineAutofillStyle.themeLocalChip(this, theme)
        visual.background = null
        close.setTextColor(InlineAutofillStyle.textColor(theme))
    }

    fun clear() {
        setOnClickListener(null)
        close.setOnClickListener(null)
        onDismiss = null
        contentDescription = null
        visual.findViewById<TextView>(androidx.autofill.R.id.autofill_inline_suggestion_title).text = null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                swallowingSwipe = false
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val dx = abs(event.rawX - startX)
                if (!swallowingSwipe && dx > swipeThreshold && dx > abs(event.rawY - startY)) {
                    // Cancel the pending tap (possibly on the close child) before swallowing the drag.
                    MotionEvent.obtain(event).also { cancel ->
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    swallowingSwipe = true
                }
                if (swallowingSwipe) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) onDismiss?.invoke()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
