package rocks.pkb.plektra.core

import android.content.Context
import android.view.inputmethod.ExtractedTextRequest
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

    private class Undo(
        val textBefore: String, val applied: String, val literal: String, val cursorDelta: Int, var cursor: Int?
    )

    private fun cursor(ic: InputConnection): Int? =
        ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0)
            ?.takeIf { it.selectionStart >= 0 && it.selectionStart == it.selectionEnd }
            ?.let { it.startOffset + it.selectionEnd }

    /** Called before editing: [applied] replaces [removed] code units, [literal] is what the user typed. */
    internal fun recordFix(ic: InputConnection, prefix: String, removed: Int, applied: String, literal: String) {
        val delta = applied.length - removed
        undo = if (applied == literal) null else Undo(
            prefix.takeLast(UNDO_CONTEXT) + applied, applied, literal, delta, cursor(ic)?.plus(delta)
        )
    }

    @Volatile
    private var undo: Undo? = null

    /**
     * Removes the spaces before [mark] when "Remove before" is chosen for it. Returns true when the
     * mark was committed here (an auto-space moved behind it); otherwise the caller commits the mark.
     */
    fun beforeMark(context: Context, inputConnection: InputConnection, mark: Char): Boolean {
        if (mark !in SettingsManager.getAutoSpacePunctuation(context) || !fieldAllowsAutoEdits()) return false
        // Comma-space owns both sides of the comma. Let it see the original spaces.
        if (mark == Punctuation.COMMA_SPACE_PUNCTUATION && SettingsManager.getCommaSpace(context)) return false
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
            recordFix(inputConnection, prefix, spaces.length, "$mark ", spaces + mark)
            inputConnection.beginBatchEdit()
            inputConnection.deleteSurroundingText(spaces.length, 0)
            inputConnection.commitText("$mark ", 1)
            inputConnection.endBatchEdit()
            return true
        }
        recordFix(inputConnection, prefix, spaces.length, mark.toString(), spaces + mark)
        inputConnection.deleteSurroundingText(spaces.length, 0)
        return false
    }

    /** The editor also reports our own cursor advance. Only a different selection invalidates it. */
    fun onSelectionChanged(oldEnd: Int, start: Int, end: Int) {
        val pending = undo ?: return
        // Some editors don't expose ExtractedText. Anchor their first acknowledgement by the
        // size of our edit; subsequent moves must match the absolute position, not just a suffix.
        val expected = pending.cursor ?: (oldEnd + pending.cursorDelta)
        if (start == end && end == expected) pending.cursor = end else clearUndo()
    }

    /** Backspace consumes at most one fix, leaving the typed punctuation in place. */
    fun handleBackspaceUndo(inputConnection: InputConnection): Boolean {
        val pending = undo ?: return false
        undo = null
        if (!inputConnection.getSelectedText(0).isNullOrEmpty()) return false
        if (pending.cursor != null && cursor(inputConnection)?.let { it != pending.cursor } == true) return false
        val before = inputConnection.getTextBeforeCursor(pending.textBefore.length, 0)?.toString()
        if (before != pending.textBefore) return false
        inputConnection.beginBatchEdit()
        inputConnection.deleteSurroundingText(pending.applied.length, 0)
        inputConnection.commitText(pending.literal, 1)
        inputConnection.endBatchEdit()
        AutoSpaceTracker.clear()
        DeferredPunctuationSpaceTracker.clear()
        return true
    }

    fun clearUndo() {
        undo = null
    }
}
