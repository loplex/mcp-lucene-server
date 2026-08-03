package cz.loplex.lucenemcp

import java.io.File

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

data class DefinitionMatch(val path: String, val line: Int, val kind: String, val text: String)

/**
 * Finds where [symbol] is *defined* rather than merely mentioned, using the per-language
 * heuristics in [definitionPatternsFor]. Always reads current file content directly (like
 * grep_code) — no index involved, so it is never stale.
 */
fun findDefinitions(root: File, symbol: String, maxMatches: Int): List<DefinitionMatch> {
    val escapedSymbol = Regex.escape(symbol)
    val results = mutableListOf<DefinitionMatch>()

    outer@ for (file in listProjectFiles(root).sortedBy { it.path }) {
        val patterns = definitionPatternsFor(file.extension)
        if (patterns.isEmpty()) continue
        if (file.length() > MAX_INDEXABLE_FILE_BYTES) continue

        val compiled = patterns.map { it.kind to it.toRegex(escapedSymbol) }
        val lines = try {
            file.readLines()
        } catch (e: Exception) {
            continue
        }

        val relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/')
        for ((index, lineText) in lines.withIndex()) {
            val kind = compiled.firstOrNull { (_, regex) -> regex.containsMatchIn(lineText) }?.first ?: continue
            results.add(DefinitionMatch(relativePath, index + 1, kind, lineText.trim()))
            if (results.size >= maxMatches) break@outer
        }
    }
    return results
}

fun runFindDefinition(root: File, symbol: String, maxMatches: Int): String {
    val trimmed = symbol.trim()
    if (trimmed.isEmpty()) return "Missing required argument: symbol"
    if (!IDENTIFIER.matches(trimmed)) {
        return "Invalid symbol: only identifier characters are supported (letters, digits, underscore, not starting with a digit)."
    }

    val matches = findDefinitions(root, trimmed, maxMatches)
    if (matches.isEmpty()) {
        return "No definition found for '$trimmed'. Heuristic regex search covers: " +
            "${SUPPORTED_DEFINITION_EXTENSIONS.sorted().joinToString(", ")} files. " +
            "For plain occurrences (calls, imports, comments) use grep_code with pattern '\\\\b$trimmed\\\\b'."
    }

    val sb = StringBuilder()
    sb.append("Found ${matches.size} definition(s) for '$trimmed':\n\n")
    for (match in matches) {
        sb.append("${match.path}:${match.line} [${match.kind}]  ${match.text}\n")
    }
    return sb.toString().trim()
}
