package com.vibecollector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vibecollector.notify.CaptureNotifier
import com.vibecollector.ui.VibeViewModel
import com.vibecollector.ui.components.VibeNavBar
import com.vibecollector.ui.screens.FilesScreen
import com.vibecollector.ui.screens.InboxScreen
import com.vibecollector.ui.screens.NewProjectScreen
import com.vibecollector.ui.screens.SettingsScreen
import com.vibecollector.ui.screens.ViewerScreen
import com.vibecollector.ui.theme.VibeCollectorTheme

enum class VibeTab(val label: String) {
    FILES("Files"),
    INBOX("Inbox"),
    NEW("New"),
    SETTINGS("Settings"),
}

class MainActivity : ComponentActivity() {

    private var pendingCaptureId by mutableStateOf<String?>(null)

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            CaptureNotifier.ensureChannels(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CaptureNotifier.ensureChannels(this)
        maybeAskForNotifications()
        consumeIntent(intent)

        setContent {
            VibeCollectorTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    VibeRoot(pendingCaptureId = pendingCaptureId, onCaptureConsumed = { pendingCaptureId = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_CAPTURE) {
            pendingCaptureId = intent.getStringExtra(EXTRA_CAPTURE_ID)
        }
    }

    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) runCatching { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    companion object {
        const val ACTION_OPEN_CAPTURE = "com.vibecollector.action.OPEN_CAPTURE"
        const val EXTRA_CAPTURE_ID = "capture_id"
    }
}

@Composable
private fun VibeRoot(pendingCaptureId: String?, onCaptureConsumed: () -> Unit) {
    val vm: VibeViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(VibeTab.FILES) }
    var openCapture by rememberSaveable { mutableStateOf(pendingCaptureId) }

    LaunchedEffect(pendingCaptureId) {
        if (pendingCaptureId != null) {
            openCapture = pendingCaptureId
            tab = VibeTab.INBOX
            vm.refreshPending()
            onCaptureConsumed()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessage()
        }
    }

    // Saving or discarding from the detail sheet dismisses it.
    LaunchedEffect(state.openCaptureId) {
        if (state.openCaptureId == null) openCapture = null
    }

    val viewer = state.viewer
    if (viewer != null) {
        ViewerScreen(vm = vm, viewer = viewer)
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { VibeNavBar(current = tab, pendingCount = state.pending.size, onSelect = { tab = it }) },
    ) { padding ->
        when (tab) {
            VibeTab.FILES -> FilesScreen(vm = vm, padding = padding)
            VibeTab.INBOX -> InboxScreen(vm = vm, padding = padding, openCaptureId = openCapture)
            VibeTab.NEW -> NewProjectScreen(vm = vm, padding = padding)
            VibeTab.SETTINGS -> SettingsScreen(vm = vm, padding = padding)
        }
    }
}
