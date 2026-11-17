package rocks.pkb.plektra.inputmethod

import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
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
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.autofill.InlineAutofillChips
import rocks.pkb.plektra.inputmethod.autofill.PendingInlineChip
import rocks.pkb.plektra.inputmethod.suggestions.ui.FullSuggestionsBar
import rocks.pkb.plektra.inputmethod.ui.CursorSwipeLayout

/**
 * "Also hide the variations row" drops the variations row wherever "Hide where it stays empty"
 * drops the empty suggestions row, unless autofill chips are using it. The full service cannot
 * be created on Robolectric's API 30.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HideVariationsRowMatrixTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var service: ServiceController<PhysicalKeyboardInputMethodService>? = null

    @After
    fun tearDown() {
        service?.destroy()
    }

    private enum class Keyboard(val software: Boolean, val arrangement: KeyboardBarArrangement) {
        HARDWARE(false, KeyboardBarArrangement.SUGGESTIONS_FIRST),
        HARDWARE_SHARED_ROW(false, KeyboardBarArrangement.SHARED),
        SOFTWARE(true, KeyboardBarArrangement.SUGGESTIONS_FIRST)
    }

    private enum class Placement { ACTIONS, SUGGESTIONS, NONE }

    /** [staysEmpty]: Always show's row is dropped there by "Hide where it stays empty". */
    private data class Field(val name: String, val inputType: Int, val staysEmpty: Boolean)

    private val fields = listOf(
        Field("text", InputType.TYPE_CLASS_TEXT, staysEmpty = false),
        Field("multi-line text", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, staysEmpty = false),
        Field("no-suggestions text", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, staysEmpty = false),
        Field("email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, staysEmpty = true),
        Field("web email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, staysEmpty = false),
        Field("URI", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, staysEmpty = true),
        Field("Chrome URL bar", 0x80011, staysEmpty = true),
        // What Chrome and WebView report for autofill.me's card name, card number and CVV inputs.
        Field("web form text", 0xc0a1, staysEmpty = false),
        Field("person name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME, staysEmpty = false),
        Field("password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, staysEmpty = true),
        Field("visible password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, staysEmpty = true),
        Field("web password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, staysEmpty = true),
        Field("number", InputType.TYPE_CLASS_NUMBER, staysEmpty = true),
        Field("signed decimal number",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL, staysEmpty = true),
        Field("number password", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, staysEmpty = true),
        Field("phone", InputType.TYPE_CLASS_PHONE, staysEmpty = true),
        Field("date and time", InputType.TYPE_CLASS_DATETIME, staysEmpty = true),
        Field("date", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE, staysEmpty = true),
        Field("time", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME, staysEmpty = true)
    )

    private data class Scenario(
        val keyboard: Keyboard,
        val alwaysShow: Boolean,
        val hideEmpty: Boolean,
        val hideVariations: Boolean,
        val animate: Boolean,
        val field: Field,
        val chips: Boolean
    ) {
        override fun toString() = "$keyboard alwaysShow=$alwaysShow hideEmpty=$hideEmpty hideVariations=$hideVariations " +
            "animate=$animate ${field.name} chips=$chips"
    }

    private data class BarState(
        val suggestionsRow: Boolean,
        val actionsRowPx: Int,
        val chips: Placement,
        val heightPx: Int
    )

    @Test
    fun variationsRowHidesOnlyWithBothSwitchesWhereTheRowStaysEmpty() {
        val observed = linkedMapOf<Scenario, BarState>()
        val failures = mutableListOf<String>()
        for (keyboard in Keyboard.values()) for (alwaysShow in listOf(false, true)) for (hideEmpty in listOf(false, true))
            for (hideVariations in listOf(false, true)) for (animate in listOf(false, true)) {
            configure(keyboard, alwaysShow, hideEmpty, hideVariations, animate)
            val (ime, root) = startService()
            val frames = FrameChecker(ime, root, keyboard, animate, failures)
            // One service per configuration: every focus below is a move from the previous field.
            for (field in fields) for (chips in listOf(false, true)) {
                val scenario = Scenario(keyboard, alwaysShow, hideEmpty, hideVariations, animate, field, chips)
                frames.step("$scenario focus") { focus(ime, field) }
                if (chips) frames.step("$scenario chips arrive") {
                    if (!deliverChips(ime)) failures += "$scenario: chips found no host"
                }
                observed[scenario] = observe(ime, root)
            }
            service?.destroy()
            service = null
        }

        fun expect(scenario: Scenario, what: String, expected: Any, actual: Any) {
            if (expected != actual) failures += "$scenario: $what expected $expected but was $actual"
        }
        observed.forEach { (scenario, state) ->
            val switchOff = observed.getValue(scenario.copy(hideVariations = false))
            // The software keyboard keeps its own empty suggestions row, so neither switch drops a row there.
            val hides = scenario.alwaysShow && scenario.hideEmpty && scenario.hideVariations &&
                scenario.field.staysEmpty && !scenario.chips && !scenario.keyboard.software
            if (!hides) {
                expect(scenario, "state as with the switch off", switchOff, state)
            } else {
                expect(scenario, "variations row", 0, state.actionsRowPx)
                expect(scenario, "chips", Placement.NONE, state.chips)
                expect(scenario, "suggestions row", false, state.suggestionsRow)
                expect(scenario, "bar height with nothing to show", 0, state.heightPx)
            }
            if (scenario.chips) {
                val expectedRow = if (scenario.keyboard == Keyboard.HARDWARE_SHARED_ROW) Placement.SUGGESTIONS else Placement.ACTIONS
                expect(scenario, "chips shown", expectedRow, state.chips)
                if (scenario.alwaysShow && scenario.hideEmpty && !scenario.keyboard.software) {
                    expect(scenario, "only the chips row: suggestions row", expectedRow == Placement.SUGGESTIONS, state.suggestionsRow)
                    expect(scenario, "only the chips row: variations row", expectedRow == Placement.ACTIONS, state.actionsRowPx > 0)
                }
            }
            if (!scenario.field.staysEmpty && !scenario.chips) {
                expect(scenario, "variations row in a field that gets predictions", true,
                    state.actionsRowPx > 0 || (scenario.keyboard == Keyboard.HARDWARE_SHARED_ROW && state.suggestionsRow))
            }
            expect(scenario, "as with the animation off", observed.getValue(scenario.copy(animate = false)), state)
        }
        println("Hide variations row matrix: ${observed.size} scenarios, ${failures.size} failures")
        assertTrue(failures.take(400).joinToString("\n", prefix = "${failures.size} failed expectations:\n"), failures.isEmpty())
        assertEquals(Keyboard.values().size * 2 * 2 * 2 * 2 * fields.size * 2, observed.size)
    }

    @Test
    fun offByDefault() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        assertEquals(false, SettingsManager.getAlwaysShowSuggestionsBarHideVariations(context))
    }

    /**
     * With the animation on, a hardware bar row change resizes the window once, to the larger
     * height, and reveals the new height by clipping from the top; it never jumps.
     */
    private inner class FrameChecker(
        private val ime: PhysicalKeyboardInputMethodService,
        private val root: ViewGroup,
        private val keyboard: Keyboard,
        private val animate: Boolean,
        private val failures: MutableList<String>
    ) {
        private var settled: Int? = null

        fun step(label: String, action: () -> Unit) {
            action()
            val chrome = chrome(ime)
            val firstFrame = visibleHeight(chrome)
            val start = settled
            val animator = ReflectionHelpers.getField<Any?>(chrome, "heightAnimator")
            val frames = mutableListOf(firstFrame)
            val laidOut = mutableSetOf<Int>()
            repeat(8) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
                measureRoot()
                if (ReflectionHelpers.getField<Any?>(chrome, "heightAnimator") === animator && animator != null &&
                    chrome.clipBounds != null) {
                    laidOut += root.measuredHeight
                    if (chrome.clipBounds!!.bottom != chrome.height) failures += "$label: clip not anchored to the bottom"
                }
                frames += visibleHeight(chrome)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            measureRoot()
            if (chrome.clipBounds != null) failures += "$label: settles clipped ${chrome.clipBounds}"
            val end = visibleHeight(chrome)
            settled = end
            if (start == null || keyboard.software) return
            if (!animate || start == end) {
                if (firstFrame != end) failures += "$label: first frame $firstFrame px, settled $end px"
                return
            }
            if (firstFrame != start) failures += "$label: jumps from $start px to $firstFrame px on the first frame"
            if (laidOut.size > 1 || laidOut.any { it != maxOf(start, end) }) {
                failures += "$label: window resized to $laidOut while animating $start -> $end px"
            }
            val low = minOf(start, end)
            val high = maxOf(start, end)
            if (frames.any { it < low || it > high }) failures += "$label: frames $frames leave $start..$end px"
            if (frames.zipWithNext().any { (a, b) -> if (end > start) b < a else b > a }) {
                failures += "$label: frames $frames are not monotonic"
            }
        }

        private fun measureRoot() {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }

        private fun visibleHeight(chrome: ImeChromeLayout): Int {
            measureRoot()
            if (!chrome.isShownWithin(root)) return 0
            return chrome.height - (chrome.clipBounds?.top ?: 0)
        }
    }

    private fun configure(keyboard: Keyboard, alwaysShow: Boolean, hideEmpty: Boolean, hideVariations: Boolean, animate: Boolean) {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSoftwareKeyboardMode(context,
            if (keyboard.software) SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL else SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setStatusBarVariationsVisible(context, true)
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setModifierIndicators(context, emptySet())
        KeyboardBarConfiguration.setArrangement(context, keyboard.arrangement)
        SettingsManager.setInlineAutofillEnabled(context, true)
        // Robolectric never reports the input view as shown; auto-show stands in for it.
        SettingsManager.setAutoShowKeyboard(context, true)
        SettingsManager.setAutoShowRespectAppVisibility(context, false)
        SettingsManager.setAlwaysShowSuggestionsBar(context, alwaysShow)
        SettingsManager.setAlwaysShowSuggestionsBarHideSensitive(context, hideEmpty)
        SettingsManager.setAlwaysShowSuggestionsBarHideVariations(context, hideVariations)
        SettingsManager.setKeyboardBarHeightAnimation(context, animate)
    }

    private fun startService(): Pair<PhysicalKeyboardInputMethodService, ViewGroup> {
        val controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = controller
        val ime = controller.get()
        ReflectionHelpers.setField(ime, "mInputEditorInfo", editorInfo(fields.first()))
        ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController").setCandidatesSurfaceActive(false)
        return ime to ime.onCreateInputView() as ViewGroup
    }

    private fun focus(ime: PhysicalKeyboardInputMethodService, field: Field) {
        val info = editorInfo(field)
        ReflectionHelpers.setField(ime, "mInputEditorInfo", info)
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun editorInfo(field: Field) = EditorInfo().apply {
        packageName = APP
        inputType = field.inputType
        fieldId = field.name.hashCode()
    }

    private fun deliverChips(ime: PhysicalKeyboardInputMethodService): Boolean {
        val chips = ReflectionHelpers.getField<InlineAutofillChips>(ime, "inlineAutofillChips")
        val pending = List(2) { index ->
            PendingInlineChip(pinned = index == 0) { callback ->
                callback(View(context).apply { tag = CHIP; layoutParams = ViewGroup.LayoutParams(160, 30) })
            }
        }
        val hosted = chips.onResponse(pending)
        shadowOf(Looper.getMainLooper()).idle()
        return hosted
    }

    private fun inputBar(ime: PhysicalKeyboardInputMethodService): StatusBarController =
        ReflectionHelpers.getField(ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController"), "inputStatusBar")

    private fun chrome(ime: PhysicalKeyboardInputMethodService): ImeChromeLayout =
        ReflectionHelpers.getField(inputBar(ime), "statusBarLayout")

    private fun observe(ime: PhysicalKeyboardInputMethodService, root: ViewGroup): BarState {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        val statusBar = inputBar(ime)
        val rowView = ReflectionHelpers.getField<FullSuggestionsBar?>(statusBar, "fullSuggestionsBar")?.ensureView()
        val suggestionsRow = rowView?.let { it.isShownWithin(root) && it.height > 0 } == true
        val actionsRow = ReflectionHelpers.getField<View?>(statusBar, "variationsWrapper")
            ?.takeIf { it.isShownWithin(root) }?.height ?: 0
        val chip = root.descendants().firstOrNull { it.tag == CHIP && it.isShownWithin(root) }
        val chips = when {
            chip == null -> Placement.NONE
            chip.hasAncestor<CursorSwipeLayout>() -> Placement.SUGGESTIONS
            else -> Placement.ACTIONS
        }
        val height = if (root.visibility == View.VISIBLE && chrome(ime).isShownWithin(root)) root.measuredHeight else 0
        return BarState(suggestionsRow, actionsRow, chips, height)
    }

    private fun View.isShownWithin(root: View): Boolean {
        var view: View = this
        while (view !== root) {
            if (view.visibility != View.VISIBLE) return false
            view = view.parent as? View ?: return false
        }
        return root.visibility == View.VISIBLE
    }

    private fun View.descendants(): List<View> =
        if (this is ViewGroup) (0 until childCount).flatMap { listOf(getChildAt(it)) + getChildAt(it).descendants() } else emptyList()

    private inline fun <reified T> View.hasAncestor(): Boolean {
        var view = parent
        while (view != null) {
            if (view is T) return true
            view = view.parent
        }
        return false
    }

    private companion object {
        const val APP = "com.example.login"
        const val CHIP = "test inline chip"
    }
}
