package cz.loplex.lucenemcp

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*

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

private const val DEFAULT_HTTP_HOST = "127.0.0.1"

data class HttpOptions(val host: String, val port: Int)

data class CliOptions(
    val httpOptions: HttpOptions?,
    val isDaemon: Boolean,
    val noDaemon: Boolean,
    val externalRoots: List<File>
)

fun parseCliOptions(args: Array<String>): CliOptions {
    var port: Int? = null
    var host = DEFAULT_HTTP_HOST
    var isDaemon = false
    var noDaemon = false
    var externalRoots = emptyList<File>()
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--http" -> if (i + 1 < args.size) port = args[++i].toIntOrNull()
            "--http-host" -> if (i + 1 < args.size) host = args[++i]
            "--daemon" -> isDaemon = true
            "--no-daemon" -> noDaemon = true
            "--external-roots" -> {
                if (i + 1 < args.size) {
                    externalRoots = args[++i].split(",").map { File(it) }
                }
            }
        }
        i++
    }
    return CliOptions(
        httpOptions = if (port != null) HttpOptions(host, port) else null,
        isDaemon = isDaemon,
        noDaemon = noDaemon,
        externalRoots = externalRoots
    )
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        System.err.println("""
            Usage: mcp-lucene-server <absolute-path-to-project> [options]
            
            Options:
              --http <port>           Run as an HTTP SSE daemon listening on the specified port.
              --http-host <host>      Host to bind the HTTP server to (default: 127.0.0.1).
              --daemon                Run as a daemon and let the OS assign a random port. The port is printed to stdout.
              --no-daemon             Force running in inline mode (stdin/stdout) without spawning a background daemon.
              --external-roots <dirs> Comma-separated list of absolute paths to index as external dependencies.
              -h, --help              Show this help message.
        """.trimIndent())
        exitProcess(if (args.contains("--help") || args.contains("-h")) 0 else 1)
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
    val indexManager = IndexManager(targetDir, analyzer, cliOptions.externalRoots)
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
                
                val pingInterval = (System.getProperty("mcp.ping.interval") ?: System.getenv("mcp.ping.interval") ?: "15000").toLong()
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
        
        val shutdownTicks = (System.getProperty("mcp.shutdown.ticks") ?: System.getenv("mcp.shutdown.ticks") ?: "10").toInt()
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
    val isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null
    val currentCommand = ProcessHandle.current().info().command().orElse(null)

    val pb = if (isNativeImage && currentCommand != null) {
        ProcessBuilder(
            currentCommand,
            targetDir.absolutePath, "--daemon"
        )
    } else {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(File(javaHome, "bin"), "java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val mainClass = "cz.loplex.lucenemcp.MainKt"
        ProcessBuilder(
            javaBin, 
            "-Dmcp.ping.interval=${System.getProperty("mcp.ping.interval", "15000")}",
            "-Dmcp.shutdown.ticks=${System.getProperty("mcp.shutdown.ticks", "10")}",
            "-cp", classpath, 
            mainClass, targetDir.absolutePath, "--daemon"
        )
    }
    
    // Pass along properties if native image (using GraalVM's -D logic or environment if needed), 
    // but default env is inherited so it's fine. For Java, we passed -D above.
    if (isNativeImage) {
        pb.environment()["mcp.ping.interval"] = System.getProperty("mcp.ping.interval", "15000")
        pb.environment()["mcp.shutdown.ticks"] = System.getProperty("mcp.shutdown.ticks", "10")
    }

    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    pb.start()
}

