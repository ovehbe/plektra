package rocks.pkb.plektra.inputmethod

import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
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

    private fun measuredHeight(): Int {
        chrome.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST)
        )
        return chrome.measuredHeight
    }

    private val animator get() = ReflectionHelpers.getField<ValueAnimator?>(chrome, "heightAnimator")

    private fun heightAt(playTimeMs: Long): Int {
        animator!!.currentPlayTime = playTimeMs
        return measuredHeight()
    }

    /** The height on each 16 ms frame from the start of the running animation through its end. */
    private fun frames(): List<Int> = (1..8).map { heightAt(it * 16L) }

    @Test
    fun removingARowEasesToTheNewHeightWithinTheConfiguredDuration() {
        chrome.rowChangeAnimationMs = 120
        assertEquals(2 * ROW, measuredHeight())
        suggestionsRow.visibility = View.GONE
        assertEquals("the first frame still shows the old height", 2 * ROW, measuredHeight())
        assertEquals(120L, animator!!.duration)
        val frames = frames()
        assertTrue("$frames", frames.zipWithNext().all { (a, b) -> b <= a })
        assertTrue("$frames", frames.count { it in (ROW + 1) until 2 * ROW } >= 4)
        assertEquals("settled by 128 ms: $frames", ROW, frames.last())
        assertEquals(ROW, measuredHeight())
    }

    @Test
    fun addingARowGrowsSmoothly() {
        chrome.rowChangeAnimationMs = 120
        suggestionsRow.visibility = View.GONE
        assertEquals(ROW, measuredHeight())
        suggestionsRow.visibility = View.VISIBLE
        assertEquals(ROW, measuredHeight())
        val frames = frames()
        assertTrue("$frames", frames.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("$frames", frames.count { it in (ROW + 1) until 2 * ROW } >= 4)
        assertEquals(2 * ROW, frames.last())
    }

    @Test
    fun theConfiguredDurationIsUsed() {
        chrome.rowChangeAnimationMs = 250
        measuredHeight()
        suggestionsRow.visibility = View.GONE
        measuredHeight()
        assertEquals(250L, animator!!.duration)
        assertTrue(heightAt(128) in (ROW + 1) until 2 * ROW)
        assertEquals(ROW, heightAt(250))
    }

    @Test
    fun withAnimationOffTheHeightSnaps() {
        chrome.rowChangeAnimationMs = 0
        assertEquals(2 * ROW, measuredHeight())
        suggestionsRow.visibility = View.GONE
        assertEquals(ROW, measuredHeight())
        suggestionsRow.visibility = View.VISIBLE
        assertEquals(2 * ROW, measuredHeight())
        assertNull(animator)
    }

    @Test
    fun relayoutsAtTheSameHeightNeverAnimate() {
        chrome.rowChangeAnimationMs = 120
        assertEquals(2 * ROW, measuredHeight())
        repeat(5) {
            suggestionsRow.requestLayout()
            assertEquals(2 * ROW, measuredHeight())
        }
        assertNull(animator)
    }

    @Test
    fun aRowChangeDuringTheAnimationContinuesFromTheCurrentHeight() {
        chrome.rowChangeAnimationMs = 120
        assertEquals(2 * ROW, measuredHeight())
        suggestionsRow.visibility = View.GONE
        measuredHeight()
        val first = animator
        val midway = heightAt(48)
        assertTrue("$midway", midway in (ROW + 1) until 2 * ROW)
        suggestionsRow.visibility = View.VISIBLE
        assertEquals("no jump back to either end", midway, measuredHeight())
        assertTrue("the first animation stops", first !== animator && !first!!.isRunning)
        val frames = frames()
        assertTrue("$frames", frames.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(2 * ROW, frames.last())
    }

    @Test
    fun theBarReturningFromHiddenShowsAtItsNewHeightWithoutAnimating() {
        chrome.rowChangeAnimationMs = 120
        assertEquals(2 * ROW, measuredHeight())
        chrome.visibility = View.GONE
        suggestionsRow.visibility = View.GONE
        chrome.visibility = View.VISIBLE
        assertEquals(ROW, measuredHeight())
        assertNull(animator)
    }

    @Test
    fun aParentRemeasuringAtTheAnimatedHeightDoesNotStopTheBarShort() {
        chrome.rowChangeAnimationMs = 120
        /** A wrap-content pass, then the parent fixing the chrome at the height it reported. */
        fun parentPasses(): Int {
            val height = measuredHeight()
            chrome.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            return chrome.measuredHeight
        }
        suggestionsRow.visibility = View.GONE
        assertEquals(ROW, parentPasses())
        suggestionsRow.visibility = View.VISIBLE
        parentPasses()
        val frames = (1..8).map { animator!!.currentPlayTime = it * 16L; parentPasses() }
        assertEquals("$frames", 2 * ROW, frames.last())
        suggestionsRow.visibility = View.GONE
        parentPasses()
        val shrinking = (1..8).map { animator!!.currentPlayTime = it * 16L; parentPasses() }
        assertEquals("$shrinking", ROW, shrinking.last())
    }

    @Test
    fun theSoftwareKeyboardKeepsOnlyItsModeTransition() {
        chrome.rowChangeAnimationMs = 120
        chrome.softwareKeyboardModeActive = true
        assertEquals(2 * ROW, measuredHeight())
        suggestionsRow.visibility = View.GONE
        assertEquals(ROW, measuredHeight())
        assertNull(animator)
    }

    private companion object {
        const val ROW = 100
    }
}
