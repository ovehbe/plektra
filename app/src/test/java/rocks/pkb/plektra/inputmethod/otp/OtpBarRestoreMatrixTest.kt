package rocks.pkb.plektra.inputmethod.otp

import android.content.Intent
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import java.time.Duration
import org.junit.After
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
import rocks.pkb.plektra.KeyboardBarArrangement
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.CandidatesBarController
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import rocks.pkb.plektra.inputmethod.StatusBarController
import rocks.pkb.plektra.inputmethod.suggestions.ui.FullSuggestionsBar

/**
 * After a one-time code chip goes away (inserted, expired, replaced, or its field changes or
 * finishes), the bar must be exactly what it would have been without the chip: the same rows,
 * the same height, never stuck collapsed. Each row runs the same steps twice on one service: once
 * with OTP off and no chip (the reference), once with the chip, on different fields of the same type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtpBarRestoreMatrixTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())
    private var service: ServiceController<PhysicalKeyboardInputMethodService>? = null

    @After
    fun tearDown() {
        service?.destroy()
        SettingsManager.setOtpEnabled(context, false)
        main.idle()
    }

    private data class Field(val name: String, val inputType: Int, val packageName: String = APP)

    private val fields = listOf(
        Field("number", InputType.TYPE_CLASS_NUMBER),
        Field("text", InputType.TYPE_CLASS_TEXT),
        Field("password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        Field("WhatsApp message", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, packageName = "com.whatsapp")
    )

    /**
     * INSERTED: tap. INSERTED_REFOCUS: tap, leave the field and come back. EXPIRED: the window
     * runs out. REPLACED: a newer code replaces the chip, which is then tapped. FIELD_CHANGE: focus
     * moves on with the chip, which then expires. FINISH_INPUT: the field finishes under the chip.
     */
    private enum class Ending { INSERTED, INSERTED_REFOCUS, EXPIRED, REPLACED, FIELD_CHANGE, FINISH_INPUT }

    private data class Bar(
        val populated: Boolean,
        val hideEmpty: Boolean,
        val hideVariations: Boolean,
        val indicatorStrip: Boolean
    ) {
        override fun toString() = (if (populated) "populated" else "empty") +
            " hideEmpty=$hideEmpty hideVariations=$hideVariations strip=$indicatorStrip"
    }

    private data class BarState(
        val chromeShown: Boolean,
        val collapsed: Boolean,
        val suggestionsRow: Boolean,
        val actionsRowPx: Int,
        val heightPx: Int,
        val chip: Boolean
    )

    private val bars = listOf(false, true).flatMap { populated ->
        listOf(false, true).flatMap { hideEmpty ->
            listOf(false, true).flatMap { hideVariations ->
                listOf(false, true).map { strip -> Bar(populated, hideEmpty, hideVariations, strip) }
            }
        }
    }

    @Test
    fun barReturnsToItsChiplessStateAfterEveryWayTheChipGoes() {
        val failures = mutableListOf<String>()
        var rows = 0
        var code = 314000
        for (bar in bars) {
            configure(bar)
            val (ime, root) = startService()
            var nextField = 1
            for (field in fields) for (ending in Ending.values()) {
                rows++
                val label = "$bar ${field.name} $ending"
                code += 2
                SettingsManager.setOtpEnabled(context, false)
                main.idle()
                val reference = runCatching { play(ime, root, field, ending, code, nextField, chip = false) }
                nextField += 2
                SettingsManager.setOtpEnabled(context, true)
                SettingsManager.setOtpSourceEnabled(context, OtpSource.HELPER, true)
                main.idle()
                val withChip = runCatching { play(ime, root, field, ending, code, nextField, chip = true) }
                nextField += 2
                val expected = reference.getOrElse { failures += "$label: reference threw $it"; null }
                val actual = withChip.getOrElse { failures += "$label: threw $it"; null }
                if (expected != null && actual != null && expected != actual) {
                    failures += "$label: expected $expected but was $actual"
                }
            }
            service?.destroy()
            service = null
        }
        println("OTP bar restore matrix: $rows rows, ${failures.size} failing")
        failures.groupBy { it.substringAfter("strip=").substringAfter(' ').substringBefore(':') }
            .forEach { (group, list) -> println("  FAIL $group: ${list.size}, e.g. ${list.first()}") }
        assertTrue(failures.take(200).joinToString("\n", prefix = "${failures.size} of $rows rows failed:\n"),
            failures.isEmpty())
    }

    /** Same steps with or without a chip; taps only happen when there is one. */
    private fun play(
        ime: PhysicalKeyboardInputMethodService, root: ViewGroup, field: Field, ending: Ending,
        code: Int, id: Int, chip: Boolean
    ): BarState {
        val editor = focus(ime, field, id)
        if (chip) {
            deliver(code.toString())
            observe(ime, root).also { check(it.chip) { "chip not shown: $it" } }
        }
        fun tap(expected: String, target: EditText) {
            if (!chip) return
            val view = requireNotNull(root.otpChip()) { "no chip to tap" }
            view.performClick()
            main.idle()
            check(target.text.toString().endsWith(expected)) { "tap inserted '${target.text}'" }
        }
        when (ending) {
            Ending.INSERTED -> tap(code.toString(), editor)
            Ending.INSERTED_REFOCUS -> {
                tap(code.toString(), editor)
                focus(ime, field, id + 1)
                focus(ime, field, id)
            }
            Ending.EXPIRED -> expire()
            Ending.REPLACED -> {
                if (chip) deliver((code + 1).toString())
                tap((code + 1).toString(), editor)
            }
            Ending.FIELD_CHANGE -> {
                focus(ime, field, id + 1)
                expire()
            }
            Ending.FINISH_INPUT -> {
                ime.onFinishInput()
                main.idle()
                tap(code.toString(), focus(ime, field, id + 1))
            }
        }
        return observe(ime, root)
    }

    private fun expire() = main.idleFor(Duration.ofSeconds(125))

    private fun configure(bar: Bar) {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setAutoShowKeyboard(context, true)
        SettingsManager.setAutoShowRespectAppVisibility(context, false)
        KeyboardBarConfiguration.setArrangement(context, KeyboardBarArrangement.SUGGESTIONS_FIRST)
        SettingsManager.setStatusBarVariationsVisible(context, bar.populated)
        SettingsManager.setSuggestionsEnabled(context, bar.populated)
        SettingsManager.setAlwaysShowSuggestionsBar(context, true)
        SettingsManager.setAlwaysShowSuggestionsBarHideSensitive(context, bar.hideEmpty)
        SettingsManager.setAlwaysShowSuggestionsBarHideVariations(context, bar.hideVariations)
        SettingsManager.setModifierIndicators(context,
            if (bar.indicatorStrip) setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP) else emptySet())
        SettingsManager.setKeyboardBarHeightAnimation(context, true)
        SettingsManager.setOtpEnabled(context, false)
        SettingsManager.setOtpSourceEnabled(context, OtpSource.HELPER, true)
    }

    private fun startService(): Pair<PhysicalKeyboardInputMethodService, ViewGroup> {
        val controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = controller
        val ime = controller.get()
        ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController").setCandidatesSurfaceActive(false)
        return ime to ime.onCreateInputView() as ViewGroup
    }

    private fun focus(ime: PhysicalKeyboardInputMethodService, field: Field, id: Int): EditText {
        val info = EditorInfo().apply {
            inputType = field.inputType
            packageName = field.packageName
            fieldId = id
        }
        val editor = EditText(context).apply { inputType = field.inputType }
        val connection = object : InputConnectionWrapper(editor.onCreateInputConnection(EditorInfo()), false) {
            override fun performEditorAction(editorAction: Int): Boolean = error("OTP must not submit")
        }
        if (ReflectionHelpers.getField<Boolean>(ime, "mInputStarted")) ime.onFinishInput()
        ReflectionHelpers.setField(ime, "mInputEditorInfo", info)
        ReflectionHelpers.setField(ime, "mStartedInputConnection", connection as InputConnection)
        ReflectionHelpers.setField(ime, "mInputStarted", true)
        ime.onStartInput(info, false)
        ime.onStartInputView(info, false)
        main.idle()
        return editor
    }

    private fun deliver(code: String) {
        OtpHelperReceiver().onReceive(context,
            Intent(OtpHelperReceiver.ACTION).setPackage(context.packageName).putExtra("code", code))
        main.idle()
    }

    private fun inputBar(ime: PhysicalKeyboardInputMethodService): StatusBarController = ReflectionHelpers.getField(
        ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController"), "inputStatusBar")

    private fun observe(ime: PhysicalKeyboardInputMethodService, root: ViewGroup): BarState {
        // Lay out once so a row change starts its animation, let it finish, then settle.
        repeat(3) { pass ->
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            if (pass == 0) main.idleFor(Duration.ofMillis(600))
        }
        val bar = inputBar(ime)
        val chrome = ReflectionHelpers.getField<ViewGroup>(bar, "statusBarLayout")
        val row = ReflectionHelpers.getField<FullSuggestionsBar?>(bar, "fullSuggestionsBar")?.ensureView()
        val actions = ReflectionHelpers.getField<View?>(bar, "variationsWrapper")
        return BarState(
            chromeShown = chrome.isShownWithin(root),
            collapsed = chrome.layoutParams?.height == 0,
            suggestionsRow = row?.let { it.isShownWithin(root) && it.height > 0 } == true,
            actionsRowPx = actions?.takeIf { it.isShownWithin(root) }?.height ?: 0,
            heightPx = if (chrome.isShownWithin(root)) chrome.height - (chrome.clipBounds?.top ?: 0) else 0,
            chip = root.otpChip() != null
        )
    }

    private fun View.isShownWithin(root: View): Boolean {
        var view: View = this
        while (view !== root) {
            if (view.visibility != View.VISIBLE) return false
            view = view.parent as? View ?: return false
        }
        return root.visibility == View.VISIBLE
    }

    private fun View.otpChip(): View? {
        if (visibility != View.VISIBLE) return null
        if (id == R.id.otp_chip) return this
        val group = this as? ViewGroup ?: return null
        return (0 until group.childCount).firstNotNullOfOrNull { group.getChildAt(it).otpChip() }
    }

    private companion object {
        const val APP = "com.example.bank"
    }
}
