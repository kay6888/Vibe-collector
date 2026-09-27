package com.vibecollector.parse

import java.util.Locale

data class ScaffoldEntry(
    val path: String,
    val isDirectory: Boolean,
    val note: String? = null,
)

/**
 * Turns a pasted project plan into a concrete file/dir tree.
 *
 * Understands the formats assistants actually emit:
 *  - ASCII trees with box-drawing characters
 *  - bullet and numbered lists, with optional trailing descriptions
 *  - indented plain lists
 *  - a single comma-separated line
 *  - markdown links `[README.md](README.md)`
 *
 * Prose is rejected by [looksLikePath] rather than by a fixed regex, because
 * "First, we'll add the config" must not become a folder called
 * "First, we'll add the config".
 */
object TreeParser {

    private val BULLET = Regex("^[ \\t]*(?:[-*+>\\u2022\\u00b7\\u25aa\\u2013]|[0-9]{1,3}[.)])[ \\t]+(.*)$")
    private val TREE_PREFIX = Regex("^[ \\t\\u2502\\u251c\\u2514\\u2500\\u2551\\u2570/\\\\|`'\\u00b7\\u2022*+-]+[ \\t]*")
    private val NOTE_SEP = Regex("[ \\t]*(?:\\s[-\\u2013\\u2014]{1,2}\\s|\\s*[:\\u2236]\\s|\\s+#\\s|\\s+//\\s)[ \\t]*")
    private val MD_LINK = Regex("^\\[([^\\]]{0,120})\\]\\(([^)]{0,200})\\)$")
    private val MD_LINK_INLINE = Regex("\\[([^\\]]{0,120})\\]\\([^)\\s]{0,200}\\)")
    private val QUOTED = Regex("^[\\`\"'](.*)[\\`\"']$")

    private val DOT_SPLIT = Regex(".")

    /** Directories that are noise in a plan but real on disk. */
    private val ALWAYS_DIR = setOf(
        "src", "app", "lib", "libs", "test", "tests", "res", "assets", "public", "dist", "build",
        "components", "utils", "services", "models", "views", "screens", "pages", "routes",
        "styles", "config", "docs", "scripts", "types", "api", "data", "store", "hooks",
        "android", "ios", "web", "server", "client", "shared", "common", "core", "domain",
    )

    fun parse(raw: String): List<ScaffoldEntry> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return emptyList()

        val rawLines = text.split('\n')

        // A single line of comma/semicolon separated paths.
        val nonBlank = rawLines.count { it.isNotBlank() }
        if (nonBlank == 1 && (rawLines[0].contains(',') || rawLines[0].contains(';'))) {
            val expanded = rawLines[0].split(',', ';').mapIndexedNotNull { i, part ->
                val p = extractPath(part.trim()) ?: return@mapIndexedNotNull null
                ScaffoldEntry(p, isDirectory = false)
            }
            return withParents(expanded)
        }

        val staged = mutableListOf<Pair<ScaffoldEntry, Int>>()
        rawLines.forEachIndexed { index, originalLine ->
            val depth = visualDepth(originalLine)
            val line = originalLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("```") || line.startsWith("~~~")) return@forEachIndexed

            val afterBullet = BULLET.find(line)?.groupValues?.get(1) ?: line
            val candidateSource = TREE_PREFIX.replace(afterBullet, "").trim()
            if (candidateSource.isEmpty()) return@forEachIndexed

            val (pathText, note) = splitNote(candidateSource)
            val path = extractPath(pathText) ?: return@forEachIndexed

            // A trailing slash is an explicit "this is a directory" signal and must
            // survive normalisation, otherwise extensionless dirs vanish and their
            // children lose their parents.
            val explicitDir = pathText.trimEnd().endsWith("/")
            val isDir = explicitDir || (!hasExtension(path) && !looksLikeFileLeaf(path))
            staged += ScaffoldEntry(path, isDir, note) to depth
        }

        // Rebuild the hierarchy from indentation. A flat list all at indent 0 stays
        // flat; an ASCII tree or indented list gets its nesting back.
        val stack = ArrayList<Pair<Int, String>>()
        val resolved = ArrayList<ScaffoldEntry>(staged.size)
        staged.forEachIndexed { i, stagedEntry ->
            val entry = stagedEntry.first
            val indent = stagedEntry.second
            val next = staged.getOrNull(i + 1)
            val isDir = entry.isDirectory ||
                (next != null && next.second > indent && !hasExtension(next.first.path))

            while (stack.isNotEmpty() && stack[stack.size - 1].first >= indent) stack.removeAt(stack.size - 1)
            val parent = stack.lastOrNull()?.second.orEmpty()
            val full = if (parent.isEmpty()) entry.path else "$parent/${entry.path}"

            val out = entry.copy(path = full, isDirectory = isDir)
            resolved += out
            if (isDir) stack += indent to full
        }

        return withParents(resolved)
    }

    /**
     * Nesting level of a line.
     *
     * An ASCII tree encodes depth in `|   ` continuation bars rather than in
     * leading spaces, so measuring leading whitespace alone would flatten every
     * tree to depth 0. Counting continuation bars in 4-column chunks, combined
     * with the raw leading-space count, handles both ASCII trees and plain
     * indented lists.
     */
    private fun visualDepth(line: String): Int {
        var i = 0
        var depth = 0
        var chunk = 0
        while (i < line.length) {
            when (line[i]) {
                '\t' -> { chunk += 4; i++ }
                ' ', '\u00a0' -> { chunk++; i++ }
                '\u2502', '\u2551', '\u2570', '|' -> { chunk++; i++ }
                '\u251c', '\u2514', '\u2500', '\u2571', '\u2572', '+', '*', '-', '\u2022', '\u00b7', '/', '\\' -> break
                else -> break
            }
            if (chunk >= 4) { depth++; chunk = 0 }
        }
        val spaces = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        return maxOf(depth, spaces)
    }

    /**
     * Extract the leading path token from a line, tolerating trailing prose and
     * markdown decoration. Returns null when the line is prose.
     */
    private fun extractPath(text: String): String? {
        var s = text.trim()
        if (s.isEmpty()) return null
        // Inline markdown links anywhere in the line reduce to their label.
        s = MD_LINK_INLINE.replace(s) { m -> m.groupValues[1].trim().ifEmpty { m.groupValues[2].trim() } }
        s = s.replace(Regex("""^[^\w\-./`"']+"""), "").trim()
        if (s.isEmpty()) return null

        // A whole line that is just a markdown link -> prefer the label, then the target.
        MD_LINK.find(s)?.let { m ->
            val label = m.groupValues[1].trim()
            val target = m.groupValues[2].trim()
            val fromLabel = normalize(label) ?: normalize(target)
            if (fromLabel != null) return fromLabel
        }

        // Trim trailing prose: "main.py — the entry point" or "main.py: the entry point".
        var token = s
        val noteMatch = NOTE_SEP.find(token)
        if (noteMatch != null && noteMatch.range.first > 0) {
            token = token.substring(0, noteMatch.range.first)
        } else {
            // Plain space-separated trailing words, but never break "My File.js".
            if (token.count { it == ' ' } > 0 && !token.endsWith("/")) {
                val spaceIdx = token.indexOf(' ')
                val head = token.substring(0, spaceIdx)
                val tail = token.substring(spaceIdx + 1)
                if (head.contains('/') || head.contains('.')) {
                    token = if (looksLikePath(head)) head else token
                } else if (tail.startsWith("-") || tail.startsWith("\u2014")) {
                    token = head
                }
            }
        }
        token = token.trim().trimEnd(',', ';')
        QUOTED.find(token)?.let { token = it.groupValues[1] }
        return normalize(token)
    }

    private fun splitNote(text: String): Pair<String, String?> {
        val m = NOTE_SEP.find(text)
        if (m != null && m.range.first > 0) {
            val pathPart = text.substring(0, m.range.first).trim()
            val note = text.substring(m.range.last + 1).trim().takeIf { it.isNotEmpty() }
            return pathPart to note
        }
        return text to null
    }

    /** Clean + validate a path, returning null for prose. */
    private fun normalize(raw: String): String? {
        var s = raw.trim().trim('`', '"', '\'', '*', '_', '[', ']', '(', ')')
        if (s.isEmpty()) return null
        s = s.replace(Regex("[\\u2502\\u251c\\u2514\\u2500]"), "").trim()
        s = s.replace('\\', '/')
        s = s.trimStart('/').replace(Regex("^\\./"), "")
        s = s.replace(Regex("/+"), "/")
        if (s.isEmpty()) return null
        if (s.length > 180) return null

        val parts = s.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.size > 20) return null
        if (parts.any { it == ".." }) return null
        if (parts.any { it.length > 70 || it.any { c -> c.code < 32 } }) return null
        if (parts.any { Regex("[\\\\:*?\"<>|]").containsMatchIn(it) }) return null

        val joined = parts.joinToString("/")
        if (joined.startsWith("http://") || joined.startsWith("https://")) return null
        if (joined.contains(" ")) {
            // Allow spaces only when the whole thing is a single plausible filename.
            if (parts.size > 1) return null
            if (!Regex("^[^/]{1,60}\\.[A-Za-z0-9]{1,8}$").containsMatchIn(joined)) return null
        }
        if (!looksLikePath(joined)) return null
        return joined
    }

    private fun looksLikePath(s: String): Boolean {
        if (s.isBlank()) return false
        val t = s.trim()
        if (t.endsWith("/")) return true
        if (t.contains('/')) {
            val last = t.substringAfterLast('/')
            if (last.isEmpty()) return true
            return hasExtension(last) || last in ALWAYS_DIR || !last.contains(' ')
        }
        if (hasExtension(t)) return true
        if (SPECIAL_NAMES.contains(t.lowercase())) return true
        // A bare slug such as `my-app` or `node_modules` is a plausible name; a
        // sentence like `Here is the structure` is not. Capitalised single words
        // like `Dockerfile` are real filenames.
        return SLUG.containsMatchIn(t) || CAMEL_FILE.containsMatchIn(t)
    }

    private val SLUG = Regex("^[a-z0-9][a-z0-9._-]{0,48}$")
    private val CAMEL_FILE = Regex("^[A-Z][A-Za-z0-9]{2,30}$")

    private val SPECIAL_NAMES = setOf(
        "dockerfile", "makefile", "procfile", "gemfile", "rakefile", "vagrantfile",
        "license", "licence", "readme", "changelog", "codeowners", "notice", "authors",
        "contributing", "go.mod", "go.sum", "cargo.lock", "jenkinsfile", "brewfile",
    )

    private fun looksLikeFileLeaf(name: String): Boolean {
        if (hasExtension(name)) return true
        if (name in ALWAYS_DIR) return true
        if (SPECIAL_NAMES.contains(name.lowercase())) return true
        return false
    }

    private fun hasExtension(s: String): Boolean {
        val last = if (s.contains('/')) s.substringAfterLast('/') else s
        if (!last.contains('.')) return false
        val ext = last.substringAfterLast('.', "")
        if (ext.isEmpty() || ext.length > 10) return false
        if (!ext.all { it.isLetterOrDigit() || it == '+' }) return false
        // "Node.js" and "README" style words are not extensions; "1." at line end is not either.
        if (DOT_SPLIT.find(ext) == null) return false
        return ext.first().isLetter()
    }

    /** Add every ancestor directory implied by the file paths, and dedupe. */
    private fun withParents(entries: List<ScaffoldEntry>): List<ScaffoldEntry> {
        if (entries.isEmpty()) return emptyList()
        // dir path -> entry, so a description written against a real tree node
        // survives instead of being replaced by a bare implicit parent.
        val dirs = LinkedHashMap<String, ScaffoldEntry>()
        val files = LinkedHashMap<String, ScaffoldEntry>()

        for (e in entries) {
            val parts = e.path.split('/')
            for (i in 1 until parts.size) {
                val implied = parts.subList(0, i).joinToString("/")
                dirs.putIfAbsent(implied, ScaffoldEntry(implied, isDirectory = true))
            }
            if (e.isDirectory) dirs[e.path.trimEnd('/')] = e
            else files[e.path] = e
        }

        val out = mutableListOf<ScaffoldEntry>()
        for (d in dirs.keys.sortedWith(compareBy({ it.count { c -> c == '/' } }, { it }))) {
            out += dirs.getValue(d)
        }
        // A path claimed as both a file and a directory is a directory.
        for ((path, e) in files) {
            if (path in dirs) continue
            out += e
        }
        return out
    }

    /** Turn entries into an ASCII tree, used to preview the scaffold. */
    fun toAsciiTree(entries: List<ScaffoldEntry>, rootName: String? = null): String {
        if (entries.isEmpty()) return ""
        val dirs = entries.filter { it.isDirectory }
        val files = entries.filter { !it.isDirectory }
        val sb = StringBuilder()
        if (rootName != null) sb.append(rootName).append('\n')

        val fileByParent = files.groupBy { it.path.substringBeforeLast('/', "") }
        val dirByParent = dirs.groupBy { it.path.substringBeforeLast('/', "") }

        fun walk(parent: String, prefix: String) {
            val childDirs = dirByParent[parent].orEmpty().sortedBy { it.path }
            val childFiles = fileByParent[parent].orEmpty().sortedBy { it.path }
            val all = childDirs.map { it to true } + childFiles.map { it to false }
            all.forEachIndexed { i, (entry, isDir) ->
                val last = i == all.size - 1
                val branch = if (last) "`-- " else "|-- "
                val name = if (isDir) entry.path.substringAfterLast('/') + "/" else entry.path.substringAfterLast('/')
                val note = entry.note?.let { "  // $it" } ?: ""
                sb.append(prefix).append(branch).append(name).append(note).append('\n')
                if (isDir) walk(entry.path, prefix + if (last) "    " else "|   ")
            }
        }
        walk("", "")
        return sb.toString().trimEnd()
    }

    /** True when the text looks like it contains any usable structure. */
    fun looksLikeStructure(raw: String): Boolean = parse(raw).isNotEmpty()

    @Suppress("unused")
    private fun unusedLocale() = Locale.getDefault()
}
