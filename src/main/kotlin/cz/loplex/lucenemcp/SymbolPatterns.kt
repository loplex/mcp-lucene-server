package cz.loplex.lucenemcp

/**
 * Per-language heuristics for recognizing where a symbol is *defined*, as opposed to merely
 * mentioned. This is regex-based line matching, not a real parser (no grammar, no scope
 * resolution) — it trades precision for zero parsing dependencies, and is meant to cut through
 * the noise of a plain grep (call sites, imports, comments) rather than to be exhaustively
 * correct. [template] contains the literal placeholder `%SYMBOL%`, substituted with the
 * (regex-escaped) symbol name before compiling.
 */
data class DefinitionPattern(val kind: String, val template: String) {
    fun toRegex(escapedSymbol: String): Regex = Regex(template.replace("%SYMBOL%", escapedSymbol))
}

private val KOTLIN_PATTERNS = listOf(
    DefinitionPattern("class", """\bclass\s+%SYMBOL%\b"""),
    DefinitionPattern("interface", """\binterface\s+%SYMBOL%\b"""),
    DefinitionPattern("object", """\bobject\s+%SYMBOL%\b"""),
    DefinitionPattern("enum", """\benum\s+class\s+%SYMBOL%\b"""),
    DefinitionPattern("typealias", """\btypealias\s+%SYMBOL%\b"""),
    DefinitionPattern("function", """\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?%SYMBOL%\s*\("""),
    DefinitionPattern("property", """\b(?:val|var)\s+%SYMBOL%\b\s*[:=,)]""")
)

private val JAVA_PATTERNS = listOf(
    DefinitionPattern("class", """\bclass\s+%SYMBOL%\b"""),
    DefinitionPattern("interface", """\binterface\s+%SYMBOL%\b"""),
    DefinitionPattern("enum", """\benum\s+%SYMBOL%\b"""),
    DefinitionPattern("record", """\brecord\s+%SYMBOL%\b"""),
    DefinitionPattern("method", """\b(?:public|private|protected|static|final|abstract|synchronized|native)\b[^;{=]*\b%SYMBOL%\s*\(""")
)

private val TS_JS_PATTERNS = listOf(
    DefinitionPattern("class", """\bclass\s+%SYMBOL%\b"""),
    DefinitionPattern("interface", """\binterface\s+%SYMBOL%\b"""),
    DefinitionPattern("type", """\btype\s+%SYMBOL%\b\s*="""),
    DefinitionPattern("enum", """\benum\s+%SYMBOL%\b"""),
    DefinitionPattern("function", """\bfunction\s*\*?\s*%SYMBOL%\s*\("""),
    DefinitionPattern("function", """\b(?:const|let|var)\s+%SYMBOL%\s*=\s*(?:\([^)]*\)|[\w]+)\s*=>"""),
    DefinitionPattern("variable", """\b(?:const|let|var)\s+%SYMBOL%\b\s*[:=,;]""")
)

private val PYTHON_PATTERNS = listOf(
    DefinitionPattern("class", """\bclass\s+%SYMBOL%\b"""),
    DefinitionPattern("function", """\bdef\s+%SYMBOL%\s*\("""),
    DefinitionPattern("variable", """^\s*%SYMBOL%\s*(?::\s*[\w\[\], .]+)?\s*=(?!=)""")
)

private val GO_PATTERNS = listOf(
    DefinitionPattern("function", """\bfunc\s+(?:\([^)]*\)\s*)?%SYMBOL%\s*\("""),
    DefinitionPattern("type", """\btype\s+%SYMBOL%\s+(?:struct|interface)\b"""),
    DefinitionPattern("variable", """\b(?:var|const)\s+%SYMBOL%\b""")
)

private val RUST_PATTERNS = listOf(
    DefinitionPattern("function", """\bfn\s+%SYMBOL%\s*[(<]"""),
    DefinitionPattern("struct", """\bstruct\s+%SYMBOL%\b"""),
    DefinitionPattern("enum", """\benum\s+%SYMBOL%\b"""),
    DefinitionPattern("trait", """\btrait\s+%SYMBOL%\b"""),
    DefinitionPattern("constant", """\b(?:const|static)\s+%SYMBOL%\b""")
)

private val PATTERNS_BY_EXTENSION: Map<String, List<DefinitionPattern>> = mapOf(
    "kt" to KOTLIN_PATTERNS, "kts" to KOTLIN_PATTERNS,
    "java" to JAVA_PATTERNS,
    "ts" to TS_JS_PATTERNS, "tsx" to TS_JS_PATTERNS, "js" to TS_JS_PATTERNS, "jsx" to TS_JS_PATTERNS, "mjs" to TS_JS_PATTERNS,
    "py" to PYTHON_PATTERNS,
    "go" to GO_PATTERNS,
    "rs" to RUST_PATTERNS
)

val SUPPORTED_DEFINITION_EXTENSIONS: Set<String> = PATTERNS_BY_EXTENSION.keys

fun definitionPatternsFor(extension: String): List<DefinitionPattern> =
    PATTERNS_BY_EXTENSION[extension.lowercase()] ?: emptyList()
