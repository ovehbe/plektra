package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import rocks.pkb.plektra.core.ModifierStateController

/**
 * Integration tests for Ctrl+Alt priority fix.
 * 
 * These tests verify the modifier state logic that feeds into InputEventRouter.
 * With the fix, when both Ctrl and Alt are active, Ctrl shortcuts should take
 * priority over Alt symbol substitution.
 * 
 * Full end-to-end verification requires running on device with actual InputConnection.
 */
class CtrlAltPriorityIntegrationTest {
    
    @Test
    fun testModifierState_bothCtrlAndAltActive() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        
        val now = System.currentTimeMillis()
        
        // Activate Alt (latch)
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now + 200)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 250)
        
        assertTrue("Alt should be latched", controller.altLatchActive)
        
        // Activate Ctrl (latch)
        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, true, now + 400)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now + 450)
        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, true, now + 600)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now + 650)
        
        assertTrue("Ctrl should be latched", controller.ctrlLatchActive)
        
        // VERIFY: Both modifiers can be active simultaneously
        assertTrue("Both Ctrl and Alt should be active", 
            controller.ctrlLatchActive && controller.altLatchActive)
        
        // With the fix in InputEventRouter, when both are active,
        // Ctrl handling is checked FIRST (before Alt), so Ctrl+A
        // will trigger select-all instead of Alt+A producing '*'
    }
    
    @Test
    fun testModifierState_ctrlOneShotWithAltLatch() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.ctrlTapLatches = false
        
        val now = System.currentTimeMillis()
        
        // Alt double-tap to latch
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 50)
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now + 200)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 250)
        
        assertTrue("Alt latched", controller.altLatchActive)
        
        // Ctrl single tap (one-shot)
        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, true, now + 400)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now + 450)
        
        assertTrue("Ctrl one-shot active", controller.ctrlOneShot)
        assertTrue("Alt still latched", controller.altLatchActive)
        
        // With fix: Ctrl one-shot + Alt latch means Ctrl+A should work
    }
    
    @Test
    fun testModifierState_altOneShotWithCtrlLatch() {
        val controller = ModifierStateController(doubleTapThreshold = 500L)
        controller.altTapLatches = false
        
        val now = System.currentTimeMillis()
        
        // Ctrl double-tap to latch
        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, true, now)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now + 50)
        controller.handleCtrlKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, true, now + 200)
        controller.handleCtrlKeyUp(KeyEvent.KEYCODE_CTRL_LEFT, now + 250)
        
        assertTrue("Ctrl latched", controller.ctrlLatchActive)
        
        // Alt single tap (one-shot)
        controller.handleAltKeyDown(KeyEvent.KEYCODE_ALT_LEFT, now + 400)
        controller.handleAltKeyUp(KeyEvent.KEYCODE_ALT_LEFT, now + 450)
        
        assertTrue("Alt one-shot active", controller.altOneShot)
        assertTrue("Ctrl still latched", controller.ctrlLatchActive)
        
        // With fix: Ctrl latch + Alt one-shot means Ctrl+C should work
    }
}
