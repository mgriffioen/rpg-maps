package com.rpgmaps.tabletop.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rpgmaps.tabletop.display.protocol.FogShape
import com.rpgmaps.tabletop.ui.display.CastButton
import com.rpgmaps.tabletop.ui.display.DisplayStatusRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    mapId: String,
    onBack: () -> Unit,
    onOpenDisplaySettings: () -> Unit,
) {
    val viewModel: MapViewModel = viewModel(factory = MapViewModel.factory(mapId))

    val entity by viewModel.map.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val statuses by viewModel.displayStatuses.collectAsState()

    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var showCalibrationDialog by remember { mutableStateOf(false) }
    var showAlignDialog by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        error?.let {
            snackbars.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                title = {
                    Column {
                        Text(entity?.name ?: "Map", style = MaterialTheme.typography.titleMedium)
                        DisplayStatusRow(statuses)
                    }
                },
                actions = {
                    CastButton(Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Fit map to screen") },
                            leadingIcon = { Icon(Icons.Default.FitScreen, null) },
                            onClick = { menuOpen = false; viewModel.fitToScreen() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (viewModel.gridEnabled) "Hide grid" else "Show grid") },
                            leadingIcon = { Icon(Icons.Default.GridOn, null) },
                            onClick = { menuOpen = false; viewModel.toggleGrid() },
                        )
                        DropdownMenuItem(
                            text = { Text("Calibrate square size") },
                            leadingIcon = { Icon(Icons.Default.Straighten, null) },
                            onClick = {
                                menuOpen = false
                                // Only arm the ruler. The dialog opens after a
                                // measurement exists -- opening it here would
                                // cover the map you have to drag across.
                                viewModel.startCalibration()
                            },
                        )
                        if ((entity?.pxPerSquare ?: 0f) > 1f) {
                            DropdownMenuItem(
                                text = { Text("Adjust grid alignment") },
                                leadingIcon = { Icon(Icons.Default.GridOn, null) },
                                onClick = { menuOpen = false; showAlignDialog = true },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Scale to life on TV") },
                            onClick = {
                                menuOpen = false
                                scope.launch { snackbars.showSnackbar(viewModel.scaleToLife()) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Display settings") },
                            onClick = { menuOpen = false; onOpenDisplaySettings() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                MapCanvas(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                MapControlBar(viewModel)
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            if (viewModel.calibrating && !showCalibrationDialog) {
                CalibrationHint(
                    measured = viewModel.measuredMapPx,
                    onDone = { showCalibrationDialog = true },
                    onCancel = { viewModel.cancelCalibration() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                )
            }
        }
    }

    if (showCalibrationDialog) {
        CalibrationDialog(
            measuredMapPx = viewModel.measuredMapPx,
            initialPxPerSquare = entity?.pxPerSquare ?: 0f,
            onPreview = viewModel::previewGrid,
            onDismiss = {
                showCalibrationDialog = false
                viewModel.cancelCalibration()
            },
            onSave = { squares, offsetX, offsetY ->
                viewModel.calibrate(viewModel.measuredMapPx, squares, offsetX, offsetY)
                viewModel.clearGridPreview()
                showCalibrationDialog = false
            },
        )
    }

    if (showAlignDialog) {
        val current = entity
        if (current == null || current.pxPerSquare <= 1f) {
            showAlignDialog = false
        } else {
            GridAlignDialog(
                pxPerSquare = current.pxPerSquare,
                initialOffsetX = current.gridOffsetX,
                initialOffsetY = current.gridOffsetY,
                onPreview = { x, y -> viewModel.previewGrid(current.pxPerSquare, x, y) },
                onDismiss = {
                    viewModel.clearGridPreview()
                    showAlignDialog = false
                },
                onSave = { x, y ->
                    viewModel.setGridOffset(x, y)
                    viewModel.clearGridPreview()
                    showAlignDialog = false
                },
            )
        }
    }
}

/**
 * Nudges an already-calibrated grid onto the lines drawn in the map art,
 * without re-measuring. The map stays visible around the dialog and shows the
 * candidate grid live, which is the only way these sliders mean anything.
 */
@Composable
private fun GridAlignDialog(
    pxPerSquare: Float,
    initialOffsetX: Float,
    initialOffsetY: Float,
    onPreview: (Float, Float) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Float, Float) -> Unit,
) {
    var offsetX by remember { mutableStateOf(initialOffsetX) }
    var offsetY by remember { mutableStateOf(initialOffsetY) }

    LaunchedEffect(Unit) { onPreview(offsetX, offsetY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grid alignment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("One square = %.1f px. Slide until the gold grid sits on the map's own lines.".format(pxPerSquare))
                Text("Across", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = offsetX,
                    onValueChange = { offsetX = it; onPreview(it, offsetY) },
                    valueRange = 0f..pxPerSquare,
                )
                Text("Down", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = offsetY,
                    onValueChange = { offsetY = it; onPreview(offsetX, it) },
                    valueRange = 0f..pxPerSquare,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(offsetX, offsetY) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapControlBar(viewModel: MapViewModel) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Row one: what a finger does, undoing it, and what the TV is up to.
            ControlRow {
                BarItem {
                    ToolChip("Pan", Icons.Default.PanTool, viewModel.tool == MapTool.PAN) {
                        viewModel.tool = MapTool.PAN
                    }
                }
                BarItem {
                    ToolChip("Reveal", Icons.Default.Visibility, viewModel.tool == MapTool.REVEAL) {
                        viewModel.tool = MapTool.REVEAL
                    }
                }
                BarItem {
                    ToolChip("Hide", Icons.Default.VisibilityOff, viewModel.tool == MapTool.HIDE) {
                        viewModel.tool = MapTool.HIDE
                    }
                }
                BarItem {
                    IconButton(onClick = viewModel::undo, enabled = viewModel.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                }
                BarItem {
                    IconButton(onClick = viewModel::redo, enabled = viewModel.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                }
                // Freezing lets the DM scout ahead without dragging the
                // players' view along.
                BarItem {
                    FilterChip(
                        selected = viewModel.tvFrozen,
                        onClick = viewModel::toggleFreezeTv,
                        label = { Text(if (viewModel.tvFrozen) "TV frozen" else "TV follows") },
                    )
                }
                // Only meaningful while frozen, and kept next to the toggle it
                // belongs to rather than stranded on the other row.
                //
                // AssistChip rather than a button so it matches the two chips
                // either side of it: same 32dp height, shape and outline. A
                // FilterChip would look identical but announce itself as a
                // toggle to accessibility services, and this is a one-shot
                // action -- there is no "pushed" state to be in.
                if (viewModel.tvFrozen) {
                    BarItem {
                        AssistChip(
                            onClick = viewModel::pushViewToTv,
                            label = { Text("Push view") },
                        )
                    }
                }
                BarItem {
                    FilterChip(
                        selected = viewModel.blanked,
                        onClick = viewModel::toggleBlank,
                        label = { Text(if (viewModel.blanked) "TV hidden" else "Hide TV") },
                    )
                }
            }

            // Row two: which way the table is facing, and the bulk fog actions.
            ControlRow {
                BarItem {
                    IconButton(onClick = { viewModel.rotateOutput(-1) }) {
                        Icon(Icons.Default.RotateLeft, contentDescription = "Rotate view anticlockwise")
                    }
                }
                BarItem {
                    Text(
                        "${viewModel.rotationQuarters * 90}\u00B0",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                BarItem {
                    IconButton(onClick = { viewModel.rotateOutput(1) }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate view clockwise")
                    }
                }
                BarItem { OutlinedButton(onClick = viewModel::revealAll) { Text("Reveal all") } }
                BarItem { OutlinedButton(onClick = viewModel::hideAll) { Text("Hide all") } }
            }

            if (viewModel.tool != MapTool.PAN) {
                BrushControls(viewModel)
            }
        }
    }
}

/**
 * One centred line of the control bar.
 *
 * FlowRow rather than a plain Row so a narrow screen wraps instead of pushing
 * controls off the edge -- the whole point of the portrait layout is that
 * nothing hides behind a sideways scroll.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

/**
 * Gives every control the same height and centres it inside.
 *
 * Chips, icon buttons and outlined buttons are all different heights, so
 * without this they sit on a common top edge and the row reads as ragged.
 * Done with a wrapper rather than FlowRow's own item alignment so it does not
 * depend on a parameter that only exists in newer Compose.
 */
@Composable
private fun BarItem(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.height(BAR_ITEM_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Tall enough for an IconButton's touch target, which is the largest control. */
private val BAR_ITEM_HEIGHT = 48.dp

/**
 * What a drag paints with, and how big and soft its edge is.
 *
 * Only shown while a fog tool is active, so the shape choice sits with the
 * sliders it modifies. The size slider disappears for shapes: a dragged
 * rectangle has no brush radius, and leaving a dead control on screen is worse
 * than moving the live ones up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrushControls(viewModel: MapViewModel) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 600.dp
        val freehand = viewModel.fogShape == FogShape.BRUSH

        @Composable
        fun shapePicker() = ControlRow {
            BarItem {
                ShapeChip("Brush", Icons.Default.Brush, freehand) {
                    viewModel.setFogShape(FogShape.BRUSH)
                }
            }
            BarItem {
                ShapeChip(
                    "Rectangle",
                    Icons.Default.CropSquare,
                    viewModel.fogShape == FogShape.RECT,
                ) { viewModel.setFogShape(FogShape.RECT) }
            }
            BarItem {
                ShapeChip(
                    "Oval",
                    Icons.Default.Circle,
                    viewModel.fogShape == FogShape.OVAL,
                ) { viewModel.setFogShape(FogShape.OVAL) }
            }
        }

        @Composable
        fun sizeSlider(modifier: Modifier) = Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Size", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = viewModel.brushRadiusMapPx,
                onValueChange = viewModel::updateBrushRadius,
                valueRange = 15f..400f,
                modifier = Modifier.weight(1f),
            )
        }

        @Composable
        fun edgeSlider(modifier: Modifier) = Row(
            modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Edge", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = viewModel.brushSoftness,
                onValueChange = viewModel::updateBrushSoftness,
                valueRange = 0f..0.9f,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            shapePicker()
            if (stacked) {
                if (freehand) sizeSlider(Modifier.fillMaxWidth())
                edgeSlider(Modifier.fillMaxWidth())
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (freehand) sizeSlider(Modifier.weight(1f))
                    edgeSlider(Modifier.weight(if (freehand) 0.7f else 1f))
                }
            }
        }
    }
}

/** Same look as the tool chips, so the two rows read as one set of choices. */
@Composable
private fun ShapeChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, Modifier.width(18.dp)) },
    )
}

@Composable
private fun ToolChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, Modifier.width(18.dp)) },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

@Composable
private fun CalibrationHint(
    measured: Float,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 480.dp),
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Drag across one grid square on the map. Drag across several " +
                    "for a more accurate result.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
            )
            Text(
                if (measured > 1f) "Measured %.0f px".format(measured) else "Nothing measured yet",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                OutlinedButton(onClick = onDone, enabled = measured > 1f) { Text("Use this") }
            }
        }
    }
}

@Composable
private fun CalibrationDialog(
    measuredMapPx: Float,
    initialPxPerSquare: Float,
    onPreview: (pxPerSquare: Float, offsetX: Float, offsetY: Float) -> Unit,
    onDismiss: () -> Unit,
    onSave: (squares: Float, offsetX: Float, offsetY: Float) -> Unit,
) {
    var squaresText by remember { mutableStateOf("1") }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val squares = squaresText.toFloatOrNull() ?: 0f
    val perSquare = if (squares > 0f) measuredMapPx / squares else initialPxPerSquare

    // Keep the gold grid on the map in step with the fields behind the dialog.
    LaunchedEffect(perSquare, offsetX, offsetY) { onPreview(perSquare, offsetX, offsetY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Square size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("You measured %.0f map pixels.".format(measuredMapPx))
                OutlinedTextField(
                    value = squaresText,
                    onValueChange = { squaresText = it },
                    label = { Text("How many squares was that?") },
                    singleLine = true,
                )
                if (perSquare > 0f) {
                    Text(
                        "One square = %.1f px".format(perSquare),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text("Nudge the gold grid onto the map's own lines:")
                Slider(
                    value = offsetX,
                    onValueChange = { offsetX = it },
                    valueRange = 0f..perSquare.coerceAtLeast(1f),
                )
                Slider(
                    value = offsetY,
                    onValueChange = { offsetY = it },
                    valueRange = 0f..perSquare.coerceAtLeast(1f),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(squares, offsetX, offsetY) },
                enabled = squares > 0f && measuredMapPx > 1f,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
