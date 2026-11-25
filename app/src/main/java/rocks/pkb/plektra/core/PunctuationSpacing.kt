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
        val textBefore: String, val applied: String, val literal: String, var cursorDelta: Int, var cursor: Int?
    )

    private fun cursor(ic: InputConnection): Int? =
        ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 }, 0)
            ?.takeIf { it.selectionStart >= 0 && it.selectionStart == it.selectionEnd }
            ?.let { it.startOffset + it.selectionEnd }

    /** Replace a suffix and publish its undo before the editor acknowledges the batch. */
    internal fun commitFix(ic: InputConnection, prefix: String, removed: String, applied: String, literal: String) {
        val delta = applied.length - removed.length
        val fix = if (applied == literal) null else Undo(
            prefix.takeLast(UNDO_CONTEXT) + applied, applied, literal, delta, cursor(ic)?.plus(delta)
        )
        // In particular, append just the space when comma-space finds an existing comma.
        val shared = removed.commonPrefixWith(applied).length
        undo = null
        ic.beginBatchEdit()
        try {
            // EditableInputConnection deletes *around* a composing span, and commitText
            // replaces it. Keep the word and edit only the suffix at the actual caret.
            ic.finishComposingText()
            if (removed.length > shared && !ic.deleteSurroundingText(removed.length - shared, 0)) {
                // The existing gap is still present. Type the symbol without duplicating it.
                ic.commitText(literal.trimStart(), 1)
            } else if (ic.commitText(applied.substring(shared), 1)) {
                undo = fix
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /** A long press removed its provisional key in the same (outer) editor batch. */
    internal fun includeBatchedDeletion(length: Int) {
        undo?.let { it.cursorDelta -= length }
    }

    @Volatile
    private var undo: Undo? = null

    /**
     * Returns true when this rule handled the mark. The caller must not run another boundary
     * edit against the newly exposed word, or commit the mark a second time.
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

        if (!inputConnection.getSelectedText(0).isNullOrEmpty()) return false
        val moveSpace = spaces.length == 1 && AutoSpaceTracker.isTrailingSpaceAuto() &&
            mark !in SettingsManager.getSpaceAfterPunctuation(context)
        AutoSpaceTracker.clear()
        commitFix(inputConnection, prefix, spaces, if (moveSpace) "$mark " else mark.toString(), spaces + mark)
        return true
    }

    /** True if this selection acknowledges our punctuation edit rather than a cursor move. */
    fun onSelectionChanged(oldEnd: Int, start: Int, end: Int): Boolean {
        val pending = undo ?: return false
        // Some editors don't expose ExtractedText. Anchor their first acknowledgement by the
        // size of our edit; subsequent moves must match the absolute position, not just a suffix.
        val expected = pending.cursor ?: (oldEnd + pending.cursorDelta)
        if (start == end && end == expected) {
            pending.cursor = end
            return true
        }
        clearUndo()
        return false
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
        try {
            inputConnection.finishComposingText()
            if (!inputConnection.deleteSurroundingText(pending.applied.length, 0)) return false
            inputConnection.commitText(pending.literal, 1)
        } finally {
            inputConnection.endBatchEdit()
        }
        AutoSpaceTracker.clear()
        DeferredPunctuationSpaceTracker.clear()
        return true
    }

    fun clearUndo() {
        undo = null
    }
}
