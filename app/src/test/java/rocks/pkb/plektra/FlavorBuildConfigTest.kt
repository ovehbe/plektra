package rocks.pkb.plektra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlavorBuildConfigTest {
    @Test
    fun channelIdentityMatchesBuildConfig() {
        assertEquals("pkb-rocks/plektra", BuildConfig.GITHUB_REPOSITORY)
        assertTrue(
            BuildConfig.RELEASE_CHANNEL == "stable" ||
                BuildConfig.RELEASE_CHANNEL == "nightly" ||
                BuildConfig.RELEASE_CHANNEL == "sideload"
        )
        assertTrue(BuildConfig.DISTRIBUTION_CHANNEL == "direct" || BuildConfig.DISTRIBUTION_CHANNEL == "play")

        if (BuildConfig.DISTRIBUTION_CHANNEL == "play") {
            assertFalse(BuildConfig.ENABLE_GITHUB_UPDATE_CHECKS)
            assertTrue(BuildConfig.APPLICATION_ID.contains(".unsovereign"))
        } else if (BuildConfig.RELEASE_CHANNEL == "sideload") {
            assertFalse(BuildConfig.ENABLE_GITHUB_UPDATE_CHECKS)
            assertFalse(BuildConfig.IS_FDROID_BUILD)
            assertTrue(BuildConfig.APPLICATION_ID.endsWith(".test"))
        } else {
            assertEquals(BuildConfig.IS_FDROID_BUILD, !BuildConfig.ENABLE_GITHUB_UPDATE_CHECKS)
        }

        if (BuildConfig.RELEASE_CHANNEL == "nightly") {
            assertTrue(BuildConfig.VERSION_NAME.contains("-nightly"))
            assertTrue(BuildConfig.APPLICATION_ID.endsWith(".nightly"))
        } else {
            assertFalse(BuildConfig.VERSION_NAME.contains("nightly"))
        }
    }
}
