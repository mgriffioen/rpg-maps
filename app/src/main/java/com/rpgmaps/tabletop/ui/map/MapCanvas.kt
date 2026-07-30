package com.rpgmaps.tabletop.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The DM's view of the map.
 *
 * Draws the same scene the players get, except fog is translucent here -- the
 * DM needs to see what is behind it in order to decide what to reveal, while
 * the TV renders the identical mask fully opaque.
 */
@Composable
fun MapCanvas(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier,
) {
    val mapImage = viewModel.mapImage
    val fogImage = viewModel.fogImage
    val entity by viewModel.map.collectAsState()
    val statuses by viewModel.displayStatuses.collectAsState()

    // The TV's aspect ratio, so the outline we draw matches what it really
    // shows. 16:9 until a receiver tells us otherwise.
    val tvAspect = statuses.firstOrNull { it.receiverW > 0 && it.receiverH > 0 }
        ?.let { it.receiverW.toFloat() / it.receiverH.toFloat() }
        ?: (16f / 9f)

    Box(
        modifier = modifier
            .background(Color(0xFF05070C))
            .onSizeChanged { viewModel.onCanvasSized(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(viewModel.tool) { handleMapGestures(viewModel) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Read so Compose repaints when the mask is drawn into. The bitmap
            // is mutated in place, so nothing else would signal a change.
            @Suppress("UNUSED_EXPRESSION")
            viewModel.fogVersion

            val map = entity ?: return@Canvas
            val image = mapImage ?: return@Canvas
            val view = viewModel.view

            val scale = size.height / (2f * view.halfH.coerceAtLeast(1f))
            val left = size.width / 2f - view.cx * scale
            val top = size.height / 2f - view.cy * scale

            withTransform({
                translate(left, top)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(map.imageW, map.imageH),
                )

                if (viewModel.gridEnabled && map.pxPerSquare > 1f) {
                    drawGrid(
                        pxPerSquare = map.pxPerSquare,
                        offsetX = map.gridOffsetX,
                        offsetY = map.gridOffsetY,
                        width = map.imageW.toFloat(),
                        height = map.imageH.toFloat(),
                        strokeWidth = 1.2f / scale,
                    )
                }

                if (fogImage != null) {
                    drawImage(
                        image = fogImage,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(map.imageW, map.imageH),
                        alpha = DM_FOG_ALPHA,
                        colorFilter = ColorFilter.tint(FOG_TINT, BlendMode.SrcIn),
                    )
                }

                viewModel.tvView?.let { tv ->
                    drawTvOutline(tv, tvAspect, 2.5f / scale, viewModel.tvFrozen)
                }

                if (viewModel.calibrating) {
                    drawMeasureLine(
                        start = viewModel.measureStart,
                        end = viewModel.measureEnd,
                        strokeWidth = 2.5f / scale,
                        handleRadius = 7f / scale,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawGrid(
    pxPerSquare: Float,
    offsetX: Float,
    offsetY: Float,
    width: Float,
    height: Float,
    strokeWidth: Float,
) {
    if (pxPerSquare <= 1f) return
    val color = Color.White.copy(alpha = 0.30f)

    var x = offsetX % pxPerSquare
    while (x <= width) {
        drawLine(color, Offset(x, 0f), Offset(x, height), strokeWidth)
        x += pxPerSquare
    }
    var y = offsetY % pxPerSquare
    while (y <= height) {
        drawLine(color, Offset(0f, y), Offset(width, y), strokeWidth)
        y += pxPerSquare
    }
}

/** Outlines the region the players are currently looking at. */
private fun DrawScope.drawTvOutline(
    tv: ViewState,
    tvAspect: Float,
    strokeWidth: Float,
    frozen: Boolean,
) {
    val halfW = tv.halfH * tvAspect
    drawRect(
        color = if (frozen) Color(0xFFD5A45B) else Color(0xFF9EC5FF),
        topLeft = Offset(tv.cx - halfW, tv.cy - tv.halfH),
        size = Size(halfW * 2f, tv.halfH * 2f),
        style = Stroke(width = strokeWidth),
        alpha = if (frozen) 0.95f else 0.45f,
    )
}

/** The calibration ruler the DM drags across a known square. */
private fun DrawScope.drawMeasureLine(
    start: Pair<Float, Float>?,
    end: Pair<Float, Float>?,
    strokeWidth: Float,
    handleRadius: Float,
) {
    if (start == null || end == null) return
    val a = Offset(start.first, start.second)
    val b = Offset(end.first, end.second)
    val color = Color(0xFFFFD479)
    drawLine(color, a, b, strokeWidth)
    drawCircle(color, handleRadius, a)
    drawCircle(color, handleRadius, b)
}

/**
 * One finger paints (or pans, in pan mode); two fingers always pan and zoom.
 *
 * Keeping the pinch gesture available in every mode matters more than it
 * sounds: revealing a corridor means constantly zooming in to brush accurately
 * and back out to see the room, and a DM should never have to change tools to
 * do that.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.handleMapGestures(
    viewModel: MapViewModel,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var painting = false
        var measuring = false
        var usedMultiTouch = false

        if (viewModel.calibrating) {
            measuring = true
            viewModel.beginMeasure(down.position.x, down.position.y)
            down.consume()
        } else if (viewModel.tool != MapTool.PAN) {
            painting = true
            viewModel.startStroke(down.position.x, down.position.y)
            down.consume()
        }

        while (true) {
            val event = awaitPointerEvent()
            val pressed: List<PointerInputChange> = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                // A second finger converts a brush stroke into a transform.
                if (painting) {
                    viewModel.endStroke()
                    painting = false
                }
                measuring = false
                usedMultiTouch = true

                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid()
                if (zoom != 1f && centroid != Offset.Unspecified) {
                    viewModel.zoomAround(zoom, centroid.x, centroid.y)
                }
                if (pan != Offset.Zero) viewModel.pan(pan.x, pan.y)
                event.changes.forEach { it.consume() }
            } else {
                val change = pressed.first()
                when {
                    measuring -> {
                        viewModel.updateMeasure(change.position.x, change.position.y)
                        change.consume()
                    }
                    painting -> {
                        viewModel.extendStroke(change.position.x, change.position.y)
                        change.consume()
                    }
                    // In pan mode a single finger always pans. In a brush mode
                    // it must not resume panning after a pinch -- lifting one
                    // finger would otherwise drag the map unexpectedly.
                    viewModel.tool == MapTool.PAN || !usedMultiTouch -> {
                        val delta = change.positionChange()
                        viewModel.pan(delta.x, delta.y)
                        change.consume()
                    }
                }
            }
        }

        if (painting) viewModel.endStroke()
    }
}

/** Enough to read the terrain through, dark enough to read as "hidden". */
private const val DM_FOG_ALPHA = 0.72f
private val FOG_TINT = Color(0xFF16233A)
