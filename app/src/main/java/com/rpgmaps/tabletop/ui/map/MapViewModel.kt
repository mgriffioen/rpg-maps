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
import com.rpgmaps.tabletop.display.protocol.GridMessage
import com.rpgmaps.tabletop.display.protocol.MapAnnounced
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
import kotlin.math.hypot

/** What a one-finger drag on the map does. Two fingers always pan and zoom. */
enum class MapTool { PAN, REVEAL, HIDE }

/** A view of the map, in map pixels. Matches the wire viewport exactly. */
data class ViewState(val cx: Float, val cy: Float, val halfH: Float)

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
    var brushRadiusMapPx by mutableStateOf(90f)
    var brushSoftness by mutableStateOf(0.35f)

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
            view = fitViewFor(entity, canvasW / canvasH)

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
        if (first) map.value?.let { view = fitViewFor(it, width / height) }
    }

    /** Screen pixels per map pixel at the current zoom. */
    fun scale(): Float = canvasH / (2f * view.halfH.coerceAtLeast(1f))

    fun screenToMap(x: Float, y: Float): Pair<Float, Float> {
        val s = scale()
        return (view.cx + (x - canvasW / 2f) / s) to (view.cy + (y - canvasH / 2f) / s)
    }

    // --- viewport ---------------------------------------------------------

    fun pan(dxScreen: Float, dyScreen: Float) {
        val s = scale()
        setView(view.copy(cx = view.cx - dxScreen / s, cy = view.cy - dyScreen / s))
    }

    /** Pinch zoom about a screen-space focal point, so the map stays put under the fingers. */
    fun zoomAround(factor: Float, focusX: Float, focusY: Float) {
        if (factor <= 0f || !factor.isFinite()) return
        val (mx, my) = screenToMap(focusX, focusY)
        val newHalfH = clampHalfH(view.halfH / factor)
        val s = canvasH / (2f * newHalfH)
        setView(
            ViewState(
                cx = mx - (focusX - canvasW / 2f) / s,
                cy = my - (focusY - canvasH / 2f) / s,
                halfH = newHalfH,
            )
        )
    }

    private fun setView(next: ViewState) {
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
        setView(fitViewFor(entity, canvasW / canvasH))
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
        val halfH = (status.receiverH / 2f) / screenPxPerMapPx

        val wasFrozen = tvFrozen
        tvFrozen = false
        setView(view.copy(halfH = clampHalfH(halfH)))
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

    fun setBrushRadius(value: Float) {
        brushRadiusMapPx = value
        viewModelScope.launch { app.settings.setBrushRadiusMapPx(value) }
    }

    fun setBrushSoftness(value: Float) {
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
