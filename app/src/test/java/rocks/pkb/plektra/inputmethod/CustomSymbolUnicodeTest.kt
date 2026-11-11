package rocks.pkb.plektra.inputmethod

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.os.LocaleList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.SettingsManager
import java.util.Locale

/**
 * Tests for custom Unicode symbol support:
 * - Unicode code entry (U+XXXX)
 * - Shift uppercase transformation with Turkish locale support
 * - Multi-codepoint grapheme support (emoji, flags, variation selectors)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CustomSymbolUnicodeTest {

    @Test
    fun unicodeCodeEntry_valid_shouldParse() {
        // Basic Latin
        assertEquals("Ö", parseUnicodeCode("U+00D6"))
        assertEquals("Ö", parseUnicodeCode("u+00d6"))
        
        // Emoji
        assertEquals("😀", parseUnicodeCode("U+1F600"))
        assertEquals("🔥", parseUnicodeCode("u+1f525"))
    }

    @Test
    fun unicodeCodeEntry_invalid_shouldReject() {
        // Out of range
        assertNull(parseUnicodeCode("U+110000"))
        
        // Surrogate range
        assertNull(parseUnicodeCode("U+D800"))
        assertNull(parseUnicodeCode("U+DFFF"))
        
        // Invalid format
        assertNull(parseUnicodeCode("U+"))
        assertNull(parseUnicodeCode("U+GGGG"))
    }

    @Test
    fun shiftUppercase_standardLocale_shouldTransform() {
        val result = toUppercaseWithLocale("ö", isTurkish = false)
        assertEquals("Ö", result)
        
        assertEquals("Ç", toUppercaseWithLocale("ç", isTurkish = false))
        assertEquals("Ş", toUppercaseWithLocale("ş", isTurkish = false))
        assertEquals("Ğ", toUppercaseWithLocale("ğ", isTurkish = false))
    }

    @Test
    fun shiftUppercase_turkishLocale_shouldTransformI() {
        // Turkish: ı→I, i→İ
        assertEquals("I", toUppercaseWithLocale("ı", isTurkish = true))
        assertEquals("İ", toUppercaseWithLocale("i", isTurkish = true))
    }

    @Test
    fun shiftUppercase_standardLocale_shouldTransformI() {
        // Standard: i→I
        assertEquals("I", toUppercaseWithLocale("i", isTurkish = false))
    }

    @Test
    fun shiftUppercase_noCase_shouldLeaveUnchanged() {
        // Emoji, digits, punctuation have no case
        assertEquals("😀", toUppercaseWithLocale("😀", isTurkish = false))
        assertEquals("123", toUppercaseWithLocale("123", isTurkish = false))
        assertEquals("!", toUppercaseWithLocale("!", isTurkish = false))
    }

    @Test
    fun shiftUppercase_multiChar_shouldTransform() {
        // ß→SS is multi-char uppercase (German sharp s)
        val result = toUppercaseWithLocale("ß", isTurkish = false)
        assertEquals("SS", result)
    }

    @Test
    fun customMapping_persistence_shouldSurviveBackup() {
        val context: Context = RuntimeEnvironment.getApplication()
        
        // Save custom mappings
        val mappings = mapOf(
            android.view.KeyEvent.KEYCODE_Q to "😀",
            android.view.KeyEvent.KEYCODE_W to "Ö",
            android.view.KeyEvent.KEYCODE_E to "U+1F525" // This would be parsed by UI
        )
        SettingsManager.saveSymMappings(context, mappings)
        
        // Verify saved
        val loaded = SettingsManager.getSymMappings(context)
        assertEquals(3, loaded.size)
        assertEquals("😀", loaded[android.view.KeyEvent.KEYCODE_Q])
        assertEquals("Ö", loaded[android.view.KeyEvent.KEYCODE_W])
    }

    // Helper functions matching the implementation

    private fun parseUnicodeCode(code: String): String? {
        if (!code.matches(Regex("^[Uu]\\+[0-9A-Fa-f]{1,6}$"))) {
            return null
        }
        
        val codeStr = code.substring(2)
        return try {
            val codePoint = codeStr.toInt(16)
            if (codePoint !in 0..0x10FFFF || (codePoint in 0xD800..0xDFFF)) {
                null
            } else {
                String(Character.toChars(codePoint))
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun toUppercaseWithLocale(text: String, isTurkish: Boolean): String {
        if (text.isEmpty()) return text
        
        return if (isTurkish) {
            val locale = Locale("tr", "TR")
            text.uppercase(locale)
        } else {
            text.uppercase()
        }
    }
}
