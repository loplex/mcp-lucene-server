package cz.loplex.lucenemcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.highlight.Highlighter
import org.apache.lucene.search.highlight.QueryScorer
import org.apache.lucene.search.highlight.SimpleFragmenter
import org.apache.lucene.search.highlight.SimpleHTMLFormatter
import org.apache.lucene.store.ByteBuffersDirectory
import java.io.File
import java.io.StringReader
import java.util.Scanner
import kotlin.system.exitProcess

private const val SERVER_NAME = "mcp-lucene-server"
private const val SERVER_VERSION = "1.0.0"
private const val PROTOCOL_VERSION = "2024-11-05"
private const val DEFAULT_LIMIT = 10

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Error: missing required argument <project-directory>. Usage: mcp-lucene-server <absolute-path-to-project>")
        exitProcess(1)
    }

    val targetDir = File(args[0])
    if (!targetDir.isDirectory) {
        System.err.println("Error: '${targetDir.path}' is not a valid directory.")
        exitProcess(1)
    }

    val gson = Gson()
    val analyzer = CodeAnalyzer()
    val index = ByteBuffersDirectory()

    System.err.println("Indexing directory: ${targetDir.absolutePath}")
    indexDirectory(targetDir, index, analyzer)
    System.err.println("Indexing finished successfully.")

    val reader = DirectoryReader.open(index)
    val searcher = IndexSearcher(reader)

    val scanner = Scanner(System.`in`)
    System.err.println("MCP Lucene Server listening on stdin...")

    while (scanner.hasNextLine()) {
        val line = scanner.nextLine()
        if (line.isBlank()) continue

        try {
            val request = gson.fromJson(line, JsonObject::class.java)
            val method = request.get("method")?.asString
            val id = request.get("id")

            if (method == null) {
                System.err.println("Ignoring malformed request without 'method': $line")
                continue
            }

            // JSON-RPC notifications (e.g. "notifications/initialized") carry no "id" and must not be answered.
            if (id == null) {
                System.err.println("Received notification: $method")
                continue
            }

            val response = when (method) {
                "initialize" -> createInitResponse(id)
                "tools/list" -> createToolsListResponse(id)
                "tools/call" -> handleToolCall(id, request, searcher, analyzer)
                else -> createErrorResponse(id, -32601, "Method not found: $method")
            }

            println(gson.toJson(response))
            System.out.flush()
        } catch (e: Exception) {
            System.err.println("Error processing line: ${e.message}")
        }
    }
}

private fun indexDirectory(targetDir: File, index: ByteBuffersDirectory, analyzer: CodeAnalyzer) {
    val config = IndexWriterConfig(analyzer)
    val writer = IndexWriter(index, config)

    targetDir.walkTopDown().forEach { file ->
        if (file.isFile && !file.path.contains(".git") && !file.path.contains("node_modules")) {
            try {
                val relativePath = file.relativeTo(targetDir).path
                val doc = Document()
                doc.add(TextField("content", file.readText(), Field.Store.YES))
                doc.add(StringField("path", relativePath, Field.Store.YES))
                doc.add(StringField("filename", file.name, Field.Store.YES))
                doc.add(StringField("extension", file.extension, Field.Store.YES))
                writer.addDocument(doc)
            } catch (e: Exception) {
                System.err.println("Skip indexing (binary/unreadable file): ${file.path} (${e.message})")
            }
        }
    }
    writer.close()
}

fun createInitResponse(id: JsonElement): JsonObject {
    val res = baseResponse(id)
    val result = JsonObject()
    result.addProperty("protocolVersion", PROTOCOL_VERSION)
    val capabilities = JsonObject()
    capabilities.add("tools", JsonObject())
    result.add("capabilities", capabilities)
    val serverInfo = JsonObject()
    serverInfo.addProperty("name", SERVER_NAME)
    serverInfo.addProperty("version", SERVER_VERSION)
    result.add("serverInfo", serverInfo)
    res.add("result", result)
    return res
}

fun createToolsListResponse(id: JsonElement): JsonObject {
    val res = baseResponse(id)
    val result = JsonObject()
    val tools = JsonArray()

    val searchTool = JsonObject()
    searchTool.addProperty("name", "search_code")
    searchTool.addProperty(
        "description",
        "Lightning-fast search over the codebase using Apache Lucene syntax. Supports fields: content, path, filename, extension. Example: content:UserService AND extension:kt"
    )

    val inputSchema = JsonObject()
    inputSchema.addProperty("type", "object")

    val properties = JsonObject()

    val queryProp = JsonObject()
    queryProp.addProperty("type", "string")
    queryProp.addProperty("description", "Lucene search query (default field: content).")
    properties.add("query", queryProp)

    val limitProp = JsonObject()
    limitProp.addProperty("type", "number")
    limitProp.addProperty("description", "Maximum number of results (default 10).")
    properties.add("limit", limitProp)

    inputSchema.add("properties", properties)
    val required = JsonArray()
    required.add("query")
    inputSchema.add("required", required)

    searchTool.add("inputSchema", inputSchema)
    tools.add(searchTool)

    result.add("tools", tools)
    res.add("result", result)
    return res
}

fun handleToolCall(id: JsonElement, request: JsonObject, searcher: IndexSearcher, analyzer: CodeAnalyzer): JsonObject {
    val res = baseResponse(id)
    val params = request.getAsJsonObject("params")
    val toolName = params?.get("name")?.asString

    if (toolName != "search_code") {
        return createErrorResponse(id, -32602, "Unknown tool: $toolName")
    }

    val arguments = params.getAsJsonObject("arguments")
    val queryStr = arguments?.get("query")?.asString

    if (queryStr.isNullOrBlank()) {
        return createErrorResponse(id, -32602, "Missing required argument: query")
    }

    val limit = arguments.get("limit")?.asInt ?: DEFAULT_LIMIT

    val result = JsonObject()
    val contentArray = JsonArray()
    val textContent = JsonObject()
    textContent.addProperty("type", "text")

    try {
        val queryParser = QueryParser("content", analyzer)
        val query = queryParser.parse(queryStr)
        val docs = searcher.search(query, limit)

        val formatter = SimpleHTMLFormatter("**", "**")
        val scorer = QueryScorer(query)
        val highlighter = Highlighter(formatter, scorer)
        highlighter.textFragmenter = SimpleFragmenter(160)
        highlighter.maxDocCharsToAnalyze = 1_000_000

        val sb = StringBuilder()
        sb.append("Found ${docs.totalHits.value} result(s). Showing top ${docs.scoreDocs.size}:\n\n")

        for (scoreDoc in docs.scoreDocs) {
            val doc = searcher.storedFields().document(scoreDoc.doc)
            val path = doc.get("path")
            val fullText = doc.get("content")

            sb.append("--- FILE: $path ---\n")
            val snippet = try {
                val tokenStream = analyzer.tokenStream("content", StringReader(fullText))
                highlighter.getBestFragments(tokenStream, fullText, 3, "\n...\n")
            } catch (e: Exception) {
                null
            }

            if (snippet.isNullOrBlank()) {
                sb.append(fullText.lineSequence().take(5).joinToString("\n"))
            } else {
                sb.append(snippet)
            }
            sb.append("\n\n")
        }

        textContent.addProperty("text", sb.toString().trim())
    } catch (e: Exception) {
        textContent.addProperty("text", "Lucene error parsing query: ${e.message}")
    }

    contentArray.add(textContent)
    result.add("content", contentArray)
    res.add("result", result)
    return res
}

private fun baseResponse(id: JsonElement): JsonObject {
    val res = JsonObject()
    res.addProperty("jsonrpc", "2.0")
    res.add("id", id)
    return res
}

fun createErrorResponse(id: JsonElement, code: Int, msg: String): JsonObject {
    val res = baseResponse(id)
    val err = JsonObject()
    err.addProperty("code", code)
    err.addProperty("message", msg)
    res.add("error", err)
    return res
}
