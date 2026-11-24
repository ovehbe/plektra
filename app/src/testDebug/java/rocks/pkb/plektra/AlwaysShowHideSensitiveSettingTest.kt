package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
class AlwaysShowHideSensitiveSettingTest {
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

    private fun alwaysShow() =
        compose.onNodeWithText(context.getString(R.string.always_show_suggestions_bar_title)).performScrollTo()

    private val title get() = context.getString(R.string.always_show_suggestions_bar_hide_sensitive_title)

    private fun hideSensitive() = compose.onNodeWithText(title).performScrollTo()

    @Test fun appearsUnderAlwaysShowOnlyWhileItIsOn_onByDefault() {
        openKeyboardBarSettings()
        alwaysShow().assertIsOff()
        compose.onNodeWithText(title).assertDoesNotExist()

        alwaysShow().performClick()
        compose.waitForIdle()
        hideSensitive().assertIsOn()
        compose.onNodeWithText(context.getString(R.string.always_show_suggestions_bar_hide_sensitive_summary)).assertExists()
        assertTrue(SettingsManager.getAlwaysShowSuggestionsBarHideSensitive(context))

        alwaysShow().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test fun theSwitchWritesThePreferenceTheImeReads_andKeepsItWhileHidden() {
        SettingsManager.setAlwaysShowSuggestionsBar(context, true)
        openKeyboardBarSettings()

        hideSensitive().performClick()
        compose.waitForIdle()
        hideSensitive().assertIsOff()
        assertFalse(SettingsManager.getAlwaysShowSuggestionsBarHideSensitive(context))

        alwaysShow().performClick()
        compose.waitForIdle()
        alwaysShow().performClick()
        compose.waitForIdle()
        hideSensitive().assertIsOff()

        hideSensitive().performClick()
        compose.waitForIdle()
        hideSensitive().assertIsOn()
        assertTrue(SettingsManager.getAlwaysShowSuggestionsBarHideSensitive(context))
    }

    @Test fun settingsSearchFindsTheSwitchAndOpensTheKeyboardBarScreen() {
        for (query in listOf(title, "password", "number", "phone", "URL", "autofill chips")) {
            val entry = SettingLinkRegistry.search(context, query)
                .firstOrNull { it.id == "keyboard_bar.always_show_suggestions_hide_sensitive" }
            assertNotNull(query, entry)
            assertEquals("status_bar_buttons", entry!!.route.toSettingsPage().customizationDestination)
        }
    }
}
