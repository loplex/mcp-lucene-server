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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileDiscoveryTest {

    @Test
    fun `git repo respects gitignore via git ls-files`(@TempDir tempDir: File) {
        initGitRepo(tempDir)
        File(tempDir, ".gitignore").writeText("ignored.txt\n")
        File(tempDir, "tracked.txt").writeText("hello")
        File(tempDir, "ignored.txt").writeText("bye")

        val paths = listProjectFiles(tempDir).map { it.name }

        assertTrue(paths.contains("tracked.txt"))
        assertTrue(paths.contains(".gitignore"))
        assertFalse(paths.contains("ignored.txt"))
    }

    @Test
    fun `non-git directory falls back to walk pruning known noise directories`(@TempDir tempDir: File) {
        File(tempDir, "src").mkdirs()
        File(tempDir, "src/App.kt").writeText("class App")
        File(tempDir, "node_modules").mkdirs()
        File(tempDir, "node_modules/lib.js").writeText("noise")
        File(tempDir, "target").mkdirs()
        File(tempDir, "target/App.class").writeText("noise")

        val relativePaths = listProjectFiles(tempDir).map { it.relativeTo(tempDir).path }

        assertTrue(relativePaths.any { it.endsWith("App.kt") })
        assertFalse(relativePaths.any { it.contains("node_modules") })
        assertFalse(relativePaths.any { it.contains("target") })
    }

    private fun initGitRepo(dir: File) {
        val process = ProcessBuilder("git", "init", "-q")
            .directory(dir)
            .start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "git init timed out")
    }

    @Test
    fun `unpacks and lists files from -sources jar`(@TempDir tempDir: File) {
        val sourcesJar = File(tempDir, "library-1.0-sources.jar")
        ZipOutputStream(FileOutputStream(sourcesJar)).use { zout ->
            val entry = ZipEntry("com/example/TestClass.java")
            zout.putNextEntry(entry)
            zout.write("public class TestClass {}".toByteArray())
            zout.closeEntry()
        }

        val discovered = listProjectFiles(tempDir, listOf(sourcesJar))
        
        assertTrue(discovered.any { it.name == "TestClass.java" }, "Should discover the file unpacked from the sources jar")
    }
}
