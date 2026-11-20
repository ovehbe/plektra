package rocks.pkb.plektra.backup

import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.customizationSettingEntries

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherCornerRadiusBackupTest {
    private val context = RuntimeEnvironment.getApplication()
    private val key = "quick_launcher_corner_radius_dp"

    @Before fun setUp() { SettingsManager.getPreferences(context).edit().clear().commit() }
    @After fun tearDown() { SettingsManager.getPreferences(context).edit().clear().commit() }

    @Test fun defaultMatchesThePreviousMaterialLargeShape() {
        // Material 3 shapes.large / shapes.medium are 16 dp / 12 dp; the panel and rows used those.
        assertEquals(16, SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP)
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(12f, SettingsManager.quickLauncherItemCornerRadiusDp(16), 0f)
    }

    @Test fun rangeIsSquareToClearlyRounderThanDefault() {
        assertEquals(0, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP)
        assertTrue(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP >= 2 * SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP)
        assertEquals(0f, SettingsManager.quickLauncherItemCornerRadiusDp(0), 0f)
    }

    @Test fun setterAndGetterClampAndPersist() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 0)
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 24)
        assertEquals(24, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(24, SettingsManager.getPreferences(context).getInt(key, -1))
        SettingsManager.setQuickLauncherCornerRadiusDp(context, -5)
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 500)
        assertEquals("stored clamped", SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP,
            SettingsManager.getPreferences(context).getInt(key, -1))
        // Hand-edited / restored out-of-range values are clamped on read.
        SettingsManager.getPreferences(context).edit().putInt(key, 999).commit()
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        SettingsManager.getPreferences(context).edit().putInt(key, -1).commit()
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP * 0.75f,
            SettingsManager.quickLauncherItemCornerRadiusDp(999), 0f)
    }

    @Test fun sliderSnapsToStepsAndHitsDefaultAndEnds() {
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(0f))
        assertEquals(0, SettingsManager.snapQuickLauncherCornerRadiusDp(-3f))
        assertEquals(16, SettingsManager.snapQuickLauncherCornerRadiusDp(15.6f))
        assertEquals(16, SettingsManager.snapQuickLauncherCornerRadiusDp(16.4f))
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP,
            SettingsManager.snapQuickLauncherCornerRadiusDp(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP.toFloat()))
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP, SettingsManager.snapQuickLauncherCornerRadiusDp(100f))
        // Slider stops: min, (steps intermediate), max — all on the 2 dp grid, so the default is a stop.
        val stops = SettingsManager.quickLauncherCornerRadiusSliderSteps + 2
        val span = SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP - SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP
        assertEquals(span / SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_STEP_DP + 1, stops)
        assertEquals(0, SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP % SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_STEP_DP)
    }

    @Test fun isExportableAndSearchable() {
        assertTrue(BackupPreferenceContract.isExportable("plektra_prefs", key))
        val entry = customizationSettingEntries().single { it.id == "quick_launcher.corner_radius" }
        assertEquals(R.string.quick_launcher_corner_radius_title, entry.titleRes)
        assertEquals("launcher_shortcut_cosmetic", entry.route.customizationDestination)
    }

    @Test fun zipRestoresCornerRadiusAfterPreferencesAreCleared() = runBlocking {
        val prefs = SettingsManager.getPreferences(context)
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 4)
        val archive = File(context.cacheDir, "quick-launcher-corner-radius.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(archive)) is BackupResult.Success)
        prefs.edit().clear().commit()
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertTrue(RestoreManager.restore(context, Uri.fromFile(archive)) is RestoreResult.Success)
        assertEquals(4, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }
}
