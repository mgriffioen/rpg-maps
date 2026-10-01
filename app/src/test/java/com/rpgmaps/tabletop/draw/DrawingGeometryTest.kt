package com.rpgmaps.tabletop.draw

import com.rpgmaps.tabletop.display.protocol.DrawKind
import com.rpgmaps.tabletop.display.protocol.Drawing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingGeometryTest {

    private fun drawing(kind: DrawKind, vararg pts: Float, width: Float = 4f) =
        Drawing(1, kind, "#FFFFFFFF", width, pts.toList())

    @Test
    fun `the eraser catches a line near it and misses one far away`() {
        val line = drawing(DrawKind.PEN, 0f, 0f, 100f, 0f)
        assertTrue(line.isHit(50f, 5f, tolerance = 4f))   // 5 <= 4 + width/2
        assertFalse(line.isHit(50f, 7f, tolerance = 4f))
        assertTrue("past the end, within reach of the cap", line.isHit(103f, 0f, tolerance = 2f))
    }

    @Test
    fun `a pen dot can be erased`() {
        assertTrue(drawing(DrawKind.PEN, 10f, 10f).isHit(12f, 10f, tolerance = 1f))
    }

    @Test
    fun `shapes are hit on their outline, not inside`() {
        val rect = drawing(DrawKind.RECT, 0f, 0f, 100f, 60f)
        assertTrue(rect.isHit(100f, 30f, tolerance = 1f))
        assertFalse(rect.isHit(50f, 30f, tolerance = 1f))

        val oval = drawing(DrawKind.OVAL, 0f, 0f, 100f, 60f)
        assertTrue(oval.isHit(50f, 0f, tolerance = 1f))   // top of the ellipse
        assertTrue(oval.isHit(100f, 30f, tolerance = 1f)) // right of the ellipse
        assertFalse(oval.isHit(50f, 30f, tolerance = 1f))
        assertFalse("the box corner is outside the ellipse", oval.isHit(0f, 0f, tolerance = 1f))
    }

    @Test
    fun `an arrow can be erased by its head`() {
        val arrow = drawing(DrawKind.ARROW, 0f, 0f, 200f, 0f, width = 5f)
        val head = arrowHead(0f, 0f, 200f, 0f, 5f)!!
        assertTrue(arrow.isHit(head[0], head[1], tolerance = 0.5f))
    }

    @Test
    fun `the arrowhead scales with width but never outgrows the shaft`() {
        val head = arrowHead(0f, 0f, 200f, 0f, width = 5f)!!
        // 4x width back from the tip, splayed 28 degrees either side.
        assertEquals(200f - 20f * kotlin.math.cos(Math.toRadians(28.0)).toFloat(), head[0], 0.01f)
        assertEquals(head[1], -head[3], 0.01f)

        val stubby = arrowHead(0f, 0f, 10f, 0f, width = 10f)!!
        val length = kotlin.math.hypot(stubby[0] - 10f, stubby[1])
        assertEquals("half the shaft at most", 5f, length, 0.01f)
    }

    @Test
    fun `a zero length arrow has no head`() {
        assertNull(arrowHead(3f, 3f, 3f, 3f, 5f))
        assertNotNull(arrowHead(3f, 3f, 4f, 3f, 5f))
    }
}
