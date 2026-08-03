package cz.loplex.lucenemcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OutlineToolTest {

    @Test
    fun `lists a Kotlin class's members in source order`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText(
            """
            class UserService {
                val name = "svc"
                fun login() {}
                fun logout() {}
            }
            fun main() {}
            """.trimIndent()
        )

        val result = runOutline(tempDir, "App.kt")
        assertTrue(result.contains("Found 5 symbol(s)"))

        val loginLine = result.indexOf("App.kt:3 [function]")
        val logoutLine = result.indexOf("App.kt:4 [function]")
        val classLine = result.indexOf("App.kt:1 [class]")
        val mainLine = result.indexOf("App.kt:6 [function]")
        assertTrue(classLine in 0 until loginLine)
        assertTrue(loginLine in 0 until logoutLine)
        assertTrue(logoutLine in 0 until mainLine)
    }

    @Test
    fun `a symbol only mentioned in a comment or string is not a definition`(@TempDir tempDir: File) {
        // The string literal's line text still surfaces "StringTrap" as part of the property
        // definition's displayed context — that's expected (outline shows the whole source line,
        // not just the matched name) — the actual check is that it is not its own [class] entry.
        File(tempDir, "Sample.kt").writeText(
            """
            // fun commentTrap() {}
            val strLiteral = "class StringTrap {}"
            fun real() {}
            """.trimIndent()
        )

        val result = runOutline(tempDir, "Sample.kt")
        assertFalse(result.contains("commentTrap"))
        assertFalse(result.contains("[class]"))
        assertTrue(result.contains("Found 2 symbol(s)"))
        assertTrue(result.contains("[function]"))
        assertTrue(result.contains("real"))
    }

    @Test
    fun `a file with no definitions reports no symbols`(@TempDir tempDir: File) {
        File(tempDir, "Empty.kt").writeText("// nothing here\n")

        val result = runOutline(tempDir, "Empty.kt")
        assertTrue(result.contains("No symbols found"))
    }

    @Test
    fun `unsupported extension is a clean error, not a crash`(@TempDir tempDir: File) {
        File(tempDir, "notes.txt").writeText("class NotReallyAClass\n")

        val result = runOutline(tempDir, "notes.txt")
        assertTrue(result.contains("unsupported extension"))
    }

    @Test
    fun `missing file produces an explicit error`(@TempDir tempDir: File) {
        val result = runOutline(tempDir, "does-not-exist.kt")
        assertTrue(result.contains("is not a file"))
    }

    @Test
    fun `path escaping the project root is rejected`(@TempDir tempDir: File) {
        val outside = File(tempDir.parentFile, "outside-${System.nanoTime()}.kt")
        outside.writeText("class Secret {}\n")
        try {
            val project = File(tempDir, "project").also { it.mkdirs() }
            val result = runOutline(project, "../${outside.name}")
            assertTrue(result.contains("escapes the project directory"))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `oversized files are rejected instead of being parsed`(@TempDir tempDir: File) {
        val big = File(tempDir, "big.kt")
        big.writeBytes(ByteArray((MAX_INDEXABLE_FILE_BYTES + 1).toInt()))

        val result = runOutline(tempDir, "big.kt")
        assertTrue(result.contains("larger than"))
    }

    @Test
    fun `a Python class and its methods are listed`(@TempDir tempDir: File) {
        File(tempDir, "app.py").writeText(
            """
            class Widget:
                def build(self):
                    pass

            def main():
                pass
            """.trimIndent()
        )

        val result = runOutline(tempDir, "app.py")
        assertTrue(result.contains("app.py:1 [class]"))
        assertTrue(result.contains("app.py:2 [function]"))
        assertTrue(result.contains("app.py:5 [function]"))
    }

    @Test
    fun `a shared AstCache still reflects file edits across calls`(@TempDir tempDir: File) {
        val file = File(tempDir, "App.kt")
        file.writeText("fun old() {}\n")
        val cache = AstCache()

        val before = runOutline(tempDir, "App.kt", cache)
        assertTrue(before.contains("old"))
        assertFalse(before.contains("brandNew"))

        Thread.sleep(1100) // ensure a distinct filesystem mtime (1s resolution on some filesystems)
        file.writeText("fun brandNew() {}\n")
        val after = runOutline(tempDir, "App.kt", cache)

        assertTrue(after.contains("brandNew"))
        assertFalse(after.contains("old"))
    }
}
