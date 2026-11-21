package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import rocks.pkb.plektra.SettingsManager

object QuickLauncherOpener {
    private const val TAG = "QuickLauncherOpener"
    private const val NIAGARA_PACKAGE = "bitpit.launcher"
    private val NIAGARA_SEARCH_URI: Uri = Uri.parse("niagara://search")

    fun open(context: Context): Boolean {
        return when (SettingsManager.getQuickLauncherBehavior(context)) {
            SettingsManager.QUICK_LAUNCHER_BEHAVIOR_NIAGARA ->
                openNiagaraSearch(context) || togglePlektraQuickLauncher(context)
            else -> togglePlektraQuickLauncher(context)
        }
    }

    internal fun resetForTest() {
        QuickLauncherToggleState.reset()
    }

    private fun togglePlektraQuickLauncher(context: Context): Boolean {
        val intent = when (QuickLauncherToggleState.onTrigger(context.applicationContext)) {
            QuickLauncherToggleState.Decision.IGNORE -> return true
            QuickLauncherToggleState.Decision.OPEN -> QuickLauncherActivity.createOpenIntent(context)
            QuickLauncherToggleState.Decision.CLOSE -> QuickLauncherActivity.createCloseIntent(context)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Error opening Plektra QuickLauncher", error)
            QuickLauncherToggleState.onStartFailed()
            false
        }
    }

    private fun openNiagaraSearch(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, NIAGARA_SEARCH_URI).apply {
                setPackage(NIAGARA_PACKAGE)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            Log.w(TAG, "Error opening Niagara search; falling back to Plektra QuickLauncher", error)
            false
        }
    }
}

/**
 * The launcher's real visibility, kept by the activity, so a trigger means "open" when it is
 * closed or closing and "close" only when it is genuinely open. Triggers that arrive within
 * [DUPLICATE_WINDOW_MS] of the last accepted one are echoes of the same press (Q25 synthetic
 * events, key repeat, Clicks double fire) and are dropped.
 */
internal object QuickLauncherToggleState {
    enum class Phase { CLOSED, OPENING, OPEN, CLOSING }
    enum class Decision { IGNORE, OPEN, CLOSE }

    const val DUPLICATE_WINDOW_MS = 250L
    const val OPENING_STALE_MS = 2_000L

    @Volatile
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    private var phase = Phase.CLOSED
    private var phaseSinceMs = 0L
    private var lastAcceptedTriggerMs: Long? = null
    private var awaitingFocus = false
    private var owner: Any? = null
    private var process: Any? = null

    @Synchronized
    fun phase(): Phase = phase

    @Synchronized
    fun onTrigger(app: Any): Decision {
        if (process !== app) {
            resetState()
            process = app
        }
        val nowMs = clock()
        val last = lastAcceptedTriggerMs
        if (last != null && nowMs - last < DUPLICATE_WINDOW_MS) return Decision.IGNORE
        val decision = when (phase) {
            Phase.CLOSED, Phase.CLOSING -> Decision.OPEN
            Phase.OPENING -> if (nowMs - phaseSinceMs >= OPENING_STALE_MS) Decision.OPEN else Decision.IGNORE
            Phase.OPEN -> Decision.CLOSE
        }
        when (decision) {
            Decision.OPEN -> {
                setPhase(Phase.OPENING, nowMs)
                awaitingFocus = true
            }
            Decision.CLOSE -> setPhase(Phase.CLOSING, nowMs)
            Decision.IGNORE -> return decision
        }
        lastAcceptedTriggerMs = nowMs
        return decision
    }

    @Synchronized
    fun onStartFailed() {
        phase = Phase.CLOSED
        awaitingFocus = false
        owner = null
    }

    @Synchronized
    fun onRelaunching() {
        setPhase(Phase.OPENING, clock())
        awaitingFocus = true
    }

    @Synchronized
    fun onShown(instance: Any, app: Any, focused: Boolean = false) {
        if (process !== app) {
            resetState()
            process = app
        }
        owner = instance
        setPhase(Phase.OPEN, clock())
        stampWhileAppearing()
        if (focused) awaitingFocus = false
    }

    /**
     * The keyboard and the launcher share the main thread, so a duplicate trigger can be handled
     * only after a slow launcher start. Until the launcher has focus, the duplicate window counts
     * from its latest start step instead of from the press.
     */
    @Synchronized
    fun onAppearing(instance: Any, focused: Boolean) {
        if (owner !== instance) return
        stampWhileAppearing()
        if (focused) awaitingFocus = false
    }

    @Synchronized
    fun onClosing(instance: Any) {
        if (owner !== instance) return
        awaitingFocus = false
        setPhase(Phase.CLOSING, clock())
    }

    @Synchronized
    fun onGone(instance: Any) {
        if (owner !== instance) return
        owner = null
        awaitingFocus = false
        setPhase(Phase.CLOSED, clock())
    }

    private fun stampWhileAppearing() {
        if (!awaitingFocus) return
        val nowMs = clock()
        lastAcceptedTriggerMs = maxOf(lastAcceptedTriggerMs ?: nowMs, nowMs)
    }

    @Synchronized
    fun reset() {
        resetState()
        process = null
        clock = { SystemClock.uptimeMillis() }
    }

    private fun resetState() {
        phase = Phase.CLOSED
        phaseSinceMs = 0L
        lastAcceptedTriggerMs = null
        awaitingFocus = false
        owner = null
    }

    private fun setPhase(next: Phase, nowMs: Long) {
        phase = next
        phaseSinceMs = nowMs
    }
}
