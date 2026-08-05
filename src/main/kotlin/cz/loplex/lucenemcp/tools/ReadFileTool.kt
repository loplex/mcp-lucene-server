package cz.loplex.lucenemcp.tools

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*

import java.io.File

/** Reads a project file, optionally restricted to a 1-based inclusive line range, prefixed with line numbers. */
fun readFileRange(root: File, relativePath: String, startLine: Int?, endLine: Int?): String {
    val target = File(root, relativePath).canonicalFile
    val rootCanonical = root.canonicalFile

    if (!target.path.startsWith(rootCanonical.path + File.separator) && target.path != rootCanonical.path) {
        return "Error: path '$relativePath' escapes the project directory."
    }
    if (!target.isFile) {
        return "Error: '$relativePath' is not a file."
    }
    if (target.length() > MAX_INDEXABLE_FILE_BYTES) {
        return "Error: '$relativePath' is larger than ${MAX_INDEXABLE_FILE_BYTES} bytes; read it in smaller line ranges."
    }

    val lines = try {
        target.readLines()
    } catch (e: Exception) {
        return "Error reading '$relativePath': ${e.message}"
    }

    val from = ((startLine ?: 1) - 1).coerceIn(0, lines.size)
    val to = ((endLine ?: lines.size) - 1).coerceIn(0, lines.size - 1)
    if (from > to) return "Requested range is empty for '$relativePath' (file has ${lines.size} line(s))."

    val sb = StringBuilder()
    sb.append("--- FILE: $relativePath (lines ${from + 1}-${to + 1} of ${lines.size}) ---\n")
    for (i in from..to) {
        sb.append("${i + 1}: ${lines[i]}\n")
    }
    return sb.toString().trim()
}
