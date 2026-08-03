package cz.loplex.lucenemcp

import org.treesitter.TSNode
import java.io.File

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

data class ReferenceMatch(val path: String, val line: Int, val kind: String, val text: String)

/**
 * Finds real-code usages of [symbol] — as opposed to `grep_code`, which matches the same text
 * inside comments and string literals too. Walks the parse tree looking for leaf identifier nodes
 * (see [ReferenceRules.identifierNodeTypes]) whose text equals [symbol], classifying each hit via
 * [classifyReferenceKind]. This is a cheap, name-based scan across the whole file — not a
 * scope/import-aware resolver, so it can't tell two same-named symbols in unrelated scopes apart.
 *
 * The symbol's own declaration (see [definitionHitsInFile]) is included as a `[definition]` hit
 * rather than silently dropped or misclassified by [classifyReferenceKind] (a class name is, for
 * example, structurally a `type_identifier` like any other type reference).
 */
fun findReferences(root: File, symbol: String, maxMatches: Int): List<ReferenceMatch> {
    val results = mutableListOf<ReferenceMatch>()

    outer@ for (file in listProjectFiles(root).sortedBy { it.path }) {
        val languageName = languageNameFor(file.extension) ?: continue
        val rules = REFERENCE_RULES_BY_LANGUAGE[languageName] ?: continue
        val parsed = parseFile(file, file.extension) ?: continue
        val relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/')
        val definitionRanges = definitionHitsInFile(parsed, languageName, symbol).map { it.range }.toHashSet()

        fun visit(node: TSNode) {
            if (rules.identifierNodeTypes.contains(node.type) && parsed.textOf(node) == symbol) {
                val range = node.startByte.toLong()..node.endByte.toLong()
                val kind = if (range in definitionRanges) "definition" else classifyReferenceKind(node, rules)
                results.add(ReferenceMatch(relativePath, node.startPoint.row + 1, kind, parsed.lineTextOf(node)))
                if (results.size >= maxMatches) return
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
                if (results.size >= maxMatches) return
            }
        }
        visit(parsed.tree.rootNode)
        if (results.size >= maxMatches) break@outer
    }
    return results
}

fun runFindReferences(root: File, symbol: String, maxMatches: Int): String {
    val trimmed = symbol.trim()
    if (trimmed.isEmpty()) return "Missing required argument: symbol"
    if (!IDENTIFIER.matches(trimmed)) {
        return "Invalid symbol: only identifier characters are supported (letters, digits, underscore, not starting with a digit)."
    }

    val matches = findReferences(root, trimmed, maxMatches)
    if (matches.isEmpty()) {
        return "No references found for '$trimmed'. AST-based search covers: " +
            "${SUPPORTED_AST_EXTENSIONS.sorted().joinToString(", ")} files. " +
            "For other file types use grep_code with pattern '\\\\b$trimmed\\\\b'."
    }

    val sb = StringBuilder()
    sb.append("Found ${matches.size} reference(s) to '$trimmed':\n\n")
    for (match in matches) {
        sb.append("${match.path}:${match.line} [${match.kind}]  ${match.text}\n")
    }
    return sb.toString().trim()
}
