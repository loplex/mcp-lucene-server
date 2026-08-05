package cz.loplex.lucenemcp.core

import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*
import cz.loplex.lucenemcp.*
import com.google.gson.*
import java.io.File
import org.apache.lucene.analysis.Analyzer

const val SERVER_NAME = "mcp-lucene-server"
const val SERVER_VERSION = "2.0.0"
const val PROTOCOL_VERSION = "2024-11-05"
const val DEFAULT_LIMIT = 10
const val DEFAULT_GREP_LIMIT = 200
const val DEFAULT_LIST_LIMIT = 200

fun handleToolCall(
    id: JsonElement,
    request: JsonObject,
    indexManager: IndexManager,
    analyzer: Analyzer,
    root: File,
    watcherActive: Boolean,
    astCache: AstCache
): JsonObject {
    val externalRoots = indexManager.externalRoots
    val params = request.getAsJsonObject("params")
    val toolName = params?.get("name")?.asString
    val arguments = params?.getAsJsonObject("arguments") ?: JsonObject()

    val text = try {
        when (toolName) {
            "search_code" -> handleSearchCode(arguments, indexManager, analyzer, watcherActive)
            "grep_code" -> handleGrepCode(arguments, root, externalRoots)
            "read_file" -> handleReadFile(arguments, root)
            "list_files" -> handleListFiles(arguments, root, externalRoots)
            "find_definition" -> handleFindDefinition(arguments, root, astCache, externalRoots)
            "find_references" -> handleFindReferences(arguments, root, astCache, externalRoots)
            "find_implementations" -> handleFindImplementations(arguments, root, astCache, externalRoots)
            "outline" -> handleOutline(arguments, root, astCache)
            "search_ast" -> handleSearchAst(arguments, root, astCache, externalRoots)
            "call_hierarchy" -> handleCallHierarchy(arguments, root, astCache, externalRoots)
            "reindex_code" -> handleReindexCode(indexManager)
            "add_external_roots" -> handleAddExternalRoots(arguments, indexManager)
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

    tools.add(tool(
        name = "add_external_roots",
        description = "Adds new external roots (directories) to be indexed and searched at runtime. Immediately triggers a sync for the newly added directories.",
        properties = linkedMapOf(
            "directories" to prop("string", "Comma-separated list of absolute paths to add as external roots (e.g. '/path/to/node_modules,/path/to/vendor').")
        ),
        required = listOf("directories")
    ))

    result.add("tools", tools)
    res.add("result", result)
    return res
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

fun createErrorResponse(id: JsonElement, code: Int, msg: String): JsonObject {
    val res = baseResponse(id)
    val err = JsonObject()
    err.addProperty("code", code)
    err.addProperty("message", msg)
    res.add("error", err)
    return res
}

fun baseResponse(id: JsonElement): JsonObject {
    val res = JsonObject()
    res.addProperty("jsonrpc", "2.0")
    res.add("id", id)
    return res
}

fun tool(name: String, description: String, properties: Map<String, JsonObject>, required: List<String>): JsonObject {
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

fun prop(type: String, description: String): JsonObject {
    val p = JsonObject()
    p.addProperty("type", type)
    p.addProperty("description", description)
    return p
}

