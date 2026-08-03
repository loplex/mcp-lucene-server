package cz.loplex.lucenemcp

import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

data class GrepOptions(
    val pattern: String,
    val literal: Boolean = false,
    val caseSensitive: Boolean = true,
    val beforeContext: Int = 0,
    val afterContext: Int = 0,
    val filePattern: String? = null,
    val outputMode: String = "content",
    val maxMatches: Int = 200
)

private data class GrepMatch(val path: String, val lineNumber: Int, val context: List<String>, val matchLineIndexInContext: Int)
private data class FileMatchCount(val path: String, val count: Int)

/** Direct filesystem regex/literal search with line numbers and context — always reflects the current file content. */
fun runGrep(root: File, options: GrepOptions): String {
    val regex = try {
        val source = if (options.literal) Pattern.quote(options.pattern) else options.pattern
        val flags = if (options.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        Pattern.compile(source, flags)
    } catch (e: PatternSyntaxException) {
        return "Invalid regex pattern: ${e.message}"
    }

    val globRegex: Regex? = options.filePattern?.let { globToRegex(it) }

    val files = listProjectFiles(root)
        .filter { file ->
            globRegex == null || globRegex.matches(file.relativeTo(root).path.replace(File.separatorChar, '/'))
        }
        .sortedBy { it.path }

    val contentMatches = mutableListOf<GrepMatch>()
    val filesWithMatches = linkedSetOf<String>()
    val fileCounts = mutableListOf<FileMatchCount>()
    var totalMatchCount = 0
    var truncated = false

    outer@ for (file in files) {
        if (file.length() > MAX_INDEXABLE_FILE_BYTES) continue

        val lines = try {
            file.readLines()
        } catch (e: Exception) {
            continue
        }

        val relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/')
        var matchesInThisFile = 0

        for (i in lines.indices) {
            if (!regex.matcher(lines[i]).find()) continue

            matchesInThisFile++
            totalMatchCount++
            filesWithMatches.add(relativePath)

            if (options.outputMode == "content") {
                if (contentMatches.size >= options.maxMatches) {
                    truncated = true
                    break@outer
                }
                val from = maxOf(0, i - options.beforeContext)
                val to = minOf(lines.size - 1, i + options.afterContext)
                contentMatches.add(GrepMatch(relativePath, i + 1, lines.subList(from, to + 1), i - from))
            }
        }

        if (matchesInThisFile > 0 && options.outputMode == "count") {
            fileCounts.add(FileMatchCount(relativePath, matchesInThisFile))
        }

        if (options.outputMode == "files_with_matches" && matchesInThisFile > 0 && filesWithMatches.size >= options.maxMatches) {
            truncated = true
            break
        }
    }

    return when (options.outputMode) {
        "files_with_matches" -> formatFilesWithMatches(filesWithMatches, truncated)
        "count" -> formatCount(fileCounts, totalMatchCount)
        else -> formatContent(contentMatches, totalMatchCount, truncated)
    }
}

private fun formatContent(matches: List<GrepMatch>, totalMatchCount: Int, truncated: Boolean): String {
    if (matches.isEmpty()) return "No matches found."
    val sb = StringBuilder()
    sb.append("Found $totalMatchCount match(es)")
    if (truncated) sb.append(" (truncated, showing first ${matches.size})")
    sb.append(":\n\n")

    for (match in matches) {
        val startLine = match.lineNumber - match.matchLineIndexInContext
        sb.append("--- FILE: ${match.path}:${match.lineNumber} ---\n")
        for ((offset, line) in match.context.withIndex()) {
            val lineNo = startLine + offset
            val marker = if (offset == match.matchLineIndexInContext) ">" else " "
            sb.append("$marker $lineNo: $line\n")
        }
        sb.append("\n")
    }
    return sb.toString().trim()
}

private fun formatFilesWithMatches(files: Set<String>, truncated: Boolean): String {
    if (files.isEmpty()) return "No matching files found."
    val sb = StringBuilder()
    sb.append("Found ${files.size} file(s) with matches")
    if (truncated) sb.append(" (truncated)")
    sb.append(":\n\n")
    files.forEach { sb.append(it).append("\n") }
    return sb.toString().trim()
}

private fun formatCount(fileCounts: List<FileMatchCount>, totalMatchCount: Int): String {
    if (fileCounts.isEmpty()) return "No matches found."
    val sb = StringBuilder()
    fileCounts.forEach { sb.append("${it.path}: ${it.count}\n") }
    sb.append("\nTotal matches: $totalMatchCount")
    return sb.toString()
}

/** Translates a shell-style glob (`*`, `**`, `?`) into a Regex matched against forward-slash relative paths. */
fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when (c) {
            '*' -> {
                if (i + 1 < glob.length && glob[i + 1] == '*') {
                    sb.append(".*")
                    i++
                } else {
                    sb.append("[^/]*")
                }
            }
            '?' -> sb.append("[^/]")
            else -> sb.append(Regex.escape(c.toString()))
        }
        i++
    }
    return Regex(sb.toString())
}
