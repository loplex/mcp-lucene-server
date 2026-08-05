package cz.loplex.lucenemcp.ast

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*
import cz.loplex.lucenemcp.*

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AstCacheTest {

    @Test
    fun `returns the same parsed instance when the file is unchanged`(@TempDir tempDir: File) {
        val file = File(tempDir, "A.kt").apply { writeText("class Foo") }
        val cache = AstCache()

        val first = cache.getOrParse(file, "kt")
        val second = cache.getOrParse(file, "kt")

        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun `reparses after the file is modified`(@TempDir tempDir: File) {
        val file = File(tempDir, "A.kt").apply { writeText("class Foo") }
        val cache = AstCache()

        val first = cache.getOrParse(file, "kt")
        Thread.sleep(1100) // ensure a distinct filesystem mtime (1s resolution on some filesystems)
        file.writeText("class Bar")
        val second = cache.getOrParse(file, "kt")

        assertNotNull(first)
        assertNotNull(second)
        assertNotSame(first, second)
        assertTrue(second!!.source.contains("Bar"))
    }

    @Test
    fun `prune drops entries for files no longer part of the project`(@TempDir tempDir: File) {
        val a = File(tempDir, "A.kt").apply { writeText("class Foo") }
        val b = File(tempDir, "B.kt").apply { writeText("class Bar") }
        val cache = AstCache()
        cache.getOrParse(a, "kt")
        cache.getOrParse(b, "kt")
        assertEquals(2, cache.size())

        cache.prune(setOf(a.absolutePath))

        assertEquals(1, cache.size())
    }

    @Test
    fun `an unparsable file is not left cached`(@TempDir tempDir: File) {
        val file = File(tempDir, "notes.txt").apply { writeText("plain text") }
        val cache = AstCache()

        val result = cache.getOrParse(file, "txt")

        assertEquals(null, result)
        assertFalse(cache.size() > 0)
    }
}
