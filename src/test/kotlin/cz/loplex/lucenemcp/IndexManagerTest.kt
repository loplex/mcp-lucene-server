package cz.loplex.lucenemcp

import org.apache.lucene.queryparser.classic.QueryParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IndexManagerTest {

    private val analyzer = CodeAnalyzer()
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
}
