package rocks.pkb.plektra.backup

import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlwaysShowHideSensitiveBackupTest {
    @Test fun zipRestoresTheSwitchOffAfterPreferencesAreCleared() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().clear().commit()
        SettingsManager.setAlwaysShowSuggestionsBar(context, true)
        SettingsManager.setAlwaysShowSuggestionsBarHideSensitive(context, false)
        val archive = File(context.cacheDir, "always-show-hide-sensitive.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(archive)) is BackupResult.Success)
        prefs.edit().clear().commit()
        assertTrue(SettingsManager.getAlwaysShowSuggestionsBarHideSensitive(context))
        assertTrue(RestoreManager.restore(context, Uri.fromFile(archive)) is RestoreResult.Success)
        assertTrue(SettingsManager.getAlwaysShowSuggestionsBar(context))
        assertFalse(SettingsManager.getAlwaysShowSuggestionsBarHideSensitive(context))
    }
}
