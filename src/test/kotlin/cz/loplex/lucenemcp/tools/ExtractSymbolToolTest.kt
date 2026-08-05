package cz.loplex.lucenemcp.tools

import cz.loplex.lucenemcp.ast.AstCache
import cz.loplex.lucenemcp.index.CodeAnalyzer
import cz.loplex.lucenemcp.index.IndexManager
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ExtractSymbolToolTest {

    @Test
    fun `test extracting symbol`(@TempDir tempDir: File) {
        val root = File(tempDir, "src")
        root.mkdirs()
        val file = File(root, "Test.kt")
        file.writeText(
            """
            package com.example
            
            class MyService {
                fun doSomething() {
                    println("Hello")
                }
            }
            """.trimIndent()
        )

        val args = JsonObject()
        args.addProperty("symbol", "MyService")
        val result = runExtractSymbol(root, "MyService", 10)
        
        assertTrue(result.contains("Found 1 definition(s) for 'MyService'"))
        assertTrue(result.contains("class MyService {"))
        assertTrue(result.contains("fun doSomething()"))
    }
}
