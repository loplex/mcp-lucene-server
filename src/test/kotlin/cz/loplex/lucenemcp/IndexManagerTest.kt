package cz.loplex.lucenemcp

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.apache.lucene.queryparser.classic.QueryParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IndexManagerTest {

    // Mirrors production (Main.kt): "words" gets WordAnalyzer, every other field keeps CodeAnalyzer.
    private val analyzer: Analyzer = PerFieldAnalyzerWrapper(CodeAnalyzer(), mapOf("words" to WordAnalyzer()))
    private val managersToClose = mutableListOf<IndexManager>()
    private val rootsToClean = mutableListOf<File>()

    @AfterEach
    fun tearDown() {
        managersToClose.forEach { it.close() }
        managersToClose.clear()
        rootsToClean.forEach { cacheIndexPath(it).toFile().parentFile.deleteRecursively() }
        rootsToClean.clear()
    }

    private fun open(root: File): IndexManager {
        rootsToClean.add(root)
        val manager = IndexManager(root, analyzer)
        managersToClose.add(manager)
        return manager
    }

    private fun hitCountFor(manager: IndexManager, query: String): Long {
        val parsed = QueryParser("content", analyzer).parse(query)
        return manager.searcher.search(parsed, 10).totalHits.value
    }

    private fun hitCountForWords(manager: IndexManager, query: String): Long {
        val parsed = QueryParser("words", analyzer).parse(query)
        return manager.searcher.search(parsed, 10).totalHits.value
    }

    @Test
    fun `initial sync indexes every discoverable file`(@TempDir tempDir: File) {
        File(tempDir, "a.kt").writeText("class UniqueMarkerAlpha")
        File(tempDir, "b.kt").writeText("class UniqueMarkerBeta")

        val manager = open(tempDir)
        val result = manager.sync()

        assertEquals(2, result.added)
        assertEquals(1L, hitCountFor(manager, "content:UniqueMarkerAlpha"))
        assertEquals(1L, hitCountFor(manager, "content:UniqueMarkerBeta"))
    }

    @Test
    fun `re-syncing with no filesystem changes touches nothing`(@TempDir tempDir: File) {
        File(tempDir, "a.kt").writeText("class Foo")
        val manager = open(tempDir)
        manager.sync()

        val result = manager.sync()

        assertFalse(result.changed)
    }

    @Test
    fun `modifying a file after initial sync is picked up as an update`(@TempDir tempDir: File) {
        val file = File(tempDir, "a.kt")
        file.writeText("class OldMarker")
        val manager = open(tempDir)
        manager.sync()
        assertEquals(1L, hitCountFor(manager, "content:OldMarker"))

        Thread.sleep(1100) // ensure a distinct filesystem mtime (1s resolution on some filesystems)
        file.writeText("class NewMarker")
        val result = manager.sync()

        assertEquals(1, result.updated)
        assertEquals(0L, hitCountFor(manager, "content:OldMarker"))
        assertEquals(1L, hitCountFor(manager, "content:NewMarker"))
    }

    @Test
    fun `deleting a file after initial sync removes it from the index`(@TempDir tempDir: File) {
        val file = File(tempDir, "a.kt")
        file.writeText("class GoneSoon")
        val manager = open(tempDir)
        manager.sync()
        assertEquals(1L, hitCountFor(manager, "content:GoneSoon"))

        file.delete()
        val result = manager.sync()

        assertEquals(1, result.deleted)
        assertEquals(0L, hitCountFor(manager, "content:GoneSoon"))
    }

    @Test
    fun `phrase and proximity queries match, proving token positions survive indexing`(@TempDir tempDir: File) {
        // WordDelimiterGraphFilter emits a token graph; without FlattenGraphFilter, indexed
        // positions are corrupted and phrase/proximity queries silently match nothing, even though
        // plain term queries (which ignore position) keep working. Plain, undecomposed words expose
        // this cleanly, without WordDelimiterGraphFilter's separate camelCase-splitting behavior
        // (which inflates position counts on both the query and the document side independently —
        // see NOTES/AI/plan.md step 13 for why that case needs a different field, not this fix).
        File(tempDir, "a.kt").writeText("alpha beta gamma delta")
        val manager = open(tempDir)
        manager.sync()

        assertEquals(1L, hitCountFor(manager, "content:\"alpha beta\""))
        assertEquals(1L, hitCountFor(manager, "content:\"alpha gamma\"~2"))
        assertEquals(0L, hitCountFor(manager, "content:\"alpha gamma\""))
    }

    @Test
    fun `words field finds two identifiers within proximity, unlike content on the same text`(@TempDir tempDir: File) {
        // content's WordDelimiterGraphFilter splits ConfigLoader/DatabasePool into word parts on
        // both the query and the document side independently, inflating position counts so the two
        // sides never line back up — confirmed empirically to return zero hits even at slop 100 (see
        // NOTES/AI/plan.md step 13). words uses WordAnalyzer instead: one identifier, one position.
        File(tempDir, "a.kt").writeText(
            """
            class ConfigLoader {
                fun load() {}
            }

            fun setup() {
                val db = DatabasePool
            }
            """.trimIndent()
        )
        val manager = open(tempDir)
        manager.sync()

        assertEquals(0L, hitCountFor(manager, "content:\"ConfigLoader DatabasePool\"~100"))
        assertEquals(1L, hitCountForWords(manager, "words:\"ConfigLoader DatabasePool\"~10"))
        assertEquals(0L, hitCountForWords(manager, "words:\"ConfigLoader DatabasePool\"~3"))
    }

    @Test
    fun `words field does not match a partial camelCase word part, unlike content`(@TempDir tempDir: File) {
        File(tempDir, "a.kt").writeText("class ConfigLoader")
        val manager = open(tempDir)
        manager.sync()

        assertEquals(1L, hitCountFor(manager, "content:Loader")) // content still splits camelCase
        assertEquals(0L, hitCountForWords(manager, "words:Loader")) // words treats it as one term
        assertEquals(1L, hitCountForWords(manager, "words:ConfigLoader"))
    }

    @Test
    fun `index survives a server restart without a full rebuild`(@TempDir tempDir: File) {
        File(tempDir, "a.kt").writeText("class SurvivesRestart")
        val first = open(tempDir)
        first.sync()
        first.close()
        managersToClose.remove(first)

        val second = IndexManager(tempDir, analyzer)
        managersToClose.add(second)

        assertEquals(1L, hitCountFor(second, "content:SurvivesRestart"))
    }

    @Test
    fun `retains and removes externalRoots automatically across restarts`(@TempDir tempDir: File, @TempDir extDir: File) {
        val rootApp = File(tempDir, "App.kt").apply { writeText("fun rootApp() {}") }
        val extApp = File(extDir, "ExtApp.kt").apply { writeText("fun extApp() {}") }

        // 1. Initial start with external roots
        val manager1 = IndexManager(tempDir, analyzer, listOf(extDir))
        rootsToClean.add(tempDir)
        managersToClose.add(manager1)
        val result1 = manager1.sync()
        assertEquals(2, result1.added)
        assertEquals(1L, hitCountFor(manager1, "content:rootApp"))
        assertEquals(1L, hitCountFor(manager1, "content:extApp"))

        // 2. Add another external root dynamically
        val extDir2 = File(tempDir.parentFile, "ext2").apply { mkdir() }
        File(extDir2, "ExtApp2.kt").writeText("fun extApp2() {}")
        manager1.externalRoots = manager1.externalRoots + extDir2
        val result2 = manager1.sync()
        assertEquals(1, result2.added)
        assertEquals(1L, hitCountFor(manager1, "content:extApp2"))

        // Close to flush everything to disk
        manager1.close()
        managersToClose.remove(manager1)

        // 3. Restart daemon without external roots
        val manager2 = IndexManager(tempDir, analyzer, emptyList())
        managersToClose.add(manager2)
        val result3 = manager2.sync()
        // It should delete the two external files (ExtApp.kt and ExtApp2.kt)
        assertEquals(2, result3.deleted)
        assertEquals(1L, hitCountFor(manager2, "content:rootApp"))
        assertEquals(0L, hitCountFor(manager2, "content:extApp"))
        assertEquals(0L, hitCountFor(manager2, "content:extApp2"))
        
        extDir2.deleteRecursively()
    }
}
