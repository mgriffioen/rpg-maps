package com.rpgmaps.tabletop.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
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

@Composable
private fun MapControlBar(viewModel: MapViewModel) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolChip("Pan", Icons.Default.PanTool, viewModel.tool == MapTool.PAN) {
                    viewModel.tool = MapTool.PAN
                }
                ToolChip("Reveal", Icons.Default.Visibility, viewModel.tool == MapTool.REVEAL) {
                    viewModel.tool = MapTool.REVEAL
                }
                ToolChip("Hide", Icons.Default.VisibilityOff, viewModel.tool == MapTool.HIDE) {
                    viewModel.tool = MapTool.HIDE
                }

                Spacer(Modifier.width(4.dp))

                IconButton(onClick = viewModel::undo, enabled = viewModel.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = viewModel::redo, enabled = viewModel.canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }

                OutlinedButton(onClick = viewModel::revealAll) { Text("Reveal all") }
                OutlinedButton(onClick = viewModel::hideAll) { Text("Hide all") }

                Spacer(Modifier.width(4.dp))

                // Freezing lets the DM scout ahead without dragging the
                // players' view along.
                FilterChip(
                    selected = viewModel.tvFrozen,
                    onClick = viewModel::toggleFreezeTv,
                    label = { Text(if (viewModel.tvFrozen) "TV frozen" else "TV follows") },
                )
                if (viewModel.tvFrozen) {
                    OutlinedButton(onClick = viewModel::pushViewToTv) { Text("Push view") }
                }

                FilterChip(
                    selected = viewModel.blanked,
                    onClick = viewModel::toggleBlank,
                    label = { Text(if (viewModel.blanked) "TV hidden" else "Hide TV") },
                )
            }

            if (viewModel.tool != MapTool.PAN) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Brush, contentDescription = null)
                    Text("Brush", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = viewModel.brushRadiusMapPx,
                        onValueChange = viewModel::updateBrushRadius,
                        valueRange = 15f..400f,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Edge", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = viewModel.brushSoftness,
                        onValueChange = viewModel::updateBrushSoftness,
                        valueRange = 0f..0.9f,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }
        }
    }
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
