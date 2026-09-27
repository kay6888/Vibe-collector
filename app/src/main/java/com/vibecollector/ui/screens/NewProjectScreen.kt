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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vibecollector.parse.ScaffoldEntry
import com.vibecollector.parse.TreeParser
import com.vibecollector.ui.VibeViewModel

/**
 * Scaffold a project before any code arrives.
 *
 * Two ways in, both optional and both local-first:
 *  - paste a tree / bullet list the assistant already gave you (no key, no network)
 *  - describe the project and let a saved DeepSeek (or other OpenAI-compatible)
 *    key write the tree for you
 *
 * The AI path is a convenience; everything below the preview works without it.
 */
@Composable
fun NewProjectScreen(vm: VibeViewModel, padding: PaddingValues) {
    val state by vm.state.collectAsStateWithLifecycle()
    val parsed by vm.scaffoldParsed.collectAsState()

    var projectName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var placeholder by remember { mutableStateOf("") }
    var withPlaceholders by remember { mutableStateOf(true) }

    val fileCount = parsed.count { !it.isDirectory }
    val dirCount = parsed.count { it.isDirectory }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("New project", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Build the folder structure first, then drop the generated code into it in the order the assistant gives it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = projectName,
            onValueChange = { projectName = it },
            label = { Text("Project name") },
            placeholder = { Text("my-app") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Text("1. Paste a structure", style = MaterialTheme.typography.titleSmall)
        Text(
            "Accepts ASCII trees, bullet lists, indented lists, or comma-separated paths. File contents in the same paste are ignored here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = vm.scaffoldText.collectAsState().value,
            onValueChange = vm::onScaffoldTextChanged,
            label = { Text("Project structure") },
            placeholder = { Text("my-app/\n  src/\n    main.py\n  package.json") },
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        )

        Spacer(Modifier.height(20.dp))
        Text("2. Or describe it", style = MaterialTheme.typography.titleSmall)
        if (!state.settings.hasApiKey) {
            Text(
                "Add a DeepSeek (or other OpenAI-compatible) API key in Settings to generate a structure from a description. Without a key, pasting a tree above is enough.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Describe the project") },
                    placeholder = { Text("A React + Vite todo app with tests") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.generateWithAi(description) },
                    enabled = description.isNotBlank() && !state.aiBusy,
                ) {
                    if (state.aiBusy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                    } else {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, Modifier.height(18.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("Generate")
                    }
                }
            }
            state.aiError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(20.dp))
        if (parsed.isNotEmpty()) {
            Text("Preview", style = MaterialTheme.typography.titleSmall)
            Text(
                "$dirCount folders, $fileCount files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            CodePreview(TreeParser.toAsciiTree(parsed, projectName.ifBlank { null }), maxLines = 30)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = withPlaceholders, onCheckedChange = { withPlaceholders = it })
            Spacer(Modifier.height(10.dp))
            Text(
                "Create empty files too",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                vm.scaffoldInto(
                    project = projectName.trim().ifBlank { "my-project" },
                    placeholder = if (withPlaceholders) placeholder else "",
                )
            },
            enabled = parsed.isNotEmpty() && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create structure") }

        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("Placeholder contents", style = MaterialTheme.typography.titleSmall)
        Text(
            "Written into every new file so nothing is accidentally half-built.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = placeholder,
            onValueChange = { placeholder = it },
            label = { Text("Placeholder text (optional)") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        )

        Spacer(Modifier.height(32.dp))
    }
}
