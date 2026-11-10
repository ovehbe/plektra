package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.text.InputType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.AltModifierBinding
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationHandler
import junit.framework.TestCase.assertEquals

/**
 * Test for Shift one-shot bug on Q25: Shift tap latches even though "Shift tap latches" is OFF.
 * 
 * Test matrix:
 * - Setting OFF: tap Shift then A A (only first should be uppercase)
 * - Setting OFF: hold Shift with A A (both uppercase)
 * - Setting OFF: double-tap (should NOT lock)
 * - Setting OFF: tap Shift then Space then A (A should be lowercase)
 * - Setting ON: tap Shift then A A (both uppercase - latch behavior)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShiftOneShotBugTest {

    data class TestScenario(
        val name: String,
        val setting: Boolean, // false = OFF (one-shot), true = ON (latch)
        val action: String, // "tap", "hold", "double-tap", "tap_space"
        val expectedResults: List<String> // Expected output for each A press
    )

    @Test
    fun shiftOneShotConsumedByAnyKey() {
        val scenarios = listOf(
            // OFF scenarios - one-shot should be consumed after first key
            TestScenario("OFF_tap_then_A_A", false, "tap", listOf("A", "a")),
            TestScenario("OFF_hold_then_A_A", false, "hold", listOf("A", "A")),
            TestScenario("OFF_double_tap_then_A", false, "double-tap", listOf("a")), // No lock with setting OFF
            TestScenario("OFF_tap_Space_A", false, "tap_space", listOf("a")),
            
            // ON scenarios - latch should persist
            TestScenario("ON_tap_then_A_A", true, "tap", listOf("A", "A"))
        )
        
        val results = mutableListOf<TestResult>()
        
        println("\n" + "=".repeat(80))
        println("SHIFT ONE-SHOT BUG TEST MATRIX")
        println("Testing: Shift one-shot must be consumed by next key, not latch")
        println("Total scenarios: ${scenarios.size}")
        println("=".repeat(80))
        
        for ((index, scenario) in scenarios.withIndex()) {
            println("\n[${index + 1}/${scenarios.size}] ${scenario.name}")
            
            try {
                val result = runScenario(scenario)
                results.add(result)
                
                val status = if (result.passed) "✓ PASS" else "✗ FAIL"
                println("  $status: ${result.message}")
                
                if (!result.passed) {
                    println("  Expected: ${scenario.expectedResults}")
                    println("  Actual:   ${result.actualResults}")
                }
            } catch (e: Exception) {
                println("  ✗ ERROR: ${e.message}")
                e.printStackTrace()
                results.add(TestResult(
                    scenario = scenario,
                    passed = false,
                    message = "Exception: ${e.message}",
                    actualResults = listOf("ERROR")
                ))
            }
        }
        
        val passed = results.count { it.passed }
        val failed = results.count { !it.passed }
        
        println("\n" + "=".repeat(80))
        println("TEST SUMMARY")
        println("=".repeat(80))
        println("Total:  ${results.size}")
        println("Passed: $passed")
        println("Failed: $failed")
        
        if (failed > 0) {
            println("\nFAILED SCENARIOS:")
            results.filter { !it.passed }.forEach {
                println("  - ${it.scenario.name}")
                println("    Expected: ${it.scenario.expectedResults}")
                println("    Got: ${it.actualResults}")
            }
        }
        
        println("=".repeat(80))
        
        assertEquals("All Shift one-shot scenarios must pass", 0, failed)
    }

    private data class TestResult(
        val scenario: TestScenario,
        val passed: Boolean,
        val message: String,
        val actualResults: List<String>
    )

    private fun runScenario(scenario: TestScenario): TestResult {
        val context = RuntimeEnvironment.getApplication()
        
        // Configure setting
        val prefs = context.getSharedPreferences("plektra_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("shift_tap_latches", scenario.setting).apply()
        
        // Setup Q25 profile
        DeviceSpecific.clearTestOverrides()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        
        // Create service
        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
            .create()
            .get()
        
        // Setup recording InputConnection
        val recorder = RecordingInputConnection()
        val inputConnection = recorder.asProxy()
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "rocks.pkb.plektra.test"
        }
        
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))
        
        val downTime = System.currentTimeMillis()
        
        when (scenario.action) {
            "tap" -> {
                // Tap Shift
                sendKey(service, KeyEvent.KEYCODE_SHIFT_LEFT, downTime, downTime)
                Thread.sleep(50)
                // Press A twice
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 100, downTime + 100)
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 200, downTime + 200)
            }
            "hold" -> {
                // Hold Shift (down only)
                val shiftDown = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0)
                service.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, shiftDown)
                // Press A twice while holding
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 50, downTime + 50)
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 100, downTime + 100)
                // Release Shift
                val shiftUp = KeyEvent(downTime, downTime + 150, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0)
                service.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT, shiftUp)
            }
            "double-tap" -> {
                // Double-tap Shift (should NOT lock with setting OFF)
                sendKey(service, KeyEvent.KEYCODE_SHIFT_LEFT, downTime, downTime)
                Thread.sleep(50)
                sendKey(service, KeyEvent.KEYCODE_SHIFT_LEFT, downTime + 100, downTime + 100)
                Thread.sleep(50)
                // Press A
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 200, downTime + 200)
            }
            "tap_space" -> {
                // Tap Shift, then Space, then A
                sendKey(service, KeyEvent.KEYCODE_SHIFT_LEFT, downTime, downTime)
                Thread.sleep(50)
                sendKey(service, KeyEvent.KEYCODE_SPACE, downTime + 100, downTime + 100)
                sendKey(service, KeyEvent.KEYCODE_A, downTime + 200, downTime + 200)
            }
        }
        
        val actualResults = recorder.committedTexts.toList()
        val passed = actualResults == scenario.expectedResults
        
        val message = if (passed) {
            when {
                scenario.setting -> "Shift latch working correctly"
                scenario.action == "hold" -> "Hold Shift working correctly"
                scenario.action == "double-tap" -> "Double-tap correctly did not lock"
                else -> "Shift one-shot consumed correctly"
            }
        } else {
            "Shift behavior incorrect"
        }
        
        return TestResult(
            scenario = scenario,
            passed = passed,
            message = message,
            actualResults = actualResults
        )
    }

    private fun sendKey(service: PhysicalKeyboardInputMethodService, keyCode: Int, downTime: Long, eventTime: Long) {
        val down = KeyEvent(downTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, 0)
        service.onKeyDown(keyCode, down)
        val up = KeyEvent(downTime, eventTime + 50, KeyEvent.ACTION_UP, keyCode, 0, 0)
        service.onKeyUp(keyCode, up)
    }

    private fun setField(target: Any, fieldName: String, value: Any?) {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val field: Field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found")
    }

    private class RecordingInputConnection {
        var textBeforeCursor: String = ""
        val committedTexts = mutableListOf<String>()
        val sentKeyEvents = mutableListOf<KeyEvent>()

        fun asProxy(): InputConnection {
            return Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java),
                InvocationHandler { _, method, args ->
                when (method.name) {
                    "commitText" -> {
                        val text = args?.getOrNull(0)?.toString()
                        if (text != null) {
                            committedTexts += text
                        }
                        true
                    }
                    "sendKeyEvent" -> {
                        val event = args?.getOrNull(0) as? KeyEvent
                        if (event != null) {
                            sentKeyEvents += event
                        }
                        true
                    }
                    "getTextBeforeCursor" -> textBeforeCursor
                    "getTextAfterCursor" -> ""
                    "deleteSurroundingText" -> true
                    "setComposingText" -> true
                    "finishComposingText" -> true
                    "beginBatchEdit" -> true
                    "endBatchEdit" -> true
                    "getCursorCapsMode" -> 0
                    "getExtractedText" -> null
                    "getSelectedText" -> null
                    "setSelection" -> true
                    "performEditorAction" -> true
                    "performContextMenuAction" -> true
                    "clearMetaKeyStates" -> true
                    "reportFullscreenMode" -> true
                    "closeConnection" -> null
                    "toString" -> "RecordingInputConnection"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> (args?.getOrNull(0) === this)
                    else -> null
                }
            }) as InputConnection
        }
    }
}
