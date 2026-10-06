package com.vibecollector.data

import kotlinx.serialization.Serializable

/**
 * Colour-coded state of a project file.
 *  - COLLECTED: real content has been collected (blue)
 *  - PLACEHOLDER: generated from the structure, waiting for code (yellow)
 *  - MISSING / BROKEN: expected by the structure but absent or unreadable (red)
 */
enum class FileStatus { COLLECTED, PLACEHOLDER, MISSING, BROKEN }

/** A file on disk inside the app's project storage. */
data class FileNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
    val childCount: Int = 0,
    val modifiedAt: Long = 0L,
    val status: FileStatus = FileStatus.COLLECTED,
)

/** A top-level project folder. */
data class Project(
    val name: String,
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
    val modifiedAt: Long,
)

/** One file recovered from a clipboard payload, pending user confirmation. */
@Serializable
data class CapturedFile(
    val path: String,
    val content: String,
    val language: String? = null,
    /** Filename was guessed, so the UI should confirm before writing. */
    val needsNameConfirm: Boolean = false,
) {
    val lineCount: Int get() = content.count { it == '\n' } + 1
    val byteCount: Int get() = content.toByteArray(Charsets.UTF_8).size
}

/**
 * A clipboard payload that was parsed and is waiting for a decision.
 *
 * Persisted as JSON so a pending decision survives the process being killed,
 * which happens routinely while the user is still reading the chatbot.
 */
@Serializable
data class PendingCapture(
    val id: String,
    val createdAt: Long,
    val sourceApp: String = "",
    val sourceLabel: String = "",
    val files: List<CapturedFile>,
    val project: String = "",
    /** Set when the payload was a bare project structure rather than code. */
    val scaffoldedDirs: Int = 0,
    val scaffoldedFiles: Int = 0,
) {
    val fileCount: Int get() = files.size
    val totalBytes: Int get() = files.sumOf { it.byteCount }
    val hasUnnamed: Boolean get() = files.any { it.needsNameConfirm }
    val isScaffoldOnly: Boolean get() = files.isEmpty() && (scaffoldedDirs > 0 || scaffoldedFiles > 0)
}

/** Result of writing a capture, for the summary notification/toast. */
data class WriteOutcome(
    val written: List<String>,
    val skipped: List<String>,
    val error: String? = null,
    val conflicts: List<String> = emptyList(),
) {
    val ok: Boolean get() = error == null && conflicts.isEmpty() && written.isNotEmpty()
}

enum class ExistingFilePolicy {
    ASK,
    KEEP_BOTH,
    OVERWRITE,
}
