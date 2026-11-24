package rocks.pkb.plektra

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
class InlineAutofillSettingTest {
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

    private fun row() = compose.onNodeWithText(context.getString(R.string.inline_autofill_title)).performScrollTo()

    @Test fun onByDefault_andTheSwitchWritesThePreferenceTheImeReads() {
        openKeyboardBarSettings()
        row().assertIsEnabled().assertIsOn()
        compose.onNodeWithText(context.getString(R.string.inline_autofill_description)).assertExists()

        row().performClick()
        compose.waitForIdle()
        row().assertIsOff()
        assertFalse(SettingsManager.getInlineAutofillEnabled(context))

        row().performClick()
        compose.waitForIdle()
        row().assertIsOn()
        assertTrue(SettingsManager.getInlineAutofillEnabled(context))
    }

    @Test fun candidatesViewNotesThatChipsNeedTheStandardBar() {
        SettingsManager.setExperimentalCandidatesViewEnabled(context, true)
        openKeyboardBarSettings()
        row()
        compose.onNodeWithText(context.getString(R.string.inline_autofill_candidates_view_note), substring = true)
            .assertExists()
    }

    @Test fun standardBarShowsNoCandidatesViewNote() {
        openKeyboardBarSettings()
        row()
        compose.onNodeWithText(context.getString(R.string.inline_autofill_candidates_view_note), substring = true)
            .assertDoesNotExist()
    }

    @Test fun settingsSearchFindsTheSwitchAndOpensTheKeyboardBarScreen() {
        for (query in listOf(context.getString(R.string.inline_autofill_title), "autofill", "password")) {
            val entry = SettingLinkRegistry.search(context, query).firstOrNull { it.id == "keyboard_bar.inline_autofill" }
            assertNotNull(query, entry)
            assertEquals("status_bar_buttons", entry!!.route.toSettingsPage().customizationDestination)
        }
    }

    @Test @Config(sdk = [29])
    fun belowAndroid11TheSwitchIsDisabledAndExplainsWhy() {
        openKeyboardBarSettings()
        row().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.inline_autofill_unsupported)).assertExists()

        row().performClick()
        compose.waitForIdle()
        assertTrue(SettingsManager.getInlineAutofillEnabled(context))
    }
}
