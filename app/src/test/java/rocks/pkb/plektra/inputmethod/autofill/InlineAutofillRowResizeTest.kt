package rocks.pkb.plektra.inputmethod.autofill

import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.KeyboardThemePreset
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonRegistry
import rocks.pkb.plektra.inputmethod.suggestions.ui.FullSuggestionsBar
import rocks.pkb.plektra.inputmethod.ui.VariationBarView
import rocks.pkb.plektra.keyboardThemePresets
import rocks.pkb.plektra.toKeyboardThemeColors
import rocks.pkb.plektra.toKeyboardThemePreset
import rocks.pkb.plektra.toSettingsTheme

/** The rows resize as soon as they start or stop hosting chips, without waiting for the next bar update. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InlineAutofillRowResizeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val theme = SettingsManager.defaultKeyboardTheme()
        .copy(variationsHeightScale = 1.2f, suggestionsHeightScale = 0.9f, autofillRowHeightScale = 2f)

    @Test
    fun variationsRowTakesTheAutofillHeightOnlyWhileItHostsChips() {
        val bar = VariationBarView(context, buttonRegistry = StatusBarButtonRegistry())
        val wrapper = bar.ensureView()
        bar.themeOverride = theme.toKeyboardThemeColors()
        val rowPx = dp(VariationBarView.rowHeightDp(1.2f))

        assertEquals(rowPx, wrapper.layoutParams.height)
        bar.hostsInlineContent = true
        assertEquals(dp(VariationBarView.rowHeightDp(1.2f) * 2f), wrapper.layoutParams.height)
        bar.hostsInlineContent = false
        assertEquals(rowPx, wrapper.layoutParams.height)
    }

    @Test
    fun suggestionsRowTakesTheAutofillHeightOnlyWhileItHostsChips() {
        val bar = FullSuggestionsBar(context)
        bar.ensureView()
        bar.themeOverride = theme.toKeyboardThemeColors()
        val frame = ReflectionHelpers.getField<FrameLayout>(bar, "frameContainer")
        val rowPx = dp(FullSuggestionsBar.rowHeightDp(0.9f))

        assertEquals(rowPx, frame.layoutParams.height)
        bar.inlineContent = View(context)
        assertEquals(dp(FullSuggestionsBar.rowHeightDp(0.9f) * 2f), frame.layoutParams.height)
        assertEquals(dp(FullSuggestionsBar.rowHeightDp(0.9f) * 2f), frame.minimumHeight)
        bar.inlineContent = null
        assertEquals(rowPx, frame.layoutParams.height)
        assertEquals(rowPx, frame.minimumHeight)
    }

    @Test
    fun presetsCarryTheAutofillRowHeight() {
        val preset: KeyboardThemePreset = keyboardThemePresets().first().copy(autofillRowHeightScale = 2.25f)
        assertEquals(2.25f, preset.toKeyboardThemeColors().autofillRowHeightScale)
        assertEquals(2.25f, preset.toSettingsTheme().autofillRowHeightScale)
        assertEquals(2.25f, preset.toSettingsTheme().toKeyboardThemePreset("Copy").autofillRowHeightScale)
        assertEquals(1f, keyboardThemePresets().map { it.autofillRowHeightScale }.distinct().single())
    }

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
}
