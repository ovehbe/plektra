package rocks.pkb.plektra.inputmethod

import android.os.Bundle
import android.os.Looper
import android.text.Layout
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.widget.TextViewCompat
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SettingsManager.KeyboardThemeTarget
import rocks.pkb.plektra.core.SymLayoutController
import rocks.pkb.plektra.data.emoji.TestEmojiFont
import rocks.pkb.plektra.inputmethod.autofill.InlineAutofillChipHost
import rocks.pkb.plektra.inputmethod.autofill.InlineAutofillChips
import rocks.pkb.plektra.inputmethod.autofill.PendingInlineChip
import rocks.pkb.plektra.inputmethod.suggestions.ui.FullSuggestionsBar
import rocks.pkb.plektra.inputmethod.ui.CursorSwipeLayout
import rocks.pkb.plektra.inputmethod.ui.VariationBarView

/**
 * The SYM page follows only its own size, never the autofill row height, on a Q25-sized screen
 * (720x720 px). The full service cannot be created on Robolectric's API 30.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h360dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SymPageSizeMatrixTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var service: ServiceController<PhysicalKeyboardInputMethodService>? = null

    @After
    fun tearDown() {
        service?.destroy()
    }

    private enum class Sym { CLOSED, FIRST_PAGE, EMOJI }

    /** Setting value to the scale applied. The autofill minimum is also its default. */
    private val autofillScales = listOf("min" to 1f, "default" to 1f, "1.75" to 1.75f, "max" to 2.5f)
    private val symSizes = listOf(0.75f, 1f, 1.75f)

    private data class Scenario(
        val software: Boolean,
        val animate: Boolean,
        val sym: Sym,
        val autofill: Pair<String, Float>,
        val symSize: Float,
        val chips: Boolean
    ) {
        override fun toString() = "${if (software) "software" else "hardware"} animate=$animate sym=$sym " +
            "autofill=${autofill.first} symSize=$symSize chips=$chips"
    }

    private data class Observed(
        val barPx: Int,
        val symPx: Int,
        val cellPx: Pair<Int, Int>?,
        val glyphPx: Float?,
        val hintPx: Float?,
        val chipRowPx: Int?
    )

    @Test
    fun symPageIgnoresTheAutofillRowHeightAndFollowsItsOwnSize() {
        val failures = mutableListOf<String>()
        val observed = linkedMapOf<Scenario, Observed>()
        fun expect(label: Any, what: String, expected: Any?, actual: Any?) {
            if (expected != actual) failures += "$label: $what expected $expected but was $actual"
        }
        for (software in listOf(false, true)) for (animate in listOf(false, true)) {
            configure(software, animate)
            val target = if (software) KeyboardThemeTarget.SOFTWARE else KeyboardThemeTarget.HARDWARE
            val base = SettingsManager.getKeyboardTheme(context, target)
            val (ime, root) = startService()
            for (sym in Sym.values()) for (autofill in autofillScales) for (symSize in symSizes) for (chips in listOf(false, true)) {
                val scenario = Scenario(software, animate, sym, autofill, symSize, chips)
                SettingsManager.setKeyboardTheme(context, target,
                    base.copy(autofillRowHeightScale = autofill.second, symPageScale = symSize))
                closeSym(ime)
                focus(ime)
                val chipViews = if (chips) deliverChips(ime) else emptyList()
                when (sym) {
                    Sym.CLOSED -> Unit
                    Sym.FIRST_PAGE -> openSym(ime) { it.toggleSymPage() }
                    Sym.EMOJI -> openSym(ime) { it.openEmojiPage() }
                }
                val barPx = settle(root)
                val chrome = chrome(ime)
                expect(scenario, "settled unclipped", null, chrome.clipBounds)
                // The software keyboard's height is its own setting.
                if (!software && barPx > SCREEN_PX) failures += "$scenario: bar $barPx px is taller than the $SCREEN_PX px screen"

                val shownChip = chipViews.firstOrNull()?.takeIf { it.isShownWithin(root) }
                val chipRowPx = shownChip?.let { chip ->
                    val host = hostOf(chip)
                    val hostDp = if (chip.hasAncestor<CursorSwipeLayout>()) {
                        FullSuggestionsBar.rowHeightDp(base.suggestionsHeightScale)
                    } else {
                        VariationBarView.rowHeightDp(base.variationsHeightScale)
                    }
                    expect(scenario, "chip row height", dp(hostDp * autofill.second), host.height)
                    host.height
                }
                if (chips && sym == Sym.CLOSED && shownChip == null) failures += "$scenario: chips not shown"

                val surface = symSurface(ime)
                val symPx = if (sym != Sym.CLOSED && surface.isShownWithin(root)) surface.height else 0
                if (sym != Sym.CLOSED && symPx == 0) failures += "$scenario: SYM page not shown"
                val grid = if (!software && sym != Sym.CLOSED) cells(surface) else emptyList()
                var cellPx: Pair<Int, Int>? = null
                var glyphPx: Float? = null
                var hintPx: Float? = null
                if (!software && sym != Sym.CLOSED) {
                    if (grid.size < 28) failures += "$scenario: only ${grid.size} SYM keys"
                    val sizes = grid.map { it.cell.width to it.cell.height }.distinct()
                    expect(scenario, "distinct key sizes", 1, sizes.size)
                    cellPx = sizes.firstOrNull()
                    val keyPx = expectedKeyPx(symSize)
                    expect(scenario, "key height", keyPx, cellPx?.second)
                    expect(scenario, "SYM page height", 3 * keyPx + 2 * dp(4f), symPx)
                    val glyphs = grid.filterNot { it.autoSized }.map { it.content.textSize }.distinct()
                    expect(scenario, "distinct glyph sizes", 1, glyphs.size)
                    glyphPx = glyphs.firstOrNull()
                    val hints = grid.map { it.hint.textSize }.distinct()
                    expect(scenario, "distinct hint sizes", 1, hints.size)
                    hintPx = hints.firstOrNull()
                    if (symSize == 1f) expect(scenario, "hint size at the default SYM size", sp(12f), hintPx)
                    grid.forEach { key ->
                        val text = key.content.text
                        if (text.isNotEmpty()) {
                            val width = Layout.getDesiredWidth(text, key.content.paint)
                            if (width > key.cell.width + 0.5f) {
                                failures += "$scenario: '${text}' is ${width.toInt()} px wide in a ${key.cell.width} px key"
                            }
                        }
                        val metrics = key.content.paint.fontMetricsInt
                        if (metrics.descent - metrics.ascent > key.cell.height) {
                            failures += "$scenario: '${text}' is ${metrics.descent - metrics.ascent} px tall in a ${key.cell.height} px key"
                        }
                        val hintWidth = Layout.getDesiredWidth(key.hint.text, key.hint.paint)
                        if (hintWidth > key.cell.width / 2f) {
                            failures += "$scenario: hint '${key.hint.text}' is ${hintWidth.toInt()} px wide in a ${key.cell.width} px key"
                        }
                    }
                    val rows = rows(surface)
                    rows.zipWithNext().forEach { (upper, lower) ->
                        if (upper.bottom > lower.top) failures += "$scenario: SYM rows overlap"
                    }
                    rows.forEach { row ->
                        if (row.right > surface.width || row.bottom > surface.height) {
                            failures += "$scenario: SYM row ${row.left},${row.top}-${row.right},${row.bottom} clipped by ${surface.width}x${surface.height}"
                        }
                    }
                }
                observed[scenario] = Observed(barPx, symPx, cellPx, glyphPx, hintPx, chipRowPx)
            }
            closeSym(ime)
            service?.destroy()
            service = null
        }

        observed.forEach { (scenario, state) ->
            val sameWithoutAutofill = observed.getValue(scenario.copy(autofill = autofillScales[1], chips = false))
            expect(scenario, "SYM page as without chips at the default autofill height", sameWithoutAutofill.symPx, state.symPx)
            expect(scenario, "SYM keys as without chips at the default autofill height", sameWithoutAutofill.cellPx, state.cellPx)
            expect(scenario, "SYM glyphs as without chips at the default autofill height", sameWithoutAutofill.glyphPx, state.glyphPx)
            expect(scenario, "SYM hints as without chips at the default autofill height", sameWithoutAutofill.hintPx, state.hintPx)
            if (scenario.sym == Sym.CLOSED || scenario.software) {
                val atDefaultSize = observed.getValue(scenario.copy(symSize = 1f))
                expect(scenario, "bar as at the default SYM size", atDefaultSize.barPx, state.barPx)
                expect(scenario, "SYM page as at the default SYM size", atDefaultSize.symPx, state.symPx)
            }
            if (!scenario.software && scenario.sym != Sym.CLOSED) {
                val smaller = symSizes.indexOf(scenario.symSize).takeIf { it > 0 }?.let { symSizes[it - 1] }
                if (smaller != null) {
                    val previous = observed.getValue(scenario.copy(symSize = smaller))
                    if ((state.glyphPx ?: 0f) < (previous.glyphPx ?: 0f)) {
                        failures += "$scenario: glyphs shrink from ${previous.glyphPx} at SYM size $smaller to ${state.glyphPx}"
                    }
                    if ((state.hintPx ?: 0f) <= (previous.hintPx ?: 0f)) {
                        failures += "$scenario: hints do not grow from ${previous.hintPx} at SYM size $smaller to ${state.hintPx}"
                    }
                }
            }
            val withoutAnimation = observed.getValue(scenario.copy(animate = false))
            expect(scenario, "as with the animation off", withoutAnimation, state)
        }
        println("SYM page size matrix: ${observed.size} scenarios, ${failures.size} failures")
        assertTrue(failures.joinToString("\n", prefix = "${failures.size} failed expectations:\n"), failures.isEmpty())
        assertEquals(2 * 2 * Sym.values().size * autofillScales.size * symSizes.size * 2, observed.size)
    }

    @Test
    fun defaultSymSizeKeepsTheOriginalKeyHeight() {
        assertEquals(dp(56f), SymPageSize.keyHeightPx(dp(56f), SymPageSize.DEFAULT_SCALE, dp(4f), SCREEN_PX))
        assertEquals(dp(56f), SymPageSize.keyHeightPx(dp(56f), SymPageSize.DEFAULT_SCALE, dp(4f), 2400))
        assertEquals((dp(56f) * 1.75f).toInt(), SymPageSize.keyHeightPx(dp(56f), 1.75f, dp(4f), 2400))
    }

    /** The original 56 dp key, scaled, and kept within 60% of the screen above the default. */
    private fun expectedKeyPx(symSize: Float): Int {
        val scaled = (dp(56f) * symSize).toInt()
        if (symSize <= 1f) return scaled
        return minOf(scaled, maxOf(dp(56f), ((SCREEN_PX * 0.6f).toInt() - 2 * dp(4f)) / 3))
    }

    private class Key(val cell: FrameLayout, val content: TextView, val hint: TextView) {
        val autoSized get() = TextViewCompat.getAutoSizeTextType(content) != TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE
    }

    private fun rows(surface: ViewGroup): List<View> =
        (0 until surface.childCount).map { surface.getChildAt(it) }.filter { it is LinearLayout && it.visibility == View.VISIBLE }

    private fun cells(surface: ViewGroup): List<Key> = rows(surface).flatMap { row ->
        (0 until (row as ViewGroup).childCount).mapNotNull { index ->
            val cell = row.getChildAt(index) as? FrameLayout ?: return@mapNotNull null
            val group = cell.getChildAt(0) as? FrameLayout ?: return@mapNotNull null
            val content = group.getChildAt(0) as? TextView ?: return@mapNotNull null
            val hint = (if (group.childCount > 1) group.getChildAt(1) else null) as? TextView ?: return@mapNotNull null
            Key(cell, content, hint)
        }
    }

    private fun configure(software: Boolean, animate: Boolean) {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSoftwareKeyboardMode(context,
            if (software) SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL else SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "q25")
        SettingsManager.setInlineAutofillEnabled(context, true)
        SettingsManager.setAlwaysShowSuggestionsBar(context, true)
        SettingsManager.setKeyboardBarHeightAnimation(context, animate)
        TestEmojiFont.install(context, drawable = true)
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

    private fun symLayout(ime: PhysicalKeyboardInputMethodService): SymLayoutController =
        ReflectionHelpers.getField(ime, "symLayoutController")

    private fun openSym(ime: PhysicalKeyboardInputMethodService, open: (SymLayoutController) -> Unit) {
        open(symLayout(ime))
        ReflectionHelpers.callInstanceMethod<Any>(ime, "refreshStatusBar")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun closeSym(ime: PhysicalKeyboardInputMethodService) {
        if (symLayout(ime).closeSymPage()) {
            ReflectionHelpers.callInstanceMethod<Any>(ime, "refreshStatusBar")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        }
    }

    private fun editorInfo() = EditorInfo().apply {
        packageName = "com.example.login"
        inputType = 0xc0a1
        fieldId = 1
    }

    private fun deliverChips(ime: PhysicalKeyboardInputMethodService): List<View> {
        val chips = ReflectionHelpers.getField<InlineAutofillChips>(ime, "inlineAutofillChips")
        val height = ime.onCreateInlineSuggestionsRequest(uiExtras())?.inlinePresentationSpecs?.single()?.maxSize?.height ?: dp(40f)
        val views = List(3) { View(context).apply { layoutParams = ViewGroup.LayoutParams(300, height) } }
        chips.onResponse(views.mapIndexed { index, view -> PendingInlineChip(pinned = index == 0) { it(view) } })
        shadowOf(Looper.getMainLooper()).idle()
        return views
    }

    private fun uiExtras(): Bundle =
        UiVersions.newStylesBuilder().addStyle(InlineSuggestionUi.newStyleBuilder().build()).build()

    private fun inputBar(ime: PhysicalKeyboardInputMethodService): StatusBarController =
        ReflectionHelpers.getField(ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController"), "inputStatusBar")

    private fun chrome(ime: PhysicalKeyboardInputMethodService): ImeChromeLayout =
        ReflectionHelpers.getField(inputBar(ime), "statusBarLayout")

    private fun symSurface(ime: PhysicalKeyboardInputMethodService): ViewGroup =
        ReflectionHelpers.getField(inputBar(ime), "emojiKeyboardContainer")

    private fun settle(root: ViewGroup): Int {
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(SCREEN_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        return if (root.visibility == View.VISIBLE) root.measuredHeight else 0
    }

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private fun hostOf(chip: View): InlineAutofillChipHost {
        var view = chip.parent
        while (view !is InlineAutofillChipHost) view = view.parent
        return view
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
        const val SCREEN_PX = 720
    }
}
