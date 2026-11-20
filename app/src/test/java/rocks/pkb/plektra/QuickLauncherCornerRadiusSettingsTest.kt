package rocks.pkb.plektra

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.backup.BackupPreferenceContract
import rocks.pkb.plektra.backup.PreferenceSchemas
import rocks.pkb.plektra.backup.PreferenceValueType
import rocks.pkb.plektra.inputmethod.quickLauncherPanelCornerRadiusDp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherCornerRadiusSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetSettings() {
        SettingsManager.getPreferences(context).edit().clear().commit()
    }

    @Test
    fun defaultMatchesTheCurrentQuickLauncherPanelRadius() {
        assertEquals(16, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(
            16,
            quickLauncherPanelCornerRadiusDp(
                isCollapsedPill = false,
                configuredRadiusDp = SettingsManager.getQuickLauncherCornerRadiusDp(context)
            )
        )
    }

    @Test
    fun cornerRadiusPersistsAndDrivesThePanel() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, 24)

        assertEquals(24, SettingsManager.getQuickLauncherCornerRadiusDp(context))
        assertEquals(
            24,
            quickLauncherPanelCornerRadiusDp(
                isCollapsedPill = false,
                configuredRadiusDp = SettingsManager.getQuickLauncherCornerRadiusDp(context)
            )
        )
        assertEquals(
            24,
            SettingsManager.getPreferences(context).getInt("quick_launcher_corner_radius_dp", -1)
        )
    }

    @Test
    fun sliderRangeGoesFromSquareToClearlyMoreRounded() {
        assertEquals(0, SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP)
        assertTrue(
            "The maximum radius should be clearly more rounded than the 16 dp default",
            SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP > 16
        )
        assertEquals(
            0,
            quickLauncherPanelCornerRadiusDp(
                isCollapsedPill = false,
                configuredRadiusDp = SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP
            )
        )
        assertEquals(
            SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP,
            quickLauncherPanelCornerRadiusDp(
                isCollapsedPill = false,
                configuredRadiusDp = SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP
            )
        )
    }

    @Test
    fun settingClampsValuesToTheSliderRange() {
        SettingsManager.setQuickLauncherCornerRadiusDp(context, -10)
        assertEquals(
            SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP,
            SettingsManager.getQuickLauncherCornerRadiusDp(context)
        )

        SettingsManager.setQuickLauncherCornerRadiusDp(context, 999)
        assertEquals(
            SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP,
            SettingsManager.getQuickLauncherCornerRadiusDp(context)
        )
    }

    @Test
    fun outOfRangeStoredValuesAreClampedWhenRead() {
        SettingsManager.getPreferences(context).edit()
            .putInt("quick_launcher_corner_radius_dp", -4)
            .commit()
        assertEquals(
            SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MIN_DP,
            SettingsManager.getQuickLauncherCornerRadiusDp(context)
        )

        SettingsManager.getPreferences(context).edit()
            .putInt("quick_launcher_corner_radius_dp", 500)
            .commit()
        assertEquals(
            SettingsManager.QUICK_LAUNCHER_CORNER_RADIUS_MAX_DP,
            SettingsManager.getQuickLauncherCornerRadiusDp(context)
        )
    }

    @Test
    fun collapsedPillKeepsItsExistingShapeRegardlessOfTheSlider() {
        assertEquals(28, quickLauncherPanelCornerRadiusDp(isCollapsedPill = true, configuredRadiusDp = 0))
        assertEquals(28, quickLauncherPanelCornerRadiusDp(isCollapsedPill = true, configuredRadiusDp = 36))
    }

    @Test
    fun backupContractExportsCornerRadiusAsAnInt() {
        assertEquals(
            PreferenceValueType.INT,
            BackupPreferenceContract.expectedExportType(
                "plektra_prefs",
                "quick_launcher_corner_radius_dp"
            )
        )
        assertTrue(
            BackupPreferenceContract.isExportable(
                "plektra_prefs",
                "quick_launcher_corner_radius_dp"
            )
        )
        assertEquals(
            PreferenceValueType.INT,
            PreferenceSchemas.expectedType("plektra_prefs", "quick_launcher_corner_radius_dp")
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun cornerRadiusSettingIsSearchableByItsTitle() {
        val title = context.getString(R.string.quick_launcher_corner_radius_title)
        val results = SettingLinkRegistry.search(context, title)

        assertTrue(results.any { it.id == "quick_launcher.corner_radius" })
    }
}
