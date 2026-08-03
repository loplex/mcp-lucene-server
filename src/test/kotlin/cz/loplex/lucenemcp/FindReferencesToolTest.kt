package cz.loplex.lucenemcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FindReferencesToolTest {

    @Test
    fun `finds real usages of a Kotlin class, classified as definition, call and type`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText(
            """
            class UserService {
                fun login() {}
            }
            class Client(val service: UserService) {
                fun run() {
                    val service = UserService()
                    service.login()
                }
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "UserService", 50)
        assertTrue(result.contains("App.kt:1 [definition]"))
        assertTrue(result.contains("App.kt:4 [type]")) // constructor parameter type
        assertTrue(result.contains("App.kt:6 [call]")) // UserService() constructor call
    }

    @Test
    fun `a symbol only mentioned in a comment or string literal is not a reference`(@TempDir tempDir: File) {
        File(tempDir, "Sample.kt").writeText(
            """
            package test

            class RealTarget {
                fun useIt() {
                    val x = "RealTarget is just a string here, not a reference"
                    println(x)
                }
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "RealTarget", 50)
        // Only the declaration itself is a real occurrence; the string mention must not count.
        assertTrue(result.contains("Found 1 reference"))
        assertTrue(result.contains("[definition]"))
    }

    @Test
    fun `finds a Java method call site`(@TempDir tempDir: File) {
        File(tempDir, "Greeter.java").writeText(
            """
            public class Greeter {
                public String greet() {
                    return "hi";
                }
                public void run() {
                    greet();
                }
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "greet", 50)
        assertTrue(result.contains("Greeter.java:2 [definition]"))
        assertTrue(result.contains("Greeter.java:6 [call]"))
    }

    @Test
    fun `blank symbol is a clean error`(@TempDir tempDir: File) {
        val result = runFindReferences(tempDir, "  ", 50)
        assertTrue(result.contains("Missing required argument"))
    }

    @Test
    fun `non-identifier symbol is rejected with a clean error`(@TempDir tempDir: File) {
        val result = runFindReferences(tempDir, "not an identifier!", 50)
        assertTrue(result.contains("Invalid symbol"))
    }

    @Test
    fun `no matches gives a clean message`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText("fun main() {}\n")

        val result = runFindReferences(tempDir, "NeverMentioned", 50)
        assertTrue(result.contains("No references found"))
        assertFalse(result.contains("Exception"))
    }

    @Test
    fun `maxMatches caps the number of returned references`(@TempDir tempDir: File) {
        val sb = StringBuilder("fun helper() {}\n")
        repeat(5) { sb.append("val x = helper()\n") }
        File(tempDir, "many.kt").writeText(sb.toString())

        val matches = findReferences(tempDir, "helper", maxMatches = 2)
        assertTrue(matches.size <= 2)
    }
}
