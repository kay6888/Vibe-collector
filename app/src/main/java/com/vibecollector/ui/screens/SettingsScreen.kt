package com.vibecollector.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecollector.capture.CaptureRules
import com.vibecollector.overlay.BubbleService
import com.vibecollector.ui.VibeViewModel
import com.vibecollector.ui.components.SectionHeader

/**
 * All the switches the app exposes, in the order someone would actually use them:
 * capture on/off first, then copy-collect, then how you get told about it.
 */
@Composable
fun SettingsScreen(vm: VibeViewModel, padding: PaddingValues) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val context = LocalContext.current

    var serviceOn by remember { mutableStateOf(vm.accessibilityEnabled()) }
    var overlayOn by remember { mutableStateOf(vm.canDrawOverlay()) }
    var showKey by remember { mutableStateOf(false) }
    var minChars by remember(s.minChars) { mutableIntStateOf(s.minChars) }

    // Re-check permission state whenever the screen comes back into view.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        serviceOn = vm.accessibilityEnabled()
        overlayOn = vm.canDrawOverlay()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Permissions")

        PermissionCard(
            title = "Capture service",
            granted = serviceOn,
            explanation = "Required. Android only lets the focused app and accessibility services read the " +
                "clipboard, so this is how copied code is noticed at all.",
            actionLabel = if (serviceOn) "Open settings" else "Enable",
            onAction = { vm.openAccessibilitySettings() },
        )
        Spacer(Modifier.height(8.dp))
        PermissionCard(
            title = "Floating button over other apps",
            granted = overlayOn,
            explanation = "Optional. Shows the vibe-collector bubble on top of DeepSeek, ChatGPT and friends so you " +
                "can jump between them and the project.",
            actionLabel = if (overlayOn) "Open settings" else "Grant",
            onAction = { vm.openOverlaySettings() },
        )
        Spacer(Modifier.height(8.dp))
        PermissionCard(
            title = "Notifications",
            granted = s.notificationsEnabled,
            explanation = "Needed for the save-or-discard prompt. If you turn them off, captures still arrive " +
                "and wait in the Inbox tab.",
            actionLabel = "Open settings",
            onAction = { vm.openNotificationSettings() },
        )

        SectionHeader("Capturing")
        ToggleRow(
            title = "Capture code",
            subtitle = "Master switch. When off, nothing is captured at all.",
            checked = s.captureEnabled,
            onChange = vm::setCaptureEnabled,
        )
        ToggleRow(
            title = "Copy collect",
            subtitle = "Anything you copy is treated as code to collect, parsed for filenames, and offered to you.",
            checked = s.copyCollectEnabled,
            onChange = vm::setCopyCollect,
        )
        ToggleRow(
            title = "Only from chatbot apps",
            subtitle = "Limits capture to the foreground app being a known assistant. " +
                "Turn off to collect from anywhere.",
            checked = s.chatAppsOnly,
            onChange = vm::setChatAppsOnly,
        )

        Spacer(Modifier.height(12.dp))
        Text("Minimum length: $minChars characters", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Shorter clipboard payloads are ignored, so copying a word does nothing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = minChars.toFloat(),
            onValueChange = { minChars = it.toInt() },
            onValueChangeFinished = { vm.setMinChars(minChars) },
            valueRange = 0f..500f,
        )

        SectionHeader("Being told")
        ToggleRow(
            title = "Notifications",
            subtitle = "Show a notification for every code collected, with Save and Discard buttons.",
            checked = s.notificationsEnabled,
            onChange = vm::setNotifications,
        )
        ToggleRow(
            title = "Yes to all",
            subtitle = "Save captures immediately without asking. You can still discard from the capture log.",
            checked = s.autoSaveAll,
            onChange = vm::setAutoSaveAll,
        )
        ToggleRow(
            title = "Floating button",
            subtitle = "Keep the bubble available over other apps (needs the permission above).",
            checked = s.bubbleEnabled,
            onChange = { enabled ->
                vm.setBubbleEnabled(enabled)
                context.startBubble(enabled)
            },
        )

        SectionHeader("Where things go")
        OutlinedTextField(
            value = s.defaultProject,
            onValueChange = vm::setDefaultProject,
            label = { Text("Default project for new captures") },
            placeholder = { Text("ask me each time") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Projects live at", style = MaterialTheme.typography.labelMedium)
                Text(
                    vm.projectsPathText(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open it from any file manager, or use Export to Downloads in the project menu.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionHeader("Optional: AI structure generation")
        Text(
            "Only used by the New tab to turn a description into a folder tree. " +
                "Capture and parsing work without it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.apiBase,
            onValueChange = vm::setApiBase,
            label = { Text("API base") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.apiModel,
            onValueChange = vm::setApiModel,
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.apiKey,
            onValueChange = vm::setApiKey,
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showKey) "Hide key" else "Show key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Stored in app-private preferences on this device and sent only to the API base above. " +
                "It is not encrypted at rest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("Recognised assistants")
        Text(
            CaptureRules.CHAT_PACKAGES.joinToString("\n") { "• $it" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("About")
        Text(
            "vibe-collector ${com.vibecollector.BuildConfig.VERSION_NAME}\n" +
                "Nothing leaves this device unless you export it or you configure an AI key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionCard(
    title: String,
    granted: Boolean,
    explanation: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (granted) "granted" else "not granted",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (granted) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(explanation, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun android.content.Context.startBubble(enabled: Boolean) {
    if (!enabled) {
        stopService(Intent(this, BubbleService::class.java))
        return
    }
    if (!BubbleService.canDrawOverlay(this)) {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(Intent(this, BubbleService::class.java))
    } else {
        startService(Intent(this, BubbleService::class.java))
    }
}
