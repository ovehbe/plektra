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
import rocks.pkb.plektra.KeyboardThemeEditorTab
import rocks.pkb.plektra.SettingLinkRegistry
import rocks.pkb.plektra.SettingsDestination
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SettingsManager.KeyboardThemeTarget
import rocks.pkb.plektra.keyboardThemePresets
import rocks.pkb.plektra.toKeyboardThemeColors
import rocks.pkb.plektra.toKeyboardThemePreset
import rocks.pkb.plektra.toSettingsTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SymPageSizeSettingsTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun backupZipRestoresTheSymPageSizeOfBothTargetsAndSavedThemes() = runBlocking {
        val hardware = SettingsManager.defaultKeyboardTheme().copy(symPageScale = 1.75f)
        val software = SettingsManager.defaultKeyboardTheme().copy(symPageScale = 0.75f)
        SettingsManager.setKeyboardTheme(context, KeyboardThemeTarget.HARDWARE, hardware)
        SettingsManager.setKeyboardTheme(context, KeyboardThemeTarget.SOFTWARE, software)
        SettingsManager.saveKeyboardTheme(context, "Big SYM", hardware)
        val zip = File(context.cacheDir, "sym-page-size.zip")
        val backup = BackupManager.createBackup(context, Uri.fromFile(zip))
        assertTrue(backup.toString(), backup is BackupResult.Success)

        SettingsManager.getPreferences(context).edit().clear().commit()
        assertEquals(1f, SettingsManager.getKeyboardTheme(context, KeyboardThemeTarget.HARDWARE).symPageScale)

        val result = RestoreManager.restore(context, Uri.fromFile(zip))
        assertTrue(result.toString(), result is RestoreResult.Success)
        assertEquals(1.75f, SettingsManager.getKeyboardTheme(context, KeyboardThemeTarget.HARDWARE).symPageScale)
        assertEquals(0.75f, SettingsManager.getKeyboardTheme(context, KeyboardThemeTarget.SOFTWARE).symPageScale)
        assertEquals(hardware, SettingsManager.getSavedKeyboardThemes(context).single().theme)
    }

    @Test
    fun presetsCarryTheSymPageSize() {
        val preset = keyboardThemePresets().first().copy(symPageScale = 1.5f)
        assertEquals(1.5f, preset.toKeyboardThemeColors().symPageScale)
        assertEquals(1.5f, preset.toSettingsTheme().symPageScale)
        assertEquals(1.5f, preset.toSettingsTheme().toKeyboardThemeColors().symPageScale)
        assertEquals(1.5f, preset.toSettingsTheme().toKeyboardThemePreset("Copy").symPageScale)
        assertEquals(1f, keyboardThemePresets().map { it.symPageScale }.distinct().single())
    }

    @Test
    @Config(qualifiers = "en")
    fun searchFindsTheSymPageSizeOnTheHardwareThemeKeysTab() {
        for (query in listOf("SYM page size", "sym page", "SYM size")) {
            val entry = SettingLinkRegistry.search(context, query).first { it.id == "keyboard_theme.hardware.sym_page_size" }
            assertEquals(SettingsDestination.Customization, entry.route.destination)
            assertEquals("keyboard_theme", entry.route.customizationDestination)
            assertEquals(KeyboardThemeTarget.HARDWARE, entry.route.keyboardThemeTarget)
            assertEquals(KeyboardThemeEditorTab.Keys, entry.route.keyboardThemeTab)
        }
        assertEquals(SettingLinkRegistry.byId("keyboard_theme.hardware.sym_page_size"),
            SettingLinkRegistry.byId("keyboard_theme.hardware.sym_page_size")?.let { SettingLinkRegistry.visibleTarget(context, it) })
    }
}
