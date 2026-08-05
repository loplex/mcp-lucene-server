package cz.loplex.lucenemcp.index

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*
import cz.loplex.lucenemcp.*

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*

import org.apache.lucene.queryparser.classic.QueryParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IndexWatcherTest {

    private val analyzer = CodeAnalyzer()
    private val managersToClose = mutableListOf<IndexManager>()
    private val watchersToClose = mutableListOf<IndexWatcher>()
    private val rootsToClean = mutableListOf<File>()

    @AfterEach
    fun tearDown() {
        watchersToClose.forEach { it.close() }
        watchersToClose.clear()
        managersToClose.forEach { it.close() }
        managersToClose.clear()
        rootsToClean.forEach { cacheIndexPath(it).toFile().parentFile.deleteRecursively() }
        rootsToClean.clear()
    }

    private fun openWatched(root: File): IndexManager {
        rootsToClean.add(root)
        val manager = IndexManager(root, analyzer)
        managersToClose.add(manager)
        manager.sync()

        val watcher = IndexWatcher(root, manager, debounceMillis = 50)
        watchersToClose.add(watcher)
        assertTrue(watcher.start(), "watcher should register successfully on a plain temp directory")
        return manager
    }

    private fun hitCountFor(manager: IndexManager, query: String): Long {
        val parsed = QueryParser("content", analyzer).parse(query)
        return manager.searcher.search(parsed, 10).totalHits.value
    }

    private fun awaitTrue(timeoutMillis: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(condition(), "condition did not become true within ${timeoutMillis}ms")
    }

    @Test
    fun `creating a new file gets picked up without an explicit sync call`(@TempDir tempDir: File) {
        val manager = openWatched(tempDir)

        File(tempDir, "a.kt").writeText("class WatcherCreatedMarker")

        awaitTrue { hitCountFor(manager, "content:WatcherCreatedMarker") == 1L }
    }

    @Test
    fun `modifying a file gets picked up without an explicit sync call`(@TempDir tempDir: File) {
        val file = File(tempDir, "a.kt")
        file.writeText("class WatcherOldMarker")
        val manager = openWatched(tempDir)
        awaitTrue { hitCountFor(manager, "content:WatcherOldMarker") == 1L }

        file.writeText("class WatcherNewMarker")

        awaitTrue { hitCountFor(manager, "content:WatcherNewMarker") == 1L }
        assertEquals(0L, hitCountFor(manager, "content:WatcherOldMarker"))
    }

    @Test
    fun `deleting a file gets picked up without an explicit sync call`(@TempDir tempDir: File) {
        val file = File(tempDir, "a.kt")
        file.writeText("class WatcherGoneSoon")
        val manager = openWatched(tempDir)
        awaitTrue { hitCountFor(manager, "content:WatcherGoneSoon") == 1L }

        file.delete()

        awaitTrue { hitCountFor(manager, "content:WatcherGoneSoon") == 0L }
    }

    @Test
    fun `a file created inside a newly created subdirectory is watched too`(@TempDir tempDir: File) {
        val manager = openWatched(tempDir)

        val subDir = File(tempDir, "sub")
        subDir.mkdir()
        // Give the watcher a moment to notice and register the new directory before writing into it.
        Thread.sleep(200)
        File(subDir, "b.kt").writeText("class WatcherNestedMarker")

        awaitTrue { hitCountFor(manager, "content:WatcherNestedMarker") == 1L }
    }
}
