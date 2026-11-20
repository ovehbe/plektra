package rocks.pkb.plektra

import android.content.Context
import android.net.Uri
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
import rocks.pkb.plektra.backup.BackupPreferenceContract
import rocks.pkb.plektra.backup.BackupManager
import rocks.pkb.plektra.backup.BackupResult
import rocks.pkb.plektra.backup.PreferenceValueType
import rocks.pkb.plektra.backup.RestoreManager
import rocks.pkb.plektra.backup.RestoreResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherCornerRadiusSettingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefKey = "quick_launcher_corner_radius_dp"

    @Before
    fun resetSettings() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun defaultMatchesPreviousMaterialLargeShapeRadius() {
        // The panel used MaterialTheme.shapes.large (Material 3: 16dp) before this was configurable.
        assertEquals(16, SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP)
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun rangeGoesFromSquareToClearlyRounderThanDefault() {
        assertEquals(0, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP)
        assertEquals(40, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP)
        assertTrue(
            SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP in
                SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP..SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP
        )
        // Default must be reachable as a slider step.
        assertEquals(
            0,
            SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP % SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_STEP_DP
        )
    }

    @Test
    fun setterPersistsAndClampsToRange() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 0)
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 40)
        assertEquals(40, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 24)
        assertEquals(24, SettingsManager.getPreferences(context).getInt(prefKey, -1))

        SettingsManager.setQuickLauncherCornerRadiusDp(context, -5)
        assertEquals(0, SettingsManager.getPreferences(context).getInt(prefKey, -1))
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 500)
        assertEquals(40, SettingsManager.getPreferences(context).getInt(prefKey, -1))
    }

    @Test
    fun outOfRangeStoredValueIsClampedOnRead() {
        SettingsManager.getPreferences(context).edit().putInt(prefKey, 999).commit()
        assertEquals(40, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        SettingsManager.getPreferences(context).edit().putInt(prefKey, -3).commit()
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun sliderValueSnapsToStepsWithinRange() {
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(0f))
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(0.9f))
        assertEquals(16, SettingsManager.snapQuickLauncherCornerRadiusDp(15.2f))
        assertEquals(16, SettingsManager.snapQuickLauncherCornerRadiusDp(16.9f))
        assertEquals(40, SettingsManager.snapQuickLauncherCornerRadiusDp(40f))
        assertEquals(40, SettingsManager.snapQuickLauncherCornerRadiusDp(55f))
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(-4f))
    }

    @Test
    fun settingIsPartOfBackupContract() {
        assertEquals(PreferenceValueType.INT, BackupPreferenceContract.expectedExportType("plektra_prefs", prefKey))
        assertTrue(BackupPreferenceContract.isExportable("plektra_prefs", prefKey))
    }

    @Test
    fun backupRoundTripRestoresCornerRadius() = runBlocking {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 30)
        val archive = File(context.cacheDir, "quick-launcher-corner-radius.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(archive)) is BackupResult.Success)
        SettingsManager.getPreferences(context).edit().clear().commit()
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertTrue(RestoreManager.restore(context, Uri.fromFile(archive)) is RestoreResult.Success)
        assertEquals(30, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun settingIsSearchableAndRoutesToAppearanceScreen() {
        val entry = requireNotNull(SettingLinkRegistry.byId("quick_launcher.corner_radius"))
        assertEquals("launcher_shortcut_cosmetic", entry.route.customizationDestination)
        val results = SettingLinkRegistry.search(context, context.getString(R.string.quick_launcher_corner_radius_title))
        assertTrue(results.any { it.id == "quick_launcher.corner_radius" })
    }
}
