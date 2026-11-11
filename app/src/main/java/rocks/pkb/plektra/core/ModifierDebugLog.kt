package rocks.pkb.plektra.core

import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ring buffer debug log for modifier key events and text commits.
 * Enabled in nightly and sideload builds via BuildConfig.ENABLE_MODIFIER_DEBUG_LOG.
 */
object ModifierDebugLog {
    private const val MAX_ENTRIES = 500
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
        ctrlStateAfter: String
    ) {
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
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
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
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
                }
                builder.append("\n")
            }

            return builder.toString()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }
}
