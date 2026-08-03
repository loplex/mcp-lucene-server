package cz.loplex.lucenemcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ListFilesToolTest {

    @Test
    fun `lists all files when no pattern is given`(@TempDir tempDir: File) {
        File(tempDir, "a.kt").writeText("a")
        File(tempDir, "b.md").writeText("b")

        val result = runListFiles(tempDir, null, 100)

        assertTrue(result.contains("a.kt"))
        assertTrue(result.contains("b.md"))
    }

    @Test
    fun `glob pattern filters by extension`(@TempDir tempDir: File) {
        File(tempDir, "a.kt").writeText("a")
        File(tempDir, "b.md").writeText("b")

        val result = runListFiles(tempDir, "*.kt", 100)

        assertTrue(result.contains("a.kt"))
        assertFalse(result.contains("b.md"))
    }

    @Test
    fun `result is truncated to the requested limit`(@TempDir tempDir: File) {
        repeat(5) { File(tempDir, "file$it.txt").writeText("x") }

        val result = runListFiles(tempDir, null, 2)

        assertTrue(result.contains("Found 5 file(s) (showing first 2)"))
    }

    @Test
    fun `no matches produces an explicit message`(@TempDir tempDir: File) {
        File(tempDir, "a.kt").writeText("a")

        val result = runListFiles(tempDir, "*.does-not-exist", 100)

        assertTrue(result.contains("No files found"))
    }
}
