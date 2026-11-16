package rocks.pkb.plektra.inputmethod.autofill

import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import rocks.pkb.plektra.KeyboardBarArrangement
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.SymLayoutController
import rocks.pkb.plektra.inputmethod.CandidatesBarController
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import rocks.pkb.plektra.inputmethod.StatusBarController
import rocks.pkb.plektra.inputmethod.ui.CursorSwipeLayout

/** The full service cannot be created on Robolectric's API 30 (dynamic-receiver permission shim). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineAutofillServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var service: ServiceController<PhysicalKeyboardInputMethodService>? = null

    @After
    fun tearDown() {
        service?.destroy()
    }

    private enum class Bar { ACTIONS_ROW, SUGGESTIONS_ROW, NONE }

    private data class RequestRow(
        val name: String,
        val expected: Bar,
        val actions: Boolean = true,
        val suggestions: Boolean = true,
        val enabled: Boolean = true,
        val uiVersion1: Boolean = true,
        val neverShowApp: Boolean = false,
        val navMode: Boolean = false,
        val symPage: Boolean = false,
        val candidatesOnly: Boolean = false,
        val virtualKeyboard: Boolean = false,
        val respectAppVisibility: Boolean = false
    )

    private val requestRows = listOf(
        RequestRow("hardware bar with actions", Bar.ACTIONS_ROW),
        RequestRow("suggestions only", Bar.SUGGESTIONS_ROW, actions = false),
        RequestRow("shared row", Bar.SUGGESTIONS_ROW),
        RequestRow("setting off", Bar.NONE, enabled = false),
        RequestRow("no inline UI version 1", Bar.NONE, uiVersion1 = false),
        RequestRow("never-show app", Bar.NONE, neverShowApp = true),
        RequestRow("no bar content", Bar.NONE, actions = false, suggestions = false),
        RequestRow("nav mode", Bar.NONE, navMode = true),
        RequestRow("hardware SYM page", Bar.NONE, symPage = true),
        RequestRow("candidates-only before an input view", Bar.NONE, candidatesOnly = true),
        RequestRow("virtual keyboard shows on input start", Bar.ACTIONS_ROW, virtualKeyboard = true),
        RequestRow("virtual keyboard waits for the app", Bar.NONE, virtualKeyboard = true, respectAppVisibility = true)
    )

    @Test
    fun requestIsOfferedOnlyWhileABarRowCanShowChips_sizedToThatRow() {
        requestRows.forEach { row ->
            configure(actions = row.actions, suggestions = row.suggestions,
                arrangement = if (row.name == "shared row") KeyboardBarArrangement.SHARED else KeyboardBarArrangement.SUGGESTIONS_FIRST)
            SettingsManager.setInlineAutofillEnabled(context, row.enabled)
            SettingsManager.setExperimentalCandidatesViewEnabled(context, row.candidatesOnly)
            SettingsManager.setAutoShowKeyboard(context, true)
            SettingsManager.setAutoShowRespectAppVisibility(context, row.respectAppVisibility)
            if (row.virtualKeyboard) {
                SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL)
            }
            SettingsManager.setKeyboardBarHiddenInApp(context, APP, row.neverShowApp)
            val ime = startService()
            if (row.navMode) modifiers(ime).apply { ctrlLatchActive = true; ctrlLatchFromNavMode = true }
            if (row.symPage) symLayout(ime).toggleSymPage()

            val request = ime.onCreateInlineSuggestionsRequest(uiExtras(row.uiVersion1))

            val theme = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController").inlineAutofillTheme()
            val expectedHeight = when (row.expected) {
                Bar.ACTIONS_ROW -> dp(55f * theme.variationsHeightScale - 8f)
                Bar.SUGGESTIONS_ROW -> dp(36f * theme.suggestionsHeightScale - 8f)
                Bar.NONE -> null
            }
            assertEquals(row.name, expectedHeight, request?.inlinePresentationSpecs?.single()?.maxSize?.height)
            request?.let { assertEquals(row.name, InlineAutofill.MAX_SUGGESTIONS, it.maxSuggestionCount) }
            symLayout(ime).closeSymPage()
            service?.destroy()
            service = null
        }
    }

    @Test
    fun clipboardOverlayOffersNoChipRow() {
        configure(actions = true, suggestions = true)
        val (controller, _) = inputSurface()
        assertNotNull(controller.inlineAutofillChipHeightPx(0, false))

        controller.updateStatusBars(snapshot().copy(clipboardOverlay = true), "", null, null)

        assertNull(controller.inlineAutofillChipHeightPx(0, false))
    }

    private enum class Placement { ACTIONS, SUGGESTIONS, NONE }

    private data class FlowRow(
        val name: String,
        val responseChips: Int,
        val expected: Placement,
        val actions: Boolean = true,
        val suggestions: Boolean = true,
        val passwordField: Boolean = false,
        val candidatesSurface: Boolean = false,
        val failedInflations: Set<Int> = emptySet(),
        val hostGoneBeforeResponse: Boolean = false,
        val hostGoneDuringInflation: Boolean = false,
        val returned: Boolean = expected != Placement.NONE
    )

    private val flowRows = listOf(
        FlowRow("T1 chips land in the actions row", 2, Placement.ACTIONS),
        FlowRow("T2 actions off: suggestions strip", 2, Placement.SUGGESTIONS, actions = false),
        FlowRow("T3 password field still shows chips", 2, Placement.SUGGESTIONS, actions = false, passwordField = true),
        FlowRow("T4 candidates-only surface hosts them", 2, Placement.ACTIONS, candidatesSurface = true),
        FlowRow("T6 empty response", 0, Placement.NONE),
        FlowRow("T7 a chip that fails to inflate is skipped", 3, Placement.ACTIONS, failedInflations = setOf(1)),
        FlowRow("T7 no chip inflates", 2, Placement.NONE, failedInflations = setOf(0, 1), returned = true),
        FlowRow("T9 host gone before the response", 2, Placement.NONE, hostGoneBeforeResponse = true),
        FlowRow("T9 host gone during inflation", 2, Placement.NONE, hostGoneDuringInflation = true, returned = true)
    )

    @Test
    fun responsesReachTheActiveSurface_orNothingAttaches() {
        flowRows.forEach { row ->
            configure(row.actions, row.suggestions)
            val controller = CandidatesBarController(context)
            var blocked = false
            controller.isSurfaceBlocked = { blocked }
            val inputView = controller.getInputView()
            val candidatesView = controller.getCandidatesView()
            controller.setCandidatesSurfaceActive(row.candidatesSurface)
            controller.updateStatusBars(snapshot().copy(shouldDisableSuggestions = row.passwordField), "", null, null)
            val chips = chipsFor(controller)
            val pending = List(row.responseChips) { DeferredChip() }
            if (row.hostGoneBeforeResponse) blocked = true

            val returned = chips.onResponse(pending.map { it.pending })
            if (row.hostGoneDuringInflation) blocked = true
            pending.forEachIndexed { index, chip -> chip.complete(inflated = index !in row.failedInflations) }

            val (active, inactive) = if (row.candidatesSurface) candidatesView to inputView else inputView to candidatesView
            val attached = pending.mapNotNull { it.view }.filter { it.parent != null }
            assertEquals(row.name, row.returned, returned)
            assertEquals(row.name, row.expected, attached.firstOrNull()?.let { placementOf(it, active) } ?: Placement.NONE)
            assertTrue(row.name, attached.none { it.isInside(inactive) })
            if (row.expected != Placement.NONE) {
                assertEquals(row.name, row.responseChips - row.failedInflations.size, attached.size)
            }
        }
    }

    @Test
    fun t5_pinnedChipsKeepResponseOrderWhateverOrderTheyInflateIn() {
        configure(actions = false, suggestions = true)
        val (controller, view) = inputSurface()
        val pending = listOf(DeferredChip(pinned = false), DeferredChip(pinned = true), DeferredChip(pinned = false))

        assertTrue(chipsFor(controller).onResponse(pending.map { it.pending }))
        listOf(2, 0, 1).forEach { pending[it].complete() }
        layout(view)

        val views = pending.map { it.view!! }
        assertEquals(listOf(views[1], views[0], views[2]), views.sortedBy { it.leftIn(view) })
        assertFalse(views[1].hasAncestor<android.widget.HorizontalScrollView>())
        assertTrue(views[0].hasAncestor<android.widget.HorizontalScrollView>())
        assertTrue(views[2].hasAncestor<android.widget.HorizontalScrollView>())
    }

    @Test
    fun t8_aNewerResponseDropsTheOlderBatchStillInflating() {
        configure(actions = true, suggestions = true)
        val (controller, _) = inputSurface()
        val chips = chipsFor(controller)
        val older = List(2) { DeferredChip() }
        val newer = List(1) { DeferredChip() }

        assertTrue(chips.onResponse(older.map { it.pending }))
        assertTrue(chips.onResponse(newer.map { it.pending }))
        newer.single().complete()
        older.forEach { it.complete() }

        assertNotNull(newer.single().view!!.parent)
        assertTrue(older.all { it.view!!.parent == null })
    }

    @Test
    fun emptyResponseClearsBothSurfaces() {
        configure(actions = true, suggestions = true)
        val controller = CandidatesBarController(context)
        controller.getInputView()
        controller.getCandidatesView()
        controller.setCandidatesSurfaceActive(true)
        controller.updateStatusBars(snapshot(), "", null, null)
        val inputBar = ReflectionHelpers.getField<StatusBarController>(controller, "inputStatusBar")
        val candidatesBar = ReflectionHelpers.getField<StatusBarController>(controller, "candidatesStatusBar")
        val onInput = chip()
        val onCandidates = chip()
        assertTrue(inputBar.showInlineAutofill(listOf(onInput), listOf(false)))
        assertTrue(candidatesBar.showInlineAutofill(listOf(onCandidates), listOf(false)))

        assertFalse(chipsFor(controller).onResponse(emptyList()))

        assertNull(onInput.parent)
        assertNull(onCandidates.parent)
    }

    private enum class Lifecycle { FINISH_INPUT, FINISH_INPUT_VIEW, START_INPUT, RESTART_INPUT }

    @Test
    fun lifecycleClearsShownChipsAndDropsBatchesStillInflating() {
        Lifecycle.values().forEach { event ->
            configure(actions = true, suggestions = true)
            val ime = startService()
            val chips = ReflectionHelpers.getField<InlineAutofillChips>(ime, "inlineAutofillChips")
            val shown = DeferredChip()
            assertTrue("$event", chips.onResponse(listOf(shown.pending)))
            shown.complete()
            assertNotNull("$event", shown.view!!.parent)
            val inflating = DeferredChip()
            assertTrue("$event", chips.onResponse(listOf(inflating.pending)))

            when (event) {
                Lifecycle.FINISH_INPUT -> ime.onFinishInput()
                Lifecycle.FINISH_INPUT_VIEW -> ime.onFinishInputView(true)
                Lifecycle.START_INPUT -> ime.onStartInput(editorInfo(), false)
                Lifecycle.RESTART_INPUT -> ime.onStartInput(editorInfo(), true)
            }
            inflating.complete()

            assertNull("$event", shown.view!!.parent)
            assertNull("$event", inflating.view!!.parent)
            service?.destroy()
            service = null
        }
    }

    @Test
    fun serviceResponse_isFalseWithoutAVisibleHost_andTrueWhileOneTakesTheBatch() {
        configure(actions = true, suggestions = true)
        val ime = startService()
        assertTrue(ime.onInlineSuggestionsResponse(response(suggestion(pinned = true), suggestion(pinned = false))))
        assertFalse(ime.onInlineSuggestionsResponse(response()))

        SettingsManager.setKeyboardBarHiddenInApp(context, APP, true)
        assertFalse(ime.onInlineSuggestionsResponse(response(suggestion(pinned = false))))
    }

    /** An inflation the test completes later, like the autofill service's asynchronous rendering. */
    private inner class DeferredChip(pinned: Boolean = false) {
        private var done: ((View?) -> Unit)? = null
        var view: View? = null
            private set
        val pending = PendingInlineChip(pinned) { callback -> done = callback }

        fun complete(inflated: Boolean = true) {
            view = if (inflated) chip() else null
            done?.invoke(view)
        }
    }

    private fun chipsFor(controller: CandidatesBarController) = InlineAutofillChips(
        canHost = { controller.inlineAutofillChipHeightPx(0, false) != null },
        show = controller::showInlineAutofill,
        clearSurfaces = controller::clearInlineAutofill
    )

    private fun startService(): PhysicalKeyboardInputMethodService {
        val controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = controller
        val ime = controller.get()
        ReflectionHelpers.setField(ime, "mInputEditorInfo", editorInfo())
        // A real show selects the surface for the backend; Robolectric never runs that posted step.
        ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController")
            .setCandidatesSurfaceActive(SettingsManager.getExperimentalCandidatesViewEnabled(context))
        ime.onCreateInputView()
        return ime
    }

    private fun editorInfo() = EditorInfo().apply {
        packageName = APP
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun modifiers(ime: PhysicalKeyboardInputMethodService) =
        ReflectionHelpers.getField<rocks.pkb.plektra.core.ModifierStateController>(ime, "modifierStateController")

    private fun symLayout(ime: PhysicalKeyboardInputMethodService) =
        ReflectionHelpers.getField<SymLayoutController>(ime, "symLayoutController")

    private fun uiExtras(uiVersion1: Boolean): Bundle =
        if (uiVersion1) UiVersions.newStylesBuilder().addStyle(InlineSuggestionUi.newStyleBuilder().build()).build() else Bundle()

    private fun suggestion(pinned: Boolean): InlineSuggestion {
        val spec = InlinePresentationSpec.Builder(Size(10, 10), Size(500, 100)).build()
        val info = ReflectionHelpers.callStaticMethod<InlineSuggestionInfo>(
            InlineSuggestionInfo::class.java, "newInlineSuggestionInfo",
            ClassParameter.from(InlinePresentationSpec::class.java, spec),
            ClassParameter.from(String::class.java, InlineSuggestionInfo.SOURCE_AUTOFILL),
            ClassParameter.from(Array<String>::class.java, arrayOf("password")),
            ClassParameter.from(String::class.java, InlineSuggestionInfo.TYPE_SUGGESTION),
            ClassParameter.from(Boolean::class.javaPrimitiveType, pinned)
        )
        return ReflectionHelpers.callStaticMethod(
            InlineSuggestion::class.java, "newInlineSuggestion", ClassParameter.from(InlineSuggestionInfo::class.java, info)
        )
    }

    private fun response(vararg suggestions: InlineSuggestion): InlineSuggestionsResponse =
        ReflectionHelpers.callConstructor(
            InlineSuggestionsResponse::class.java, ClassParameter.from(List::class.java, suggestions.toList())
        )

    private fun configure(
        actions: Boolean,
        suggestions: Boolean,
        arrangement: KeyboardBarArrangement = KeyboardBarArrangement.SUGGESTIONS_FIRST
    ) {
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setStatusBarVariationsVisible(context, actions)
        SettingsManager.setSuggestionsEnabled(context, suggestions)
        SettingsManager.setModifierIndicators(context, emptySet())
        KeyboardBarConfiguration.setArrangement(context, arrangement)
    }

    private fun inputSurface(): Pair<CandidatesBarController, ViewGroup> {
        val controller = CandidatesBarController(context)
        val view = controller.getInputView()
        controller.setCandidatesSurfaceActive(false)
        controller.updateStatusBars(snapshot(), "", null, null)
        return controller to view
    }

    private fun chip() = View(context).apply { layoutParams = ViewGroup.LayoutParams(160, 30) }

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun snapshot() = StatusBarController.StatusSnapshot(
        capsLockEnabled = false,
        shiftPhysicallyPressed = false,
        shiftOneShot = false,
        ctrlLatchActive = false,
        ctrlPhysicallyPressed = false,
        ctrlOneShot = false,
        ctrlLatchFromNavMode = false,
        altLatchActive = false,
        altPhysicallyPressed = false,
        altOneShot = false,
        symPage = 0,
        suggestions = listOf("hello", "help", "held")
    )

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun placementOf(chip: View, root: View): Placement {
        var view: View = chip
        while (view !== root) {
            if (view.visibility != View.VISIBLE) return Placement.NONE
            view = view.parent as? View ?: return Placement.NONE
        }
        if (root.visibility != View.VISIBLE) return Placement.NONE
        return if (chip.hasAncestor<CursorSwipeLayout>()) Placement.SUGGESTIONS else Placement.ACTIONS
    }

    private fun View.isInside(root: View): Boolean {
        var view: View? = this
        while (view != null) {
            if (view === root) return true
            view = view.parent as? View
        }
        return false
    }

    private inline fun <reified T> View.hasAncestor(): Boolean {
        var view = parent
        while (view != null) {
            if (view is T) return true
            view = view.parent
        }
        return false
    }

    private fun View.leftIn(root: View): Int {
        var left = 0
        var view: View = this
        while (view !== root) {
            left += view.left - ((view.parent as? View)?.scrollX ?: 0)
            view = view.parent as View
        }
        return left
    }

    private companion object {
        const val APP = "com.example.login"
    }
}
