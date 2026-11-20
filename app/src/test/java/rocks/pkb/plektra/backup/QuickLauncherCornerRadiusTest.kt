package rocks.pkb.plektra.backup

import android.net.Uri
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.customizationSettingEntries

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherCornerRadiusTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs get() = SettingsManager.getPreferences(context)

    @Before fun clear() {
        prefs.edit().clear().commit()
    }

    @Test fun defaultMatchesThePreviousMaterialLargeShape() {
        assertEquals(16, SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP)
        assertEquals(
            Shapes().large,
            RoundedCornerShape(SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP.dp)
        )
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test fun rangeGoesFromSquareToClearlyRounderThanDefault() {
        assertEquals(0, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP)
        assertTrue(
            SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP >=
                2 * SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP
        )
        // The default must be a reachable slider stop.
        assertEquals(
            0,
            SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP % SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_STEP_DP
        )
        assertEquals(19, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_SLIDER_STEPS)
    }

    @Test fun setterPersistsAndClampsToRange() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 0)
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(0, prefs.getInt("quick_launcher_corner_radius_dp", -1))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, 28)
        assertEquals(28, SettingsManager.getQuickLauncherCornerRadiusDp(context))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, -5)
        assertEquals(0, prefs.getInt("quick_launcher_corner_radius_dp", -1))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, 500)
        assertEquals(
            SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP,
            prefs.getInt("quick_launcher_corner_radius_dp", -1)
        )
    }

    @Test fun outOfRangeStoredValuesAreClampedWhenRead() {
        prefs.edit().putInt("quick_launcher_corner_radius_dp", 999).commit()
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        prefs.edit().putInt("quick_launcher_corner_radius_dp", -3).commit()
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test fun sliderPositionsSnapToStepsWithinRange() {
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(0f))
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(0.9f))
        assertEquals(16, SettingsManager.snapQuickLauncherCornerRadiusDp(15.9f))
        assertEquals(16, SettingsManager.snapQuickLauncherCornerRadiusDp(16.0001f))
        assertEquals(18, SettingsManager.snapQuickLauncherCornerRadiusDp(17.2f))
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP, SettingsManager.snapQuickLauncherCornerRadiusDp(40f))
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP, SettingsManager.snapQuickLauncherCornerRadiusDp(99f))
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(-4f))
    }

    @Test fun includedInBackupContract() {
        assertEquals(
            PreferenceValueType.INT,
            BackupPreferenceContract.expectedExportType("plektra_prefs", "quick_launcher_corner_radius_dp")
        )
    }

    @Test fun zipBackupRestoresCornerRadius() = runBlocking {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 32)
        val archive = File(context.cacheDir, "quick-launcher-corner-radius.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(archive)) is BackupResult.Success)
        prefs.edit().clear().commit()
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertTrue(RestoreManager.restore(context, Uri.fromFile(archive)) is RestoreResult.Success)
        assertEquals(32, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test fun searchableFromQuickLauncherAppearanceScreen() {
        val entry = customizationSettingEntries().single { it.id == "quick_launcher.corner_radius" }
        assertEquals("launcher_shortcut_cosmetic", entry.route.customizationDestination)
    }
}
