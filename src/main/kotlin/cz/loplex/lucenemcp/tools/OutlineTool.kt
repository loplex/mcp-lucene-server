package cz.loplex.lucenemcp.tools

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*

import java.io.File

/** One symbol [file] defines, as reported by `outline`. */
data class OutlineEntry(val line: Int, val kind: String, val text: String)

/**
 * Lists every symbol [file] defines (class/interface/function/property/...), in source order —
 * a quick structural overview without reading the whole file. Same tree-sitter basis as
 * `find_definition`/`find_references` ([definitionHitsInFile] with no symbol filter), so nested
 * members (e.g. a class's methods) are included alongside top-level declarations, not just the
 * outermost ones.
 */
fun outlineFile(file: File, extension: String, astCache: AstCache = AstCache()): List<OutlineEntry>? {
    val languageName = languageNameFor(extension) ?: return null
    val parsed = astCache.getOrParse(file, extension) ?: return null
    return definitionHitsInFile(parsed, languageName)
        .sortedBy { it.nameNode.startByte }
        .map { OutlineEntry(it.nameNode.startPoint.row + 1, it.kind, parsed.lineTextOf(it.nameNode)) }
}

fun runOutline(root: File, path: String, astCache: AstCache = AstCache()): String {
    val target = File(root, path).canonicalFile
    val rootCanonical = root.canonicalFile

    if (!target.path.startsWith(rootCanonical.path + File.separator) && target.path != rootCanonical.path) {
        return "Error: path '$path' escapes the project directory."
    }
    if (!target.isFile) {
        return "Error: '$path' is not a file."
    }
    if (target.length() > MAX_INDEXABLE_FILE_BYTES) {
        return "Error: '$path' is larger than $MAX_INDEXABLE_FILE_BYTES bytes; use read_file/grep_code instead."
    }

    val extension = target.extension
    if (languageNameFor(extension) == null) {
        return "Error: '$path' has an unsupported extension for outline. AST-based outline covers: " +
            "${SUPPORTED_AST_EXTENSIONS.sorted().joinToString(", ")} files."
    }

    val entries = outlineFile(target, extension, astCache)
        ?: return "Error reading '$path': could not parse the file."

    if (entries.isEmpty()) {
        return "No symbols found in '$path'."
    }

    val sb = StringBuilder()
    sb.append("Found ${entries.size} symbol(s) in '$path':\n\n")
    for (entry in entries) {
        sb.append("$path:${entry.line} [${entry.kind}]  ${entry.text}\n")
    }
    return sb.toString().trim()
}
