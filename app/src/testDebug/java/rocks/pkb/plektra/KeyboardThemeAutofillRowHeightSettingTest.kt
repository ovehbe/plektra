package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.SettingsManager.KeyboardThemeTarget
import rocks.pkb.plektra.inputmethod.autofill.InlineAutofillRowHeight

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h1600dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class KeyboardThemeAutofillRowHeightSettingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null
    private val label get() = context.getString(R.string.keyboard_theme_autofill_row_height)

    @Before fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @After fun tearDown() { scenario?.close() }

    private fun open(target: KeyboardThemeTarget) {
        scenario?.close()
        val intent = Intent(context, SettingsActivity::class.java).putSettingsPage(SettingLinkRegistry
            .byId("keyboard_theme.${target.name.lowercase()}.autofill_row_height")!!.route.toSettingsPage())
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    private fun slider(at: Float) = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo,
        androidx.compose.ui.semantics.ProgressBarRangeInfo(at, InlineAutofillRowHeight.SCALE_RANGE, 0)
    )

    @Test fun sliderFollowsTheRowHeightsAndChangesOnlyTheEditedKeyboard() {
        for (target in KeyboardThemeTarget.values()) {
            SettingsManager.getPreferences(context).edit().clear().commit()
            open(target)
            compose.onNodeWithText(label).performScrollTo()
            val variations = compose.onNodeWithText(context.getString(R.string.keyboard_theme_variations_height))
                .fetchSemanticsNode().boundsInRoot
            val autofill = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue("$target: below Variations height", autofill.top > variations.top)

            compose.onNode(slider(InlineAutofillRowHeight.DEFAULT_SCALE)).performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(InlineAutofillRowHeight.MAX_SCALE) }
            compose.waitForIdle()
            compose.onNode(slider(InlineAutofillRowHeight.MAX_SCALE)).assertExists()

            val other = KeyboardThemeTarget.values().single { it != target }
            assertEquals(target.name, 2.5f, SettingsManager.getKeyboardTheme(context, target).autofillRowHeightScale)
            assertEquals(target.name, 1f, SettingsManager.getKeyboardTheme(context, other).autofillRowHeightScale)
        }
    }

    @Test fun searchFindsTheSliderForBothKeyboards() {
        val found = SettingLinkRegistry.search(context, "autofill row").map { it.id }
        for (target in KeyboardThemeTarget.values()) {
            val id = "keyboard_theme.${target.name.lowercase()}.autofill_row_height"
            assertTrue("$id in $found", id in found)
            val page = SettingLinkRegistry.byId(id)!!.route.toSettingsPage()
            assertEquals("keyboard_theme", page.customizationDestination)
            assertNotNull(SettingLinkRegistry.byId(id))
            assertEquals(target, SettingLinkRegistry.byId(id)!!.route.keyboardThemeTarget)
            assertEquals(KeyboardThemeEditorTab.Keys, SettingLinkRegistry.byId(id)!!.route.keyboardThemeTab)
        }
    }
}
