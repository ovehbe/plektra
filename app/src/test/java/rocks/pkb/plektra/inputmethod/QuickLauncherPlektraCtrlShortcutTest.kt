package rocks.pkb.plektra.inputmethod

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.InputContextState
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/**
 * Q25 has no physical Ctrl key. Plektra remaps SHIFT_RIGHT → Ctrl in the IME
 * (DeviceSpecific) and tracks one-shot/latch in ModifierStateController via the
 * service/router path. Quick Launcher must honor that Plektra Ctrl state, not only
 * KeyEvent META_CTRL_ON.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherPlektraCtrlShortcutTest {

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DeviceSpecific.clearTestOverrides()
        DeviceSpecific.setBuildFingerprintForTests("zinwa", "zinwa", "Q25", "Q25", "q25")
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setQuickLauncherAnimationDurationMs(context, 0)
        SettingsManager.setCtrlTapLatches(context, false)
        PhysicalKeyboardInputMethodService.clearActiveModifierStateControllerForTests()
    }

    @Test
    fun q25CtrlOneShotFromServiceIsHonoredByQuickLauncherAndClearedAfterShortcut() {
        val service = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java)
            .create()
            .get()
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            packageName = "rocks.pkb.plektra.test"
        }
        val inputConnection = emptyInputConnection()
        // Bind an editable field so Ctrl taps go through InputEventRouter (same as device).
        setField(service, "mInputConnection", inputConnection)
        setField(service, "mStartedInputConnection", inputConnection)
        setField(service, "mInputEditorInfo", editorInfo)
        setField(service, "isInputViewActive", true)
        setField(service, "inputContextState", InputContextState.fromEditorInfo(editorInfo))

        val modifierState = requireNotNull(
            PhysicalKeyboardInputMethodService.activeModifierStateControllerForTests()
        ) {
            "IME must publish its ModifierStateController for Quick Launcher"
        }

        val activity = Robolectric.buildActivity(QuickLauncherActivity::class.java)
            .setup()
            .get()

        val downTime = 10_000L
        // Populate search query without Ctrl.
        listOf(
            KeyEvent.KEYCODE_H,
            KeyEvent.KEYCODE_E,
            KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_O
        ).forEach { code ->
            assertTrue(
                activity.onKeyDown(code, keyEvent(KeyEvent.ACTION_DOWN, code, downTime, downTime))
            )
        }
        assertEquals("hello", activityQuery(activity))

        // Real Q25 Ctrl tap through the service remap + router path (SHIFT_RIGHT → Ctrl).
        val ctrlDown = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            downTime + 100,
            downTime + 100
        )
        val ctrlUp = keyEvent(
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            downTime + 100,
            downTime + 140
        )
        service.onKeyDown(KeyEvent.KEYCODE_SHIFT_RIGHT, ctrlDown)
        service.onKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT, ctrlUp)
        assertTrue("Service/router path should leave Ctrl one-shot active", modifierState.ctrlOneShot)
        assertFalse(modifierState.ctrlLatchActive)

        // Letter with no META_CTRL_ON — only Plektra one-shot from the service path.
        val xDown = keyEvent(
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_X,
            downTime + 200,
            downTime + 200
        )
        assertTrue(
            "Quick Launcher must treat Plektra Ctrl one-shot as Ctrl+X",
            activity.onKeyDown(KeyEvent.KEYCODE_X, xDown)
        )
        assertEquals(
            "Ctrl+X should cut the query, not append 'x'",
            "",
            activityQuery(activity)
        )
        assertFalse(
            "One-shot Ctrl must clear after the shortcut, like normal text fields",
            modifierState.ctrlOneShot
        )
    }

    private fun activityQuery(activity: QuickLauncherActivity): String {
        val field: Field = QuickLauncherActivity::class.java.getDeclaredField("query\$delegate")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(activity) as androidx.compose.runtime.MutableState<String>
        return state.value
    }

    private fun keyEvent(
        action: Int,
        keyCode: Int,
        downTime: Long,
        eventTime: Long,
        metaState: Int = 0
    ): KeyEvent {
        return KeyEvent(downTime, eventTime, action, keyCode, 0, metaState)
    }

    private fun emptyInputConnection(): InputConnection {
        return Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)
        ) { _, _, _ ->
            null
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
        error("Field not found: $fieldName")
    }
}
