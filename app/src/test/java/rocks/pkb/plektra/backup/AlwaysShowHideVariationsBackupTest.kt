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
class AlwaysShowHideVariationsBackupTest {
    @Test fun zipRestoresTheSwitchOnAfterPreferencesAreCleared() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = SettingsManager.getPreferences(context)
        prefs.edit().clear().commit()
        assertFalse(SettingsManager.getAlwaysShowSuggestionsBarHideVariations(context))
        SettingsManager.setAlwaysShowSuggestionsBar(context, true)
        SettingsManager.setAlwaysShowSuggestionsBarHideVariations(context, true)
        val archive = File(context.cacheDir, "always-show-hide-variations.zip")
        assertTrue(BackupManager.createBackup(context, Uri.fromFile(archive)) is BackupResult.Success)
        prefs.edit().clear().commit()
        assertFalse(SettingsManager.getAlwaysShowSuggestionsBarHideVariations(context))
        assertTrue(RestoreManager.restore(context, Uri.fromFile(archive)) is RestoreResult.Success)
        assertTrue(SettingsManager.getAlwaysShowSuggestionsBarHideVariations(context))
    }
}
