package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AlwaysShowHideVariationsSettingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<SettingsActivity>? = null

    @Before fun setup() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @After fun tearDown() { scenario?.close() }

    private fun openKeyboardBarSettings() {
        val intent = Intent(context, SettingsActivity::class.java)
            .putSettingsPage(SettingsPage(SettingsDestination.Customization, "status_bar_buttons"))
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    private fun node(res: Int) = compose.onNodeWithText(context.getString(res)).performScrollTo()
    private fun alwaysShow() = node(R.string.always_show_suggestions_bar_title)
    private fun hideEmpty() = node(R.string.always_show_suggestions_bar_hide_sensitive_title)
    private fun hideVariations() = node(R.string.always_show_suggestions_bar_hide_variations_title)
    private val title get() = context.getString(R.string.always_show_suggestions_bar_hide_variations_title)

    @Test fun sitsRightBelowHideWhereItStaysEmpty_offByDefault() {
        openKeyboardBarSettings()
        compose.onNodeWithText(title).assertDoesNotExist()

        alwaysShow().performClick()
        compose.waitForIdle()
        hideVariations().assertIsOff().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.always_show_suggestions_bar_hide_variations_summary)).assertExists()
        assertFalse(SettingsManager.getAlwaysShowSuggestionsBarHideVariations(context))
        val parentSummary = compose.onNodeWithText(context.getString(R.string.always_show_suggestions_bar_hide_sensitive_summary))
            .getUnclippedBoundsInRoot()
        val child = compose.onNodeWithText(title).getUnclippedBoundsInRoot()
        val actions = compose.onNodeWithText(context.getString(R.string.keyboard_bar_actions)).getUnclippedBoundsInRoot()
        assertTrue("below the parent switch", child.top >= parentSummary.bottom)
        assertTrue("above Variations and menu", child.bottom <= actions.top)

        alwaysShow().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test fun isDisabledWhileTheParentIsOff_andKeepsItsValue() {
        SettingsManager.setAlwaysShowSuggestionsBar(context, true)
        openKeyboardBarSettings()

        hideVariations().performClick()
        compose.waitForIdle()
        hideVariations().assertIsOn()
        assertTrue(SettingsManager.getAlwaysShowSuggestionsBarHideVariations(context))

        hideEmpty().performClick()
        compose.waitForIdle()
        hideVariations().assertIsOn().assertIsNotEnabled()
        hideVariations().performClick()
        compose.waitForIdle()
        assertTrue(SettingsManager.getAlwaysShowSuggestionsBarHideVariations(context))

        hideEmpty().performClick()
        compose.waitForIdle()
        hideVariations().assertIsEnabled().performClick()
        compose.waitForIdle()
        hideVariations().assertIsOff()
        assertFalse(SettingsManager.getAlwaysShowSuggestionsBarHideVariations(context))
    }

    @Test fun settingsSearchFindsTheSwitchAndOpensTheKeyboardBarScreen() {
        for (query in listOf(title, "variations row")) {
            val entry = SettingLinkRegistry.search(context, query)
                .firstOrNull { it.id == "keyboard_bar.always_show_suggestions_hide_variations" }
            assertNotNull(query, entry)
            assertEquals("status_bar_buttons", entry!!.route.toSettingsPage().customizationDestination)
        }
    }
}
