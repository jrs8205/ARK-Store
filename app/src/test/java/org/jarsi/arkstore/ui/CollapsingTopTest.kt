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
    fun listKeepsItsRoomInALowWindow() {
        val top = top(600, room = 500, minRoom = 200)
        assertEquals(300, top.height)
        assertEquals(300, top.shown(600, 500))
        top.consume(-1000f)
        assertEquals(0, top.shown(600, 500))
        assertEquals(-300f, top.offset)
    }

    @Test
    fun nothingHappensWithoutAHeightOrAScroll() {
        assertEquals(0f, CollapsingTop().consume(-10f))
        assertEquals(0f, top(100).consume(0f))
    }
}
