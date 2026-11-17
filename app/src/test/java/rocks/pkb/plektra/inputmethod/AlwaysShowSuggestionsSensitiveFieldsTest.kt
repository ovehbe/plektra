package rocks.pkb.plektra.inputmethod

import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
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
import org.robolectric.util.ReflectionHelpers.ClassParameter
import rocks.pkb.plektra.KeyboardBarArrangement
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.suggestions.SuggestionController
import rocks.pkb.plektra.core.suggestions.SuggestionResult
import rocks.pkb.plektra.core.suggestions.SuggestionSource
import rocks.pkb.plektra.inputmethod.autofill.InlineAutofillChips
import rocks.pkb.plektra.inputmethod.autofill.PendingInlineChip
import rocks.pkb.plektra.inputmethod.suggestions.ui.FullSuggestionsBar
import rocks.pkb.plektra.inputmethod.ui.CursorSwipeLayout

/** The full service cannot be created on Robolectric's API 30 (dynamic-receiver permission shim). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlwaysShowSuggestionsSensitiveFieldsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var service: ServiceController<PhysicalKeyboardInputMethodService>? = null

    @After
    fun tearDown() {
        service?.destroy()
    }

    private enum class Placement { ACTIONS, SUGGESTIONS, NONE }

    /** A suggestions-only bar collapses entirely while it has nothing to show, Always show or not. */
    private enum class Bar(
        val actions: Boolean,
        val suggestions: Boolean,
        val ledStrip: Boolean,
        val chipRow: Placement,
        val alwaysShowKeepsEmptyRow: Boolean
    ) {
        ACTIONS_ROW(actions = true, suggestions = true, ledStrip = false, chipRow = Placement.ACTIONS, alwaysShowKeepsEmptyRow = true),
        SUGGESTIONS_ROW(actions = false, suggestions = true, ledStrip = false, chipRow = Placement.SUGGESTIONS, alwaysShowKeepsEmptyRow = false),
        LED_ONLY(actions = false, suggestions = false, ledStrip = true, chipRow = Placement.SUGGESTIONS, alwaysShowKeepsEmptyRow = true)
    }

    /**
     * [predicts]: Plektra runs word predictions there today. Only the plain email variation is restricted, and the
     * time variation shares its bits, so web email predicts and time does not.
     */
    private data class Field(val name: String, val inputType: Int, val sensitive: Boolean, val predicts: Boolean)

    private val fields = listOf(
        Field("text", InputType.TYPE_CLASS_TEXT, sensitive = false, predicts = true),
        Field("multi-line text", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, sensitive = false, predicts = true),
        Field("no-suggestions text", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, sensitive = false, predicts = true),
        Field("email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, sensitive = false, predicts = false),
        Field("web email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, sensitive = false, predicts = true),
        Field("URI", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, sensitive = false, predicts = false),
        Field("password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, sensitive = true, predicts = false),
        Field("visible password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, sensitive = true, predicts = false),
        Field("web password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, sensitive = true, predicts = false),
        Field("number", InputType.TYPE_CLASS_NUMBER, sensitive = true, predicts = true),
        Field("signed decimal number",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL, sensitive = true, predicts = true),
        Field("number password", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, sensitive = true, predicts = false),
        Field("phone", InputType.TYPE_CLASS_PHONE, sensitive = true, predicts = true),
        Field("date and time", InputType.TYPE_CLASS_DATETIME, sensitive = true, predicts = true),
        Field("date", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE, sensitive = true, predicts = true),
        Field("time", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME, sensitive = true, predicts = false)
    )

    private data class Scenario(
        val field: Field,
        val alwaysShow: Boolean,
        val hideSensitive: Boolean,
        val predictions: Boolean,
        val chips: Boolean,
        val bar: Bar
    ) {
        override fun toString() =
            "${field.name} alwaysShow=$alwaysShow hideSensitive=$hideSensitive predictions=$predictions chips=$chips bar=$bar"
    }

    /** [words]: a delivered prediction is visible in the suggestions row. */
    private data class BarState(val suggestionsRow: Boolean, val words: Boolean, val chips: Placement, val heightPx: Int)

    @Test
    fun sensitiveFieldsShowNoSuggestionsRow_andNothingElseChanges() {
        val observed = linkedMapOf<Scenario, BarState>()
        for (bar in Bar.values()) for (alwaysShow in listOf(false, true)) for (hideSensitive in listOf(false, true)) {
            configure(bar, alwaysShow, hideSensitive)
            val (ime, view) = startService()
            // One service per configuration: every focus below is a move from the previous field.
            for (field in fields) for (predictions in listOf(false, true)) for (chips in listOf(false, true)) {
                focus(ime, field)
                if (predictions) deliverPredictions(ime)
                if (chips) deliverChips(ime)
                observed[Scenario(field, alwaysShow, hideSensitive, predictions, chips, bar)] = observe(ime, view)
            }
            service?.destroy()
            service = null
        }

        val failures = mutableListOf<String>()
        fun expect(scenario: Scenario, what: String, expected: Any, actual: Any) {
            if (expected != actual) failures += "$scenario: $what expected $expected but was $actual"
        }
        observed.forEach { (scenario, state) ->
            val hidden = scenario.field.sensitive && scenario.alwaysShow && scenario.hideSensitive
            expect(scenario, "chip row", if (scenario.chips) scenario.bar.chipRow else Placement.NONE, state.chips)
            // Chips pause word predictions.
            val words = scenario.predictions && !scenario.chips && scenario.bar.suggestions && scenario.field.predicts && !hidden
            expect(scenario, "predictions", words, state.words)
            if (!scenario.chips) {
                val forced = scenario.bar.alwaysShowKeepsEmptyRow && scenario.alwaysShow && !hidden
                expect(scenario, "suggestions row", words || forced, state.suggestionsRow)
            }
            if (hidden) {
                expect(scenario, "state as with Always show off and no predictions",
                    observed.getValue(scenario.copy(alwaysShow = false, predictions = false)), state)
            }
            if (!scenario.field.sensitive || !scenario.alwaysShow) {
                expect(scenario, "state as with the switch off", observed.getValue(scenario.copy(hideSensitive = false)), state)
            }
        }
        println("Always show sensitive-field matrix: ${observed.size} scenarios, ${failures.size} failures")
        assertTrue(failures.joinToString("\n", prefix = "${failures.size} failed expectations:\n"), failures.isEmpty())
        assertEquals(fields.size * 2 * 2 * 2 * 2 * Bar.values().size, observed.size)
    }

    @Test
    fun movingFocusBetweenPasswordNumberAndTextRefreshesTheRow() {
        configure(Bar.ACTIONS_ROW, alwaysShow = true, hideSensitive = true)
        val (ime, view) = startService()
        val text = fields.first { it.name == "text" }
        val password = fields.first { it.name == "password" }
        val number = fields.first { it.name == "number" }
        val phone = fields.first { it.name == "phone" }
        val date = fields.first { it.name == "date" }

        listOf(text to true, password to false, text to true, number to false, phone to false, text to true, date to false,
            password to false, text to true)
            .forEachIndexed { step, (field, rowShown) ->
                focus(ime, field)
                assertEquals("step $step ${field.name}", rowShown, observe(ime, view).suggestionsRow)
                deliverPredictions(ime)
                assertEquals("step $step ${field.name} with predictions", rowShown, observe(ime, view).words)
            }

        for (field in listOf(password, number)) {
            focus(ime, field)
            deliverPredictions(ime)
            deliverChips(ime)
            assertEquals(field.name, Placement.ACTIONS, observe(ime, view).chips)
            focus(ime, field, restarting = true)
            assertEquals(field.name, BarState(false, false, Placement.NONE, observe(ime, view).heightPx), observe(ime, view))
        }
        focus(ime, text)
        assertTrue(observe(ime, view).suggestionsRow)
    }

    @Test
    fun addWordCandidateInANumberFieldIsHiddenOnlyWithAlwaysShowAndTheSwitch() {
        val number = fields.first { it.name == "number" }
        for (alwaysShow in listOf(false, true)) for (hideSensitive in listOf(false, true)) {
            configure(Bar.ACTIONS_ROW, alwaysShow, hideSensitive)
            val (ime, view) = startService()
            focus(ime, number)
            ReflectionHelpers.setField(suggestionController(ime), "pendingAddUserWord", "123")
            deliverPredictions(ime, words = emptyList())
            val label = "alwaysShow=$alwaysShow hideSensitive=$hideSensitive"
            val rowShown = !(alwaysShow && hideSensitive)
            assertEquals(label, rowShown, observe(ime, view).suggestionsRow)
            assertEquals(label, rowShown, view.visibleTexts().any { it == "123" })
            service?.destroy()
            service = null
        }
    }

    @Test
    fun trackpadSwipeCannotPickOrAddHiddenPredictions() {
        val number = fields.first { it.name == "number" }
        for (hideSensitive in listOf(false, true)) {
            configure(Bar.ACTIONS_ROW, alwaysShow = true, hideSensitive = hideSensitive)
            SettingsManager.setTrackpadGestureAddWordEnabled(context, true)
            val (ime, _) = startService()
            val editor = EditText(context)
            focus(ime, number)
            ReflectionHelpers.setField(ime, "mStartedInputConnection", editor.onCreateInputConnection(EditorInfo()))
            deliverPredictions(ime)

            swipeUp(ime, third = 1)
            // Auto-capitalization may capitalize the committed word at the start of the field.
            assertEquals("hideSensitive=$hideSensitive", if (hideSensitive) "" else PREDICTIONS[0],
                editor.text.toString().trim().lowercase())

            editor.setText("")
            ReflectionHelpers.setField(suggestionController(ime), "pendingAddUserWord", "123")
            swipeUp(ime, third = 0)
            assertEquals("hideSensitive=$hideSensitive", if (hideSensitive) "123" else null,
                suggestionController(ime).pendingAddWord())
            service?.destroy()
            service = null
        }
    }

    private fun configure(bar: Bar, alwaysShow: Boolean, hideSensitive: Boolean) {
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setStatusBarVariationsVisible(context, bar.actions)
        SettingsManager.setSuggestionsEnabled(context, bar.suggestions)
        SettingsManager.setModifierIndicators(
            context, if (bar.ledStrip) setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP) else emptySet()
        )
        KeyboardBarConfiguration.setArrangement(context, KeyboardBarArrangement.SUGGESTIONS_FIRST)
        SettingsManager.setAlwaysShowSuggestionsBar(context, alwaysShow)
        SettingsManager.setAlwaysShowSuggestionsBarHideSensitive(context, hideSensitive)
    }

    private fun startService(): Pair<PhysicalKeyboardInputMethodService, ViewGroup> {
        val controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = controller
        val ime = controller.get()
        ReflectionHelpers.setField(ime, "mInputEditorInfo", editorInfo(fields.first()))
        ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController").setCandidatesSurfaceActive(false)
        val view = ime.onCreateInputView() as ViewGroup
        return ime to view
    }

    private fun focus(ime: PhysicalKeyboardInputMethodService, field: Field, restarting: Boolean = false) {
        val info = editorInfo(field)
        ReflectionHelpers.setField(ime, "mInputEditorInfo", info)
        ime.onStartInput(info, restarting)
        ime.onStartInputView(info, restarting)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun editorInfo(field: Field) = EditorInfo().apply {
        packageName = APP
        inputType = field.inputType
        fieldId = field.name.hashCode()
    }

    /** The suggestion engine's callback into the service, as after typing in the field. */
    private fun deliverPredictions(ime: PhysicalKeyboardInputMethodService, words: List<String> = PREDICTIONS) {
        val results = words.mapIndexed { index, word -> SuggestionResult(word, 0, 10.0 - index, SuggestionSource.MAIN) }
        ReflectionHelpers.callInstanceMethod<Unit>(
            ime, "handleSuggestionsUpdated", ClassParameter.from(List::class.java, results)
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    }

    private fun suggestionController(ime: PhysicalKeyboardInputMethodService) =
        ReflectionHelpers.getField<SuggestionController>(ime, "suggestionController")

    private fun swipeUp(ime: PhysicalKeyboardInputMethodService, third: Int) {
        ReflectionHelpers.callInstanceMethod<Unit>(ime, "acceptSuggestionAtIndex", ClassParameter.from(Int::class.javaPrimitiveType, third))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun deliverChips(ime: PhysicalKeyboardInputMethodService) {
        val chips = ReflectionHelpers.getField<InlineAutofillChips>(ime, "inlineAutofillChips")
        val pending = List(2) { index ->
            PendingInlineChip(pinned = index == 0) { callback ->
                callback(View(context).apply { tag = CHIP; layoutParams = ViewGroup.LayoutParams(160, 30) })
            }
        }
        assertTrue("chips need a host", chips.onResponse(pending))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun observe(ime: PhysicalKeyboardInputMethodService, root: ViewGroup): BarState {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val statusBar = ReflectionHelpers.getField<StatusBarController>(
            ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController"), "inputStatusBar"
        )
        val rowView = ReflectionHelpers.getField<FullSuggestionsBar?>(statusBar, "fullSuggestionsBar")?.ensureView()
        val suggestionsRow = rowView?.let { it.isShownWithin(root) && it.height > 0 } == true
        val words = suggestionsRow && rowView!!.visibleTexts(root).any { it in PREDICTIONS }
        val chip = root.descendants().firstOrNull { it.tag == CHIP && it.isShownWithin(root) }
        val chips = when {
            chip == null -> Placement.NONE
            chip.hasAncestor<CursorSwipeLayout>() -> Placement.SUGGESTIONS
            else -> Placement.ACTIONS
        }
        return BarState(suggestionsRow, words, chips, if (root.visibility == View.VISIBLE) root.measuredHeight else 0)
    }

    private fun View.isShownWithin(root: View): Boolean {
        var view: View = this
        while (view !== root) {
            if (view.visibility != View.VISIBLE) return false
            view = view.parent as? View ?: return false
        }
        return root.visibility == View.VISIBLE
    }

    private fun View.visibleTexts(root: View = this): List<String> =
        (listOf(this) + descendants()).filter { it is TextView && it.isShownWithin(root) }.map { (it as TextView).text.toString() }

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
        val PREDICTIONS = listOf("alpha", "bravo", "charlie")
    }
}
