package rocks.pkb.plektra.inputmethod.suggestions.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import rocks.pkb.plektra.R
import rocks.pkb.plektra.inputmethod.ImeChromeLayout
import rocks.pkb.plektra.inputmethod.statusbar.KeyboardBarRowGeometry

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CenterSlotDetachedViewTest(private val scenario: Scenario, private val leds: Boolean) {
    enum class Scenario { PASTE_CLEAR, LONG_PRESS, HIDDEN_EXPIRY, INACTIVE, REPLACE, INLINE, TAG, DETACHED }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}, contourLEDs={1}")
        fun cases() = Scenario.values().flatMap { scenario ->
            val modes = when (scenario) {
                Scenario.PASTE_CLEAR, Scenario.LONG_PRESS, Scenario.HIDDEN_EXPIRY -> listOf(false, true)
                else -> listOf(false)
            }
            modes.map { arrayOf(scenario, it) }
        }
    }

    private val context get() = RuntimeEnvironment.getApplication()

    private inner class Strip(label: String) {
        val bar = FullSuggestionsBar(context)
        val frame = bar.ensureView()
        val chrome = ImeChromeLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            contourIntegratedIndicators = leds
            addView(frame)
            if (leds) indicatorView = View(context).also { addView(it) }
        }
        val middle: TextView

        init {
            bar.setContentEnabled(suggestions = true, menuButtons = false)
            bar.centerAction = FullSuggestionsBar.CenterAction(label, { bar.centerAction = null }, {})
            render()
            layout()
            middle = taggedSurfaces().filterIsInstance<TextView>().single { it.text == label }
            assertEquals(View.VISIBLE, middle.visibility)
            assertTrue(middle.width > 0)
        }

        fun render() = bar.update(listOf("one", "two", "three"), true, null, null, false, null,
            null, null, null, null, null, null)

        fun layout() {
            chrome.forceLayout()
            chrome.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
            chrome.layout(0, 0, chrome.measuredWidth, chrome.measuredHeight)
        }

        // Read the stored Row, not a fresh walk of the surviving children.
        @Suppress("UNCHECKED_CAST")
        fun taggedSurfaces(): List<View> {
            val row = frame.getTag(R.id.keyboard_bar_row_geometry)
            return row.javaClass.getDeclaredField("surfaces").apply { isAccessible = true }.get(row) as List<View>
        }

        fun assertRemainingSurfacesUnder(parent: ViewGroup) {
            val remaining = taggedSurfaces()
            assertTrue(remaining.all { view -> generateSequence(view.parent) { it.parent }.any { it === parent } })
            assertEquals(2, remaining.size)
        }
    }

    @Test fun layoutAfterCenterSlotChange() {
        val strip = Strip(if (scenario == Scenario.PASTE_CLEAR) "Paste recent text" else "123456")
        with(strip) {
            when (scenario) {
                Scenario.PASTE_CLEAR -> bar.centerAction = null
                Scenario.LONG_PRESS -> {
                    assertTrue(middle.performLongClick())
                    assertNull(bar.centerAction)
                }
                Scenario.HIDDEN_EXPIRY -> {
                    frame.visibility = View.GONE
                    bar.centerAction = null
                    frame.visibility = View.VISIBLE
                }
                Scenario.INACTIVE -> {
                    val active = Strip("654321")
                    bar.centerAction = null
                    active.render()
                    active.layout()
                }
                Scenario.REPLACE -> {
                    bar.centerAction = FullSuggestionsBar.CenterAction("654321", {}, {})
                    assertFalse(middle.hasOnClickListeners())
                    assertNull(shadowOf(middle).onLongClickListener)
                }
                Scenario.INLINE -> {
                    val inline = View(context)
                    bar.inlineContent = inline
                    render()
                    assertSame(frame, inline.parent)
                    assertNull(middle.parent)
                }
                Scenario.TAG -> {
                    bar.centerAction = null
                    assertRemainingSurfacesUnder(frame)
                }
                Scenario.DETACHED -> {
                    // Leave measured, styled edge views in both kinds of stale binding.
                    val (left, _, right) = taggedSurfaces()
                    val slots = middle.parent as ViewGroup
                    KeyboardBarRowGeometry.bind(frame, listOf(left))
                    KeyboardBarRowGeometry.bindTrailingSurface(slots, right)
                    for (edge in listOf(left, right)) {
                        slots.removeView(edge)
                        assertEquals(View.VISIBLE, edge.visibility)
                        assertTrue(edge.width > 0)
                    }
                    val row = requireNotNull(KeyboardBarRowGeometry.bottomRow(chrome))
                    assertNull(row.left)
                    assertNull(row.right)
                }
            }
            layout() // No update() on the cleared/replaced strip before this layout.
            if (scenario == Scenario.PASTE_CLEAR) assertRemainingSurfacesUnder(chrome)
        }
    }
}
