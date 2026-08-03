package cz.loplex.lucenemcp

import java.io.File

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

data class DefinitionMatch(val path: String, val line: Int, val kind: String, val text: String)

/**
 * Finds where [symbol] is *defined* rather than merely mentioned, using real tree-sitter parse
 * trees (see [definitionHitsInFile]/[DEFINITIONS_BY_LANGUAGE]) — not text/regex matching, so a
 * symbol name that happens to appear inside a comment or string literal is never mistaken for a
 * definition. Always parses current file content directly (like `grep_code`) — no index involved,
 * never stale.
 */
fun findDefinitions(root: File, symbol: String, maxMatches: Int, astCache: AstCache = AstCache()): List<DefinitionMatch> {
    val results = mutableListOf<DefinitionMatch>()
    val projectFiles = listProjectFiles(root).sortedBy { it.path }
    astCache.prune(projectFiles.mapTo(HashSet()) { it.absolutePath })

    outer@ for (file in projectFiles) {
        val languageName = languageNameFor(file.extension) ?: continue
        val parsed = astCache.getOrParse(file, file.extension) ?: continue
        val relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/')

        for (hit in definitionHitsInFile(parsed, languageName, symbol)) {
            results.add(DefinitionMatch(relativePath, hit.nameNode.startPoint.row + 1, hit.kind, parsed.lineTextOf(hit.nameNode)))
            if (results.size >= maxMatches) break@outer
        }
    }
    return results.sortedWith(compareBy({ it.path }, { it.line }))
}

fun runFindDefinition(root: File, symbol: String, maxMatches: Int, astCache: AstCache = AstCache()): String {
    val trimmed = symbol.trim()
    if (trimmed.isEmpty()) return "Missing required argument: symbol"
    if (!IDENTIFIER.matches(trimmed)) {
        return "Invalid symbol: only identifier characters are supported (letters, digits, underscore, not starting with a digit)."
    }

    val matches = findDefinitions(root, trimmed, maxMatches, astCache)
    if (matches.isEmpty()) {
        return "No definition found for '$trimmed'. AST-based search covers: " +
            "${SUPPORTED_AST_EXTENSIONS.sorted().joinToString(", ")} files. " +
            "For plain occurrences (calls, imports, comments) use grep_code with pattern '\\\\b$trimmed\\\\b', " +
            "or find_references for real-code usages."
    }

    val sb = StringBuilder()
    sb.append("Found ${matches.size} definition(s) for '$trimmed':\n\n")
    for (match in matches) {
        sb.append("${match.path}:${match.line} [${match.kind}]  ${match.text}\n")
    }
    return sb.toString().trim()
}
