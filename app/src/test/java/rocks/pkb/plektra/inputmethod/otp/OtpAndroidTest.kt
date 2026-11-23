package rocks.pkb.plektra.inputmethod.otp

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.KeyboardBarArrangement
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.inputmethod.CandidatesBarController
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import rocks.pkb.plektra.inputmethod.StatusBarController.StatusSnapshot
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtpAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private val runtime get() = OtpRuntime.get(context)

    @After
    fun clear() {
        SettingsManager.setOtpEnabled(context, false)
        main.idle()
    }

    @Test
    fun sourcesRespectAllToggleAndAccessCombinationsBeforeReadingPayloads() {
        for (mask in 0 until 64) {
            fun enabled(bit: Int) = mask and (1 shl bit) != 0
            SettingsManager.setOtpEnabled(context, false)
            main.idle()
            SettingsManager.setOtpEnabled(context, enabled(0))
            OtpSource.entries.forEachIndexed { i, source -> SettingsManager.setOtpSourceEnabled(context, source, enabled(i + 1)) }
            if (enabled(4)) shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
            else shadowOf(context).denyPermissions(Manifest.permission.RECEIVE_SMS)
            notificationAccess(enabled(5))
            OtpSource.entries.forEachIndexed { i, source ->
                val access = when (source) {
                    OtpSource.SMS -> enabled(4)
                    OtpSource.NOTIFICATION -> enabled(5)
                    OtpSource.HELPER -> true
                }
                val expected = enabled(0) && enabled(i + 1) && access
                var read = false
                val code = (410000 + mask * 10 + i).toString()
                runtime.receive(source) { read = true; code }
                assertEquals("mask=$mask source=$source read", expected, read)
                assertEquals("mask=$mask source=$source shown", expected, runtime.inbox.current()?.code == code)
                runtime.inbox.current()?.let { runtime.inbox.use(it) { true } }
            }
        }
    }

    @Test
    fun helperAcceptsOnlyTheContractActionAndShortAsciiCodes() {
        SettingsManager.setOtpEnabled(context, true)
        SettingsManager.setOtpSourceEnabled(context, OtpSource.HELPER, true)
        val rows = listOf("1234" to true, "Ab9k" to true, "1357902468" to true, "abc" to false,
            "12345678901" to false, "12-345" to false, "AB 98" to false, " AB98" to false,
            "１２３４" to false, "1\n234" to false, "aBcDe" to true, null to false, 1234 to false)
        val receiver = OtpHelperReceiver()
        for ((value, valid) in rows) {
            val intent = Intent(OtpHelperReceiver.ACTION).setPackage(context.packageName)
            when (value) {
                is String -> intent.putExtra("code", value)
                is Int -> intent.putExtra("code", value)
            }
            receiver.onReceive(context, Intent(intent).setAction("other.action"))
            assertNull(runtime.inbox.current())
            receiver.onReceive(context, intent)
            assertEquals(if (valid) value else null, runtime.inbox.current()?.code)
            runtime.inbox.current()?.let { runtime.inbox.use(it) { true } }
        }
    }

    @Test
    fun smsReassemblesPartsBeforeExtraction() {
        SettingsManager.setOtpEnabled(context, true)
        SettingsManager.setOtpSourceEnabled(context, OtpSource.SMS, true)
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("format", "3gpp")
            putExtra("pdus", arrayOf(smsPart(1, "Doğrulama kodu: 684-"), smsPart(2, "912")))
        }
        assertEquals(listOf("Doğrulama kodu: 684-", "912"),
            Telephony.Sms.Intents.getMessagesFromIntent(intent).map { it?.messageBody })
        OtpSmsReceiver().onReceive(context, intent)
        assertEquals("684912", runtime.inbox.current()?.code)
    }

    private fun smsPart(part: Int, body: String): ByteArray {
        // GSM SMS-DELIVER, no SMSC address, UCS-2, 8-bit concatenation UDH.
        val data = byteArrayOf(5, 0, 3, 11, 2, part.toByte()) + body.toByteArray(Charsets.UTF_16BE)
        return byteArrayOf(0, 0x44, 2, 0x81.toByte(), 0x21, 0, 8, 0x52, 0x10, 0x10, 0, 0, 0, 0,
            data.size.toByte()) + data
    }

    @Suppress("DEPRECATION")
    @Test
    fun notificationTitleAndBodyAreExtractedAndRepostsDeduplicate() {
        SettingsManager.setOtpEnabled(context, true)
        SettingsManager.setOtpSourceEnabled(context, OtpSource.NOTIFICATION, true)
        notificationAccess(true)
        val controller = Robolectric.buildService(OtpNotificationListener::class.java).create()
        try {
            val notification = Notification.Builder(context, "otp-test")
                .setContentTitle("Doğrulama kodu").setContentText("887-765").build()
            val posted = StatusBarNotification("example.bank", "example.bank", 5, null, 0, 0, 0,
                notification, android.os.Process.myUserHandle(), 0)
            controller.get().onNotificationPosted(posted)
            assertEquals("887765", runtime.inbox.current()?.code)
            runtime.inbox.current()?.let { runtime.inbox.use(it) { true } }
            controller.get().onNotificationPosted(posted)
            assertNull(runtime.inbox.current())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun fieldAndBarMatrixTapOnlyCommitsToCurrentEditorAndClears() {
        configure()
        val ctl = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        try {
            val ime = ctl.get()
            val view = requireNotNull(ime.onCreateInputView())
            val fields = listOf(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD to EditorInfo.IME_ACTION_DONE,
                InputType.TYPE_CLASS_NUMBER to EditorInfo.IME_ACTION_NEXT,
                InputType.TYPE_CLASS_TEXT to EditorInfo.IME_ACTION_NONE,
                InputType.TYPE_CLASS_TEXT to EditorInfo.IME_ACTION_SEARCH
            )
            for ((index, field) in fields.withIndex()) for (populated in listOf(false, true)) {
                SettingsManager.setStatusBarVariationsVisible(context, populated)
                val original = focus(ime, field.first, field.second, index * 2)
                val code = (817220 + index * 2 + if (populated) 1 else 0).toString()
                deliver(code)
                val originalChip = view.chip()!!
                assertEquals(code, originalChip.title.text.toString())
                assertEquals("", original.text.toString())
                ime.onFinishInput()
                assertNull(view.chip())
                originalChip.performClick()
                assertEquals("", original.text.toString())
                val focused = focus(ime, field.first, field.second, index * 2 + 1)
                val focusedChip = view.chip()!!
                focusedChip.performClick()
                assertEquals(code, focused.text.toString())
                assertEquals("", original.text.toString())
                assertNull(view.chip())
                focusedChip.performClick()
                assertEquals(code, focused.text.toString())
                deliver(code)
                assertNull(view.chip())
                ime.onFinishInput()
            }
        } finally { ctl.destroy() }
    }

    @Test
    fun emptyBarEasesOpenAndChipClearsOnReplacementExpiryOrDisable() {
        configure()
        SettingsManager.setStatusBarVariationsVisible(context, false)
        SettingsManager.setKeyboardBarHeightAnimation(context, true)
        val ctl = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        try {
            val ime = ctl.get()
            val view = requireNotNull(ime.onCreateInputView())
            val editor = focus(ime, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_NONE, 3)
            layout(view)
            val emptyHeight = if (view.visibility == View.GONE) 0 else view.height
            assertNull(view.chip())
            deliver("654823")
            layout(view)
            val opening = view.clipBounds
            assertTrue("OTP should reveal the row from its empty height", opening != null && opening.height() == emptyHeight)
            assertTrue(emptyHeight < view.height)
            main.idleFor(Duration.ofMillis(500))
            layout(view)
            assertTrue((view.clipBounds?.height() ?: view.height) > emptyHeight)
            val stale = view.chip()!!
            deliver("654824")
            stale.performClick()
            assertEquals("", editor.text.toString())
            assertEquals("654824", view.chip()?.title?.text.toString())
            main.idleFor(Duration.ofMinutes(2))
            assertNull(view.chip())
            deliver("654825")
            assertNotNull(view.chip())
            SettingsManager.setOtpSourceEnabled(context, OtpSource.HELPER, false)
            main.idle()
            assertNull(view.chip())
        } finally { ctl.destroy() }
    }

    private enum class Dismiss { CLOSE, SWIPE, TIMEOUT, LETTER, DIGIT, PUNCTUATION, SPACE, SOFT_CHAR, SOFT_SPACE, INSERT }

    @Test
    fun otpDismissMatrix() {
        configure()
        SettingsManager.setAutoCapitalizeFirstLetter(context, false)
        OtpSource.entries.forEach { SettingsManager.setOtpSourceEnabled(context, it, true) }
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
        notificationAccess(true)
        val ctl = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        val failures = mutableListOf<String>()
        var rows = 0
        try {
            val ime = ctl.get()
            val root = requireNotNull(ime.onCreateInputView())
            val bar = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController")
            for (exit in Dismiss.entries) for (replay in OtpSource.entries) for (changeField in listOf(false, true)) {
                rows++
                val code = (650000 + rows).toString()
                val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                val action = EditorInfo.IME_ACTION_DONE
                runCatching {
                    val first = focus(ime, type, action, rows * 3)
                    val receivedAt = android.os.SystemClock.elapsedRealtime()
                    runtime.receive(OtpSource.SMS) { code }
                    val original = checkNotNull(root.chip()) { "SMS chip missing" }
                    check(first.text.isEmpty()) { "arrival typed" }
                    val editor = if (changeField) focus(ime, type, action, rows * 3 + 1) else first
                    val target = checkNotNull(root.chip()) { "pending chip lost on focus" }
                    if (changeField) {
                        original.performClick()
                        check(first.text.isEmpty() && editor.text.isEmpty() && root.chip() === target) { "stale tap" }
                    }
                    layout(root)
                    val expected = when (exit) {
                        Dismiss.CLOSE -> {
                            checkNotNull(root.findClose()) { "no close button" }.performClick(); ""
                        }
                        Dismiss.SWIPE -> {
                            val t = android.os.SystemClock.uptimeMillis()
                            for ((i, event) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
                                MotionEvent.obtain(t, t + i * 20L, event, target.width / 2f + 120f * i, target.height / 2f, 0).also {
                                    target.dispatchTouchEvent(it); it.recycle()
                                }
                            }
                            ""
                        }
                        Dismiss.TIMEOUT -> {
                            // Switching fields can advance Robolectric's clock; don't restart the deadline there.
                            main.idleFor(Duration.ofMillis(receivedAt + 59_999 - android.os.SystemClock.elapsedRealtime()))
                            runtime.receive(replay) { code }
                            check(root.chip() === target) { "expired early or duplicate replaced chip" }
                            main.idleFor(Duration.ofMillis(1)); ""
                        }
                        Dismiss.INSERT -> { target.performClick(); code }
                        Dismiss.SOFT_CHAR, Dismiss.SOFT_SPACE -> {
                            val text = if (exit == Dismiss.SOFT_SPACE) " " else "é"
                            bar.onSoftwareKeyboardTextInput!!.invoke(text, ime.currentInputConnection, StatusSnapshot(
                                false, false, false, false, false, false, false, false, false, false, 0))
                            text
                        }
                        else -> {
                            val (key, text) = when (exit) {
                                Dismiss.SPACE -> KeyEvent.KEYCODE_SPACE to " "
                                Dismiss.DIGIT -> KeyEvent.KEYCODE_7 to "7"
                                Dismiss.PUNCTUATION -> KeyEvent.KEYCODE_PERIOD to "."
                                else -> KeyEvent.KEYCODE_A to "a"
                            }
                            val down = KeyEvent(KeyEvent.ACTION_DOWN, key)
                            val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
                            if (!ime.onKeyDown(key, down)) editor.dispatchKeyEvent(down)
                            if (!ime.onKeyUp(key, up)) editor.dispatchKeyEvent(up)
                            text
                        }
                    }
                    main.idle()
                    check(root.chip() == null) { "still shown after $exit" }
                    target.performClick()
                    check(editor.text.toString() == expected) { "unexpected edit" }
                    runtime.receive(replay) { code }
                    check(root.chip() == null) { "duplicate resurrected" }
                    ime.onFinishInput()
                    main.idleFor(Duration.ofMinutes(3))
                    runtime.receive(replay) { code }
                    focus(ime, type, action, rows * 3 + 2)
                    check(root.chip() == null) { "late replay resurrected on refocus" }
                    deliver((660000 + rows).toString())
                    check(root.chip() != null) { "different code suppressed" }
                }.onFailure { failures += "$exit/$replay/change=$changeField: $it" }
                ime.onFinishInput()
            }
        } finally { ctl.destroy() }
        println("OTP dismiss matrix: $rows rows, ${failures.size} failing")
        assertEquals(60, rows)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun View.findClose(): View? {
        if (visibility != View.VISIBLE) return null
        if (this is TextView && text.toString() == "✕") return this
        return (this as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { group.getChildAt(it).findClose() } }
    }

    private enum class Exit { TAP, EXPIRY, REPLACEMENT, FIELD_CHANGE, FINISH }

    @Test
    fun barReturnsToNoChipGeometryAcrossEditorLifecycles() {
        val flags = listOf(false, true)
        val fields = listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val failures = mutableListOf<String>()
        var rows = 0
        configure()
        KeyboardBarConfiguration.setArrangement(context, KeyboardBarArrangement.SUGGESTIONS_FIRST)
        SettingsManager.setKeyboardBarHeightAnimation(context, true)
        for (populated in flags) for (hideEmpty in flags) for (hideVariations in flags) for (strip in flags) {
            SettingsManager.setStatusBarVariationsVisible(context, populated)
            SettingsManager.setStaticVariationBarModeEnabled(context, populated)
            SettingsManager.setSuggestionsEnabled(context, populated)
            SettingsManager.setAlwaysShowSuggestionsBarHideSensitive(context, hideEmpty)
            SettingsManager.setAlwaysShowSuggestionsBarHideVariations(context, hideVariations)
            SettingsManager.setModifierIndicators(context,
                if (strip) setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP) else emptySet())
            val ctl = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
            try {
                val ime = ctl.get()
                val bar = requireNotNull(ime.onCreateInputView()) as ViewGroup
                for (field in fields) for (exit in Exit.entries) {
                    rows++
                    fun exercise(withChip: Boolean): List<Int> {
                        val id = rows * 4 + if (withChip) 2 else 0
                        val action = if (field == fields.last()) EditorInfo.IME_ACTION_SEND else EditorInfo.IME_ACTION_DONE
                        val code = (514000 + rows * 2).toString()
                        val editor = focus(ime, field, action, id)
                        geometry(bar)
                        if (withChip) deliver(code)
                        val first = bar.chip()
                        check(!withChip || first != null) { "chip not shown" }
                        when (exit) {
                            Exit.TAP -> {
                                if (withChip) first!!.performClick() else ime.currentInputConnection.commitText(code, 1)
                                check(editor.text.toString() == code) { "insert failed" }
                            }
                            Exit.REPLACEMENT -> {
                                if (withChip) {
                                    deliver((code.toInt() + 1).toString())
                                    check(bar.chip() !== first && bar.chip() != null) { "not replaced" }
                                    first!!.performClick()
                                    check(editor.text.isEmpty()) { "stale click inserted" }
                                }
                                main.idleFor(Duration.ofMinutes(2))
                            }
                            Exit.EXPIRY -> main.idleFor(Duration.ofMinutes(2))
                            Exit.FIELD_CHANGE -> {
                                focus(ime, if (field == fields.first()) fields.last() else fields.first(), action, id + 1)
                                check(!withChip || bar.chip() != null) { "pending chip lost on focus" }
                                main.idleFor(Duration.ofMinutes(2))
                            }
                            Exit.FINISH -> {
                                ime.onFinishInput()
                                check(bar.chip() == null) { "chip remained after finish" }
                                main.idleFor(Duration.ofMinutes(2))
                                focus(ime, field, action, id)
                            }
                        }
                        check(bar.chip() == null) { "chip remained after exit" }
                        return geometry(bar).also { ime.onFinishInput() }
                    }
                    val error = runCatching {
                        val expected = exercise(false)
                        val actual = exercise(true)
                        check(expected == actual) { "expected $expected, got $actual" }
                    }.exceptionOrNull()
                    if (error != null) failures += "populated=$populated hideEmpty=$hideEmpty hideVariations=$hideVariations " +
                        "strip=$strip field=$field $exit: $error"
                }
            } finally { ctl.destroy() }
        }
        println("OTP bar lifecycle matrix: $rows rows, ${failures.size} failing")
        assertEquals(320, rows)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // Compare the actual settled chrome and each row (including suggestions, actions and LEDs),
    // not just the absence of a TextView. A GONE parent or a stale zero-height row matters.
    private fun geometry(bar: ViewGroup): List<Int> {
        layout(bar)
        main.idleFor(Duration.ofMillis(600))
        layout(bar)
        if (bar.visibility == View.GONE) return listOf(View.GONE, bar.layoutParams.height)
        return listOf(bar.visibility, bar.layoutParams?.height ?: -2, bar.height, bar.clipBounds?.height() ?: -1) +
            (0 until bar.childCount).map { bar.getChildAt(it).let { v -> if (v.visibility == View.VISIBLE) v.height else -1 } }
    }

    private fun notificationAccess(granted: Boolean) {
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(context, OtpNotificationListener::class.java), granted)
    }

    private fun configure() {
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setModifierIndicators(context, emptySet())
        SettingsManager.setSuggestionsEnabled(context, false)
        SettingsManager.setAlwaysShowSuggestionsBar(context, true)
        SettingsManager.setAlwaysShowSuggestionsBarHideSensitive(context, true)
        SettingsManager.setAlwaysShowSuggestionsBarHideVariations(context, true)
        SettingsManager.setOtpEnabled(context, true)
        SettingsManager.setOtpSourceEnabled(context, OtpSource.HELPER, true)
    }

    private fun focus(ime: PhysicalKeyboardInputMethodService, type: Int, action: Int, id: Int): EditText {
        val info = EditorInfo().apply {
            inputType = type
            imeOptions = action
            fieldId = id
            packageName = if (action == EditorInfo.IME_ACTION_SEND) "com.whatsapp" else "test.editor"
        }
        val editor = EditText(context).apply { inputType = type }
        val connection = object : InputConnectionWrapper(editor.onCreateInputConnection(EditorInfo()), false) {
            override fun performEditorAction(editorAction: Int): Boolean = error("OTP must not submit")
            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean = error("OTP must not send key events")
        }
        ReflectionHelpers.setField(ime, "mInputEditorInfo", info)
        ReflectionHelpers.setField(ime, "mStartedInputConnection", connection)
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        main.idle()
        return editor
    }

    private fun deliver(code: String) {
        OtpHelperReceiver().onReceive(context, Intent(OtpHelperReceiver.ACTION).setPackage(context.packageName).putExtra("code", code))
        main.idle()
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private val View.title: TextView get() = findViewById(androidx.autofill.R.id.autofill_inline_suggestion_title)

    private fun View.chip(): View? {
        if (visibility != View.VISIBLE) return null
        if (id == R.id.otp_chip) return this
        return (this as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { group.getChildAt(it).chip() } }
    }
}
