package rocks.pkb.plektra

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.backup.BackupPreferenceContract
import rocks.pkb.plektra.backup.PreferenceSchemas
import rocks.pkb.plektra.backup.PreferenceValueType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherCornerRadiusSettingsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun default_isSixteenDp() {
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun default_matchesThemeLargeShapePreviouslyUsedByPanel() {
        assertEquals(
            Shapes().large,
            RoundedCornerShape(SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP.dp)
        )
    }

    @Test
    fun range_isSquareToClearlyMoreRoundedThanDefault() {
        assertEquals(0, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP)
        assertEquals(32, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP)
        assertTrue(
            SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP in
                SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP..SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP
        )
    }

    @Test
    fun sliderSteps_landOnEveryTwoDpIncludingDefault() {
        val min = SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP
        val max = SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP
        val intervals = SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_SLIDER_STEPS + 1
        val positions = (0..intervals).map { min + it * (max - min) / intervals }

        assertEquals((0..32 step 2).toList(), positions)
        assertTrue(SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP in positions)
    }

    @Test
    fun setValue_persistsAcrossReads() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 24)

        assertEquals(24, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(
            24,
            context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
                .getInt("quick_launcher_corner_radius_dp", -1)
        )
    }

    @Test
    fun setValue_acceptsBothRangeEnds() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 0)
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, 32)
        assertEquals(32, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun setValue_clampsOutOfRangeValues() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, -5)
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, 500)
        assertEquals(32, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun getValue_clampsOutOfRangeStoredValues() {
        val prefs = SettingsManager.getPreferences(context)

        prefs.edit().putInt("quick_launcher_corner_radius_dp", 999).commit()
        assertEquals(32, SettingsManager.getQuickLauncherCornerRadiusDp(context))

        prefs.edit().putInt("quick_launcher_corner_radius_dp", -1).commit()
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun backup_exportsAndRestoresAsInt() {
        assertTrue(BackupPreferenceContract.isExportable("plektra_prefs", "quick_launcher_corner_radius_dp"))
        assertEquals(
            PreferenceValueType.INT,
            PreferenceSchemas.expectedType("plektra_prefs", "quick_launcher_corner_radius_dp")
        )
    }

    @Test
    fun settingsSearch_routesToQuickLauncherAppearance() {
        val entry = customizationSettingEntries().single { it.id == "quick_launcher.corner_radius" }

        assertEquals(R.string.quick_launcher_corner_radius_title, entry.titleRes)
        assertEquals(SettingsDestination.Customization, entry.route.destination)
        assertEquals("launcher_shortcut_cosmetic", entry.route.customizationDestination)
    }
}
