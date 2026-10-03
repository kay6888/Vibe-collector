package com.vibecollector.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecollector.VibeTab
import com.vibecollector.data.FileNode
import com.vibecollector.formatTime
import com.vibecollector.ui.VibeViewModel
import com.vibecollector.ui.components.ProjectPicker
import com.vibecollector.ui.components.RowMenu

/**
 * The file manager: projects, folders, files.
 *
 * Reads and writes through [VibeViewModel], which is the only thing that touches
 * [com.vibecollector.storage.ProjectStore].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: VibeViewModel, padding: PaddingValues) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val browse = state.browse

    var showNewFolder by remember { mutableStateOf(false) }
    var showNewProject by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<FileNode?>(null) }
    var renameTarget by remember { mutableStateOf<FileNode?>(null) }
    var moveTarget by remember { mutableStateOf<FileNode?>(null) }
    var movePath by remember { mutableStateOf("") }
    var searchQuery by remember(browse.project, browse.path) { mutableStateOf("") }

    LaunchedEffect(browse.project) { vm.refreshProjects() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(browse.project.ifBlank { "No project" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (browse.path.isNotBlank()) {
                                Text(
                                    browse.path,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (browse.path.isNotBlank()) {
                            IconButton(onClick = { vm.goUp() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.refreshProjects(); vm.refreshPending() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        RowMenu(
                            items = listOf(
                                "New folder" to { showNewFolder = true },
                                "New project" to { showNewProject = true },
                                "Export to Downloads" to { showExport = true },
                                "Share project" to { vm.shareProject(browse.project) },
                                "Delete project" to { vm.deleteCurrentProject() },
                            )
                        ) { Icon(Icons.Filled.MoreVert, contentDescription = "Project menu") }
                    },
                )
                if (browse.loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewFolder = true },
                icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                text = { Text("New folder") },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            ProjectPicker(
                projects = state.projects.map { it.name },
                selected = browse.project,
                onSelect = { vm.openProject(it, "") },
            )

            if (browse.project.isNotBlank()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search this folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            if (browse.project.isBlank()) {
                EmptyHint("Create a project to start collecting code into it.")
                return@Column
            }

            val visibleNodes = browse.nodes.filter {
                it.name.contains(searchQuery, ignoreCase = true) || it.path.contains(searchQuery, ignoreCase = true)
            }
            if (visibleNodes.isEmpty() && !browse.loading) {
                EmptyHint(if (searchQuery.isBlank()) "This folder is empty.\nCopy some code from a chatbot and it will show up here." else "No files match ‘$searchQuery’ in this folder.")
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visibleNodes, key = { it.path }) { node ->
                    FileRow(
                        node = node,
                        contextTime = { formatTime(context, node.modifiedAt) },
                        size = { VibeViewModel.formatSize(context, node.sizeBytes) },
                        onClick = {
                            if (node.isDirectory) vm.navigateTo(node) else vm.openFile(node)
                        },
                        onRename = { renameTarget = node },
                        onMove = { moveTarget = node; movePath = "" },
                        onDelete = { pendingDelete = node },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    if (showNewFolder) {
        TextInputDialog(
            title = "New folder",
            label = "Folder name",
            onConfirm = { name ->
                vm.createFolder(name.trim())
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false },
        )
    }

    if (showNewProject) {
        TextInputDialog(
            title = "New project",
            label = "Project name",
            onConfirm = { name ->
                vm.createProject(name.trim())
                showNewProject = false
            },
            onDismiss = { showNewProject = false },
        )
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export ${browse.project}") },
            text = {
                Text(
                    "A zip of the whole project is written to your Downloads folder as " +
                        "${browse.project}.zip. Nothing leaves the device.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.exportProject(browse.project); showExport = false }) {
                    Text("Export")
                }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("Cancel") } },
        )
    }

    renameTarget?.let { target ->
        TextInputDialog(
            title = "Rename ${target.name}",
            label = "New name",
            initial = target.name,
            onConfirm = { name ->
                vm.renameNode(target, name.trim())
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    moveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("Move ${target.name}") },
            text = {
                OutlinedTextField(
                    value = movePath,
                    onValueChange = { movePath = it },
                    label = { Text("Destination folder path") },
                    placeholder = { Text("src/main") },
                    supportingText = { Text("Use a project-relative path; leave blank for the project root.") },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.moveNode(target, movePath.trim().trim('/')); moveTarget = null }) {
                    Text("Move")
                }
            },
            dismissButton = { TextButton(onClick = { moveTarget = null }) { Text("Cancel") } },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${target.name}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteNode(target); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileRow(
    node: FileNode,
    contextTime: () -> String,
    size: () -> String,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (node.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = if (node.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                buildString {
                    if (node.isDirectory) {
                        append("${node.childCount} item")
                        if (node.childCount != 1) append("s")
                    } else {
                        append(size())
                    }
                    val t = contextTime()
                    if (t.isNotBlank()) append("  •  ").append(t)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "File actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text("Move") }, onClick = { menuOpen = false; onMove() })
                if (node.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "Create",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CodePreview(text: String, maxLines: Int = 6) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun UpButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Filled.ArrowUpward, contentDescription = "Up")
    }
}
