package org.jarsi.arkstore.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsingTopTest {

    private fun top(height: Int, room: Int = 2000, minRoom: Int = 0) = CollapsingTop().apply {
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
        val top = top(600, room = 500, minRoom = 200)
        assertEquals(-300f, top.consume(-1000f, hide = false))
        assertEquals(300, top.shown(600, 500))
        assertEquals(-300, top.placement)
        assertEquals(0f, top.consume(-10f, hide = false))
        assertEquals(300f, top.consume(400f, hide = false))
        // A top that fits is not moved at all.
        assertEquals(0f, top(300).consume(-100f, hide = false))
    }

    @Test
    fun aTopLeftHalfwaySettlesToTheNearerEdge() {
        val top = top(300)
        assertEquals(null, top.settleTarget(hide = true))
        top.consume(-100f)
        assertEquals(0f, top.settleTarget(hide = true))
        top.consume(-100f)
        assertEquals(-300f, top.settleTarget(hide = true))
        // Not when kept in place, nor in a low window, where part of it is the point.
        assertEquals(null, top.settleTarget(hide = false))
        assertEquals(null, top(600, room = 500, minRoom = 200).apply { consume(-200f) }.settleTarget(hide = true))
    }

    @Test
    fun nothingHappensWithoutAHeightOrAScroll() {
        assertEquals(0f, CollapsingTop().consume(-10f))
        assertEquals(0f, top(100).consume(0f))
    }
}
