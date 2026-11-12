package rocks.pkb.plektra.inputmethod

import android.view.KeyEvent
import rocks.pkb.plektra.core.ShiftState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ring buffer debug log for modifier key events and state transitions.
 * Enabled only in Test and nightly builds.
 */
object ModifierDebugLog {
    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    sealed class LogEntry(val timestamp: Long) {
        data class KeyEventEntry(
            val time: Long,
            val action: String,
            val keyCode: Int,
            val keyCodeName: String,
            val scanCode: Int,
            val metaState: String,
            val repeatCount: Int,
            val flags: Int,
            val deviceId: Int,
            val source: Int,
            val eventTime: Long,
            val downTime: Long,
            val handledBy: String,
            val altBefore: ModifierState,
            val shiftBefore: ModifierState,
            val ctrlBefore: ModifierState,
            val altAfter: ModifierState,
            val shiftAfter: ModifierState,
            val ctrlAfter: ModifierState
        ) : LogEntry(time)

        data class CommitTextEntry(
            val time: Long,
            val text: String,
            val altState: ModifierState,
            val shiftState: ModifierState,
            val ctrlState: ModifierState
        ) : LogEntry(time)
    }

    enum class ModifierState {
        OFF,
        ONE_SHOT,
        LATCHED,
        CAPS,
        PRESSED
    }

    fun logKeyEvent(
        event: KeyEvent?,
        action: String,
        handledBy: String,
        altBefore: ModifierState,
        shiftBefore: ModifierState,
        ctrlBefore: ModifierState,
        altAfter: ModifierState,
        shiftAfter: ModifierState,
        ctrlAfter: ModifierState
    ) {
        if (event == null) return

        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.add(
                LogEntry.KeyEventEntry(
                    time = System.currentTimeMillis(),
                    action = action,
                    keyCode = event.keyCode,
                    keyCodeName = KeyEvent.keyCodeToString(event.keyCode),
                    scanCode = event.scanCode,
                    metaState = "0x${event.metaState.toString(16)}",
                    repeatCount = event.repeatCount,
                    flags = event.flags,
                    deviceId = event.deviceId,
                    source = event.source,
                    eventTime = event.eventTime,
                    downTime = event.downTime,
                    handledBy = handledBy,
                    altBefore = altBefore,
                    shiftBefore = shiftBefore,
                    ctrlBefore = ctrlBefore,
                    altAfter = altAfter,
                    shiftAfter = shiftAfter,
                    ctrlAfter = ctrlAfter
                )
            )
        }
    }

    fun logCommitText(
        text: String,
        altState: ModifierState,
        shiftState: ModifierState,
        ctrlState: ModifierState
    ) {
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.add(
                LogEntry.CommitTextEntry(
                    time = System.currentTimeMillis(),
                    text = text,
                    altState = altState,
                    shiftState = shiftState,
                    ctrlState = ctrlState
                )
            )
        }
    }

    fun exportAsText(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Plektra Modifier Debug Log ===")
        sb.appendLine("Exported: ${dateFormat.format(Date())}")
        sb.appendLine("Total entries: ${entries.size}")
        sb.appendLine()

        synchronized(entries) {
            entries.forEach { entry ->
                when (entry) {
                    is LogEntry.KeyEventEntry -> {
                        sb.appendLine("${dateFormat.format(Date(entry.time))} KEY ${entry.action}")
                        sb.appendLine("  keyCode: ${entry.keyCodeName} (${entry.keyCode}), scan: ${entry.scanCode}")
                        sb.appendLine("  meta: ${entry.metaState}, repeat: ${entry.repeatCount}, flags: 0x${entry.flags.toString(16)}")
                        sb.appendLine("  device: ${entry.deviceId}, source: 0x${entry.source.toString(16)}")
                        sb.appendLine("  eventTime: ${entry.eventTime}, downTime: ${entry.downTime}")
                        sb.appendLine("  handledBy: ${entry.handledBy}")
                        sb.appendLine("  BEFORE: Alt=${entry.altBefore} Shift=${entry.shiftBefore} Ctrl=${entry.ctrlBefore}")
                        sb.appendLine("  AFTER:  Alt=${entry.altAfter} Shift=${entry.shiftAfter} Ctrl=${entry.ctrlAfter}")
                    }
                    is LogEntry.CommitTextEntry -> {
                        sb.appendLine("${dateFormat.format(Date(entry.time))} COMMIT '${entry.text}'")
                        sb.appendLine("  modifiers: Alt=${entry.altState} Shift=${entry.shiftState} Ctrl=${entry.ctrlState}")
                    }
                }
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
    }

    fun getAltState(oneShot: Boolean, latched: Boolean): ModifierState {
        return when {
            latched -> ModifierState.LATCHED
            oneShot -> ModifierState.ONE_SHOT
            else -> ModifierState.OFF
        }
    }

    fun getShiftState(state: ShiftState): ModifierState {
        return when (state) {
            ShiftState.OFF -> ModifierState.OFF
            ShiftState.ONE_SHOT -> ModifierState.ONE_SHOT
            ShiftState.CAPS -> ModifierState.CAPS
        }
    }

    fun getCtrlState(oneShot: Boolean, latched: Boolean): ModifierState {
        return when {
            latched -> ModifierState.LATCHED
            oneShot -> ModifierState.ONE_SHOT
            else -> ModifierState.OFF
        }
    }
}
