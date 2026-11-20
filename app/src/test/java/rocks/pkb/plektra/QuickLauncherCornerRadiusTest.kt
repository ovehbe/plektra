package rocks.pkb.plektra

import android.net.Uri
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ShapeDefaults
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
import rocks.pkb.plektra.backup.BackupManager
import rocks.pkb.plektra.backup.BackupPreferenceContract
import rocks.pkb.plektra.backup.BackupResult
import rocks.pkb.plektra.backup.RestoreManager
import rocks.pkb.plektra.backup.RestoreResult
import rocks.pkb.plektra.inputmethod.quickLauncherPanelShape

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherCornerRadiusTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = SettingsManager.getPreferences(context)

    @Before
    fun reset() {
        prefs.edit().clear().commit()
    }

    @Test
    fun defaultEqualsTheRadiusThePanelUsedBefore() {
        // The panel used MaterialTheme.shapes.large (the Material 3 default; the app theme does not override shapes).
        assertEquals(ShapeDefaults.Large, RoundedCornerShape(16.dp))
        assertEquals(16, SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP)
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(ShapeDefaults.Large, quickLauncherPanelShape(SettingsManager.getQuickLauncherCornerRadiusDp(context)))
    }

    @Test
    fun rangeRunsFromSquareToClearlyMoreRoundedThanToday() {
        assertEquals(0, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP)
        assertTrue(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP >= 2 * SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP)
        assertEquals(0, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP % SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_STEP_DP)
        assertEquals(0, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP % SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_STEP_DP)
        assertEquals(0, SettingsManager.DEFAULT_QUICK_LAUNCHER_CORNER_RADIUS_DP % SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_STEP_DP)
    }

    @Test
    fun setterAndGetterClampToTheRange() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, -5)
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(0, prefs.getInt("quick_launcher_corner_radius_dp", -1))
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 10_000)
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP, prefs.getInt("quick_launcher_corner_radius_dp", -1))
        // Hand-edited / restored out-of-range values are clamped on read.
        prefs.edit().putInt("quick_launcher_corner_radius_dp", -3).commit()
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        prefs.edit().putInt("quick_launcher_corner_radius_dp", 999).commit()
        assertEquals(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun valuePersists() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 28)
        assertEquals(28, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(28, prefs.getInt("quick_launcher_corner_radius_dp", -1))
    }

    @Test
    fun panelShapeFollowsRadiusAndClamps() {
        assertEquals(RoundedCornerShape(0.dp), quickLauncherPanelShape(0))
        assertEquals(RoundedCornerShape(24.dp), quickLauncherPanelShape(24))
        assertEquals(RoundedCornerShape(0.dp), quickLauncherPanelShape(-8))
        assertEquals(
            RoundedCornerShape(SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP.dp),
            quickLauncherPanelShape(500)
        )
    }

    @Test
    fun settingIsBackedUpAndRestored() = runBlocking {
        assertTrue(BackupPreferenceContract.isExportable("plektra_prefs", "quick_launcher_corner_radius_dp"))
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 36)
        val archive = File(context.cacheDir, "quick-launcher-corner-radius.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(archive)) is BackupResult.Success)
        prefs.edit().clear().commit()
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertTrue(RestoreManager.restore(context, Uri.fromFile(archive)) is RestoreResult.Success)
        assertEquals(36, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun settingIsSearchableAndRoutesToTheAppearanceScreen() {
        val entry = requireNotNull(SettingLinkRegistry.byId("quick_launcher.corner_radius"))
        assertEquals("launcher_shortcut_cosmetic", entry.route.customizationDestination)
        val results = SettingLinkRegistry.search(context, context.getString(R.string.quick_launcher_corner_radius_title))
        assertTrue(results.any { it.id == "quick_launcher.corner_radius" })
    }

    @Test
    fun stringsAreTranslatedInEveryLocaleShippedForTheNeighbouringWidthStrings() {
        val resDir = locateResDir()
        val keys = listOf("quick_launcher_corner_radius_title", "quick_launcher_corner_radius_value")
        val localeDirs = resDir.listFiles { f -> f.isDirectory && File(f, "strings.xml").exists() }!!
            .filter { File(it, "strings.xml").readText().contains("name=\"quick_launcher_width_title\"") }
        assertTrue(localeDirs.size >= 10)
        localeDirs.forEach { dir ->
            val text = File(dir, "strings.xml").readText()
            keys.forEach { key ->
                assertTrue("${dir.name} lacks $key", text.contains("name=\"$key\""))
            }
        }
    }

    private fun locateResDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf("app/src/main/res", "src/main/res").map { File(dir, it) }.firstOrNull { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("res dir not found")
    }
}
