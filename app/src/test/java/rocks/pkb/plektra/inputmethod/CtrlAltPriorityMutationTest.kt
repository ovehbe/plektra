package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.NavModeController
import rocks.pkb.plektra.core.SymLayoutController
import rocks.pkb.plektra.core.TextInputController
import rocks.pkb.plektra.data.mappings.KeyMappingLoader
import rocks.pkb.plektra.inputmethod.AlternateCharacterManager

/**
 * Mutation test to prove Bug #2 fix: Ctrl shortcuts must beat Alt symbol mappings.
 * 
 * This test drives the REAL InputEventRouter to verify modifier priority ordering.
 * It must FAIL when Ctrl/Alt blocks are swapped back (RED), and PASS with correct order (GREEN).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CtrlAltPriorityMutationTest {

    @Test
    fun ctrlActiveWithAltMapping_shouldTriggerCtrlShortcut_notAltSymbol() {
        val context: Context = RuntimeEnvironment.getApplication()
        
        // Set up router and controllers
        val modifierStateController = ModifierStateController(500L)
        val navModeController = NavModeController(context, modifierStateController)
        val router = InputEventRouter(context, navModeController)
        val symLayoutController = SymLayoutController(context, {}, {}, {})
        val alternateCharacterManager = AlternateCharacterManager(context, symLayoutController)
        val textInputController = TextInputController(context, modifierStateController, 500L)
        
        // Create mock InputConnection that records actions
        val recorder = ContextMenuRecorder()
        val inputConnection = recorder.createProxy()
        
        // Simulate Ctrl+A: Ctrl is latched, then 'A' is pressed
        // Even if 'A' has an Alt mapping, Ctrl+A should execute SELECT_ALL
        modifierStateController.ctrlLatchActive = true
        
        val keyCode = KeyEvent.KEYCODE_A
        val event = KeyEvent(1000L, 1000L, KeyEvent.ACTION_DOWN, keyCode, 0, 0)
        
        val params = InputEventRouter.EditableFieldKeyDownHandlingParams(
            inputConnection = inputConnection,
            isNumericField = false,
            isInputViewActive = true,
            shiftPressed = false,
            shiftLayerLatched = false,
            ctrlPressed = false,
            ctrlPhysicallyPressed = false,
            altPressed = false,
            ctrlLatchActive = true,  // Ctrl is active (latched)
            altLatchActive = false,
            ctrlLatchFromNavMode = false,
            ctrlKeyMap = KeyMappingLoader.loadCtrlKeyMappings(context.assets, context),
            ctrlOneShot = false,
            altOneShot = false,
            clearAltOnSpaceEnabled = false,
            shiftOneShot = false,
            capsLockEnabled = false,
            cursorUpdateDelayMs = 0L,
            altMappingsOverride = null,
            shouldDisableSmartFeatures = false
        )
        
        val controllers = InputEventRouter.EditableFieldKeyDownControllers(
            modifierStateController = modifierStateController,
            symLayoutController = symLayoutController,
            alternateCharacterManager = alternateCharacterManager,
            variationStateController = rocks.pkb.plektra.core.VariationStateController(emptyList()),
            textInputController = textInputController
        )
        
        val callbacks = InputEventRouter.EditableFieldKeyDownHandlingCallbacks(
            updateStatusBar = {},
            refreshStatusBar = {},
            disableShiftOneShot = {},
            clearAltOneShot = {},
            clearCtrlOneShot = {},
            getCharacterFromLayout = { _, _, _ -> null },
            isAlphabeticKey = { false },
            callSuper = { false },
            callSuperWithKey = { _, _ -> false },
            startSpeechRecognition = {},
            getMapping = { null },
            handleMultiTapCommit = { _, _, _, _, _ -> false },
            isLongPressSuppressed = { false },
            toggleMinimalUi = {},
            handleBoundaryText = { _, _ -> false }
        )
        
        // Route the key event
        val result = router.routeEditableFieldKeyDown(keyCode, event, params, controllers, callbacks)
        
        // ASSERTION: With correct Ctrl > Alt priority, Ctrl+A triggers SELECT_ALL
        // If Alt comes first, it would commit an Alt symbol instead
        assertTrue(
            "Ctrl+A must trigger SELECT_ALL shortcut, not Alt symbol (Ctrl priority bug!)",
            recorder.performedActions.contains(android.R.id.selectAll)
        )
        
        assertFalse(
            "Ctrl+A must NOT commit Alt symbol text (Ctrl should prevent Alt handling)",
            recorder.committedText.isNotEmpty()
        )
    }
    
    private class ContextMenuRecorder {
        val performedActions = mutableListOf<Int>()
        val committedText = mutableListOf<String>()
        
        fun createProxy(): InputConnection {
            return java.lang.reflect.Proxy.newProxyInstance(
                InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "performContextMenuAction" -> {
                        val action = args?.getOrNull(0) as? Int
                        if (action != null) {
                            performedActions += action
                        }
                        true
                    }
                    "commitText" -> {
                        val text = args?.getOrNull(0)?.toString()
                        if (text != null) {
                            committedText += text
                        }
                        true
                    }
                    "getTextBeforeCursor" -> ""
                    "getExtractedText" -> android.view.inputmethod.ExtractedText().apply {
                        text = ""
                        selectionStart = 0
                        selectionEnd = 0
                    }
                    "beginBatchEdit", "endBatchEdit", "finishComposingText" -> true
                    else -> when {
                        method.returnType == Boolean::class.javaPrimitiveType -> false
                        method.returnType == Int::class.javaPrimitiveType -> 0
                        method.returnType == Long::class.javaPrimitiveType -> 0L
                        else -> null
                    }
                }
            } as InputConnection
        }
    }
}
