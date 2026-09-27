package com.vibecollector.parse

import org.junit.Test

/**
 * Regression suite for the two pure-Kotlin parsers. Ported from the standalone
 * kotlinc harness; every check throws on first failure so a red build names the
 * exact behaviour that regressed.
 */
private fun check(name: String, cond: Boolean, detail: Any? = "") {
    if (!cond) throw AssertionError("$name :: $detail")
}

private fun eq(name: String, actual: Any?, expected: Any?) {
    if (actual != expected) throw AssertionError("$name :: expected=<$expected> actual=<$actual>")
}

private fun paths2(e: List<ScaffoldEntry>) = e.joinToString(",") { if (it.isDirectory) it.path + "/" else it.path }

private fun entry(entries: List<ScaffoldEntry>, path: String) =
    entries.firstOrNull { it.path == path } ?: ScaffoldEntry("<<MISSING:$path>>", isDirectory = false, note = null)

class ParserTest {
    @Test
    fun parserSuite() {
        println("\n=== CodeBlockParser: fenced blocks with filename in info string ===")
        run {
            val r = CodeBlockParser.parse(
                """
                Here's the code:
                ```python main.py
                print("hi")
                ```
                """.trimIndent()
            )
            eq("single block -> path", r.files.single().path, "main.py")
            eq("content preserved", r.files.single().content, "print(\"hi\")")
            check("no name confirmation needed", !r.files.single().needsNameConfirm)
        }
        run {
            val r = CodeBlockParser.parse("```ts src/app/services/user.service.ts\nexport const x = 1\n```")
            eq("nested path from info string", r.files.single().path, "src/app/services/user.service.ts")
        }
        run {
            val r = CodeBlockParser.parse("```js {file=src/utils/helpers.js}\nmodule.exports = {}\n```")
            eq("brace/equals info string", r.files.single().path, "src/utils/helpers.js")
        }
        run {
            val r = CodeBlockParser.parse("```jsx title=\"src/components/Button.jsx\"\nexport default () => null\n```")
            eq("quoted title= info string", r.files.single().path, "src/components/Button.jsx")
        }
        run {
            val r = CodeBlockParser.parse("```csharp Program.cs\nclass P {}\n```")
            eq("lang + filename", r.files.single().path, "Program.cs")
        }
    
        println("\n=== CodeBlockParser: prose labels above the fence ===")
        run {
            val r = CodeBlockParser.parse(
                """
                **File: src/main/kotlin/App.kt**
                ```kotlin
                fun main() {}
                ```
                """.trimIndent()
            )
            eq("bold File: label", r.files.single().path, "src/main/kotlin/App.kt")
        }
        run {
            val r = CodeBlockParser.parse(
                """
                ### src/models/user.model.js
                ```js
                module.exports = {};
                ```
                """.trimIndent()
            )
            eq("heading label", r.files.single().path, "src/models/user.model.js")
        }
        run {
            val r = CodeBlockParser.parse(
                """
                Create `server/config.py`:
                ```python
                DEBUG = True
                ```
                """.trimIndent()
            )
            eq("verb label + backticks", r.files.single().path, "server/config.py")
        }
        run {
            val r = CodeBlockParser.parse(
                """
                Here is the updated `src/index.js`:
                ```js
                console.log(1)
                ```
                """.trimIndent()
            )
            eq("trailing filename in prose", r.files.single().path, "src/index.js")
        }
        run {
            val r = CodeBlockParser.parse(
                """
                1. `package.json`
                ```json
                {"name":"app"}
                ```
                """.trimIndent()
            )
            eq("numbered list label", r.files.single().path, "package.json")
        }
    
        println("\n=== CodeBlockParser: header comment inside the block ===")
        run {
            val r = CodeBlockParser.parse("```\n// src/routes/auth.routes.js\nrouter.get('/');\n```")
            eq("comment header path", r.files.single().path, "src/routes/auth.routes.js")
            check("header comment stripped", !r.files.single().content.contains("auth.routes.js"),
                r.files.single().content)
        }
        run {
            val r = CodeBlockParser.parse("```html\n<!-- index.html -->\n<h1>hi</h1>\n```")
            eq("html comment header", r.files.single().path, "index.html")
            eq("html body only", r.files.single().content, "<h1>hi</h1>")
        }
    
        println("\n=== CodeBlockParser: several files in one response ===")
        run {
            val r = CodeBlockParser.parse(
                """
                **File: a.js**
                ```js
                const a = 1
                ```
                **File: src/b.ts**
                ```ts
                const b: number = 2
                ```
                **File: c.py**
                ```python
                c = 3
                ```
                """.trimIndent()
            )
            eq("three files", r.files.size, 3)
            eq("order 1", r.files[0].path, "a.js")
            eq("order 2", r.files[1].path, "src/b.ts")
            eq("order 3", r.files[2].path, "c.py")
            check("no duplicate snippets", !r.files.any { it.needsNameConfirm })
        }
        run {
            val r = CodeBlockParser.parse(
                """
                | File | Purpose |
                | --- | --- |
                | `main.py` | entry |
                """.trimIndent()
            )
            check("markdown table is not treated as code", r.files.size <= 1, r.files.map { it.path }.toString())
        }
        run {
            // One fence, many files, delimited by path comments.
            val r = CodeBlockParser.parse(
                """
                ```
                // src/index.js
                const a = 1
                // src/lib/util.js
                const b = 2
                // src/lib/other.js
                const c = 3
                ```
                """.trimIndent()
            )
            eq("comment-split into 3", r.files.size, 3)
            eq("split 1", r.files[0].path, "src/index.js")
            eq("split 2", r.files[1].path, "src/lib/util.js")
            eq("split 3", r.files[2].path, "src/lib/other.js")
            eq("split 1 body", r.files[0].content, "const a = 1")
        }
        run {
            // `// eslint-disable` must not be mistaken for a file header.
            val r = CodeBlockParser.parse("```js\n// eslint-disable-next-line\nconst x = 1\n// no-alert\nconst y = 2\n```")
            eq("lint comments do not split", r.files.size, 1)
        }
    
        println("\n=== CodeBlockParser: unfenced / unnamed ===")
        run {
            val r = CodeBlockParser.parse("main.py\ndef hello():\n    return 1")
            eq("bare first line is the name", r.files.single().path, "main.py")
            check("content keeps code", r.files.single().content.contains("def hello"))
        }
        run {
            val r = CodeBlockParser.parse("const a = 1;\nconst b = 2;")
            eq("unfamed js -> js ext", r.files.single().path, "snippet-1.js")
            check("flagged for confirmation", r.files.single().needsNameConfirm)
            check("result reports unnamed", r.hasUnnamed)
        }
        run {
            val r = CodeBlockParser.parse("def main():\n    print(1)")
            eq("sniff python", r.files.single().path, "snippet-1.py")
        }
        run {
            val r = CodeBlockParser.parse("{\"name\": \"x\", \"version\": \"1.0.0\"}")
            eq("sniff json", r.files.single().path, "snippet-1.json")
        }
        run {
            val r = CodeBlockParser.parse("```\n<svg width=\"10\"></svg>\n```")
            eq("sniff svg", r.files.single().path, "snippet-1.svg")
        }
        run {
            val r = CodeBlockParser.parse("")
            check("empty input", r.isEmpty)
        }
    
        println("\n=== CodeBlockParser: path sanitising / safety ===")
        run {
            eq("strips traversal", CodeBlockParser.cleanPath("../../etc/passwd"), "etc/passwd")
            eq("strips absolute", CodeBlockParser.cleanPath("/etc/passwd"), "etc/passwd")
            eq("strips leading ./", CodeBlockParser.cleanPath("./src/a.js"), "src/a.js")
            eq("collapses slashes", CodeBlockParser.cleanPath("src//a.js"), "src/a.js")
            eq("keeps deep valid path", CodeBlockParser.cleanPath("a/b/c/d/e/file.ts"), "a/b/c/d/e/file.ts")
            eq("rejects prose", CodeBlockParser.cleanPath("Here is the code"), null)
            eq("rejects empty", CodeBlockParser.cleanPath("   "), null)
            eq("rejects url", CodeBlockParser.cleanPath("https://x.com/a.js"), null)
            eq("rejects bare language", CodeBlockParser.cleanPath("python"), null)
            eq("strips line ref", CodeBlockParser.cleanPath("app.py:12"), "app.py")
            eq("strips #L ref", CodeBlockParser.cleanPath("app.ts#L40"), "app.ts")
            eq("strips trailing note", CodeBlockParser.cleanPath("main.py (updated)"), "main.py")
            eq("strips trailing colon", CodeBlockParser.cleanPath("src/main.js:"), "src/main.js")
            eq("neutralises backslash traversal", CodeBlockParser.cleanPath("..\\..\\windows\\system32"), "windows/system32")
            eq("trailing dot removed", CodeBlockParser.cleanPath("main.py."), "main.py")
        }
        run {
            // Windows drive letter
            eq("strips drive letter", CodeBlockParser.cleanPath("C:/Users/me/app.js"), "Users/me/app.js")
        }
        run {
            // A filename with a space is legitimate.
            val r = CodeBlockParser.parse("```\n// My Component.jsx\nexport default 1\n```")
            eq("spaced filename kept", r.files.single().path, "My Component.jsx")
        }
        run {
            // A path with spaces only survives if the whole thing is one filename.
            val r = CodeBlockParser.parse("```\n// src/ my file.js\nx\n```")
            check("multi-segment spaced path rejected", r.files.single().needsNameConfirm,
                r.files.single().path)
        }
    
        println("\n=== TreeParser: ascii tree ===")
        run {
            val t = """
                my-app/
                |-- src/
                |   |-- index.js
                |   `-- components/
                |       `-- Button.jsx
                |-- package.json
                `-- README.md
            """.trimIndent()
            val e = TreeParser.parse(t)
            val paths = e.map { it.path }
            check("tree dirs", paths.containsAll(listOf("my-app", "src", "src/components")), paths.toString())
            check("tree files", paths.containsAll(listOf("src/index.js", "src/components/Button.jsx", "package.json", "README.md")), paths.toString())
            check("dir flagged", entry(e, "src").isDirectory)
            check("file flagged", !entry(e, "README.md").isDirectory)
        }
        run {
            val t = """
                my-app/
                └── src/
                    └── main.py
            """.trimIndent()
            val e = TreeParser.parse(t)
            check("unicode box drawing", e.any { it.path == "src" && it.isDirectory }, e.toString())
            check("unicode file", e.any { it.path == "src/main.py" }, e.toString())
        }
    
        println("\n=== TreeParser: bullets, numbers, indentation ===")
        run {
            val e = TreeParser.parse(
                """
                - src/main.js
                - src/index.html
                - src/styles.css
                """.trimIndent()
            )
            val paths = e.map { it.path }
            check("bullets: 3 files + implicit src dir", e.count { !it.isDirectory } == 3 && e.count { it.isDirectory } == 1, paths.toString())
            check("bullet paths", paths.containsAll(listOf("src", "src/main.js", "src/index.html", "src/styles.css")), paths.toString())
            check("src is a dir", entry(e, "src").isDirectory)
        }
        run {
            val e = TreeParser.parse(
                """
                1. `src/app.ts`
                2. `src/app.spec.ts`
                3. `tsconfig.json`
                """.trimIndent()
            )
            val paths = e.map { it.path }
            check("numbered with backticks", paths.containsAll(listOf("src/app.ts", "src/app.spec.ts", "tsconfig.json")), paths.toString())
            check("no backticks leaked", paths.none { it.contains('`') }, paths.toString())
        }
        run {
            val e = TreeParser.parse(
                """
                my-project/
                  src/
                    main.py
                  tests/
                    test_main.py
                  requirements.txt
                """.trimIndent()
            )
            val paths = e.map { it.path }
            check("indented tree dirs", paths.containsAll(listOf("my-project", "my-project/src", "my-project/tests")), paths.toString())
            check("indented tree files", paths.containsAll(listOf("my-project/src/main.py", "my-project/tests/test_main.py", "my-project/requirements.txt")), paths.toString())
            check("extensionless child is a dir", entry(e, "my-project/src").isDirectory, paths2(e))
            check("requirements.txt is a file", !entry(e, "my-project/requirements.txt").isDirectory, paths2(e))
        }
    
        println("\n=== TreeParser: descriptions and prose rejection ===")
        run {
            val e = TreeParser.parse(
                """
                src/
                  components/    # React components
                    Button.tsx
                  utils.js        # helpers
                """.trimIndent()
            )
            val btn = entry(e, "src/components/Button.tsx")
            eq("nested via note", btn.path, "src/components/Button.tsx")
            check("note captured", entry(e, "src/components").note.orEmpty().contains("React"),
                paths2(e) + " note=" + entry(e, "src/components").note)
            check("utils note", entry(e, "src/utils.js").note.orEmpty().contains("helpers"))
        }
        run {
            val e = TreeParser.parse(
                """
                main.py — the entry point
                README.md: project docs
                """.trimIndent()
            )
            eq("em dash note", e[0].path, "main.py")
            eq("em dash note text", e[0].note, "the entry point")
            eq("colon note", e[1].path, "README.md")
            eq("colon note text", e[1].note, "project docs")
        }
        run {
            val e = TreeParser.parse(
                """
                Sure! Here is the project structure for a todo app. Let me know if
                you would like me to add tests or a Docker setup instead.
                - src/index.js
                - src/todo.js
                """.trimIndent()
            )
            check("prose ignored", e.count { !it.isDirectory } == 2, paths2(e))
            check("no prose leaked in", e.none { it.path.contains(" ") }, paths2(e))
        }
        run {
            val e = TreeParser.parse("Here's the plan:\n")
            check("prose only -> empty", e.isEmpty(), e.toString())
        }
        run {
            val e = TreeParser.parse("main.py, utils.py, src/app.js")
            check("single comma line: 3 files + src dir", e.count { !it.isDirectory } == 3, paths2(e))
            check("comma line paths", e.filter { !it.isDirectory }.map { it.path } == listOf("main.py", "utils.py", "src/app.js"), paths2(e))
        }
        run {
            val e = TreeParser.parse("- [README.md](README.md)\n- src/[index.js](src/index.js)")
            check("markdown links", e.any { it.path == "README.md" }, e.toString())
            check("markdown link nested", e.any { it.path == "src/index.js" }, e.toString())
        }
        run {
            val e = TreeParser.parse("src/\n  index.js\n  index.js")
            eq("duplicates deduped", e.count { it.path == "src/index.js" }, 1)
        }
        run {
            val e = TreeParser.parse("src\nsrc/index.js")
            check("path claimed as both -> dir", entry(e, "src").isDirectory, e.toString())
        }
        run {
            val e = TreeParser.parse("Dockerfile\n.gitignore\n.env.example")
            eq("dotfiles kept", e.size, 3)
            check("Dockerfile is a file", !entry(e, "Dockerfile").isDirectory, paths2(e))
        }
        run {
            val e = TreeParser.parse("..\n../../etc/passwd\n")
            check("traversal rejected", e.none { it.path.contains("..") }, e.toString())
        }
    
        println("\n=== round trip: tree preview ===")
        run {
            val t = TreeParser.parse("- src/main.js\n- src/lib/util.js\n- package.json")
            val ascii = TreeParser.toAsciiTree(t, "demo")
            check("preview has root", ascii.startsWith("demo"), ascii)
            check("preview has files", ascii.contains("main.js") && ascii.contains("package.json"), ascii)
            println(ascii.prependIndent("        "))
        }
    
    }
}
