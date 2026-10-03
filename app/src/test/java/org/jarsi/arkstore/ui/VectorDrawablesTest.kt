package org.jarsi.arkstore.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorDrawablesTest {

    @Test
    fun groupsAndPathsAreBuiltAsDescribed() {
        val described = """{"width":108,"height":54,"root":{"nodes":[
            {"rotation":45,"pivotX":54,"pivotY":27,"scaleX":2,"clip":"M0 0h108v54h-108z",
             "nodes":[{"path":"M0 0h10v10z","fill":"#ff112233"}]},
            {"path":"M5 5h2v2z","stroke":"#80445566","strokeWidth":3,"fillType":1,"cap":1},
            {"path":"not a path","fill":"#ff000000"}
        ]}}"""
        val image = VectorDrawables.fromJson(described)!!
        assertEquals(108f, image.viewportWidth)
        assertEquals(54f, image.viewportHeight)
        val nodes = image.root.toList()
        assertEquals(2, nodes.size)
        val group = nodes[0] as VectorGroup
        assertEquals(45f, group.rotation)
        assertEquals(54f, group.pivotX)
        assertEquals(2f, group.scaleX)
        assertEquals(1f, group.scaleY)
        assertTrue(group.clipPathData.isNotEmpty())
        val inner = group.single() as VectorPath
        assertEquals(SolidColor(Color(0xFF112233)), inner.fill)
        assertNull(inner.stroke)
        val stroked = nodes[1] as VectorPath
        assertNull(stroked.fill)
        assertEquals(SolidColor(Color(0x80445566)), stroked.stroke)
        assertEquals(3f, stroked.strokeLineWidth)
        assertEquals(PathFillType.EvenOdd, stroked.pathFillType)
        assertEquals(StrokeCap.Round, stroked.strokeLineCap)
    }

    @Test
    fun descriptionThatIsNoDrawableIsNone() {
        assertNull(VectorDrawables.fromJson("not json"))
        assertNull(VectorDrawables.fromJson("""{"width":0,"height":108,"root":{"nodes":[]}}"""))
        assertNull(VectorDrawables.fromJson("""{"width":108,"height":108}"""))
    }

    @Test
    fun coloursAreReadWithAndWithoutAlpha() {
        assertEquals(Color(0xFF112233), VectorDrawables.color("#112233"))
        assertEquals(Color(0x80445566), VectorDrawables.color("#80445566"))
        assertNull(VectorDrawables.color("red"))
        assertNull(VectorDrawables.color("#12345"))
        assertNull(VectorDrawables.color(""))
    }
}
