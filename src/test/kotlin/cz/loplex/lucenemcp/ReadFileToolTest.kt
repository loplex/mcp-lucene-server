package cz.loplex.lucenemcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReadFileToolTest {

    private fun fixture(tempDir: File): File {
        val file = File(tempDir, "sample.txt")
        file.writeText((1..5).joinToString("\n") { "line$it" })
        return file
    }

    @Test
    fun `reads whole file with line numbers by default`(@TempDir tempDir: File) {
        fixture(tempDir)
        val result = readFileRange(tempDir, "sample.txt", null, null)

        assertTrue(result.contains("1: line1"))
        assertTrue(result.contains("5: line5"))
    }

    @Test
    fun `reads a specific line range`(@TempDir tempDir: File) {
        fixture(tempDir)
        val result = readFileRange(tempDir, "sample.txt", 2, 4)

        assertFalse(result.contains("1: line1"))
        assertTrue(result.contains("2: line2"))
        assertTrue(result.contains("3: line3"))
        assertTrue(result.contains("4: line4"))
        assertFalse(result.contains("5: line5"))
    }

    @Test
    fun `an empty range after clamping produces an explicit message`(@TempDir tempDir: File) {
        fixture(tempDir)
        val result = readFileRange(tempDir, "sample.txt", 50, 60)

        assertTrue(result.contains("Requested range is empty"))
    }

    @Test
    fun `path escaping the project root is rejected`(@TempDir tempDir: File) {
        val outside = File(tempDir.parentFile, "outside-${System.nanoTime()}.txt")
        outside.writeText("secret")
        try {
            val project = File(tempDir, "project").also { it.mkdirs() }
            val result = readFileRange(project, "../${outside.name}", null, null)
            assertTrue(result.contains("escapes the project directory"))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `oversized files are rejected instead of being read fully`(@TempDir tempDir: File) {
        val big = File(tempDir, "big.txt")
        big.writeBytes(ByteArray((MAX_INDEXABLE_FILE_BYTES + 1).toInt()))

        val result = readFileRange(tempDir, "big.txt", null, null)

        assertTrue(result.contains("larger than"))
    }

    @Test
    fun `missing file produces an explicit error`(@TempDir tempDir: File) {
        val result = readFileRange(tempDir, "does-not-exist.txt", null, null)
        assertTrue(result.contains("is not a file"))
    }
}
