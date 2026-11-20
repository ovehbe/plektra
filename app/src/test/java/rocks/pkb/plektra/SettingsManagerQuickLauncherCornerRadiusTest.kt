package rocks.pkb.plektra

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsManagerQuickLauncherCornerRadiusTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun cornerRadius_defaultsToCurrentPanelRadius() {
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun cornerRadius_persistsStoredValue() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 24)

        assertEquals(24, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun cornerRadius_setterClampsToSupportedRange() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, -4)
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP, SettingsManager.getQuickLauncherCornerRadiusDp(context))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, 400)
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun cornerRadius_getterClampsLegacyOutOfRangeValue() {
        SettingsManager.getPreferences(context).edit()
            .putInt("quick_launcher_corner_radius_dp", 999)
            .commit()

        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP, SettingsManager.getQuickLauncherCornerRadiusDp(context))

        SettingsManager.getPreferences(context).edit()
            .putInt("quick_launcher_corner_radius_dp", -12)
            .commit()

        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun cornerRadius_rangeAllowsSquareAndClearlyMoreRoundedThanDefault() {
        assertEquals(0, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP)
        assertEquals(40, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP)
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }
}
