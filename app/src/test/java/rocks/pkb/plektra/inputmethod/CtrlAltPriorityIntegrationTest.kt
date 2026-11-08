package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.SymLayoutController
import rocks.pkb.plektra.core.TextInputController
import rocks.pkb.plektra.core.VariationStateController
import rocks.pkb.plektra.data.mappings.KeyMappingLoader
import rocks.pkb.plektra.inputmethod.AlternateCharacterManager

/**
 * Integration tests for Ctrl+Alt priority fix.
 * 
 * Tests verify that Ctrl shortcuts (Ctrl+A, Ctrl+C, Ctrl+V) work correctly
 * when Alt is active (latched or one-shot), by actually invoking production
 * InputEventRouter with real modifier states and verifying InputConnection calls.
 * 
 * RED (baseline): With original code, Alt is checked first and consumes events
 * GREEN (fixed): With reordered code, Ctrl is checked first and shortcuts work
 */
@RunWith(RobolectricTestRunner::class)
class CtrlAltPriorityIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var inputConnection: InputConnection
    private lateinit var router: InputEventRouter
    private lateinit var modifierStateController: ModifierStateController
    private lateinit var symLayoutController: SymLayoutController
    private lateinit var alternateCharacterManager: AlternateCharacterManager
    private lateinit var variationStateController: VariationStateController
    private lateinit var textInputController: TextInputController
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        inputConnection = mock(InputConnection::class.java)
        router = InputEventRouter(context)
        
        // Initialize real production controllers
        modifierStateController = ModifierStateController(doubleTapThreshold = 500L)
        symLayoutController = SymLayoutController()
        alternateCharacterManager = AlternateCharacterManager(context)
        variationStateController = VariationStateController(context)
        textInputController = TextInputController(
            context = context,
            doubleTapThreshold = 500L
        )
        
        // Load Q25 device sym mappings (Alt+A → '*')
        alternateCharacterManager.reloadAltMappings(
            altModifierBinding = rocks.pkb.plektra.AltModifierBinding.DeviceSymProfile("Q25")
        )
    }
    
    @Test
    fun testCtrlA_withAltLatched_shouldCallSelectAll() {
        // Setup: Alt is latched (persistent lock)
        modifierStateController.altLatchActive = true
        
        // Create Ctrl key mapping
        val ctrlKeyMap = mapOf(
            KeyEvent.KEYCODE_A to KeyMappingLoader.CtrlMapping("action", "select_all")
        )
        
        // Create event for 'A' key
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        
        // Route the key with both Ctrl and Alt active
        val params = InputEventRouter.EditableFieldKeyDownHandlingParams(
            inputConnection = inputConnection,
            isNumericField = false,
            isInputViewActive = true,
            shiftPressed = false,
            shiftLayerLatched = false,
            ctrlPressed = true,  // Ctrl is active
            ctrlPhysicallyPressed = true,
            altPressed = false,
            ctrlLatchActive = false,
            altLatchActive = true,  // Alt is latched
            ctrlLatchFromNavMode = false,
            ctrlKeyMap = ctrlKeyMap,
            ctrlOneShot = false,
            altOneShot = false,
            clearAltOnSpaceEnabled = true,
            shiftOneShot = false,
            capsLockEnabled = false,
            cursorUpdateDelayMs = 0L
        )
        
        val controllers = InputEventRouter.EditableFieldKeyDownControllers(
            modifierStateController = modifierStateController,
            symLayoutController = symLayoutController,
            alternateCharacterManager = alternateCharacterManager,
            variationStateController = variationStateController,
            textInputController = textInputController
        )
        
        var statusBarUpdated = false
        val callbacks = InputEventRouter.EditableFieldKeyDownHandlingCallbacks(
            updateStatusBar = { statusBarUpdated = true },
            refreshStatusBar = { },
            disableShiftOneShot = { },
            clearAltOneShot = { },
            clearCtrlOneShot = { },
            getCharacterFromLayout = { _, _, _ -> null },
            isAlphabeticKey = { false },
            callSuper = { false },
            callSuperWithKey = { _, _ -> false },
            startSpeechRecognition = { },
            getMapping = { null },
            handleMultiTapCommit = { _, _, _, _, _ -> false },
            isLongPressSuppressed = { false },
            toggleMinimalUi = { }
        )
        
        // Execute routing
        val result = router.routeEditableFieldKeyDown(
            keyCode = KeyEvent.KEYCODE_A,
            event = event,
            params = params,
            controllers = controllers,
            callbacks = callbacks
        )
        
        // VERIFY: Should call performContextMenuAction with selectAll ID
        // With the fix, Ctrl is checked FIRST, so select-all action is triggered
        // Without the fix, Alt would be checked first and commit '*' character
        verify(inputConnection).performContextMenuAction(android.R.id.selectAll)
        
        // Should NOT commit Alt symbol
        verify(inputConnection, never()).commitText(eq("*"), anyInt())
        
        assertEquals(InputEventRouter.EditableFieldRoutingResult.Consume, result)
    }
    
    @Test
    fun testCtrlC_withAltOneShot_shouldCallCopy() {
        // Setup: Alt is one-shot (active for next key)
        modifierStateController.altOneShot = true
        
        val ctrlKeyMap = mapOf(
            KeyEvent.KEYCODE_C to KeyMappingLoader.CtrlMapping("action", "copy")
        )
        
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C)
        
        val params = InputEventRouter.EditableFieldKeyDownHandlingParams(
            inputConnection = inputConnection,
            isNumericField = false,
            isInputViewActive = true,
            shiftPressed = false,
            shiftLayerLatched = false,
            ctrlPressed = true,
            ctrlPhysicallyPressed = true,
            altPressed = false,
            ctrlLatchActive = false,
            altLatchActive = false,
            ctrlLatchFromNavMode = false,
            ctrlKeyMap = ctrlKeyMap,
            ctrlOneShot = false,
            altOneShot = true,  // Alt one-shot active
            clearAltOnSpaceEnabled = true,
            shiftOneShot = false,
            capsLockEnabled = false,
            cursorUpdateDelayMs = 0L
        )
        
        val controllers = InputEventRouter.EditableFieldKeyDownControllers(
            modifierStateController = modifierStateController,
            symLayoutController = symLayoutController,
            alternateCharacterManager = alternateCharacterManager,
            variationStateController = variationStateController,
            textInputController = textInputController
        )
        
        val callbacks = InputEventRouter.EditableFieldKeyDownHandlingCallbacks(
            updateStatusBar = { },
            refreshStatusBar = { },
            disableShiftOneShot = { },
            clearAltOneShot = { },
            clearCtrlOneShot = { },
            getCharacterFromLayout = { _, _, _ -> null },
            isAlphabeticKey = { false },
            callSuper = { false },
            callSuperWithKey = { _, _ -> false },
            startSpeechRecognition = { },
            getMapping = { null },
            handleMultiTapCommit = { _, _, _, _, _ -> false },
            isLongPressSuppressed = { false },
            toggleMinimalUi = { }
        )
        
        val result = router.routeEditableFieldKeyDown(
            keyCode = KeyEvent.KEYCODE_C,
            event = event,
            params = params,
            controllers = controllers,
            callbacks = callbacks
        )
        
        // VERIFY: Should call copy action, not commit Alt+C symbol ('9')
        verify(inputConnection).performContextMenuAction(android.R.id.copy)
        verify(inputConnection, never()).commitText(eq("9"), anyInt())
        
        assertEquals(InputEventRouter.EditableFieldRoutingResult.Consume, result)
    }
    
    @Test
    fun testAltOnly_withoutCtrl_shouldStillProduceSymbol() {
        // Setup: Alt is latched, but NO Ctrl
        modifierStateController.altLatchActive = true
        
        val ctrlKeyMap = emptyMap<Int, KeyMappingLoader.CtrlMapping>()
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        
        val params = InputEventRouter.EditableFieldKeyDownHandlingParams(
            inputConnection = inputConnection,
            isNumericField = false,
            isInputViewActive = true,
            shiftPressed = false,
            shiftLayerLatched = false,
            ctrlPressed = false,  // NO Ctrl
            ctrlPhysicallyPressed = false,
            altPressed = false,
            ctrlLatchActive = false,
            altLatchActive = true,  // Alt only
            ctrlLatchFromNavMode = false,
            ctrlKeyMap = ctrlKeyMap,
            ctrlOneShot = false,
            altOneShot = false,
            clearAltOnSpaceEnabled = true,
            shiftOneShot = false,
            capsLockEnabled = false,
            cursorUpdateDelayMs = 0L
        )
        
        val controllers = InputEventRouter.EditableFieldKeyDownControllers(
            modifierStateController = modifierStateController,
            symLayoutController = symLayoutController,
            alternateCharacterManager = alternateCharacterManager,
            variationStateController = variationStateController,
            textInputController = textInputController
        )
        
        val callbacks = InputEventRouter.EditableFieldKeyDownHandlingCallbacks(
            updateStatusBar = { },
            refreshStatusBar = { },
            disableShiftOneShot = { },
            clearAltOneShot = { },
            clearCtrlOneShot = { },
            getCharacterFromLayout = { _, _, _ -> null },
            isAlphabeticKey = { false },
            callSuper = { false },
            callSuperWithKey = { _, _ -> false },
            startSpeechRecognition = { },
            getMapping = { null },
            handleMultiTapCommit = { _, _, _, _, _ -> false },
            isLongPressSuppressed = { false },
            toggleMinimalUi = { }
        )
        
        val result = router.routeEditableFieldKeyDown(
            keyCode = KeyEvent.KEYCODE_A,
            event = event,
            params = params,
            controllers = controllers,
            callbacks = callbacks
        )
        
        // VERIFY: Should commit Alt symbol '*' since Ctrl is NOT active
        verify(inputConnection).commitText("*", 1)
        verify(inputConnection, never()).performContextMenuAction(android.R.id.selectAll)
        
        assertEquals(InputEventRouter.EditableFieldRoutingResult.Consume, result)
    }
}
