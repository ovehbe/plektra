package rocks.pkb.plektra.backup

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
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SettingsManager.KeyboardThemeTarget

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutofillRowHeightBackupTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun backupZipRestoresTheAutofillRowHeightOfBothTargetsAndSavedThemes() = runBlocking {
        val hardware = SettingsManager.defaultKeyboardTheme().copy(autofillRowHeightScale = 2.5f)
        val software = SettingsManager.defaultKeyboardTheme().copy(autofillRowHeightScale = 1.75f)
        SettingsManager.setKeyboardTheme(context, KeyboardThemeTarget.HARDWARE, hardware)
        SettingsManager.setKeyboardTheme(context, KeyboardThemeTarget.SOFTWARE, software)
        SettingsManager.saveKeyboardTheme(context, "Tall chips", hardware)
        val zip = File(context.cacheDir, "autofill-row-height.zip")
        val backup = BackupManager.createBackup(context, Uri.fromFile(zip))
        assertTrue(backup.toString(), backup is BackupResult.Success)

        SettingsManager.getPreferences(context).edit().clear().commit()
        assertEquals(1f, SettingsManager.getKeyboardTheme(context, KeyboardThemeTarget.HARDWARE).autofillRowHeightScale)

        val result = RestoreManager.restore(context, Uri.fromFile(zip))
        assertTrue(result.toString(), result is RestoreResult.Success)
        assertEquals(2.5f, SettingsManager.getKeyboardTheme(context, KeyboardThemeTarget.HARDWARE).autofillRowHeightScale)
        assertEquals(1.75f, SettingsManager.getKeyboardTheme(context, KeyboardThemeTarget.SOFTWARE).autofillRowHeightScale)
        assertEquals(hardware, SettingsManager.getSavedKeyboardThemes(context).single().theme)
    }
}
