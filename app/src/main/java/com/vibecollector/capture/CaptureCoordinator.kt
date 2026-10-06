package com.vibecollector.capture

import android.content.Context
import com.vibecollector.data.CapturedFile
import com.vibecollector.data.ExistingFilePolicy
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

    /** Keep only the files that still need another save attempt. */
    private fun replaceFiles(id: String, files: List<CapturedFile>) {
        synchronized(lock) {
            val current = pending[id] ?: return
            pending[id] = current.copy(files = files)
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

    suspend fun save(
        id: String,
        overrideFiles: List<CapturedFile>? = null,
        conflictPolicy: ExistingFilePolicy = ExistingFilePolicy.ASK,
    ): SaveReport =
        withContext(Dispatchers.IO) {
            val capture = find(id) ?: return@withContext SaveReport(false, "capture expired")
            val project = capture.project.ifBlank { suggestProjectName() }
            val files = overrideFiles ?: capture.files
            if (files.any { it.needsNameConfirm }) {
                return@withContext SaveReport(false, "Confirm guessed filenames before saving", project)
            }
            if (files.isEmpty()) {
                remove(id)
                return@withContext SaveReport(true, "Structure created in $project", project, emptyList())
            }
            // Route each file into its slot in the generated structure; anything that
            // fits nowhere is parked in the misc folder instead of polluting the project.
            val hasStructure = store.expectedFiles(project).isNotEmpty()
            val routed = mutableListOf<CapturedFile>()
            val misc = mutableListOf<CapturedFile>()
            for (f in files) {
                val target = if (hasStructure) store.matchStructure(project, f.path) else f.path
                if (target == null) misc += f else routed += f.copy(path = target)
            }
            val outcome = store.writeCapture(project, routed, conflictPolicy)
            if (outcome.conflicts.isNotEmpty()) {
                return@withContext SaveReport(false, "Files already exist in $project", project, conflicts = outcome.conflicts)
            }
            if (outcome.error != null) {
                SaveReport(false, outcome.error, project, emptyList())
            } else {
                val failed = routed.filter { it.path in outcome.skipped }.toMutableList()
                var miscWritten = 0
                for (file in misc) {
                    val miscOutcome = store.writeCapture(
                        ProjectStore.MISC_PROJECT,
                        listOf(file),
                        ExistingFilePolicy.KEEP_BOTH,
                    )
                    val stored = miscOutcome.error == null &&
                        miscOutcome.conflicts.isEmpty() &&
                        miscOutcome.skipped.isEmpty() &&
                        miscOutcome.written.isNotEmpty()
                    if (stored) miscWritten++ else failed += file
                }
                if (outcome.written.isNotEmpty()) {
                    store.appendCaptureLog(project, capture.sourceLabel, outcome.written)
                }
                if (failed.isNotEmpty()) {
                    replaceFiles(id, failed)
                    val detail = buildString {
                        if (outcome.skipped.isNotEmpty()) {
                            append("${outcome.skipped.size} file(s) could not be written to $project")
                        }
                        val miscFailed = failed.count { it.path !in outcome.skipped }
                        if (miscFailed > 0) {
                            if (isNotEmpty()) append("; ")
                            append("$miscFailed file(s) could not be stored in Misc")
                        }
                    }
                    SaveReport(false, detail, project, outcome.written, miscCount = miscWritten)
                } else {
                    remove(id)
                    SaveReport(true, null, project, outcome.written, miscCount = miscWritten)
                }
            }
        }

    /** Save every pending capture at once — the "yes to all" button. */
    suspend fun saveAll(conflictPolicy: ExistingFilePolicy = ExistingFilePolicy.ASK): List<SaveReport> = withContext(Dispatchers.IO) {
        list().map { save(it.id, conflictPolicy = conflictPolicy) }
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
    val conflicts: List<String> = emptyList(),
    val miscCount: Int = 0,
) {
    val summary: String
        get() = when {
            !ok -> error ?: "could not save"
            written.isEmpty() && miscCount > 0 -> "$miscCount file${if (miscCount == 1) "" else "s"} stored in Misc (not part of $project)"
            miscCount > 0 -> "Saved ${written.size} to $project, $miscCount to Misc"
            written.isEmpty() -> "Created $project"
            written.size == 1 -> "Saved ${written.first()}"
            else -> "Saved ${written.size} files to $project"
        }
}
