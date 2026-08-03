package cz.loplex.lucenemcp

import java.io.File

/** Lists project files whose relative path matches an optional glob pattern (e.g. `src/**/*.kt`). */
fun runListFiles(root: File, globPattern: String?, limit: Int): String {
    val files = listProjectFiles(root)
        .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
        .sorted()

    val filtered = if (globPattern != null) {
        val regex = globToRegex(globPattern)
        files.filter { regex.matches(it) }
    } else {
        files
    }

    if (filtered.isEmpty()) return "No files found."

    val truncated = filtered.size > limit
    val shown = filtered.take(limit)

    val sb = StringBuilder()
    sb.append("Found ${filtered.size} file(s)")
    if (truncated) sb.append(" (showing first $limit)")
    sb.append(":\n\n")
    shown.forEach { sb.append(it).append("\n") }
    return sb.toString().trim()
}
