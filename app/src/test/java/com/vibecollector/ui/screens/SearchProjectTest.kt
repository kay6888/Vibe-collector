package com.vibecollector.ui.screens

import com.vibecollector.data.FileNode
import org.junit.Test

class SearchProjectTest {
    @Test
    fun searchIncludesAncestorsOfCollapsedFiles() {
        val catalog = listOf(
            FileNode("src", "src", true),
            FileNode("main", "src/main", true),
            FileNode("App.kt", "src/main/App.kt", false),
            FileNode("README.md", "README.md", false),
        )
        val rows = searchProject(catalog, "app")
        check(rows.map { it.node.path } == listOf("src", "src/main", "src/main/App.kt")) { rows.map { it.node.path } }
        check(rows.map { it.depth } == listOf(0, 1, 2)) { rows.map { it.depth } }
        check(rows[0].expanded && rows[1].expanded && !rows[2].expanded) { rows }
    }

    @Test
    fun blankSearchReturnsNothing() {
        val catalog = listOf(FileNode("README.md", "README.md", false))
        check(searchProject(catalog, "   ").isEmpty()) { searchProject(catalog, "   ") }
    }

    private fun check(cond: Boolean, detail: () -> Any?) {
        if (!cond) throw AssertionError(detail().toString())
    }
}
