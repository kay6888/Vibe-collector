package com.vibecollector.storage

import android.content.Context
import android.net.Uri
import com.vibecollector.data.CapturedFile
import com.vibecollector.data.FileNode
import com.vibecollector.data.FileStatus
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
        val expected = expectedFiles(project)
        val nodes = dir.listFiles().orEmpty()
            .filter { !it.name.startsWith(".vibe") }
            .map { f ->
                val isDir = f.isDirectory
                val path = joinRelative(relativePath, f.name)
                FileNode(
                    name = f.name,
                    path = path,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0 else f.length(),
                    childCount = if (isDir) f.list()?.count { !it.startsWith(".vibe") } ?: 0 else 0,
                    modifiedAt = f.lastModified(),
                    status = if (isDir) folderStatus(project, path, expected) else fileStatus(project, path, expected),
                )
            }
        // Files the structure promised but that are gone show up in red.
        val prefix = if (relativePath.isBlank()) "" else "$relativePath/"
        val present = nodes.map { it.name }.toSet()
        val ghosts = mutableMapOf<String, FileNode>()
        for (e in expected) {
            if (!e.startsWith(prefix)) continue
            val rest = e.removePrefix(prefix)
            val head = rest.substringBefore('/')
            if (head in present || head in ghosts) continue
            val isDir = rest.contains('/')
            val path = joinRelative(relativePath, head)
            if (resolve(project, path)?.exists() == true) continue
            ghosts[head] = FileNode(head, path, isDir, status = FileStatus.MISSING)
        }
        return (nodes + ghosts.values)
            .sortedWith(compareByDescending<FileNode> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** Colour status of any file or folder in a project. */
    fun statusOf(project: String, relativePath: String): FileStatus {
        val f = resolve(project, relativePath)
        val expected = expectedFiles(project)
        return if (f != null && f.isDirectory) folderStatus(project, relativePath, expected)
        else fileStatus(project, relativePath, expected)
    }

    // ------------------------------------------------------------------ structure manifest

    /** Project-relative file paths promised by the generated structure. */
    fun expectedFiles(project: String): Set<String> {
        val f = resolve(project, STRUCTURE_FILE) ?: return emptySet()
        if (!f.isFile) return emptySet()
        return runCatching { f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            .getOrDefault(emptySet())
    }

    private fun recordExpected(project: String, paths: Collection<String>) {
        if (paths.isEmpty()) return
        val f = resolve(project, STRUCTURE_FILE) ?: return
        runCatching { f.writeText((expectedFiles(project) + paths).sorted().joinToString("\n") + "\n") }
    }

    fun placeholderHeader(path: String): String = "$PLACEHOLDER_MARK $path\n"

    private fun isPlaceholder(file: File): Boolean {
        if (!file.isFile || file.length() == 0L || file.length() > 512) return false
        return runCatching { file.readText().contains(PLACEHOLDER_MARK) }.getOrDefault(false)
    }

    fun fileStatus(project: String, relativePath: String, expected: Set<String> = expectedFiles(project)): FileStatus {
        val f = resolve(project, relativePath)
        if (f == null || !f.exists()) return if (relativePath in expected) FileStatus.MISSING else FileStatus.BROKEN
        if (f.isDirectory) return FileStatus.BROKEN
        if (!f.canRead()) return FileStatus.BROKEN
        return if (isPlaceholder(f)) FileStatus.PLACEHOLDER else FileStatus.COLLECTED
    }

    /** Worst status among the files below a folder: red beats yellow beats blue. */
    fun folderStatus(project: String, relativePath: String, expected: Set<String> = expectedFiles(project)): FileStatus {
        val prefix = if (relativePath.isBlank()) "" else "$relativePath/"
        var worst = FileStatus.COLLECTED
        val dir = resolve(project, relativePath)
        val seen = mutableSetOf<String>()
        if (dir != null && dir.isDirectory) {
            for (f in dir.walkTopDown()) {
                if (!f.isFile || f.name.startsWith(".vibe")) continue
                val rel = prefix + f.toRelativeString(dir).replace(File.separatorChar, '/')
                seen += rel
                val st = fileStatus(project, rel, expected)
                if (st.ordinal > worst.ordinal) worst = st
            }
        }
        if (expected.any { it.startsWith(prefix) && it !in seen }) return FileStatus.MISSING
        return worst
    }

    /**
     * Pick the structure file a captured path belongs to: an exact match, or the
     * only (preferring still-empty) expected file with the same name. Null when
     * the project has no structure or nothing matches.
     */
    fun matchStructure(project: String, path: String): String? {
        val expected = expectedFiles(project)
        if (expected.isEmpty()) return null
        val clean = path.trim().trim('/')
        if (clean in expected) return clean
        val name = clean.substringAfterLast('/').lowercase()
        val candidates = expected.filter {
            it.endsWith("/$clean", ignoreCase = true) || it.substringAfterLast('/').lowercase() == name
        }
        if (candidates.isEmpty()) return null
        val suffixMatches = candidates.filter { it.endsWith("/$clean", ignoreCase = true) }
        val pool = suffixMatches.ifEmpty { candidates }
        val open = pool.filter { c -> fileStatus(project, c, expected) != FileStatus.COLLECTED }
        return (open.ifEmpty { pool }).sorted().first()
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
        val expectedNow = mutableSetOf<String>()
        for (entry in entries) {
            var rel = entry.path.trim('/')
            if (singleWrapper) rel = rel.removePrefix(topDirs.first()).trim('/')
            if (rel.isEmpty()) continue

            val target = File(dir, rel)
            val canonical = runCatching { target.canonicalFile }.getOrNull() ?: continue
            if (canonical != rootCanonical && !canonical.path.startsWith(rootCanonical.path + File.separator)) continue

            if (!entry.isDirectory) expectedNow += rel
            if (entry.isDirectory) {
                if (!canonical.exists() && runCatching { canonical.mkdirs() }.getOrDefault(false)) dirsMade++
            } else {
                if (canonical.exists()) continue
                canonical.parentFile?.mkdirs()
                val written = runCatching {
                    canonical.parentFile?.mkdirs()
                    canonical.writeText(placeholderHeader(rel) + (placeholderText ?: ""))
                    true
                }.getOrDefault(false)
                if (written) filesMade++
            }
        }
        recordExpected(project, expectedNow)
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
        val conflicts = files.map { it.path }.filter { path ->
            resolve(project, path)?.let { it.exists() && !isPlaceholder(it) } == true
        }.distinct()
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
        if ((!file.exists() || isPlaceholder(file)) && path !in reserved) return path
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

    /** Zip a project to a user-selected document Uri. */
    fun exportZip(project: String, destination: Uri): Boolean {
        val resolver = appContext.contentResolver
        val output = runCatching { resolver.openOutputStream(destination, "w") }.getOrNull()
            ?: return false
        val wrote = writeProjectZip(project, output)
        if (!wrote) runCatching { resolver.delete(destination, null, null) }
        return wrote
    }

    /** Write a project archive to [output], which is closed before this returns. */
    fun writeProjectZip(project: String, output: java.io.OutputStream): Boolean {
        val dir = projectDir(project)
        if (dir == null || !dir.isDirectory) {
            runCatching { output.close() }
            return false
        }
        return try {
            ZipOutputStream(output).use { zip ->
                val rootPrefix = "${sanitizeForFilename(project)}/"
                dir.walkTopDown().forEach { file ->
                    if (file == dir) return@forEach
                    val relativePath = file.relativeTo(dir).path.replace(File.separatorChar, '/')
                    val entryName = rootPrefix + relativePath
                    if (file.isDirectory) {
                        zip.putNextEntry(ZipEntry("$entryName/"))
                        zip.closeEntry()
                    } else {
                        zip.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Total size of the project's regular files. */
    fun projectBytes(project: String): Long =
        projectDir(project)?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun sanitizeForFilename(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifEmpty { "project" }

    private fun joinRelative(parent: String, child: String): String =
        if (parent.isBlank()) child else "$parent/$child"

    companion object {
        const val LOG_FILE = ".vibe-log.txt"
        const val STRUCTURE_FILE = ".vibe-structure.txt"
        const val PLACEHOLDER_MARK = "VIBE-PLACEHOLDER:"
        /** Folder (shown as a project) where copies that fit no project structure wait until deleted. */
        const val MISC_PROJECT = "_Misc"
        const val MAX_EDIT_BYTES = 2L * 1024 * 1024
    }
}

data class ScaffoldResult(val dirsCreated: Int, val filesCreated: Int, val error: String?)

class StorageException(message: String) : IOException(message)
