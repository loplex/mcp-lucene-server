package cz.loplex.lucenemcp.tools

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*

import org.treesitter.TSNode
import org.treesitter.TSQueryCursor
import org.treesitter.TSQueryMatch
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher

fun runSearchAst(root: File, queryStr: String, languageName: String, pathPattern: String?, astCache: AstCache = AstCache(), externalRoots: List<File> = emptyList()): String {
    if (queryStr.isBlank()) return "Missing required argument: query"
    if (languageName.isBlank()) return "Missing required argument: language"

    val query = compiledQuery(languageName, queryStr)
        ?: return "Failed to compile tree-sitter query for language '$languageName'. Check syntax or unsupported language."

    val projectFiles = listProjectFiles(root, externalRoots).sortedBy { it.path }
    astCache.prune(projectFiles.mapTo(HashSet()) { it.absolutePath })

    var matcher: PathMatcher? = null
    if (!pathPattern.isNullOrBlank()) {
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:$pathPattern")
        } catch (e: Exception) {
            return "Invalid glob pattern '$pathPattern': ${e.message}"
        }
    }

    val results = mutableListOf<String>()
    var matchCount = 0

    for (file in projectFiles) {
        val relativePathForMatch = if (file.absolutePath.startsWith(root.absolutePath)) {
            file.relativeTo(root).path.replace(File.separatorChar, '/')
        } else {
            file.absolutePath.replace(File.separatorChar, '/')
        }
        if (matcher != null) {
            if (!matcher.matches(File(relativePathForMatch).toPath())) continue
        }

        val fileLang = languageNameFor(file.extension)
        if (fileLang != languageName) continue

        val parsed = astCache.getOrParse(file, file.extension) ?: continue

        val cursor = TSQueryCursor()
        cursor.exec(query, parsed.tree.rootNode)
        val match = TSQueryMatch()
        
        var fileHasMatches = false
        while (cursor.nextMatch(match)) {
            if (!fileHasMatches) {
                results.add("\n--- $relativePathForMatch ---")
                fileHasMatches = true
            }
            
            for (capture in match.captures) {
                val captureName = query.getCaptureNameForId(capture.index)
                val node = capture.node
                val text = parsed.textOf(node)
                val line = node.startPoint.row + 1
                val col = node.startPoint.column + 1
                
                results.add("L$line:$col [@$captureName] $text")
                matchCount++
            }
        }
    }

    if (matchCount == 0) {
        return "No matches found for the given query."
    }

    return "Found $matchCount capture(s) across the project:\n" + results.joinToString("\n")
}
