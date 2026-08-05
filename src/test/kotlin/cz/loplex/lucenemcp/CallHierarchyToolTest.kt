package cz.loplex.lucenemcp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CallHierarchyToolTest {

    @Test
    fun `finds incoming calls to a function`(@TempDir tempDir: File) {
        File(tempDir, "Target.kt").writeText(
            """
            fun login() {}
            """.trimIndent()
        )
        
        File(tempDir, "Caller.kt").writeText(
            """
            fun startSession() {
                login()
            }
            """.trimIndent()
        )

        val result = runCallHierarchy(tempDir, "login", "incoming")
        
        assertTrue(result.contains("Incoming calls to 'login':"))
        assertTrue(result.contains("Caller.kt"))
        assertTrue(result.contains("startSession"))
    }

    @Test
    fun `finds outgoing calls from a function`(@TempDir tempDir: File) {
        File(tempDir, "Aggregator.kt").writeText(
            """
            fun fetchData() {
                val db = connectDb()
                db.query()
                parseResult()
            }
            """.trimIndent()
        )

        val result = runCallHierarchy(tempDir, "fetchData", "outgoing")
        
        assertTrue(result.contains("Outgoing calls from 'fetchData':"))
        assertTrue(result.contains("connectDb"))
        assertTrue(result.contains("query"))
        assertTrue(result.contains("parseResult"))
    }
}
