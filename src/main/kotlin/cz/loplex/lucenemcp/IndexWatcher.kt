package cz.loplex.lucenemcp

import java.io.File
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

/**
 * Watches [root] for filesystem changes on a background thread and keeps [indexManager] in sync,
 * so `search_code` no longer pays a full mtime-diff on every request. Events are debounced by
 * [debounceMillis] of quiet time before triggering a single [IndexManager.sync] call, which batches
 * bursts of changes (e.g. a git checkout or IDE save-all) into one pass.
 *
 * Registration mirrors the same directory ignore list as [isIgnoredDirName] used by the fallback
 * walk in FileDiscovery — this is a heuristic, not full `.gitignore` semantics, but [IndexManager.sync]
 * is still the source of truth for *what* ends up in the index; the watcher only decides *when* to
 * re-run it.
 */
class IndexWatcher(
    private val root: File,
    private val indexManager: IndexManager,
    private val debounceMillis: Long = 400
) {
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val keys = HashMap<WatchKey, Path>()
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Registers watches on the directory tree and starts the background sync loop. Returns false
     * (watcher not started) if the initial registration failed entirely, e.g. the OS inotify watch
     * limit was hit on a very large tree — callers should fall back to sync-per-call in that case. */
    fun start(): Boolean {
        val registered = try {
            registerAll(root.toPath())
            true
        } catch (e: IOException) {
            System.err.println("IndexWatcher: failed to register file watches, falling back to sync-per-call: ${e.message}")
            false
        }
        if (!registered) {
            watchService.close()
            return false
        }

        running = true
        thread = Thread(::run, "index-watcher").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun close() {
        running = false
        thread?.interrupt()
        try {
            watchService.close()
        } catch (e: IOException) {
            // already closed or unusable, nothing to do
        }
    }

    private fun registerAll(dir: Path) {
        registerDir(dir)
        val children = dir.toFile().listFiles { f -> f.isDirectory && !isIgnoredDirName(f.name) } ?: return
        for (child in children) {
            registerAll(child.toPath())
        }
    }

    private fun registerDir(dir: Path) {
        val key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
        keys[key] = dir
    }

    private fun run() {
        while (running) {
            val key = try {
                watchService.take()
            } catch (e: InterruptedException) {
                break
            } catch (e: ClosedWatchServiceException) {
                break
            }
            processKey(key)
            drainDebounced()
            if (running) trySync()
        }
    }

    private fun drainDebounced() {
        while (true) {
            val next = try {
                watchService.poll(debounceMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            } catch (e: ClosedWatchServiceException) {
                return
            } ?: return
            processKey(next)
        }
    }

    private fun processKey(key: WatchKey) {
        val dir = keys[key]
        if (dir != null) {
            for (event in key.pollEvents()) {
                if (event.kind() == OVERFLOW) continue
                @Suppress("UNCHECKED_CAST")
                val name = (event.context() as? Path) ?: continue
                val child = dir.resolve(name)
                if (event.kind() == ENTRY_CREATE && child.toFile().isDirectory && !isIgnoredDirName(child.fileName.toString())) {
                    try {
                        registerAll(child)
                    } catch (e: IOException) {
                        System.err.println("IndexWatcher: failed to register new directory $child: ${e.message}")
                    }
                }
            }
        }

        if (!key.reset()) {
            keys.remove(key)
        }
    }

    private fun trySync() {
        try {
            indexManager.sync()
        } catch (e: Exception) {
            System.err.println("IndexWatcher: background index sync failed: ${e.message}")
        }
    }
}
