package cz.loplex.lucenemcp

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches parsed tree-sitter trees across `find_definition`/`find_references` calls, keyed by
 * absolute path and validated by mtime. Both tools otherwise reparse every project file on every
 * call, which dominates their cost on larger repos. A stale entry (mtime mismatch since last
 * parse) is silently reparsed and replaces itself — this is the same freshness guarantee as a
 * plain [parseFile] call, just skipping the reparse when nothing changed.
 *
 * One instance lives for the whole server process (see `Main.kt`); tests default to a fresh
 * instance per call via the tools' `astCache` parameter, so caching is opt-in for reuse without
 * needing to thread it through every existing test.
 */
class AstCache {
    private data class Entry(val mtime: Long, val parsed: ParsedFile)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun getOrParse(file: File, extension: String): ParsedFile? {
        val key = file.absolutePath
        val mtime = file.lastModified()
        entries[key]?.let { if (it.mtime == mtime) return it.parsed }

        val parsed = parseFile(file, extension)
        if (parsed == null) {
            entries.remove(key)
            return null
        }
        entries[key] = Entry(mtime, parsed)
        return parsed
    }

    /** Drops entries for files no longer part of the project (renamed/deleted) so a long-lived
     * server process doesn't accumulate cache garbage forever. Call after a full-project walk with
     * the absolute paths seen in that walk. */
    fun prune(liveAbsolutePaths: Set<String>) {
        entries.keys.retainAll(liveAbsolutePaths)
    }

    fun size(): Int = entries.size
}
