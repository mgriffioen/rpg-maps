package com.rpgmaps.tabletop.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rpgmaps.tabletop.RpgMapsApplication
import com.rpgmaps.tabletop.data.db.MapEntity
import com.rpgmaps.tabletop.display.protocol.BlankMessage
import com.rpgmaps.tabletop.display.protocol.FogOp
import com.rpgmaps.tabletop.display.protocol.FogShape
import com.rpgmaps.tabletop.display.protocol.GridMessage
import com.rpgmaps.tabletop.display.protocol.MapAnnounced
import com.rpgmaps.tabletop.display.protocol.RotationMessage
import com.rpgmaps.tabletop.display.protocol.ViewportMessage
import com.rpgmaps.tabletop.fog.FogEditor
import com.rpgmaps.tabletop.fog.FogMask
import com.rpgmaps.tabletop.ui.library.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

/** What a one-finger drag on the map does. Two fingers always pan and zoom. */
enum class MapTool { PAN, REVEAL, HIDE }

/** A view of the map, in map pixels. Matches the wire viewport exactly. */
data class ViewState(val cx: Float, val cy: Float, val halfH: Float)

/** An unsaved grid, previewed on the DM canvas while a dialog is open. */
data class GridPreview(val pxPerSquare: Float, val offsetX: Float, val offsetY: Float)

class MapViewModel(
    private val app: RpgMapsApplication,
    private val mapId: String,
) : ViewModel() {

    val map: StateFlow<MapEntity?> = app.repository.observeMap(mapId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val displayStatuses = app.displayHub.statuses

    // --- pixels -----------------------------------------------------------

    var mapImage by mutableStateOf<ImageBitmap?>(null)
        private set

    var fogImage by mutableStateOf<ImageBitmap?>(null)
        private set

    /**
     * Bumped whenever the fog bitmap is drawn into. Reading it inside a draw
     * scope is what makes Compose repaint; the bitmap itself is mutated in
     * place and never swapped.
     */
    var fogVersion by mutableIntStateOf(0)
        private set

    private var editor: FogEditor? = null

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // --- DM viewport ------------------------------------------------------

    var view by mutableStateOf(ViewState(0f, 0f, 500f))
        private set

    /** DM canvas size in pixels, reported by the map canvas as it lays out. */
    private var canvasW = 1f
    private var canvasH = 1f

    // --- tools ------------------------------------------------------------

    var tool by mutableStateOf(MapTool.PAN)

    // Private setters so every change goes through updateBrush*, which also
    // persists the value. A bare assignment would silently not be remembered.
    var brushRadiusMapPx by mutableStateOf(90f)
        private set
    var brushSoftness by mutableStateOf(0.35f)
        private set

    /**
     * What a drag paints with: freehand, or a dragged-out rectangle/ellipse.
     * Independent of [tool] -- each shape can either reveal or hide.
     */
    var fogShape by mutableStateOf(FogShape.BRUSH)
        private set

    // update*, not set*: `setFogShape(FogShape)` is the JVM signature Kotlin
    // already generates for the property, and declaring both is a platform
    // declaration clash. tools/check-declaration-clashes.sh catches this.
    fun updateFogShape(shape: FogShape) {
        if (shapeStart != null) cancelShape()
        fogShape = shape
    }

    /** Corners of the shape being dragged out, in map pixels. Null when idle. */
    var shapeStart by mutableStateOf<Pair<Float, Float>?>(null)
        private set
    var shapeEnd by mutableStateOf<Pair<Float, Float>?>(null)
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    // --- what the players see --------------------------------------------

    /**
     * When true the TV holds its current framing while the DM moves around
     * freely. Being able to check a corridor three rooms ahead without
     * dragging the players' view along is the single most requested thing in
     * every VTT, so it is here from the start.
     */
    var tvFrozen by mutableStateOf(false)
        private set

    /** The region the TV is showing, for the outline drawn on the DM canvas. */
    var tvView by mutableStateOf<ViewState?>(null)
        private set

    var blanked by mutableStateOf(false)
        private set

    var gridEnabled by mutableStateOf(false)
        private set

    /**
     * Two independent quarter-turns, because they answer different questions.
     *
     * [tvRotationQuarters] is how the TV is standing. Turning it makes a
     * landscape signal fill a screen stood on its end. It moves the player view
     * only -- the tablet is in your hands and you orient that by turning it.
     *
     * [mapRotationQuarters] is which way the map artwork should face. That
     * belongs to the map rather than to either screen, so it turns **both**
     * together.
     *
     * The receiver only ever needs the sum, so the wire protocol still carries
     * a single value: see [playerTotalQuarters].
     */
    var tvRotationQuarters by mutableStateOf(0)
        private set

    var mapRotationQuarters by mutableStateOf(0)
        private set

    /** What the player view draws at: both turns composed. */
    val playerTotalQuarters: Int
        get() = (mapRotationQuarters + tvRotationQuarters).mod(4)

    /**
     * Turns the TV. Deliberately leaves the viewport alone: this canvas is not
     * rotating, so its framing must not move. The receiver re-frames itself
     * around whichever of its own axes is vertical after the turn, which is
     * precisely what fills a screen standing on its end.
     */
    fun rotateTv(delta: Int) {
        tvRotationQuarters = (tvRotationQuarters + delta).mod(4)
        app.displayHub.setRotation(RotationMessage(playerTotalQuarters))
        viewModelScope.launch { app.settings.setRotationQuarters(tvRotationQuarters) }
    }

    /**
     * Turns the map on both screens. Unlike [rotateTv] this one *does* move
     * this canvas, so the framing has to be rescaled: a quarter turn swaps
     * which screen axis halfH is measured against, and leaving it alone would
     * silently change the zoom.
     */
    fun rotateMap(delta: Int) {
        val extentBefore = framingExtent()
        mapRotationQuarters = (mapRotationQuarters + delta).mod(4)
        val extentAfter = framingExtent()
        if (extentBefore > 0f && extentAfter > 0f) {
            applyView(view.copy(halfH = view.halfH * (extentAfter / extentBefore)))
        }
        app.displayHub.setRotation(RotationMessage(playerTotalQuarters))
        viewModelScope.launch { app.settings.setMapRotationQuarters(mapRotationQuarters) }
    }

    // --- calibration ------------------------------------------------------

    /**
     * While true, a one-finger drag measures a distance instead of painting.
     * Typing a pixel count is not something anyone can do by eye, so
     * calibration works by dragging across a square the DM can actually see.
     */
    var calibrating by mutableStateOf(false)
        private set

    /** Both ends of the measuring line, in map pixels. */
    var measureStart by mutableStateOf<Pair<Float, Float>?>(null)
        private set
    var measureEnd by mutableStateOf<Pair<Float, Float>?>(null)
        private set

    /**
     * Candidate grid drawn on the DM canvas while the calibration or alignment
     * dialog is open, so the offset sliders can be judged against the map art
     * instead of adjusted blind.
     */
    var gridPreview by mutableStateOf<GridPreview?>(null)
        private set

    fun previewGrid(pxPerSquare: Float, offsetX: Float, offsetY: Float) {
        gridPreview = if (pxPerSquare > 1f) GridPreview(pxPerSquare, offsetX, offsetY) else null
    }

    fun clearGridPreview() {
        gridPreview = null
    }

    val measuredMapPx: Float
        get() {
            val a = measureStart ?: return 0f
            val b = measureEnd ?: return 0f
            return hypot(b.first - a.first, b.second - a.second)
        }

    fun startCalibration() {
        calibrating = true
        measureStart = null
        measureEnd = null
        tool = MapTool.PAN
    }

    fun cancelCalibration() {
        calibrating = false
        measureStart = null
        measureEnd = null
        gridPreview = null
    }

    fun beginMeasure(screenX: Float, screenY: Float) {
        measureStart = screenToMap(screenX, screenY)
        measureEnd = measureStart
    }

    fun updateMeasure(screenX: Float, screenY: Float) {
        measureEnd = screenToMap(screenX, screenY)
    }

    private var strokeFlushJob: Job? = null
    private var fogSaveJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _loading.value = true
        try {
            val entity = app.repository.getMap(mapId)
            if (entity == null) {
                _error.value = "That map is no longer in the library."
                return
            }

            val settings = app.settings.flow.first()
            brushRadiusMapPx = settings.brushRadiusMapPx
            brushSoftness = settings.brushSoftness

            val bitmap = app.repository.loadDisplayBitmap(mapId)
            if (bitmap == null) {
                _error.value = "The image file for this map is missing or unreadable."
                return
            }
            mapImage = bitmap.asImageBitmap()

            val fogPng = app.repository.readFogPng(mapId)
            val mask = FogMask.fromPngOrHidden(fogPng, entity.fogW, entity.fogH)
            editor = FogEditor(mask)
            fogImage = mask.bitmap.asImageBitmap()

            gridEnabled = entity.gridEnabled
            tvRotationQuarters = settings.rotationQuarters
            mapRotationQuarters = settings.mapRotationQuarters
            view = fitViewFor(entity, dmAspect())

            app.repository.markOpened(mapId)
            present(entity)
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not open this map."
        } catch (e: OutOfMemoryError) {
            _error.value = "Not enough memory to open this map."
        } finally {
            _loading.value = false
        }
    }

    /** Hands the map to every display sink and starts mirroring state. */
    private suspend fun present(entity: MapEntity) {
        val bytes = app.repository.readDisplayBytes(mapId) ?: return
        val currentEditor = editor ?: return

        // Seed the hub with this map's framing first: presentMap pushes full
        // state asynchronously and will include whatever viewport is set.
        app.displayHub.setRotation(RotationMessage(playerTotalQuarters))
        pushGrid(entity)
        pushViewport(force = true)

        app.displayHub.presentMap(
            announcement = MapAnnounced(
                mapId = entity.id,
                name = entity.name,
                imageW = entity.imageW,
                imageH = entity.imageH,
                fogW = entity.fogW,
                fogH = entity.fogH,
                imageRevision = 0, // the hub assigns the real revision
            ),
            image = bytes,
            fogSeq = currentEditor.revision,
            // Encoding happens on the main thread on purpose: the mask is
            // mutated from there too, and a one-off frame hitch on connect is
            // a better trade than a torn snapshot mid-stroke.
            fogSnapshotProvider = {
                withContext(Dispatchers.Main) { editor?.mask?.toPng() }
            },
        )
    }

    // --- canvas plumbing --------------------------------------------------

    fun onCanvasSized(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val first = canvasW == 1f && canvasH == 1f
        canvasW = width
        canvasH = height
        if (first) map.value?.let { view = fitViewFor(it, dmAspect()) }
    }

    /**
     * The screen axis that [ViewState.halfH] governs on *this* canvas. The map
     * turn rotates this view, so under a quarter turn the map's vertical runs
     * across the screen and the framing is set by the canvas width.
     */
    private fun framingExtent(): Float =
        if (mapRotationQuarters % 2 == 0) canvasH else canvasW

    /** This canvas's aspect as the map sees it, i.e. after the map turn. */
    private fun dmAspect(): Float {
        val aspect =
            if (mapRotationQuarters % 2 == 0) canvasW / canvasH else canvasH / canvasW
        return if (aspect.isFinite() && aspect > 0f) aspect else 16f / 9f
    }

    /** Screen pixels per map pixel at the current zoom. */
    fun scale(): Float = framingExtent() / (2f * view.halfH.coerceAtLeast(1f))

    /**
     * Undoes the map turn on a screen-space delta, turning a finger movement
     * back into a movement across the map. Written as quarter-turn cases
     * rather than trigonometry so it stays exact.
     */
    private fun unrotate(dx: Float, dy: Float): Pair<Float, Float> = when (mapRotationQuarters) {
        1 -> dy to -dx
        2 -> -dx to -dy
        3 -> -dy to dx
        else -> dx to dy
    }

    fun screenToMap(x: Float, y: Float): Pair<Float, Float> {
        val s = scale()
        val (mx, my) = unrotate((x - canvasW / 2f) / s, (y - canvasH / 2f) / s)
        return (view.cx + mx) to (view.cy + my)
    }

    // --- viewport ---------------------------------------------------------

    fun pan(dxScreen: Float, dyScreen: Float) {
        val s = scale()
        val (mdx, mdy) = unrotate(dxScreen / s, dyScreen / s)
        applyView(view.copy(cx = view.cx - mdx, cy = view.cy - mdy))
    }

    /** Pinch zoom about a screen-space focal point, so the map stays put under the fingers. */
    fun zoomAround(factor: Float, focusX: Float, focusY: Float) {
        if (factor <= 0f || !factor.isFinite()) return
        val (mx, my) = screenToMap(focusX, focusY)
        val newHalfH = clampHalfH(view.halfH / factor)
        val s = framingExtent() / (2f * newHalfH)
        val (ox, oy) = unrotate((focusX - canvasW / 2f) / s, (focusY - canvasH / 2f) / s)
        applyView(ViewState(cx = mx - ox, cy = my - oy, halfH = newHalfH))
    }

    /**
     * The single place the viewport changes: clamps, stores, and mirrors to the
     * TV unless it is frozen.
     *
     * Not called `setView` -- that is the JVM signature Kotlin already
     * generates for the `view` property's (private) setter, and declaring both
     * is a platform declaration clash.
     */
    private fun applyView(next: ViewState) {
        val entity = map.value ?: return
        view = ViewState(
            cx = next.cx.coerceIn(0f, entity.imageW.toFloat()),
            cy = next.cy.coerceIn(0f, entity.imageH.toFloat()),
            halfH = clampHalfH(next.halfH),
        )
        if (!tvFrozen) pushViewport()
    }

    private fun clampHalfH(value: Float): Float {
        val entity = map.value ?: return value
        val max = entity.imageH.toFloat()
        return value.coerceIn(MIN_HALF_H, max)
    }

    fun fitToScreen() {
        val entity = map.value ?: return
        applyView(fitViewFor(entity, dmAspect()))
    }

    private fun fitViewFor(entity: MapEntity, aspect: Float): ViewState {
        val safeAspect = if (aspect.isFinite() && aspect > 0f) aspect else 16f / 9f
        // Vertical half-extent that also fits the full width at this aspect.
        val halfH = maxOf(entity.imageH / 2f, (entity.imageW / 2f) / safeAspect)
        return ViewState(entity.imageW / 2f, entity.imageH / 2f, halfH * FIT_MARGIN)
    }

    fun toggleFreezeTv() {
        tvFrozen = !tvFrozen
        if (!tvFrozen) pushViewport()
    }

    /** Sends the DM's current framing to the TV without unfreezing. */
    fun pushViewToTv() {
        pushViewport(force = true)
    }

    private fun pushViewport(force: Boolean = false) {
        if (tvFrozen && !force) return
        val current = view
        tvView = current
        app.displayHub.setViewport(ViewportMessage(current.cx, current.cy, current.halfH))
    }

    /**
     * Frames the map so one battle square renders at one real inch on the TV,
     * which is what makes a 28 mm miniature stand correctly on its square.
     *
     * Needs three things: the map's calibration, the TV's pixel dimensions
     * (which the receiver reports on connect) and its physical diagonal (which
     * the DM enters in settings).
     */
    suspend fun scaleToLife(): String {
        val entity = map.value ?: return "No map loaded."
        if (entity.pxPerSquare <= 0f) {
            return "Calibrate this map first so it knows how big a square is."
        }
        val status = displayStatuses.value.firstOrNull { it.receiverW > 0 && it.receiverH > 0 }
            ?: return "No player display connected yet."

        val diagonalInches = app.settings.flow.first().tvDiagonalInches
        if (diagonalInches <= 0f) return "Set the TV size in display settings first."

        val diagonalPx = hypot(status.receiverW.toFloat(), status.receiverH.toFloat())
        val tvPpi = diagonalPx / diagonalInches
        // One square must occupy one inch, i.e. tvPpi screen pixels.
        val screenPxPerMapPx = tvPpi / entity.pxPerSquare
        // Under a quarter turn the receiver frames on its width, so that is the
        // extent halfH has to be measured against. The diagonal above is
        // rotation-invariant, so only this line needs to know.
        val framingPx = if (playerTotalQuarters % 2 == 0) {
            status.receiverH.toFloat()
        } else {
            status.receiverW.toFloat()
        }
        val halfH = (framingPx / 2f) / screenPxPerMapPx

        val wasFrozen = tvFrozen
        tvFrozen = false
        applyView(view.copy(halfH = clampHalfH(halfH)))
        tvFrozen = wasFrozen
        pushViewport(force = true)

        return "Scaled to life on a %.0f\" screen".format(diagonalInches)
    }

    // --- fog --------------------------------------------------------------

    fun startStroke(screenX: Float, screenY: Float) {
        val entity = map.value ?: return
        val currentEditor = editor ?: return
        if (tool == MapTool.PAN) return

        val (fx, fy) = mapToFog(entity, screenX, screenY)
        currentEditor.startStroke(
            reveal = tool == MapTool.REVEAL,
            radius = brushRadiusFog(entity),
            softness = brushSoftness,
            x = fx,
            y = fy,
        )
        startFlushing()
    }

    fun extendStroke(screenX: Float, screenY: Float) {
        val entity = map.value ?: return
        val currentEditor = editor ?: return
        val (fx, fy) = mapToFog(entity, screenX, screenY)
        currentEditor.addPoint(fx, fy)
    }

    fun endStroke() {
        strokeFlushJob?.cancel()
        strokeFlushJob = null
        val currentEditor = editor ?: return
        currentEditor.endStroke()?.let { op ->
            app.displayHub.sendFogOps(currentEditor.revision, listOf(op))
            fogVersion++
        }
        refreshHistoryFlags()
        scheduleFogSave()
    }

    /**
     * Draws and broadcasts buffered points on a timer while a stroke is live.
     * The same op object is applied here and sent over the wire, so the
     * tablet and the TV cannot drift apart.
     */
    private fun startFlushing() {
        strokeFlushJob?.cancel()
        strokeFlushJob = viewModelScope.launch {
            while (isActive) {
                val currentEditor = editor ?: break
                currentEditor.flush()?.let { op ->
                    app.displayHub.sendFogOps(currentEditor.revision, listOf(op))
                    fogVersion++
                }
                delay(STROKE_FLUSH_MS)
            }
        }
    }

    private fun brushRadiusFog(entity: MapEntity): Float =
        (brushRadiusMapPx * entity.fogW / entity.imageW.toFloat()).coerceAtLeast(1f)

    private fun mapToFog(entity: MapEntity, screenX: Float, screenY: Float): Pair<Float, Float> {
        val (mx, my) = screenToMap(screenX, screenY)
        return (mx * entity.fogW / entity.imageW) to (my * entity.fogH / entity.imageH)
    }

    // --- shapes -----------------------------------------------------------

    fun beginShape(screenX: Float, screenY: Float) {
        if (tool == MapTool.PAN || fogShape == FogShape.BRUSH) return
        shapeStart = screenToMap(screenX, screenY)
        shapeEnd = shapeStart
    }

    fun updateShape(screenX: Float, screenY: Float) {
        if (shapeStart == null) return
        shapeEnd = screenToMap(screenX, screenY)
    }

    /** Abandons the in-progress shape, e.g. when a second finger starts a pinch. */
    fun cancelShape() {
        shapeStart = null
        shapeEnd = null
    }

    /** Commits the dragged shape as one undoable op and mirrors it to the TV. */
    fun endShape() {
        val entity = map.value
        val currentEditor = editor
        val start = shapeStart
        val end = shapeEnd
        cancelShape()
        if (entity == null || currentEditor == null || start == null || end == null) return
        if (fogShape == FogShape.BRUSH || tool == MapTool.PAN) return

        val (x0, y0) = mapToFogPoint(entity, start)
        val (x1, y1) = mapToFogPoint(entity, end)
        // A tap rather than a drag: nothing to fill.
        if (abs(x1 - x0) < 1f || abs(y1 - y0) < 1f) return

        val op = FogOp(
            reveal = tool == MapTool.REVEAL,
            radius = 0f,
            softness = brushSoftness,
            pts = listOf(x0, y0, x1, y1),
            shape = fogShape,
        )
        val seq = currentEditor.applyOnce(op)
        fogVersion++
        app.displayHub.sendFogOps(seq, listOf(op))
        refreshHistoryFlags()
        scheduleFogSave()
    }

    private fun mapToFogPoint(entity: MapEntity, point: Pair<Float, Float>): Pair<Float, Float> =
        (point.first * entity.fogW / entity.imageW) to (point.second * entity.fogH / entity.imageH)

    fun revealAll() = fill(fogged = false)

    fun hideAll() = fill(fogged = true)

    private fun fill(fogged: Boolean) {
        val currentEditor = editor ?: return
        val seq = currentEditor.fill(fogged)
        fogVersion++
        app.displayHub.sendFogFill(seq, fogged)
        refreshHistoryFlags()
        scheduleFogSave()
    }

    fun undo() {
        val currentEditor = editor ?: return
        val seq = currentEditor.undo() ?: return
        fogVersion++
        // Undo cannot be expressed as an op, so the whole mask goes over.
        app.displayHub.sendFogSnapshot(seq, currentEditor.mask.toPng())
        refreshHistoryFlags()
        scheduleFogSave()
    }

    fun redo() {
        val currentEditor = editor ?: return
        val seq = currentEditor.redo() ?: return
        fogVersion++
        app.displayHub.sendFogSnapshot(seq, currentEditor.mask.toPng())
        refreshHistoryFlags()
        scheduleFogSave()
    }

    private fun refreshHistoryFlags() {
        canUndo = editor?.canUndo == true
        canRedo = editor?.canRedo == true
    }

    /** Coalesces rapid edits into one write instead of hammering flash. */
    private fun scheduleFogSave() {
        fogSaveJob?.cancel()
        fogSaveJob = viewModelScope.launch {
            delay(FOG_SAVE_DEBOUNCE_MS)
            saveFogNow()
        }
    }

    suspend fun saveFogNow() {
        val png = withContext(Dispatchers.Main) { editor?.mask?.toPng() } ?: return
        app.repository.writeFogPng(mapId, png)
    }

    // --- grid, calibration, curtain ---------------------------------------

    fun toggleGrid() {
        val entity = map.value ?: return
        gridEnabled = !gridEnabled
        viewModelScope.launch {
            app.repository.updateMap(entity.copy(gridEnabled = gridEnabled))
            pushGrid(entity.copy(gridEnabled = gridEnabled))
        }
    }

    /**
     * Records how big a battle square is by having the DM drag across one
     * known square. [distanceMapPx] is that drag length; [squares] lets them
     * span several squares for better precision.
     */
    fun calibrate(distanceMapPx: Float, squares: Float, offsetX: Float, offsetY: Float) {
        val entity = map.value ?: return
        if (distanceMapPx <= 1f || squares <= 0f) return
        val perSquare = distanceMapPx / squares
        viewModelScope.launch {
            val updated = entity.copy(
                pxPerSquare = perSquare,
                gridOffsetX = offsetX,
                gridOffsetY = offsetY,
                gridEnabled = true,
            )
            app.repository.updateMap(updated)
            gridEnabled = true
            pushGrid(updated)
            cancelCalibration()
        }
    }

    /** Nudges the grid so its lines land on the ones drawn into the map art. */
    fun setGridOffset(offsetX: Float, offsetY: Float) {
        val entity = map.value ?: return
        viewModelScope.launch {
            val updated = entity.copy(gridOffsetX = offsetX, gridOffsetY = offsetY)
            app.repository.updateMap(updated)
            pushGrid(updated)
        }
    }

    private fun pushGrid(entity: MapEntity) {
        app.displayHub.setGrid(
            GridMessage(
                enabled = entity.gridEnabled && entity.pxPerSquare > 1f,
                pxPerSquare = entity.pxPerSquare,
                offsetX = entity.gridOffsetX,
                offsetY = entity.gridOffsetY,
            )
        )
    }

    fun toggleBlank() {
        blanked = !blanked
        viewModelScope.launch {
            val text = app.settings.flow.first().blankText
            app.displayHub.setBlank(BlankMessage(blanked, text))
        }
    }

    // Named update* rather than set*: `setBrushSoftness(Float)` is the JVM
    // signature Kotlin generates for the property itself, so declaring both
    // would be a platform declaration clash.

    fun updateBrushRadius(value: Float) {
        brushRadiusMapPx = value
        viewModelScope.launch { app.settings.setBrushRadiusMapPx(value) }
    }

    fun updateBrushSoftness(value: Float) {
        brushSoftness = value
        viewModelScope.launch { app.settings.setBrushSoftness(value) }
    }

    fun consumeError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        strokeFlushJob?.cancel()
        fogSaveJob?.cancel()
        // Best effort: the scope is already cancelled, so use the app scope.
        val currentEditor = editor
        if (currentEditor != null) {
            val png = currentEditor.mask.toPng()
            app.appScope.launch { app.repository.writeFogPng(mapId, png) }
        }
    }

    companion object {
        /** Deepest zoom, in map pixels of visible half-height. */
        private const val MIN_HALF_H = 40f

        /** A little air around the map when fitting it to the screen. */
        private const val FIT_MARGIN = 1.04f

        /** 25 Hz: smooth to watch, and well within what a Chromecast absorbs. */
        private const val STROKE_FLUSH_MS = 40L

        private const val FOG_SAVE_DEBOUNCE_MS = 1_200L

        fun factory(mapId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { MapViewModel(application(this), mapId) }
        }
    }
}
