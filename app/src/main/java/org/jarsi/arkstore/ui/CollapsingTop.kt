package org.jarsi.arkstore.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.unit.Velocity
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How far the top of the screen, the bar with the search and the filters, has slid up out
 * of view. The list below it scrolls as usual; what it would scroll by goes first into
 * sliding the top away, downwards, or back into view, upwards, so the top is never further
 * than a short scroll up away. A fling takes the top with it the same way, and a gesture
 * that leaves the top half-way lets it settle, unhurried, to whichever edge is nearer.
 *
 * The top never takes the whole window: at least [minRoomBelow] pixels stay for the list,
 * so that there is always somewhere to scroll from. In a window lower than that the top
 * is shown through a [viewport] lower than itself, and slides through it the same way: the
 * bar goes up first and the rows below it come into view in its place, so that every row
 * can be reached. A top kept in place ([hide] false) slides only as far as brings its
 * last row into view.
 */
internal class CollapsingTop {
    /** Pixels, from 0 (all shown) down to the [floor]. */
    var offset by mutableFloatStateOf(0f)

    /** Whether the top slides out of view at all, or only as far as its viewport needs. */
    var hide = true

    /** The top's height in pixels as last laid out. */
    var height = 0

    /** How many pixels of the top may be in view at once; [height] when it all fits. */
    var viewport = 0

    /** How many pixels the list keeps however high the top is; set from the layout. */
    var minRoomBelow = 0

    /** Whether the top is on its way to an edge; a touch or a focus that moves it ends that. */
    var settling = false
        private set

    /** The lowest offset the top may have as it is now. */
    private val floor: Float get() = if (hide) -height.toFloat() else (viewport - height).toFloat()

    /**
     * Takes what the top can of a scroll by [dy] pixels (down negative) and returns how much
     * it took. An offset left over from a higher top is first brought within the current
     * height, so that a top that has grown lower does not pay out the difference as a jump.
     */
    fun consume(dy: Float): Float {
        if (height == 0 || dy == 0f) return 0f
        settling = false
        val before = offset.coerceIn(floor, 0f)
        val after = (before + dy).coerceIn(floor, 0f)
        offset = after
        return after - before
    }

    /** Brings the whole top back at once, and ends any settling on the way. */
    fun show() {
        settling = false
        offset = 0f
    }

    /**
     * Slides the top just far enough for a child at [top]..[bottom] pixels, in the top's
     * coordinates as laid out, to be within the [visible] pixels; a child in view leaves
     * the top as it is. A keyboard or a D-pad focusing a control that has slid out of
     * view goes through here.
     */
    fun reveal(top: Float, bottom: Float, visible: Int) {
        if (height == 0) return
        // A settling under way would take the focused control out of view again.
        settling = false
        val by = if (top < 0f) -top else if (bottom > visible) visible - bottom else return
        offset = (offset.coerceIn(floor, 0f) + by).coerceIn(floor, 0f)
    }

    /**
     * Where the top goes once a gesture has left it: all shown or all hidden, whichever is
     * nearer, or null where it stays as it is, kept in place or shown through a viewport
     * lower than itself, where a part of it in view is the point.
     */
    fun settleTarget(): Float? {
        if (!hide || height == 0 || viewport < height) return null
        val at = offset.coerceIn(-height.toFloat(), 0f)
        if (at == 0f || at == -height.toFloat()) return null
        return if (at > -height / 2f) 0f else -height.toFloat()
    }

    /** Marks the top as on its way to its [settleTarget] and gives it, or null when it stays. */
    fun startSettling(): Float? {
        val target = settleTarget() ?: return null
        settling = true
        return target
    }

    /** Eases the top to its [settleTarget], unless a touch, a focus or [show] moves it meanwhile. */
    suspend fun settle() {
        val target = startSettling() ?: return
        animate(offset, target, animationSpec = SETTLE) { value, _ ->
            if (!settling) throw CancellationException("moved")
            offset = value
        }
        settling = false
    }

    /** The connection to give the list. */
    fun connection() = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            Offset(0f, consume(available.y))

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle()
            return Velocity.Zero
        }
    }

    /** The height to lay the top out at, of [full] pixels with [room] below for the list. */
    fun shown(full: Int, room: Int): Int {
        height = full
        viewport = min(full, (room - minRoomBelow).coerceAtLeast(0))
        return (full + placement).coerceIn(0, viewport)
    }

    /** Where the top's content is placed: as far above the edge as it has slid. */
    val placement: Int get() = offset.roundToInt().coerceIn(floor.roundToInt(), 0)

    private companion object {
        /** Soft and without a bounce, so that the top settles rather than snaps. */
        val SETTLE = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    }
}

/**
 * Lays the top out as high as is still in view, and the rest above the edge, and slides
 * it to show a child that asks to be seen, such as a control the keyboard focuses. The
 * revealing node sits outside the layout, so that it measures a child against the part
 * in view, not against the whole of the top it is placed in.
 */
internal fun Modifier.collapsing(top: CollapsingTop): Modifier = this
    .then(RevealElement(top))
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        val shown = top.shown(placeable.height, constraints.maxHeight)
        layout(placeable.width, shown) { placeable.placeRelative(0, top.placement) }
    }

private data class RevealElement(val top: CollapsingTop) : ModifierNodeElement<RevealNode>() {
    override fun create() = RevealNode(top)
    override fun update(node: RevealNode) {
        node.top = top
    }
}

private class RevealNode(var top: CollapsingTop) : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        val bounds = boundsProvider() ?: return
        val mine = requireLayoutCoordinates()
        if (!mine.isAttached || !childCoordinates.isAttached) return
        val child = mine.localBoundingBoxOf(childCoordinates, clipBounds = false)
        top.reveal(child.top + bounds.top, child.top + bounds.bottom, mine.size.height)
    }
}
