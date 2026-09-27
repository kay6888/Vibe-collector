package com.vibecollector.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecollector.data.CapturedFile
import com.vibecollector.data.PendingCapture
import com.vibecollector.ui.VibeViewModel

/**
 * Everything waiting for a decision, plus the full save/discard detail sheet.
 *
 * This is the screen a user lands on when they tap a capture notification, so it
 * has to be usable for "is this right?" without hunting: each file is listed with
 * a preview, and a guessed filename can be corrected before writing.
 */
@Composable
fun InboxScreen(vm: VibeViewModel, padding: PaddingValues, openCaptureId: String?) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshPending() }

    val open = state.pending.firstOrNull { it.id == openCaptureId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = padding.calculateBottomPadding())
    ) {
        if (state.pending.isEmpty()) {
            EmptyHint(
                "Nothing waiting.\nCopy code from a chatbot and it will appear here, " +
                    "or as a notification asking whether to keep it.",
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.saveAllPending() },
                enabled = !state.busy && state.pending.none { it.hasUnnamed },
            ) { Text("Save all") }
            OutlinedButton(
                onClick = { vm.discardAllPending() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Discard all") }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.pending, key = { it.id }) { capture ->
                CaptureCard(
                    capture = capture,
                    onOpen = { vm.openCapture(capture.id) },
                    onSave = { vm.saveCapture(capture.id) },
                    onDiscard = { vm.discardCapture(capture.id) },
                )
            }
        }
    }

    if (open != null) {
        CaptureDetailSheet(vm = vm, capture = open)
    }
}

@Composable
private fun CaptureCard(
    capture: PendingCapture,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                capture.sourceLabel.ifBlank { "Clipboard" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (capture.fileCount == 1) capture.files.first().path else "${capture.fileCount} files",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (capture.fileCount > 1) {
                Text(
                    capture.files.take(3).joinToString("\n") { "• ${it.path}" } +
                        if (capture.fileCount > 3) "\n… and ${capture.fileCount - 3} more" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (capture.hasUnnamed) {
                Spacer(Modifier.height(6.dp))
                AssistChip(
                    onClick = onOpen,
                    label = { Text("Some names were guessed — tap to fix") },
                    leadingIcon = {
                        Icon(Icons.Filled.Warning, contentDescription = null, Modifier.height(16.dp))
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = !capture.hasUnnamed) {
                    Icon(Icons.Filled.Check, contentDescription = null, Modifier.height(18.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("Save")
                }
                OutlinedButton(onClick = onDiscard) {
                    Icon(Icons.Filled.Close, contentDescription = null, Modifier.height(18.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("Discard")
                }
            }
        }
    }
}

/** Full detail: pick a project, correct guessed names, then save. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CaptureDetailSheet(vm: VibeViewModel, capture: PendingCapture) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var project by remember(capture.id) { mutableStateOf(capture.project) }
    var renaming by remember { mutableStateOf<CapturedFile?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val effective = state.pending.firstOrNull { it.id == capture.id } ?: capture

    ModalBottomSheet(onDismissRequest = { vm.openCapture(null) }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Review capture", style = MaterialTheme.typography.titleLarge)
            Text(
                "From ${effective.sourceLabel.ifBlank { "clipboard" }} • ${effective.files.size} file(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = project,
                onValueChange = { project = it },
                label = { Text("Save into project") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Leave blank to create one automatically") },
            )

            Spacer(Modifier.height(14.dp))
            effective.files.forEach { file ->
                FileDraftRow(
                    file = file,
                    onRename = { renaming = file },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        vm.saveCapture(effective.id, project = project.ifBlank { null })
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save ${effective.files.size} file(s)") }
                OutlinedButton(onClick = { confirmDiscard = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Discard")
                }
            }
        }
    }

    renaming?.let { file ->
        TextInputDialog(
            title = "Confirm filename",
            label = "Path inside the project",
            initial = file.path,
            confirmLabel = "Use this",
            onConfirm = { newPath ->
                vm.renameCapturedFile(effective.id, file.path, newPath)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this capture?") },
            text = { Text("Nothing will be written to disk.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.discardCapture(effective.id)
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun FileDraftRow(file: CapturedFile, onRename: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.path,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (file.needsNameConfirm) {
                    TextButton(onClick = onRename) { Text("Rename") }
                }
            }
            Text(
                "${file.lineCount} lines • ${file.byteCount} bytes" +
                    (file.language?.let { " • $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                file.content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
