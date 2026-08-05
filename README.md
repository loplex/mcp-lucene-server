# mcp-lucene-server

A standalone MCP (Model Context Protocol) server, written in Kotlin, that gives an AI coding
agent fast, accurate code-search tools over any project — backed by a persistent Apache Lucene
index for fulltext search and tree-sitter for AST-aware structural search. It's meant to replace
an agent's built-in grep/glob/read-file tools for a target codebase.

Communication is strictly JSON-RPC 2.0 over standard I/O for the MCP client, but internally it uses a lightweight Proxy-Daemon architecture over HTTP/SSE. This allows the heavy Lucene JVM to stay alive in the background between editor restarts, sharing a single loaded index across multiple client windows.

## Tools

- **`search_code`** — analyzed/fuzzy fulltext search (word-form aware) over a persistent,
  auto-synced Lucene index. Best for conceptual/fuzzy lookups.
- **`grep_code`** — exact regex/literal search read directly from disk (never stale).
- **`find_definition`** — finds where a symbol is *defined*, via a real tree-sitter parse tree,
  not text/regex matching.
- **`find_references`** — finds real-code usages of a symbol (calls, type references, member
  access, imports), import/package-aware so unrelated same-named symbols in unconnected files are
  filtered out where possible.
- **`find_implementations`** — finds types that directly `extends`/`implements` a given
  class/interface/trait.
- **`outline`** — lists every symbol a file defines, in source order, without reading the whole
  file.
- **`search_ast`** — runs raw tree-sitter queries against all files of a specific language for structural search.
- **`call_hierarchy`** — finds incoming or outgoing function calls (caller/callee) for a symbol using AST analysis.
- **`read_file`** — reads a file or line range.
- **`list_files`** — lists project files by glob pattern (respects `.gitignore`).
- **`reindex_code`** — forces an incremental resync of the `search_code` index.
- **`add_external_roots`** — adds new external directories to the index at runtime. (Note: Any `*-sources.jar` files added or found within directories are automatically unzipped and indexed!)
- **`add_maven_dependency_sources`** — downloads a Maven dependency's source jar and adds it to the index on the fly.

Supported languages for the tree-sitter-backed tools: Kotlin, Java, TypeScript/TSX, JavaScript,
Python, Go, Rust, C, C++, C#, PHP, Ruby, Swift (`find_implementations` excludes Go — its interfaces are structural, with no
`extends`/`implements` clause to search for).

Full tool semantics, field syntax, and tool-selection guidance are documented in
[`CLAUDE_INSTRUCTIONS.md`](./CLAUDE_INSTRUCTIONS.md) — that file is written to be handed to the
AI agent consuming this server, not to a human reader.

## Requirements

- JDK 17+
- Maven

## Build

```bash
mvn package
```

Produces a self-contained fat jar at `target/mcp-lucene-server-1.0-SNAPSHOT.jar`.

To build a GraalVM **Native Image** (instant startup, no JRE required):

```bash
mvn package -Pnative
```

Produces a native executable at `target/mcp-lucene-server`.

## Run

```bash
java -jar target/mcp-lucene-server-1.0-SNAPSHOT.jar /absolute/path/to/target-project
# or use the native binary:
./target/mcp-lucene-server /absolute/path/to/target-project
```

The target project directory is a required argument — the server indexes and searches that
project, not its own source tree. 

### Architecture & Modes

By default, the server acts as an intelligent **Proxy**. It checks for an existing background daemon for the project (in `$XDG_CACHE_HOME/mcp-lucene-server/.../daemon.port`), connects to it via HTTP Server-Sent Events (SSE), and bridges `stdio` from your MCP client to the daemon. If no daemon exists, it spawns one automatically in the background.

When all clients disconnect from a background daemon, the daemon gracefully shuts itself down after 10 seconds of inactivity to free memory.

**Optional Flags:**
- `--daemon` — starts directly as the HTTP/SSE daemon in the background on a random port (or the port specified by `--http`), creates the lock file, and waits for clients. Shuts down automatically when idle.
- `--no-daemon` — starts the classic, standalone in-line mode (a single process handles both the I/O and the Lucene indexing). Useful for one-off tasks.
- `--http <port>` / `--http-host <host>` — force the daemon to listen on a specific port/host instead of dynamically picking a free one.
- `--external-roots <dirs>` — a comma-separated list of absolute paths to index as external dependencies (e.g. `/project/node_modules`).
- `--help` (`-h`) — shows the usage options.

## Adding it to an MCP client

This is a plain stdio MCP server from the client's perspective, so any MCP-compatible client works the same way: point it at
`java` as the command, with `-jar <path-to-jar> <absolute-project-path>` as arguments. The proxy-daemon lifecycle is completely transparent.

**Claude Code:**

```bash
claude mcp add lucene-server -- java -jar /absolute/path/to/mcp-lucene-server/target/mcp-lucene-server-1.0-SNAPSHOT.jar /absolute/path/to/target-project
```

**Google Antigravity:** add an entry to `~/.gemini/config/mcp_config.json` (global) or
`.agents/mcp_config.json` (workspace-local):

```json
{
  "mcpServers": {
    "lucene-server": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/mcp-lucene-server/target/mcp-lucene-server-1.0-SNAPSHOT.jar",
        "/absolute/path/to/target-project"
      ]
    }
  }
}
```

Rebuild the jar (`mvn package`) after changing the Kotlin sources — the client keeps running
whatever jar it was pointed at.

## Testing

```bash
mvn test          # JUnit 5 unit tests
e2e/run.sh --build  # end-to-end test: drives the packaged jar over the real JSON-RPC/stdio
                     # protocol against a disposable git fixture
```

## License

This project is licensed under the [Apache License 2.0](LICENSE).
