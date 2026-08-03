package cz.loplex.lucenemcp

import org.treesitter.TSNode
import java.io.File

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

data class ReferenceMatch(val path: String, val line: Int, val kind: String, val text: String)

/** One file's data needed across both passes of [findReferences] — parsed once, reused twice. */
private class FileContext(val file: File, val relativePath: String, val languageName: String, val parsed: ParsedFile) {
    lateinit var definitionRanges: Set<LongRange>
    lateinit var importInfo: ImportInfo
}

/**
 * Finds real-code usages of [symbol] — as opposed to `grep_code`, which matches the same text
 * inside comments and string literals too. Walks the parse tree looking for leaf identifier nodes
 * (see [ReferenceRules.identifierNodeTypes]) whose text equals [symbol], classifying each hit via
 * [classifyReferenceKind]. This is a name-based scan across the whole file, narrowed by import
 * awareness (see [isCandidateFile]) — not a scope/import-aware *resolver*, so it still can't tell
 * two same-named symbols in unrelated scopes apart when both are otherwise reachable.
 *
 * The symbol's own declaration (see [definitionHitsInFile]) is included as a `[definition]` hit
 * rather than silently dropped or misclassified by [classifyReferenceKind] (a class name is, for
 * example, structurally a `type_identifier` like any other type reference).
 */
fun findReferences(root: File, symbol: String, maxMatches: Int, astCache: AstCache = AstCache()): List<ReferenceMatch> {
    val projectFiles = listProjectFiles(root).sortedBy { it.path }
    astCache.prune(projectFiles.mapTo(HashSet()) { it.absolutePath })

    val contexts = projectFiles.mapNotNull { file ->
        val languageName = languageNameFor(file.extension) ?: return@mapNotNull null
        val parsed = astCache.getOrParse(file, file.extension) ?: return@mapNotNull null
        val relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/')
        FileContext(file, relativePath, languageName, parsed)
    }

    // Pass 1: which files actually declare `symbol`, and what does each file import? Both are
    // needed before any file's candidacy can be judged, so this must finish before pass 2 starts.
    val definingFiles = mutableSetOf<File>()
    val definingPackages = mutableSetOf<String>()
    for (ctx in contexts) {
        val hits = definitionHitsInFile(ctx.parsed, ctx.languageName, symbol)
        ctx.definitionRanges = hits.map { it.range }.toHashSet()
        ctx.importInfo = extractImportInfo(ctx.parsed, ctx.languageName)
        if (hits.isNotEmpty()) {
            definingFiles.add(ctx.file)
            if (IMPORT_QUERIES_BY_LANGUAGE[ctx.languageName]?.packageAware == true && ctx.importInfo.packageName.isNotEmpty()) {
                definingPackages.add(ctx.importInfo.packageName)
            }
        }
    }
    // No definition found anywhere in the repo (external/stdlib symbol, typo, ...): we have no
    // package/import context to narrow against, so filtering would only produce false negatives.
    val canFilter = definingFiles.isNotEmpty()

    fun isCandidateFile(ctx: FileContext): Boolean {
        if (!canFilter || ctx.file in definingFiles) return true
        if (ctx.importInfo.hasWildcardImport || symbol in ctx.importInfo.importedNames) return true
        val packageAware = IMPORT_QUERIES_BY_LANGUAGE[ctx.languageName]?.packageAware == true
        return packageAware && ctx.importInfo.packageName in definingPackages
    }

    // Pass 2: full walk, using pass 1's data to drop unqualified hits in non-candidate files while
    // never dropping a qualified (receiver.symbol) hit — see isQualifiedAccess's kdoc for why.
    val results = mutableListOf<ReferenceMatch>()
    outer@ for (ctx in contexts) {
        val rules = REFERENCE_RULES_BY_LANGUAGE[ctx.languageName] ?: continue
        val fileIsCandidate = isCandidateFile(ctx)

        fun visit(node: TSNode) {
            if (rules.identifierNodeTypes.contains(node.type) && ctx.parsed.textOf(node) == symbol) {
                val range = node.startByte.toLong()..node.endByte.toLong()
                val isDefinition = range in ctx.definitionRanges
                if (isDefinition || fileIsCandidate || isQualifiedAccess(node, ctx.languageName)) {
                    val kind = if (isDefinition) "definition" else classifyReferenceKind(node, rules)
                    results.add(ReferenceMatch(ctx.relativePath, node.startPoint.row + 1, kind, ctx.parsed.lineTextOf(node)))
                    if (results.size >= maxMatches) return
                }
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
                if (results.size >= maxMatches) return
            }
        }
        visit(ctx.parsed.tree.rootNode)
        if (results.size >= maxMatches) break@outer
    }
    return results
}

fun runFindReferences(root: File, symbol: String, maxMatches: Int, astCache: AstCache = AstCache()): String {
    val trimmed = symbol.trim()
    if (trimmed.isEmpty()) return "Missing required argument: symbol"
    if (!IDENTIFIER.matches(trimmed)) {
        return "Invalid symbol: only identifier characters are supported (letters, digits, underscore, not starting with a digit)."
    }

    val matches = findReferences(root, trimmed, maxMatches, astCache)
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
