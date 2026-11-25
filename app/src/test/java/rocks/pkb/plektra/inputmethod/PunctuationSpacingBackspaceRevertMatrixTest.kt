package rocks.pkb.plektra.inputmethod

import android.app.Activity
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.LinearLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PunctuationSpacingBackspaceRevertMatrixTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ServiceController<PhysicalKeyboardInputMethodService>? = null
    private lateinit var chat: ActivityController<Activity>
    private var exposesExtractedText = true
    private var selectionTrace: MutableList<Int>? = null
    private lateinit var ime: PhysicalKeyboardInputMethodService
    private lateinit var editText: EditText
    private lateinit var ic: InputConnection
    private var clock = 10_000L

    @After
    fun tearDown() {
        controller?.destroy()
        if (::chat.isInitialized) chat.pause().stop().destroy()
        DeviceSpecific.clearTestOverrides()
    }

    private enum class Path { HARDWARE_KEY, ALT_KEY, SYM_PAGE, VARIATIONS_ROW, SOFTWARE_KEY }

    private enum class FieldType(val inputType: Int, val autoEdits: Boolean, val suggestions: Boolean = autoEdits) {
        TEXT(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, autoEdits = true),
        TEXT_SUGGESTIONS_OFF(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, autoEdits = true, suggestions = false),
        URL(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, autoEdits = false),
        EMAIL(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, autoEdits = false),
        PASSWORD(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, autoEdits = false),
        NUMBER(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED, autoEdits = false)
    }

    private val menuCharacters = Punctuation.AUTO_SPACE_CANDIDATES.toList()
    private enum class Delete { HARDWARE, SOFTWARE, SYM }

    // Collect every mismatch (rather than stopping at the first path that loses the undo).
    private val failures = mutableListOf<String>()
    private val failedRows = linkedSetOf<String>()
    private var count = 0
    private var row = ""

    private fun scenario(name: String, field: FieldType = FieldType.TEXT, run: () -> Unit) {
        count++
        row = name
        focus(field)
        run()
    }

    private fun expect(expected: String, cursor: Int = expected.length) {
        if (text() != expected || editText.selectionStart != cursor || editText.selectionEnd != cursor) {
            failedRows += row
            failures += "$row: expected <$expected> @$cursor, got <${text()}> @${editText.selectionStart}:${editText.selectionEnd}"
        }
    }

    @Test
    fun onlyTheImmediatelyPrecedingSpacingFixIsReverted() {
        startService()
        removalAndSwapRows()
        commaAndFrenchRows()
        interferenceRows()
        contextRows()
        editorStateRows()
        literalAndCorrectionRows()
        assertRows()
    }

    private fun assertRows() {
        val summary = "$count scenarios; ${failedRows.size} failed: " +
            failedRows.groupingBy { it.substringBefore(':') }.eachCount()
        println(summary)
        assertTrue(summary + "\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun settings(remove: String = "", after: String = "", comma: Boolean = false, french: Boolean = false) {
        SettingsManager.setAutoSpacePunctuation(context, remove)
        SettingsManager.setSpaceAfterPunctuation(context, after)
        SettingsManager.setCommaSpace(context, comma)
        SettingsManager.setFrenchPunctuationSpacing(context, french)
        SettingsManager.setFrenchPunctuationOnlyFrenchLayouts(context, false)
    }

    private fun removalAndSwapRows() {
        for ((i, mark) in ".,!?;:)\"".withIndex()) {
            settings(remove = mark.toString())
            for (path in Path.values()) {
                if (path == Path.HARDWARE_KEY && hardwareKeyFor(mark) == null) continue
                val word = listOf("bugün", "merhaba dünya", "çığ", "how are you", "İzmir", "teşekkür", "göl", "he said \"yes")[i]
                for (spaces in 1..2) scenario("remove: $word $spaces $mark $path") {
                    val original = word + " ".repeat(spaces)
                    ic.commitText(original, 1)
                    typePunctuation(mark, path)
                    expect(word + mark)
                    backspace(Delete.values()[(i + spaces + path.ordinal) % 3])
                    expect(original + mark)
                    backspace(Delete.HARDWARE)
                    expect(original)
                }
            }
        }
        settings(remove = "?!")
        for (path in Path.values()) for (delete in Delete.values()) scenario("swap: $path $delete") {
            pickWord()
            typePunctuation('?', path)
            expect("how are you? ")
            backspace(delete)
            expect("how are you ?")
            backspace(Delete.HARDWARE)
            expect("how are you ")
        }
        scenario("tracker: variation without boundary listener") {
            pickWord()
            VariationButtonHandler.createStaticVariationClickListener("?", ic, context).onClick(null)
            expect("how are you? ")
            backspace(Delete.HARDWARE)
            expect("how are you ?")
        }
    }

    private fun interferenceRows() {
        settings(remove = ".?!", after = ".")
        for (action in listOf("immediate", "type x", "dpad", "touch", "software cursor", "new field")) {
            scenario("interference: $action") {
                ic.commitText("hello ", 1)
                typePunctuation('.', Path.HARDWARE_KEY)
                expect("hello.")
                when (action) {
                    "type x" -> { pressKey(KeyEvent.KEYCODE_X); expect("hello. x") }
                    "dpad" -> { pressKey(KeyEvent.KEYCODE_DPAD_LEFT); select(6, 6) }
                    "touch" -> { select(3, 3); select(6, 6) }
                    "software cursor" -> softwareKeyboard().apply { onCursorMove(-1); onCursorMove(1) }
                    "new field" -> { focus(FieldType.TEXT); ic.commitText("hello.", 1) }
                }
                backspace(Delete.HARDWARE)
                expect(when (action) { "immediate" -> "hello ."; "type x" -> "hello. "; else -> "hello" })
            }
        }
        scenario("interference: touch moves between identical suffixes") {
            val phrase = "this is a long repeated phrase"
            val prefix = "$phrase? $phrase"
            ic.commitText("$prefix  ", 1)
            typePunctuation('?', Path.SOFTWARE_KEY)
            expect("$prefix?")
            select(phrase.length + 1, phrase.length + 1)
            select(prefix.length + 1, prefix.length + 1)
            backspace(Delete.HARDWARE)
            expect(prefix)
        }
        for (callback in listOf(false, true)) scenario("selection: callback=$callback") {
            ic.commitText("hello tail", 1)
            select(6, 6)
            typePunctuation('?', Path.HARDWARE_KEY)
            expect("hello?tail", 6)
            // The text *before* this selection still matches the fix exactly.
            if (callback) select(6, 10) else editText.setSelection(6, 10)
            backspace(Delete.HARDWARE)
            expect("hello?")
        }
        scenario("chain: two marks") {
            pickWord()
            typePunctuation('?', Path.HARDWARE_KEY)
            expect("how are you? ")
            typePunctuation('!', Path.HARDWARE_KEY)
            expect("how are you?!")
            backspace(Delete.HARDWARE)
            expect("how are you? !")
            backspace(Delete.HARDWARE)
            expect("how are you? ")
        }
        settings(remove = ".,", comma = true)
        scenario("chain: two words") {
            ic.commitText("hello ", 1)
            typePunctuation(',', Path.HARDWARE_KEY)
            expect("hello, ")
            typeWords("worl")
            pickSuggestion("world")
            expect("hello, world ")
            typePunctuation('.', Path.HARDWARE_KEY)
            expect("hello, world. ")
            backspace(Delete.HARDWARE)
            expect("hello, world .")
        }
    }

    private fun contextRows() {
        val contexts = listOf("word" to "", "word " to "", "word  " to "tail", "word\n" to "")
        for (mark in ".,:;?!") {
            settings(remove = "$mark", after = "$mark") // Both: don't insert the next space yet.
            val inputs = Path.values().associate { it.name to { typePunctuation(mark, it) } }.toMutableMap()
            if (mark in ":;") inputs["held Alt"] = { heldChord(KeyEvent.KEYCODE_ALT_LEFT, altKeyFor(mark)) }
            val (key, shifted) = hardwareKeyFor(mark)!!
            if (shifted) inputs["held Shift"] = { heldChord(KeyEvent.KEYCODE_SHIFT_LEFT, key) }
            for ((prefix, suffix) in contexts) for ((path, typeMark) in inputs) {
                scenario("context: ${prefix.replace("\n", "\\n")}|$suffix $mark $path") {
                    ic.commitText(prefix + suffix, 1)
                    select(prefix.length, prefix.length)
                    typeMark()
                    val applied = prefix.trimEnd(' ') + mark
                    expect(applied + suffix, applied.length)
                    backspace(Delete.HARDWARE)
                    // Without a spacing edit there is nothing to revert (including after newline).
                    val first = if (prefix.endsWith(' ')) prefix + mark else prefix
                    expect(first + suffix, first.length)
                    backspace(Delete.HARDWARE)
                    expect(first.dropLast(1) + suffix, first.length - 1)
                }
            }
        }
        for (path in Path.values()) scenario("deferred: two removed spaces $path") {
            settings(remove = ":", after = ":")
            ic.commitText("word  ", 1)
            typePunctuation(':', path)
            expect("word:")
            pressKey(KeyEvent.KEYCODE_X)
            expect("word: x")
        }
    }

    private fun editorStateRows() {
        for (extract in listOf(true, false)) for (path in Path.values()) {
            exposesExtractedText = extract
            for (mark in ":,?") scenario("composing: $mark $path extracted=$extract") {
                settings(remove = ":", comma = true, french = mark == '?')
                ic.commitText("Ex ", 1)
                ic.setComposingText("two  ", 1)
                assertEquals(3, BaseInputConnection.getComposingSpanStart(editText.text))
                typePunctuation(mark, path)
                expect(when (mark) { ',' -> "Ex two, "; '?' -> "Ex two\u202f?"; else -> "Ex two:" })
                backspace(Delete.HARDWARE)
                expect("Ex two  $mark")
                backspace(Delete.HARDWARE)
                expect("Ex two  ")
            }
        }
        exposesExtractedText = true
        for (mark in ":,?") for (path in Path.values()) scenario("replace selection: $mark $path") {
            settings(remove = ":", comma = true, french = mark == '?')
            ic.commitText("Ex two selected!", 1)
            select(7, 15)
            typePunctuation(mark, path)
            expect("Ex two $mark!", 8)
            backspace(Delete.HARDWARE)
            expect("Ex two !", 7)
        }
    }

    @Test
    fun q25ChatReplay() {
        startService()
        settings(remove = ":;", comma = true)
        enableCorrections(mapOf("lagh" to "lakh", "twp" to "two"))
        scenario("video: undo word correction") {
            typeWords("Lagh")
            expect("Lagh")
            pressKey(KeyEvent.KEYCODE_SPACE)
            expect("Lakh ")
            backspace(Delete.HARDWARE)
            expect("Lagh")
        }
        scenario("correction: manual space must not reopen previous word", FieldType.TEXT_SUGGESTIONS_OFF) {
            ic.commitText("twp ", 1)
            typePunctuation(':', Path.HARDWARE_KEY)
            expect("twp:")
            backspace(Delete.HARDWARE)
            expect("twp :")
        }
        // Don't let the emulator's default dictionary change the English word "two" to "tuo".
        SettingsManager.setAutoReplaceOnSpaceEnter(context, false)
        for (path in listOf(Path.ALT_KEY, Path.SYM_PAGE, Path.SOFTWARE_KEY)) scenario("video: manual space $path") {
            typeWords("Ex two ")
            repeat(2) {
                expect("Ex two ")
                val trace = mutableListOf(editText.selectionEnd)
                selectionTrace = trace
                typePunctuation(':', path)
                selectionTrace = null
                if (6 in trace) { failedRows += row; failures += "$row: exposed partial edit: $trace" }
                expect("Ex two:")
                backspace(Delete.HARDWARE)
                expect("Ex two :")
                backspace(Delete.HARDWARE)
                expect("Ex two ")
            }
        }
        for (pick in listOf(true, false)) scenario("video: auto space pick=$pick") {
            if (pick) { typeWords("Ex tw"); pickSuggestion("two") } else {
                SettingsManager.setAutoReplaceOnSpaceEnter(context, true)
                typeWords("Ex twp ")
                SettingsManager.setAutoReplaceOnSpaceEnter(context, false)
            }
            expect("Ex two ")
            typePunctuation(':', Path.ALT_KEY)
            expect("Ex two: ")
            backspace(Delete.HARDWARE)
            expect("Ex two :")
            backspace(Delete.HARDWARE)
            expect("Ex two ")
        }
        scenario("video: comma-space and echoed DEL") {
            typeWords("Ex two")
            typePunctuation(',', Path.HARDWARE_KEY)
            expect("Ex two, ")
            val down = clock
            backspace(Delete.HARDWARE)
            expect("Ex two,")
            val consumed = echo(KeyEvent.KEYCODE_DEL, down + 30 + 18)
            expect("Ex two,")
            if (!consumed) { failedRows += row; failures += "$row: both DEL echo actions must be consumed" }
            backspace(Delete.HARDWARE)
            expect("Ex two")
            // Same virtual device, but not a duplicate of the last real DEL (outside 60 ms).
            echo(KeyEvent.KEYCODE_DEL, clock)
            expect("Ex tw")
        }
        for ((modifier, key, mark) in listOf(
            Triple(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_H, ':'),
            Triple(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_J, ';'),
            Triple(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SEMICOLON, ':')
        )) scenario("chord: $modifier + $key") {
            // A moved suggestion space is already a batched edit on 26.10.31. This isolates
            // modifier-echo invalidation from the unbatched manual-space regression.
            typeWords("Ex tw")
            pickSuggestion("two")
            heldChord(modifier, key)
            expect("Ex two$mark ")
            backspace(Delete.HARDWARE)
            expect("Ex two $mark")
            backspace(Delete.HARDWARE)
            expect("Ex two ")
        }
        for (extract in listOf(true, false)) scenario("long press: Alt symbol at cursor extracted=$extract") {
            exposesExtractedText = extract
            SettingsManager.setLongPressModifier(context, "alt")
            SettingsManager.setLongPressThreshold(context, 50L)
            typeWords("Ex two ")
            val t = clock
            clock += 200
            dispatch(KeyEvent.KEYCODE_H, KeyEvent.ACTION_DOWN, t, t)
            expect("Ex two h")
            shadowOf(Looper.getMainLooper()).idleFor(60, java.util.concurrent.TimeUnit.MILLISECONDS)
            dispatch(KeyEvent.KEYCODE_H, KeyEvent.ACTION_UP, t, t + 80)
            expect("Ex two:")
            backspace(Delete.HARDWARE)
            expect("Ex two :")
            backspace(Delete.HARDWARE)
            expect("Ex two ")
        }
        assertRows()
    }

    private fun literalAndCorrectionRows() {
        for (field in FieldType.values()) {
            settings(remove = "?!.,\"", comma = true, french = true)
            val literals = mutableListOf("3.14", "example.com", "nasılsın \"")
            if (!field.autoEdits) literals += listOf("bonjour ?", "hello ,")
            for (literal in literals) scenario("literal: $field <$literal>", field) {
                // Type the punctuation as well as its suffix through the keyboard.
                val i = literal.indexOfFirst { it in ".\"?," }
                ic.commitText(literal.take(i), 1)
                literal.drop(i).forEach { softwareKey(it.toString()) }
                expect(literal)
                backspace(Delete.HARDWARE)
                expect(literal.dropLast(1))
            }
        }
        settings(remove = ".")
        enableCorrections(mapOf("teh" to "the"))
        for (dot in listOf(true, false)) scenario("autocorrect: dot=$dot") {
            typeWords("teh ")
            expect("the ")
            if (dot) { typePunctuation('.', Path.HARDWARE_KEY); expect("the. ") }
            backspace(Delete.HARDWARE)
            expect(if (dot) "the ." else "teh")
        }
    }

    private fun enableCorrections(entries: Map<String, String>) {
        SettingsManager.saveCustomAutoCorrections(context, "test", entries)
        SettingsManager.setAutoCorrectEnabledLanguages(context, setOf("test"))
        SettingsManager.setAutoCorrectEnabled(context, true)
        AutoCorrector.loadCorrections(context.assets, context)
        SettingsManager.setAutoReplaceOnSpaceEnter(context, true)
    }

    private fun select(start: Int, end: Int) = ic.setSelection(start, end)

    private fun pickWord() {
        ic.commitText("how are yo", 1)
        pickSuggestion("you")
        expect("how are you ")
    }

    private fun backspace(delete: Delete) {
        when (delete) {
            Delete.HARDWARE -> pressKey(KeyEvent.KEYCODE_DEL)
            Delete.SOFTWARE -> softwareKeyboard().onBackspace()
            Delete.SYM -> {
                val container = LinearLayout(context)
                val bar = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController")
                val status = ReflectionHelpers.getField<StatusBarController>(bar, "inputStatusBar")
                ReflectionHelpers.setField(status, "emojiKeyboardContainer", container)
                ReflectionHelpers.callInstanceMethod<Unit>(status, "updateSoftwareSymbolKeyboard",
                    ReflectionHelpers.ClassParameter.from(Map::class.java, emptyMap<Int, String>()),
                    ReflectionHelpers.ClassParameter.from(StatusBarController.StatusSnapshot::class.java, snapshot().copy(symPage = 2)),
                    ReflectionHelpers.ClassParameter.from(InputConnection::class.java, ic))
                val thirdRow = container.getChildAt(2) as LinearLayout
                assertTrue(thirdRow.getChildAt(thirdRow.childCount - 1).performClick())
            }
        }
        idle()
    }

    private fun commaAndFrenchRows() {
        for (remove in listOf("", ",")) for (original in listOf("hello", "hello ", "hello  ", "hello ,")) {
            settings(remove = remove, comma = true)
            for (field in listOf(FieldType.TEXT, FieldType.TEXT_SUGGESTIONS_OFF)) {
                scenario("comma: $remove <$original> $field", field) {
                    ic.commitText(original, 1)
                    typePunctuation(',', Path.HARDWARE_KEY)
                    expect("hello, ")
                    backspace(Delete.HARDWARE)
                    expect(original.removeSuffix(",") + ',')
                    backspace(Delete.HARDWARE)
                    expect(original.removeSuffix(","))
                }
            }
        }
        settings(remove = "?!;:", french = true)
        for ((i, mark) in "?!;:".withIndex()) for (gap in listOf("", " ", "  ", "\u00a0", "\u202f")) {
            scenario("French: $mark <$gap>") {
                ic.commitText("bonjour$gap", 1)
                typePunctuation(mark, Path.SOFTWARE_KEY)
                expect("bonjour\u202f$mark")
                backspace(Delete.values()[i % 3])
                // An already-correct narrow space wasn't a spacing fix.
                expect("bonjour$gap" + if (gap == "\u202f") "" else mark.toString())
            }
        }
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
        chat = Robolectric.buildActivity(Activity::class.java).setup()
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
        val info = EditorInfo()
        editText = EditText(chat.get()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        chat.get().setContentView(editText)
        editText.requestFocus()
        ic = ChatConnection(editText, info)
        // Keep the editor permissive even when testing IME policy for restricted fields.
        info.inputType = field.inputType
        info.packageName = "com.example.chat"
        info.fieldId = clock.toInt()
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

    /** Robolectric doesn't deliver the IMM→IME callbacks. Report the actual Editable selection
     * and composing spans after each editor operation, coalescing nested batches just as IMM does. */
    private inner class ChatConnection(val editor: EditText, info: EditorInfo) :
        InputConnectionWrapper(editor.onCreateInputConnection(info), false) {
        private var depth = 0
        private var start = editor.selectionStart
        private var end = editor.selectionEnd

        private fun report() {
            if (depth != 0) return
            val oldStart = start
            val oldEnd = end
            start = editor.selectionStart
            end = editor.selectionEnd
            selectionTrace?.add(end)
            ime.onUpdateSelection(oldStart, oldEnd, start, end,
                BaseInputConnection.getComposingSpanStart(editor.text), BaseInputConnection.getComposingSpanEnd(editor.text))
        }

        override fun beginBatchEdit() = super.beginBatchEdit().also { depth++ }
        override fun endBatchEdit() = super.endBatchEdit().also { depth--; report() }
        override fun commitText(text: CharSequence?, pos: Int) = super.commitText(text, pos).also { report() }
        override fun deleteSurroundingText(before: Int, after: Int) = super.deleteSurroundingText(before, after).also { report() }
        override fun setComposingText(text: CharSequence?, pos: Int) = super.setComposingText(text, pos).also { report() }
        override fun finishComposingText() = super.finishComposingText().also { report() }
        override fun setComposingRegion(start: Int, end: Int) = super.setComposingRegion(start, end).also { report() }
        override fun setSelection(start: Int, end: Int) = super.setSelection(start, end).also { report() }
        override fun sendKeyEvent(event: KeyEvent) = editor.dispatchKeyEvent(event).also { report() }
        override fun getExtractedText(request: ExtractedTextRequest, flags: Int) =
            if (exposesExtractedText) super.getExtractedText(request, flags) else null
    }

    private fun typeWords(words: String) {
        words.forEach { char ->
            if (char == ' ') pressKey(KeyEvent.KEYCODE_SPACE)
            else pressKey(KeyEvent.KEYCODE_A + (char.lowercaseChar() - 'a'), if (char.isUpperCase()) KeyEvent.META_SHIFT_ON else 0)
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
                val t = clock
                pressKey(KeyEvent.KEYCODE_ALT_LEFT)
                assertTrue(echo(KeyEvent.KEYCODE_ALT_LEFT, t + 45))
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
        dispatch(keyCode, KeyEvent.ACTION_DOWN, start, start, metaState)
        dispatch(keyCode, KeyEvent.ACTION_UP, start, start + 30, metaState)
        idle()
    }

    private fun dispatch(key: Int, action: Int, down: Long, time: Long, meta: Int = 0, echo: Boolean = false): Boolean {
        val event = if (key == KeyEvent.KEYCODE_SEMICOLON) {
            // Robolectric's virtual KCM lacks this entry. Supply the Q25 glyph, not a text commit.
            object : KeyEvent(down, time, action, key, 0, meta, 0, 0, FLAG_FROM_SYSTEM) {
                override fun getUnicodeChar() = getUnicodeChar(metaState)
                override fun getUnicodeChar(metaState: Int) = if (metaState and META_SHIFT_ON != 0) ':'.code else ';'.code
            }
        } else KeyEvent(down, time, action, key, 0, meta, if (echo) -1 else 0, 0, if (echo) 0 else KeyEvent.FLAG_FROM_SYSTEM)
        val consumed = if (action == KeyEvent.ACTION_DOWN) ime.onKeyDown(key, event) else ime.onKeyUp(key, event)
        if (!consumed) ic.sendKeyEvent(event)
        return consumed
    }

    private fun heldChord(modifier: Int, key: Int) {
        val meta = if (modifier == KeyEvent.KEYCODE_SHIFT_LEFT) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            else KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        val t = clock
        clock += 300
        dispatch(modifier, KeyEvent.ACTION_DOWN, t, t, meta)
        dispatch(key, KeyEvent.ACTION_DOWN, t + 40, t + 40, meta)
        dispatch(key, KeyEvent.ACTION_UP, t + 40, t + 75, meta)
        dispatch(modifier, KeyEvent.ACTION_UP, t, t + 110)
        assertTrue(echo(modifier, t + 125))
        idle()
    }

    private fun echo(key: Int, time: Long): Boolean {
        val down = dispatch(key, KeyEvent.ACTION_DOWN, time, time, echo = true)
        val up = dispatch(key, KeyEvent.ACTION_UP, time, time, echo = true)
        return down && up
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun text() = editText.text.toString()

    private fun altManager() = ReflectionHelpers.getField<AlternateCharacterManager>(ime, "alternateCharacterManager")

    /** Hardware keys from the Q25 trace or the Robolectric virtual key map. */
    private fun hardwareKeyFor(char: Char): Pair<Int, Boolean>? = when (char) {
        '.' -> KeyEvent.KEYCODE_PERIOD to false
        ',' -> KeyEvent.KEYCODE_COMMA to false
        ';' -> KeyEvent.KEYCODE_SEMICOLON to false
        ':' -> KeyEvent.KEYCODE_SEMICOLON to true
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
        KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_T,
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
