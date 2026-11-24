package rocks.pkb.plektra.inputmethod.autofill

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.HorizontalScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.KeyboardBarArrangement
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.CandidatesBarController
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import rocks.pkb.plektra.inputmethod.StatusBarController
import rocks.pkb.plektra.inputmethod.ui.CursorSwipeLayout
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InlineAutofillChipHostTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private enum class Placement { ACTIONS, SUGGESTIONS, NONE }

    private data class Row(
        val name: String,
        val actions: Boolean,
        val suggestions: Boolean,
        val arrangement: KeyboardBarArrangement = KeyboardBarArrangement.SUGGESTIONS_FIRST,
        val ledStrip: Boolean = false,
        val passwordField: Boolean = false,
        val blocked: Boolean = false,
        val navMode: Boolean = false,
        val symPage: Int = 0,
        val expected: Placement
    )

    private val rows = listOf(
        Row("actions and suggestions", actions = true, suggestions = true, expected = Placement.ACTIONS),
        Row("actions only", actions = true, suggestions = false, expected = Placement.ACTIONS),
        Row("actions first", actions = true, suggestions = true,
            arrangement = KeyboardBarArrangement.ACTIONS_FIRST, expected = Placement.ACTIONS),
        Row("shared row", actions = true, suggestions = true,
            arrangement = KeyboardBarArrangement.SHARED, expected = Placement.SUGGESTIONS),
        Row("suggestions only", actions = false, suggestions = true, expected = Placement.SUGGESTIONS),
        Row("suggestions only, password field", actions = false, suggestions = true,
            passwordField = true, expected = Placement.SUGGESTIONS),
        Row("LED strip only, password field", actions = false, suggestions = false, ledStrip = true,
            passwordField = true, expected = Placement.SUGGESTIONS),
        Row("blocked surface", actions = true, suggestions = true, blocked = true, expected = Placement.NONE),
        Row("no bar content", actions = false, suggestions = false, expected = Placement.NONE),
        Row("nav mode", actions = true, suggestions = true, navMode = true, expected = Placement.NONE),
        Row("hardware SYM page", actions = true, suggestions = true, symPage = 1, expected = Placement.NONE)
    )

    @Test
    fun placementFollowsBarConfiguration_onBothSurfaces() {
        for (candidatesSurface in listOf(false, true)) {
            rows.forEach { row ->
                val label = "${row.name} candidatesSurface=$candidatesSurface"
                configure(row.actions, row.suggestions, row.arrangement, row.ledStrip)
                val controller = CandidatesBarController(context)
                controller.isSurfaceBlocked = { row.blocked }
                val inputView = controller.getInputView()
                val candidatesView = controller.getCandidatesView()
                controller.setCandidatesSurfaceActive(candidatesSurface)
                controller.updateStatusBars(snapshot(row), "", null, null)
                val chips = listOf(chip(), chip())

                val shown = controller.showInlineAutofill(chips, listOf(false, false))

                val (active, inactive) = if (candidatesSurface) candidatesView to inputView else inputView to candidatesView
                layout(active)
                assertEquals(label, row.expected != Placement.NONE, shown)
                assertEquals(label, row.expected, placementOf(chips.first(), active))
                assertFalse(label, chips.any { it.isInside(inactive) })
                if (shown) {
                    assertTrue(label, chips.all { it.width > 0 && it.height > 0 })
                    assertTrue(label, visibleTexts(active).intersect(WORDS.toSet()).isEmpty())
                } else if (row.blocked) {
                    assertTrue(label, chips.all { it.parent == null })
                }
                if (row.navMode || row.symPage != 0) {
                    controller.updateStatusBars(snapshot(row.copy(navMode = false, symPage = 0)), "", null, null)
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    layout(active)
                    assertEquals("$label, after it closes", Placement.ACTIONS, placementOf(chips.first(), active))
                }
            }
        }
    }

    @Test
    fun chipsFollowRowReordersWithoutAFixedChildIndex() {
        configure(actions = true, suggestions = true, arrangement = KeyboardBarArrangement.SUGGESTIONS_FIRST)
        val (controller, view) = inputSurface()
        val chip = chip()
        assertTrue(controller.showInlineAutofill(listOf(chip), listOf(false)))
        val actionsRowIndex = view.indexOfChild(rowOf(chip, view))

        KeyboardBarConfiguration.setArrangement(context, KeyboardBarArrangement.ACTIONS_FIRST)
        controller.updateStatusBars(snapshot(), "", null, null)
        layout(view)
        assertEquals(Placement.ACTIONS, placementOf(chip, view))
        assertNotEquals(actionsRowIndex, view.indexOfChild(rowOf(chip, view)))

        KeyboardBarConfiguration.setArrangement(context, KeyboardBarArrangement.SHARED)
        controller.updateStatusBars(snapshot(), "", null, null)
        layout(view)
        assertEquals(Placement.SUGGESTIONS, placementOf(chip, view))

        KeyboardBarConfiguration.setArrangement(context, KeyboardBarArrangement.SUGGESTIONS_FIRST)
        controller.updateStatusBars(snapshot(), "", null, null)
        layout(view)
        assertEquals(Placement.ACTIONS, placementOf(chip, view))
        assertEquals(actionsRowIndex, view.indexOfChild(rowOf(chip, view)))
    }

    @Test
    fun pinnedChipsStayAtTheStartAndOnlyTheOthersScroll() {
        configure(actions = false, suggestions = true)
        val (controller, view) = inputSurface()
        val chips = List(4) { chip() }

        assertTrue(controller.showInlineAutofill(chips, listOf(false, true, false, true)))
        layout(view)

        assertTrue(chips.all { it.width == 160 })
        val leftToRight = chips.sortedBy { it.leftIn(view) }
        assertEquals(listOf(chips[1], chips[3], chips[0], chips[2]), leftToRight)
        assertFalse(chips[1].hasAncestor<HorizontalScrollView>())
        assertFalse(chips[3].hasAncestor<HorizontalScrollView>())
        assertTrue(chips[0].hasAncestor<HorizontalScrollView>())
        assertTrue(chips[2].hasAncestor<HorizontalScrollView>())
    }

    @Test
    fun aNewBatchStartsScrolledToTheStart() {
        configure(actions = false, suggestions = true)
        val (controller, view) = inputSurface()
        val wide = { View(context).apply { layoutParams = ViewGroup.LayoutParams(600, 30) } }
        val first = List(4) { wide() }
        assertTrue(controller.showInlineAutofill(first, List(4) { false }))
        layout(view)
        val scroller = first[0].parent.parent as HorizontalScrollView
        scroller.scrollTo(900, 0)
        assertEquals(900, scroller.scrollX)

        val next = List(4) { wide() }
        assertTrue(controller.showInlineAutofill(next, List(4) { false }))
        layout(view)

        assertEquals(0, scroller.scrollX)
    }

    @Test
    fun ledStripOnlyBarGrowsAChipRowAndShrinksBackWhenChipsClear() {
        configure(actions = false, suggestions = false, ledStrip = true)
        val (controller, view) = inputSurface()
        controller.updateStatusBars(snapshot().copy(shouldDisableSuggestions = true), "", null, null)
        layout(view)
        val ledOnlyHeight = view.measuredHeight
        assertTrue(ledOnlyHeight > 0)

        repeat(2) { cycle ->
            val chips = listOf(chip(), chip())
            assertTrue(controller.showInlineAutofill(chips, listOf(false, false)))
            layout(view)
            assertEquals("cycle $cycle", Placement.SUGGESTIONS, placementOf(chips.first(), view))
            assertTrue("cycle $cycle", view.measuredHeight > ledOnlyHeight)

            controller.clearInlineAutofill()
            layout(view)
            assertTrue("cycle $cycle", chips.all { it.parent == null })
            assertEquals("cycle $cycle", ledOnlyHeight, view.measuredHeight)
        }
    }

    @Test
    fun clearingResumesWordSuggestions() {
        configure(actions = false, suggestions = true)
        val (controller, view) = inputSurface()
        assertTrue(visibleTexts(view).containsAll(WORDS))
        val chip = chip()

        assertTrue(controller.showInlineAutofill(listOf(chip), listOf(false)))
        assertTrue(visibleTexts(view).intersect(WORDS.toSet()).isEmpty())

        controller.clearInlineAutofill()
        assertNull(chip.parent)
        assertTrue(visibleTexts(view).containsAll(WORDS))
    }

    @Test
    fun emptyChipListIsRejected() {
        configure(actions = true, suggestions = true)
        val (controller, _) = inputSurface()
        assertFalse(controller.showInlineAutofill(emptyList(), emptyList()))
    }

    @Test
    fun chipsBeforeTheFirstRenderShowWithIt() {
        configure(actions = true, suggestions = true)
        val controller = CandidatesBarController(context)
        val view = controller.getInputView()
        controller.setCandidatesSurfaceActive(false)
        val chip = chip()
        assertFalse(controller.showInlineAutofill(listOf(chip), listOf(false)))
        assertEquals(Placement.NONE, placementOf(chip, view))

        controller.updateStatusBars(snapshot(), "", null, null)
        layout(view)

        assertEquals(Placement.ACTIONS, placementOf(chip, view))
    }

    @Test
    fun surfaceSwapClearsChipsAndTheNextShowUsesTheNewSurface() {
        configure(actions = true, suggestions = true)
        val controller = CandidatesBarController(context)
        val inputView = controller.getInputView()
        val candidatesView = controller.getCandidatesView()
        controller.setCandidatesSurfaceActive(true)
        controller.updateStatusBars(snapshot(), "", null, null)
        val chip = chip()
        assertTrue(controller.showInlineAutofill(listOf(chip), listOf(false)))
        assertTrue(chip.isInside(candidatesView))

        controller.setCandidatesSurfaceActive(false)
        assertNull(chip.parent)

        controller.updateStatusBars(snapshot(), "", null, null)
        assertTrue(controller.showInlineAutofill(listOf(chip), listOf(false)))
        assertTrue(chip.isInside(inputView))
        assertFalse(chip.isInside(candidatesView))
    }

    @Test
    fun blockingTheSurfaceClearsShownChips() {
        configure(actions = true, suggestions = true)
        val (controller, _) = inputSurface()
        var blocked = false
        controller.isSurfaceBlocked = { blocked }
        val chip = chip()
        assertTrue(controller.showInlineAutofill(listOf(chip), listOf(false)))

        blocked = true
        controller.updateStatusBars(snapshot(), "", null, null)

        assertNull(chip.parent)
        assertFalse(controller.showInlineAutofill(listOf(chip), listOf(false)))
    }

    @Test
    fun chipsReplacePendingSuggestionAnnouncements() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        SettingsManager.setAccessibilityLiveAnnouncementsEnabled(context, true)
        configure(actions = false, suggestions = true)
        val controller = CandidatesBarController(activity)
        controller.setCandidatesSurfaceActive(false)
        activity.setContentView(controller.getInputView())

        controller.updateStatusBars(snapshot(), "", null, null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(announcements(activity).any { WORDS.first() in it })

        val pending = listOf("world", "word", "worm")
        controller.updateStatusBars(snapshot().copy(suggestions = pending), "", null, null)
        val chip = chip()
        assertTrue(controller.showInlineAutofill(listOf(chip), listOf(false)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertTrue(announcements(activity).none { text -> pending.any { it in text } })
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, hostOf(chip).accessibilityLiveRegion)
    }

    @Test
    fun chipsInActionsRowStayReadableWhileTheCoveredButtonsAreHidden() {
        SettingsManager.setAccessibilityReadSecondRowEnabled(context, false)
        configure(actions = true, suggestions = false)
        val (controller, view) = inputSurface()
        val chip = chip()
        assertTrue(controller.showInlineAutofill(listOf(chip), listOf(false)))
        val actionsRow = rowOf(chip, view) as ViewGroup
        val host = actionsRow.children().single { chip.isInside(it) }

        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, actionsRow.importantForAccessibility)
        actionsRow.children().filter { it !== host }.forEach {
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, it.importantForAccessibility)
        }

        controller.clearInlineAutofill()
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, actionsRow.importantForAccessibility)
        actionsRow.children().forEach {
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, it.importantForAccessibility)
        }
    }

    /** The full service cannot be created on Robolectric's API 30 (dynamic-receiver permission shim). */
    @Test
    @Config(sdk = [34])
    fun finishingInputClearsChips() {
        configure(actions = true, suggestions = true)
        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        try {
            val controller = ReflectionHelpers.getField<CandidatesBarController>(service.get(), "candidatesBarController")
            controller.getInputView()
            controller.setCandidatesSurfaceActive(false)
            controller.updateStatusBars(snapshot(), "", null, null)
            val chip = chip()
            assertTrue(controller.showInlineAutofill(listOf(chip), listOf(false)))

            service.get().onFinishInput()

            assertNull(chip.parent)
        } finally {
            service.destroy()
        }
    }

    @Test
    fun centerSlotLayoutMatrix() {
        val failures = mutableListOf<String>()
        var count = 0
        for (arrangement in KeyboardBarArrangement.entries) for (candidates in listOf(false, true)) {
            for (password in listOf(false, true)) for (actions in listOf(false, true)) {
                configure(actions, suggestions = true, arrangement, ledStrip = true)
                SettingsManager.setExperimentalSuggestionsEnabled(context, true)
                SettingsManager.setAlwaysShowSuggestionsBar(context, true)
                val controller = CandidatesBarController(context)
                val root = if (candidates) controller.getCandidatesView() else controller.getInputView()
                controller.setCandidatesSurfaceActive(candidates)
                fun render() { controller.updateStatusBars(snapshot(), "", null, null); layout(root) }
                render()
                val fill = chip()
                if (password) check(controller.showInlineAutofill(listOf(fill), listOf(true)))
                layout(root)
                val before = visibleTexts(root).filter(String::isNotEmpty)
                val height = root.height
                val children = root.childCount
                val fillPlacement = placementOf(fill, root)
                controller.setCenterSuggestion("012345") { }
                render()
                count++
                runCatching {
                    check(root.childCount == children && root.height == height) { "added OTP row" }
                    val after = visibleTexts(root)
                    check(after.containsAll(before - WORDS.first())) { "covered other bar content" }
                    check(!password || placementOf(fill, root) == fillPlacement && fill.width > 0) { "covered autofill" }
                    // If the password provider owns this very strip, never evict it to show local text.
                    if (fillPlacement != Placement.SUGGESTIONS) {
                        val title = root.findLabel("012345") ?: error("missing code")
                        val slots = title.parent as ViewGroup
                        check(slots.childCount == 3 && slots.getChildAt(1) === title) { "not middle word slot" }
                        val neighbor = slots.getChildAt(0) as TextView
                        check(title.height == neighbor.height && title.autoSizeMaxTextSize == neighbor.autoSizeMaxTextSize)
                        if (!password) {
                            check(neighbor.text == WORDS[2] &&
                                (slots.getChildAt(2) as TextView).text == WORDS[1]) { "lost word neighbors" }
                        }
                    }
                    controller.setCenterSuggestion(null) { }
                    render()
                    check(visibleTexts(root).filter(String::isNotEmpty) == before && root.height == height) { "not restored" }
                }.onFailure { failures += "$arrangement/candidates=$candidates/password=$password/actions=$actions: $it" }
            }
        }
        println("Center slot layout matrix: $count rows, ${failures.size} failing")
        assertEquals(24, count)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun View.findLabel(label: String): TextView? {
        if (visibility != View.VISIBLE) return null
        if (this is TextView && text == label) return this
        return (this as? ViewGroup)?.let { (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findLabel(label) } }
    }

    private fun configure(
        actions: Boolean,
        suggestions: Boolean,
        arrangement: KeyboardBarArrangement = KeyboardBarArrangement.SUGGESTIONS_FIRST,
        ledStrip: Boolean = false
    ) {
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setStatusBarVariationsVisible(context, actions)
        SettingsManager.setSuggestionsEnabled(context, suggestions)
        SettingsManager.setModifierIndicators(
            context, if (ledStrip) setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP) else emptySet()
        )
        KeyboardBarConfiguration.setArrangement(context, arrangement)
    }

    private fun inputSurface(): Pair<CandidatesBarController, ViewGroup> {
        val controller = CandidatesBarController(context)
        val view = controller.getInputView()
        controller.setCandidatesSurfaceActive(false)
        controller.updateStatusBars(snapshot(), "", null, null)
        return controller to view
    }

    /** Stands in for InlineContentView, which inflates with the size the service rendered. */
    private fun chip() = View(context).apply { layoutParams = ViewGroup.LayoutParams(160, 30) }

    private fun snapshot(row: Row? = null) = StatusBarController.StatusSnapshot(
        capsLockEnabled = false,
        shiftPhysicallyPressed = false,
        shiftOneShot = false,
        ctrlLatchActive = row?.navMode == true,
        ctrlPhysicallyPressed = false,
        ctrlOneShot = false,
        ctrlLatchFromNavMode = row?.navMode == true,
        altLatchActive = false,
        altPhysicallyPressed = false,
        altOneShot = false,
        symPage = row?.symPage ?: 0,
        suggestions = WORDS,
        shouldDisableSuggestions = row?.passwordField == true
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

    private fun rowOf(chip: View, root: ViewGroup): View {
        var view: View = chip
        while (view.parent !== root) view = view.parent as View
        return view
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

    private fun ViewGroup.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    private fun hostOf(chip: View): InlineAutofillChipHost {
        var view = chip.parent
        while (view !is InlineAutofillChipHost) view = view.parent
        return view
    }

    private fun visibleTexts(root: View): List<String> {
        layout(root)
        fun collect(view: View): List<String> {
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return emptyList()
            return when (view) {
                is TextView -> listOf(view.text.toString())
                is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
                else -> emptyList()
            }
        }
        return collect(root)
    }

    private fun announcements(activity: Activity): List<String> =
        shadowOf(activity.getSystemService(AccessibilityManager::class.java)).sentAccessibilityEvents
            .filter { it.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT }
            .map { event -> event.text.joinToString(" ") }

    private companion object {
        val WORDS = listOf("hello", "help", "held")
    }
}
