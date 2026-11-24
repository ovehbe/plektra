package rocks.pkb.plektra.inputmethod.autofill

import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
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
import rocks.pkb.plektra.inputmethod.CandidatesBarController
import rocks.pkb.plektra.inputmethod.ImeChromeLayout
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import rocks.pkb.plektra.inputmethod.StatusBarController
import rocks.pkb.plektra.inputmethod.ui.CursorSwipeLayout

/** The full service cannot be created on Robolectric's API 30 (dynamic-receiver permission shim). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineAutofillRowHeightTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var service: ServiceController<PhysicalKeyboardInputMethodService>? = null

    @After
    fun tearDown() {
        service?.destroy()
    }

    private enum class Host(val baseDp: Float) { ACTIONS(55f), SUGGESTIONS(36f) }

    private data class Row(
        val name: String,
        val host: Host,
        val actions: Boolean = true,
        val suggestions: Boolean = true,
        val arrangement: KeyboardBarArrangement = KeyboardBarArrangement.SUGGESTIONS_FIRST,
        val ledStrip: Boolean = false,
        val alwaysShow: Boolean = false,
        val variationsScale: Float = 1f,
        val suggestionsScale: Float = 1f
    ) {
        val hostScale get() = if (host == Host.ACTIONS) variationsScale else suggestionsScale
    }

    private val rows = listOf(
        Row("actions and suggestions", Host.ACTIONS),
        Row("actions first", Host.ACTIONS, arrangement = KeyboardBarArrangement.ACTIONS_FIRST),
        Row("actions only", Host.ACTIONS, suggestions = false),
        Row("actions with Always show", Host.ACTIONS, alwaysShow = true),
        Row("tall actions row", Host.ACTIONS, variationsScale = 1.4f),
        Row("shared row", Host.SUGGESTIONS, arrangement = KeyboardBarArrangement.SHARED),
        Row("suggestions only", Host.SUGGESTIONS, actions = false),
        Row("short suggestions row", Host.SUGGESTIONS, actions = false, suggestionsScale = 0.8f),
        Row("LED strip only", Host.SUGGESTIONS, actions = false, suggestions = false, ledStrip = true)
    )

    /** Setting value to the scale applied: min, default, between, max, and out-of-range values clamped. */
    private val scales = listOf(
        InlineAutofillRowHeight.MIN_SCALE to 1f,
        InlineAutofillRowHeight.DEFAULT_SCALE to 1f,
        1.75f to 1.75f,
        InlineAutofillRowHeight.MAX_SCALE to 2.5f,
        0.5f to 1f,
        4f to 2.5f
    )

    private data class Observed(val withChipsPx: Int, val withoutChipsPx: Int, val rowPx: Int)

    @Test
    fun chipRowFollowsTheSettingOnEveryRowWithAndWithoutTheAnimation() {
        val failures = mutableListOf<String>()
        val observed = linkedMapOf<Triple<String, Int, Boolean>, Observed>()
        fun expect(label: String, what: String, expected: Any?, actual: Any?) {
            if (expected != actual) failures += "$label: $what expected $expected but was $actual"
        }
        for (row in rows) for (animate in listOf(false, true)) {
            configure(row, animate)
            val (ime, root) = startService()
            val chrome = chrome(ime)
            scales.forEachIndexed { index, (setting, applied) ->
                val label = "${row.name} scale=$setting animate=$animate"
                setTheme(row, setting)
                focus(ime)
                val withoutChips = settle(root, chrome)
                val rowsWithoutChips = rowHeights(chrome)

                val request = ime.onCreateInlineSuggestionsRequest(uiExtras())
                val rowPx = dp(row.host.baseDp * row.hostScale * applied)
                val chipPx = dp(row.host.baseDp * row.hostScale * applied - 8f)
                val spec = request?.inlinePresentationSpecs?.single()
                expect(label, "requested chip height", chipPx, spec?.maxSize?.height)
                expect(label, "requested min chip height", chipPx, spec?.minSize?.height)

                val idle = animatorOf(chrome)
                val chips = deliverChips(ime, chipPx)
                val firstFrame = visibleHeight(root, chrome)
                val animating = animatorOf(chrome).let { it != null && it !== idle }
                val withChips = settle(root, chrome)
                expect(label, "chips shown", true, chips.all { it.isShownWithin(root) })
                expect(label, "chip row", row.host, if (chips[0].hasAncestor<CursorSwipeLayout>()) Host.SUGGESTIONS else Host.ACTIONS)
                val host = hostOf(chips[0])
                expect(label, "row height", rowPx, (host.parent as View).height)
                expect(label, "chip host height", rowPx, host.height)
                expect(label, "chip height", chipPx, chips[0].height)
                val chipRow = rowOf(host, chrome)
                if (row.host == Host.SUGGESTIONS) {
                    val variations = ReflectionHelpers.getField<rocks.pkb.plektra.inputmethod.ui.VariationBarView?>(
                        ReflectionHelpers.getField<StatusBarController>(
                            ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController"), "inputStatusBar"
                        ), "variationBarView")
                    expect(label, "variations row hosts chips", false, variations?.hostsInlineContent)
                }
                rowHeights(chrome).forEach { (other, height) ->
                    val before = rowsWithoutChips[other]
                    if (other !== chipRow && before != null && before != height) {
                        failures += "$label: ${other.javaClass.simpleName} row changed from $before to $height"
                    }
                }
                val chipCenter = chips[0].topIn(host) + chips[0].height / 2f
                if (kotlin.math.abs(chipCenter - host.height / 2f) > 1f) {
                    failures += "$label: chip centered at $chipCenter in a ${host.height}px row"
                }
                val scroller = chips.last().parent.parent as HorizontalScrollView
                expect(label, "scroller height", rowPx, scroller.height)
                scroller.scrollTo(10_000, 0)
                if (scroller.scrollX <= 0) failures += "$label: chips do not scroll"
                val grows = withChips != withoutChips
                // A bar that was off screen appears at its height; only row changes on screen animate.
                val onScreen = withoutChips > 0
                if (onScreen) {
                    expect(label, "animated in", animate && grows, animating)
                    expect(label, "first frame shows", if (animate) withoutChips else withChips, firstFrame)
                }
                expect(label, "settled unclipped", null, chrome.clipBounds)

                val shown = animatorOf(chrome)
                clearChips(ime)
                val clearFirstFrame = visibleHeight(root, chrome)
                val clearAnimating = animatorOf(chrome).let { it != null && it !== shown }
                expect(label, "height after chips", withoutChips, settle(root, chrome))
                if (onScreen) {
                    expect(label, "animated out", animate && grows, clearAnimating)
                    expect(label, "first frame after chips", if (animate) withChips else withoutChips, clearFirstFrame)
                }
                expect(label, "settled unclipped after chips", null, chrome.clipBounds)

                if (animate && grows) {
                    deliverChips(ime, chipPx)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
                    clearChips(ime)
                    expect(label, "height after chips cleared mid-reveal", withoutChips, settle(root, chrome))
                    expect(label, "unclipped after chips cleared mid-reveal", null, chrome.clipBounds)
                }
                observed[Triple(row.name, index, animate)] = Observed(withChips, withoutChips, rowPx)
            }
            service?.destroy()
            service = null
        }

        observed.forEach { (key, state) ->
            val (name, index, animate) = key
            val label = "$name scale=${scales[index].first} animate=$animate"
            val default = observed.getValue(Triple(name, DEFAULT_INDEX, animate))
            expect(label, "bar without chips as at the default", default.withoutChipsPx, state.withoutChipsPx)
            expect(label, "growth over the default", state.rowPx - default.rowPx, state.withChipsPx - default.withChipsPx)
            if (!animate) expect(label, "as with the animation on", observed.getValue(Triple(name, index, true)), state)
        }
        println("Autofill row height matrix: ${observed.size} scenarios, ${failures.size} failures")
        assertTrue(failures.joinToString("\n", prefix = "${failures.size} failed expectations:\n"), failures.isEmpty())
        assertEquals(rows.size * scales.size * 2, observed.size)
    }

    @Test
    fun defaultSettingRequestsTheSameChipsAsBefore() {
        for (row in rows) {
            configure(row, animate = false)
            SettingsManager.setKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE,
                SettingsManager.getKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE)
                    .copy(variationsHeightScale = row.variationsScale, suggestionsHeightScale = row.suggestionsScale))
            val (ime, _) = startService()
            focus(ime)
            val request = ime.onCreateInlineSuggestionsRequest(uiExtras())
            assertEquals(row.name, dp(row.host.baseDp * row.hostScale - 8f), request?.inlinePresentationSpecs?.single()?.maxSize?.height)
            service?.destroy()
            service = null
        }
    }

    private fun configure(row: Row, animate: Boolean) {
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setInlineAutofillEnabled(context, true)
        SettingsManager.setStatusBarVariationsVisible(context, row.actions)
        SettingsManager.setSuggestionsEnabled(context, row.suggestions)
        SettingsManager.setModifierIndicators(
            context, if (row.ledStrip) setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP) else emptySet()
        )
        KeyboardBarConfiguration.setArrangement(context, row.arrangement)
        SettingsManager.setAlwaysShowSuggestionsBar(context, row.alwaysShow)
        SettingsManager.setKeyboardBarHeightAnimation(context, animate)
    }

    private fun setTheme(row: Row, autofillScale: Float) {
        SettingsManager.setKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE,
            SettingsManager.defaultKeyboardTheme().copy(
                variationsHeightScale = row.variationsScale,
                suggestionsHeightScale = row.suggestionsScale,
                autofillRowHeightScale = autofillScale
            ))
    }

    private fun startService(): Pair<PhysicalKeyboardInputMethodService, ViewGroup> {
        val controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = controller
        val ime = controller.get()
        ReflectionHelpers.setField(ime, "mInputEditorInfo", editorInfo())
        ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController").setCandidatesSurfaceActive(false)
        return ime to ime.onCreateInputView() as ViewGroup
    }

    private fun focus(ime: PhysicalKeyboardInputMethodService) {
        val info = editorInfo()
        ReflectionHelpers.setField(ime, "mInputEditorInfo", info)
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clearChips(ime: PhysicalKeyboardInputMethodService) {
        val info = editorInfo()
        ime.onStartInput(info, true)
        ime.onStartInputView(info, true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** What Chrome reports for autofill.me's card name input. */
    private fun editorInfo() = EditorInfo().apply {
        packageName = APP
        inputType = 0xc0a1
        fieldId = 1
    }

    /** Wider than the bar together, so the unpinned ones must scroll. */
    private fun deliverChips(ime: PhysicalKeyboardInputMethodService, heightPx: Int): List<View> {
        val chips = ReflectionHelpers.getField<InlineAutofillChips>(ime, "inlineAutofillChips")
        val views = List(6) { View(context).apply { layoutParams = ViewGroup.LayoutParams(400, heightPx) } }
        assertTrue("chips need a host", chips.onResponse(views.mapIndexed { index, view ->
            PendingInlineChip(pinned = index == 0) { callback -> callback(view) }
        }))
        shadowOf(Looper.getMainLooper()).idle()
        return views
    }

    private fun chrome(ime: PhysicalKeyboardInputMethodService): ImeChromeLayout =
        ReflectionHelpers.getField(
            ReflectionHelpers.getField<StatusBarController>(
                ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController"), "inputStatusBar"
            ), "statusBarLayout"
        )

    private fun animatorOf(chrome: ImeChromeLayout): Any? = ReflectionHelpers.getField(chrome, "heightAnimator")

    private fun measure(root: ViewGroup): Int {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return if (root.visibility == View.VISIBLE) root.measuredHeight else 0
    }

    private fun visibleHeight(root: ViewGroup, chrome: ImeChromeLayout): Int =
        measure(root) - (chrome.clipBounds?.top ?: 0)

    private fun settle(root: ViewGroup, chrome: ImeChromeLayout): Int {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        return visibleHeight(root, chrome)
    }

    private fun uiExtras(): Bundle =
        UiVersions.newStylesBuilder().addStyle(InlineSuggestionUi.newStyleBuilder().build()).build()

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun rowHeights(chrome: ViewGroup): Map<View, Int> =
        (0 until chrome.childCount).map { chrome.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE && it.height > 0 }
            .associateWith { it.height }

    private fun rowOf(view: View, root: ViewGroup): View {
        var row = view
        while (row.parent !== root) row = row.parent as View
        return row
    }

    private fun hostOf(chip: View): InlineAutofillChipHost {
        var view = chip.parent
        while (view !is InlineAutofillChipHost) view = view.parent
        return view
    }

    private fun View.topIn(ancestor: View): Int {
        var top = 0
        var view: View = this
        while (view !== ancestor) {
            top += view.top
            view = view.parent as View
        }
        return top
    }

    private fun View.isShownWithin(root: View): Boolean {
        var view: View = this
        while (view !== root) {
            if (view.visibility != View.VISIBLE) return false
            view = view.parent as? View ?: return false
        }
        return root.visibility == View.VISIBLE
    }

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
        const val DEFAULT_INDEX = 1
    }
}
