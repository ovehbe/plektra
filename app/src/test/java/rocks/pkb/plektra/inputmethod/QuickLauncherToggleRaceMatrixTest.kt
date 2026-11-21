package rocks.pkb.plektra.inputmethod

import android.app.Application
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import rocks.pkb.plektra.SettingsManager

/**
 * Drives the Quick Launcher the way the system does: every trigger goes through
 * [QuickLauncherOpener.open], and each started intent either creates a new instance or, while a
 * non-finishing instance exists (singleTop), is delivered through onNewIntent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLauncherToggleRaceMatrixTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        QuickLauncherOpener.resetForTest()
    }

    @After
    fun tearDown() {
        QuickLauncherOpener.resetForTest()
    }

    private sealed class Action {
        object Trigger : Action()
        object Back : Action()
        object SystemBack : Action()
        object PauseResume : Action()
        object LeaveApp : Action()
        object Recreate : Action()
        object ServiceRestart : Action()
        object ProcessRestart : Action()
        object StartFails : Action()
        object HoldShortcutRepeat : Action()
        override fun toString(): String = javaClass.simpleName
    }

    private data class Step(val atMs: Long, val action: Action)

    private data class Scenario(
        val name: String,
        val steps: List<Step>,
        val expectOpen: Boolean,
        val deliveryDelayMs: Long = 0L,
        val creationCostMs: Long = 0L
    )

    private inner class Harness(
        private val animationMs: Int,
        private val deliveryDelayMs: Long,
        private val creationCostMs: Long
    ) {
        private var controller: ActivityController<QuickLauncherActivity>? = null
        private var destroyed = false
        private var nowMs = 0L
        private val queued = ArrayDeque<Pair<Long, Intent>>()
        var failNextStart = false
        var instancesCreated = 0
            private set

        init {
            SettingsManager.setQuickLauncherAnimationDurationMs(app, animationMs)
            // Robolectric moves its own clock to run animation frames; scenario time is what the
            // toggle sees, as on a device where animations do not hold up the main thread.
            QuickLauncherToggleState.clock = { nowMs }
        }

        private val live: QuickLauncherActivity?
            get() = controller?.get()?.takeIf { !destroyed && !it.isFinishing }

        fun advanceTo(targetMs: Long) {
            while (true) {
                val nextDelivery = queued.firstOrNull()?.first
                val stop = if (nextDelivery != null && nextDelivery <= targetMs) nextDelivery else targetMs
                idle(stop - nowMs)
                nowMs = maxOf(nowMs, stop)
                reap()
                if (nextDelivery != null && nextDelivery <= targetMs) {
                    deliver(queued.removeFirst().second)
                } else {
                    break
                }
            }
        }

        // Compose's global snapshot observer is bound to the first test's main looper, so state
        // written outside composition must be announced by hand, as Compose's test rules do.
        private fun idle(ms: Long) {
            Snapshot.sendApplyNotifications()
            if (ms <= 0) {
                ShadowLooper.idleMainLooper()
                return
            }
            var left = ms
            while (left > 0) {
                val slice = minOf(left, 8L)
                ShadowLooper.idleMainLooper(slice, java.util.concurrent.TimeUnit.MILLISECONDS)
                Snapshot.sendApplyNotifications()
                left -= slice
            }
        }

        private fun reap() {
            val c = controller ?: return
            if (!destroyed && c.get().isFinishing) {
                c.pause().stop().destroy()
                destroyed = true
            }
        }

        private fun collectStarted() {
            val shadowApp = shadowOf(app)
            while (true) {
                val intent = shadowApp.nextStartedActivity ?: break
                if (intent.component?.className != QuickLauncherActivity::class.java.name) continue
                if (failNextStart) {
                    failNextStart = false
                    continue
                }
                if (deliveryDelayMs > 0) {
                    queued.addLast((nowMs + deliveryDelayMs) to intent)
                } else {
                    deliver(intent)
                }
            }
        }

        private fun deliver(intent: Intent) {
            val current = live
            if (current != null) {
                controller!!.newIntent(intent)
            } else {
                controller = Robolectric.buildActivity(QuickLauncherActivity::class.java, intent).setup()
                destroyed = false
                instancesCreated += 1
                // Creating the launcher blocks the shared main thread; input queued meanwhile is
                // handled only afterwards.
                if (creationCostMs > 0) {
                    idle(creationCostMs)
                    nowMs += creationCostMs
                }
                controller!!.get().takeUnless { it.isFinishing }?.onWindowFocusChanged(true)
            }
            ShadowLooper.idleMainLooper()
            reap()
        }

        fun perform(action: Action) {
            when (action) {
                Action.Trigger -> {
                    QuickLauncherOpener.open(app)
                    collectStarted()
                }
                Action.StartFails -> {
                    failNextStart = true
                    QuickLauncherOpener.open(app)
                    collectStarted()
                }
                Action.HoldShortcutRepeat -> {
                    val activity = live ?: return
                    val repeat = KeyEvent(
                        0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 3, KeyEvent.META_SYM_ON
                    )
                    activity.onKeyDown(KeyEvent.KEYCODE_SPACE, repeat)
                }
                Action.Back -> {
                    val activity = live ?: return
                    activity.onKeyDown(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                    activity.onKeyUp(KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                }
                Action.SystemBack -> {
                    val activity = live ?: return
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                Action.PauseResume -> {
                    val c = controller?.takeIf { live != null } ?: return
                    c.pause()
                    c.get().onWindowFocusChanged(false)
                    ShadowLooper.idleMainLooper()
                    c.resume()
                    c.get().onWindowFocusChanged(true)
                }
                Action.LeaveApp -> {
                    val c = controller?.takeIf { live != null } ?: return
                    // noHistory: leaving the launcher finishes it.
                    c.pause().stop()
                    c.get().finish()
                    c.destroy()
                    destroyed = true
                }
                Action.Recreate -> {
                    val c = controller?.takeIf { live != null } ?: return
                    controller = c.recreate()
                    destroyed = false
                    instancesCreated += 1
                }
                Action.ServiceRestart -> {
                    // The keyboard service is recreated inside the same process; nothing that the
                    // launcher relies on may be tied to the old service instance.
                }
                Action.ProcessRestart -> {
                    controller?.takeIf { !destroyed }?.let {
                        it.pause().stop().destroy()
                    }
                    destroyed = true
                    queued.clear()
                    QuickLauncherOpener.resetForTest()
                    QuickLauncherToggleState.clock = { nowMs }
                }
            }
            ShadowLooper.idleMainLooper()
            reap()
        }

        fun isOpenAndSettled(): Boolean = live != null
    }

    private fun run(scenario: Scenario, animationMs: Int): Pair<Boolean, Int> {
        QuickLauncherOpener.resetForTest()
        val harness = Harness(animationMs, scenario.deliveryDelayMs, scenario.creationCostMs)
        var last = 0L
        for (step in scenario.steps.sortedBy { it.atMs }) {
            harness.advanceTo(step.atMs)
            harness.perform(step.action)
            last = step.atMs
        }
        harness.advanceTo(last + 2_500L)
        return harness.isOpenAndSettled() to harness.instancesCreated
    }

    private fun t(at: Long, action: Action = Action.Trigger) = Step(at, action)

    private fun scenarios(): List<Scenario> {
        val list = mutableListOf<Scenario>()
        val duplicateGaps = listOf(0L, 1L, 13L, 15L, 40L, 60L, 120L, 200L)
        for (delivery in listOf(0L, 30L, 150L)) {
            list += Scenario("single trigger opens (delivery ${delivery}ms)", listOf(t(0)), true, delivery)
            for (gap in duplicateGaps) {
                list += Scenario(
                    "duplicate trigger +${gap}ms while opening (delivery ${delivery}ms)",
                    listOf(t(0), t(gap)), true, delivery
                )
                list += Scenario(
                    "triple trigger within +${gap}ms (delivery ${delivery}ms)",
                    listOf(t(0), t(gap / 2), t(gap)), true, delivery
                )
                list += Scenario(
                    "open, genuine close, duplicate +${gap}ms of the close (delivery ${delivery}ms)",
                    listOf(t(0), t(1_000), t(1_000 + gap)), false, delivery
                )
                list += Scenario(
                    "open, close, reopen with duplicate +${gap}ms (delivery ${delivery}ms)",
                    listOf(t(0), t(1_000), t(3_000), t(3_000 + gap)), true, delivery
                )
            }
            for (gap in listOf(400L, 700L, 1_500L)) {
                list += Scenario(
                    "genuine second trigger +${gap}ms closes (delivery ${delivery}ms)",
                    listOf(t(0), t(gap)), false, delivery
                )
                list += Scenario(
                    "third genuine trigger +${gap * 2}ms reopens (delivery ${delivery}ms)",
                    listOf(t(0), t(gap), t(gap * 2)), true, delivery
                )
            }
        }
        for (cost in listOf(150L, 400L, 900L)) {
            for (gap in listOf(1L, 15L, 60L)) {
                list += Scenario(
                    "duplicate +${gap}ms queued behind a ${cost}ms launcher start",
                    listOf(t(0), t(gap)), true, creationCostMs = cost
                )
                list += Scenario(
                    "reopen with duplicate +${gap}ms queued behind a ${cost}ms start",
                    listOf(t(0), t(cost + 1_000), t(cost + 3_000), t(cost + 3_000 + gap)), true, creationCostMs = cost
                )
            }
            list += Scenario(
                "genuine second trigger after a ${cost}ms start closes",
                listOf(t(0), t(cost + 400)), false, creationCostMs = cost
            )
        }
        list += Scenario("trigger right after Back closed it reopens", listOf(t(0), t(800, Action.Back), t(1_100)), true)
        list += Scenario("trigger during Back close animation reopens", listOf(t(0), t(800, Action.Back), t(860)), true)
        list += Scenario("trigger right after close-by-trigger animation reopens", listOf(t(0), t(800), t(1_300)), true)
        list += Scenario("trigger during close-by-trigger animation reopens", listOf(t(0), t(800), t(1_080)), true)
        list += Scenario("trigger after system back close reopens", listOf(t(0), t(800, Action.SystemBack), t(1_300)), true)
        list += Scenario("focus loss and return keeps it open", listOf(t(0), t(500, Action.PauseResume)), true)
        list += Scenario("trigger after focus loss and return closes", listOf(t(0), t(500, Action.PauseResume), t(1_000)), false)
        list += Scenario("trigger soon after focus returns closes", listOf(t(0), t(500, Action.PauseResume), t(560)), false)
        list += Scenario("trigger after leaving the app (noHistory finish) opens", listOf(t(0), t(500, Action.LeaveApp), t(900)), true)
        list += Scenario("config change keeps it open", listOf(t(0), t(500, Action.Recreate)), true)
        list += Scenario("trigger after config change closes", listOf(t(0), t(500, Action.Recreate), t(1_000)), false)
        list += Scenario("trigger after config change and close reopens", listOf(t(0), t(500, Action.Recreate), t(1_000), t(2_000)), true)
        list += Scenario("keyboard service restart keeps the toggle state", listOf(t(0), t(500, Action.ServiceRestart), t(1_000)), false)
        list += Scenario("process restart: next trigger opens", listOf(t(0), t(500, Action.ProcessRestart), t(1_000)), true)
        list += Scenario("start that never arrives does not block the next trigger", listOf(t(0, Action.StartFails), t(3_000)), true)
        list += Scenario("held shortcut repeat inside the launcher keeps it open", listOf(t(0), t(450, Action.HoldShortcutRepeat), t(500, Action.HoldShortcutRepeat)), true)
        return list
    }

    @Test
    fun triggerWhileClosedOpensAndTriggerWhileOpenCloses() {
        val failures = mutableListOf<String>()
        var count = 0
        for (animationMs in listOf(0, 120, 300)) {
            for (scenario in scenarios()) {
                count++
                val (open, _) = run(scenario, animationMs)
                if (open != scenario.expectOpen) {
                    failures += "anim=${animationMs}ms ${scenario.name}: open=$open expected ${scenario.expectOpen}"
                }
            }
        }
        println("QuickLauncherToggleRaceMatrix: ${count - failures.size}/$count passed")
        failures.forEach { println("QuickLauncherToggleRaceMatrix FAIL $it") }
        assertTrue("${failures.size} of $count scenarios failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun duplicateTriggersNeverCreateASecondInstance() {
        for (animationMs in listOf(0, 120)) {
            val (open, instances) = run(Scenario("dup", listOf(t(0), t(15), t(30)), true), animationMs)
            assertTrue(open)
            assertEquals(1, instances)
        }
    }

    @Test
    fun closeRequestClosesAnOpenLauncher() {
        SettingsManager.setQuickLauncherAnimationDurationMs(app, 0)
        val controller = Robolectric.buildActivity(
            QuickLauncherActivity::class.java,
            QuickLauncherActivity.createOpenIntent(app)
        ).setup()
        ShadowLooper.idleMainLooper(1_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        controller.newIntent(QuickLauncherActivity.createCloseIntent(app))
        ShadowLooper.idleMainLooper()
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun closeRequestThatStartsAFreshInstanceNeverShowsIt() {
        val controller = Robolectric.buildActivity(
            QuickLauncherActivity::class.java,
            QuickLauncherActivity.createCloseIntent(app)
        ).create()
        assertTrue(controller.get().isFinishing)
        assertEquals(QuickLauncherToggleState.Phase.CLOSED, QuickLauncherToggleState.phase())
    }
}
