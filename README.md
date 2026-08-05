# mcp-lucene-server

A standalone MCP (Model Context Protocol) server, written in Kotlin, that gives an AI coding
agent fast, accurate code-search tools over any project — backed by a persistent Apache Lucene
index for fulltext search and tree-sitter for AST-aware structural search. It's meant to replace
an agent's built-in grep/glob/read-file tools for a target codebase.

Communication is JSON-RPC 2.0 over stdio (one process per target project) — there is no HTTP
server or network listener involved.

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
- **`read_file`** — reads a file or line range.
- **`list_files`** — lists project files by glob pattern (respects `.gitignore`).
- **`reindex_code`** — forces an incremental resync of the `search_code` index.

Supported languages for the tree-sitter-backed tools: Kotlin, Java, TypeScript/TSX, JavaScript,
Python, Go, Rust (`find_implementations` excludes Go — its interfaces are structural, with no
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

## Run

```bash
java -jar target/mcp-lucene-server-1.0-SNAPSHOT.jar /absolute/path/to/target-project
```

The target project directory is a required argument — the server indexes and searches that
project, not its own source tree. On first run it builds a persistent Lucene index under
`$XDG_CACHE_HOME/mcp-lucene-server/` (falls back to `~/.cache`); later runs reuse and
incrementally update it, and a background file watcher keeps it in sync while the server runs.

## Adding it to an MCP client

This is a plain stdio MCP server, so any MCP-compatible client works the same way: point it at
`java` as the command, with `-jar <path-to-jar> <absolute-project-path>` as arguments.

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
