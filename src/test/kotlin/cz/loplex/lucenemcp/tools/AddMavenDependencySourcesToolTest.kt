package cz.loplex.lucenemcp.tools

import cz.loplex.lucenemcp.index.IndexManager
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.analysis.standard.StandardAnalyzer

class AddMavenDependencySourcesToolTest {

    @Test
    fun `test adding maven dependency success`(@TempDir tempDir: File) {
        val indexManager = IndexManager(tempDir, cz.loplex.lucenemcp.index.CodeAnalyzer())
        val args = JsonObject()
        // Use a tiny, extremely common library that is probably already cached
        args.addProperty("artifact", "org.slf4j:slf4j-api:1.7.36")
        
        val result = handleAddMavenDependencySources(args, indexManager)
        
        assertTrue(result.contains("Successfully downloaded and added sources"), "Expected success message, got: $result")
        assertTrue(indexManager.externalRoots.any { it.name.contains("slf4j-api-1.7.36-sources.jar") }, "Expected jar in external roots")
    }

    @Test
    fun `test invalid format`(@TempDir tempDir: File) {
        val indexManager = IndexManager(tempDir, cz.loplex.lucenemcp.index.CodeAnalyzer())
        val args = JsonObject()
        args.addProperty("artifact", "invalid-format")
        
        val result = handleAddMavenDependencySources(args, indexManager)
        
        assertTrue(result.contains("Invalid artifact format"), "Expected invalid format message")
    }
}
