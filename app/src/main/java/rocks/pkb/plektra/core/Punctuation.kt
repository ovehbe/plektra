package rocks.pkb.plektra.core

/**
 * Centralized punctuation sets. Apostrophes are intentionally excluded from boundary handling.
 */
object Punctuation {
    const val NARROW_NO_BREAK_SPACE: Char = '\u202F'

    // Boundary punctuation (no apostrophes).
    const val BOUNDARY: String = ".,;:!?()[]{}\\/\""

    // Auto-space punctuation (no apostrophes). All candidates are opt-in by
    // default so ASCII smileys and quotes stay easy to type.
    const val AUTO_SPACE_CANDIDATES: String = ".,;:!?\\/\")]}"
    const val DEFAULT_AUTO_SPACE: String = ""
    const val AUTO_SPACE: String = DEFAULT_AUTO_SPACE

    const val FRENCH_SPACED_PUNCTUATION: String = "?!;:"
    const val COMMA_SPACE_PUNCTUATION: Char = ','

    // Opening quotes and brackets that may come before a sentence's first letter, as
    // TextUtils.getCapsMode skips them, plus the Spanish inverted marks.
    fun isOpeningPunctuation(c: Char): Boolean = when (c) {
        '"', '\'', '(', '[', '{', '¿', '¡', '»', '›' -> true
        else -> Character.getType(c).let {
            it == Character.START_PUNCTUATION.toInt() ||
                it == Character.INITIAL_QUOTE_PUNCTUATION.toInt()
        }
    }

    // Normalize curly/variant apostrophes to straight.
    fun normalizeApostrophe(c: Char): Char = when (c) {
        '’', '‘', 'ʼ' -> '\''
        else -> c
    }

    fun isWordBoundary(ch: Char, prev: Char? = null, next: Char? = null): Boolean {
        val normalized = normalizeApostrophe(ch)
        if (normalized.isWhitespace()) return true
        if (normalized in BOUNDARY) return true
        if (normalized == '\'') {
            val prevIsWord = prev?.let { normalizeApostrophe(it) }?.isLetterOrDigit() == true
            return !prevIsWord
        }
        return !normalized.isLetterOrDigit()
    }

    fun commitFrenchSpacedPunctuation(
        inputConnection: android.view.inputmethod.InputConnection,
        punctuation: Char
    ): Boolean {
        if (punctuation !in FRENCH_SPACED_PUNCTUATION || !PunctuationSpacing.fieldAllowsAutoEdits()) return false

        val before = inputConnection.getTextBeforeCursor(16, 0)?.toString().orEmpty()
        val existingSpaceCount = before.takeLastWhile { it == ' ' || it == '\u00A0' || it == NARROW_NO_BREAK_SPACE }.length
        val prefix = before.dropLast(existingSpaceCount)
        if (prefix.isEmpty() || prefix.last().isWhitespace()) {
            return false
        }

        val applied = "$NARROW_NO_BREAK_SPACE$punctuation"
        if (!inputConnection.getSelectedText(0).isNullOrEmpty()) return false
        val spaces = before.takeLast(existingSpaceCount)
        PunctuationSpacing.commitFix(inputConnection, prefix, spaces, applied, spaces + punctuation)
        AutoSpaceTracker.clear()
        return true
    }

    fun commitCommaSpace(
        inputConnection: android.view.inputmethod.InputConnection,
        punctuation: Char
    ): Boolean {
        if (punctuation != COMMA_SPACE_PUNCTUATION || !PunctuationSpacing.fieldAllowsAutoEdits()) return false

        val before = inputConnection.getTextBeforeCursor(16, 0)?.toString().orEmpty()
        if (!inputConnection.getSelectedText(0).isNullOrEmpty()) return false
        if (before.endsWith("$punctuation ")) {
            AutoSpaceTracker.markAutoSpace()
            return true
        }
        val hasComma = before.endsWith(punctuation)
        val beforeComma = if (hasComma) before.dropLast(1) else before
        val spaces = beforeComma.takeLastWhile { it == ' ' }
        val removed = if (hasComma) spaces + punctuation else spaces
        PunctuationSpacing.commitFix(inputConnection, beforeComma.dropLast(spaces.length), removed, "$punctuation ", spaces + punctuation)
        AutoSpaceTracker.markAutoSpace()
        return true
    }
}
