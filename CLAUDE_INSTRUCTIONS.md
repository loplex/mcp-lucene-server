# INSTRUCTIONS FOR CLAUDE (MCP Lucene Integration)

You are equipped with a code-search MCP server backed by Apache Lucene and tree-sitter, meant to
replace the built-in Grep/Glob/Read tools for this project. It exposes eight tools:

- `search_code` — analyzed/fuzzy fulltext search (word-form aware) over a persistent, auto-synced
  Lucene index. Best for conceptual lookups. Fields: `content`, `path`, `filename`, `extension`,
  `words`. Example: `content:UserService AND extension:kt`, `content:"jwt.verify" AND
  -path:node_modules`, `content:initialise~1` (fuzzy). For "does X sit near Y" — real word-distance
  proximity — use the `words` field instead of `content`: `words:"ConfigLoader DatabasePool"~10`
  (matches within ~10 words either order; exact phrase drop the `~N`). `words` tokenizes each
  identifier as exactly one term (no camelCase splitting like `content` does), so slop counts real
  words apart — `content`'s proximity/phrase queries on multi-word identifiers are unreliable (its
  word-splitting inflates position counts unpredictably) even though its plain term/fuzzy search
  works fine. `words` is also useful on its own for an exact, unsplit identifier match with none of
  `content`'s word-part noise.
- `grep_code` — exact regex/literal search read directly from disk (never stale). Returns
  `file:line` with surrounding context. Use `outputMode: files_with_matches` or `count` for
  broader sweeps before drilling into content. Matches everywhere, including comments and strings.
- `find_definition` — finds where a symbol is DEFINED (class/interface/function/property/...),
  backed by a real tree-sitter parse tree, not text/regex matching — a symbol name that happens to
  appear inside a comment or string literal is never mistaken for a definition. Supported: kt, kts,
  java, ts, tsx, js, jsx, mjs, py, go, rs.
- `find_references` — finds real-code usages of a symbol (calls, type references, member access,
  imports), tagged with a cheap `[kind]` (`call`/`type`/`member`/`import`/`definition`/`reference`).
  Same AST basis as `find_definition`. Bare (unqualified) hits are narrowed by import/package
  awareness — a file with no import and no shared package as any real definition is skipped for
  those; qualified `receiver.symbol` access is never filtered this way. For TS/JS/Python, a relative
  (`./foo`) or absolute (`pkg.mod`) import path is resolved to the actual on-disk file it points to,
  so an import that merely mentions the symbol's name but resolves to a different, real project file
  no longer counts as a match on its own — bare/external specifiers (npm packages, stdlib) that can't
  be resolved on disk still fall back to the name-mention check. Still not a full type-resolving scope
  resolver, so two same-named symbols that are otherwise both importable/visible aren't distinguished.
  Same supported extensions as `find_definition`.
- `read_file` — read a file, optionally restricted to a line range, to jump to an exact location
  found by `search_code`/`grep_code`/`find_definition`/`find_references` without re-requesting the
  whole file.
- `outline` — lists every symbol a file defines (class/interface/function/property/...), in source
  order, without reading the whole file. Same tree-sitter basis as `find_definition`/
  `find_references`. Nested members (e.g. a class's methods) are included alongside top-level
  declarations. Supported extensions: kt, kts, java, ts, tsx, js, jsx, mjs, py, go, rs.
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
7. Need a quick structural overview of one file (its classes/functions/properties) before deciding
   what to read → `outline`.

## Goal
Keep your active session memory minimal. Run a targeted query first, retrieve only the relevant
snippets/line ranges, then use `read_file` for exactly the block you need. Do not request whole
codebase dumps.
