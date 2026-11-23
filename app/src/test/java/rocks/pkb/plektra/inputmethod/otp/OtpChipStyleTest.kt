package rocks.pkb.plektra.inputmethod.otp

import android.content.Intent
import android.graphics.drawable.InsetDrawable
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.KeyboardBarArrangement
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.CandidatesBarController
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import rocks.pkb.plektra.inputmethod.StatusBarController
import rocks.pkb.plektra.inputmethod.autofill.InlineAutofillChipHost
import rocks.pkb.plektra.inputmethod.autofill.InlineAutofillChips
import rocks.pkb.plektra.inputmethod.autofill.InlineAutofillStyle
import rocks.pkb.plektra.inputmethod.autofill.PendingInlineChip
import rocks.pkb.plektra.inputmethod.ui.KeyboardThemeColors
import rocks.pkb.plektra.toKeyboardThemeColors

/**
 * The one-time code chip is an autofill chip of Plektra's own: hosted by the autofill chips host,
 * in the row autofill chips take, at their height, in the theme's autofill chip colors, and with
 * the whole row height as its touch target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtpChipStyleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private var service: ServiceController<PhysicalKeyboardInputMethodService>? = null

    @After
    fun tearDown() {
        service?.destroy()
        SettingsManager.setOtpEnabled(context, false)
        main.idle()
    }

    private data class Layout(val name: String, val actions: Boolean, val suggestions: Boolean,
        val arrangement: KeyboardBarArrangement = KeyboardBarArrangement.SUGGESTIONS_FIRST)

    private val layouts = listOf(
        Layout("actions and suggestions", actions = true, suggestions = true),
        Layout("actions only", actions = true, suggestions = false),
        Layout("suggestions only", actions = false, suggestions = true),
        Layout("shared row", actions = true, suggestions = true, arrangement = KeyboardBarArrangement.SHARED),
        Layout("empty bar", actions = false, suggestions = false)
    )

    private val customTheme = SettingsManager.defaultKeyboardTheme().copy(
        background = 0xFF102030.toInt(), suggestion = 0xFF405060.toInt(), textAndIcons = 0xFFFFE070.toInt(),
        suggestionsHeightScale = 1.2f, variationsHeightScale = 0.9f, autofillRowHeightScale = 1.5f
    )

    @Test
    fun otpChipIsAnAutofillChipInPlacementStyleAndTouchTarget() {
        val failures = mutableListOf<String>()
        var code = 482900
        for (layout in layouts) for (custom in listOf(false, true)) {
            val otp = (++code).toString()
            val label = "${layout.name} ${if (custom) "custom" else "default"} theme"
            // Where autofill chips go in this bar, with OTP off.
            val (autofillIme, autofillRoot) = start(layout, custom)
            focus(autofillIme, 1)
            deliverAutofillChips(autofillIme)
            layout(autofillRoot)
            val autofillRow = hostRow(autofillIme)
            val autofillChipPx = autofillRoot.descendants().firstOrNull { it.tag == AUTOFILL_CHIP }?.let {
                (it.parent as View).height
            }
            stop()

            val (ime, root) = start(layout, custom)
            SettingsManager.setOtpEnabled(context, true)
            val editor = focus(ime, 2)
            deliver(otp)
            layout(root)
            val chip = root.descendants().firstOrNull { it.id == R.id.otp_chip && it.visibleUpTree() } as? TextView
            if (chip == null) {
                val hidden = root.descendants().firstOrNull { it.id == R.id.otp_chip }?.let { found ->
                    (sequenceOf(found) + found.ancestors()).firstOrNull { it.visibility != View.VISIBLE }?.javaClass?.simpleName
                }
                failures += "$label: no chip on screen (hidden by $hidden)"
                stop()
                continue
            }
            val bar = inputBar(ime)
            val host = ReflectionHelpers.getField<InlineAutofillChipHost?>(bar, "inlineAutofillHost")
            if (chip.ancestors().none { it === host }) failures += "$label: chip not in the autofill chips host"
            if (layout.name != "empty bar" && hostRow(ime) != autofillRow) {
                failures += "$label: chip in ${hostRow(ime)}, autofill chips in $autofillRow"
            }
            if (autofillChipPx != null && chip.height != autofillChipPx) {
                failures += "$label: chip ${chip.height}px tall, autofill chip row ${autofillChipPx}px"
            }
            val rowPx = (host?.parent as? View)?.height ?: 0
            if (chip.height != host?.height || host.height != rowPx) {
                failures += "$label: chip ${chip.height}px, host ${host?.height}px, row ${rowPx}px: not the full row"
            }
            if (chip.width < dp(InlineAutofillStyle.MIN_TOUCH_TARGET_DP)) failures += "$label: chip only ${chip.width}px wide"
            checkStyle(label, chip, bar, failures)
            // A tap at the very top or bottom edge of the row still lands on the chip.
            val hostRowView = host?.parent as? View
            if (hostRowView != null) {
                val rowTop = android.graphics.Rect().also { hostRowView.getDrawingRect(it); root.offsetDescendantRectToMyCoords(hostRowView, it) }
                val chipBox = android.graphics.Rect(0, 0, chip.width, chip.height).also { root.offsetDescendantRectToMyCoords(chip, it) }
                if (hitTest(root, chipBox.centerX(), rowTop.bottom - 1) !== chip) failures += "$label: bottom edge misses the chip"
            }
            tapAt(root, chip, 0f)
            if (editor.text.toString() != otp) failures += "$label: edge tap inserted '${editor.text}'"
            stop()
        }
        assertTrue(failures.joinToString("\n", prefix = "${failures.size} failures:\n"), failures.isEmpty())
    }

    private fun checkStyle(label: String, chip: TextView, bar: StatusBarController, failures: MutableList<String>) {
        val theme = ReflectionHelpers.callInstanceMethod<KeyboardThemeColors>(bar, "inlineAutofillTheme")
        fun expect(what: String, expected: Any?, actual: Any?) {
            if (expected != actual) failures += "$label: $what expected $expected but was $actual"
        }
        expect("text color", InlineAutofillStyle.textColor(theme), chip.currentTextColor)
        expect("text size", TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, InlineAutofillStyle.TITLE_TEXT_SIZE_SP,
            context.resources.displayMetrics), chip.textSize)
        expect("start padding", dp(InlineAutofillStyle.CHIP_HORIZONTAL_PADDING_DP), chip.paddingStart)
        expect("end padding", dp(InlineAutofillStyle.CHIP_HORIZONTAL_PADDING_DP), chip.paddingEnd)
        val inset = chip.background as? InsetDrawable
        val shape = inset?.drawable as? android.graphics.drawable.GradientDrawable
        expect("chip color", InlineAutofillStyle.chipColor(theme), shape?.color?.defaultColor)
        assertNotNull("$label: leading icon", chip.compoundDrawablesRelative[0])
    }

    private fun start(layout: Layout, custom: Boolean): Pair<PhysicalKeyboardInputMethodService, ViewGroup> {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setAutoShowKeyboard(context, true)
        SettingsManager.setAutoShowRespectAppVisibility(context, false)
        SettingsManager.setStatusBarVariationsVisible(context, layout.actions)
        SettingsManager.setSuggestionsEnabled(context, layout.suggestions)
        SettingsManager.setModifierIndicators(context, emptySet())
        SettingsManager.setInlineAutofillEnabled(context, true)
        SettingsManager.setKeyboardBarHeightAnimation(context, false)
        KeyboardBarConfiguration.setArrangement(context, layout.arrangement)
        if (custom) SettingsManager.setKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE, customTheme)
        SettingsManager.setOtpSourceEnabled(context, OtpSource.HELPER, true)
        val controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = controller
        val ime = controller.get()
        ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController").setCandidatesSurfaceActive(false)
        return ime to (ime.onCreateInputView() as ViewGroup).also { it.tag = INPUT_ROOT }
    }

    private fun stop() {
        service?.destroy()
        service = null
        SettingsManager.setOtpEnabled(context, false)
        main.idle()
    }

    private fun focus(ime: PhysicalKeyboardInputMethodService, id: Int): EditText {
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER; packageName = APP; fieldId = id }
        val editor = EditText(context).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        ReflectionHelpers.setField(ime, "mInputEditorInfo", info)
        ReflectionHelpers.setField(ime, "mStartedInputConnection", editor.onCreateInputConnection(EditorInfo()) as InputConnection)
        ReflectionHelpers.setField(ime, "mInputStarted", true)
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        main.idle()
        return editor
    }

    private fun deliver(code: String) {
        OtpHelperReceiver().onReceive(context,
            Intent(OtpHelperReceiver.ACTION).setPackage(context.packageName).putExtra("code", code))
        main.idle()
    }

    private fun deliverAutofillChips(ime: PhysicalKeyboardInputMethodService) {
        val chips = ReflectionHelpers.getField<InlineAutofillChips>(ime, "inlineAutofillChips")
        val height = ReflectionHelpers.callInstanceMethod<Int?>(inputBar(ime), "inlineAutofillChipHeightPx",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false)) ?: 30
        chips.onResponse(listOf(PendingInlineChip(pinned = true) { done ->
            done(View(context).apply { tag = AUTOFILL_CHIP; layoutParams = ViewGroup.LayoutParams(160, height) })
        }))
        main.idle()
    }

    private fun inputBar(ime: PhysicalKeyboardInputMethodService): StatusBarController = ReflectionHelpers.getField(
        ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController"), "inputStatusBar")

    /** "actions", "suggestions" or "none": which bar row holds the chips host now. */
    private fun hostRow(ime: PhysicalKeyboardInputMethodService): String {
        val bar = inputBar(ime)
        val host = ReflectionHelpers.getField<View?>(bar, "inlineAutofillHost")?.takeIf { it.visibleUpTree() } ?: return "none"
        val actions = ReflectionHelpers.getField<View?>(bar, "variationsWrapper")
        return if (host.ancestors().any { it === actions }) "actions" else "suggestions"
    }

    private fun layout(root: View) {
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
    }

    /**
     * Taps the deepest clickable view under [y] px from the top of [target]'s row, at the chip's
     * horizontal center. A detached bar never runs posted clicks, so this hit-tests and clicks.
     */
    private fun tapAt(root: ViewGroup, target: View, y: Float) {
        val bounds = android.graphics.Rect(0, 0, target.width, target.height)
        root.offsetDescendantRectToMyCoords(target, bounds)
        val hit = hitTest(root, bounds.exactCenterX().toInt(), (bounds.top + y).toInt()) ?: return
        hit.performClick()
        main.idle()
    }

    private fun hitTest(view: View, x: Int, y: Int): View? {
        if (!view.visibleUpTree()) return null
        if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            if (x in child.left until child.right && y in child.top until child.bottom) {
                hitTest(child, x - child.left + child.scrollX, y - child.top + child.scrollY)?.let { return it }
            }
        }
        return view.takeIf { it.isClickable }
    }

    /**
     * Visible up to the input view. Robolectric never completes the window show that adds the
     * framework's input frame for an otherwise empty bar, so the walk stops at the bar root.
     */
    private fun View.visibleUpTree(): Boolean {
        var view: View? = this
        while (view != null) {
            if (view.visibility != View.VISIBLE) return false
            if (view.tag == INPUT_ROOT) return true
            view = view.parent as? View
        }
        return true
    }

    private fun View.ancestors(): Sequence<View> = generateSequence(parent as? View) { it.parent as? View }

    private fun View.descendants(): List<View> =
        if (this is ViewGroup) (0 until childCount).flatMap { listOf(getChildAt(it)) + getChildAt(it).descendants() }
        else emptyList()

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private companion object {
        const val APP = "com.example.bank"
        const val AUTOFILL_CHIP = "autofill chip"
        const val INPUT_ROOT = "input view"
    }
}
