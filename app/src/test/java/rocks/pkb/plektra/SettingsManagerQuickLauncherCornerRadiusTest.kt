package rocks.pkb.plektra

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.backup.BackupPreferenceContract
import rocks.pkb.plektra.backup.PreferenceValueType
import rocks.pkb.plektra.inputmethod.quickLauncherPanelShape

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsManagerQuickLauncherCornerRadiusTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun defaultMatchesThePreviousMaterialLargeShape() {
        assertEquals(16, SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP)
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        // The panel used MaterialTheme.shapes.large before; PlektraTheme does not override shapes.
        assertEquals(Shapes().large, quickLauncherPanelShape(SettingsManager.getQuickLauncherCornerRadiusDp(context)))
    }

    @Test
    fun rangeGoesFromSquareToClearlyMoreRoundedThanDefault() {
        assertEquals(0, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP)
        assertTrue(
            SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP >=
                SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP * 2
        )
    }

    @Test
    fun valuesPersistAndAreClampedToTheRange() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 0)
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, 28)
        assertEquals(28, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(28, SettingsManager.getPreferences(context).getInt("quick_launcher_corner_radius_dp", -1))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, 40)
        assertEquals(40, SettingsManager.getQuickLauncherCornerRadiusDp(context))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, -5)
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, 500)
        assertEquals(40, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun outOfRangeStoredValuesAreClampedOnRead() {
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().putInt("quick_launcher_corner_radius_dp", 999).commit()
        assertEquals(40, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        prefs.edit().putInt("quick_launcher_corner_radius_dp", -3).commit()
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun sliderSnapsToStepsWithinRange() {
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(0f))
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(0.9f))
        assertEquals(2, SettingsManager.snapQuickLauncherCornerRadiusDp(1.1f))
        assertEquals(16, SettingsManager.snapQuickLauncherCornerRadiusDp(15.99f))
        assertEquals(40, SettingsManager.snapQuickLauncherCornerRadiusDp(40f))
        assertEquals(40, SettingsManager.snapQuickLauncherCornerRadiusDp(55f))
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(-4f))
        // 0..40 in 2dp increments = 21 positions = 19 intermediate stops.
        assertEquals(19, SettingsManager.quickLauncherCornerRadiusSliderSteps())
    }

    @Test
    fun panelShapeFollowsTheSettingAndClamps() {
        assertEquals(RoundedCornerShape(0.dp), quickLauncherPanelShape(0))
        assertEquals(RoundedCornerShape(32.dp), quickLauncherPanelShape(32))
        assertEquals(RoundedCornerShape(40.dp), quickLauncherPanelShape(100))
        assertEquals(RoundedCornerShape(0.dp), quickLauncherPanelShape(-1))
    }

    @Test
    fun settingIsCoveredByBackupAndSettingsSearch() {
        assertTrue(BackupPreferenceContract.isExportable("plektra_prefs", "quick_launcher_corner_radius_dp"))
        assertEquals(
            PreferenceValueType.INT,
            BackupPreferenceContract.expectedExportType("plektra_prefs", "quick_launcher_corner_radius_dp")
        )
        val entry = customizationSettingEntries().single { it.id == "quick_launcher.corner_radius" }
        assertEquals(R.string.quick_launcher_corner_radius_title, entry.titleRes)
        assertEquals("launcher_shortcut_cosmetic", entry.route.customizationDestination)
    }
}
