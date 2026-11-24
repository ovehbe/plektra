package rocks.pkb.plektra.inputmethod.autofill

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.TextView
import androidx.autofill.inline.Renderer
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.xmlpull.v1.XmlPullParser
import rocks.pkb.plektra.R
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import rocks.pkb.plektra.inputmethod.ui.KeyboardThemeColors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class InlineAutofillTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** Translucent tokens, as textured and custom themes produce them. */
    private val theme = KeyboardThemeColors(
        background = 0x00000000,
        divider = 0xFF2C3136.toInt(),
        normalKey = 0xFF15191D.toInt(),
        specialKey = 0xFF2B3138.toInt(),
        textAndIcons = 0xCCE8EAED.toInt(),
        ledInactive = 0xFF444444.toInt(),
        ledActive = 0xFF4C8DFF.toInt(),
        ledLocked = 0xFFFF8A00.toInt(),
        accent = 0xFF4C8DFF.toInt(),
        suggestion = 0x8033AAFF.toInt()
    )

    private data class Row(
        val sdk: Int,
        val enabled: Boolean,
        val hostAvailable: Boolean,
        val uiVersion1: Boolean,
        val offered: Boolean
    )

    private val rows = listOf(
        Row(sdk = 29, enabled = true, hostAvailable = true, uiVersion1 = true, offered = false),
        Row(sdk = 30, enabled = true, hostAvailable = true, uiVersion1 = true, offered = true),
        Row(sdk = 34, enabled = true, hostAvailable = true, uiVersion1 = true, offered = true),
        Row(sdk = 30, enabled = false, hostAvailable = true, uiVersion1 = true, offered = false),
        Row(sdk = 34, enabled = false, hostAvailable = true, uiVersion1 = true, offered = false),
        Row(sdk = 30, enabled = true, hostAvailable = false, uiVersion1 = true, offered = false),
        Row(sdk = 30, enabled = true, hostAvailable = true, uiVersion1 = false, offered = false),
        Row(sdk = 29, enabled = false, hostAvailable = false, uiVersion1 = false, offered = false)
    )

    private fun uiExtras(uiVersion1: Boolean): Bundle =
        if (uiVersion1) {
            UiVersions.newStylesBuilder().addStyle(InlineSuggestionUi.newStyleBuilder().build()).build()
        } else {
            Bundle()
        }

    @Test
    fun inlineAutofill_isOnByDefault() {
        assertTrue(SettingsManager.getInlineAutofillEnabled(context))
    }

    @Test
    fun createRequest_followsApiLevelSettingHostAndUiVersionGates() {
        rows.forEach { row ->
            SettingsManager.setInlineAutofillEnabled(context, row.enabled)
            val request = InlineAutofill.createRequest(
                context, uiExtras(row.uiVersion1), CHIP_HEIGHT_PX.takeIf { row.hostAvailable }, sdkInt = row.sdk
            ) { theme }
            assertEquals("$row", row.offered, request != null)
            assertEquals(
                "$row",
                row.sdk >= 30 && row.enabled && row.hostAvailable,
                InlineAutofill.offersRequest(row.sdk, row.enabled, row.hostAvailable)
            )
        }
    }

    @Test
    fun offeredRequest_carriesStyleBundleAndRowSizedSpecs() {
        val request = InlineAutofill.createRequest(context, uiExtras(true), CHIP_HEIGHT_PX) { theme }
        assertNotNull(request)
        request!!
        val screenWidth = context.resources.displayMetrics.widthPixels
        assertEquals(InlineAutofill.MAX_SUGGESTIONS, request.maxSuggestionCount)
        assertTrue(request.inlinePresentationSpecs.isNotEmpty())
        request.inlinePresentationSpecs.forEach { spec ->
            assertTrue(UiVersions.INLINE_UI_VERSION_1 in UiVersions.getVersions(spec.style))
            assertEquals(CHIP_HEIGHT_PX, spec.minSize.height)
            assertEquals(CHIP_HEIGHT_PX, spec.maxSize.height)
            assertTrue(spec.minSize.width in 1..spec.maxSize.width)
            assertEquals(screenWidth * 2 / 3, spec.maxSize.width)
        }
    }

    @Test
    fun styleColors_areSolidThemeTokens() {
        val host = InlineAutofillStyle.hostColor(theme)
        val chip = InlineAutofillStyle.chipColor(theme)
        val text = InlineAutofillStyle.textColor(theme)
        listOf(host, chip, text).forEach { assertEquals(0xFF, Color.alpha(it)) }
        assertEquals(Color.BLACK, host)
        assertEquals(ColorUtils.compositeColors(theme.suggestion, Color.BLACK), chip)
        assertEquals(0xFFE8EAED.toInt(), text)
    }

    @Test
    fun offeredRequest_rendersChipsWithThemeStyle() {
        val request = InlineAutofill.createRequest(context, uiExtras(true), CHIP_HEIGHT_PX) { theme }!!
        val attribution = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        val content = InlineSuggestionUi.newContentBuilder(attribution).setTitle("title").build()

        val chip = Renderer.render(context, content.slice, request.inlinePresentationSpecs.single().style)

        assertNotNull(chip)
        assertNotNull(chip!!.background)
        assertEquals(InlineAutofillStyle.textColor(theme), findText(chip, "title")?.currentTextColor)
    }

    private fun findText(view: View, text: String): TextView? = when {
        view is TextView && view.text.toString() == text -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findText(view.getChildAt(it), text) }
        else -> null
    }

    /** The full service cannot be created on Robolectric's API 30 (dynamic-receiver permission shim). */
    @Test
    @Config(sdk = [34])
    fun service_offersNoRequestAndRejectsResponses_whileNoBarHostExists() {
        SettingsManager.setInlineAutofillEnabled(context, true)
        val controller = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        try {
            val service = controller.get()
            assertNull(service.onCreateInlineSuggestionsRequest(uiExtras(true)))
            val emptyResponse = ReflectionHelpers.callConstructor(
                InlineSuggestionsResponse::class.java,
                ClassParameter.from(List::class.java, emptyList<Any>())
            )
            assertFalse(service.onInlineSuggestionsResponse(emptyResponse))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun inputMethodXml_declaresInlineSuggestionSupport() {
        val parser = context.resources.getXml(R.xml.method)
        var declared: Boolean? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "input-method") {
                declared = parser.getAttributeBooleanValue(ANDROID_NS, "supportsInlineSuggestions", false)
            }
        }
        assertEquals(true, declared)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val CHIP_HEIGHT_PX = 96
    }
}
