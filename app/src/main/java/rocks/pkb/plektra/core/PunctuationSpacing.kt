package rocks.pkb.plektra.core

import android.content.Context
import android.view.inputmethod.InputConnection
import rocks.pkb.plektra.SettingsManager

/**
 * "Remove before" from the punctuation spacing menu, applied to the spaces before a typed mark
 * whoever typed them: "How are you ?" becomes "How are you?". Plektra's own auto-space moves
 * behind the mark instead, unless "Before next text" defers it.
 */
object PunctuationSpacing {
    private const val LOOKBACK = 64
    private const val UNDO_CONTEXT = 16

    /** Whether the focused field takes automatic spacing edits; the service supplies its field state. */
    @Volatile
    var fieldAllowsAutoEdits: () -> Boolean = { true }

    private class Undo(val textBefore: String, val spaces: String, val mark: Char)

    @Volatile
    private var undo: Undo? = null

    /**
     * Removes the spaces before [mark] when "Remove before" is chosen for it. Returns true when the
     * mark was committed here (an auto-space moved behind it); otherwise the caller commits the mark.
     */
    fun beforeMark(context: Context, inputConnection: InputConnection, mark: Char): Boolean {
        if (mark !in SettingsManager.getAutoSpacePunctuation(context) || !fieldAllowsAutoEdits()) return false
        if (mark in Punctuation.FRENCH_SPACED_PUNCTUATION && SettingsManager.shouldApplyFrenchPunctuationSpacing(context)) {
            return false
        }
        val before = inputConnection.getTextBeforeCursor(LOOKBACK, 0)?.toString() ?: return false
        val spaces = before.takeLastWhile { it == ' ' }
        val prefix = before.dropLast(spaces.length)
        // A space that starts the field or a line is indentation, not a gap before the mark.
        if (spaces.isEmpty() || prefix.isEmpty() || prefix.last().isWhitespace()) return false
        if (mark == '"' && !AutoSpaceTracker.closesStraightDoubleQuote(prefix)) return false

        val autoSpace = AutoSpaceTracker.isTrailingSpaceAuto() && spaces.length == 1
        AutoSpaceTracker.clear()
        if (autoSpace && mark !in SettingsManager.getSpaceAfterPunctuation(context)) {
            inputConnection.beginBatchEdit()
            inputConnection.deleteSurroundingText(spaces.length, 0)
            inputConnection.commitText("$mark ", 1)
            inputConnection.endBatchEdit()
            undo = null
            return true
        }
        inputConnection.deleteSurroundingText(spaces.length, 0)
        undo = Undo(prefix.takeLast(UNDO_CONTEXT) + mark, spaces, mark)
        return false
    }

    /** Backspace right after a removal puts the spaces back in front of the mark. */
    fun handleBackspaceUndo(inputConnection: InputConnection): Boolean {
        val pending = undo ?: return false
        undo = null
        val before = inputConnection.getTextBeforeCursor(pending.textBefore.length, 0)?.toString()
        if (before != pending.textBefore) return false
        inputConnection.beginBatchEdit()
        inputConnection.deleteSurroundingText(1, 0)
        inputConnection.commitText(pending.spaces + pending.mark, 1)
        inputConnection.endBatchEdit()
        return true
    }

    fun clearUndo() {
        undo = null
    }
}
