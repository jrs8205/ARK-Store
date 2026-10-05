package org.jarsi.arkstore.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How far the top of the screen, the bar with the search and the filters, has slid up out
 * of view. The list below it scrolls as usual; what it would scroll by goes first into
 * sliding the top away, downwards, or back into view, upwards, so the top is never further
 * than a short scroll up away. A fling takes the top with it the same way.
 *
 * The top never takes the whole window: at least [minRoomBelow] pixels stay for the list,
 * so that there is always somewhere to scroll from, and in a window lower than that the
 * top's lower part is cut off rather than the list.
 */
internal class CollapsingTop {
    /** Pixels, from 0 (all shown) down to minus the top's [height]. */
    var offset by mutableFloatStateOf(0f)

    /** The top's height in pixels as last laid out, as far as it is given room. */
    var height = 0

    /** How many pixels the list keeps however high the top is; set from the layout. */
    var minRoomBelow = 0

    /**
     * Takes what the top can of a scroll by [dy] pixels (down negative) and returns how much
     * it took. An offset left over from a higher top is first brought within the current
     * height, so that a top that has grown lower does not pay out the difference as a jump.
     */
    fun consume(dy: Float): Float {
        if (height == 0 || dy == 0f) return 0f
        val before = offset.coerceIn(-height.toFloat(), 0f)
        val after = (before + dy).coerceIn(-height.toFloat(), 0f)
        offset = after
        return after - before
    }

    /** The connection to give the list's ancestor; [enabled] false leaves the top alone. */
    fun connection(enabled: Boolean) = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (enabled) Offset(0f, consume(available.y)) else Offset.Zero
    }

    /** The height to lay the top out at, of [full] pixels with [room] below for the list. */
    fun shown(full: Int, room: Int): Int {
        height = min(full, (room - minRoomBelow).coerceAtLeast(0))
        return (height + offset.roundToInt().coerceIn(-height, 0)).coerceAtLeast(0)
    }
}

/** Lays the top out as high as is still in view, and the rest above the edge. */
internal fun Modifier.collapsing(top: CollapsingTop): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val shown = top.shown(placeable.height, constraints.maxHeight)
    layout(placeable.width, shown) { placeable.placeRelative(0, shown - placeable.height) }
}
