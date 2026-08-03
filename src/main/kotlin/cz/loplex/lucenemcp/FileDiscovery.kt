package cz.loplex.lucenemcp

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Directory names pruned during the fallback filesystem walk. Only used when [listProjectFiles]
 * cannot shell out to `git ls-files` (target directory is not a git repository).
 */
private val IGNORED_DIR_NAMES = setOf(
    ".git", ".idea", ".gradle", ".mvn", ".cache", ".next", ".venv", "venv",
    "node_modules", "target", "build", "dist", "out", "__pycache__"
)

const val MAX_INDEXABLE_FILE_BYTES = 2_000_000L

/**
 * Lists all project files that should be visible to search/grep/list tools.
 *
 * When [root] is inside a git repository, defers to `git ls-files -co --exclude-standard`
 * so results exactly match what `.gitignore` (and friends) would allow, without needing to
 * reimplement gitignore semantics. Falls back to a pruned filesystem walk otherwise.
 */
fun listProjectFiles(root: File): List<File> {
    tryGitLsFiles(root)?.let { return it }
    return walkWithIgnore(root)
}

private fun tryGitLsFiles(root: File): List<File>? {
    return try {
        val process = ProcessBuilder("git", "ls-files", "-co", "--exclude-standard")
            .directory(root)
            .redirectErrorStream(false)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null

        output.lineSequence()
            .filter { it.isNotBlank() }
            .map { File(root, it) }
            .filter { it.isFile }
            .toList()
    } catch (e: Exception) {
        null
    }
}

private fun walkWithIgnore(root: File): List<File> {
    return root.walkTopDown()
        .onEnter { dir -> dir.name !in IGNORED_DIR_NAMES }
        .filter { it.isFile }
        .toList()
}
