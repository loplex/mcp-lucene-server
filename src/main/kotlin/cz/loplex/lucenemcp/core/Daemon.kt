package cz.loplex.lucenemcp.core

import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*
import cz.loplex.lucenemcp.*
import com.google.gson.*
import com.sun.net.httpserver.HttpServer
import java.io.*
import java.net.*
import java.net.http.*
import java.util.Scanner
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper

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

