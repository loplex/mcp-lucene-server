package cz.loplex.lucenemcp

import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSQuery
import org.treesitter.TSQueryCursor
import org.treesitter.TSQueryMatch
import org.treesitter.TSTree
import java.io.File

/**
 * A parsed file: the tree itself plus the exact source text/bytes it was parsed from.
 *
 * [sourceBytes] is the UTF-8 encoding of [source] — tree-sitter-ng's `parseString` converts the
 * Java (UTF-16) string to real UTF-8 before parsing (see `TSParser.ts_parser_parse_string` JNI
 * glue), so `TSNode.getStartByte()/getEndByte()` are offsets into *that* UTF-8 buffer, not into
 * [source] itself. Re-encoding here keeps text extraction correct for non-ASCII content.
 */
class ParsedFile(val tree: TSTree, val source: String, val sourceBytes: ByteArray) {
    fun textOf(node: TSNode): String =
        String(sourceBytes, node.startByte, node.endByte - node.startByte, Charsets.UTF_8)

    /** 1-based display line for [node], taken from the original string (never touches [sourceBytes]). */
    fun lineTextOf(node: TSNode): String {
        val lines = source.lines()
        val row = node.startPoint.row
        return if (row in lines.indices) lines[row].trim() else ""
    }
}

/**
 * Parses [file] with the tree-sitter grammar matching [extension]. Always reads current file
 * content directly (like `grep_code`/the old `find_definition`) — no index involved, never stale.
 * Returns null for unsupported extensions, oversized files, unreadable files, or a grammar/parser
 * that fails to load — callers should skip such files, not fail the whole search.
 */
fun parseFile(file: File, extension: String): ParsedFile? {
    val languageName = languageNameFor(extension) ?: return null
    if (file.length() > MAX_INDEXABLE_FILE_BYTES) return null

    val source = try {
        file.readText()
    } catch (e: Exception) {
        return null
    }

    return try {
        val parser = TSParser()
        if (!parser.setLanguage(newLanguageInstance(languageName))) return null
        val tree = parser.parseString(null, source) ?: return null
        ParsedFile(tree, source, source.toByteArray(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }
}

/** One place in [parsed] where [symbol] is defined as [kind] — the name node's own byte range. */
data class DefinitionHit(val range: LongRange, val kind: String, val nameNode: TSNode)

/**
 * Runs every [DefinitionQuery] for [languageName] (see [DEFINITIONS_BY_LANGUAGE]) against
 * [parsed], keeping only name nodes whose text equals [symbol] — or every definition, in document
 * order, when [symbol] is null (used by `outline` to list a file's whole structure). Shared by
 * `find_definition` (which reports these directly) and `find_references` (which uses the resulting
 * ranges to label/exclude the declaration among plain usages) — see `AstQueries.kt` for how a
 * definition's [DefinitionQuery.priority] resolves grammars that reuse one node type for several
 * kinds.
 */
fun definitionHitsInFile(parsed: ParsedFile, languageName: String, symbol: String? = null): List<DefinitionHit> {
    val queries = DEFINITIONS_BY_LANGUAGE[languageName] ?: return emptyList()
    val claimedRanges = HashSet<LongRange>()
    val hits = mutableListOf<DefinitionHit>()

    for (defQuery in queries.sortedBy { it.priority }) {
        val query = compiledQuery(languageName, defQuery.source) ?: continue
        val cursor = TSQueryCursor()
        cursor.exec(query, parsed.tree.rootNode)
        val match = TSQueryMatch()
        while (cursor.nextMatch(match)) {
            val nameNode = match.captures
                .firstOrNull { query.getCaptureNameForId(it.index) == "name" }
                ?.node ?: continue
            if (symbol != null && parsed.textOf(nameNode) != symbol) continue

            val range = nameNode.startByte.toLong()..nameNode.endByte.toLong()
            if (!claimedRanges.add(range)) continue // already claimed by a higher-priority pattern
            hits.add(DefinitionHit(range, defQuery.kind, nameNode))
        }
    }
    return hits
}

private val PLAIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Extracts [ImportInfo] for [parsed] (at [file], inside [root]) using [languageName]'s
 * [ImportQueryConfig] (see [IMPORT_QUERIES_BY_LANGUAGE]). Returns an empty/permissive [ImportInfo]
 * for languages without a config — callers must treat that as "nothing known", never as "imports
 * nothing". Each import statement becomes one [ImportRecord], resolved via [resolveImportTarget]
 * for the languages/forms that support it.
 */
fun extractImportInfo(parsed: ParsedFile, languageName: String, file: File, root: File): ImportInfo {
    val config = IMPORT_QUERIES_BY_LANGUAGE[languageName] ?: return ImportInfo(packageName = "", records = emptyList())

    var packageName = ""
    val packageQuery = config.packageQuery
    if (packageQuery != null) {
        compiledQuery(languageName, packageQuery)?.let { query ->
            val cursor = TSQueryCursor()
            cursor.exec(query, parsed.tree.rootNode)
            val match = TSQueryMatch()
            if (cursor.nextMatch(match)) {
                val node = match.captures.firstOrNull { query.getCaptureNameForId(it.index) == "package" }?.node
                if (node != null) {
                    packageName = parsed.textOf(node).removePrefix("package").trim().removeSuffix(";").trim()
                }
            }
        }
    }

    val records = mutableListOf<ImportRecord>()
    compiledQuery(languageName, config.importQuery)?.let { query ->
        val cursor = TSQueryCursor()
        cursor.exec(query, parsed.tree.rootNode)
        val match = TSQueryMatch()
        while (cursor.nextMatch(match)) {
            val importNode = match.captures.firstOrNull { query.getCaptureNameForId(it.index) == "import" }?.node ?: continue
            val importedNames = mutableSetOf<String>()
            collectPlainIdentifierLeaves(importNode, parsed, importedNames)
            val isWildcard = containsNodeType(importNode, config.wildcardNodeTypes)
            val resolvedFile = resolveImportTarget(importNode, parsed, file, root, languageName)
            records.add(ImportRecord(importedNames, isWildcard, resolvedFile))
        }
    }

    return ImportInfo(packageName, records)
}

private fun collectPlainIdentifierLeaves(node: TSNode, parsed: ParsedFile, into: MutableSet<String>) {
    if (node.childCount == 0) {
        val text = parsed.textOf(node)
        if (PLAIN_IDENTIFIER.matches(text)) into.add(text)
        return
    }
    for (i in 0 until node.childCount) {
        collectPlainIdentifierLeaves(node.getChild(i), parsed, into)
    }
}

private fun containsNodeType(node: TSNode, types: Set<String>): Boolean {
    if (types.isEmpty()) return false
    if (types.contains(node.type)) return true
    for (i in 0 until node.childCount) {
        if (containsNodeType(node.getChild(i), types)) return true
    }
    return false
}

/** Compiled-query cache, keyed by (language name, query source) — one-time compile cost per process. */
private val queryCache = HashMap<Pair<String, String>, TSQuery?>()

/** Compiles [querySource] for [languageName], caching the result (including failures, as null). */
fun compiledQuery(languageName: String, querySource: String): TSQuery? =
    queryCache.getOrPut(languageName to querySource) {
        try {
            TSQuery(newLanguageInstance(languageName), querySource)
        } catch (e: Exception) {
            System.err.println("Skipping tree-sitter query for '$languageName' (failed to compile): ${e.message}")
            null
        }
    }
