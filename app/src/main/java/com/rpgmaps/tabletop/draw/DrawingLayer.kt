package com.rpgmaps.tabletop.draw

import com.rpgmaps.tabletop.display.protocol.DisplayMessage
import com.rpgmaps.tabletop.display.protocol.DrawAppendMessage
import com.rpgmaps.tabletop.display.protocol.DrawKind
import com.rpgmaps.tabletop.display.protocol.DrawRemoveMessage
import com.rpgmaps.tabletop.display.protocol.DrawUpsertMessage
import com.rpgmaps.tabletop.display.protocol.Drawing
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The drawings on one map, the one being drawn right now, and their undo
 * history. The drawing counterpart of [com.rpgmaps.tabletop.fog.FogEditor].
 *
 * History is a stack of whole lists rather than of inverse operations. The
 * lists are immutable and share their [Drawing]s, so a snapshot costs one
 * array of references, and restoring one can never get an inverse subtly
 * wrong.
 *
 * Every method that changes what the players should see returns the message
 * that says so, or null. The caller broadcasts it verbatim, which keeps this
 * class free of any transport and testable on the JVM.
 *
 * Not thread-safe -- drive it from the main thread, like the fog editor.
 */
class DrawingLayer(initial: List<Drawing> = emptyList()) {

    /** Committed drawings, oldest first -- which is also the paint order. */
    var items: List<Drawing> = initial
        private set

    /** The drawing under the finger, not yet committed. */
    var active: Drawing? = null
        private set

    /** Committed drawings plus the one in progress: what a late receiver needs. */
    val visible: List<Drawing>
        get() = active?.let { items + it } ?: items

    private var nextId: Long = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    private val undoStack = ArrayDeque<List<Drawing>>()
    private val redoStack = ArrayDeque<List<Drawing>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // --- drawing --------------------------------------------------------

    /** Whether [active] has been sent at all, and how many of its points. */
    private var activeSent = false
    private var sentPoints = 0
    private var activeDirty = false

    /** Starts a drawing at ([x], [y]) in map pixels. */
    fun begin(kind: DrawKind, color: String, width: Float, x: Float, y: Float) {
        val pts = if (kind == DrawKind.PEN) listOf(x, y) else listOf(x, y, x, y)
        active = Drawing(nextId++, kind, color, width, pts)
        activeSent = false
        sentPoints = 0
        activeDirty = true
    }

    /**
     * Moves the finger to ([x], [y]). A pen line gains a point unless it is
     * within [minStep] of the last one -- touch streams repeat coordinates
     * when a finger rests, and those only make the line heavier to send. A
     * shape moves its second corner.
     */
    fun extend(x: Float, y: Float, minStep: Float = 0f) {
        val current = active ?: return
        val p = current.pts
        active = if (current.kind == DrawKind.PEN) {
            if (hypot(x - p[p.size - 2], y - p[p.size - 1]) <= minStep) return
            current.copy(pts = p + listOf(x, y))
        } else {
            current.copy(pts = listOf(p[0], p[1], x, y))
        }
        activeDirty = true
    }

    /**
     * What to send to bring receivers up to date with [active], or null if
     * they already are. Called on a 40 ms timer while a finger is down: the
     * first call sends the whole drawing, later ones only the new pen points
     * or, for a shape, its new corners.
     */
    fun flush(): DisplayMessage? {
        val current = active ?: return null
        if (!activeDirty) return null
        activeDirty = false

        if (!activeSent || current.kind != DrawKind.PEN) {
            activeSent = true
            sentPoints = current.pts.size
            return DrawUpsertMessage(current)
        }
        if (current.pts.size <= sentPoints) return null
        val fresh = current.pts.subList(sentPoints, current.pts.size).toList()
        sentPoints = current.pts.size
        return DrawAppendMessage(current.id, fresh)
    }

    /**
     * Finishes [active] as one undo step. Returns the final copy to send --
     * which also repairs anything a receiver missed while it was being drawn
     * -- or, for a shape too small to see after all, its removal.
     */
    fun commit(): DisplayMessage? {
        val current = active ?: return null
        val wasSent = activeSent
        clearActive()

        if (!isVisible(current)) {
            return if (wasSent) DrawRemoveMessage(listOf(current.id)) else null
        }
        pushUndoSnapshot()
        items = items + current
        return DrawUpsertMessage(current)
    }

    /** Abandons [active], e.g. when a second finger starts a pinch. */
    fun cancel(): DisplayMessage? {
        val current = active ?: return null
        val wasSent = activeSent
        clearActive()
        return if (wasSent) DrawRemoveMessage(listOf(current.id)) else null
    }

    private fun clearActive() {
        active = null
        activeSent = false
        sentPoints = 0
        activeDirty = false
    }

    /**
     * A pen line is always visible -- a tap leaves a dot. A shape dragged
     * less than a pixel either way is a tap that meant nothing.
     */
    private fun isVisible(d: Drawing): Boolean = when (d.kind) {
        DrawKind.PEN -> d.pts.size >= 2
        DrawKind.ARROW -> hypot(d.pts[2] - d.pts[0], d.pts[3] - d.pts[1]) >= MIN_SHAPE_PX
        DrawKind.RECT, DrawKind.OVAL ->
            abs(d.pts[2] - d.pts[0]) >= MIN_SHAPE_PX && abs(d.pts[3] - d.pts[1]) >= MIN_SHAPE_PX
    }

    // --- erasing --------------------------------------------------------

    /** Set once an erase gesture has taken its undo snapshot. */
    private var eraseSnapshotTaken = false

    /** Opens an erase gesture. Everything it removes is one undo step. */
    fun beginErase() {
        eraseSnapshotTaken = false
    }

    /**
     * Removes every drawing within [radius] map pixels of ([x], [y]). Returns
     * the removal to send, or null when the eraser touched nothing.
     */
    fun eraseAt(x: Float, y: Float, radius: Float): DisplayMessage? {
        val hit = items.filter { it.isHit(x, y, radius) }
        if (hit.isEmpty()) return null
        if (!eraseSnapshotTaken) {
            pushUndoSnapshot()
            eraseSnapshotTaken = true
        }
        val ids = hit.map { it.id }.toSet()
        items = items.filterNot { it.id in ids }
        return DrawRemoveMessage(ids.toList())
    }

    /** True if the gesture just finished removed anything, i.e. made an undo step. */
    fun endErase(): Boolean {
        val erased = eraseSnapshotTaken
        eraseSnapshotTaken = false
        return erased
    }

    /** Removes every drawing, undoably. False if there was nothing to clear. */
    fun clear(): Boolean {
        if (items.isEmpty()) return false
        pushUndoSnapshot()
        items = emptyList()
        return true
    }

    // --- history --------------------------------------------------------

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(items)
        items = previous
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(items)
        items = next
        return true
    }

    /**
     * Drops the redo branch. Called when the *fog* is edited too: undo and
     * redo walk one shared timeline across both layers, and a new edit of
     * either kind ends the old future.
     */
    fun clearRedo() {
        redoStack.clear()
    }

    private fun pushUndoSnapshot() {
        undoStack.addLast(items)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
    }

    private companion object {
        const val MAX_HISTORY = 50

        /** In map pixels. Below this a shape is a tap, not a drawing. */
        const val MIN_SHAPE_PX = 1f
    }
}
