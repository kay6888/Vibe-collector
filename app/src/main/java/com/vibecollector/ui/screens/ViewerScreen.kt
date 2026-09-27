package com.vibecollector.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import com.vibecollector.ui.VibeViewModel
import com.vibecollector.ui.ViewerState

/** Open, read and edit a file inside a project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: VibeViewModel, viewer: ViewerState) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewer.path) },
                navigationIcon = {
                    IconButton(onClick = vm::closeViewer) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (viewer.edited && !viewer.readOnly) {
                        IconButton(onClick = vm::saveViewer) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            if (viewer.tooBig) {
                EmptyHint("This file is too large to open here.")
                return@Column
            }
            if (viewer.readOnly) {
                SelectionContainer {
                    Text(
                        viewer.content,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    )
                }
                return@Column
            }

            OutlinedTextField(
                value = viewer.content,
                onValueChange = vm::editViewer,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = vm::saveViewer, enabled = viewer.edited, modifier = Modifier.weight(1f)) {
                    Text("Save")
                }
                OutlinedButton(onClick = vm::closeViewer) { Text("Close") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
