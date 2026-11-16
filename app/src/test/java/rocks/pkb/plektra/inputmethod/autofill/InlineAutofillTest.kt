package rocks.pkb.plektra.inputmethod.autofill

import android.os.Bundle
import android.view.inputmethod.InlineSuggestionsResponse
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class InlineAutofillTest {

    private val context get() = RuntimeEnvironment.getApplication()

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
                context, uiExtras(row.uiVersion1), row.hostAvailable, sdkInt = row.sdk
            )
            assertEquals("$row", row.offered, request != null)
            assertEquals(
                "$row",
                row.sdk >= 30 && row.enabled && row.hostAvailable,
                InlineAutofill.offersRequest(row.sdk, row.enabled, row.hostAvailable)
            )
        }
    }

    @Test
    fun offeredRequest_carriesStyleBundleAndBoundedSizes() {
        val request = InlineAutofill.createRequest(context, uiExtras(true), hostAvailable = true)
        assertNotNull(request)
        request!!
        assertTrue(request.maxSuggestionCount in 1..5)
        assertTrue(request.inlinePresentationSpecs.isNotEmpty())
        request.inlinePresentationSpecs.forEach { spec ->
            assertTrue(UiVersions.INLINE_UI_VERSION_1 in UiVersions.getVersions(spec.style))
            assertTrue(spec.minSize.height > 0 && spec.minSize.width > 0)
            assertTrue(spec.minSize.width <= spec.maxSize.width)
            assertTrue(spec.minSize.height <= spec.maxSize.height)
        }
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
    }
}
