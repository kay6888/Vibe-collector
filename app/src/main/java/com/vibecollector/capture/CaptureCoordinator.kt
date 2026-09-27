package com.vibecollector.capture

import android.content.Context
import com.vibecollector.data.CapturedFile
import com.vibecollector.data.PendingCapture
import com.vibecollector.data.VibeSettings
import com.vibecollector.parse.CodeBlockParser
import com.vibecollector.parse.ScaffoldEntry
import com.vibecollector.parse.TreeParser
import com.vibecollector.storage.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Turns raw clipboard text into a [PendingCapture] and applies the user's answer.
 *
 * Pending captures are written to disk immediately, because the process is
 * frequently killed while the user is still reading the chatbot and an
 * in-memory queue would silently lose the code.
 */
class CaptureCoordinator(
    private val context: Context,
    private val store: ProjectStore,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val pending = LinkedHashMap<String, PendingCapture>()

    private val queueFile: File
        get() = File(context.filesDir, "pending-captures.json").also { it.parentFile?.mkdirs() }

    init {
        synchronized(lock) { pending.putAll(load()) }
    }

    private fun load(): Map<String, PendingCapture> = runCatching {
        if (!queueFile.exists()) return emptyMap()
        val list = json.decodeFromString<List<PendingCapture>>(queueFile.readText())
        list.associateBy { it.id }
    }.getOrDefault(emptyMap())

    private fun persist() {
        runCatching {
            queueFile.writeText(json.encodeToString(pending.values.toList()))
        }
    }

    fun list(): List<PendingCapture> = synchronized(lock) { pending.values.sortedByDescending { it.createdAt } }

    fun count(): Int = synchronized(lock) { pending.size }

    /**
     * Build a pending capture from a clipboard payload, or null if nothing usable
     * was found. [sourceLabel] is the app the text came from, shown in the prompt.
     */
    fun build(
        text: String,
        settings: VibeSettings,
        sourcePackage: String,
        sourceLabel: String,
    ): PendingCapture? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val result = CodeBlockParser.parse(trimmed)

        // Structure-only payload: scaffold the folders, no code to write.
        if (CaptureRules.isStructureOnly(result, trimmed)) {
            val entries: List<ScaffoldEntry> = TreeParser.parse(trimmed)
            if (entries.isEmpty()) return null
            val project = settings.defaultProject.ifBlank { suggestProjectName() }
            val scaffold = store.scaffold(project, entries)
            if (scaffold.error != null) return null
            return PendingCapture(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                sourceApp = sourcePackage,
                sourceLabel = sourceLabel,
                files = emptyList(),
                project = project,
                scaffoldedDirs = scaffold.dirsCreated,
                scaffoldedFiles = scaffold.filesCreated,
            )
        }

        if (result.isEmpty) return null

        val files = result.files.map {
            CapturedFile(
                path = it.path,
                content = it.content,
                language = it.language,
                needsNameConfirm = it.needsNameConfirm,
            )
        }
        return PendingCapture(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            sourceApp = sourcePackage,
            sourceLabel = sourceLabel,
            files = files,
            project = settings.defaultProject,
        )
    }

    fun enqueue(capture: PendingCapture) {
        synchronized(lock) {
            pending[capture.id] = capture
            persist()
        }
    }

    fun find(id: String): PendingCapture? = synchronized(lock) { pending[id] }

    fun remove(id: String) {
        synchronized(lock) {
            pending.remove(id)
            persist()
        }
    }

    fun clear() {
        synchronized(lock) {
            pending.clear()
            persist()
        }
    }

    /** Update a file's path in place, used when the user renames before saving. */
    fun renameFile(id: String, oldPath: String, newPath: String) {
        synchronized(lock) {
            val c = pending[id] ?: return
            pending[id] = c.copy(
                files = c.files.map {
                    if (it.path == oldPath) it.copy(path = newPath, needsNameConfirm = false) else it
                }
            )
            persist()
        }
    }

    suspend fun save(id: String, overrideFiles: List<CapturedFile>? = null): SaveReport =
        withContext(Dispatchers.IO) {
            val capture = find(id) ?: return@withContext SaveReport(false, "capture expired")
            val project = capture.project.ifBlank { suggestProjectName() }
            val files = overrideFiles ?: capture.files
            if (files.isEmpty()) {
                remove(id)
                return@withContext SaveReport(true, "Structure created in $project", project, emptyList())
            }
            val outcome = store.writeCapture(project, files)
            if (outcome.error != null) {
                SaveReport(false, outcome.error, project, emptyList())
            } else {
                store.appendCaptureLog(project, capture.sourceLabel, outcome.written)
                remove(id)
                SaveReport(true, null, project, outcome.written)
            }
        }

    /** Save every pending capture at once — the "yes to all" button. */
    suspend fun saveAll(): List<SaveReport> = withContext(Dispatchers.IO) {
        list().map { save(it.id) }
    }

    suspend fun discardAll() = withContext(Dispatchers.IO) { clear() }

    fun suggestProjectName(): String {
        val existing = store.listProjects()
        if (existing.isEmpty()) return "my-project"
        val base = "project-${existing.size + 1}"
        if (existing.none { it.name == base }) return base
        var i = existing.size + 1
        while (existing.any { it.name == "project-$i" }) i++
        return "project-$i"
    }
}

data class SaveReport(
    val ok: Boolean,
    val error: String? = null,
    val project: String = "",
    val written: List<String> = emptyList(),
) {
    val summary: String
        get() = when {
            !ok -> error ?: "could not save"
            written.isEmpty() -> "Created $project"
            written.size == 1 -> "Saved ${written.first()}"
            else -> "Saved ${written.size} files to $project"
        }
}
