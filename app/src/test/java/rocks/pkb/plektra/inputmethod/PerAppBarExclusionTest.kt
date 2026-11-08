package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager

/**
 * Tests for per-app bar exclusion feature.
 * 
 * Verifies that:
 * - Excluded apps list can be stored and retrieved
 * - Settings persist across sessions
 * - Empty default behavior
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PerAppBarExclusionTest {
    
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    
    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        prefs = SettingsManager.getPreferences(context)
        // Clear any existing excluded apps
        prefs.edit().remove("bar_excluded_apps").apply()
    }
    
    @Test
    fun testDefaultExcludedAppsIsEmpty() {
        val excludedApps = SettingsManager.getBarExcludedApps(context)
        
        assertTrue("Default excluded apps should be empty", excludedApps.isEmpty())
    }
    
    @Test
    fun testSetAndGetSingleExcludedApp() {
        val testPackage = "com.example.testapp"
        val excludedApps = setOf(testPackage)
        
        SettingsManager.setBarExcludedApps(context, excludedApps)
        
        val retrieved = SettingsManager.getBarExcludedApps(context)
        assertEquals("Should retrieve the same excluded app", excludedApps, retrieved)
        assertTrue("Should contain test package", retrieved.contains(testPackage))
    }
    
    @Test
    fun testSetAndGetMultipleExcludedApps() {
        val testPackages = setOf(
            "com.example.app1",
            "com.example.app2",
            "com.example.app3"
        )
        
        SettingsManager.setBarExcludedApps(context, testPackages)
        
        val retrieved = SettingsManager.getBarExcludedApps(context)
        assertEquals("Should retrieve all excluded apps", testPackages, retrieved)
        assertEquals("Should have 3 apps", 3, retrieved.size)
    }
    
    @Test
    fun testUpdateExcludedApps() {
        val initialApps = setOf("com.example.app1", "com.example.app2")
        SettingsManager.setBarExcludedApps(context, initialApps)
        
        val updatedApps = setOf("com.example.app1", "com.example.app3")
        SettingsManager.setBarExcludedApps(context, updatedApps)
        
        val retrieved = SettingsManager.getBarExcludedApps(context)
        assertEquals("Should have updated list", updatedApps, retrieved)
        assertTrue("Should contain app1", retrieved.contains("com.example.app1"))
        assertTrue("Should contain app3", retrieved.contains("com.example.app3"))
        assertFalse("Should not contain app2", retrieved.contains("com.example.app2"))
    }
    
    @Test
    fun testClearExcludedApps() {
        val testPackages = setOf("com.example.app1", "com.example.app2")
        SettingsManager.setBarExcludedApps(context, testPackages)
        
        // Verify it was set
        val retrieved = SettingsManager.getBarExcludedApps(context)
        assertEquals("Should have 2 apps", 2, retrieved.size)
        
        // Clear by setting empty set
        SettingsManager.setBarExcludedApps(context, emptySet())
        
        val cleared = SettingsManager.getBarExcludedApps(context)
        assertTrue("Should be empty after clearing", cleared.isEmpty())
    }
    
    @Test
    fun testExcludedAppsPersistedAcrossReads() {
        val testPackages = setOf("com.example.app1", "com.example.app2")
        SettingsManager.setBarExcludedApps(context, testPackages)
        
        // Read multiple times
        val read1 = SettingsManager.getBarExcludedApps(context)
        val read2 = SettingsManager.getBarExcludedApps(context)
        val read3 = SettingsManager.getBarExcludedApps(context)
        
        assertEquals("Should be consistent across reads", testPackages, read1)
        assertEquals("Should be consistent across reads", testPackages, read2)
        assertEquals("Should be consistent across reads", testPackages, read3)
    }
    
    @Test
    fun testEmptyStringSetIsHandledCorrectly() {
        // Set empty set explicitly
        SettingsManager.setBarExcludedApps(context, emptySet())
        
        val retrieved = SettingsManager.getBarExcludedApps(context)
        assertNotNull("Should return non-null set", retrieved)
        assertTrue("Should be empty", retrieved.isEmpty())
    }
    
    @Test
    fun testPackageNameWithSpecialCharacters() {
        val testPackages = setOf(
            "com.example.test-app",
            "com.example.test_app",
            "com.example.test.app.123"
        )
        
        SettingsManager.setBarExcludedApps(context, testPackages)
        
        val retrieved = SettingsManager.getBarExcludedApps(context)
        assertEquals("Should handle special characters", testPackages, retrieved)
    }
}
