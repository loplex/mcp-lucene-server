package cz.loplex.lucenemcp

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.NIOFSDirectory
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest

data class SyncResult(val added: Int, val updated: Int, val deleted: Int) {
    val changed: Boolean get() = added > 0 || updated > 0 || deleted > 0
}

/**
 * Owns a persistent, on-disk Lucene index for [root] and keeps it in sync with the filesystem.
 * [sync] performs a cheap mtime-based diff (stat only, no re-read of unchanged files) so it is safe
 * to call before every search — search_code is never stale, and restarts skip a full rebuild.
 */
class IndexManager(private val root: File, private val analyzer: Analyzer) {
    private val indexDirectory = NIOFSDirectory(cacheIndexPath(root))
    private val writer = IndexWriter(indexDirectory, IndexWriterConfig(analyzer))
    private var reader: DirectoryReader
    var searcher: IndexSearcher
        private set

    init {
        writer.commit()
        reader = DirectoryReader.open(writer)
        searcher = IndexSearcher(reader)
    }

    @Synchronized
    fun sync(): SyncResult {
        val currentFiles = listProjectFiles(root)
            .filter { it.length() <= MAX_INDEXABLE_FILE_BYTES }
            .associateBy { it.relativeTo(root).path.replace(File.separatorChar, '/') }

        val existingMtimes = loadExistingMtimes()

        var added = 0
        var updated = 0
        for ((relativePath, file) in currentFiles) {
            val mtime = file.lastModified()
            val existingMtime = existingMtimes[relativePath]
            if (existingMtime == mtime) continue

            val content = try {
                file.readText()
            } catch (e: Exception) {
                continue
            }

            val doc = Document()
            doc.add(TextField("content", content, Field.Store.YES))
            // Same text, indexed via WordAnalyzer (see PerFieldAnalyzerWrapper in Main.kt) — one
            // position slot per whole identifier, so words:"a b"~N proximity/phrase queries count
            // real words apart instead of getting lost in content's camelCase-splitting positions.
            doc.add(TextField("words", content, Field.Store.NO))
            doc.add(StringField("path", relativePath, Field.Store.YES))
            doc.add(StringField("filename", file.name, Field.Store.YES))
            doc.add(StringField("extension", file.extension, Field.Store.YES))
            doc.add(StoredField("mtime", mtime))
            writer.updateDocument(Term("path", relativePath), doc)

            if (existingMtime == null) added++ else updated++
        }

        var deleted = 0
        for (relativePath in existingMtimes.keys) {
            if (relativePath !in currentFiles) {
                writer.deleteDocuments(Term("path", relativePath))
                deleted++
            }
        }

        val result = SyncResult(added, updated, deleted)
        if (result.changed) {
            writer.commit()
            val refreshed = DirectoryReader.openIfChanged(reader, writer)
            if (refreshed != null) {
                reader.close()
                reader = refreshed
                searcher = IndexSearcher(reader)
            }
        }
        return result
    }

    private fun loadExistingMtimes(): Map<String, Long> {
        val result = HashMap<String, Long>()
        for (leafContext in reader.leaves()) {
            val leafReader = leafContext.reader()
            val liveDocs = leafReader.liveDocs
            val storedFields = leafReader.storedFields()
            for (docId in 0 until leafReader.maxDoc()) {
                if (liveDocs != null && !liveDocs.get(docId)) continue
                val doc = storedFields.document(docId, setOf("path", "mtime"))
                val path = doc.get("path") ?: continue
                val mtime = doc.getField("mtime")?.numericValue()?.toLong() ?: continue
                result[path] = mtime
            }
        }
        return result
    }

    fun close() {
        reader.close()
        writer.close()
        indexDirectory.close()
    }
}

fun cacheIndexPath(root: File): java.nio.file.Path {
    val canonical = root.canonicalFile.path
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
    val hash = digest.joinToString("") { "%02x".format(it) }.take(16)
    val cacheHome = System.getenv("XDG_CACHE_HOME") ?: (System.getProperty("user.home") + "/.cache")
    val dir = Paths.get(cacheHome, "mcp-lucene-server", "${root.name}-$hash", "index")
    dir.toFile().mkdirs()
    return dir
}
