package rocks.pkb.plektra.inputmethod.autofill

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.util.Size
import android.view.View
import android.view.autofill.AutofillId
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.Executor
import java.util.function.Consumer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.core.NavModeController
import rocks.pkb.plektra.core.SymLayoutController
import rocks.pkb.plektra.inputmethod.CandidatesBarController
import rocks.pkb.plektra.inputmethod.KeyboardVisibilityController
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService

/**
 * Drives the real service through Android's own inline-suggestion plumbing
 * (InputMethodService's session controller) against a fake system autofill session that follows
 * AOSP's AutofillInlineSuggestionsRequestSession: it asks once per request, refuses chips for the
 * whole session when the IME returns no request, and re-sends the latest response only when the
 * IME's started / input-view-started state changes on the matching field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [
        InlineAutofillLifecycleMatrixTest.ShadowInlineSuggestion::class,
        InlineAutofillLifecycleMatrixTest.ShadowSoftInputWindow::class
    ]
)
class InlineAutofillLifecycleMatrixTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var service: ServiceController<PhysicalKeyboardInputMethodService>? = null

    @After
    fun tearDown() {
        service?.destroy()
    }

    /** Opaque chips render into an InlineContentView; inflation completes on the next main-loop turn. */
    @Implements(InlineSuggestion::class)
    class ShadowInlineSuggestion {
        @Implementation
        fun inflate(context: Context, size: Size, callbackExecutor: Executor, callback: Consumer<InlineContentView>) {
            val handler = android.os.Handler(Looper.getMainLooper())
            handler.post {
                val view = ReflectionHelpers.callConstructor(
                    InlineContentView::class.java, ClassParameter.from(Context::class.java, context)
                )
                callbackExecutor.execute { callback.accept(view.also { inflated += it }) }
            }
        }

        companion object {
            val inflated = mutableListOf<InlineContentView>()
        }
    }

    /** Robolectric has no display area for the IME window; the view tree is what the matrix checks. */
    @Implements(className = "android.inputmethodservice.SoftInputWindow", isInAndroidSdk = false)
    class ShadowSoftInputWindow {
        @Implementation
        fun show() = Unit
    }

    private enum class Keyboard { HARDWARE, SOFTWARE }

    private data class Config(
        val keyboard: Keyboard,
        val actionsRow: Boolean,
        val alwaysShow: Boolean,
        val hideEmptyRow: Boolean,
        val animation: Boolean,
        val sensitive: Boolean
    ) {
        override fun toString() =
            "$keyboard actions=${actionsRow.on} always=${alwaysShow.on} hideEmpty=${hideEmptyRow.on} " +
                "anim=${animation.on} sensitive=${sensitive.on}"

        private val Boolean.on get() = if (this) "on" else "off"
    }

    private val configs = Keyboard.values().flatMap { keyboard ->
        listOf(true, false).flatMap { actions ->
            listOf(true, false).flatMap { always ->
                listOf(true, false).flatMap { hideEmpty ->
                    listOf(true, false).flatMap { animation ->
                        listOf(true, false).map { sensitive ->
                            Config(keyboard, actions, always, hideEmpty, animation, sensitive)
                        }
                    }
                }
            }
        }
    }

    /** A focusable field. Web fields are virtual children of one host view, as in Chrome and WebView. */
    private enum class Field { A, B, PAGE, WEB1, WEB2 }

    private sealed interface Step
    private data class Request(val field: Field) : Step
    private data class Respond(val chips: Int) : Step
    private data class Start(val field: Field, val restarting: Boolean = false) : Step
    private object Finish : Step { override fun toString() = "Finish" }
    private object ShowWindow : Step { override fun toString() = "ShowWindow" }
    private object HideWindow : Step { override fun toString() = "HideWindow" }
    private object NavMode : Step { override fun toString() = "NavMode" }
    private object SymOpen : Step { override fun toString() = "SymOpen" }
    private object SymClose : Step { override fun toString() = "SymClose" }
    private object Selection : Step { override fun toString() = "Selection" }
    private object Relayout : Step { override fun toString() = "Relayout" }

    private data class Scenario(
        val name: String,
        val steps: List<Step>,
        val expectedChips: Int,
        val setup: (Config) -> Unit = {},
        val applies: (Config) -> Boolean = { true }
    )

    private val scenarios = listOf(
        Scenario("request at input start", listOf(Request(Field.A), Start(Field.A), Respond(2)), 2),
        Scenario("field becomes autofillable after it started", listOf(Start(Field.A), Request(Field.A), Respond(2)), 2),
        Scenario("response waits for the input view", listOf(Start(Field.A), HideWindow, Request(Field.A), Respond(2), ShowWindow), 2),
        Scenario("late response after a selection update", listOf(Start(Field.A), Request(Field.A), Selection, Respond(2)), 2),
        Scenario("empty response", listOf(Start(Field.A), Request(Field.A), Respond(0)), 0),
        Scenario("duplicate response", listOf(Request(Field.A), Start(Field.A), Respond(2), Respond(2)), 2),
        Scenario("updated response replaces chips", listOf(Request(Field.A), Start(Field.A), Respond(3), Respond(1)), 1),
        Scenario("response then empty", listOf(Request(Field.A), Start(Field.A), Respond(2), Respond(0)), 0),
        Scenario("app re-attaches the same field", listOf(Request(Field.A), Start(Field.A), Respond(2), Start(Field.A, restarting = true)), 2),
        Scenario("re-attach before the response", listOf(Request(Field.A), Start(Field.A), Start(Field.A, restarting = true), Respond(2)), 2),
        Scenario("focus moves to a field without a request", listOf(Request(Field.A), Start(Field.A), Respond(2), Start(Field.B)), 0),
        Scenario("focus moves to a field with its own request", listOf(Request(Field.A), Start(Field.A), Respond(2), Start(Field.B), Request(Field.B), Respond(1)), 1),
        Scenario("web field to web field", listOf(Start(Field.PAGE), Request(Field.WEB1), Start(Field.WEB1, restarting = true), ShowWindow, Respond(2), Request(Field.WEB2), Start(Field.WEB2, restarting = true), Respond(1)), 1),
        Scenario("finish input", listOf(Request(Field.A), Start(Field.A), Respond(2), Finish), 0),
        Scenario("hide and show the window", listOf(Request(Field.A), Start(Field.A), Respond(2), HideWindow, ShowWindow), 2),
        Scenario("re-attach while hidden", listOf(Request(Field.A), Start(Field.A), Respond(2), HideWindow, Start(Field.A, restarting = true), ShowWindow), 2),
        Scenario("chips survive a SYM page", listOf(Request(Field.A), Start(Field.A), Respond(2), SymOpen, SymClose), 2),
        Scenario("relayout", listOf(Request(Field.A), Start(Field.A), Respond(2), Relayout), 2),
        Scenario("selection update", listOf(Request(Field.A), Start(Field.A), Respond(2), Selection), 2),
        Scenario("nav mode latched before a new field", listOf(Finish, NavMode, Request(Field.A), Start(Field.A), Respond(2)), 2),
        Scenario("nav mode on a web page, tap restarts input", listOf(Start(Field.PAGE), NavMode, Request(Field.WEB1), Start(Field.WEB1, restarting = true), ShowWindow, Respond(2)), 2),
        Scenario("nav mode on a web page, request after the restart", listOf(Start(Field.PAGE), NavMode, Start(Field.WEB1, restarting = true), Request(Field.WEB1), ShowWindow, Respond(2)), 2),
        Scenario("SYM page open when Android asks", listOf(Start(Field.A), SymOpen, Request(Field.A), Respond(2), SymClose), 2),
        Scenario("SYM page open when the response arrives", listOf(Start(Field.A), Request(Field.A), SymOpen, Respond(2), SymClose), 2),
        Scenario("inline autofill off", listOf(Request(Field.A), Start(Field.A), Respond(2)), 0,
            setup = { SettingsManager.setInlineAutofillEnabled(context, false) }),
        Scenario("bar never shown in this app", listOf(Request(Field.A), Start(Field.A), Respond(2)), 0,
            setup = { SettingsManager.setKeyboardBarHiddenInApp(context, APP, true) }),
        Scenario("virtual keyboard waits for the app", listOf(Request(Field.A), Start(Field.A), Respond(2)), 0,
            setup = { SettingsManager.setAutoShowRespectAppVisibility(context, true) },
            applies = { it.keyboard == Keyboard.SOFTWARE })
    )

    @Test
    fun chipsReachTheBarWheneverAndroidHandsThemOver() {
        val failures = mutableListOf<String>()
        var rows = 0
        scenarios.forEach { scenario ->
            configs.filter(scenario.applies).forEach { config ->
                rows++
                val outcome = runCatching { run(scenario, config) }
                val shown = outcome.getOrNull()
                if (shown != scenario.expectedChips) {
                    failures += "${scenario.name} [$config]: expected ${scenario.expectedChips} chips on screen, " +
                        (outcome.exceptionOrNull()?.let { "threw $it" } ?: "saw $shown")
                }
            }
        }
        println("Inline autofill lifecycle matrix: $rows rows, ${failures.size} failing")
        failures.groupBy { it.substringBefore(" [") }.forEach { (name, rowsFailing) ->
            println("  FAIL $name: ${rowsFailing.size} rows, e.g. ${rowsFailing.first()}")
        }
        if (failures.isNotEmpty()) fail("${failures.size} of $rows rows failed:\n" + failures.joinToString("\n"))
    }

    private fun run(scenario: Scenario, config: Config): Int {
        SettingsManager.getPreferences(context).edit().clear().commit()
        ShadowInlineSuggestion.inflated.clear()
        configure(config)
        scenario.setup(config)
        val controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        service = controller
        val ime = controller.get()
        rendersWithoutADisplay(ime)
        val autofill = FakeAutofill(ime)
        val host = EditText(context)
        val page = viewId(103)
        val ids = mapOf(
            Field.A to viewId(101),
            Field.B to viewId(102),
            Field.PAGE to page,
            Field.WEB1 to virtualId(page, 1),
            Field.WEB2 to virtualId(page, 2)
        )
        try {
            scenario.steps.forEach { step ->
                when (step) {
                    is Request -> autofill.request(ids.getValue(step.field))
                    is Respond -> autofill.respond(step.chips)
                    is Start -> {
                        if (!step.restarting && ReflectionHelpers.getField<Boolean>(ime, "mInputStarted")) ime.finishInputForTest()
                        ime.startInputForTest(editorInfo(step.field, ids.getValue(step.field), config.sensitive), host, step.restarting)
                        // The keyboard shows itself (hardware, auto-show) or the app asks once the field takes focus.
                        if (!step.restarting && editable(step.field)) ime.showWindow(true)
                    }
                    Finish -> ime.finishInputForTest()
                    ShowWindow -> ime.showWindow(true)
                    HideWindow -> ime.hideWindow()
                    NavMode -> {
                        ReflectionHelpers.getField<NavModeController>(ime, "navModeController").enterNavMode()
                        ime.refreshBar()
                    }
                    SymOpen -> {
                        ReflectionHelpers.getField<SymLayoutController>(ime, "symLayoutController").toggleSymPage()
                        ime.refreshBar()
                    }
                    SymClose -> {
                        ReflectionHelpers.getField<SymLayoutController>(ime, "symLayoutController").closeSymPage()
                        ime.refreshBar()
                    }
                    Selection -> ime.onUpdateSelection(0, 0, 1, 1, -1, -1)
                    Relayout -> ime.refreshBar()
                }
                settle(ime)
            }
            return ShadowInlineSuggestion.inflated.count { it.isOnScreen(ime) }
        } finally {
            controller.destroy()
            service = null
        }
    }

    /**
     * Without a display the IME window never lays out, so the service's "actually rendered" probe
     * would time out and close the window; rendered here means the input view is shown and visible.
     */
    private fun rendersWithoutADisplay(ime: PhysicalKeyboardInputMethodService) {
        val visibility = ReflectionHelpers.getField<Any>(ime, "keyboardVisibilityController")
        val bars = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController")
        val rendered: () -> KeyboardVisibilityController.RenderedSurface = {
            if (ime.isInputViewShown && bars.getInputView().visibleUpTree()) KeyboardVisibilityController.RenderedSurface.FULL_INPUT_VIEW
            else KeyboardVisibilityController.RenderedSurface.HIDDEN
        }
        ReflectionHelpers.setField(visibility, "renderedSurface", rendered)
    }

    private fun View.visibleUpTree(): Boolean {
        var view: View? = this
        while (view != null) {
            if (view.visibility != View.VISIBLE) return false
            view = view.parent as? View
        }
        return true
    }

    private fun configure(config: Config) {
        SettingsManager.setSoftwareKeyboardMode(
            context,
            if (config.keyboard == Keyboard.HARDWARE) SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE
            else SettingsManager.SoftwareKeyboardMode.FORCE_VIRTUAL
        )
        SettingsManager.setAutoShowKeyboard(context, true)
        SettingsManager.setAutoShowRespectAppVisibility(context, false)
        SettingsManager.setStatusBarVariationsVisible(context, config.actionsRow)
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setAlwaysShowSuggestionsBar(context, config.alwaysShow)
        SettingsManager.setAlwaysShowSuggestionsBarHideSensitive(context, config.hideEmptyRow)
        SettingsManager.setKeyboardBarHeightAnimation(context, config.animation)
        SettingsManager.setInlineAutofillEnabled(context, true)
        SettingsManager.setNavModeEnabled(context, true)
        KeyboardBarConfiguration.setArrangement(context, rocks.pkb.plektra.KeyboardBarArrangement.SUGGESTIONS_FIRST)
    }

    /** The system's per-request session, after AOSP's AutofillInlineSuggestionsRequestSession. */
    private inner class FakeAutofill(private val ime: PhysicalKeyboardInputMethodService) {
        private val requestCallbackClass = Class.forName("com.android.internal.inputmethod.IInlineSuggestionsRequestCallback")
        private var session: Session? = null

        fun request(field: AutofillId) {
            val next = Session(field)
            session = next
            val info = ReflectionHelpers.callConstructor(
                Class.forName("com.android.internal.inputmethod.InlineSuggestionsRequestInfo"),
                ClassParameter.from(ComponentName::class.java, ComponentName(APP, "$APP.Login")),
                ClassParameter.from(AutofillId::class.java, field),
                ClassParameter.from(Bundle::class.java, uiExtras())
            )
            val controller = ReflectionHelpers.getField<Any>(ime, "mInlineSuggestionSessionController")
            ReflectionHelpers.callInstanceMethod<Any>(
                controller, "onMakeInlineSuggestionsRequest",
                ClassParameter.from(info.javaClass, info),
                ClassParameter.from(requestCallbackClass, next.proxy(requestCallbackClass))
            )
        }

        fun respond(chips: Int) {
            val current = session ?: return
            current.fillUi = List(chips) { suggestion() }
            current.maybeSend()
        }
    }

    private class Session(private val autofillId: AutofillId) {
        private var imeRequest: InlineSuggestionsRequest? = null
        private var responseCallback: Any? = null
        private var invalidated = false
        private var imeFieldId: AutofillId? = null
        private var imeInputStarted = false
        private var imeInputViewStarted = false
        private var previousResponseIsNotEmpty: Boolean? = null
        var fillUi: List<InlineSuggestion>? = null

        fun proxy(type: Class<*>): Any = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "onInlineSuggestionsUnsupported" -> Unit
                "onInlineSuggestionsRequest" -> {
                    imeRequest = args[0] as InlineSuggestionsRequest
                    responseCallback = args[1]
                    status(autofillId, started = true, viewStarted = false)
                }
                "onInputMethodStartInput" -> status(args[0] as AutofillId, started = true, viewStarted = false)
                "onInputMethodStartInputView" -> status(null, started = true, viewStarted = true)
                "onInputMethodFinishInputView" -> status(null, started = true, viewStarted = false)
                "onInputMethodFinishInput" -> status(null, started = false, viewStarted = false)
                "onInlineSuggestionsSessionInvalidated" -> invalidated = true
                "onInputMethodShowInputRequested" -> Unit
                "asBinder" -> return@newProxyInstance Binder()
                "hashCode" -> return@newProxyInstance System.identityHashCode(self)
                "equals" -> return@newProxyInstance self === args[0]
                "toString" -> return@newProxyInstance "FakeAutofillSession"
            }
            null
        }

        private fun status(fieldId: AutofillId?, started: Boolean, viewStarted: Boolean) {
            if (fieldId != null) imeFieldId = fieldId
            if (imeFieldId == null) return
            val changed = imeInputStarted != started || imeInputViewStarted != viewStarted
            imeInputStarted = started
            imeInputViewStarted = viewStarted
            if (changed) maybeSend()
        }

        fun maybeSend() {
            val callback = responseCallback ?: return
            if (imeRequest == null || invalidated) return
            val suggestions = fillUi ?: return
            if (!imeInputViewStarted || imeFieldId?.viewId != autofillId.viewId) return
            if (suggestions.isEmpty() && previousResponseIsNotEmpty == false) return
            val response = ReflectionHelpers.callConstructor(
                InlineSuggestionsResponse::class.java, ClassParameter.from(List::class.java, suggestions)
            )
            callback.javaClass.getMethod(
                "onInlineSuggestionsResponse", AutofillId::class.java, InlineSuggestionsResponse::class.java
            ).apply { isAccessible = true }.invoke(callback, autofillId, response)
            previousResponseIsNotEmpty = suggestions.isNotEmpty()
        }
    }

    private fun PhysicalKeyboardInputMethodService.startInputForTest(info: EditorInfo, host: EditText, restarting: Boolean) {
        ReflectionHelpers.callInstanceMethod<Any>(
            this, "doStartInput",
            ClassParameter.from(InputConnection::class.java, BaseInputConnection(host, true)),
            ClassParameter.from(EditorInfo::class.java, info),
            ClassParameter.from(Boolean::class.javaPrimitiveType, restarting)
        )
    }

    private fun PhysicalKeyboardInputMethodService.finishInputForTest() {
        ReflectionHelpers.callInstanceMethod<Any>(this, "doFinishInput")
    }

    private fun PhysicalKeyboardInputMethodService.refreshBar() {
        ReflectionHelpers.callInstanceMethod<Any>(this, "refreshStatusBar")
    }

    /** Lays the window out like a frame would, so rows have real heights and transitions animate. */
    private fun settle(ime: PhysicalKeyboardInputMethodService) {
        val inputView = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController").getInputView()
        repeat(2) {
            var root: View = inputView
            while (root.parent is View) root = root.parent as View
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST)
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        }
    }

    private fun editable(field: Field) = field != Field.PAGE

    private fun editorInfo(field: Field, autofillId: AutofillId, sensitive: Boolean) = EditorInfo().apply {
        packageName = APP
        fieldId = field.ordinal + 1
        inputType = when {
            field == Field.PAGE -> InputType.TYPE_NULL
            sensitive -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT
        }
        ReflectionHelpers.setField(this, "autofillId", autofillId)
    }

    private fun viewId(id: Int): AutofillId =
        ReflectionHelpers.callConstructor(AutofillId::class.java, ClassParameter.from(Int::class.javaPrimitiveType, id))

    private fun virtualId(host: AutofillId, virtualId: Int): AutofillId =
        ReflectionHelpers.callConstructor(
            AutofillId::class.java,
            ClassParameter.from(AutofillId::class.java, host),
            ClassParameter.from(Int::class.javaPrimitiveType, virtualId)
        )

    private fun uiExtras(): Bundle =
        UiVersions.newStylesBuilder().addStyle(InlineSuggestionUi.newStyleBuilder().build()).build()

    private fun suggestion(): InlineSuggestion {
        val spec = InlinePresentationSpec.Builder(Size(10, 10), Size(500, 100)).build()
        val info = ReflectionHelpers.callStaticMethod<InlineSuggestionInfo>(
            InlineSuggestionInfo::class.java, "newInlineSuggestionInfo",
            ClassParameter.from(InlinePresentationSpec::class.java, spec),
            ClassParameter.from(String::class.java, InlineSuggestionInfo.SOURCE_AUTOFILL),
            ClassParameter.from(Array<String>::class.java, arrayOf("password")),
            ClassParameter.from(String::class.java, InlineSuggestionInfo.TYPE_SUGGESTION),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false)
        )
        return ReflectionHelpers.callStaticMethod(
            InlineSuggestion::class.java, "newInlineSuggestion", ClassParameter.from(InlineSuggestionInfo::class.java, info)
        )
    }

    /** Attached under the shown input view with every ancestor visible and the bar row not collapsed. */
    private fun View.isOnScreen(ime: PhysicalKeyboardInputMethodService): Boolean {
        if (!ime.isInputViewShown) return false
        val inputView = ReflectionHelpers.getField<CandidatesBarController>(ime, "candidatesBarController").getInputView()
        var view: View? = this
        var underInputView = false
        while (view != null) {
            if (view.visibility != View.VISIBLE) return false
            if (view.layoutParams?.height == 0) return false
            if (view === inputView) underInputView = true
            view = view.parent as? View
        }
        return underInputView
    }

    private companion object {
        const val APP = "com.example.login"
    }
}
