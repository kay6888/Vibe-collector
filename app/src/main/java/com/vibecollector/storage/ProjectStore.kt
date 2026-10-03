package com.vibecollector.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vibecollector.data.CapturedFile
import com.vibecollector.data.FileNode
import com.vibecollector.data.Project
import com.vibecollector.data.ExistingFilePolicy
import com.vibecollector.data.WriteOutcome
import com.vibecollector.parse.ScaffoldEntry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * All filesystem access for projects.
 *
 * Two invariants hold everywhere:
 *  1. Every path that came from parsed AI text is re-resolved through [resolve]
 *     and rejected unless it is genuinely inside the project root. This is the
 *     only thing standing between a hallucinated `../../databases/app.db` and
 *     your data directory, so it never trusts its input.
 *  2. Nothing is written until the user has explicitly saved it.
 */
class ProjectStore(context: Context) {

    private val appContext = context.applicationContext

    /**
     * `getExternalFilesDir` is app-scoped but world-readable, so projects show up
     * in any file manager under Android/data/com.vibecollector/files/Projects —
     * no storage permission required.
     */
    val projectsRoot: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "Projects")
            .also { if (!it.exists()) it.mkdirs() }

    // ------------------------------------------------------------------ resolution

    /** Reject anything that is not a simple folder name. */
    fun isValidProjectName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= 60 &&
            name == name.trim() &&
            name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') &&
            name.none { it.code < 32 }

    fun projectDir(name: String): File? {
        if (!isValidProjectName(name)) return null
        val dir = File(projectsRoot, name)
        return if (isInside(dir, projectsRoot)) dir else null
    }

    /**
     * Resolve a project-relative path to a real [File], or null if it escapes the
     * project. Blocks `..`, absolute paths and symlink tricks via canonical paths.
     */
    fun resolve(project: String, relativePath: String): File? {
        val dir = projectDir(project) ?: return null
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val candidate = File(dir, relativePath)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) return null
        return canonical
    }

    private fun isInside(child: File, root: File): Boolean {
        val c = runCatching { child.canonicalFile }.getOrNull() ?: return false
        val r = runCatching { root.canonicalFile }.getOrNull() ?: return false
        return c.path == r.path || c.path.startsWith(r.path + File.separator)
    }

    // ------------------------------------------------------------------ listing

    fun listProjects(): List<Project> {
        val root = projectsRoot
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f: File -> f.isDirectory }
            .orEmpty()
            .map { dir ->
                var files = 0
                var folders = 0
                var bytes = 0L
                var newest = dir.lastModified()
                dir.walkTopDown().onEnter { it != dir || true }.forEach { f ->
                    if (f == dir) return@forEach
                    if (f.isDirectory) folders++ else { files++; bytes += f.length() }
                    if (f.lastModified() > newest) newest = f.lastModified()
                }
                Project(dir.name, files, folders, bytes, newest)
            }
            .sortedByDescending { it.modifiedAt }
    }

    fun listDir(project: String, relativePath: String = ""): List<FileNode> {
        val dir = resolve(project, relativePath) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { !it.name.startsWith(".vibe") }
            .map { f ->
                val isDir = f.isDirectory
                FileNode(
                    name = f.name,
                    path = joinRelative(relativePath, f.name),
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0 else f.length(),
                    childCount = if (isDir) f.list()?.size ?: 0 else 0,
                    modifiedAt = f.lastModified(),
                )
            }
            .sortedWith(compareByDescending<FileNode> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun readFile(project: String, relativePath: String): String? {
        val f = resolve(project, relativePath) ?: return null
        if (!f.isFile || f.length() > MAX_EDIT_BYTES) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun readFileSize(project: String, relativePath: String): Long =
        resolve(project, relativePath)?.takeIf { it.isFile }?.length() ?: 0L

    fun writeFile(project: String, relativePath: String, content: String): Boolean {
        val f = resolve(project, relativePath) ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ mutation

    fun createProject(name: String): Boolean {
        val dir = projectDir(name) ?: return false
        return runCatching { dir.mkdirs() }.getOrDefault(false)
    }

    fun createFolder(project: String, parentRelative: String, name: String): Boolean {
        if (!isValidProjectName(name)) return false
        val parent = resolve(project, parentRelative) ?: return false
        if (!parent.isDirectory) return false
        val target = File(parent, name)
        if (!isInside(target, projectDir(project) ?: return false)) return false
        return runCatching { target.mkdirs() }.getOrDefault(false)
    }

    fun rename(project: String, relativePath: String, newName: String): Boolean {
        if (!isValidProjectName(newName)) return false
        val from = resolve(project, relativePath) ?: return false
        if (from.parentFile == null) return false
        val to = File(from.parentFile, newName)
        if (!isInside(to, projectDir(project) ?: return false)) return false
        if (to.exists()) return false
        return runCatching { from.renameTo(to) }.getOrDefault(false)
    }
    fun move(project: String, relativePath: String, destinationDirectory: String): Boolean {
        val from = resolve(project, relativePath) ?: return false
        val destination = resolve(project, destinationDirectory) ?: return false
        if (!from.exists() || !destination.isDirectory || from == destination) return false
        val target = File(destination, from.name)
        if (target.exists() || !isInside(target, projectDir(project) ?: return false)) return false
        return runCatching { from.renameTo(target) }.getOrDefault(false)
    }

    fun delete(project: String, relativePath: String): Boolean {
        val target = resolve(project, relativePath) ?: return false
        if (target.parentFile == null) return false
        return runCatching { target.deleteRecursively() }.getOrDefault(false)
    }

    fun deleteProject(name: String): Boolean {
        val dir = projectDir(name) ?: return false
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ structure scaffold

    /**
     * Create the folders and empty placeholder files for a parsed structure.
     *
     * A single top-level folder that contains everything else is treated as the
     * project root, so pasting
     * ```
     * my-app/
     *   src/
     *     main.py
     * ```
     * into project "my-app" produces `src/main.py` rather than `my-app/src/main.py`.
     */
    fun scaffold(project: String, entries: List<ScaffoldEntry>, placeholderText: String? = null): ScaffoldResult {
        val dir = projectDir(project) ?: return ScaffoldResult(0, 0, "invalid project name")
        if (!dir.exists() && !runCatching { dir.mkdirs() }.getOrDefault(false)) {
            return ScaffoldResult(0, 0, "could not create project")
        }
        val rootCanonical = runCatching { dir.canonicalFile }.getOrNull()
            ?: return ScaffoldResult(0, 0, "could not resolve project")

        val topDirs = entries.filter { it.isDirectory && !it.path.contains('/') }
            .map { it.path }
            .toSet()
        val singleWrapper = topDirs.size == 1 &&
            entries.all { it.isDirectory == true || it.path.startsWith(topDirs.first() + "/") }

        var dirsMade = 0
        var filesMade = 0
        for (entry in entries) {
            var rel = entry.path.trim('/')
            if (singleWrapper) rel = rel.removePrefix(topDirs.first()).trim('/')
            if (rel.isEmpty()) continue

            val target = File(dir, rel)
            val canonical = runCatching { target.canonicalFile }.getOrNull() ?: continue
            if (canonical != rootCanonical && !canonical.path.startsWith(rootCanonical.path + File.separator)) continue

            if (entry.isDirectory) {
                if (!canonical.exists() && runCatching { canonical.mkdirs() }.getOrDefault(false)) dirsMade++
            } else {
                if (canonical.exists()) continue
                canonical.parentFile?.mkdirs()
                val written = runCatching {
                    canonical.parentFile?.mkdirs()
                    canonical.writeText(placeholderText ?: "")
                    true
                }.getOrDefault(false)
                if (written) filesMade++
            }
        }
        return ScaffoldResult(dirsMade, filesMade, null)
    }

    // ------------------------------------------------------------------ capture writing

    /**
     * Write a confirmed capture. Files whose path cannot be resolved safely are
     * reported in [WriteOutcome.skipped] rather than written somewhere unexpected.
     */
    fun writeCapture(
        project: String,
        files: List<CapturedFile>,
        policy: ExistingFilePolicy = ExistingFilePolicy.ASK,
    ): WriteOutcome {
        val dir = projectDir(project) ?: return WriteOutcome(emptyList(), files.map { it.path }, "invalid project name")
        if (!dir.exists() && !runCatching { dir.mkdirs() }.getOrDefault(false)) {
            return WriteOutcome(emptyList(), files.map { it.path }, "could not create project folder")
        }
        val conflicts = files.map { it.path }.filter { path -> resolve(project, path)?.exists() == true }.distinct()
        if (policy == ExistingFilePolicy.ASK && conflicts.isNotEmpty()) {
            return WriteOutcome(emptyList(), emptyList(), conflicts = conflicts)
        }
        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val reserved = mutableSetOf<String>()
        for (file in files) {
            val selectedPath = if (policy == ExistingFilePolicy.KEEP_BOTH) uniquePath(project, file.path, reserved) else file.path
            val target = resolve(project, selectedPath)
            if (target == null || target.isDirectory) {
                skipped += file.path
                continue
            }
            val ok = runCatching {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { it.write(file.content.toByteArray(Charsets.UTF_8)) }
                true
            }.getOrDefault(false)
            if (ok) {
                written += selectedPath
                reserved += selectedPath
            } else {
                skipped += file.path
            }
        }
        return WriteOutcome(written, skipped, null)
    }

    private fun uniquePath(project: String, path: String, reserved: Set<String>): String {
        val file = resolve(project, path) ?: return path
        if (!file.exists() && path !in reserved) return path
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        var index = 2
        while (true) {
            val candidateName = "$stem ($index)$extension"
            val candidate = if (parent.isBlank()) candidateName else "$parent/$candidateName"
            val resolved = resolve(project, candidate)
            if (resolved != null && !resolved.exists() && candidate !in reserved) return candidate
            index++
        }
    }

    /** Keep a log of what was collected, newest first, at the project root. */
    fun appendCaptureLog(project: String, source: String, paths: List<String>) {
        val file = resolve(project, LOG_FILE) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val entry = buildString {
                append("[").append(stamp).append("] ")
                append(source.ifBlank { "clipboard" })
                append('\n')
                paths.forEach { append("  + ").append(it).append('\n') }
            }
            val existing = if (file.exists()) file.readText() else ""
            file.writeText(entry + existing)
        }
    }

    fun readCaptureLog(project: String): String =
        resolve(project, LOG_FILE)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrDefault("") } ?: ""

    // ------------------------------------------------------------------ export

    /** Zip a project into Downloads via MediaStore. Returns the resulting content Uri. */
    fun exportZipToDownloads(project: String): Uri? {
        val dir = projectDir(project) ?: return null
        if (!dir.isDirectory) return null
        val fileName = "${sanitizeForFilename(project)}.zip"
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = appContext.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    ZipOutputStream(out).use { zip ->
                        val rootPrefix = "${sanitizeForFilename(project)}/"
                        dir.walkTopDown().forEach { f ->
                            if (f == dir) return@forEach
                            val rel = rootPrefix + dir.toRelativeString(f)
                            if (f.isDirectory) {
                                runCatching { zip.putNextEntry(ZipEntry("$rel/")); zip.closeEntry() }
                            } else {
                                zip.putNextEntry(ZipEntry(rel))
                                FileInputStream(f).use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }.getOrNull()
    }

    /** Copy a project into Downloads as a real directory tree (pre-Q only, or via SAF). */
    fun projectBytes(project: String): Long =
        projectDir(project)?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun sanitizeForFilename(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifEmpty { "project" }

    private fun joinRelative(parent: String, child: String): String =
        if (parent.isBlank()) child else "$parent/$child"

    companion object {
        const val LOG_FILE = ".vibe-log.txt"
        const val MAX_EDIT_BYTES = 2L * 1024 * 1024
    }
}

data class ScaffoldResult(val dirsCreated: Int, val filesCreated: Int, val error: String?)

class StorageException(message: String) : IOException(message)
