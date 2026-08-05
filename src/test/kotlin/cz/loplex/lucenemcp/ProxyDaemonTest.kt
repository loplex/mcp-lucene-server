package cz.loplex.lucenemcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.Scanner
import java.util.concurrent.TimeUnit

class ProxyDaemonTest {

    @Test
    fun `test proxy daemon auto-start and basic communication`(@TempDir tempDir: File) {
        System.setProperty("mcp.ping.interval", "1000")
        System.setProperty("mcp.shutdown.ticks", "2")
        val javaHome = System.getProperty("java.home")
        val javaBin = File(File(javaHome, "bin"), "java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val mainClass = "cz.loplex.lucenemcp.MainKt"

        // Ensure cache directory for this temp project is empty
        val cacheDir = cacheIndexPath(tempDir).toFile()
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }

        // Start the proxy process. It should automatically start the daemon.
        val pb = ProcessBuilder(
            javaBin, 
            "-Dmcp.ping.interval=1000",
            "-Dmcp.shutdown.ticks=2",
            "-cp", classpath, 
            mainClass, tempDir.absolutePath
        )
        val process = pb.start()

        val writer = PrintWriter(OutputStreamWriter(process.outputStream, "UTF-8"), true)
        val scanner = Scanner(process.inputStream, "UTF-8")
        val errScanner = Scanner(process.errorStream, "UTF-8")

        // Read errors in background so the process doesn't block
        val errThread = Thread {
            while (errScanner.hasNextLine()) {
                val line = errScanner.nextLine()
                // println("PROXY ERR: $line") // Uncomment for debugging
            }
        }
        errThread.isDaemon = true
        errThread.start()

        // Wait a bit to ensure daemon starts and connects
        Thread.sleep(1500) // Integration test, allow some time for JVM to boot

        // Send a simple JSON-RPC request to the proxy
        val reqId = "test-123"
        val request = """{"jsonrpc": "2.0", "id": "$reqId", "method": "initialize"}"""
        writer.println(request)

        // Read response from proxy's stdout
        var response: String? = null
        if (scanner.hasNextLine()) {
            response = scanner.nextLine()
        }

        assertNotNull(response, "Proxy did not return a response")
        assertTrue(response!!.contains(reqId), "Response should contain the request ID")
        assertTrue(response.contains("mcp-lucene-server"), "Response should contain server name")

        // Close the proxy's stdin to simulate client disconnecting
        writer.close()

        // Wait for proxy to exit
        val exited = process.waitFor(5, TimeUnit.SECONDS)
        assertTrue(exited, "Proxy process should exit when stdin is closed")

        // The daemon should still be running because auto-shutdown takes 10 seconds.
        val portFile = File(cacheDir, "daemon.port")
        assertTrue(portFile.exists(), "Daemon port file should still exist immediately after proxy disconnects")

        // Wait for daemon to auto-shutdown (ping takes up to 1s, shutdown timer takes 2s)
        var daemonExited = false
        for (i in 1..20) { // up to 10 seconds
            if (!portFile.exists()) {
                daemonExited = true
                break
            }
            Thread.sleep(500)
        }
        
        assertTrue(daemonExited, "Daemon should have auto-shut down and deleted daemon.port")
    }
}
