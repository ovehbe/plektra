package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import java.lang.reflect.Proxy

/**
 * Replay test using exact event sequence from device log (26.10.3 a088e9f6).
 * 
 * Device behavior: Every physical Alt/Shift press arrives TWICE:
 * 1. Real event: deviceId=0, flags=0x8 (FLAG_FROM_SYSTEM), real ~100-180ms duration
 * 2. Synthetic copy: deviceId=-1 (VIRTUAL_KEYBOARD), flags=0x0, metaState=0x0, 
 *    eventTime==downTime (zero duration), arriving ~13-20ms after real UP
 * 
 * This caused ModifierStateController to count real+copy as double-tap → latch/CAPS.
 * Expected behavior after fix: synthetic duplicates are filtered at entry point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class SyntheticModifierDuplicateReplayTest {

    private fun createService(context: Context): PhysicalKeyboardInputMethodService {
        val controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
        controller.create()
        return controller.get()
    }

    private fun createMockInputConnection(): InputConnection {
        return Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "commitText" -> true
                "getTextBeforeCursor" -> ""
                "getTextAfterCursor" -> ""
                else -> when {
                    method.returnType == Boolean::class.javaPrimitiveType -> false
                    method.returnType == Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
        } as InputConnection
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found")
    }

    private fun modifierController(service: PhysicalKeyboardInputMethodService): rocks.pkb.plektra.core.ModifierStateController {
        val field = PhysicalKeyboardInputMethodService::class.java.getDeclaredField("modifierStateController")
        field.isAccessible = true
        return field.get(service) as rocks.pkb.plektra.core.ModifierStateController
    }

    /**
     * Real Alt press from device log entries [1]-[4]:
     * - Real DOWN: deviceId=0, flags=0x8, metaState=0x12, eventTime=11214651
     * - Real UP: deviceId=0, flags=0x8, metaState=0x0, eventTime=11214801 (150ms duration)
     * - Synthetic DOWN: deviceId=-1, flags=0x0, metaState=0x0, eventTime=11214814 (~13ms after real UP)
     * - Synthetic UP: deviceId=-1, flags=0x0, metaState=0x0, eventTime=11214814 (zero duration)
     * 
     * WITHOUT fix: real+copy counted as double-tap → latch
     * WITH fix: synthetic copy filtered → single tap gives one-shot
     */
    @Test
    fun alt_realPlusSyntheticCopy_shouldGiveOneShot_notLatch() {
        val context: Context = RuntimeEnvironment.getApplication()
        SettingsManager.setStaticVariationBarLayerStickyEnabled(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        val service = createService(context)
        
        // Real Alt press (deviceId=0, flags=0x8)
        val realDown = KeyEvent(
            11214651L, // downTime
            11214651L, // eventTime
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_ALT_LEFT,
            0, // repeatCount
            0x12, // metaState (META_ALT_LEFT_ON | META_ALT_ON)
            0, // deviceId
            56, // scanCode
            0x8, // flags (FLAG_FROM_SYSTEM)
            0x101 // source (SOURCE_KEYBOARD | SOURCE_CLASS_BUTTON)
        )
        val realUp = KeyEvent(
            11214651L, // downTime
            11214801L, // eventTime (150ms later)
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_ALT_LEFT,
            0,
            0x0, // metaState cleared
            0,
            56,
            0x8,
            0x101
        )
        
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, realDown)
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, realUp)
        
        val mod = modifierController(service)
        assertTrue("After real press, Alt should be one-shot", mod.altOneShot)
        assertFalse("After real press, Alt should NOT be latched", mod.altLatchActive)
        
        // Synthetic copy (deviceId=-1, flags=0x0, zero duration, ~13ms after real UP)
        val syntheticDown = KeyEvent(
            11214814L, // downTime == eventTime (zero duration!)
            11214814L, // eventTime (~13ms after real UP)
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_ALT_LEFT,
            0,
            0x0, // metaState cleared
            -1, // deviceId (VIRTUAL_KEYBOARD)
            56,
            0x0, // flags=0 (no FLAG_FROM_SYSTEM)
            0x101
        )
        val syntheticUp = KeyEvent(
            11214814L, // downTime == eventTime
            11214814L, // eventTime (same as down)
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_ALT_LEFT,
            0,
            0x0,
            -1,
            56,
            0x0,
            0x101
        )
        
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, syntheticDown)
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, syntheticUp)
        
        // After fix: synthetic copy should be filtered, so Alt remains one-shot (not latched)
        assertTrue("After synthetic copy, Alt should still be one-shot", mod.altOneShot)
        assertFalse("After synthetic copy, Alt should NOT be latched", mod.altLatchActive)
    }

    /**
     * Real Shift press from device log entries [21]-[24]:
     * - Real DOWN: deviceId=0, flags=0x8, metaState=0x41, eventTime=11282093
     * - Real UP: deviceId=0, flags=0x8, metaState=0x0, eventTime=11282214 (121ms duration)
     * - Synthetic DOWN: deviceId=-1, flags=0x0, metaState=0x0, eventTime=11282234 (~20ms after real UP)
     * - Synthetic UP: deviceId=-1, flags=0x0, metaState=0x0, eventTime=11282234 (zero duration)
     * 
     * WITHOUT fix: real+copy counted as double-tap → CAPS
     * WITH fix: synthetic copy filtered → single tap gives one-shot
     */
    @Test
    fun shift_realPlusSyntheticCopy_shouldGiveOneShot_notCaps() {
        val context: Context = RuntimeEnvironment.getApplication()
        SettingsManager.setStaticVariationBarLayerStickyEnabled(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        val service = createService(context)
        
        // Real Shift press (deviceId=0, flags=0x8)
        val realDown = KeyEvent(
            11282093L,
            11282093L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            0,
            0x41, // metaState (META_SHIFT_LEFT_ON | META_SHIFT_ON)
            0,
            42, // scanCode
            0x8,
            0x101
        )
        val realUp = KeyEvent(
            11282093L,
            11282214L, // 121ms later
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            0,
            0x0,
            0,
            42,
            0x8,
            0x101
        )
        
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, realDown)
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, realUp)
        
        val mod = modifierController(service)
        assertTrue("After real press, Shift should be one-shot", mod.shiftOneShot)
        assertFalse("After real press, Shift should NOT be CAPS", mod.capsLockEnabled)
        
        // Synthetic copy (deviceId=-1, flags=0x0, zero duration, ~20ms after real UP)
        val syntheticDown = KeyEvent(
            11282234L,
            11282234L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            0,
            0x0,
            -1,
            42,
            0x0,
            0x101
        )
        val syntheticUp = KeyEvent(
            11282234L,
            11282234L,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            0,
            0x0,
            -1,
            42,
            0x0,
            0x101
        )
        
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, syntheticDown)
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, syntheticUp)
        
        // After fix: synthetic copy should be filtered, so Shift remains one-shot (not CAPS)
        assertTrue("After synthetic copy, Shift should still be one-shot", mod.shiftOneShot)
        assertFalse("After synthetic copy, Shift should NOT be CAPS", mod.capsLockEnabled)
    }

    /**
     * Synthetic copy arriving BEFORE real press (entries [5]-[6] in log):
     * - Synthetic DOWN: deviceId=-1, flags=0x0, eventTime=11217947
     * - Synthetic UP: deviceId=-1, flags=0x0, eventTime=11217947
     * - (No corresponding real event logged - may have gone through unlogged path)
     * 
     * Expected: synthetic event without recent real event should NOT be filtered (it's alone)
     */
    @Test
    fun alt_syntheticAlone_shouldGiveOneShot() {
        val context: Context = RuntimeEnvironment.getApplication()
        SettingsManager.setStaticVariationBarLayerStickyEnabled(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        val service = createService(context)
        
        // Synthetic event without preceding real event
        val syntheticDown = KeyEvent(
            11217947L,
            11217947L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_ALT_LEFT,
            0,
            0x0,
            -1,
            56,
            0x0,
            0x101
        )
        val syntheticUp = KeyEvent(
            11217947L,
            11217947L,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_ALT_LEFT,
            0,
            0x0,
            -1,
            56,
            0x0,
            0x101
        )
        
        service.onKeyDown(KeyEvent.KEYCODE_ALT_LEFT, syntheticDown)
        service.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT, syntheticUp)
        
        val mod = modifierController(service)
        assertTrue("Synthetic event alone should give one-shot", mod.altOneShot)
        assertFalse("Synthetic event alone should NOT latch", mod.altLatchActive)
    }
}
