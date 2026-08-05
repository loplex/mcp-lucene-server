# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A standalone MCP server (Kotlin, JSON-RPC 2.0) that gives an AI coding agent
code-search tools backed by Apache Lucene (fulltext) and tree-sitter (AST-aware structural
search). It uses a Daemon-Proxy architecture: a background HTTP/SSE daemon keeps the heavy Lucene index loaded in memory, while a lightweight proxy bridges the stdio JSON-RPC requests from the MCP client to the daemon.
The server takes the target project's absolute path as its one CLI argument.

The nine tools it exposes (`search_code`, `grep_code`, `read_file`, `list_files`,
`find_definition`, `find_references`, `find_implementations`, `outline`, `reindex_code`) are
documented for consumers in `CLAUDE_INSTRUCTIONS.md` — read that file for behavior/semantics: this
file is about building and maintaining the server itself.

## Commands

```bash
mvn package                    # builds the shaded (fat) jar: target/mcp-lucene-server-1.0-SNAPSHOT.jar
mvn test                       # JUnit 5 unit tests
mvn test -Dtest=ClassName                       # single test class
mvn test -Dtest=ClassName#methodName            # single test method

e2e/run.sh                     # end-to-end test against the packaged jar (reuses existing jar)
e2e/run.sh --build             # same, but rebuilds the jar first (mvn -q package -DskipTests)

java -jar target/mcp-lucene-server-1.0-SNAPSHOT.jar /absolute/path/to/target-project
```

The e2e script drives the real JSON-RPC/stdio protocol against a disposable git fixture (built in
a temp dir), not JUnit — it is the only test that exercises `Main.kt`'s request loop, the file
watcher, and index freshness end-to-end. Always run it (with `--build`) after touching `Main.kt`,
the index/watcher, or any tool's wiring.

## Architecture

**Two independent data sources back the tools, and they must not be confused:**

- `search_code` is the only tool that reads from the persistent Lucene index
  (`IndexManager`/`IndexWatcher`). Every other tool (`grep_code`, `read_file`, `list_files`,
  `find_definition`, `find_references`, `find_implementations`, `outline`) reads current file
  content directly off disk on every call — never stale, independent of the index.
- `IndexManager` opens an on-disk `NIOFSDirectory` index under
  `$XDG_CACHE_HOME/mcp-lucene-server/<project-name>-<sha256-hash-of-canonical-path>/index`
  (`cacheIndexPath`), keyed by canonical path so restarts reuse the same index instead of
  rebuilding. `sync()` does a cheap mtime-based diff against stored docs (no re-read of unchanged
  files) and is safe to call before every search. **`NIOFSDirectory`, not `MMapDirectory`** — MMap
  broke after the shade-plugin fat jar (a broken multi-release-JAR trick), see
  `NOTES/AI/plan.md` step 5 for the history; don't switch this back without re-checking that.
  `IndexWatcher` runs a debounced (400ms) `java.nio.file.WatchService` in the background so
  `search_code` doesn't need to sync per-call; if the watcher fails to start (e.g. OS inotify
  watch limit), `Main.kt` falls back to syncing on every `search_code` call (`watcherActive` flag).
  Two Lucene fields exist for the same text: `content` (`CodeAnalyzer`, camelCase-splitting,
  good for term/fuzzy search) and `words` (`WordAnalyzer`, one token per identifier — the only
  field where phrase/proximity slop queries count real words apart) — see `PerFieldAnalyzerWrapper`
  wiring in `Main.kt`.

- The five AST-based tools (`find_definition`, `find_references`, `find_implementations`,
  `outline`, and `FindReferencesTool`'s import filtering) share one tree-sitter pipeline:
  `TreeSitterLanguages.kt` maps a file extension to a grammar name; `AstParser.kt`'s `parseFile`
  parses a file into a `ParsedFile` (tree + source + UTF-8 bytes — byte offsets from tree-sitter
  are into the re-encoded UTF-8 buffer, not the UTF-16 Kotlin `String`, see `ParsedFile.textOf`);
  `AstQueries.kt` holds the per-language, per-kind tree-sitter query patterns
  (`DEFINITIONS_BY_LANGUAGE`, `IMPLEMENTS_BY_LANGUAGE`, ...) that `AstParser.kt`'s
  `definitionHitsInFile`/`implementsHitsInFile` run and label with a `kind`. `AstCache` (one
  instance per server process, see `Main.kt`) memoizes parsed trees by absolute path + mtime so
  repeated tool calls don't reparse unchanged files. `ImportResolution.kt` resolves TS/JS/Python
  relative/absolute import specifiers to actual on-disk files so `find_references` can drop bare
  name-mentions that resolve to an unrelated file (unresolvable bare specifiers, e.g. npm
  packages, fall back to a plain name-mention check). Go has no `find_implementations` support —
  its interfaces are satisfied structurally, with no `extends`/`implements` clause to query for.

- `FileDiscovery.kt` is the single source of truth for "which files does this project have" —
  used by `IndexManager`, `IndexWatcher`, `list_files`, and `grep_code`. Prefers
  `git ls-files -co --exclude-standard` (exact `.gitignore` semantics for free) when the target is
  a git repo, else falls back to a directory walk pruned by a hardcoded ignore list
  (`isIgnoredDirName`) — keep both in sync if you change what should be excluded.

- `Main.kt` handles the dual Proxy/Daemon modes:
  - **Proxy Mode** (default): Bridges `stdio` JSON-RPC lines to the daemon via HTTP POST, and streams responses back via HTTP Server-Sent Events (SSE). Spawns the daemon if it's missing, and sends a graceful `DELETE` request upon exit.
  - **Daemon Mode** (`--daemon`): Runs a lightweight `com.sun.net.httpserver.HttpServer`. Serves `/sse` for streaming responses, `/message` for incoming JSON-RPC lines. Maintains `activeSessions` and shuts itself down after 10 seconds (configurable via `mcp.shutdown.ticks`) of inactivity.
  - **Standalone Mode** (`--no-daemon`): The classic blocking `Scanner(System.in)` loop for one-off tasks.
  Adding a new tool means: a `tools.add(tool(...))` schema entry in `createToolsListResponse`, a
  `handleXyz` branch in `handleToolCall`'s `when`, and (per the instructions in
  `CLAUDE_INSTRUCTIONS.md`) a matching description update there for whoever consumes this server.

- Tool files each own one tool's argument parsing + formatting (`GrepTool.kt`, `ReadFileTool.kt`,
  `ListFilesTool.kt`, `FindDefinitionTool.kt`, `FindReferencesTool.kt`,
  `FindImplementationsTool.kt`, `OutlineTool.kt`) and are called from a thin `handleXyz` wrapper in
  `Main.kt` that only extracts arguments out of the request JSON.

## Notes

- `NOTES/AI/plan.md` carries the running implementation log/plan and a "proposed next steps"
  TODO section (Czech) — check it for prior design decisions and open items before starting new
  work here.
