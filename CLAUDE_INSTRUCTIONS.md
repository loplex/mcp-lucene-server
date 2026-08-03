# INSTRUCTIONS FOR CLAUDE (MCP Lucene Integration)

You are equipped with a code-search MCP server backed by Apache Lucene and tree-sitter, meant to
replace the built-in Grep/Glob/Read tools for this project. It exposes seven tools:

- `search_code` — analyzed/fuzzy fulltext search (word-form aware) over a persistent, auto-synced
  Lucene index. Best for conceptual lookups. Fields: `content`, `path`, `filename`, `extension`.
  Example: `content:UserService AND extension:kt`, `content:"jwt.verify" AND -path:node_modules`,
  `content:initialise~1` (fuzzy).
- `grep_code` — exact regex/literal search read directly from disk (never stale). Returns
  `file:line` with surrounding context. Use `outputMode: files_with_matches` or `count` for
  broader sweeps before drilling into content. Matches everywhere, including comments and strings.
- `find_definition` — finds where a symbol is DEFINED (class/interface/function/property/...),
  backed by a real tree-sitter parse tree, not text/regex matching — a symbol name that happens to
  appear inside a comment or string literal is never mistaken for a definition. Supported: kt, kts,
  java, ts, tsx, js, jsx, mjs, py, go, rs.
- `find_references` — finds real-code usages of a symbol (calls, type references, member access,
  imports), tagged with a cheap `[kind]` (`call`/`type`/`member`/`import`/`definition`/`reference`).
  Same AST basis as `find_definition`; not a scope/import-aware resolver, so same-named symbols in
  unrelated scopes aren't distinguished. Same supported extensions as `find_definition`.
- `read_file` — read a file, optionally restricted to a line range, to jump to an exact location
  found by `search_code`/`grep_code`/`find_definition`/`find_references` without re-requesting the
  whole file.
- `list_files` — list project files by glob pattern (respects `.gitignore`).
- `reindex_code` — force an incremental resync of the `search_code` index (rarely needed —
  `search_code` already resyncs automatically before every call).

## How to choose a tool
1. Know the exact string/regex (function name, error message, import) → `grep_code`.
2. Need the declaration site of a symbol, not every mention → `find_definition`.
3. Need every real call/usage site of a symbol, excluding comments/strings → `find_references`.
4. Fuzzy/conceptual query, word-form variations, or want ranked relevance → `search_code`.
5. Need to enumerate files by name/extension → `list_files`.
6. Have a `file:line` hit and need the surrounding code → `read_file` with a line range.

## Goal
Keep your active session memory minimal. Run a targeted query first, retrieve only the relevant
snippets/line ranges, then use `read_file` for exactly the block you need. Do not request whole
codebase dumps.
