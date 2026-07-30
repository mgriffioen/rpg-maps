package com.rpgmaps.tabletop.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rpgmaps.tabletop.data.MapFiles
import com.rpgmaps.tabletop.data.db.MapEntity
import com.rpgmaps.tabletop.ui.display.CastButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenMap: (String) -> Unit,
    onOpenDisplaySettings: () -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    val maps by viewModel.maps.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbars = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<MapEntity?>(null) }
    var deleting by remember { mutableStateOf<MapEntity?>(null) }

    // OpenMultipleDocuments rather than the photo picker: battle maps are
    // usually downloads or Drive files, not camera roll photos.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.import(uris) }

    LaunchedEffect(message) {
        message?.let {
            snackbars.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text("Map library") },
                actions = {
                    CastButton(Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = onOpenDisplaySettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Display settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("image/*")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add maps") },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                importProgress != null -> ImportingState(
                    done = importProgress!!.done,
                    total = importProgress!!.total,
                )

                maps.isEmpty() -> EmptyState()

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(maps, key = { it.id }) { map ->
                        MapCard(
                            map = map,
                            onOpen = { onOpenMap(map.id) },
                            onRename = { renaming = map },
                            onDelete = { deleting = map },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { map ->
        RenameDialog(
            initial = map.name,
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                viewModel.rename(map.id, newName)
                renaming = null
            },
        )
    }

    deleting?.let { map ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${map.name}?") },
            text = { Text("The image and its fog of war are removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(map); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MapCard(
    map: MapEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Card(Modifier.clickable(onClick = onOpen)) {
        Column {
            AsyncImage(
                model = MapFiles(context, map.id).thumb,
                contentDescription = map.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        map.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${map.imageW} x ${map.imageH}" +
                            if (map.pxPerSquare > 0f) "  ·  calibrated" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Map options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No maps yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Tap Add maps to bring in battle map images. They are copied onto " +
                "this tablet, so you can play with no internet at all.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ImportingState(done: Int, total: Int) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            "Importing ${done + 1} of $total…",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename map") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
