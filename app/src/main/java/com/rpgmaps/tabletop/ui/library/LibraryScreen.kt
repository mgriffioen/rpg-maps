package com.rpgmaps.tabletop.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedCard
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
import com.rpgmaps.tabletop.data.FolderTree
import com.rpgmaps.tabletop.data.LibrarySort
import com.rpgmaps.tabletop.data.MapFiles
import com.rpgmaps.tabletop.data.db.FolderEntity
import com.rpgmaps.tabletop.data.db.MapEntity
import com.rpgmaps.tabletop.ui.display.CastButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenMap: (String) -> Unit,
    onOpenDisplaySettings: () -> Unit,
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    val allMaps by viewModel.maps.collectAsState()
    val allFolders by viewModel.folders.collectAsState()
    val openFolderId by viewModel.openFolderId.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val message by viewModel.message.collectAsState()
    val missingImages by viewModel.missingImages.collectAsState()

    val snackbars = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<MapEntity?>(null) }
    var deleting by remember { mutableStateOf<MapEntity?>(null) }
    var movingMap by remember { mutableStateOf<MapEntity?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var movingFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var deletingFolder by remember { mutableStateOf<FolderEntity?>(null) }

    // What is directly inside the folder being browsed.
    val path = FolderTree.path(allFolders, openFolderId)
    val maps = sort.sortMaps(allMaps.filter { it.folderId == openFolderId })
    val folders = sort.sortFolders(allFolders.filter { it.parentId == openFolderId }, allFolders, allMaps)

    // Back steps out of a folder before it leaves the library.
    BackHandler(enabled = openFolderId != null) { viewModel.openParentFolder() }

    // OpenMultipleDocuments rather than the photo picker: battle maps are
    // usually downloads or Drive files, not camera roll photos.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.import(uris) }

    // The map a picked image is for. Held outside the launcher because the
    // result callback only receives the URI.
    var replacing by remember { mutableStateOf<MapEntity?>(null) }
    val replacePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val map = replacing
        replacing = null
        if (uri != null && map != null) viewModel.replaceImage(map, uri)
    }
    fun chooseImageFor(map: MapEntity) {
        replacing = map
        replacePicker.launch(arrayOf("image/*"))
    }

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
                title = {
                    Text(
                        path.lastOrNull()?.name ?: "Map library",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (openFolderId != null) {
                        IconButton(onClick = { viewModel.openParentFolder() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one folder")
                        }
                    }
                },
                actions = {
                    SortMenu(current = sort, onSelect = viewModel::selectSort)
                    IconButton(onClick = { creatingFolder = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                    }
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (path.isNotEmpty()) {
                Breadcrumbs(path = path, onOpen = viewModel::openFolder)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                importProgress != null -> ImportingState(
                    done = importProgress!!.done,
                    total = importProgress!!.total,
                )

                maps.isEmpty() && folders.isEmpty() ->
                    if (openFolderId == null) EmptyState() else EmptyFolderState()

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val showHeadings = folders.isNotEmpty() && maps.isNotEmpty()
                    if (showHeadings) sectionHeading("Folders")
                    items(folders, key = { "folder:" + it.id }) { folder ->
                        FolderCard(
                            folder = folder,
                            mapCount = allMaps.count { it.folderId == folder.id },
                            folderCount = allFolders.count { it.parentId == folder.id },
                            onOpen = { viewModel.openFolder(folder.id) },
                            onRename = { renamingFolder = folder },
                            onMove = { movingFolder = folder },
                            onDelete = { deletingFolder = folder },
                        )
                    }
                    if (showHeadings) sectionHeading("Maps")
                    items(maps, key = { it.id }) { map ->
                        val missing = map.id in missingImages
                        MapCard(
                            map = map,
                            missing = missing,
                            // Opening a map with no image only shows an
                            // error, so go straight to fixing it instead.
                            onOpen = { if (missing) chooseImageFor(map) else onOpenMap(map.id) },
                            onChooseImage = { chooseImageFor(map) },
                            onRename = { renaming = map },
                            onMove = { movingMap = map },
                            onDelete = { deleting = map },
                        )
                    }
                }
            }
            }
        }
    }

    renaming?.let { map ->
        NameDialog(
            title = "Rename map",
            initial = map.name,
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                viewModel.rename(map.id, newName)
                renaming = null
            },
        )
    }

    if (creatingFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                viewModel.createFolder(name)
                creatingFolder = false
            },
        )
    }

    renamingFolder?.let { folder ->
        NameDialog(
            title = "Rename folder",
            initial = folder.name,
            onDismiss = { renamingFolder = null },
            onConfirm = { name ->
                viewModel.renameFolder(folder.id, name)
                renamingFolder = null
            },
        )
    }

    movingMap?.let { map ->
        MoveDialog(
            title = "Move ${map.name}",
            folders = allFolders,
            currentParentId = map.folderId,
            onDismiss = { movingMap = null },
            onMove = { target ->
                viewModel.moveMap(map, target)
                movingMap = null
            },
        )
    }

    movingFolder?.let { folder ->
        MoveDialog(
            title = "Move ${folder.name}",
            // A folder cannot go inside itself or anything it contains.
            folders = allFolders.filterNot { FolderTree.isWithin(allFolders, it.id, folder.id) },
            currentParentId = folder.parentId,
            onDismiss = { movingFolder = null },
            onMove = { target ->
                viewModel.moveFolder(folder, target)
                movingFolder = null
            },
        )
    }

    deletingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text("Delete folder ${folder.name}?") },
            text = {
                Text(
                    "Only the folder goes. Its maps and folders move up to " +
                        (allFolders.firstOrNull { it.id == folder.parentId }?.name
                            ?: "the top of the library") + ".",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteFolder(folder); deletingFolder = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text("Cancel") } },
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
    missing: Boolean,
    onOpen: () -> Unit,
    onChooseImage: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Card(Modifier.clickable(onClick = onOpen)) {
        Column {
            if (missing) {
                MissingImage(Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            } else {
                AsyncImage(
                    model = MapFiles(context, map.id).thumb,
                    contentDescription = map.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f),
                )
            }
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
                            text = { Text(if (missing) "Choose image" else "Replace image") },
                            leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = { menuOpen = false; onChooseImage() },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to folder") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                            onClick = { menuOpen = false; onMove() },
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

/** The sort button in the top bar and its menu, with the current order ticked. */
@Composable
private fun SortMenu(current: LibrarySort, onSelect: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort by ${current.label.lowercase()}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            for (option in LibrarySort.entries) {
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = {
                        if (option == current) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = { open = false; onSelect(option) },
                )
            }
        }
    }
}

/** A heading that takes a whole row, so folders and maps never share one. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.sectionHeading(text: String) {
    item(key = "heading:$text", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun FolderCard(
    folder: FolderEntity,
    mapCount: Int,
    folderCount: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    OutlinedCard(Modifier.clickable(onClick = onOpen)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    folderSummary(mapCount, folderCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Folder options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to folder") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                        onClick = { menuOpen = false; onMove() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete folder") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

private fun folderSummary(maps: Int, folders: Int): String {
    val parts = buildList {
        if (maps > 0) add(if (maps == 1) "1 map" else "$maps maps")
        if (folders > 0) add(if (folders == 1) "1 folder" else "$folders folders")
    }
    return parts.joinToString("  ·  ").ifEmpty { "Empty" }
}

/** Library › Campaign › Chapter 1 -- each step tappable to jump back to it. */
@Composable
private fun Breadcrumbs(path: List<FolderEntity>, onOpen: (String?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen(null) }) {
            Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Library")
        }
        path.forEachIndexed { index, folder ->
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val last = index == path.lastIndex
            TextButton(onClick = { onOpen(folder.id) }, enabled = !last) {
                Text(folder.name, maxLines = 1)
            }
        }
    }
    HorizontalDivider()
}

/**
 * Picks where a map or folder goes: the top level of the library or any
 * folder, shown as an indented tree. [folders] is already limited to valid
 * destinations; [currentParentId] is marked and cannot be picked again.
 */
@Composable
private fun MoveDialog(
    title: String,
    folders: List<FolderEntity>,
    currentParentId: String?,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
) {
    val entries = FolderTree.flatten(folders)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    MoveTarget(
                        label = "Library (top level)",
                        icon = { Icon(Icons.Default.Home, null) },
                        depth = 0,
                        current = currentParentId == null,
                        onClick = { onMove(null) },
                    )
                }
                listItems(entries, key = { it.folder.id }) { entry ->
                    MoveTarget(
                        label = entry.folder.name,
                        icon = { Icon(Icons.Default.Folder, null) },
                        depth = entry.depth + 1,
                        current = entry.folder.id == currentParentId,
                        onClick = { onMove(entry.folder.id) },
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "No folders yet. Use the new-folder button at the top of the library to make one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveTarget(
    label: String,
    icon: @Composable () -> Unit,
    depth: Int,
    current: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !current, onClick = onClick)
            .padding(start = (depth * 20).dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (current) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (current) {
            Icon(Icons.Default.Check, contentDescription = "Already here", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Stands in for the thumbnail of a map whose image is gone, and says what
 * tapping it does: the name and calibration survived, only the picture needs
 * choosing again.
 */
@Composable
private fun MissingImage(modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.ImageNotSupported,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Image missing",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Tap to choose it again. The name and grid are kept.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun EmptyFolderState() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("This folder is empty", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Maps you add now go into this folder. To file a map that is " +
                "already in the library, use Move to folder in its menu.",
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
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String = "Save",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
