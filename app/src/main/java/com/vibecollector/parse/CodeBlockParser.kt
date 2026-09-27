package com.vibecollector.parse

/**
 * A single file recovered from chatbot output.
 *
 * [path] is a cleaned, relative, slash-separated path (never absolute, never
 * containing `..`). [needsNameConfirm] is true when the source text gave us no
 * usable filename, so the UI should let the user confirm/rename before saving.
 */
data class ParsedFile(
    val path: String,
    val content: String,
    val language: String? = null,
    val needsNameConfirm: Boolean = false,
)

data class ParseResult(
    val files: List<ParsedFile>,
    /** True when at least one markdown fence was found in the source text. */
    val sawFence: Boolean,
) {
    val isEmpty: Boolean get() = files.isEmpty()

    /** True when any filename was guessed rather than read from the text. */
    val hasUnnamed: Boolean get() = files.any { it.needsNameConfirm }

    /** Every top-level path segment mentioned, e.g. "src" — used for suggestions. */
    val rootSegments: List<String>
        get() = files.mapNotNull { it.path.substringBefore('/').takeIf { s -> s.isNotBlank() } }.distinct()
}

/**
 * Recovers files + filenames from arbitrary chatbot output.
 *
 * Chatbots are wildly inconsistent about how they label code, so this tries a
 * ladder of strategies per fenced block, from most explicit to least:
 *
 *  1. The fence info string  — ```python main.py  /  ```ts src/app.ts  /  ```js {file=a.js}
 *  2. Prose right above it    — "File: src/main.py", "### src/main.py", "Create src/main.py:"
 *  3. A header comment on the first content line — "// main.py"
 *  4. Comment-delimited sections inside one fence — one block, many files
 *  5. A guess derived from the language tag, with extension sniffed from content
 */
object CodeBlockParser {

    private val FENCE = Regex(
        pattern = "(?sm)^[ \\t]{0,3}(?:```|~~~)[ \\t]*([^\\n`~]*)\\n(.*?)(?:^|\\n)[ \\t]{0,3}(?:```|~~~)[ \\t]*(?:\\n|\\z)"
    )

    /** `**File: src/main.py**`, `File - main.py`, `New file: a.py` */
    private val RE_FILE_LABEL = Regex(
        "(?i)^[ \\t]*[\\*_>#]{0,4}[ \\t]*(?:new[ \\t]+)?(?:file|filename|filepath)[ \\t]*[:\\-=][ \\t]*[\\*_`\"'<>]{0,4}(.+?)[\\*_`\"'<>]{0,4}[ \\t]*$"
    )

    /** `### src/main.py` or `# src/main.py` (heading-style label) */
    private val RE_HEADING_LABEL = Regex("^[ \\t]{0,3}(?:#{1,6}|=+|\\*\\*)[ \\t]+(.+?)[ \\t]*$")

    /** `Create src/main.py:`, `Add the file app.js`, `Write main.py` */
    private val RE_VERB_LABEL = Regex(
        "(?i)^[ \\t]*[\\*_`\"'>]{0,3}[ \\t]*(?:please[ \\t]+)?(?:create|add|write|make|save|update|put)[ \\t]+(?:the[ \\t]+)?(?:new[ \\t]+)?(?:file[ \\t]+)?[\\`\"'(\\[]*([\\w.\\-]+(?:/[\\w.\\-]+)*)[\\`\"')\\]]*[ \\t]*[:.,]?[ \\t]*[\\*_`\"'<>]{0,3}$"
    )

    /** A bare path on its own line: `src/main.py`, `main.py`, `` `src/main.js` `` */
    private val RE_BARE_PATH_LINE = Regex("^[ \\t]*[\\*_`\"'\\[\\(]{0,3}[ \\t]*([^\\s\\*_`\"'\\[\\]()]+)[\\*_`\"'\\]\\)]{0,3}[ \\t]*[:.]?[ \\t]*$")

    /** `// main.py`, `# src/app.py`, `<!-- index.html -->`, `/* util.js */` */
    private val RE_PATH_COMMENT = Regex(
        "^[ \\t]*(?://|#|/\\*+|\\*|<!--|;)[ \\t]*(?:(?:file|filename)[ \\t]*[:=][ \\t]*)?(.{1,180}?)[ \\t]*(?:\\*/|-->)?[ \\t]*$"
    )

    /** Trailing decorations: `main.py (updated)`, `app.js [new]`, `a.py lines 1-20` */
    private val RE_TRAILING_NOTE = Regex("[ \\t]*(?:\\([^()]{0,60}\\)|\\[[^\\[\\]]{0,60}\\]|[ \\t]+(?:lines?|ln)[ \\t]*[\\d\\-–:, ]+)$")
    private val RE_LINE_REF = Regex(
        "(?::\\s*\\d+(?:\\s*[-–]\\s*\\d+)?|:#L\\d+(?:[-–]L?\\d+)?|#L\\d+(?:[-–]L?\\d+)?|\\bL\\d+(?:[-–]\\d+)?|\\b(?:lines?|ln)\\s*\\d+(?:\\s*[-–]\\s*\\d+)?|\\b\\d+\\s*[-–]\\s*\\d+)[ \\t]*$"
    )

    private val ILLEGAL_PATH_CHARS = Regex("[\\u0000-\\u001F*?\"<>|]")
    private val RE_DRIVE_PREFIX = Regex("^[A-Za-z]:[\\\\/]")

    private val LANG_TO_EXT = mapOf(
        "kotlin" to "kt", "kt" to "kt", "java" to "java", "python" to "py", "py" to "py",
        "javascript" to "js", "js" to "js", "jsx" to "jsx", "node" to "js",
        "typescript" to "ts", "ts" to "ts", "tsx" to "tsx",
        "html" to "html", "htm" to "html", "xml" to "xml", "svg" to "svg",
        "css" to "css", "scss" to "scss", "sass" to "sass", "less" to "less",
        "json" to "json", "jsonc" to "json", "json5" to "json",
        "yaml" to "yml", "yml" to "yml", "toml" to "toml", "ini" to "ini", "cfg" to "cfg",
        "properties" to "properties", "env" to "env", "editorconfig" to "editorconfig",
        "markdown" to "md", "md" to "md", "mdx" to "mdx", "rst" to "rst", "txt" to "txt", "text" to "txt", "plain" to "txt",
        "sh" to "sh", "bash" to "sh", "shell" to "sh", "zsh" to "sh", "console" to "sh", "shell-session" to "sh",
        "sql" to "sql", "graphql" to "graphql", "gql" to "graphql",
        "go" to "go", "golang" to "go", "rust" to "rs", "rs" to "rs", "swift" to "swift",
        "c" to "c", "h" to "h", "cpp" to "cpp", "c++" to "cpp", "cc" to "cpp", "hpp" to "hpp",
        "csharp" to "cs", "cs" to "cs", "objective-c" to "m", "objc" to "m",
        "php" to "php", "ruby" to "rb", "rb" to "rb", "perl" to "pl", "lua" to "lua",
        "dart" to "dart", "r" to "r", "vue" to "vue", "svelte" to "svelte",
        "gradle" to "gradle", "maven" to "xml", "dockerfile" to "dockerfile", "docker" to "dockerfile",
        "makefile" to "mk", "make" to "mk", "cmake" to "cmake",
        "proto" to "proto", "diff" to "diff", "patch" to "patch", "log" to "log",
        "http" to "http", "rest" to "http", "latex" to "tex", "tex" to "tex", "bibtex" to "bib",
    )

    private val LANG_ALIASES = mapOf(
        "c++" to "cpp", "c#" to "csharp", "cs" to "csharp", "golang" to "go",
        "node.js" to "js", "nodejs" to "js", "ecmascript" to "js", "es6" to "js",
        "py" to "python", "py3" to "python", "python3" to "python",
        "ts" to "typescript", "sh" to "bash", "shell" to "bash", "yml" to "yaml",
        "text" to "plain", "txt" to "plain", "plaintext" to "plain",
    )

    /** Language tags that are never filenames. */
    private val NOT_A_FILENAME = setOf(
        "code", "text", "plain", "plaintext", "example", "output", "result", "console",
        "diff", "patch", "log", "json", "yaml", "xml", "html", "css", "javascript", "typescript",
    )

    fun parse(raw: String): ParseResult {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim('\uFEFF', ' ', '\n')
        if (text.isBlank()) return ParseResult(emptyList(), sawFence = false)

        val files = mutableListOf<ParsedFile>()
        val fence = FENCE.find(text)
        var sawFence = false

        if (fence != null) {
            sawFence = true
            var cursor = 0
            while (true) {
                val m = FENCE.find(text, cursor) ?: break
                val info = m.groupValues[1].trim()
                val body = m.groupValues[2]
                cursor = m.range.last + 1

                if (body.isBlank()) continue
                val before = text.substring(0, m.range.first)
                val language = normalizeLanguage(info)

                // One fence can actually hold several path-commented files. This must be
                // attempted on the raw body, before any header comment is stripped —
                // the top comment is itself the first file's marker.
                val sections = splitCommentSections(body, language)
                if (sections.size > 1) {
                    sections.forEach { (p, c) ->
                        files += ParsedFile(path = p, content = c, language = language)
                    }
                    continue
                }

                val named = resolvePath(before, info, body, language)
                val cleanedBody = if (named != null && headerCommentMatch(body) != null) {
                    stripLeadingHeaderComment(body)
                } else {
                    body
                }

                if (named != null) {
                    files += ParsedFile(
                        path = named,
                        content = cleanedBody.trimEnd(),
                        language = language,
                    )
                } else {
                    val n = files.count { it.needsNameConfirm } + 1
                    files += ParsedFile(
                        path = fallbackName(n, language, cleanedBody),
                        content = cleanedBody.trimEnd(),
                        language = language,
                        needsNameConfirm = true,
                    )
                }
            }
        }

        if (files.isEmpty() && !sawFence) {
            // No fences at all: the user probably copied a raw snippet, possibly
            // with the filename on the first line.
            val body = text
            val named = fromFirstLine(body) ?: fromProseAbove(body)
            if (named != null) {
                files += ParsedFile(
                    path = named,
                    content = stripLeadingHeaderComment(body).trimEnd(),
                    language = null,
                )
            } else {
                files += ParsedFile(
                    path = fallbackName(1, null, body),
                    content = body.trimEnd(),
                    language = null,
                    needsNameConfirm = true,
                )
            }
        }

        return ParseResult(dedupe(files), sawFence)
    }

    // ---------------------------------------------------------------- path resolution

    private fun resolvePath(before: String, info: String, body: String, language: String?): String? {
        fromInfoString(info)?.let { return it }
        fromProseAbove(before)?.let { return it }
        headerCommentMatch(body)?.let { return it }
        return null
    }

    /** The first non-blank line of an unfenced snippet, if it is just a path. */
    private fun fromFirstLine(body: String): String? {
        val first = body.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        if (first.length > 160) return null
        RE_BARE_PATH_LINE.find(first)?.let { m -> return cleanPath(m.groupValues[1]) }
        return null
    }

    /** ```python main.py  ·  ```ts src/app.ts  ·  ```js {file=a.js}  ·  ```jsx title="App.jsx" */
    private fun fromInfoString(info: String): String? {
        var s = info.trim()
        if (s.isEmpty()) return null
        s = s.replace(Regex("^\\{+"), " ").replace(Regex("\\}+$"), " ")
        s = s.replace(Regex("(?:^|\\s)(?:file|filename|filepath|title|name|path)\\s*=", RegexOption.IGNORE_CASE), " ")
        s = s.replace('=', ' ').replace(',', ' ').replace(';', ' ')
        val tokens = s.split(Regex("[\\s]+")).map { it.trim('`', '"', '\'', '(', ')', '[', ']', '<', '>', '*', '_') }.filter { it.isNotEmpty() }
        // Prefer a token that actually looks like a file, not a bare language name.
        tokens.firstOrNull { looksLikeFilename(it) && !isLanguageName(it) }?.let { return cleanPath(it) }
        return null
    }

    /** Look at the nearest few non-blank lines above the fence for a label. */
    private fun fromProseAbove(before: String): String? {
        if (before.isEmpty()) return null
        val lines = before.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        var checked = 0
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            checked++
            if (checked > 6) break
            if (line.length > 160) continue

            RE_FILE_LABEL.find(line)?.let { m ->
                cleanPath(m.groupValues[1])?.let { return it }
            }
            RE_VERB_LABEL.find(line)?.let { m ->
                cleanPath(m.groupValues[1])?.let { return it }
            }
            RE_HEADING_LABEL.find(line)?.let { m ->
                cleanPath(m.groupValues[1])?.let { return it }
            }
            RE_BARE_PATH_LINE.find(line)?.let { m ->
                cleanPath(m.groupValues[1])?.let { return it }
            }
            // A plain sentence that ends in a filename: "…here is the updated main.py:"
            trailingFilenameInProse(line)?.let { return it }
        }
        return null
    }

    /** "Here is the updated src/main.py:" -> "src/main.py" */
    private fun trailingFilenameInProse(line: String): String? {
        val m = Regex("([\\w.\\-]+(?:/[\\w.\\-]+)*\\.[A-Za-z0-9]{1,8})[\\s\\*_`\\\"':.]*$").find(line) ?: return null
        val cand = m.groupValues[1]
        if (cand.length < 3) return null
        // Guard against "e.g." / "Node.js" style false positives inside long prose.
        if (line.length > 120) return null
        return cleanPath(cand)
    }

    private fun headerCommentMatch(body: String): String? {
        val first = body.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val m = RE_PATH_COMMENT.find(first) ?: return null
        return cleanPath(m.groupValues[1])
    }

    private fun stripLeadingHeaderComment(body: String): String {
        val lines = body.lines().toMutableList()
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        if (i < lines.size && headerCommentMatch(lines[i]) != null) lines.removeAt(i)
        return lines.joinToString("\n")
    }

    /** Split a single fence into several files on strict path-comment lines. */
    private fun splitCommentSections(body: String, language: String?): List<Pair<String, String>> {
        val lines = body.lines()
        val marks = mutableListOf<Pair<Int, String>>()
        lines.forEachIndexed { i, line ->
            if (line.isBlank()) return@forEachIndexed
            val m = RE_PATH_COMMENT.find(line) ?: return@forEachIndexed
            val raw = m.groupValues[1]
            // Require slash or a plausible extension: "// eslint-disable" must not match.
            val plausible = raw.contains('/') || Regex("\\.[A-Za-z0-9]{1,8}$").containsMatchIn(raw)
            if (!plausible) return@forEachIndexed
            cleanPath(raw)?.let { marks += i to it }
        }
        // Require at least two *content-bearing* sections to bother splitting.
        if (marks.size < 2) return emptyList()
        // A shared comment banner at the top of the block would create a bogus first file.
        if (marks.first().first != lines.indexOfFirst { it.isNotBlank() }) return emptyList()

        val out = mutableListOf<Pair<String, String>>()
        marks.forEachIndexed { idx, (start, path) ->
            val end = marks.getOrNull(idx + 1)?.first ?: lines.size
            val chunk = lines.subList(start + 1, end)
                .joinToString("\n")
                .trim('\n', ' ', '\t')
            if (chunk.isNotBlank()) out += path to chunk
        }
        return if (out.size >= 2) out else emptyList()
    }

    // ---------------------------------------------------------------- naming helpers

    private fun fallbackName(index: Int, language: String?, body: String): String {
        val ext = language?.let { LANG_TO_EXT[it] } ?: sniffExtension(body)
        return "snippet-$index.$ext"
    }

    /** Best-effort language detection from the raw content. */
    private fun sniffExtension(body: String): String {
        val head = body.take(400)
        val looksLike = { p: String -> Regex(p, setOf(RegexOption.MULTILINE)).containsMatchIn(head) }
        val jsish = looksLike("^\\s*(export\\s+default|import\\s+.*\\s+from\\s+|const\\s+\\w+\\s*=|let\\s+\\w+\\s*=|var\\s+\\w+\\s*=|function\\s+\\w+\\s*\\()")
        val tsish = Regex("\\b(interface|type|enum)\\s+\\w+", RegexOption.MULTILINE).containsMatchIn(head) ||
            Regex(":\\s*(string|number|boolean|any|void|unknown)\\b").containsMatchIn(head)
        return when {
            head.trimStart().startsWith("<?xml") -> "xml"
            head.contains("<!DOCTYPE html", true) || head.contains("<html", true) -> "html"
            Regex("^<svg[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(head.trimStart()) -> "svg"
            head.trimStart().startsWith("#!") && Regex("\\b(bash|sh|env python|node)\\b").containsMatchIn(head.take(80)) ->
                if (head.contains("python")) "py" else "sh"
            looksLike("^\\s*package\\s+[\\w.]+\\s*;?\\s*$") -> "java"
            looksLike("^\\s*from\\s+[\\w.]+\\s+import|^\\s*import\\s+\\w+") && looksLike("^\\s*def\\s+\\w+") -> "py"
            looksLike("^\\s*def\\s+\\w+\\s*\\(|^\\s*class\\s+\\w+.*:\\s*$") -> "py"
            jsish && tsish -> "ts"
            jsish -> if (head.contains("=>") || head.contains(';')) "js" else "js"
            looksLike("^\\s*fun\\s+main\\(|^\\s*fun\\s+\\w+\\s*\\(") -> "kt"
            looksLike("^\\s*func\\s+\\w+\\s*\\(") -> "go"
            looksLike("^\\s*fn\\s+\\w+\\s*\\(") -> "rs"
            looksLike("^\\s*public\\s+(static\\s+)?(void|class|int)\\b|^\\s*System\\.out\\.print") -> "java"
            Regex("\\{\\s*\"[\\w-]+\"\\s*:", RegexOption.MULTILINE).containsMatchIn(head) -> "json"
            Regex("^\\s*\\{?\\s*[\\w.\"'-]+\\s*=", RegexOption.MULTILINE).containsMatchIn(head) &&
                Regex("^\\s*[\\w.\"'-]+\\s*:\\s", RegexOption.MULTILINE).containsMatchIn(head) -> "yaml"
            Regex("^\\s*(SELECT|INSERT\\s+INTO|CREATE\\s+TABLE|UPDATE\\s+\\w+\\s+SET)\\b", RegexOption.IGNORE_CASE).containsMatchIn(head) -> "sql"
            Regex("^\\s*(<\\?php|use\\s+strict|require_once)", RegexOption.MULTILINE).containsMatchIn(head) -> "php"
            Regex("^\\s*(module\\s+\\w+|local\\s+\\w+\\s*=|function\\s+\\w+\\s*\\()", RegexOption.MULTILINE).containsMatchIn(head) -> "lua"
            else -> "txt"
        }
    }

    private fun normalizeLanguage(info: String): String? {
        if (info.isBlank()) return null
        val first = info.trim().split(Regex("[\\s,;:={]")).first().trim('`', '"', '\'', '*', '_', '.').lowercase()
        if (first.isEmpty()) return null
        val mapped = LANG_ALIASES[first] ?: first
        return if (LANG_TO_EXT.containsKey(mapped) || mapped in NOT_A_FILENAME) mapped else null
    }

    private fun isLanguageName(token: String): Boolean {
        val t = token.lowercase()
        return LANG_TO_EXT.containsKey(t) || NOT_A_FILENAME.contains(t) ||
            (t in LANG_ALIASES)
    }

    private fun looksLikeFilename(token: String): Boolean {
        if (token.isBlank() || token.length > 200) return false
        val t = token.lowercase()
        if (t in NOT_A_FILENAME) return false
        if (t.startsWith("http://") || t.startsWith("https://")) return false
        if (t.contains('\\') || t.startsWith("~")) return false
        // A space is only tolerated in a single-segment name like "My Component.jsx".
        if (t.any { it.isWhitespace() } && !t.contains('/')) {
            return Regex("^[^/]{1,60}\\.[A-Za-z0-9]{1,10}$").containsMatchIn(token)
        }
        if (t.any { it.isWhitespace() }) return false
        // Needs either a directory separator or a real-looking extension.
        val hasExt = Regex("\\.[A-Za-z0-9_]{1,10}$").containsMatchIn(t)
        return hasExt || t.contains('/')
    }

    /**
     * Normalise a candidate path. Returns null if the candidate cannot be a path.
     *
     * This is the security boundary: the result is joined onto a project root, so
     * traversal segments and absolute paths must never survive. Order matters —
     * decorations are stripped *before* characters are sanitised, otherwise a
     * mangled `app.py-12` no longer matches any pattern.
     */
    fun cleanPath(raw: String): String? {
        if (raw.isBlank()) return null
        var s = raw.trim()
        if (s.isEmpty()) return null

        // 1. URLs are never local paths. Reject before anything is mangled.
        val lower = s.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ftp://")) return null
        if (s.startsWith("\\\\")) return null // UNC share

        // 2. Normalise separators first so that backslash traversal is catchable.
        s = s.replace('\\', '/')
        s = RE_DRIVE_PREFIX.replace(s, "")

        // 3. Leading "./" and duplicate slashes.
        s = s.replace(Regex("(^|/)\\./"), "$1")
        s = s.replace(Regex("/+"), "/").trimStart('/')

        // 4. Trailing decorations, only when what remains is still a filename.
        s = stripDecoration(s)

        // 5. Label prefixes and wrapping decoration.
        s = s.replace(Regex("^[ \\t]*(?:new[ \\t]+)?(?:file|filename|filepath|path)[ \\t]*[:=][ \\t]*", RegexOption.IGNORE_CASE), "")
        s = s.trim().trim('`', '"', '\'', '*', '_', '~', '=', '<', '>', '#')
        s = s.trim().trimEnd(':', '.', ',', ';', '!').trim()
        if (s.isEmpty()) return null

        // 6. Collapse traversal. Segments are dropped, never resolved upward, so
        //    "../../etc/passwd" becomes "etc/passwd" inside the project root.
        val parts = ArrayList<String>()
        for (segment in s.split('/')) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") continue
            if (segment.length > 80) return null
            if (segment.any { it.code < 32 }) return null
            parts += segment
        }
        if (parts.isEmpty() || parts.size > 24) return null
        if (s.length > 200) return null

        // 7. Sanitise remaining illegal characters, then drop empty segments again.
        val cleaned = parts.map { it.replace(ILLEGAL_PATH_CHARS, "-").trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        if (cleaned.isEmpty() || cleaned.size > 24) return null

        val joined = cleaned.joinToString("/")
        if (joined.length > 200) return null
        if (joined.length > 0 && joined.any { it.code < 32 }) return null

        // A space is only legal in a single-segment name like "My Component.jsx".
        if (joined.any { it.isWhitespace() } && cleaned.size > 1) return null

        return if (looksLikeFilename(joined)) joined else null
    }

    /** Strip ` (note)`, ` [note]`, `:12`, `#L4`, ` lines 1-20` when a filename survives. */
    private fun stripDecoration(s: String): String {
        var out = s
        for (pass in 0 until 2) {
            val m = RE_TRAILING_NOTE.find(out) ?: RE_LINE_REF.find(out) ?: break
            val head = out.substring(0, m.range.first).trim()
            if (head.isEmpty()) break
            val keepsName = looksLikeFilename(head) ||
                (head.contains('.') && !head.contains(' ')) ||
                (head.contains('/') && !head.contains(' '))
            if (!keepsName) break
            out = head
        }
        return out.trim()
    }

    private fun dedupe(files: List<ParsedFile>): List<ParsedFile> {
        val byPath = LinkedHashMap<String, ParsedFile>()
        for (f in files) {
            val key = f.path.lowercase()
            val prev = byPath[key]
            // Prefer the entry whose filename was actually stated in the text.
            when {
                prev == null -> byPath[key] = f
                prev.needsNameConfirm && !f.needsNameConfirm -> byPath[key] = f
                !prev.needsNameConfirm && f.needsNameConfirm -> Unit
                f.content.length > prev.content.length -> byPath[key] = f
            }
        }
        return byPath.values.toList()
    }
}
