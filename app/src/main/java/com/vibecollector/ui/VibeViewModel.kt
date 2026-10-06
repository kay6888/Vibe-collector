package com.vibecollector.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibecollector.Vibe
import com.vibecollector.ai.AiStructureClient
import com.vibecollector.capture.CaptureRules
import com.vibecollector.data.CapturedFile
import com.vibecollector.data.ExistingFilePolicy
import com.vibecollector.data.FileNode
import com.vibecollector.data.PendingCapture
import com.vibecollector.data.Project
import com.vibecollector.data.VibeSettings
import com.vibecollector.notify.CaptureNotifier
import com.vibecollector.parse.ScaffoldEntry
import com.vibecollector.parse.TreeParser
import com.vibecollector.storage.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrowseState(
    val project: String = "",
    val path: String = "",
    val nodes: List<FileNode> = emptyList(),
    val loading: Boolean = false,
)

/** One visible row of the drop-down project tree. */
data class TreeRow(val node: FileNode, val depth: Int, val expanded: Boolean)

data class UiState(
    val tree: List<TreeRow> = emptyList(),
    /** Every file and folder in the open project, independent of expansion. */
    val catalog: List<FileNode> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val projects: List<Project> = emptyList(),
    val browse: BrowseState = BrowseState(),
    val pending: List<PendingCapture> = emptyList(),
    val settings: VibeSettings = VibeSettings(),
    val openCaptureId: String? = null,
    val viewer: ViewerState? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val aiBusy: Boolean = false,
    val conflictCaptureId: String? = null,
    val conflictPaths: List<String> = emptyList(),
    val aiError: String? = null,
)

data class ViewerState(
    val path: String,
    val content: String,
    val edited: Boolean = false,
    val readOnly: Boolean = false,
    val tooBig: Boolean = false,
)

class VibeViewModel(app: Application) : AndroidViewModel(app) {

    private val store: ProjectStore = Vibe.app.projectStore
    private val settingsStore = Vibe.app.settingsStore

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val scaffoldParsed: MutableStateFlow<List<ScaffoldEntry>> = MutableStateFlow(emptyList())
    val scaffoldText: MutableStateFlow<String> = MutableStateFlow("")

    init {
        viewModelScope.launch {
            Vibe.app.settingsFlow.collect { s -> _state.update { it.copy(settings = s) } }
        }
        refreshProjects()
        refreshPending()
    }

    // ---------------------------------------------------------------- browsing

    fun refreshProjects() = viewModelScope.launch(Dispatchers.IO) {
        val projects = store.listProjects()
        _state.update { current ->
            val active = current.browse.project.ifBlank { projects.firstOrNull()?.name.orEmpty() }
            current.copy(projects = projects)
        }
        if (_state.value.browse.project.isBlank()) {
            val first = projects.firstOrNull()?.name
            if (first != null) openProject(first, "") else _state.update { it.copy(browse = BrowseState()) }
        } else {
            openProject(_state.value.browse.project, _state.value.browse.path)
        }
    }

    fun openProject(name: String, path: String = "") = viewModelScope.launch(Dispatchers.IO) {
        val sameProject = _state.value.browse.project == name
        _state.update { it.copy(browse = it.browse.copy(project = name, path = path, loading = true)) }
        val nodes = store.listDir(name, path)
        val expanded = if (sameProject) _state.value.expanded else emptySet()
        val catalog = projectCatalog(name)
        val tree = buildTree(catalog, expanded)
        _state.update {
            it.copy(
                browse = it.browse.copy(nodes = nodes, loading = false),
                expanded = expanded,
                catalog = catalog,
                tree = tree,
            )
        }
    }

    private fun projectCatalog(project: String): List<FileNode> {
        if (project.isBlank()) return emptyList()
        val all = mutableListOf<FileNode>()
        val seen = mutableSetOf("")
        fun walk(path: String) {
            for (node in store.listDir(project, path)) {
                if (!seen.add(node.path)) continue
                all += node
                if (node.isDirectory) walk(node.path)
            }
        }
        walk("")
        return all
    }

    private fun buildTree(catalog: List<FileNode>, expanded: Set<String>): List<TreeRow> {
        val rows = mutableListOf<TreeRow>()
        var skipBelow = -1
        for (node in catalog) {
            val depth = node.path.count { it == '/' }
            if (skipBelow >= 0 && depth > skipBelow) continue
            skipBelow = -1
            val open = node.isDirectory && node.path in expanded
            rows += TreeRow(node, depth, open)
            if (node.isDirectory && !open) skipBelow = depth
        }
        return rows
    }

    fun toggleFolder(node: FileNode) = viewModelScope.launch(Dispatchers.IO) {
        val cur = _state.value.expanded
        val next = if (node.path in cur) cur - node.path else cur + node.path
        _state.update { it.copy(expanded = next, tree = buildTree(it.catalog, next)) }
    }

    fun navigateTo(node: FileNode) {
        if (node.isDirectory) openProject(_state.value.browse.project, node.path)
    }

    fun goUp(): Boolean {
        val b = _state.value.browse
        if (b.path.isBlank()) return false
        val parent = b.path.substringBeforeLast('/', "")
        openProject(b.project, parent)
        return true
    }

    fun refreshPending() = viewModelScope.launch(Dispatchers.IO) {
        val pending = Vibe.app.captureCoordinator.list()
        _state.update { it.copy(pending = pending) }
        Vibe.setBadgeCount(pending.size)
    }

    // ---------------------------------------------------------------- pending captures

    fun captureById(id: String): PendingCapture? = _state.value.pending.firstOrNull { it.id == id }

    fun openCapture(id: String?) = _state.update { it.copy(openCaptureId = id) }

    fun saveCapture(
        id: String,
        files: List<CapturedFile>? = null,
        project: String? = null,
        conflictPolicy: ExistingFilePolicy? = null,
    ) = viewModelScope.launch {
        project?.let { chosen ->
            val existing = Vibe.app.captureCoordinator.find(id)
            if (existing != null) Vibe.app.captureCoordinator.enqueue(existing.copy(project = chosen))
        }
        _state.update { it.copy(busy = true) }
        val policy = conflictPolicy ?: _state.value.settings.existingFilePolicy
        val report = Vibe.app.captureCoordinator.save(id, files, policy)
        if (report.ok) CaptureNotifier.clearCapturePrompt(getApplication(), id)
        _state.update { state ->
            if (report.conflicts.isNotEmpty()) {
                state.copy(busy = false, conflictCaptureId = id, conflictPaths = report.conflicts, openCaptureId = id)
            } else {
                state.copy(
                    busy = false,
                    message = report.summary,
                    openCaptureId = null,
                    conflictCaptureId = null,
                    conflictPaths = emptyList(),
                )
            }
        }
        refreshPending()
        refreshProjects()
    }

    fun discardCapture(id: String) = viewModelScope.launch(Dispatchers.IO) {
        Vibe.app.captureCoordinator.remove(id)
        CaptureNotifier.clearCapturePrompt(getApplication(), id)
        _state.update { it.copy(message = "Discarded", openCaptureId = null) }
        refreshPending()
    }

    fun saveAllPending() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val captures = Vibe.app.captureCoordinator.list()
        val reports = captures.map { capture ->
            Vibe.app.captureCoordinator.save(capture.id, conflictPolicy = _state.value.settings.existingFilePolicy)
        }
        reports.forEachIndexed { index, report -> if (report.ok) CaptureNotifier.clearCapturePrompt(getApplication(), captures[index].id) }
        val conflictIndex = reports.indexOfFirst { it.conflicts.isNotEmpty() }
        val conflictCapture = captures.getOrNull(conflictIndex)
        val saved = reports.count { it.ok }
        val waiting = reports.count { !it.ok && it.conflicts.isEmpty() }
        _state.update {
            it.copy(
                busy = false,
                message = buildString {
                    append("Saved $saved capture${if (saved == 1) "" else "s"}")
                    if (waiting > 0) append(", $waiting still waiting")
                },
                conflictCaptureId = conflictCapture?.id,
                conflictPaths = reports.getOrNull(conflictIndex)?.conflicts.orEmpty(),
            )
        }
        refreshPending()
        refreshProjects()
    }

    fun discardAllPending() = viewModelScope.launch(Dispatchers.IO) {
        Vibe.app.captureCoordinator.list().forEach { CaptureNotifier.clearCapturePrompt(getApplication(), it.id) }
        Vibe.app.captureCoordinator.clear()
        _state.update { it.copy(message = "Inbox cleared") }
        refreshPending()
    }

    fun renameCapturedFile(id: String, oldPath: String, newPath: String) = viewModelScope.launch(Dispatchers.IO) {
        Vibe.app.captureCoordinator.renameFile(id, oldPath, newPath)
        refreshPending()
    }

    // ---------------------------------------------------------------- files

    fun openFile(node: FileNode) = viewModelScope.launch(Dispatchers.IO) {
        val project = _state.value.browse.project
        val size = store.readFileSize(project, node.path)
        if (size > ProjectStore.MAX_EDIT_BYTES) {
            _state.update {
                it.copy(viewer = ViewerState(node.path, "", readOnly = true, tooBig = true))
            }
            return@launch
        }
        val content = store.readFile(project, node.path).orEmpty()
        _state.update { it.copy(viewer = ViewerState(node.path, content)) }
    }

    fun editViewer(content: String) =
        _state.update { s -> s.copy(viewer = s.viewer?.copy(content = content, edited = true)) }

    fun saveViewer() = viewModelScope.launch(Dispatchers.IO) {
        val viewer = _state.value.viewer ?: return@launch
        val ok = store.writeFile(_state.value.browse.project, viewer.path, viewer.content)
        _state.update {
            it.copy(message = if (ok) "Saved ${viewer.path}" else "Could not save", viewer = null)
        }
        openProject(_state.value.browse.project, _state.value.browse.path)
    }

    fun closeViewer() = _state.update { it.copy(viewer = null) }

    fun createFolder(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val b = _state.value.browse
        val ok = store.createFolder(b.project, b.path, name)
        _state.update { it.copy(message = if (ok) "Created $name" else "Invalid folder name") }
        openProject(b.project, b.path)
    }

    fun createProject(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val ok = store.createProject(name)
        _state.update { it.copy(message = if (ok) "Created $name" else "Could not create project") }
        if (ok) openProject(name, "")
        refreshProjects()
    }

    fun renameNode(node: FileNode, newName: String) = viewModelScope.launch(Dispatchers.IO) {
        val b = _state.value.browse
        val ok = store.rename(b.project, node.path, newName)
        _state.update { it.copy(message = if (ok) "Renamed to $newName" else "Rename failed") }
        openProject(b.project, b.path)
    }

    fun moveNode(node: FileNode, destinationDirectory: String) = viewModelScope.launch(Dispatchers.IO) {
        val b = _state.value.browse
        val ok = store.move(b.project, node.path, destinationDirectory)
        _state.update { it.copy(message = if (ok) "Moved ${node.name}" else "Move failed; destination must exist and be empty") }
        openProject(b.project, b.path)
    }

    fun deleteNode(node: FileNode) = viewModelScope.launch(Dispatchers.IO) {
        val b = _state.value.browse
        val ok = store.delete(b.project, node.path)
        _state.update { it.copy(message = if (ok) "Deleted ${node.name}" else "Delete failed") }
        openProject(b.project, b.path)
    }

    fun deleteCurrentProject() = viewModelScope.launch(Dispatchers.IO) {
        val name = _state.value.browse.project
        if (name.isBlank()) return@launch
        store.deleteProject(name)
        _state.update { it.copy(browse = BrowseState(), message = "Deleted $name") }
        refreshProjects()
    }

    fun exportProject(name: String, destination: Uri) = viewModelScope.launch(Dispatchers.IO) {
        _state.update { it.copy(busy = true) }
        val exported = store.exportZip(name, destination)
        _state.update {
            it.copy(busy = false, message = if (exported) "Project ZIP saved" else "Export failed")
        }
    }

    fun shareProject(name: String) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "project" }
            val zip = java.io.File(app.cacheDir, "$safe.zip")
            val wrote = runCatching {
                java.io.FileOutputStream(zip).use { store.writeProjectZip(name, it) }
            }.getOrDefault(false)
            if (!wrote) {
                _state.update { it.copy(message = "Could not share $name") }
                return@launch
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", zip)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Vibe.mainHandler.post {
                runCatching {
                    app.startActivity(
                        Intent.createChooser(send, "Share $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    _state.update { state -> state.copy(message = "Could not share $name") }
                }
            }
        }
    }

    fun readLog(): String = store.readCaptureLog(_state.value.browse.project)

    fun importText(text: String, sourceLabel: String = "Shared text") = viewModelScope.launch(Dispatchers.IO) {
        val settings = settingsStore.flow.first()
        val importSettings = settings.copy(
            captureEnabled = true,
            copyCollectEnabled = true,
            chatAppsOnly = false,
            pauseUntilMillis = 0,
            excludedPackages = emptySet(),
        )
        val verdict = CaptureRules.evaluate(text, importSettings, null)
        if (!verdict.capture) {
            _state.update { it.copy(message = "No code detected: ${verdict.reason}") }
            return@launch
        }
        val capture = Vibe.app.captureCoordinator.build(text, settings, "", sourceLabel)
        if (capture == null) {
            _state.update { it.copy(message = "Could not import this text") }
            return@launch
        }
        if (capture.isScaffoldOnly) {
            _state.update { it.copy(message = "Project structure created in ${capture.project}") }
            refreshProjects()
            return@launch
        }
        Vibe.app.captureCoordinator.enqueue(capture)
        if (settings.savesWithoutAsking && !capture.hasUnnamed) {
            val report = Vibe.app.captureCoordinator.save(capture.id, conflictPolicy = settings.existingFilePolicy)
            _state.update {
                it.copy(
                    message = report.summary,
                    conflictCaptureId = capture.id.takeIf { report.conflicts.isNotEmpty() },
                    conflictPaths = report.conflicts,
                )
            }
            if (!report.ok && settings.notificationsEnabled) CaptureNotifier.showCapturePrompt(getApplication(), capture, settings)
        } else {
            _state.update { it.copy(message = "Imported to Inbox for review") }
            if (settings.notificationsEnabled) CaptureNotifier.showCapturePrompt(getApplication(), capture, settings)
        }
        refreshPending()
        refreshProjects()
    }

    // ---------------------------------------------------------------- scaffold

    fun onScaffoldTextChanged(text: String) {
        scaffoldText.value = text
        scaffoldParsed.value = TreeParser.parse(text)
    }

    fun scaffoldInto(project: String, placeholder: String) = viewModelScope.launch(Dispatchers.IO) {
        val entries = scaffoldParsed.value
        if (entries.isEmpty()) {
            _state.update { it.copy(message = "Nothing recognisable in that text") }
            return@launch
        }
        val result = store.scaffold(project, entries, placeholder.ifBlank { null })
        _state.update {
            it.copy(
                message = if (result.error != null) result.error
                else "Created ${result.dirsCreated} folders and ${result.filesCreated} files",
            )
        }
        refreshProjects()
        openProject(project, "")
    }

    fun generateWithAi(description: String) = viewModelScope.launch {
        val s = _state.value.settings
        _state.update { it.copy(aiBusy = true, aiError = null) }
        val result = AiStructureClient().generateStructure(s.apiBase, s.apiKey, s.apiModel, description)
        result.onSuccess { structure ->
            onScaffoldTextChanged(structure)
            _state.update { it.copy(aiBusy = false, message = "Structure generated") }
        }.onFailure { e ->
            _state.update { it.copy(aiBusy = false, aiError = e.message ?: "Request failed") }
        }
    }

    // ---------------------------------------------------------------- settings

    fun setCaptureEnabled(v: Boolean) = viewModelScope.launch { settingsStore.setCaptureEnabled(v) }
    fun setCopyCollect(v: Boolean) = viewModelScope.launch { settingsStore.setCopyCollect(v) }
    fun setNotifications(v: Boolean) = viewModelScope.launch {
        settingsStore.setNotifications(v)
        if (!v) CaptureNotifier.clearCaptureNotifications(getApplication())
    }
    fun setAutoSaveAll(v: Boolean) = viewModelScope.launch { settingsStore.setAutoSaveAll(v) }
    fun setChatAppsOnly(v: Boolean) = viewModelScope.launch { settingsStore.setChatAppsOnly(v) }
    fun setMinChars(v: Int) = viewModelScope.launch { settingsStore.setMinChars(v) }
    fun setDefaultProject(v: String) = viewModelScope.launch { settingsStore.setDefaultProject(v) }
    fun setApiKey(v: String) = viewModelScope.launch { settingsStore.setApiKey(v) }
    fun setApiModel(v: String) = viewModelScope.launch { settingsStore.setApiModel(v) }
    fun setApiBase(v: String) = viewModelScope.launch { settingsStore.setApiBase(v) }
    fun setBubbleEnabled(v: Boolean) = viewModelScope.launch { settingsStore.setBubbleEnabled(v) }
    fun setPauseUntil(v: Long) = viewModelScope.launch { settingsStore.setPauseUntil(v) }
    fun setHideNotificationPreview(v: Boolean) = viewModelScope.launch { settingsStore.setHideNotificationPreview(v) }
    fun setExistingFilePolicy(v: ExistingFilePolicy) = viewModelScope.launch { settingsStore.setExistingFilePolicy(v) }
    fun setExcludedPackage(packageName: String, excluded: Boolean) = viewModelScope.launch {
        val packages = _state.value.settings.excludedPackages.toMutableSet()
        if (excluded) packages += packageName else packages -= packageName
        settingsStore.setExcludedPackages(packages)
    }

    fun clearConflict() = _state.update { it.copy(conflictCaptureId = null, conflictPaths = emptyList()) }

    // ---------------------------------------------------------------- helpers

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun accessibilityEnabled(): Boolean {
        val expected = "${getApplication<Application>().packageName}/${com.vibecollector.capture.ChatAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) } ||
            enabled.split(':').any { it.endsWith("/${com.vibecollector.capture.ChatAccessibilityService::class.java.name}", ignoreCase = true) }
    }

    fun openAccessibilitySettings() {
        getApplication<Application>().startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getApplication<Application>().packageName)
        getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun canDrawOverlay(): Boolean =
        com.vibecollector.overlay.BubbleService.canDrawOverlay(getApplication())

    fun openOverlaySettings() {
        getApplication<Application>().startActivity(
            com.vibecollector.overlay.BubbleService.overlaySettingsIntent(getApplication<Application>().packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun projectsPathText(): String = store.projectsRoot.absolutePath

    companion object {
        fun formatSize(context: android.content.Context, bytes: Long): String =
            Formatter.formatShortFileSize(context, bytes)
    }
}
