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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class KeyboardBarHeightAnimationSettingTest {
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

    private val animationTitle get() = context.getString(R.string.keyboard_bar_height_animation_title)
    private val durationTitle get() = context.getString(R.string.keyboard_bar_height_animation_duration_title)

    @Test fun onByDefaultWithAShortDuration_switchingOffHidesTheDurationAndSnaps() {
        openKeyboardBarSettings()
        compose.onNodeWithText(animationTitle).performScrollTo().assertIsOn()
        compose.onNodeWithText(durationTitle).performScrollTo()
        compose.onNodeWithText(context.getString(R.string.keyboard_bar_height_animation_duration_value, 120)).assertExists()

        compose.onNodeWithText(animationTitle).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(animationTitle).assertIsOff()
        compose.onNodeWithText(durationTitle).assertDoesNotExist()
        assertFalse(SettingsManager.getKeyboardBarHeightAnimation(context))
        assertEquals(0L, SettingsManager.keyboardBarHeightAnimationDurationMs(context))

        compose.onNodeWithText(animationTitle).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(SettingsManager.getKeyboardBarHeightAnimation(context))
        assertEquals(120L, SettingsManager.keyboardBarHeightAnimationDurationMs(context))
    }

    @Test fun rowHeightsOpensTheKeyboardThemeRowHeightSetting() {
        openKeyboardBarSettings()
        compose.onNodeWithText(context.getString(R.string.keyboard_bar_row_heights_title)).performScrollTo().performClick()
        compose.waitForIdle()
        val started = scenario!!.let { s ->
            var intent: Intent? = null
            s.onActivity { intent = shadowOf(it).nextStartedActivity }
            intent
        }
        assertEquals("plektra://setting/keyboard_theme.hardware.suggestions_height", started?.dataString)
        assertNotNull(SettingLinkRegistry.byId("keyboard_theme.hardware.suggestions_height"))
    }

    @Test fun searchFindsTheControls_andTheHiddenDurationFallsBackToTheSwitch() {
        for ((query, id) in listOf(animationTitle to "keyboard_bar.height_animation", durationTitle to "keyboard_bar.height_animation_duration",
            context.getString(R.string.keyboard_bar_row_heights_title) to "keyboard_bar.row_heights")) {
            val entry = SettingLinkRegistry.search(context, query).firstOrNull { it.id == id }
            assertNotNull(query, entry)
            assertEquals("status_bar_buttons", entry!!.route.toSettingsPage().customizationDestination)
        }
        val duration = SettingLinkRegistry.byId("keyboard_bar.height_animation_duration")!!
        assertEquals(duration.id, SettingLinkRegistry.visibleTarget(context, duration).id)
        SettingsManager.setKeyboardBarHeightAnimation(context, false)
        assertEquals("keyboard_bar.height_animation", SettingLinkRegistry.visibleTarget(context, duration).id)
    }
}
