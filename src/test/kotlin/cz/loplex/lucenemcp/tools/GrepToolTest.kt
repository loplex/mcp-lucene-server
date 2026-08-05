package cz.loplex.lucenemcp.tools

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

class GrepToolTest {

    private fun fixture(tempDir: File): File {
        File(tempDir, "a.txt").writeText("hello world\nfoo BAR\nHELLO again\n")
        File(tempDir, "b").mkdirs()
        File(tempDir, "b/nested.kt").writeText("fun foo() {}\nval bar = 1\n")
        return tempDir
    }

    @Test
    fun `literal search is case-sensitive by default`(@TempDir tempDir: File) {
        fixture(tempDir)
        val result = runGrep(tempDir, GrepOptions(pattern = "foo", literal = true))

        assertTrue(result.contains("a.txt:2"))
        assertTrue(result.contains("b/nested.kt:1"))
        assertFalse(result.contains("HELLO"))
    }

    @Test
    fun `case-insensitive search matches differently-cased lines`(@TempDir tempDir: File) {
        fixture(tempDir)
        val result = runGrep(tempDir, GrepOptions(pattern = "hello", literal = true, caseSensitive = false))

        assertTrue(result.contains("hello world"))
        assertTrue(result.contains("HELLO again"))
    }

    @Test
    fun `context lines surround the match`(@TempDir tempDir: File) {
        fixture(tempDir)
        val result = runGrep(
            tempDir,
            GrepOptions(pattern = "foo", literal = true, beforeContext = 0, afterContext = 1, filePattern = "**/*.kt")
        )

        assertTrue(result.contains("fun foo() {}"))
        assertTrue(result.contains("val bar = 1"))
        assertFalse(result.contains("a.txt"))
    }

    @Test
    fun `files_with_matches mode lists only paths`(@TempDir tempDir: File) {
        fixture(tempDir)
        val result = runGrep(tempDir, GrepOptions(pattern = "foo", literal = true, outputMode = "files_with_matches"))

        assertTrue(result.contains("a.txt"))
        assertTrue(result.contains("b/nested.kt"))
        assertFalse(result.contains(">")) // no content markers in this mode
    }

    @Test
    fun `count mode reports per-file and total match counts`(@TempDir tempDir: File) {
        fixture(tempDir)
        val result = runGrep(tempDir, GrepOptions(pattern = "o", literal = true, outputMode = "count"))

        assertTrue(result.contains("Total matches:"))
    }

    @Test
    fun `invalid regex returns a readable error instead of throwing`(@TempDir tempDir: File) {
        fixture(tempDir)
        val result = runGrep(tempDir, GrepOptions(pattern = "(unclosed", literal = false))

        assertTrue(result.startsWith("Invalid regex pattern"))
    }

    @Test
    fun `no matches returns an explicit message`(@TempDir tempDir: File) {
        fixture(tempDir)
        val result = runGrep(tempDir, GrepOptions(pattern = "doesNotExistAnywhere", literal = true))

        assertTrue(result.contains("No matches found"))
    }
}
