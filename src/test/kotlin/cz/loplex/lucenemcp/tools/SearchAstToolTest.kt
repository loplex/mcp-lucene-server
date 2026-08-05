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

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SearchAstToolTest {

    @Test
    fun `executes structural query across files`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText(
            """
            class App {
                fun start() {}
            }
            """.trimIndent()
        )
        
        File(tempDir, "Main.kt").writeText(
            """
            fun main() {
                val app = App()
                app.start()
            }
            """.trimIndent()
        )

        val query = "(function_declaration (simple_identifier) @fn_name)"
        val result = runSearchAst(tempDir, query, "kotlin", null)
        
        assertTrue(result.contains("Found 2 capture(s) across the project:"))
        assertTrue(result.contains("App.kt"))
        assertTrue(result.contains("start"))
        assertTrue(result.contains("Main.kt"))
        assertTrue(result.contains("main"))
    }

    @Test
    fun `filters by path pattern`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText("fun a() {}")
        File(tempDir, "Test.kt").writeText("fun b() {}")

        val query = "(function_declaration) @fun"
        val result = runSearchAst(tempDir, query, "kotlin", "Test.kt")
        
        assertTrue(result.contains("Found 1 capture(s) across the project:"))
        assertTrue(result.contains("Test.kt"))
        assertTrue(!result.contains("App.kt"))
    }
}
