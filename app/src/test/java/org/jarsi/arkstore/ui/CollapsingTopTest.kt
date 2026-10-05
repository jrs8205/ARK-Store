package org.jarsi.arkstore.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsingTopTest {

    private fun top(height: Int, room: Int = 2000, minRoom: Int = 0, hide: Boolean = true) = CollapsingTop().apply {
        this.hide = hide
        minRoomBelow = minRoom
        shown(height, room)
    }

    @Test
    fun scrollingDownSlidesTheTopAwayFirstAndUpBringsItBack() {
        val top = top(300)
        assertEquals(-100f, top.consume(-100f))
        assertEquals(-200f, top.consume(-250f))
        assertEquals(-300f, top.offset)
        assertEquals(0f, top.consume(-50f))
        assertEquals(40f, top.consume(40f))
        assertEquals(260f, top.consume(400f))
        assertEquals(0f, top.offset)
    }

    @Test
    fun topGrownLowerDoesNotPayTheDifferenceOutAsAJump() {
        val top = top(300)
        top.consume(-300f)
        top.shown(60, 2000)
        // A 10 pixel scroll up moves the top by 10, not by the 250 the old height would allow.
        assertEquals(10f, top.consume(10f))
        assertEquals(-50f, top.offset)
    }

    @Test
    fun lowWindowKeepsTheListItsRoomAndScrollsTheTopThroughItsWholeHeight() {
        val top = top(600, room = 500, minRoom = 200)
        assertEquals(300, top.viewport)
        assertEquals(300, top.shown(600, 500))
        assertEquals(0, top.placement)
        // The bar goes up first, and the rows below it come into view in its place.
        assertEquals(-200f, top.consume(-200f))
        assertEquals(300, top.shown(600, 500))
        assertEquals(-200, top.placement)
        assertEquals(-400f, top.consume(-1000f))
        assertEquals(0, top.shown(600, 500))
        assertEquals(-600f, top.offset)
        // And comes back the same way.
        assertEquals(350f, top.consume(350f))
        assertEquals(300, top.shown(600, 500))
        assertEquals(-250, top.placement)
    }

    @Test
    fun topKeptInPlaceStillScrollsWhatDoesNotFit() {
        val top = top(600, room = 500, minRoom = 200, hide = false)
        assertEquals(-300f, top.consume(-1000f))
        assertEquals(300, top.shown(600, 500))
        assertEquals(-300, top.placement)
        assertEquals(0f, top.consume(-10f))
        assertEquals(300f, top.consume(400f))
        // A top that fits is not moved at all.
        assertEquals(0f, top(300, hide = false).consume(-100f))
    }

    @Test
    fun topKeptInPlaceFillsItsViewportWhenItsContentShrinks() {
        val top = top(600, room = 500, minRoom = 200, hide = false)
        top.consume(-300f)
        // Shorter content: the old offset would leave a gap below the content, so the
        // placement is held to the new floor at once, not at the next scroll.
        assertEquals(300, top.shown(500, 500))
        assertEquals(-200, top.placement)
        assertEquals(1f, top.consume(1f))
        assertEquals(300, top.shown(500, 500))
    }

    @Test
    fun aTopLeftHalfwaySettlesToTheNearerEdge() {
        val top = top(300)
        assertEquals(null, top.settleTarget())
        top.consume(-100f)
        assertEquals(0f, top.settleTarget())
        top.consume(-100f)
        assertEquals(-300f, top.settleTarget())
        // Not when kept in place, nor in a low window, where part of it is the point.
        top.hide = false
        assertEquals(null, top.settleTarget())
        assertEquals(null, top(600, room = 500, minRoom = 200).apply { consume(-200f) }.settleTarget())
    }

    @Test
    fun showBringsTheWholeTopBack() {
        val top = top(300)
        top.consume(-200f)
        top.show()
        assertEquals(0f, top.offset)
    }

    @Test
    fun revealSlidesTheTopJustFarEnoughToShowAChild() {
        val top = top(300, room = 300, minRoom = 200)
        top.consume(-200f)
        assertEquals(100, top.shown(300, 300))
        // A child below the viewport's lower edge: the top slides up by what is missing.
        top.reveal(80f, 120f, 100)
        assertEquals(-220f, top.offset)
        // A child above the edge: it slides down by what is missing.
        top.reveal(-30f, 10f, 100)
        assertEquals(-190f, top.offset)
        // A child in view: nothing moves.
        top.reveal(20f, 60f, 100)
        assertEquals(-190f, top.offset)
    }

    @Test
    fun nothingHappensWithoutAHeightOrAScroll() {
        assertEquals(0f, CollapsingTop().consume(-10f))
        assertEquals(0f, top(100).consume(0f))
    }
}
