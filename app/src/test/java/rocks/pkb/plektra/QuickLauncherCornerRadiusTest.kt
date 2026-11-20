package rocks.pkb.plektra

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
import rocks.pkb.plektra.backup.BackupManager
import rocks.pkb.plektra.backup.BackupPreferenceContract
import rocks.pkb.plektra.backup.BackupResult
import rocks.pkb.plektra.backup.RestoreManager
import rocks.pkb.plektra.backup.RestoreResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherCornerRadiusTest {
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
    fun defaultMatchesThePreviousMaterialLargeAndMediumShapes() {
        // The panel used MaterialTheme.shapes.large (16dp) and rows shapes.medium (12dp).
        assertEquals(16, SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP)
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(12, SettingsManager.quickLauncherItemCornerRadiusDp(16))
    }

    @Test
    fun rangeGoesFromSquareToClearlyRounderThanToday() {
        assertEquals(0, SettingsManager.MIN_QUICK_LAUNCHER_CORNER_RADIUS_DP)
        assertTrue(
            SettingsManager.MAX_QUICK_LAUNCHER_CORNER_RADIUS_DP >=
                2 * SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP
        )
        val span = SettingsManager.MAX_QUICK_LAUNCHER_CORNER_RADIUS_DP -
            SettingsManager.MIN_QUICK_LAUNCHER_CORNER_RADIUS_DP
        assertEquals(0, span % SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_STEP_DP)
        assertEquals(
            0,
            SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP % SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_STEP_DP
        )
    }

    @Test
    fun setterClampsToRange() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, -5)
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 999)
        assertEquals(
            SettingsManager.MAX_QUICK_LAUNCHER_CORNER_RADIUS_DP,
            SettingsManager.getQuickLauncherCornerRadiusDp(context)
        )
    }

    @Test
    fun getterClampsOutOfRangeStoredValues() {
        SettingsManager.getPreferences(context).edit()
            .putInt("quick_launcher_corner_radius_dp", 500).commit()
        assertEquals(
            SettingsManager.MAX_QUICK_LAUNCHER_CORNER_RADIUS_DP,
            SettingsManager.getQuickLauncherCornerRadiusDp(context)
        )
        SettingsManager.getPreferences(context).edit()
            .putInt("quick_launcher_corner_radius_dp", -1).commit()
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun valuePersists() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 28)
        assertEquals(28, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(28, SettingsManager.getPreferences(context).getInt("quick_launcher_corner_radius_dp", -1))
    }

    @Test
    fun itemRadiusFollowsPanelAndStaysWithinIt() {
        assertEquals(0, SettingsManager.quickLauncherItemCornerRadiusDp(0))
        assertEquals(
            SettingsManager.MAX_QUICK_LAUNCHER_CORNER_RADIUS_DP * 3 / 4,
            SettingsManager.quickLauncherItemCornerRadiusDp(SettingsManager.MAX_QUICK_LAUNCHER_CORNER_RADIUS_DP)
        )
        for (r in SettingsManager.MIN_QUICK_LAUNCHER_CORNER_RADIUS_DP..SettingsManager.MAX_QUICK_LAUNCHER_CORNER_RADIUS_DP) {
            assertTrue(SettingsManager.quickLauncherItemCornerRadiusDp(r) <= r)
        }
        assertEquals(
            SettingsManager.quickLauncherItemCornerRadiusDp(SettingsManager.MAX_QUICK_LAUNCHER_CORNER_RADIUS_DP),
            SettingsManager.quickLauncherItemCornerRadiusDp(1000)
        )
    }

    @Test
    fun settingIsExportableAndSearchable() {
        assertTrue(BackupPreferenceContract.isExportable("plektra_prefs", "quick_launcher_corner_radius_dp"))
        val entry = requireNotNull(SettingLinkRegistry.byId("quick_launcher.corner_radius"))
        assertEquals("launcher_shortcut_cosmetic", entry.route.customizationDestination)
        val results = SettingLinkRegistry.search(
            context,
            context.getString(R.string.quick_launcher_corner_radius_title)
        )
        assertTrue(results.any { it.id == "quick_launcher.corner_radius" })
    }

    @Test
    fun backupRestoresCornerRadius() = runBlocking {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 32)
        val archive = File(context.cacheDir, "quick-launcher-corner-radius.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(archive)) is BackupResult.Success)
        SettingsManager.getPreferences(context).edit().clear().commit()
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertTrue(RestoreManager.restore(context, Uri.fromFile(archive)) is RestoreResult.Success)
        assertEquals(32, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }
}
