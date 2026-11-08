package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import rocks.pkb.plektra.core.ModifierStateController

/**
 * Production tests for Alt modifier state machine behavior.
 * 
 * Tests exercise actual ModifierKeyHandler logic through ModifierStateController
 * to verify one-shot vs persistent latch behavior with real event sequences.
 */
class AltModifierProductionTest {
    
    @Test
    fun testAltTapDefault_enablesOneShot_notLatch() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.altTapLatches = false // Default setting
        
        val now = System.currentTimeMillis()
        
        // Simulate Alt key down
        val downResult = controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now)
        
        // Simulate Alt key up (completes the tap)
        val upResult = controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        
        // VERIFY: First tap with altTapLatches=false should enable one-shot, NOT latch
        assertTrue("One-shot should be active after first tap", controller.altOneShot)
        assertFalse("Latch should NOT be active after first tap", controller.altLatchActive)
        
        assertTrue("Status bar should update", downResult.shouldUpdateStatusBar || upResult.shouldUpdateStatusBar)
    }
    
    @Test
    fun testAltDoubleTap_enablesLatch() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.altTapLatches = false
        
        val now = System.currentTimeMillis()
        
        // First tap
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        
        assertTrue("After first tap: one-shot active", controller.altOneShot)
        assertFalse("After first tap: latch NOT active", controller.altLatchActive)
        
        // Second tap within threshold (consecutive)
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now + 200)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 250)
        
        // VERIFY: Double-tap should enable persistent latch, clear one-shot
        assertFalse("After double-tap: one-shot cleared", controller.altOneShot)
        assertTrue("After double-tap: latch active", controller.altLatchActive)
    }
    
    @Test
    fun testAltSingleTapLatchesWhenSettingEnabled() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.altTapLatches = true // Enable single-tap latch setting
        
        val now = System.currentTimeMillis()
        
        // Single tap
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        
        // VERIFY: With setting ON, single tap should immediately enable latch
        assertFalse("One-shot should NOT be active", controller.altOneShot)
        assertTrue("Latch should be active immediately", controller.altLatchActive)
    }
    
    @Test
    fun testAltDoubleTapFailsWhenNonModifierKeyBetween() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.altTapLatches = false
        
        val now = System.currentTimeMillis()
        
        // First Alt tap
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        
        assertTrue("After first tap: one-shot active", controller.altOneShot)
        
        // User presses another key (breaks consecutive detection)
        controller.registerNonModifierKey()
        
        // Second Alt tap - NOT consecutive because non-modifier key was pressed
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now + 200)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 250)
        
        // VERIFY: Should NOT latch because tap wasn't consecutive
        // Instead, the one-shot from first tap is toggled OFF
        assertFalse("One-shot should be off", controller.altOneShot)
        assertFalse("Latch should NOT activate", controller.altLatchActive)
    }
    
    @Test
    fun testAltLatchToggleOff_byTappingAgain() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.altTapLatches = false
        
        val now = System.currentTimeMillis()
        
        // Double-tap to enable latch
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now + 200)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 250)
        
        assertTrue("Latch should be active", controller.altLatchActive)
        
        // Tap again to turn off
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now + 400)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 450)
        
        // VERIFY: Tapping when latch is active should turn it OFF
        assertFalse("Latch should be off", controller.altLatchActive)
        assertFalse("One-shot should be off", controller.altOneShot)
    }
}
