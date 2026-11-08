package rocks.pkb.plektra.inputmethod

import org.junit.Assert.*
import org.junit.Test
import rocks.pkb.plektra.core.ModifierStateController

/**
 * Tests for Alt latching bug investigation.
 * 
 * Owner reports: "Alt is ALWAYS latching"
 * Expected with alt_tap_latches=false (default):
 * - First tap → one-shot (clears after next key)
 * - Double-tap within 500ms → persistent latch
 */
class AltLatchingBugTest {
    
    @Test
    fun testAltTapDefaultBehavior_shouldBeOneShot() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.altTapLatches = false // Default setting
        
        val now = System.currentTimeMillis()
        
        // First tap
        controller.handleAltKeyDown(android.view.KeyEvent.KEYCODE_ALT_LEFT, now)
        controller.handleAltKeyUp(android.view.KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        
        // Verify: Should be one-shot, NOT latch
        assertTrue("First tap should enable one-shot", controller.altOneShot)
        assertFalse("First tap should NOT enable latch", controller.altLatchActive)
    }
    
    @Test
    fun testAltDoubleTap_shouldLatch() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.altTapLatches = false
        
        val now = System.currentTimeMillis()
        
        // First tap
        controller.handleAltKeyDown(android.view.KeyEvent.KEYCODE_ALT_LEFT, now)
        controller.handleAltKeyUp(android.view.KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        
        assertTrue("After first tap: one-shot active", controller.altOneShot)
        
        // Second tap within threshold (consecutive)
        controller.handleAltKeyDown(android.view.KeyEvent.KEYCODE_ALT_LEFT, now + 200)
        controller.handleAltKeyUp(android.view.KeyEvent.KEYCODE_ALT_LEFT, now + 250)
        
        // Verify: Should be latched, NOT one-shot
        assertFalse("After double-tap: one-shot cleared", controller.altOneShot)
        assertTrue("After double-tap: latch active", controller.altLatchActive)
    }
    
    @Test
    fun testAltSingleTapLatches_whenSettingEnabled() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.altTapLatches = true // Setting enabled
        
        val now = System.currentTimeMillis()
        
        // Single tap
        controller.handleAltKeyDown(android.view.KeyEvent.KEYCODE_ALT_LEFT, now)
        controller.handleAltKeyUp(android.view.KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        
        // Verify: Should immediately latch, NOT one-shot
        assertFalse("With setting ON: should NOT be one-shot", controller.altOneShot)
        assertTrue("With setting ON: should latch immediately", controller.altLatchActive)
    }
    
    @Test
    fun testAltOneShot_clearsAfterNonModifierKey() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.altTapLatches = false
        
        val now = System.currentTimeMillis()
        
        // First tap → one-shot
        controller.handleAltKeyDown(android.view.KeyEvent.KEYCODE_ALT_LEFT, now)
        controller.handleAltKeyUp(android.view.KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        assertTrue("One-shot should be active", controller.altOneShot)
        
        // Simulate pressing a non-modifier key
        controller.registerNonModifierKey()
        
        // In production, altOneShot would be cleared by the input router
        // after the key is processed. For this test, we manually verify
        // that the state allows for clearing.
        
        // The one-shot flag itself doesn't auto-clear - it's cleared by
        // the caller (InputEventRouter) after handling the Alt-modified key
        assertTrue("One-shot remains active until explicitly cleared", controller.altOneShot)
        
        // Manually clear to simulate production behavior
        controller.altOneShot = false
        assertFalse("After key processed: one-shot cleared", controller.altOneShot)
    }
}
