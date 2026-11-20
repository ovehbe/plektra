package rocks.pkb.plektra.inputmethod

import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.AltModifierBinding
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.SymPagesConfig
import rocks.pkb.plektra.core.AutoSpaceTracker
import rocks.pkb.plektra.core.DeferredPunctuationSpaceTracker
import rocks.pkb.plektra.core.InputContextState
import rocks.pkb.plektra.core.Punctuation
import rocks.pkb.plektra.core.SymLayoutController
import rocks.pkb.plektra.inputmethod.aospkeyboard.AospKeyboardView
import rocks.pkb.plektra.inputmethod.suggestions.SuggestionButtonHandler

/**
 * The punctuation spacing menu ("Remove before" and "Before next text" per character) applied
 * while typing on a Q25: every menu character × option × input path × preceding text × field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PunctuationSpacingMatrixTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ServiceController<PhysicalKeyboardInputMethodService>? = null
    private lateinit var ime: PhysicalKeyboardInputMethodService
    private lateinit var editText: EditText
    private lateinit var ic: InputConnection
    private var clock = 10_000L

    @After
    fun tearDown() {
        controller?.destroy()
        DeviceSpecific.clearTestOverrides()
    }

    private enum class Option(val removeBefore: Boolean, val beforeNextText: Boolean) {
        NONE(false, false), REMOVE_BEFORE(true, false), BEFORE_NEXT_TEXT(false, true), BOTH(true, true)
    }

    private enum class Path { HARDWARE_KEY, ALT_KEY, SYM_PAGE, VARIATIONS_ROW, SOFTWARE_KEY }

    /** [text]: what the context leaves before the cursor; [autoSpace]: its last space is Plektra's own. */
    private enum class Context(val text: String, val autoSpace: Boolean = false, val needsSuggestions: Boolean = false) {
        ONE_SPACE("how are you "),
        TWO_SPACES("how are you  "),
        NO_SPACE("how are you"),
        NEWLINE("how are you\n"),
        SPACE_AFTER_NEWLINE("how are you\n "),
        START_OF_FIELD(""),
        SPACE_AT_START(" "),
        AFTER_EMOJI("how are you \uD83D\uDE00 "),
        INSIDE_QUOTE("he said \"yes "),
        SOFTWARE_SPACE("how are you "),
        SUGGESTION_PICK("how are you ", autoSpace = true, needsSuggestions = true),
        SUGGESTION_PICK_AND_SPACE("how are you  ", needsSuggestions = true)
    }

    private enum class FieldType(val inputType: Int, val autoEdits: Boolean, val suggestions: Boolean = autoEdits) {
        TEXT(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, autoEdits = true),
        TEXT_SUGGESTIONS_OFF(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, autoEdits = true, suggestions = false),
        URL(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, autoEdits = false),
        EMAIL(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, autoEdits = false),
        PASSWORD(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, autoEdits = false),
        NUMBER(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED, autoEdits = false)
    }

    private data class Scenario(
        val char: Char, val option: Option, val path: Path, val context: Context, val field: FieldType
    ) {
        override fun toString() = "'$char' $option $path $context $field"
    }

    private val menuCharacters = Punctuation.AUTO_SPACE_CANDIDATES.toList()

    @Test
    fun menuRulesApplyWhileTyping() {
        startService()
        val failures = mutableListOf<String>()
        val failedScenarios = linkedSetOf<Scenario>()
        var scenarios = 0
        for (char in menuCharacters) for (option in Option.values()) {
            SettingsManager.setAutoSpacePunctuation(context, if (option.removeBefore) char.toString() else "")
            SettingsManager.setSpaceAfterPunctuation(context, if (option.beforeNextText) char.toString() else "")
            for (field in FieldType.values()) for (precedingContext in Context.values()) for (path in Path.values()) {
                if (!applies(path, char, precedingContext, field)) continue
                val scenario = Scenario(char, option, path, precedingContext, field)
                scenarios++
                focus(field)
                buildContext(precedingContext, field)
                val failuresBefore = failures.size
                val before = text()
                if (before != precedingContext.text) {
                    failures += "$scenario: context typed as ${quote(before)}"
                    failedScenarios += scenario
                    continue
                }
                typePunctuation(char, path)
                val afterMark = text()
                val expected = expectedAfterMark(scenario)
                if (afterMark != expected) failures += "$scenario: ${quote(before)} + '$char' gave ${quote(afterMark)}, expected ${quote(expected)}"
                if (field != FieldType.NUMBER) {
                    pressKey(KeyEvent.KEYCODE_X)
                    val next = text()
                    val expectedNext = expectedAfterNextText(scenario, afterMark)
                    if (next != expectedNext) failures += "$scenario: then 'x' gave ${quote(next)}, expected ${quote(expectedNext)}"
                }
                if (failures.size > failuresBefore) failedScenarios += scenario
            }
        }
        println("Punctuation spacing matrix: $scenarios scenarios, ${failedScenarios.size} failing, ${failures.size} failures")
        java.io.File("build/punctuation-spacing-matrix-failures.txt").writeText(failures.joinToString("\n"))
        failedScenarios.groupBy { "${it.option} ${it.path} ${it.context} ${it.field}" }
            .forEach { (group, list) -> println("  $group: ${list.size}") }
        println("  failing scenarios: ${failedScenarios.size}")
        assertTrue(failures.take(300).joinToString("\n", prefix = "${failures.size} failed expectations:\n"), failures.isEmpty())
        assertEquals(Option.values().size * menuCharacters.sumOf { char ->
            FieldType.values().sumOf { field ->
                Context.values().sumOf { ctx -> Path.values().count { applies(it, char, ctx, field) } }
            }
        }, scenarios)
    }

    private fun typeQuestionMarkAfter(words: String, spaces: Int, field: FieldType = FieldType.TEXT) {
        startService()
        SettingsManager.setAutoSpacePunctuation(context, "?")
        focus(field)
        typeWords(words)
        repeat(spaces) { pressKey(KeyEvent.KEYCODE_SPACE) }
        typePunctuation('?', Path.HARDWARE_KEY)
    }

    @Test
    fun backspaceRightAfterRemovalRestoresTheSpaces() {
        typeQuestionMarkAfter("how are you", spaces = 1)
        assertEquals("how are you?", text())
        pressKey(KeyEvent.KEYCODE_DEL)
        assertEquals("how are you ?", text())
        pressKey(KeyEvent.KEYCODE_DEL)
        assertEquals("how are you ", text())
    }

    @Test
    fun backspaceRestoresEveryRemovedSpace() {
        typeQuestionMarkAfter("how are you", spaces = 2)
        assertEquals("how are you?", text())
        pressKey(KeyEvent.KEYCODE_DEL)
        assertEquals("how are you  ?", text())
    }

    @Test
    fun backspaceAfterMoreTypingDeletesNormally() {
        typeQuestionMarkAfter("how are you", spaces = 1)
        pressKey(KeyEvent.KEYCODE_X)
        pressKey(KeyEvent.KEYCODE_DEL)
        assertEquals("how are you?", text())
        pressKey(KeyEvent.KEYCODE_DEL)
        assertEquals("how are you", text())
    }

    @Test
    fun backspaceAfterMovingTheCursorDeletesNormally() {
        typeQuestionMarkAfter("how are you", spaces = 1)
        ic.setSelection(3, 3)
        pressKey(KeyEvent.KEYCODE_DEL)
        assertEquals("ho are you?", text())
    }

    @Test
    fun softwareBackspaceRightAfterRemovalRestoresTheSpace() {
        startService()
        SettingsManager.setAutoSpacePunctuation(context, "?")
        focus(FieldType.TEXT)
        typeWords("how are you")
        val keyboard = softwareKeyboard()
        keyboard.onText(" ")
        keyboard.onText("?")
        idle()
        assertEquals("how are you?", text())
        keyboard.onBackspace()
        idle()
        assertEquals("how are you ?", text())
    }

    @Test
    fun backspaceAfterMovingAwayAndBackDeletesNormally() {
        typeQuestionMarkAfter("how are you", spaces = 1)
        pressKey(KeyEvent.KEYCODE_DPAD_LEFT)
        pressKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("how are you?", text())
        pressKey(KeyEvent.KEYCODE_DEL)
        assertEquals("how are you", text())
    }

    @Test
    fun spacesInAUrlFieldAreKeptAndBackspaceDeletesTheMark() {
        typeQuestionMarkAfter("how are you", spaces = 1, field = FieldType.URL)
        assertEquals("how are you ?", text())
        pressKey(KeyEvent.KEYCODE_DEL)
        assertEquals("how are you ", text())
    }

    private fun applies(path: Path, char: Char, precedingContext: Context, field: FieldType) =
        (!precedingContext.needsSuggestions || field.suggestions) &&
            (path != Path.HARDWARE_KEY || hardwareKeyFor(char) != null) &&
            // Number fields send every key through its Alt mapping, so there is no SYM page there.
            (path != Path.SYM_PAGE || field != FieldType.NUMBER)

    private fun quote(text: String) = "\"" + text.replace("\n", "\\n") + "\""

    private fun expectedAfterMark(scenario: Scenario): String {
        val text = scenario.context.text
        if (!scenario.field.autoEdits || !scenario.option.removeBefore) return text + scenario.char
        val spaces = text.takeLastWhile { it == ' ' }.length
        val prefix = text.dropLast(spaces)
        val startsLine = prefix.isEmpty() || prefix.last() == '\n'
        val opensQuote = scenario.char == '"' && scenario.context != Context.INSIDE_QUOTE
        if (spaces == 0 || startsLine || opensQuote) return text + scenario.char
        // Plektra's own space moves behind the mark unless "Before next text" defers it.
        val movedSpace = if (scenario.context.autoSpace && !scenario.option.beforeNextText) " " else ""
        return prefix + scenario.char + movedSpace
    }

    private fun expectedAfterNextText(scenario: Scenario, afterMark: String): String {
        val deferred = scenario.option.beforeNextText && scenario.field.autoEdits && !afterMark.endsWith(" ")
        return afterMark + (if (deferred) " " else "") + "x"
    }

    private fun startService() {
        DeviceSpecific.clearTestOverrides()
        DeviceSpecific.setBuildFingerprintForTests("zinwa", "zinwa", "Q25", "Q25", "q25")
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setPhysicalKeyboardProfileOverride(context, "Q25")
        SettingsManager.setAltModifierBinding(context, AltModifierBinding.DeviceSym)
        SettingsManager.setSymPagesConfig(context, SymPagesConfig())
        SettingsManager.resetSymMappings(context)
        SettingsManager.saveSymMappingsPage2(context, menuCharacters.associate { symKeyFor(it) to it.toString() })
        SettingsManager.setSymAutoClose(context, true)
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        SettingsManager.setAutoCapitalizeAfterPeriod(context, false)
        SettingsManager.setAutoCorrectEnabled(context, false)
        SettingsManager.setDoubleSpaceToPeriod(context, false)
        SettingsManager.setCommaSpace(context, false)
        SettingsManager.setFrenchPunctuationSpacing(context, false)
        SettingsManager.setSmartQuotes(context, false)
        SettingsManager.setSpacedHyphenToEnDash(context, false)
        SettingsManager.setMidWordQuoteToApostrophe(context, false)
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        val serviceController = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        controller = serviceController
        ime = serviceController.get()
        // The first Alt chord after the service starts only arms the modifier state.
        focus(FieldType.TEXT)
        pressKey(KeyEvent.KEYCODE_ALT_LEFT)
        pressKey(KeyEvent.KEYCODE_Q)
    }

    private fun focus(field: FieldType) {
        SettingsManager.setSuggestionsEnabled(context, field.suggestions)
        SettingsManager.setExperimentalSuggestionsEnabled(context, field.suggestions)
        val info = EditorInfo().apply {
            inputType = field.inputType
            packageName = "com.example.notes"
            fieldId = clock.toInt()
        }
        // The IME sees the field type; the editor itself accepts any text, so a number field's
        // filter cannot hide what the IME did. The editor fills in its own EditorInfo.
        editText = EditText(context).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        ic = editText.onCreateInputConnection(EditorInfo())
        ReflectionHelpers.setField(ime, "mInputConnection", ic)
        ReflectionHelpers.setField(ime, "mStartedInputConnection", ic)
        ReflectionHelpers.setField(ime, "mInputEditorInfo", info)
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        ReflectionHelpers.setField(ime, "isInputViewActive", true)
        idle()
        AutoSpaceTracker.clear()
        DeferredPunctuationSpaceTracker.clear()
        altManager().apply { menuCharacters.forEach { addAltKeyMapping(altKeyFor(it), it.toString()) } }
    }

    private fun buildContext(precedingContext: Context, field: FieldType) {
        if (field == FieldType.NUMBER) {
            ic.commitText(precedingContext.text, 1)
            return
        }
        when (precedingContext) {
            Context.ONE_SPACE -> { typeWords("how are you"); pressKey(KeyEvent.KEYCODE_SPACE) }
            Context.TWO_SPACES -> { typeWords("how are you"); pressKey(KeyEvent.KEYCODE_SPACE); pressKey(KeyEvent.KEYCODE_SPACE) }
            Context.NO_SPACE -> typeWords("how are you")
            Context.NEWLINE -> { typeWords("how are you"); ic.commitText("\n", 1) }
            Context.SPACE_AFTER_NEWLINE -> { typeWords("how are you"); ic.commitText("\n", 1); pressKey(KeyEvent.KEYCODE_SPACE) }
            Context.START_OF_FIELD -> Unit
            Context.SPACE_AT_START -> pressKey(KeyEvent.KEYCODE_SPACE)
            Context.AFTER_EMOJI -> { typeWords("how are you"); pressKey(KeyEvent.KEYCODE_SPACE); ic.commitText("\uD83D\uDE00", 1); pressKey(KeyEvent.KEYCODE_SPACE) }
            Context.INSIDE_QUOTE -> { typeWords("he said"); pressKey(KeyEvent.KEYCODE_SPACE); ic.commitText("\"", 1); typeWords("yes"); pressKey(KeyEvent.KEYCODE_SPACE) }
            Context.SOFTWARE_SPACE -> { typeWords("how are you"); softwareKey(" ") }
            Context.SUGGESTION_PICK -> { typeWords("how are yo"); pickSuggestion("you") }
            Context.SUGGESTION_PICK_AND_SPACE -> { typeWords("how are yo"); pickSuggestion("you"); pressKey(KeyEvent.KEYCODE_SPACE) }
        }
    }

    private fun typeWords(words: String) {
        words.forEach { char ->
            if (char == ' ') pressKey(KeyEvent.KEYCODE_SPACE)
            else pressKey(KeyEvent.KEYCODE_A + (char - 'a'))
        }
    }

    private fun pickSuggestion(word: String) {
        SuggestionButtonHandler.createSuggestionClickListener(word, ic, null, shouldDisableAutoCapitalize = true)
            .onClick(View(context))
        idle()
    }

    private fun typePunctuation(char: Char, path: Path) {
        when (path) {
            Path.HARDWARE_KEY -> {
                val (keyCode, shift) = hardwareKeyFor(char)!!
                pressKey(keyCode, if (shift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)
            }
            Path.ALT_KEY -> {
                pressKey(KeyEvent.KEYCODE_ALT_LEFT)
                pressKey(altKeyFor(char))
            }
            Path.SYM_PAGE -> {
                val sym = ReflectionHelpers.getField<SymLayoutController>(ime, "symLayoutController")
                repeat(6) { if (sym.currentSymPage() != SYMBOLS_PAGE) sym.toggleSymPage() }
                assertEquals(SYMBOLS_PAGE, sym.currentSymPage())
                pressKey(symKeyFor(char))
                if (sym.isSymActive()) sym.closeSymPage()
            }
            Path.VARIATIONS_ROW -> {
                val bar = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController")
                val listener = ReflectionHelpers.getField<StatusBarController>(bar, "inputStatusBar").onVariationSelectedListener
                VariationButtonHandler.createStaticVariationClickListener(char.toString(), ic, context, listener).onClick(null)
            }
            Path.SOFTWARE_KEY -> softwareKey(char.toString())
        }
        idle()
    }

    private fun softwareKey(text: String) {
        val bar = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController")
        bar.onSoftwareKeyboardTextInput!!.invoke(text, ic, snapshot())
        idle()
    }

    /** The software keyboard's own key listener, wired to the focused field. */
    private fun softwareKeyboard(): AospKeyboardView.Listener {
        val bar = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController")
        val statusBar = ReflectionHelpers.getField<StatusBarController>(bar, "inputStatusBar")
        val view = AospKeyboardView(context)
        ReflectionHelpers.callInstanceMethod<Unit>(
            statusBar, "configureSoftwareKeyboard",
            ReflectionHelpers.ClassParameter.from(AospKeyboardView::class.java, view),
            ReflectionHelpers.ClassParameter.from(StatusBarController.StatusSnapshot::class.java, snapshot()),
            ReflectionHelpers.ClassParameter.from(InputConnection::class.java, ic),
            ReflectionHelpers.ClassParameter.from(Map::class.java, null)
        )
        return view.listener!!
    }

    private fun snapshot(): StatusBarController.StatusSnapshot {
        val state = ReflectionHelpers.getField<InputContextState>(ime, "inputContextState")
        return StatusBarController.StatusSnapshot(
            capsLockEnabled = false, shiftPhysicallyPressed = false, shiftOneShot = false,
            ctrlLatchActive = false, ctrlPhysicallyPressed = false, ctrlOneShot = false,
            ctrlLatchFromNavMode = false, altLatchActive = false, altPhysicallyPressed = false,
            altOneShot = false, symPage = 0,
            shouldDisableSuggestions = state.shouldDisableSuggestions,
            shouldDisableAutoCorrect = state.shouldDisableAutoCorrect,
            shouldDisableAutoCapitalize = true,
            shouldDisableDoubleSpaceToPeriod = state.shouldDisableDoubleSpaceToPeriod
        )
    }

    private fun pressKey(keyCode: Int, metaState: Int = 0) {
        val start = clock
        clock += 200
        val down = KeyEvent(start, start, KeyEvent.ACTION_DOWN, keyCode, 0, metaState, -1, 0)
        val up = KeyEvent(start, start + 30, KeyEvent.ACTION_UP, keyCode, 0, metaState, -1, 0)
        // A key the IME leaves alone reaches the app, which types it.
        if (!ime.onKeyDown(keyCode, down)) editText.dispatchKeyEvent(down)
        if (!ime.onKeyUp(keyCode, up)) editText.dispatchKeyEvent(up)
        idle()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun text() = editText.text.toString()

    private fun altManager() = ReflectionHelpers.getField<AlternateCharacterManager>(ime, "alternateCharacterManager")

    /** Keys Robolectric's virtual key map types; it has no ';' and no Shift+0. */
    private fun hardwareKeyFor(char: Char): Pair<Int, Boolean>? = when (char) {
        '.' -> KeyEvent.KEYCODE_PERIOD to false
        ',' -> KeyEvent.KEYCODE_COMMA to false
        '!' -> KeyEvent.KEYCODE_1 to true
        '?' -> KeyEvent.KEYCODE_SLASH to true
        '\\' -> KeyEvent.KEYCODE_BACKSLASH to false
        '/' -> KeyEvent.KEYCODE_SLASH to false
        '"' -> KeyEvent.KEYCODE_APOSTROPHE to true
        ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET to false
        '}' -> KeyEvent.KEYCODE_RIGHT_BRACKET to true
        else -> null
    }

    private val letterKeys = listOf(
        KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_T,
        KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P,
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S
    )

    private fun altKeyFor(char: Char) = letterKeys[menuCharacters.indexOf(char)]

    // Q and P turn the emoji and symbol pages.
    private val symKeys = listOf(
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G,
        KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_Z,
        KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V
    )

    private fun symKeyFor(char: Char) = symKeys[menuCharacters.indexOf(char)]

    private companion object {
        const val SYMBOLS_PAGE = 2
    }
}
