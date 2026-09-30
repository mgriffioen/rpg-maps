package com.rpgmaps.tabletop.draw

import com.rpgmaps.tabletop.display.protocol.DrawAppendMessage
import com.rpgmaps.tabletop.display.protocol.DrawKind
import com.rpgmaps.tabletop.display.protocol.DrawRemoveMessage
import com.rpgmaps.tabletop.display.protocol.DrawUpsertMessage
import com.rpgmaps.tabletop.display.protocol.Drawing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingLayerTest {

    private val red = "#FFE53935"

    private fun layerWithLine(): DrawingLayer = DrawingLayer().apply {
        begin(DrawKind.PEN, red, 4f, 0f, 0f)
        extend(100f, 0f)
        commit()
    }

    @Test
    fun `a pen line streams whole first, then only its new points`() {
        val layer = DrawingLayer()
        layer.begin(DrawKind.PEN, red, 4f, 10f, 10f)
        layer.extend(20f, 10f)

        val first = layer.flush() as DrawUpsertMessage
        assertEquals(listOf(10f, 10f, 20f, 10f), first.item.pts)
        assertNull("nothing moved, nothing to send", layer.flush())

        layer.extend(30f, 15f)
        val next = layer.flush() as DrawAppendMessage
        assertEquals(first.item.id, next.id)
        assertEquals(listOf(30f, 15f), next.pts)
    }

    @Test
    fun `a resting finger does not add points`() {
        val layer = DrawingLayer()
        layer.begin(DrawKind.PEN, red, 4f, 10f, 10f)
        layer.extend(10.5f, 10f, minStep = 1f)
        assertEquals(listOf(10f, 10f), layer.active!!.pts)
    }

    @Test
    fun `committing sends the final copy and makes one undo step`() {
        val layer = DrawingLayer()
        layer.begin(DrawKind.ARROW, red, 4f, 0f, 0f)
        layer.extend(50f, 60f)
        val message = layer.commit() as DrawUpsertMessage

        assertEquals(listOf(0f, 0f, 50f, 60f), message.item.pts)
        assertEquals(listOf(message.item), layer.items)
        assertNull(layer.active)
        assertTrue(layer.canUndo)

        assertTrue(layer.undo())
        assertTrue(layer.items.isEmpty())
        assertTrue(layer.redo())
        assertEquals(listOf(message.item), layer.items)
    }

    @Test
    fun `a tapped shape is dropped, and removed from receivers that saw it`() {
        val layer = DrawingLayer()
        layer.begin(DrawKind.RECT, red, 4f, 5f, 5f)
        assertNotNull(layer.flush())

        val message = layer.commit()
        assertTrue(message is DrawRemoveMessage)
        assertTrue(layer.items.isEmpty())
        assertFalse("a dropped tap is not an undo step", layer.canUndo)
    }

    @Test
    fun `a tapped pen leaves a dot`() {
        val layer = DrawingLayer()
        layer.begin(DrawKind.PEN, red, 4f, 5f, 5f)
        assertTrue(layer.commit() is DrawUpsertMessage)
        assertEquals(1, layer.items.size)
    }

    @Test
    fun `cancelling an unsent drawing sends nothing`() {
        val layer = DrawingLayer()
        layer.begin(DrawKind.OVAL, red, 4f, 5f, 5f)
        assertNull(layer.cancel())
        assertTrue(layer.visible.isEmpty())
    }

    @Test
    fun `the drawing in progress is part of what a late receiver gets`() {
        val layer = layerWithLine()
        layer.begin(DrawKind.PEN, red, 4f, 0f, 50f)
        assertEquals(2, layer.visible.size)
        assertEquals(1, layer.items.size)
    }

    @Test
    fun `ids carry on after the highest saved one`() {
        val saved = listOf(Drawing(41, DrawKind.PEN, red, 2f, listOf(1f, 1f)))
        val layer = DrawingLayer(saved)
        layer.begin(DrawKind.PEN, red, 2f, 0f, 0f)
        assertEquals(42L, layer.active!!.id)
    }

    @Test
    fun `one erase gesture is one undo step however much it removes`() {
        val layer = DrawingLayer()
        repeat(3) { i ->
            layer.begin(DrawKind.PEN, red, 4f, 0f, i * 100f)
            layer.extend(100f, i * 100f)
            layer.commit()
        }

        layer.beginErase()
        assertTrue(layer.eraseAt(50f, 0f, 5f) is DrawRemoveMessage)
        assertNull("missing everything removes nothing", layer.eraseAt(50f, 50f, 5f))
        assertTrue(layer.eraseAt(50f, 100f, 5f) is DrawRemoveMessage)
        assertTrue(layer.endErase())
        assertEquals(1, layer.items.size)

        layer.undo()
        assertEquals(3, layer.items.size)
    }

    @Test
    fun `an erase that touches nothing is not an undo step`() {
        val layer = layerWithLine()
        layer.undo()
        layer.redo()
        layer.beginErase()
        layer.eraseAt(500f, 500f, 5f)
        assertFalse(layer.endErase())
        assertFalse("the redo branch is untouched", layer.canRedo)
    }

    @Test
    fun `clear is undoable and a no-op when empty`() {
        val layer = layerWithLine()
        assertTrue(layer.clear())
        assertTrue(layer.items.isEmpty())
        assertFalse(layer.clear())
        layer.undo()
        assertEquals(1, layer.items.size)
    }

    @Test
    fun `a new drawing ends the redo branch`() {
        val layer = layerWithLine()
        layer.undo()
        assertTrue(layer.canRedo)
        layer.begin(DrawKind.PEN, red, 4f, 0f, 0f)
        layer.commit()
        assertFalse(layer.canRedo)
    }
}
