package com.rpgmaps.tabletop.fog

import com.rpgmaps.tabletop.display.protocol.FogOp

/**
 * Editing session on top of a [FogMask]: brush strokes, undo/redo, and the
 * revision counter that receivers use to notice they have fallen behind.
 *
 * Strokes are accumulated and flushed in short batches rather than applied per
 * touch event. That matters for more than performance: the local mask and every
 * receiver's mask must end up pixel-identical, so both sides apply the *same*
 * polyline ops. Flushing a batch produces one [FogOp] that is drawn here and
 * streamed verbatim.
 *
 * Not thread-safe -- drive it from a single coroutine or the main thread.
 */
class FogEditor(val mask: FogMask) {

    /**
     * Bumped on every committed change. A receiver that sees a gap between its
     * own sequence and an incoming one asks for a full resync.
     */
    var revision: Long = 0L
        private set

    private val undoStack = ArrayDeque<ByteArray>()
    private val redoStack = ArrayDeque<ByteArray>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // --- Current stroke -------------------------------------------------

    private var strokeReveal = true
    private var strokeRadius = 0f
    private var strokeSoftness = 0f

    /** Points not yet drawn, preceded by the last point that was. */
    private val pending = ArrayList<Float>()
    private var strokeActive = false
    private var firstFlushOfStroke = true

    /**
     * Opens a stroke at ([x], [y]) in mask pixels. Takes the undo snapshot, so
     * one undo removes one finger-down-to-finger-up gesture.
     */
    fun startStroke(reveal: Boolean, radius: Float, softness: Float, x: Float, y: Float) {
        if (strokeActive) endStroke()
        pushUndoSnapshot()
        strokeReveal = reveal
        strokeRadius = radius
        strokeSoftness = softness
        pending.clear()
        pending.add(x)
        pending.add(y)
        strokeActive = true
    }

    /** Adds a point to the open stroke. Nothing is drawn until [flush]. */
    fun addPoint(x: Float, y: Float) {
        if (!strokeActive) return
        val n = pending.size
        // Touch streams repeat coordinates when a finger rests; skip those so
        // soft-edged brushes do not erode the same spot over and over.
        if (n >= 2 && pending[n - 2] == x && pending[n - 1] == y) return
        pending.add(x)
        pending.add(y)
    }

    /**
     * Draws everything buffered so far and returns the op to broadcast, or
     * null if there is nothing new. The last point is retained so the next
     * batch joins seamlessly onto this one.
     */
    fun flush(): FogOp? {
        if (!strokeActive || pending.size < 2) return null
        // On the first flush a lone point is a real op -- a tap leaves a dot.
        // Later, a lone point is just the retained join point, so nothing new.
        if (pending.size == 2 && !firstFlushOfStroke) return null

        val op = FogOp(
            reveal = strokeReveal,
            radius = strokeRadius,
            softness = strokeSoftness,
            pts = ArrayList(pending),
        )
        mask.apply(op)
        revision++
        firstFlushOfStroke = false

        val lastX = pending[pending.size - 2]
        val lastY = pending[pending.size - 1]
        pending.clear()
        pending.add(lastX)
        pending.add(lastY)
        return op
    }

    /** Flushes any remainder and closes the stroke. */
    fun endStroke(): FogOp? {
        if (!strokeActive) return null
        val op = flush()
        strokeActive = false
        firstFlushOfStroke = true
        pending.clear()
        return op
    }

    // --- Bulk operations ------------------------------------------------

    /** Hides or reveals everything. Undoable. */
    fun fill(fogged: Boolean): Long {
        pushUndoSnapshot()
        mask.fill(fogged)
        revision++
        return revision
    }

    // --- History --------------------------------------------------------

    /** Returns the new revision, or null if there was nothing to undo. */
    fun undo(): Long? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(mask.toPng())
        trim(redoStack)
        mask.restoreFrom(previous)
        revision++
        return revision
    }

    /** Returns the new revision, or null if there was nothing to redo. */
    fun redo(): Long? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(mask.toPng())
        trim(undoStack)
        mask.restoreFrom(next)
        revision++
        return revision
    }

    private fun pushUndoSnapshot() {
        undoStack.addLast(mask.toPng())
        trim(undoStack)
        // Any new edit invalidates the redo branch.
        redoStack.clear()
    }

    private fun trim(stack: ArrayDeque<ByteArray>) {
        while (stack.size > MAX_HISTORY) stack.removeFirst()
    }

    private companion object {
        /**
         * Snapshots are PNGs of a two-tone image, typically well under 100 KB,
         * so a deep history is affordable. 30 covers any plausible "undo back
         * to before that reveal" during play.
         */
        const val MAX_HISTORY = 30
    }
}
