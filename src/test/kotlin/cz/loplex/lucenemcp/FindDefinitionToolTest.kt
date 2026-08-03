package cz.loplex.lucenemcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FindDefinitionToolTest {

    @Test
    fun `finds a Kotlin class and function definition, not a call site`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText(
            """
            class UserService {
                fun login() {}
            }
            fun main() {
                val service = UserService()
                service.login()
            }
            """.trimIndent()
        )

        val classResult = runFindDefinition(tempDir, "UserService", 50)
        assertTrue(classResult.contains("App.kt:1"))
        assertTrue(classResult.contains("[class]"))
        assertFalse(classResult.contains("App.kt:6")) // constructor call site, not the definition

        val funResult = runFindDefinition(tempDir, "login", 50)
        assertTrue(funResult.contains("App.kt:2"))
        assertTrue(funResult.contains("[function]"))
        assertFalse(funResult.contains("App.kt:7")) // service.login() call site
    }

    @Test
    fun `finds a Java method definition, not an unrelated mention`(@TempDir tempDir: File) {
        File(tempDir, "Greeter.java").writeText(
            """
            public class Greeter {
                public String greet() {
                    return "hi";
                }
            }
            """.trimIndent()
        )

        val result = runFindDefinition(tempDir, "greet", 50)
        assertTrue(result.contains("Greeter.java:2"))
        assertTrue(result.contains("[method]"))
    }

    @Test
    fun `finds a Python function and class definition`(@TempDir tempDir: File) {
        File(tempDir, "app.py").writeText(
            """
            class Widget:
                pass

            def build_widget():
                return Widget()
            """.trimIndent()
        )

        val funResult = runFindDefinition(tempDir, "build_widget", 50)
        assertTrue(funResult.contains("app.py:4"))
        assertTrue(funResult.contains("[function]"))

        val classResult = runFindDefinition(tempDir, "Widget", 50)
        assertTrue(classResult.contains("app.py:1"))
        assertTrue(classResult.contains("[class]"))
        assertFalse(classResult.contains("app.py:5")) // Widget() call site
    }

    @Test
    fun `finds a Go function and a Rust struct definition`(@TempDir tempDir: File) {
        File(tempDir, "main.go").writeText("func Handle(w int) {}\n")
        File(tempDir, "lib.rs").writeText("struct Handle {\n    id: u32,\n}\n")

        val goResult = runFindDefinition(tempDir, "Handle", 50)
        assertTrue(goResult.contains("main.go:1"))
        assertTrue(goResult.contains("[function]"))
        assertTrue(goResult.contains("lib.rs:1"))
        assertTrue(goResult.contains("[struct]"))
    }

    @Test
    fun `unsupported extensions are silently skipped`(@TempDir tempDir: File) {
        File(tempDir, "notes.txt").writeText("class NotReallyAClass\n")

        val result = runFindDefinition(tempDir, "NotReallyAClass", 50)
        assertTrue(result.contains("No definition found"))
    }

    @Test
    fun `blank symbol is a clean error`(@TempDir tempDir: File) {
        val result = runFindDefinition(tempDir, "  ", 50)
        assertTrue(result.contains("Missing required argument"))
    }

    @Test
    fun `non-identifier symbol is rejected with a clean error`(@TempDir tempDir: File) {
        val result = runFindDefinition(tempDir, "not an identifier!", 50)
        assertTrue(result.contains("Invalid symbol"))
    }

    @Test
    fun `a symbol only mentioned in a comment or string literal is not a definition`(@TempDir tempDir: File) {
        File(tempDir, "Sample.kt").writeText(
            """
            package test

            // This comment mentions class Foo but it is not a real definition
            val commentTrap = "class Foo { val Foo = 1 }"
            """.trimIndent()
        )

        val result = runFindDefinition(tempDir, "Foo", 50)
        assertTrue(result.contains("No definition found"))
    }

    @Test
    fun `maxMatches caps the number of returned definitions`(@TempDir tempDir: File) {
        val sb = StringBuilder()
        repeat(5) { i -> sb.append("class Dup$i {}\nfun helper() {}\n") }
        File(tempDir, "many.kt").writeText(sb.toString())

        val matches = findDefinitions(tempDir, "helper", maxMatches = 2)
        assertTrue(matches.size <= 2)
    }
}
