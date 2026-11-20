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
import rocks.pkb.plektra.backup.BackupManager
import rocks.pkb.plektra.backup.BackupResult
import rocks.pkb.plektra.backup.RestoreManager
import rocks.pkb.plektra.backup.RestoreResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherCornerRadiusTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun missingPreferenceKeepsOriginalPanelRadius() {
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun setterClampsBeforePersisting() {
        val prefs = SettingsManager.getPreferences(context)
        val key = SettingsManager.KEY_QUICK_LAUNCHER_CORNER_RADIUS_DP
        for ((input, expected) in listOf(-1 to 0, 23 to 23, 41 to 40)) {
            SettingsManager.setQuickLauncherCornerRadiusDp(context, input)
            assertEquals(expected, prefs.getInt(key, -1))
            assertEquals(expected, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        }
    }

    @Test
    fun getterClampsOutOfRangeStoredValues() {
        val prefs = SettingsManager.getPreferences(context)
        val key = SettingsManager.KEY_QUICK_LAUNCHER_CORNER_RADIUS_DP
        prefs.edit().putInt(key, -10).commit()
        assertEquals(0, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        prefs.edit().putInt(key, 99).commit()
        assertEquals(40, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }

    @Test
    fun backupRoundTripRestoresCustomRadius() = runBlocking {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 32)
        val uri = Uri.fromFile(File(context.cacheDir, "quick-launcher-corner-radius.zip"))
        val backup = BackupManager.createBackup(context, uri)
        assertTrue(backup.toString(), backup is BackupResult.Success)

        SettingsManager.getPreferences(context).edit().clear().commit()
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))

        val restored = RestoreManager.restore(context, uri)
        assertTrue(restored.toString(), restored is RestoreResult.Success)
        assertEquals(32, SettingsManager.getQuickLauncherCornerRadiusDp(context))
    }
}
