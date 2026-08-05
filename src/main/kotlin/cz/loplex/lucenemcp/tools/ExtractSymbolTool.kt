package cz.loplex.lucenemcp.tools

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import java.io.File

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

data class ExtractSymbolMatch(val path: String, val startLine: Int, val endLine: Int, val kind: String, val sourceCode: String)

fun extractSymbols(root: File, symbol: String, maxMatches: Int, astCache: AstCache = AstCache(), externalRoots: List<File> = emptyList()): List<ExtractSymbolMatch> {
    val results = mutableListOf<ExtractSymbolMatch>()
    val projectFiles = listProjectFiles(root, externalRoots).sortedBy { it.path }
    astCache.prune(projectFiles.mapTo(HashSet()) { it.absolutePath })

    outer@ for (file in projectFiles) {
        val languageName = languageNameFor(file.extension) ?: continue
        val parsed = astCache.getOrParse(file, file.extension) ?: continue
        val relativePath = if (file.absolutePath.startsWith(root.absolutePath)) {
            file.relativeTo(root).path.replace(File.separatorChar, '/')
        } else {
            file.absolutePath.replace(File.separatorChar, '/')
        }

        for (hit in definitionHitsInFile(parsed, languageName, symbol)) {
            val sourceCode = parsed.textOf(hit.defNode)
            results.add(ExtractSymbolMatch(
                relativePath, 
                hit.defNode.startPoint.row + 1, 
                hit.defNode.endPoint.row + 1, 
                hit.kind, 
                sourceCode
            ))
            if (results.size >= maxMatches) break@outer
        }
    }
    return results.sortedWith(compareBy({ it.path }, { it.startLine }))
}

fun runExtractSymbol(root: File, symbol: String, maxMatches: Int, astCache: AstCache = AstCache(), externalRoots: List<File> = emptyList()): String {
    val trimmed = symbol.trim()
    if (trimmed.isEmpty()) return "Missing required argument: symbol"
    if (!IDENTIFIER.matches(trimmed)) {
        return "Invalid symbol: only identifier characters are supported (letters, digits, underscore, not starting with a digit)."
    }

    val matches = extractSymbols(root, trimmed, maxMatches, astCache, externalRoots)
    if (matches.isEmpty()) {
        return "No definition found for '$trimmed'."
    }

    val sb = StringBuilder()
    sb.append("Found ${matches.size} definition(s) for '$trimmed':\n\n")
    for (match in matches) {
        sb.append("File: ${match.path} (Lines ${match.startLine}-${match.endLine}) [${match.kind}]\n")
        sb.append("```\n")
        sb.append(match.sourceCode)
        sb.append("\n```\n\n")
    }
    return sb.toString().trim()
}
