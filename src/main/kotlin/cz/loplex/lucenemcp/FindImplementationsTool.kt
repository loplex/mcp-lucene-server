package cz.loplex.lucenemcp

import java.io.File

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

private val IMPLEMENTS_SUPPORTED_EXTENSIONS = setOf("kt", "kts", "java", "ts", "tsx", "js", "jsx", "mjs", "py", "rs")

data class ImplementationMatch(val path: String, val line: Int, val kind: String, val text: String)

/**
 * Finds types that directly `extends`/`implements` [type] — see [implementsHitsInFile]/
 * [IMPLEMENTS_BY_LANGUAGE]. Only direct subtypes declared in project source files: no transitive
 * chain through an intermediate abstract type, and nothing inside a dependency (no library source
 * to read, typically only compiled jars/binaries). Always parses current file content directly, no
 * index involved, never stale. Go is not supported — see [IMPLEMENTS_BY_LANGUAGE]'s kdoc.
 */
fun findImplementations(root: File, type: String, maxMatches: Int, astCache: AstCache = AstCache(), externalRoots: List<File> = emptyList()): List<ImplementationMatch> {
    val results = mutableListOf<ImplementationMatch>()
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

        for (hit in implementsHitsInFile(parsed, languageName, type)) {
            results.add(ImplementationMatch(relativePath, hit.nameNode.startPoint.row + 1, hit.kind, parsed.lineTextOf(hit.nameNode)))
            if (results.size >= maxMatches) break@outer
        }
    }
    return results.sortedWith(compareBy({ it.path }, { it.line }))
}

fun runFindImplementations(root: File, type: String, maxMatches: Int, astCache: AstCache = AstCache(), externalRoots: List<File> = emptyList()): String {
    val trimmed = type.trim()
    if (trimmed.isEmpty()) return "Missing required argument: type"
    if (!IDENTIFIER.matches(trimmed)) {
        return "Invalid type: only identifier characters are supported (letters, digits, underscore, not starting with a digit)."
    }

    val matches = findImplementations(root, trimmed, maxMatches, astCache, externalRoots)
    if (matches.isEmpty()) {
        return "No implementations/subtypes found for '$trimmed'. AST-based search covers direct " +
            "extends/implements clauses in: ${IMPLEMENTS_SUPPORTED_EXTENSIONS.sorted().joinToString(", ")} files " +
            "(Go is not supported — its interfaces are satisfied structurally, with no extends/implements " +
            "clause to search for). For an exact-text search use grep_code."
    }

    val sb = StringBuilder()
    sb.append("Found ${matches.size} implementation(s)/subtype(s) of '$trimmed':\n\n")
    for (match in matches) {
        sb.append("${match.path}:${match.line} [${match.kind}]  ${match.text}\n")
    }
    return sb.toString().trim()
}
