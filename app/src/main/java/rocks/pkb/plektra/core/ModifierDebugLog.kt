package rocks.pkb.plektra.core

import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ring buffer debug log for modifier key events, IME lifecycle, and Quick Launcher key paths.
 * Enabled in nightly and sideload builds via BuildConfig.ENABLE_MODIFIER_DEBUG_LOG.
 */
object ModifierDebugLog {
    private const val MAX_ENTRIES = 1000
    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val lock = Any()

    sealed class LogEntry(val timestamp: Long) {
        data class KeyEventEntry(
            val time: Long,
            val action: String,
            val keyCode: Int,
            val scanCode: Int,
            val metaState: String,
            val repeatCount: Int,
            val flags: String,
            val deviceId: Int,
            val source: String,
            val eventTime: Long,
            val downTime: Long,
            val handledBy: String,
            val consumed: Boolean?,
            val returnedToSystem: Boolean?,
            val note: String,
            val altStateBefore: String,
            val altStateAfter: String,
            val shiftStateBefore: String,
            val shiftStateAfter: String,
            val ctrlStateBefore: String,
            val ctrlStateAfter: String
        ) : LogEntry(time)

        data class CommitTextEntry(
            val time: Long,
            val text: String,
            val altStateBefore: String,
            val altStateAfter: String,
            val shiftStateBefore: String,
            val shiftStateAfter: String,
            val ctrlStateBefore: String,
            val ctrlStateAfter: String
        ) : LogEntry(time)

        data class MessageEntry(
            val time: Long,
            val message: String
        ) : LogEntry(time)

        data class ImeLifecycleEntry(
            val time: Long,
            val phase: String,
            val bound: Boolean,
            val inputViewActive: Boolean,
            val packageName: String,
            val fieldId: String,
            val inputType: String,
            val restarting: String
        ) : LogEntry(time)

        data class QuickLauncherEntry(
            val time: Long,
            val entryPoint: String,
            val action: String,
            val keyCode: Int,
            val scanCode: Int,
            val metaState: String,
            val repeatCount: Int,
            val flags: String,
            val deviceId: Int,
            val source: String,
            val sharedControllerNull: Boolean,
            val ctrlState: String,
            val eventCtrlPressed: Boolean?,
            val branch: String,
            val consumed: Boolean
        ) : LogEntry(time)
    }

    fun logKeyEvent(
        action: String,
        keyCode: Int,
        scanCode: Int,
        metaState: Int,
        repeatCount: Int,
        flags: Int,
        deviceId: Int,
        source: Int,
        eventTime: Long,
        downTime: Long,
        handledBy: String,
        altStateBefore: String,
        altStateAfter: String,
        shiftStateBefore: String,
        shiftStateAfter: String,
        ctrlStateBefore: String,
        ctrlStateAfter: String,
        consumed: Boolean? = null,
        note: String = ""
    ) {
        synchronized(lock) {
            trimToCapacity()
            entries.addLast(
                LogEntry.KeyEventEntry(
                    time = System.currentTimeMillis(),
                    action = action,
                    keyCode = keyCode,
                    scanCode = scanCode,
                    metaState = "0x${metaState.toString(16)}",
                    repeatCount = repeatCount,
                    flags = "0x${flags.toString(16)}",
                    deviceId = deviceId,
                    source = "0x${source.toString(16)}",
                    eventTime = eventTime,
                    downTime = downTime,
                    handledBy = handledBy,
                    consumed = consumed,
                    returnedToSystem = consumed?.not(),
                    note = note,
                    altStateBefore = altStateBefore,
                    altStateAfter = altStateAfter,
                    shiftStateBefore = shiftStateBefore,
                    shiftStateAfter = shiftStateAfter,
                    ctrlStateBefore = ctrlStateBefore,
                    ctrlStateAfter = ctrlStateAfter
                )
            )
        }
    }

    fun logKeyEventFromEvent(
        event: KeyEvent?,
        action: String,
        keyCode: Int = event?.keyCode ?: -1,
        handledBy: String,
        altStateBefore: String,
        altStateAfter: String,
        shiftStateBefore: String,
        shiftStateAfter: String,
        ctrlStateBefore: String,
        ctrlStateAfter: String,
        consumed: Boolean? = null,
        note: String = ""
    ) {
        logKeyEvent(
            action = action,
            keyCode = keyCode,
            scanCode = event?.scanCode ?: -1,
            metaState = event?.metaState ?: 0,
            repeatCount = event?.repeatCount ?: -1,
            flags = event?.flags ?: 0,
            deviceId = event?.deviceId ?: -1,
            source = event?.source ?: 0,
            eventTime = event?.eventTime ?: 0L,
            downTime = event?.downTime ?: 0L,
            handledBy = handledBy,
            altStateBefore = altStateBefore,
            altStateAfter = altStateAfter,
            shiftStateBefore = shiftStateBefore,
            shiftStateAfter = shiftStateAfter,
            ctrlStateBefore = ctrlStateBefore,
            ctrlStateAfter = ctrlStateAfter,
            consumed = consumed,
            note = note
        )
    }

    fun logCommitText(
        text: String,
        altStateBefore: String,
        altStateAfter: String,
        shiftStateBefore: String,
        shiftStateAfter: String,
        ctrlStateBefore: String,
        ctrlStateAfter: String
    ) {
        synchronized(lock) {
            trimToCapacity()
            entries.addLast(
                LogEntry.CommitTextEntry(
                    time = System.currentTimeMillis(),
                    text = text,
                    altStateBefore = altStateBefore,
                    altStateAfter = altStateAfter,
                    shiftStateBefore = shiftStateBefore,
                    shiftStateAfter = shiftStateAfter,
                    ctrlStateBefore = ctrlStateBefore,
                    ctrlStateAfter = ctrlStateAfter
                )
            )
        }
    }

    fun logMessage(message: String) {
        synchronized(lock) {
            trimToCapacity()
            entries.addLast(
                LogEntry.MessageEntry(
                    time = System.currentTimeMillis(),
                    message = message
                )
            )
        }
    }

    fun logImeLifecycle(
        phase: String,
        bound: Boolean,
        inputViewActive: Boolean,
        packageName: String?,
        fieldId: Int?,
        inputType: Int?,
        restarting: Boolean? = null
    ) {
        synchronized(lock) {
            trimToCapacity()
            entries.addLast(
                LogEntry.ImeLifecycleEntry(
                    time = System.currentTimeMillis(),
                    phase = phase,
                    bound = bound,
                    inputViewActive = inputViewActive,
                    packageName = packageName ?: "(null)",
                    fieldId = fieldId?.toString() ?: "(null)",
                    inputType = inputType?.let { "0x${it.toString(16)}" } ?: "(null)",
                    restarting = restarting?.toString() ?: "n/a"
                )
            )
        }
    }

    fun logQuickLauncher(
        entryPoint: String,
        action: String,
        keyCode: Int,
        event: KeyEvent?,
        sharedControllerNull: Boolean,
        ctrlState: String,
        branch: String,
        consumed: Boolean
    ) {
        synchronized(lock) {
            trimToCapacity()
            entries.addLast(
                LogEntry.QuickLauncherEntry(
                    time = System.currentTimeMillis(),
                    entryPoint = entryPoint,
                    action = action,
                    keyCode = keyCode,
                    scanCode = event?.scanCode ?: -1,
                    metaState = "0x${(event?.metaState ?: 0).toString(16)}",
                    repeatCount = event?.repeatCount ?: -1,
                    flags = "0x${(event?.flags ?: 0).toString(16)}",
                    deviceId = event?.deviceId ?: -1,
                    source = "0x${(event?.source ?: 0).toString(16)}",
                    sharedControllerNull = sharedControllerNull,
                    ctrlState = ctrlState,
                    eventCtrlPressed = event?.isCtrlPressed,
                    branch = branch,
                    consumed = consumed
                )
            )
        }
    }

    fun exportAsText(): String {
        synchronized(lock) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val builder = StringBuilder()
            builder.append("Modifier Debug Log (${entries.size} entries)\n")
            builder.append("Generated: ${dateFormat.format(Date())}\n")
            builder.append("=".repeat(80)).append("\n\n")

            entries.forEachIndexed { index, entry ->
                builder.append("[${index + 1}] ")
                builder.append(dateFormat.format(Date(entry.timestamp)))
                builder.append("\n")

                when (entry) {
                    is LogEntry.KeyEventEntry -> {
                        builder.append("  KeyEvent: ${entry.action}\n")
                        builder.append("  keyCode=${KeyEvent.keyCodeToString(entry.keyCode)} (${entry.keyCode})\n")
                        builder.append("  scanCode=${entry.scanCode}\n")
                        builder.append("  metaState=${entry.metaState}\n")
                        builder.append("  repeatCount=${entry.repeatCount}\n")
                        builder.append("  flags=${entry.flags}\n")
                        builder.append("  deviceId=${entry.deviceId}\n")
                        builder.append("  source=${entry.source}\n")
                        builder.append("  eventTime=${entry.eventTime}\n")
                        builder.append("  downTime=${entry.downTime}\n")
                        builder.append("  handledBy=${entry.handledBy}\n")
                        if (entry.consumed != null) {
                            builder.append("  consumed=${entry.consumed}\n")
                            builder.append("  returnedToSystem=${entry.returnedToSystem}\n")
                        }
                        if (entry.note.isNotEmpty()) {
                            builder.append("  note=${entry.note}\n")
                        }
                        builder.append("  Alt: ${entry.altStateBefore} → ${entry.altStateAfter}\n")
                        builder.append("  Shift: ${entry.shiftStateBefore} → ${entry.shiftStateAfter}\n")
                        builder.append("  Ctrl: ${entry.ctrlStateBefore} → ${entry.ctrlStateAfter}\n")
                    }
                    is LogEntry.CommitTextEntry -> {
                        builder.append("  CommitText: \"${entry.text}\"\n")
                        builder.append("  Alt: ${entry.altStateBefore} → ${entry.altStateAfter}\n")
                        builder.append("  Shift: ${entry.shiftStateBefore} → ${entry.shiftStateAfter}\n")
                        builder.append("  Ctrl: ${entry.ctrlStateBefore} → ${entry.ctrlStateAfter}\n")
                    }
                    is LogEntry.MessageEntry -> {
                        builder.append("  Message: ${entry.message}\n")
                    }
                    is LogEntry.ImeLifecycleEntry -> {
                        builder.append("  ImeLifecycle: ${entry.phase}\n")
                        builder.append("  bound=${entry.bound}\n")
                        builder.append("  inputViewActive=${entry.inputViewActive}\n")
                        builder.append("  package=${entry.packageName}\n")
                        builder.append("  fieldId=${entry.fieldId}\n")
                        builder.append("  inputType=${entry.inputType}\n")
                        builder.append("  restarting=${entry.restarting}\n")
                    }
                    is LogEntry.QuickLauncherEntry -> {
                        builder.append("  QuickLauncher: ${entry.entryPoint} ${entry.action}\n")
                        builder.append("  keyCode=${KeyEvent.keyCodeToString(entry.keyCode)} (${entry.keyCode})\n")
                        builder.append("  scanCode=${entry.scanCode}\n")
                        builder.append("  metaState=${entry.metaState}\n")
                        builder.append("  repeatCount=${entry.repeatCount}\n")
                        builder.append("  flags=${entry.flags}\n")
                        builder.append("  deviceId=${entry.deviceId}\n")
                        builder.append("  source=${entry.source}\n")
                        builder.append("  sharedModifierStateControllerNull=${entry.sharedControllerNull}\n")
                        builder.append("  sharedCtrlState=${entry.ctrlState}\n")
                        builder.append("  event.isCtrlPressed=${entry.eventCtrlPressed}\n")
                        builder.append("  branch=${entry.branch}\n")
                        builder.append("  consumed=${entry.consumed}\n")
                        builder.append("  returnedToSystem=${!entry.consumed}\n")
                    }
                }
                builder.append("\n")
            }

            return builder.toString()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return entries.size
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    private fun trimToCapacity() {
        while (entries.size >= MAX_ENTRIES) {
            entries.removeFirst()
        }
    }
}
