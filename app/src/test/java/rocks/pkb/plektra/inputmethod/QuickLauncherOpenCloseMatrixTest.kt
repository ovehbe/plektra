package rocks.pkb.plektra.inputmethod

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.AppListHelper
import rocks.pkb.plektra.InstalledApp
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.commands.CommandExecutor
import rocks.pkb.plektra.commands.CommandLaunchSpec
import rocks.pkb.plektra.commands.PlektraCommandSource
import rocks.pkb.plektra.core.ModifierStateController
import rocks.pkb.plektra.core.NavModeController

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherOpenCloseMatrixTest(
    private val scenario: Scenario,
    private val startup: Startup,
    private val appearance: Appearance
) {
    enum class Source(val key: Int = KeyEvent.KEYCODE_UNKNOWN, val modifiers: Int = 0) {
        SYM_SPACE(KeyEvent.KEYCODE_SPACE, KeyEvent.META_SYM_ON),
        SYM_ENTER(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SYM_ON),
        ALT_ENTER(KeyEvent.KEYCODE_ENTER, KeyEvent.META_ALT_ON),
        HOME_SHORTCUT(KeyEvent.KEYCODE_A),
        POWER_SHORTCUT(KeyEvent.KEYCODE_ENTER),
        CLICKS(KeyEvent.KEYCODE_TAB),
        COMMAND
    }

    enum class Tail {
        NONE, RELEASE_BEFORE_HANDOFF, RELEASE_HELD, RELEASE_MODIFIER_FIRST, REPEAT_IN_ACTIVITY,
        REPEAT_MODIFIER_HELD, REPEAT_IN_IME, RAPID_OPEN, OPEN_WHILE_ACTIVE,
        BACK, ESCAPE, FRESH_SYM, CONFIRM, CANCELED_BACK
    }

    enum class Startup { NORMAL, FOCUS_LOST_BEFORE_RESUME, PAUSE_AND_RESUME }
    enum class Appearance(val radius: Int, val pill: Boolean, val autoStart: Boolean) {
        SQUARE(0, false, false), ROUNDED(24, true, true)
    }

    data class Scenario(val source: Source, val tail: Tail) {
        val closes get() = tail in setOf(Tail.BACK, Tail.ESCAPE, Tail.FRESH_SYM, Tail.CONFIRM)
        override fun toString() = "$source/$tail"
    }

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun clearCachedApps() = AppListHelper.invalidateInstalledApps()

    @Test
    fun launcherClosesOnlyOnDismissOrConfirmation() {
        val source = scenario.source
        val shortcut = if (source.key == KeyEvent.KEYCODE_UNKNOWN) KeyEvent.KEYCODE_SPACE else source.key
        SettingsManager.setQuickLauncherShortcut(app, shortcut)
        SettingsManager.setQuickLauncherTextFieldShortcuts(app, true)
        SettingsManager.setQuickLauncherAltShortcutsOutsideTextFields(app, true)
        SettingsManager.setLauncherShortcutsEnabled(app, source == Source.HOME_SHORTCUT)
        SettingsManager.setQuickLauncherCornerRadiusDp(app, appearance.radius)
        SettingsManager.setQuickLauncherPillMode(app, appearance.pill)
        SettingsManager.setQuickLauncherAutoStartSingle(app, appearance.autoStart)
        // Finish synchronously so isFinishing reports the outcome, not the animation progress.
        SettingsManager.setQuickLauncherAnimationDurationMs(app, 0)

        val result = ComponentName("test.launchable", "test.launchable.MainActivity")
        shadowOf(app.packageManager).apply {
            val info = addActivityIfNotPresent(result).apply { exported = true }
            val candidate = ResolveInfo().apply { activityInfo = info }
            val launcherIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(result.packageName)
            setResolveInfosForIntent(launcherIntent, listOf(candidate))
        }
        // Populate startup results without waiting on the registry's background reload.
        ReflectionHelpers.setStaticField(
            AppListHelper::class.java, "cachedInstalledApps",
            listOf(InstalledApp(result.packageName, "Test result", ColorDrawable(), false))
        )
        val launcherShortcuts = LauncherShortcutController(app)
        val router = InputEventRouter(app, NavModeController(app, ModifierStateController(300L)))
        val routerCallbacks = InputEventRouter.NoEditableFieldCallbacks(
            isShortcutKey = { it == shortcut },
            isLauncherPackage = { source == Source.HOME_SHORTCUT },
            handleLauncherShortcut = launcherShortcuts::handleLauncherShortcut,
            handlePowerShortcut = launcherShortcuts::handlePowerShortcut,
            togglePowerShortcutMode = { _, _ -> error("Unexpected power-mode toggle") },
            callSuper = { false },
            currentInputConnection = { null }
        )
        fun routeDown(event: KeyEvent): Boolean = router.handleKeyDownWithNoEditableField(
            keyCode = event.keyCode,
            event = event,
            ctrlKeyMap = emptyMap(),
            callbacks = routerCallbacks,
            ctrlLatchActive = false,
            editorInfo = null,
            currentPackageName = "test.foreground",
            powerShortcutsEnabled = source == Source.POWER_SHORTCUT
        )
        fun trigger(at: Long) {
            when (source) {
                Source.CLICKS -> ClicksButtonDirectActionExecutor.execute(app, ClicksButtonDirectAction.QUICK_LAUNCHER)
                Source.COMMAND -> assertTrue(CommandExecutor(app).execute(
                    CommandLaunchSpec.InternalAction(PlektraCommandSource.ACTION_OPEN_QUICK_LAUNCHER)
                ).isSuccess)
                else -> {
                    if (source == Source.POWER_SHORTCUT) launcherShortcuts.togglePowerShortcutMode({})
                    assertTrue(routeDown(key(KeyEvent.ACTION_DOWN, source.key, at, meta = source.modifiers)))
                }
            }
        }
        fun startedIntent(): Intent? = shadowOf(app).nextStartedActivity?.also {
            assertEquals(QuickLauncherActivity::class.java.name, it.component?.className)
        }

        val downTime = 1_000L
        trigger(downTime)
        val launch = checkNotNull(startedIntent()) { "Trigger did not start Quick Launcher" }
        if (scenario.tail == Tail.RAPID_OPEN) trigger(downTime + 10)
        if (scenario.tail == Tail.RELEASE_BEFORE_HANDOFF) {
            router.handleKeyUpWithNoEditableField(
                source.key, key(KeyEvent.ACTION_UP, source.key, downTime, 20, meta = source.modifiers),
                emptyMap(), routerCallbacks
            )
        }
        if (scenario.tail == Tail.REPEAT_IN_IME) {
            routeDown(key(KeyEvent.ACTION_DOWN, source.key, downTime, 500, 1, source.modifiers))
        }
        val controller = Robolectric.buildActivity(QuickLauncherActivity::class.java, launch).create().start()
        val activity = controller.get()
        if (startup == Startup.FOCUS_LOST_BEFORE_RESUME) activity.onWindowFocusChanged(false)
        // Model singleTop reuse, including a second request already queued at the focus handoff.
        var next = startedIntent()
        while (next != null) {
            controller.newIntent(next)
            next = startedIntent()
        }
        controller.resume()
        activity.onWindowFocusChanged(true)
        if (startup == Startup.PAUSE_AND_RESUME) {
            // Model a transient focus/window loss, such as while Android hides the previous IME.
            activity.onWindowFocusChanged(false)
            controller.pause().resume()
            activity.onWindowFocusChanged(true)
        }
        try {
            assertFalse("launcher finished during startup", activity.isFinishing)
            fun send(action: Int, code: Int, meta: Int = 0, repeat: Int = 0, flags: Int = 0) {
                activity.dispatchKeyEvent(key(action, code, downTime, 550, repeat, meta, flags))
            }
            when (scenario.tail) {
                Tail.RELEASE_HELD -> send(KeyEvent.ACTION_UP, source.key, source.modifiers)
                Tail.RELEASE_MODIFIER_FIRST -> {
                    val modifierKey = if (source.modifiers == KeyEvent.META_SYM_ON) {
                        KeyEvent.KEYCODE_SYM
                    } else {
                        KeyEvent.KEYCODE_ALT_LEFT
                    }
                    send(KeyEvent.ACTION_UP, modifierKey)
                    send(KeyEvent.ACTION_UP, source.key)
                }
                Tail.REPEAT_IN_ACTIVITY, Tail.REPEAT_MODIFIER_HELD -> {
                    val meta = if (scenario.tail == Tail.REPEAT_MODIFIER_HELD) source.modifiers else 0
                    send(KeyEvent.ACTION_DOWN, source.key, meta, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS)
                    send(KeyEvent.ACTION_DOWN, source.key, meta, repeat = 2)
                    send(KeyEvent.ACTION_UP, source.key, meta)
                }
                Tail.OPEN_WHILE_ACTIVE -> {
                    trigger(downTime + 1_000)
                    controller.newIntent(checkNotNull(startedIntent()))
                }
                Tail.BACK, Tail.ESCAPE, Tail.FRESH_SYM, Tail.CONFIRM, Tail.CANCELED_BACK -> {
                    val (code, meta) = when (scenario.tail) {
                        Tail.ESCAPE -> KeyEvent.KEYCODE_ESCAPE to 0
                        Tail.FRESH_SYM -> shortcut to (KeyEvent.META_SYM_ON or KeyEvent.META_SHIFT_ON)
                        Tail.CONFIRM -> KeyEvent.KEYCODE_ENTER to 0
                        else -> KeyEvent.KEYCODE_BACK to 0
                    }
                    val freshDownTime = downTime + 1_000
                    activity.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, code, freshDownTime, meta = meta))
                    if (scenario.tail != Tail.CONFIRM) assertFalse("dismissed before key-up", activity.isFinishing)
                    val flags = if (scenario.tail == Tail.CANCELED_BACK) KeyEvent.FLAG_CANCELED else 0
                    activity.dispatchKeyEvent(key(KeyEvent.ACTION_UP, code, freshDownTime, 20, flags = flags))
                }
                else -> Unit
            }
            assertEquals("launcher finishing", scenario.closes, activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun key(
        action: Int,
        code: Int,
        downTime: Long,
        elapsed: Long = 0,
        repeat: Int = 0,
        meta: Int = 0,
        flags: Int = 0
    ) = KeyEvent(downTime, downTime + elapsed, action, code, repeat, meta, 0, 0, flags)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}, {1}, {2}")
        fun parameters(): List<Array<Any>> = listOf(
            Scenario(Source.SYM_SPACE, Tail.RELEASE_BEFORE_HANDOFF),
            Scenario(Source.SYM_SPACE, Tail.RELEASE_HELD),
            Scenario(Source.SYM_SPACE, Tail.REPEAT_MODIFIER_HELD),
            Scenario(Source.SYM_SPACE, Tail.REPEAT_IN_ACTIVITY),
            Scenario(Source.SYM_SPACE, Tail.REPEAT_IN_IME),
            Scenario(Source.SYM_SPACE, Tail.FRESH_SYM),
            Scenario(Source.SYM_ENTER, Tail.RELEASE_HELD),
            Scenario(Source.SYM_ENTER, Tail.RELEASE_MODIFIER_FIRST),
            Scenario(Source.SYM_ENTER, Tail.REPEAT_IN_ACTIVITY),
            Scenario(Source.SYM_ENTER, Tail.REPEAT_MODIFIER_HELD),
            Scenario(Source.ALT_ENTER, Tail.RELEASE_HELD),
            Scenario(Source.ALT_ENTER, Tail.RELEASE_MODIFIER_FIRST),
            Scenario(Source.ALT_ENTER, Tail.REPEAT_MODIFIER_HELD),
            Scenario(Source.ALT_ENTER, Tail.REPEAT_IN_IME),
            Scenario(Source.HOME_SHORTCUT, Tail.RAPID_OPEN),
            Scenario(Source.HOME_SHORTCUT, Tail.OPEN_WHILE_ACTIVE),
            Scenario(Source.POWER_SHORTCUT, Tail.RELEASE_HELD),
            Scenario(Source.CLICKS, Tail.RELEASE_HELD),
            Scenario(Source.CLICKS, Tail.RAPID_OPEN),
            Scenario(Source.CLICKS, Tail.OPEN_WHILE_ACTIVE),
            Scenario(Source.COMMAND, Tail.NONE),
            Scenario(Source.COMMAND, Tail.RAPID_OPEN),
            Scenario(Source.COMMAND, Tail.OPEN_WHILE_ACTIVE),
            Scenario(Source.COMMAND, Tail.BACK),
            Scenario(Source.COMMAND, Tail.ESCAPE),
            Scenario(Source.COMMAND, Tail.CANCELED_BACK),
            Scenario(Source.COMMAND, Tail.CONFIRM)
        ).flatMap { scenario ->
            Startup.entries.flatMap { startup ->
                Appearance.entries.map { appearance -> arrayOf<Any>(scenario, startup, appearance) }
            }
        }
    }
}
