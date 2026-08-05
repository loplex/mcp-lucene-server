package cz.loplex.lucenemcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.highlight.Highlighter
import org.apache.lucene.search.highlight.QueryScorer
import org.apache.lucene.search.highlight.SimpleFragmenter
import org.apache.lucene.search.highlight.SimpleHTMLFormatter
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Scanner
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import com.sun.net.httpserver.HttpServer

private const val SERVER_NAME = "mcp-lucene-server"
private const val SERVER_VERSION = "2.0.0"
private const val PROTOCOL_VERSION = "2024-11-05"
private const val DEFAULT_LIMIT = 10
private const val DEFAULT_GREP_LIMIT = 200
private const val DEFAULT_LIST_LIMIT = 200
private const val DEFAULT_HTTP_HOST = "127.0.0.1"

data class HttpOptions(val host: String, val port: Int)

data class CliOptions(
    val httpOptions: HttpOptions?,
    val isDaemon: Boolean,
    val noDaemon: Boolean
)

fun parseCliOptions(args: Array<String>): CliOptions {
    var port: Int? = null
    var host = DEFAULT_HTTP_HOST
    var isDaemon = false
    var noDaemon = false
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--http" -> if (i + 1 < args.size) port = args[++i].toIntOrNull()
            "--http-host" -> if (i + 1 < args.size) host = args[++i]
            "--daemon" -> isDaemon = true
            "--no-daemon" -> noDaemon = true
        }
        i++
    }
    return CliOptions(
        httpOptions = if (port != null) HttpOptions(host, port) else null,
        isDaemon = isDaemon,
        noDaemon = noDaemon
    )
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Error: missing required argument <project-directory>. Usage: mcp-lucene-server <absolute-path-to-project> [--http <port>] [--http-host <host>] [--daemon] [--no-daemon]")
        exitProcess(1)
    }

    val targetDir = File(args[0])
    if (!targetDir.isDirectory) {
        System.err.println("Error: '${targetDir.path}' is not a valid directory.")
        exitProcess(1)
    }

    val cliOptions = parseCliOptions(args)

    if (!cliOptions.isDaemon && cliOptions.httpOptions == null && !cliOptions.noDaemon) {
        runProxyMode(targetDir)
        return
    }

    val gson = Gson()
    val analyzer: Analyzer = PerFieldAnalyzerWrapper(CodeAnalyzer(), mapOf("words" to WordAnalyzer()))

    System.err.println("Opening persistent index for: ${targetDir.absolutePath}")
    val indexManager = IndexManager(targetDir, analyzer)
    val initialSync = indexManager.sync()
    System.err.println("Initial sync: +${initialSync.added} ~${initialSync.updated} -${initialSync.deleted}")

    val indexWatcher = IndexWatcher(targetDir, indexManager)
    val watcherStarted = indexWatcher.start()
    val astCache = AstCache()
    if (watcherStarted) {
        System.err.println("File watcher active: index stays in sync in the background.")
    } else {
        System.err.println("File watcher unavailable: search_code will sync on every call instead.")
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        indexWatcher.close()
        indexManager.close()
    })

    if (cliOptions.httpOptions != null || cliOptions.isDaemon) {
        val options = cliOptions.httpOptions ?: HttpOptions(DEFAULT_HTTP_HOST, 0)
        startHttpServer(options, cliOptions.isDaemon, gson, indexManager, analyzer, targetDir, watcherStarted, astCache)
    } else {
        runInlineMode(gson, indexManager, analyzer, targetDir, watcherStarted, astCache)
    }
}

fun runInlineMode(
    gson: Gson,
    indexManager: IndexManager,
    analyzer: Analyzer,
    targetDir: File,
    watcherStarted: Boolean,
    astCache: AstCache
) {
    val scanner = Scanner(System.`in`)
    System.err.println("MCP Lucene Server listening on stdin...")

    while (scanner.hasNextLine()) {
        val line = scanner.nextLine()
        val responseStr = processRequest(line, gson, indexManager, analyzer, targetDir, watcherStarted, astCache)
        if (responseStr != null) {
            println(responseStr)
            System.out.flush()
        }
    }
}

fun processRequest(
    line: String,
    gson: Gson,
    indexManager: IndexManager,
    analyzer: Analyzer,
    targetDir: File,
    watcherStarted: Boolean,
    astCache: AstCache
): String? {
    if (line.isBlank()) return null
    return try {
        val request = gson.fromJson(line, JsonObject::class.java)
        val method = request.get("method")?.asString
        val id = request.get("id")

        if (method == null) {
            System.err.println("Ignoring malformed request without 'method': $line")
            return null
        }

        if (id == null) {
            System.err.println("Received notification: $method")
            return null
        }

        val response = when (method) {
            "initialize" -> createInitResponse(id)
            "tools/list" -> createToolsListResponse(id)
            "tools/call" -> handleToolCall(id, request, indexManager, analyzer, targetDir, watcherStarted, astCache)
            else -> createErrorResponse(id, -32601, "Method not found: $method")
        }

        gson.toJson(response)
    } catch (e: Exception) {
        System.err.println("Error processing line: ${e.message}")
        null
    }
}

fun startHttpServer(
    httpOptions: HttpOptions,
    isDaemon: Boolean,
    gson: Gson,
    indexManager: IndexManager,
    analyzer: Analyzer,
    targetDir: File,
    watcherStarted: Boolean,
    astCache: AstCache
) {
    val server = HttpServer.create(InetSocketAddress(httpOptions.host, httpOptions.port), 0)
    val activeSessions = java.util.concurrent.ConcurrentHashMap<String, java.io.OutputStream>()
    
    server.createContext("/sse") { exchange ->
        if (exchange.requestMethod == "GET") {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.responseHeaders.add("Connection", "keep-alive")
            exchange.sendResponseHeaders(200, 0)
            
            val sessionId = java.util.UUID.randomUUID().toString()
            val os = exchange.responseBody
            activeSessions[sessionId] = os
            
            try {
                val endpointUrl = "http://${httpOptions.host}:${server.address.port}/message?sessionId=$sessionId"
                val event = "event: endpoint\ndata: $endpointUrl\n\n"
                synchronized(os) {
                    os.write(event.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                
                val pingInterval = System.getProperty("mcp.ping.interval", "15000").toLong()
                while (true) {
                    Thread.sleep(pingInterval)
                    synchronized(os) {
                        os.write(": ping\n\n".toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                }
            } catch (e: Exception) {
            } finally {
                activeSessions.remove(sessionId)
                exchange.close()
            }
        } else {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
        }
    }
    
    server.createContext("/message") { exchange ->
        if (exchange.requestMethod == "POST") {
            try {
                val query = exchange.requestURI.query ?: ""
                val sessionIdParam = query.split("&").find { it.startsWith("sessionId=") }
                val sessionId = sessionIdParam?.substringAfter("sessionId=")
                
                val os = if (sessionId != null) activeSessions[sessionId] else null
                if (os == null) {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return@createContext
                }
                
                try {
                    synchronized(os) {
                        os.write(": processing\n\n".toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                } catch (e: Exception) {
                    activeSessions.remove(sessionId)
                    exchange.sendResponseHeaders(410, -1)
                    exchange.close()
                    return@createContext
                }
                
                val reader = InputStreamReader(exchange.requestBody, "UTF-8")
                val requestBody = reader.readText()
                val responseStr = processRequest(requestBody, gson, indexManager, analyzer, targetDir, watcherStarted, astCache)
                
                if (responseStr != null) {
                    val event = "event: message\ndata: $responseStr\n\n"
                    synchronized(os) {
                        os.write(event.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                }
                
                exchange.sendResponseHeaders(202, -1)
            } catch (e: Exception) {
                System.err.println("HTTP Error on /message: ${e.message}")
                exchange.sendResponseHeaders(500, -1)
            } finally {
                exchange.close()
            }
        } else if (exchange.requestMethod == "DELETE") {
            try {
                val query = exchange.requestURI.query ?: ""
                val sessionId = query.split("&").find { it.startsWith("sessionId=") }?.substringAfter("sessionId=")
                if (sessionId != null) {
                    val os = activeSessions.remove(sessionId)
                    os?.close()
                }
                exchange.sendResponseHeaders(204, -1)
            } catch (e: Exception) {
                exchange.sendResponseHeaders(500, -1)
            } finally {
                exchange.close()
            }
        } else {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
        }
    }
    
    server.executor = java.util.concurrent.Executors.newCachedThreadPool()
    server.start()
    val actualPort = server.address.port
    System.err.println("MCP Lucene Server listening for SSE on http://${httpOptions.host}:$actualPort/sse")
    
    var daemonPortFile: File? = null
    if (isDaemon) {
        val cacheDir = cacheIndexPath(targetDir).toFile()
        daemonPortFile = File(cacheDir, "daemon.port")
        daemonPortFile.writeText(actualPort.toString())
        
        Runtime.getRuntime().addShutdownHook(Thread {
            daemonPortFile.delete()
        })
        
        val shutdownTicks = System.getProperty("mcp.shutdown.ticks", "10").toInt()
        val autoShutdownThread = Thread {
            var emptyTicks = 0
            while (true) {
                Thread.sleep(1000)
                if (activeSessions.isEmpty()) {
                    emptyTicks++
                    if (emptyTicks >= shutdownTicks) {
                        System.err.println("No active clients for $shutdownTicks seconds. Daemon shutting down.")
                        System.exit(0)
                    }
                } else {
                    emptyTicks = 0
                }
            }
        }
        autoShutdownThread.isDaemon = true
        autoShutdownThread.start()
    }
    
    Runtime.getRuntime().addShutdownHook(Thread {
        System.err.println("Stopping HTTP server...")
        daemonPortFile?.delete()
        server.stop(1)
    })
    
    try {
        Thread.currentThread().join()
    } catch (e: InterruptedException) {
    }
}

fun getDaemonPort(file: File): Int? {
    if (!file.exists()) return null
    return try {
        file.readText().trim().toIntOrNull()
    } catch (e: Exception) {
        null
    }
}

fun isDaemonAlive(port: Int): Boolean {
    return try {
        java.net.Socket("127.0.0.1", port).use { true }
    } catch (e: Exception) {
        false
    }
}

fun startDaemonProcess(targetDir: File) {
    val javaHome = System.getProperty("java.home")
    val javaBin = File(File(javaHome, "bin"), "java").absolutePath
    val classpath = System.getProperty("java.class.path")
    val mainClass = "cz.loplex.lucenemcp.MainKt"
    val pb = ProcessBuilder(
        javaBin, 
        "-Dmcp.ping.interval=${System.getProperty("mcp.ping.interval", "15000")}",
        "-Dmcp.shutdown.ticks=${System.getProperty("mcp.shutdown.ticks", "10")}",
        "-cp", classpath, 
        mainClass, targetDir.absolutePath, "--daemon"
    )
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    pb.start()
}

fun runProxyMode(targetDir: File) {
    val cacheDir = cacheIndexPath(targetDir).toFile()
    val daemonPortFile = File(cacheDir, "daemon.port")
    var port = getDaemonPort(daemonPortFile)
    
    if (port == null || !isDaemonAlive(port)) {
        System.err.println("Starting daemon...")
        startDaemonProcess(targetDir)
        var retries = 50
        while (retries > 0) {
            port = getDaemonPort(daemonPortFile)
            if (port != null && isDaemonAlive(port)) break
            Thread.sleep(100)
            retries--
        }
        if (port == null || !isDaemonAlive(port)) {
            System.err.println("Failed to start daemon.")
            exitProcess(1)
        }
    }
    
    System.err.println("Connected to daemon on port $port")
    
    val client = HttpClient.newHttpClient()
    val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/sse"))
        .header("Accept", "text/event-stream")
        .build()
        
    val sessionIdRef = AtomicReference<String>(null)
    
    val sseThread = Thread {
        try {
            val resp = client.send(req, HttpResponse.BodyHandlers.ofLines())
            var currentEvent = ""
            resp.body().forEach { line ->
                if (line.startsWith("event: ")) {
                    currentEvent = line.substringAfter("event: ").trim()
                } else if (line.startsWith("data: ")) {
                    val data = line.substringAfter("data: ")
                    if (currentEvent == "endpoint") {
                        val url = data.trim()
                        val sessionId = url.substringAfter("sessionId=")
                        sessionIdRef.set(sessionId)
                    } else if (currentEvent == "message") {
                        println(data)
                        System.out.flush()
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("SSE connection closed: ${e.message}")
        }
        System.exit(0)
    }
    sseThread.start()
    
    while (sessionIdRef.get() == null) {
        Thread.sleep(50)
        if (!sseThread.isAlive) exitProcess(1)
    }
    
    val sessionId = sessionIdRef.get()
    
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            val delReq = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/message?sessionId=$sessionId"))
                .DELETE()
                .build()
            client.send(delReq, HttpResponse.BodyHandlers.discarding())
        } catch (e: Exception) {
            // Ignore
        }
    })
    
    val scanner = Scanner(System.`in`)
    while (scanner.hasNextLine()) {
        val line = scanner.nextLine()
        if (line.isBlank()) continue
        
        try {
            val postReq = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/message?sessionId=$sessionId"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(line))
                .build()
            client.send(postReq, HttpResponse.BodyHandlers.discarding())
        } catch (e: Exception) {
            System.err.println("Failed to send message: ${e.message}")
            break
        }
    }
    System.exit(0)
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

    tools.add(tool(
        name = "search_code",
        description = "Analyzed fulltext search over the codebase using Apache Lucene syntax (best for conceptual/fuzzy queries). Supports fields: content, path, filename, extension. Example: content:UserService AND extension:kt. The index is kept fresh in the background by a file watcher (or synced before every call if the watcher isn't available).",
        properties = linkedMapOf(
            "query" to prop("string", "Lucene search query (default field: content)."),
            "limit" to prop("number", "Maximum number of results (default $DEFAULT_LIMIT).")
        ),
        required = listOf("query")
    ))

    tools.add(tool(
        name = "grep_code",
        description = "Exact regex/literal search read directly from files (always fresh, independent of the index). Returns file:line references with context. Use for exact matches, unlike search_code.",
        properties = linkedMapOf(
            "pattern" to prop("string", "Regular expression (Java regex) or literal text if literal=true."),
            "literal" to prop("boolean", "If true, pattern is treated as literal text, not a regex (default false)."),
            "caseSensitive" to prop("boolean", "Whether matching is case-sensitive (default true)."),
            "context" to prop("number", "Number of context lines before and after a match (default 0)."),
            "beforeContext" to prop("number", "Number of context lines before a match (overrides 'context')."),
            "afterContext" to prop("number", "Number of context lines after a match (overrides 'context')."),
            "filePattern" to prop("string", "Glob pattern for the relative path, e.g. 'src/**/*.kt' or '*.ts'."),
            "outputMode" to prop("string", "content | files_with_matches | count (default content)."),
            "maxMatches" to prop("number", "Maximum number of matches/files returned (default $DEFAULT_GREP_LIMIT).")
        ),
        required = listOf("pattern")
    ))

    tools.add(tool(
        name = "read_file",
        description = "Reads a specific file from the project, optionally restricted to a line range. Result lines are prefixed with their line number.",
        properties = linkedMapOf(
            "path" to prop("string", "Path to the file, relative to the project root."),
            "startLine" to prop("number", "First line (1-based, inclusive). Default 1."),
            "endLine" to prop("number", "Last line (1-based, inclusive). Default end of file.")
        ),
        required = listOf("path")
    ))

    tools.add(tool(
        name = "list_files",
        description = "Lists project files (respects .gitignore), optionally filtered by a glob pattern.",
        properties = linkedMapOf(
            "pattern" to prop("string", "Glob pattern for the relative path, e.g. '**/*.kt'. Lists everything if omitted."),
            "limit" to prop("number", "Maximum number of files returned (default $DEFAULT_LIST_LIMIT).")
        ),
        required = emptyList()
    ))

    tools.add(tool(
        name = "find_definition",
        description = "Finds where a symbol is DEFINED (class/interface/object/function/property/...), as opposed to grep_code, which finds every mention including call sites, imports, and comments. Backed by a real tree-sitter parse tree (not regex/text matching), so a symbol name appearing inside a comment or string literal is never mistaken for a definition. Supported extensions: kt, kts, java, ts, tsx, js, jsx, mjs, py, go, rs. Always reads current file content, independent of the index.",
        properties = linkedMapOf(
            "symbol" to prop("string", "Exact symbol name (identifier), e.g. 'UserService'."),
            "maxMatches" to prop("number", "Maximum number of returned definitions (default $DEFAULT_GREP_LIMIT).")
        ),
        required = listOf("symbol")
    ))

    tools.add(tool(
        name = "find_references",
        description = "Finds real-code usages of a symbol (calls, type references, member access, imports, plain reads/writes), as opposed to grep_code, which also matches the same text inside comments and string literals. Backed by the same tree-sitter parse tree as find_definition. This is a name-based scan across the whole project, not a scope/import-aware resolver, so unrelated symbols that share a name are not distinguished. Supported extensions: kt, kts, java, ts, tsx, js, jsx, mjs, py, go, rs. Always reads current file content, independent of the index.",
        properties = linkedMapOf(
            "symbol" to prop("string", "Exact symbol name (identifier), e.g. 'UserService'."),
            "maxMatches" to prop("number", "Maximum number of returned references (default $DEFAULT_GREP_LIMIT).")
        ),
        required = listOf("symbol")
    ))

    tools.add(tool(
        name = "find_implementations",
        description = "Finds types that directly extend/implement a given class/interface/trait name (e.g. who implements a Kotlin interface, who extends a Java class, who does 'impl Trait for Type' in Rust). Backed by the same tree-sitter parse tree as find_definition/find_references. Only direct subtypes in project source files, not transitive chains through an intermediate type and nothing inside dependencies. Go is not supported (its interfaces are satisfied structurally, with no extends/implements clause to search for). Supported extensions: kt, kts, java, ts, tsx, js, jsx, mjs, py, rs.",
        properties = linkedMapOf(
            "type" to prop("string", "Exact base type/interface/trait name (identifier), e.g. 'Shape'."),
            "maxMatches" to prop("number", "Maximum number of returned implementations (default $DEFAULT_GREP_LIMIT).")
        ),
        required = listOf("type")
    ))

    tools.add(tool(
        name = "outline",
        description = "Lists every symbol a file defines (class/interface/function/property/...) in source order, without reading the whole file — a quick structural overview before deciding what to read_file. Backed by the same tree-sitter parse tree as find_definition/find_references. Supported extensions: kt, kts, java, ts, tsx, js, jsx, mjs, py, go, rs, c, cpp, cs, php, rb, swift.",
        properties = linkedMapOf(
            "path" to prop("string", "Path to the file, relative to the project root.")
        ),
        required = listOf("path")
    ))

    tools.add(tool(
        name = "search_ast",
        description = "Runs a raw tree-sitter query against all files of a specific language in the project. Useful for structural code search (e.g., finding all classes inheriting from X, or methods named Y).",
        properties = linkedMapOf(
            "query" to prop("string", "Tree-sitter query string (e.g. '(class_declaration name: (identifier) @name)'). At least one capture like @name is required."),
            "language" to prop("string", "The tree-sitter language name (e.g. 'kotlin', 'java', 'typescript', 'python', 'c', 'cpp', 'c_sharp', 'php', 'ruby', 'swift', 'go', 'rust')."),
            "pattern" to prop("string", "Optional glob pattern for relative path to filter files (e.g. '**/*.kt').")
        ),
        required = listOf("query", "language")
    ))

    tools.add(tool(
        name = "call_hierarchy",
        description = "Finds incoming or outgoing function calls for a given symbol. Incoming calls: who calls this function. Outgoing calls: which functions this function calls.",
        properties = linkedMapOf(
            "symbol" to prop("string", "Exact symbol name (function/method name), e.g. 'handleRequest'."),
            "direction" to prop("string", "Either 'incoming' or 'outgoing'.")
        ),
        required = listOf("symbol", "direction")
    ))

    tools.add(tool(
        name = "reindex_code",
        description = "Explicitly runs an incremental sync of the Lucene index (search_code) with the filesystem and returns the number of added/updated/deleted documents. The index is normally kept fresh in the background by a file watcher; this tool is for forced verification or in case the watcher missed something (e.g. the OS watched-directory limit was hit).",
        properties = linkedMapOf(),
        required = emptyList()
    ))

    result.add("tools", tools)
    res.add("result", result)
    return res
}

private fun prop(type: String, description: String): JsonObject {
    val p = JsonObject()
    p.addProperty("type", type)
    p.addProperty("description", description)
    return p
}

private fun tool(name: String, description: String, properties: Map<String, JsonObject>, required: List<String>): JsonObject {
    val toolObj = JsonObject()
    toolObj.addProperty("name", name)
    toolObj.addProperty("description", description)

    val inputSchema = JsonObject()
    inputSchema.addProperty("type", "object")
    val propsObj = JsonObject()
    properties.forEach { (key, value) -> propsObj.add(key, value) }
    inputSchema.add("properties", propsObj)

    val requiredArray = JsonArray()
    required.forEach { requiredArray.add(it) }
    inputSchema.add("required", requiredArray)

    toolObj.add("inputSchema", inputSchema)
    return toolObj
}

fun handleToolCall(
    id: JsonElement,
    request: JsonObject,
    indexManager: IndexManager,
    analyzer: Analyzer,
    root: File,
    watcherActive: Boolean,
    astCache: AstCache
): JsonObject {
    val params = request.getAsJsonObject("params")
    val toolName = params?.get("name")?.asString
    val arguments = params?.getAsJsonObject("arguments") ?: JsonObject()

    val text = try {
        when (toolName) {
            "search_code" -> handleSearchCode(arguments, indexManager, analyzer, watcherActive)
            "grep_code" -> handleGrepCode(arguments, root)
            "read_file" -> handleReadFile(arguments, root)
            "list_files" -> handleListFiles(arguments, root)
            "find_definition" -> handleFindDefinition(arguments, root, astCache)
            "find_references" -> handleFindReferences(arguments, root, astCache)
            "find_implementations" -> handleFindImplementations(arguments, root, astCache)
            "outline" -> handleOutline(arguments, root, astCache)
            "search_ast" -> handleSearchAst(arguments, root, astCache)
            "call_hierarchy" -> handleCallHierarchy(arguments, root, astCache)
            "reindex_code" -> handleReindexCode(indexManager)
            else -> return createErrorResponse(id, -32602, "Unknown tool: $toolName")
        }
    } catch (e: Exception) {
        "Error executing tool '$toolName': ${e.message}"
    }

    val res = baseResponse(id)
    val result = JsonObject()
    val contentArray = JsonArray()
    val textContent = JsonObject()
    textContent.addProperty("type", "text")
    textContent.addProperty("text", text)
    contentArray.add(textContent)
    result.add("content", contentArray)
    res.add("result", result)
    return res
}

private fun handleSearchCode(arguments: JsonObject, indexManager: IndexManager, analyzer: Analyzer, watcherActive: Boolean): String {
    val queryStr = arguments.get("query")?.asString
    if (queryStr.isNullOrBlank()) return "Missing required argument: query"
    val limit = arguments.get("limit")?.asInt ?: DEFAULT_LIMIT

    if (!watcherActive) indexManager.sync()
    val searcher = indexManager.searcher

    return try {
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

        sb.toString().trim()
    } catch (e: Exception) {
        "Lucene error parsing query: ${e.message}"
    }
}

private fun handleGrepCode(arguments: JsonObject, root: File): String {
    val pattern = arguments.get("pattern")?.asString
    if (pattern.isNullOrBlank()) return "Missing required argument: pattern"

    val context = arguments.get("context")?.asInt ?: 0
    val options = GrepOptions(
        pattern = pattern,
        literal = arguments.get("literal")?.asBoolean ?: false,
        caseSensitive = arguments.get("caseSensitive")?.asBoolean ?: true,
        beforeContext = arguments.get("beforeContext")?.asInt ?: context,
        afterContext = arguments.get("afterContext")?.asInt ?: context,
        filePattern = arguments.get("filePattern")?.asString,
        outputMode = arguments.get("outputMode")?.asString ?: "content",
        maxMatches = arguments.get("maxMatches")?.asInt ?: DEFAULT_GREP_LIMIT
    )
    return runGrep(root, options)
}

private fun handleReadFile(arguments: JsonObject, root: File): String {
    val path = arguments.get("path")?.asString
    if (path.isNullOrBlank()) return "Missing required argument: path"
    val startLine = arguments.get("startLine")?.asInt
    val endLine = arguments.get("endLine")?.asInt
    return readFileRange(root, path, startLine, endLine)
}

private fun handleListFiles(arguments: JsonObject, root: File): String {
    val pattern = arguments.get("pattern")?.asString
    val limit = arguments.get("limit")?.asInt ?: DEFAULT_LIST_LIMIT
    return runListFiles(root, pattern, limit)
}

private fun handleFindDefinition(arguments: JsonObject, root: File, astCache: AstCache): String {
    val symbol = arguments.get("symbol")?.asString
    if (symbol.isNullOrBlank()) return "Missing required argument: symbol"
    val maxMatches = arguments.get("maxMatches")?.asInt ?: DEFAULT_GREP_LIMIT
    return runFindDefinition(root, symbol, maxMatches, astCache)
}

private fun handleFindReferences(arguments: JsonObject, root: File, astCache: AstCache): String {
    val symbol = arguments.get("symbol")?.asString
    if (symbol.isNullOrBlank()) return "Missing required argument: symbol"
    val maxMatches = arguments.get("maxMatches")?.asInt ?: DEFAULT_GREP_LIMIT
    return runFindReferences(root, symbol, maxMatches, astCache)
}

private fun handleFindImplementations(arguments: JsonObject, root: File, astCache: AstCache): String {
    val type = arguments.get("type")?.asString
    if (type.isNullOrBlank()) return "Missing required argument: type"
    val maxMatches = arguments.get("maxMatches")?.asInt ?: DEFAULT_GREP_LIMIT
    return runFindImplementations(root, type, maxMatches, astCache)
}

private fun handleOutline(arguments: JsonObject, root: File, astCache: AstCache): String {
    val path = arguments.get("path")?.asString ?: return "Missing path argument"
    return runOutline(root, path, astCache)
}

private fun handleSearchAst(arguments: JsonObject, root: File, astCache: AstCache): String {
    val query = arguments.get("query")?.asString ?: return "Missing query argument"
    val language = arguments.get("language")?.asString ?: return "Missing language argument"
    val pattern = arguments.get("pattern")?.asString
    return runSearchAst(root, query, language, pattern, astCache)
}

private fun handleCallHierarchy(arguments: JsonObject, root: File, astCache: AstCache): String {
    val symbol = arguments.get("symbol")?.asString ?: return "Missing symbol argument"
    val direction = arguments.get("direction")?.asString ?: return "Missing direction argument"
    return runCallHierarchy(root, symbol, direction, astCache)
}

private fun handleReindexCode(indexManager: IndexManager): String {
    val result = indexManager.sync()
    return "Reindex complete: +${result.added} added, ~${result.updated} updated, -${result.deleted} deleted."
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
