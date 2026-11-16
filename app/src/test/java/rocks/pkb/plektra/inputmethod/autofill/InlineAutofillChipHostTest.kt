package rocks.pkb.plektra.inputmethod.autofill

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import rocks.pkb.plektra.KeyboardBarArrangement
import rocks.pkb.plektra.KeyboardBarConfiguration
import rocks.pkb.plektra.KeyboardThemeTexture
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.CandidatesBarController
import rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService
import rocks.pkb.plektra.inputmethod.StatusBarController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InlineAutofillChipHostTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private data class Placement(
        val name: String,
        val actions: Boolean,
        val suggestions: Boolean,
        val arrangement: KeyboardBarArrangement,
        val blocked: Boolean,
        val ledOnly: Boolean,
        val passwordField: Boolean,
        val shown: Boolean,
        val inVariations: Boolean
    )

    private val placements = listOf(
        Placement("actions row", true, true, KeyboardBarArrangement.SUGGESTIONS_FIRST, false, false, false, true, true),
        Placement("actions without word suggestions", true, false, KeyboardBarArrangement.SUGGESTIONS_FIRST, false, false, false, true, true),
        Placement("actions first still uses the variations area", true, true, KeyboardBarArrangement.ACTIONS_FIRST, false, false, false, true, true),
        Placement("shared row has no separate variations area", true, true, KeyboardBarArrangement.SHARED, false, false, false, true, false),
        Placement("suggestions strip when actions are off", false, true, KeyboardBarArrangement.SUGGESTIONS_FIRST, false, false, false, true, false),
        Placement("password field still gets a strip", false, true, KeyboardBarArrangement.SUGGESTIONS_FIRST, false, false, true, true, false),
        Placement("blocked surface", true, true, KeyboardBarArrangement.SUGGESTIONS_FIRST, true, false, false, false, false),
        Placement("led only", false, false, KeyboardBarArrangement.SUGGESTIONS_FIRST, false, true, false, false, false),
        Placement("no chrome", false, false, KeyboardBarArrangement.SUGGESTIONS_FIRST, false, false, false, false, false)
    )

    @Before
    fun reset() {
        SettingsManager.getPreferences(context).edit().clear().commit()
        SettingsManager.setSoftwareKeyboardMode(context, SettingsManager.SoftwareKeyboardMode.FORCE_HARDWARE)
        SettingsManager.setExperimentalSuggestionsEnabled(context, true)
    }

    @Test
    fun placement_followsBarConfiguration() {
        placements.forEach { row ->
            val bar = openBar(row)
            val chips = chipViews("alpha", "beta")
            val shown = bar.showInlineAutofill(chips, listOf(false, false))
            assertEquals(row.name, row.shown, shown)
            val host = findHost(bar.getLayout()!!)
            if (!row.shown) {
                assertNull(row.name, host)
            } else {
                assertNotNull(row.name, host)
                assertEquals(row.name, row.inVariations, hostInVariations(host!!))
                assertTrue(row.name, bar.getLayout()!!.clipChildren)
                assertFalse(row.name, host.clipChildren)
                assertFalse(row.name, hostInSuggestionSlots(host))
                assertTrue(row.name, suggestionTexts(bar.getLayout()!!).none { it == "secret" || it == "other" })
                assertEquals(row.name, View.ACCESSIBILITY_LIVE_REGION_NONE, host.accessibilityLiveRegion)
                bar.update(snapshot(suggestions = listOf("secret", "other"), password = row.passwordField))
                val kept = findHost(bar.getLayout()!!)
                assertNotNull(row.name, kept)
                assertEquals(row.name, chips, kept!!.pinnedChips() + kept.scrollingChips())
                assertTrue(row.name, suggestionTexts(bar.getLayout()!!).none { it == "secret" || it == "other" })
            }
        }
    }

    @Test
    fun pinnedChipsStayAtTheStartInCallOrder() {
        val bar = openBar(placements.first())
        val first = chip("first")
        val middle = chip("middle")
        val last = chip("last")
        assertTrue(bar.showInlineAutofill(listOf(first, middle, last), listOf(true, false, true)))
        val host = findHost(bar.getLayout()!!)!!
        assertEquals(listOf(first, last), host.pinnedChips())
        assertEquals(listOf(middle), host.scrollingChips())
        assertTrue(bar.getLayout()!!.indexOfChild(host) < 0)
        assertTrue(host.indexOfChild(host.pinnedRow) < host.indexOfChild(host.scroller))
    }

    @Test
    fun bothSurfacesMirrorPinnedOrderAndClearTogether() {
        val controller = CandidatesBarController(context)
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setModifierIndicators(context, emptySet())
        controller.getInputView()
        controller.getCandidatesView()
        controller.updateStatusBars(snapshot(listOf("secret")), "", null, null)
        val shown = controller.showInlineAutofill(listOf(true, false)) { mode ->
            listOf(chip("$mode-pin"), chip("$mode-scroll"))
        }
        assertTrue(shown)
        listOf(controller.getInputView(), controller.getCandidatesView()).forEach { root ->
            val host = findHost(root)
            assertNotNull(host)
            assertEquals(1, host!!.pinnedChips().size)
            assertEquals(1, host.scrollingChips().size)
            assertTrue((host.pinnedChips().single().tag as String).endsWith("-pin"))
            assertTrue((host.scrollingChips().single().tag as String).endsWith("-scroll"))
        }
        controller.onFinishInput()
        assertNull(findHost(controller.getInputView()))
        assertNull(findHost(controller.getCandidatesView()))
    }

    @Test
    fun surfaceSwapAndBlockClearChipsAndRefuseTheNextShow() {
        val controller = CandidatesBarController(context)
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setModifierIndicators(context, emptySet())
        controller.getInputView()
        controller.getCandidatesView()
        controller.updateStatusBars(snapshot(listOf("secret")), "", null, null)
        assertTrue(controller.showInlineAutofill(listOf(false)) { listOf(chip("one")) })
        controller.setCandidatesSurfaceActive(false)
        assertNull(findHost(controller.getInputView()))
        assertNull(findHost(controller.getCandidatesView()))
        controller.setCandidatesSurfaceActive(true)
        controller.updateStatusBars(snapshot(listOf("secret")), "", null, null)
        assertTrue(controller.showInlineAutofill(listOf(false)) { listOf(chip("again")) })
        controller.setInlineAutofillBlocked(true)
        assertNull(findHost(controller.getInputView()))
        assertFalse(controller.showInlineAutofill(listOf(false)) { listOf(chip("blocked")) })
        assertNull(findHost(controller.getCandidatesView()))
    }

    @Test
    @Config(sdk = [34])
    fun serviceFinishInputClearsChipsAndStillOffersNoRequest() {
        SettingsManager.setSuggestionsEnabled(context, true)
        SettingsManager.setStatusBarVariationsEnabled(context, true)
        SettingsManager.setModifierIndicators(context, emptySet())
        SettingsManager.setInlineAutofillEnabled(context, true)
        val handle = Robolectric.buildService(PhysicalKeyboardInputMethodService::class.java).create()
        try {
            val service = handle.get()
            assertNull(service.onCreateInlineSuggestionsRequest(uiExtras()))
            val controller = ReflectionHelpers.getField<CandidatesBarController>(service, "candidatesBarController")
            controller.getInputView()
            controller.getCandidatesView()
            controller.updateStatusBars(snapshot(listOf("secret")), "", null, null)
            assertTrue(controller.showInlineAutofill(listOf(true)) { listOf(chip("finish")) })
            assertNotNull(findHost(controller.getInputView()))
            service.onFinishInput()
            assertNull(findHost(controller.getInputView()))
            assertNull(findHost(controller.getCandidatesView()))
            assertNull(service.onCreateInlineSuggestionsRequest(uiExtras()))
        } finally {
            handle.destroy()
        }
    }

    @Test
    fun suggestionStyleUsesSolidChipDrawableAndIgnoresTransparentTextures() {
        val texture = "texture-" + "ab".repeat(32) + ".png"
        val theme = SettingsManager.defaultKeyboardTheme().copy(
            suggestion = 0xFF112233.toInt(),
            textAndIcons = 0xFFEEEEDD.toInt(),
            normalKey = 0xFF445566.toInt(),
            textures = mapOf("background" to KeyboardThemeTexture(asset = texture, wholeKeyboard = true))
        )
        SettingsManager.setKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE, theme)
        val style = InlineAutofill.suggestionStyle(theme)
        assertEquals(0xFF112233.toInt(), chipPixel(style))
        assertFalse(bundleText(style).contains("texture-"))
        assertEquals(0xFFEEEEDD.toInt(), titleColor(style))
        val transparent = theme.copy(suggestion = Color.TRANSPARENT)
        assertEquals(0xFF445566.toInt(), chipPixel(InlineAutofill.suggestionStyle(transparent)))
        val request = InlineAutofill.createRequest(context, uiExtras(), hostAvailable = true)
        assertNotNull(request)
        assertEquals(0xFF112233.toInt(), chipPixel(request!!.inlinePresentationSpecs.first().style))
    }

    @Test
    fun chipHostBackgroundUsesOpaqueThemeColor() {
        val theme = SettingsManager.defaultKeyboardTheme().copy(suggestion = 0xFF336699.toInt())
        SettingsManager.setKeyboardTheme(context, SettingsManager.KeyboardThemeTarget.HARDWARE, theme)
        val bar = openBar(placements.first())
        assertTrue(bar.showInlineAutofill(chipViews("a"), listOf(true)))
        val host = findHost(bar.getLayout()!!)!!
        val background = host.background as ColorDrawable
        assertEquals(0xFF336699.toInt(), background.color)
        assertEquals(255, Color.alpha(background.color))
    }

    private fun openBar(row: Placement): StatusBarController {
        SettingsManager.setSuggestionsEnabled(context, row.suggestions)
        SettingsManager.setStatusBarVariationsEnabled(context, row.actions)
        SettingsManager.setModifierIndicators(
            context,
            if (row.ledOnly) setOf(SettingsManager.MODIFIER_INDICATOR_BOTTOM_STRIP) else emptySet()
        )
        KeyboardBarConfiguration.setArrangement(context, row.arrangement)
        val bar = StatusBarController(context, StatusBarController.Mode.INPUT_VIEW)
        bar.setInlineAutofillBlocked(row.blocked)
        bar.update(snapshot(suggestions = listOf("secret", "other"), password = row.passwordField))
        return bar
    }

    private fun snapshot(suggestions: List<String>, password: Boolean = false) = StatusBarController.StatusSnapshot(
        capsLockEnabled = false,
        shiftPhysicallyPressed = false,
        shiftOneShot = false,
        ctrlLatchActive = false,
        ctrlPhysicallyPressed = false,
        ctrlOneShot = false,
        ctrlLatchFromNavMode = false,
        altLatchActive = false,
        altPhysicallyPressed = false,
        altOneShot = false,
        symPage = 0,
        suggestions = suggestions,
        shouldDisableSuggestions = password
    )

    private fun chipViews(vararg names: String) = names.map { chip(it) }

    private fun chip(name: String) = View(context).apply { tag = name }

    private fun findHost(root: View): InlineAutofillChipHost? {
        if (root is InlineAutofillChipHost) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findHost(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun hostInVariations(host: View): Boolean {
        var view: View? = host
        var inSuggestions = false
        while (view != null) {
            if (view.javaClass.simpleName == "CursorSwipeLayout") inSuggestions = true
            view = view.parent as? View
        }
        return !inSuggestions
    }

    private fun hostInSuggestionSlots(host: View): Boolean {
        var view: View? = host.parent as? View
        while (view != null) {
            if (view.accessibilityLiveRegion != View.ACCESSIBILITY_LIVE_REGION_NONE) return true
            if (view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL &&
                view.layoutParams is android.widget.FrameLayout.LayoutParams &&
                (view.layoutParams as android.widget.FrameLayout.LayoutParams).width == ViewGroup.LayoutParams.MATCH_PARENT &&
                (0 until view.childCount).any { view.getChildAt(it) is TextView }
            ) {
                return true
            }
            view = view.parent as? View
        }
        return false
    }

    private fun suggestionTexts(root: View): List<String> {
        val found = mutableListOf<String>()
        fun walk(view: View) {
            if (view is TextView && view.visibility == View.VISIBLE) {
                found += view.text.toString()
            }
            if (view is ViewGroup && view.visibility == View.VISIBLE) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        return found
    }

    private fun uiExtras(): Bundle =
        UiVersions.newStylesBuilder().addStyle(InlineSuggestionUi.newStyleBuilder().build()).build()

    private fun chipPixel(style: Bundle): Int {
        val chip = style.getBundle(UiVersions.INLINE_UI_VERSION_1)!!.getBundle("chip_style")!!
        assertFalse(chip.containsKey("background_color"))
        val icon = chip.getParcelable("background", Icon::class.java)!!
        val stored = runCatching { org.robolectric.util.ReflectionHelpers.getField<Bitmap>(icon, "mObj1") }.getOrNull()
        val bitmap = if (stored != null && stored.width > 0) stored else {
            val drawable = icon.loadDrawable(context)!!
            Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(8), drawable.intrinsicHeight.coerceAtLeast(8), Bitmap.Config.ARGB_8888).also { drawn ->
                val canvas = Canvas(drawn)
                drawable.setBounds(0, 0, drawn.width, drawn.height)
                drawable.draw(canvas)
            }
        }
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    private fun titleColor(style: Bundle): Int =
        style.getBundle(UiVersions.INLINE_UI_VERSION_1)!!.getBundle("title_style")!!.getInt("text_color")

    private fun bundleText(bundle: Bundle): String = buildString { appendBundle(bundle) }

    private fun StringBuilder.appendBundle(bundle: Bundle) {
        for (key in bundle.keySet()) {
            append(key)
            when (val value = bundle.get(key)) {
                is Bundle -> appendBundle(value)
                is String -> append(value)
                else -> append(value?.javaClass?.simpleName ?: "")
            }
        }
    }
}
