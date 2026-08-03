package cz.loplex.lucenemcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlobToRegexTest {

    @Test
    fun `star matches within a single path segment only`() {
        val regex = globToRegex("*.kt")
        assertTrue(regex.matches("Main.kt"))
        assertFalse(regex.matches("src/Main.kt"))
    }

    @Test
    fun `double star matches across path segments`() {
        val regex = globToRegex("src/**/*.kt")
        assertTrue(regex.matches("src/cz/loplex/Main.kt"))
        assertTrue(regex.matches("src/Main.kt"))
        assertFalse(regex.matches("test/Main.kt"))
    }

    @Test
    fun `question mark matches exactly one character excluding slash`() {
        val regex = globToRegex("a?.txt")
        assertTrue(regex.matches("ab.txt"))
        assertFalse(regex.matches("a/.txt"))
        assertFalse(regex.matches("abc.txt"))
    }

    @Test
    fun `special regex characters in the glob are escaped literally`() {
        val regex = globToRegex("file(1).txt")
        assertTrue(regex.matches("file(1).txt"))
    }
}
