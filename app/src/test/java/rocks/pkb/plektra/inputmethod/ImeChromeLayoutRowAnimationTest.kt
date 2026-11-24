package rocks.pkb.plektra.inputmethod

import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Frames are stepped through the layout's own animator: Robolectric's looper runs an attached window's animation at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeChromeLayoutRowAnimationTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val chrome = ImeChromeLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val suggestionsRow = row()
    private val actionsRow = row()

    init {
        chrome.addView(suggestionsRow)
        chrome.addView(actionsRow)
        activity.setContentView(FrameLayout(activity).apply {
            addView(chrome, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun row() = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ROW)
    }

    /** The bar as the IME window sees it: the window's height and the part of it that is drawn. */
    private data class Bar(val window: Int, val visible: Int)

    /** A wrap-content pass, then the window capped at the height just reported, then layout. */
    private fun pass(capMode: Int = View.MeasureSpec.AT_MOST): Bar {
        measure(2000, View.MeasureSpec.AT_MOST)
        measure(chrome.measuredHeight, capMode)
        chrome.layout(0, 0, 1080, chrome.measuredHeight)
        return Bar(chrome.height, chrome.clipBounds?.height() ?: chrome.height)
    }

    private fun measure(height: Int, mode: Int) = chrome.measure(
        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, mode)
    )

    private val animator get() = ReflectionHelpers.getField<ValueAnimator?>(chrome, "heightAnimator")

    /** A frame redraws without asking for layout; a layout pass only follows if the window must resize. */
    private fun at(playTimeMs: Long): Bar {
        animator!!.currentPlayTime = playTimeMs
        if (chrome.isLayoutRequested) return pass()
        return Bar(chrome.height, chrome.clipBounds?.height() ?: chrome.height)
    }

    /** Each 16 ms frame from the start of the running animation through its end. */
    private fun frames(): List<Bar> = (1..8).map { at(it * 16L) }

    @Test
    fun removingARowEasesToTheNewHeightWithinTheConfiguredDuration() {
        chrome.rowChangeAnimationMs = 120
        assertEquals(Bar(2 * ROW, 2 * ROW), pass())
        suggestionsRow.visibility = View.GONE
        assertEquals("the first frame still shows the old height", Bar(2 * ROW, 2 * ROW), pass())
        assertEquals(120L, animator!!.duration)
        val frames = frames()
        val visible = frames.map { it.visible }
        assertTrue("$frames", visible.zipWithNext().all { (a, b) -> b <= a })
        assertTrue("$frames", visible.count { it in (ROW + 1) until 2 * ROW } >= 4)
        assertTrue("the window shrinks once, at the end: $frames",
            frames.dropLast(1).all { it.window == 2 * ROW })
        assertEquals("settled by 128 ms: $frames", Bar(ROW, ROW), frames.last())
        assertNull(chrome.clipBounds)
        assertEquals(Bar(ROW, ROW), pass())
    }

    @Test
    fun addingARowGrowsSmoothly() {
        chrome.rowChangeAnimationMs = 120
        suggestionsRow.visibility = View.GONE
        assertEquals(Bar(ROW, ROW), pass())
        suggestionsRow.visibility = View.VISIBLE
        assertEquals("the window grows at once; the new row is revealed", Bar(2 * ROW, ROW), pass())
        val frames = frames()
        val visible = frames.map { it.visible }
        assertTrue("$frames", frames.all { it.window == 2 * ROW })
        assertTrue("$frames", visible.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("$frames", visible.count { it in (ROW + 1) until 2 * ROW } >= 4)
        assertEquals(Bar(2 * ROW, 2 * ROW), frames.last())
        assertNull(chrome.clipBounds)
    }

    @Test
    fun framesOnlyRedrawAndNeverRelayout() {
        chrome.rowChangeAnimationMs = 120
        pass()
        for (visibility in listOf(View.GONE, View.VISIBLE)) {
            suggestionsRow.visibility = visibility
            pass()
            for (time in listOf(16L, 48L, 96L)) {
                animator!!.currentPlayTime = time
                assertFalse("frame at $time ms", chrome.isLayoutRequested)
            }
            animator!!.end()
            pass()
        }
    }

    @Test
    fun theClipFollowsTheBottomEdge() {
        chrome.rowChangeAnimationMs = 120
        pass()
        suggestionsRow.visibility = View.GONE
        pass()
        at(48)
        val clip = chrome.clipBounds!!
        assertEquals(chrome.height, clip.bottom)
        assertEquals(0, clip.left)
        assertEquals(chrome.width, clip.right)
    }

    @Test
    fun theConfiguredDurationIsUsed() {
        chrome.rowChangeAnimationMs = 250
        pass()
        suggestionsRow.visibility = View.GONE
        pass()
        assertEquals(250L, animator!!.duration)
        assertTrue(at(128).visible in (ROW + 1) until 2 * ROW)
        assertEquals(Bar(ROW, ROW), at(250))
    }

    @Test
    fun withAnimationOffTheHeightSnaps() {
        chrome.rowChangeAnimationMs = 0
        assertEquals(Bar(2 * ROW, 2 * ROW), pass())
        suggestionsRow.visibility = View.GONE
        assertEquals(Bar(ROW, ROW), pass())
        suggestionsRow.visibility = View.VISIBLE
        assertEquals(Bar(2 * ROW, 2 * ROW), pass())
        assertNull(animator)
        assertNull(chrome.clipBounds)
    }

    @Test
    fun relayoutsAtTheSameHeightNeverAnimate() {
        chrome.rowChangeAnimationMs = 120
        assertEquals(Bar(2 * ROW, 2 * ROW), pass())
        repeat(5) {
            suggestionsRow.requestLayout()
            assertEquals(Bar(2 * ROW, 2 * ROW), pass())
        }
        assertNull(animator)
    }

    @Test
    fun aRowChangeDuringTheAnimationContinuesFromTheCurrentHeight() {
        chrome.rowChangeAnimationMs = 120
        assertEquals(Bar(2 * ROW, 2 * ROW), pass())
        suggestionsRow.visibility = View.GONE
        pass()
        val first = animator
        val midway = at(48).visible
        assertTrue("$midway", midway in (ROW + 1) until 2 * ROW)
        suggestionsRow.visibility = View.VISIBLE
        assertEquals("no jump back to either end", Bar(2 * ROW, midway), pass())
        assertTrue("the first animation stops", first !== animator && !first!!.isRunning)
        val frames = frames()
        assertTrue("$frames", frames.map { it.visible }.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(Bar(2 * ROW, 2 * ROW), frames.last())
    }

    @Test
    fun theBarReturningFromHiddenShowsAtItsNewHeightWithoutAnimating() {
        chrome.rowChangeAnimationMs = 120
        assertEquals(Bar(2 * ROW, 2 * ROW), pass())
        chrome.visibility = View.GONE
        suggestionsRow.visibility = View.GONE
        chrome.visibility = View.VISIBLE
        assertEquals(Bar(ROW, ROW), pass())
        assertNull(animator)
    }

    @Test
    fun hidingTheBarMidAnimationDropsTheClip() {
        chrome.rowChangeAnimationMs = 120
        pass()
        suggestionsRow.visibility = View.GONE
        pass()
        at(48)
        chrome.visibility = View.GONE
        assertNull(chrome.clipBounds)
        chrome.visibility = View.VISIBLE
        assertEquals(Bar(ROW, ROW), pass())
    }

    @Test
    fun aParentCappingTheBarAtItsReportedHeightDoesNotStopTheAnimation() {
        chrome.rowChangeAnimationMs = 120
        for (capMode in listOf(View.MeasureSpec.AT_MOST, View.MeasureSpec.EXACTLY)) {
            suggestionsRow.visibility = View.GONE
            pass(capMode)
            animator?.end()
            assertEquals(Bar(ROW, ROW), pass(capMode))
            suggestionsRow.visibility = View.VISIBLE
            pass(capMode)
            val growing = (1..8).map { animator!!.currentPlayTime = it * 16L; pass(capMode) }
            assertEquals("$capMode $growing", Bar(2 * ROW, 2 * ROW), growing.last())
            assertTrue("$capMode $growing", growing.first().visible in (ROW + 1) until 2 * ROW)
            suggestionsRow.visibility = View.GONE
            pass(capMode)
            val shrinking = (1..8).map { animator!!.currentPlayTime = it * 16L; pass(capMode) }
            assertEquals("$capMode $shrinking", Bar(ROW, ROW), shrinking.last())
            assertTrue("$capMode $shrinking", shrinking.first().visible in (ROW + 1) until 2 * ROW)
        }
    }

    @Test
    fun aCappedPassDuringTheModeTransitionDoesNotTurnItIntoARowReveal() {
        chrome.rowChangeAnimationMs = 120
        chrome.softwareKeyboardModeActive = true
        suggestionsRow.visibility = View.GONE
        assertEquals(Bar(ROW, ROW), pass())
        suggestionsRow.visibility = View.VISIBLE
        chrome.softwareKeyboardModeActive = false
        pass()
        val transition = animator
        val frames = (1..12).map { transition!!.currentPlayTime = it * 16L; pass() }
        assertTrue("$frames", transition === animator)
        assertNull(chrome.clipBounds)
        assertEquals(Bar(2 * ROW, 2 * ROW), frames.last())
    }

    @Test
    fun theSoftwareKeyboardKeepsOnlyItsModeTransition() {
        chrome.rowChangeAnimationMs = 120
        chrome.softwareKeyboardModeActive = true
        assertEquals(Bar(2 * ROW, 2 * ROW), pass())
        suggestionsRow.visibility = View.GONE
        assertEquals(Bar(ROW, ROW), pass())
        assertNull(animator)
    }

    private companion object {
        const val ROW = 100
    }
}
