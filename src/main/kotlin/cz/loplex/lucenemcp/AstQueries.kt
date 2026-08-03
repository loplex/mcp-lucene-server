package cz.loplex.lucenemcp

import org.treesitter.TSNode
import java.io.File

/**
 * One tree-sitter query pattern identifying a definition of [kind] (e.g. "class", "function").
 * [source] must end in a single `@definition.<kind>` capture on the definition node itself, with
 * a `@name` capture on the identifier node holding the symbol's name.
 *
 * Some grammars use one node type for several kinds (Kotlin's `class_declaration` covers class,
 * interface, and enum-class alike). When several patterns match the *same* name node, the one
 * with the lowest [priority] wins — see the dedup step in `FindDefinitionTool.kt`.
 */
data class DefinitionQuery(val kind: String, val source: String, val priority: Int = 0)

/**
 * Per-language rules for classifying a `find_references` hit. [identifierNodeTypes] are the leaf
 * node types scanned for occurrences of the symbol's text. A hit's own node type refines it to
 * "type" or "member"; otherwise its ancestor chain is checked for "import" or "call" context.
 * This is a cheap, best-effort classification (e.g. a call's receiver/object may also be labeled
 * "call") — not a scope/import-aware resolver.
 */
data class ReferenceRules(
    val identifierNodeTypes: Set<String>,
    val typeNodeTypes: Set<String> = setOf("type_identifier"),
    val memberNodeTypes: Set<String> = setOf("field_identifier", "property_identifier", "shorthand_property_identifier"),
    val callAncestorTypes: Set<String> = emptySet(),
    val importAncestorTypes: Set<String> = emptySet()
)

private val KOTLIN_DEFINITIONS = listOf(
    DefinitionQuery("typealias", """(type_alias (type_identifier) @name) @definition.typealias"""),
    DefinitionQuery("interface", """(class_declaration "interface" (type_identifier) @name) @definition.interface""", priority = 0),
    DefinitionQuery("enum", """(class_declaration "enum" (type_identifier) @name) @definition.enum""", priority = 0),
    DefinitionQuery("class", """(class_declaration "class" (type_identifier) @name) @definition.class""", priority = 1),
    DefinitionQuery("object", """(object_declaration (type_identifier) @name) @definition.object"""),
    DefinitionQuery("function", """(function_declaration (simple_identifier) @name) @definition.function"""),
    DefinitionQuery("property", """(property_declaration (variable_declaration (simple_identifier) @name)) @definition.property""")
)

private val JAVA_DEFINITIONS = listOf(
    DefinitionQuery("class", """(class_declaration name: (identifier) @name) @definition.class"""),
    DefinitionQuery("interface", """(interface_declaration name: (identifier) @name) @definition.interface"""),
    DefinitionQuery("enum", """(enum_declaration name: (identifier) @name) @definition.enum"""),
    DefinitionQuery("record", """(record_declaration name: (identifier) @name) @definition.record"""),
    DefinitionQuery("method", """(method_declaration name: (identifier) @name) @definition.method"""),
    DefinitionQuery("constructor", """(constructor_declaration name: (identifier) @name) @definition.constructor""")
)

private val TYPESCRIPT_DEFINITIONS = listOf(
    DefinitionQuery("type", """(type_alias_declaration name: (type_identifier) @name) @definition.type"""),
    DefinitionQuery("interface", """(interface_declaration name: (type_identifier) @name) @definition.interface"""),
    DefinitionQuery("enum", """(enum_declaration name: (identifier) @name) @definition.enum"""),
    DefinitionQuery("class", """(class_declaration name: (type_identifier) @name) @definition.class"""),
    DefinitionQuery("function", """(function_declaration name: (identifier) @name) @definition.function"""),
    DefinitionQuery("function", """(variable_declarator name: (identifier) @name value: (arrow_function)) @definition.function""", priority = 0),
    DefinitionQuery("variable", """(variable_declarator name: (identifier) @name) @definition.variable""", priority = 1),
    DefinitionQuery("method", """(method_definition name: (property_identifier) @name) @definition.method"""),
    DefinitionQuery("field", """(public_field_definition name: (property_identifier) @name) @definition.field""")
)

private val JAVASCRIPT_DEFINITIONS = listOf(
    DefinitionQuery("function", """(function_declaration name: (identifier) @name) @definition.function"""),
    DefinitionQuery("function", """(variable_declarator name: (identifier) @name value: (arrow_function)) @definition.function""", priority = 0),
    DefinitionQuery("variable", """(variable_declarator name: (identifier) @name) @definition.variable""", priority = 1),
    DefinitionQuery("class", """(class_declaration name: (identifier) @name) @definition.class"""),
    DefinitionQuery("method", """(method_definition name: (property_identifier) @name) @definition.method"""),
    DefinitionQuery("field", """(field_definition property: (property_identifier) @name) @definition.field""")
)

private val PYTHON_DEFINITIONS = listOf(
    DefinitionQuery("class", """(class_definition name: (identifier) @name) @definition.class"""),
    DefinitionQuery("function", """(function_definition name: (identifier) @name) @definition.function"""),
    DefinitionQuery("variable", """(assignment left: (identifier) @name) @definition.variable""")
)

private val GO_DEFINITIONS = listOf(
    DefinitionQuery("function", """(function_declaration name: (identifier) @name) @definition.function"""),
    DefinitionQuery("method", """(method_declaration name: (field_identifier) @name) @definition.method"""),
    DefinitionQuery("type", """(type_spec name: (type_identifier) @name) @definition.type"""),
    DefinitionQuery("variable", """(var_spec name: (identifier) @name) @definition.variable"""),
    DefinitionQuery("constant", """(const_spec name: (identifier) @name) @definition.constant""")
)

private val RUST_DEFINITIONS = listOf(
    DefinitionQuery("function", """(function_item name: (identifier) @name) @definition.function"""),
    DefinitionQuery("struct", """(struct_item name: (type_identifier) @name) @definition.struct"""),
    DefinitionQuery("enum", """(enum_item name: (type_identifier) @name) @definition.enum"""),
    DefinitionQuery("trait", """(trait_item name: (type_identifier) @name) @definition.trait"""),
    DefinitionQuery("constant", """(const_item name: (identifier) @name) @definition.constant"""),
    DefinitionQuery("constant", """(static_item name: (identifier) @name) @definition.constant"""),
    DefinitionQuery("type", """(type_item name: (type_identifier) @name) @definition.type""")
)

val DEFINITIONS_BY_LANGUAGE: Map<String, List<DefinitionQuery>> = mapOf(
    "kotlin" to KOTLIN_DEFINITIONS,
    "java" to JAVA_DEFINITIONS,
    "typescript" to TYPESCRIPT_DEFINITIONS,
    "tsx" to TYPESCRIPT_DEFINITIONS,
    "javascript" to JAVASCRIPT_DEFINITIONS,
    "python" to PYTHON_DEFINITIONS,
    "go" to GO_DEFINITIONS,
    "rust" to RUST_DEFINITIONS
)

val REFERENCE_RULES_BY_LANGUAGE: Map<String, ReferenceRules> = mapOf(
    "kotlin" to ReferenceRules(
        identifierNodeTypes = setOf("simple_identifier", "type_identifier"),
        callAncestorTypes = setOf("call_expression"),
        importAncestorTypes = setOf("import_header")
    ),
    "java" to ReferenceRules(
        identifierNodeTypes = setOf("identifier", "type_identifier"),
        callAncestorTypes = setOf("method_invocation", "object_creation_expression"),
        importAncestorTypes = setOf("import_declaration")
    ),
    "typescript" to ReferenceRules(
        identifierNodeTypes = setOf("identifier", "type_identifier", "property_identifier"),
        callAncestorTypes = setOf("call_expression", "new_expression"),
        importAncestorTypes = setOf("import_statement")
    ),
    "tsx" to ReferenceRules(
        identifierNodeTypes = setOf("identifier", "type_identifier", "property_identifier"),
        callAncestorTypes = setOf("call_expression", "new_expression"),
        importAncestorTypes = setOf("import_statement")
    ),
    "javascript" to ReferenceRules(
        identifierNodeTypes = setOf("identifier", "property_identifier", "shorthand_property_identifier"),
        callAncestorTypes = setOf("call_expression", "new_expression"),
        importAncestorTypes = setOf("import_statement")
    ),
    "python" to ReferenceRules(
        identifierNodeTypes = setOf("identifier"),
        callAncestorTypes = setOf("call"),
        importAncestorTypes = setOf("import_statement", "import_from_statement")
    ),
    "go" to ReferenceRules(
        identifierNodeTypes = setOf("identifier", "type_identifier", "field_identifier", "package_identifier"),
        callAncestorTypes = setOf("call_expression"),
        importAncestorTypes = setOf("import_spec", "import_declaration")
    ),
    "rust" to ReferenceRules(
        identifierNodeTypes = setOf("identifier", "type_identifier", "field_identifier"),
        callAncestorTypes = setOf("call_expression"),
        importAncestorTypes = setOf("use_declaration")
    )
)

/** Classifies a candidate reference node using [rules] — own node type first, then ancestor chain. */
fun classifyReferenceKind(node: TSNode, rules: ReferenceRules): String {
    if (rules.typeNodeTypes.contains(node.type)) return "type"
    if (rules.memberNodeTypes.contains(node.type)) return "member"

    var current: TSNode? = node.parent
    while (current != null && !current.isNull) {
        if (rules.importAncestorTypes.contains(current.type)) return "import"
        if (rules.callAncestorTypes.contains(current.type)) return "call"
        current = current.parent
    }
    return "reference"
}

/**
 * True if [node] sits on the qualified/selector side of a member access (`receiver.node`), and is
 * therefore resolved by the receiver's own type, not by [node]'s file's imports or package — import-
 * aware candidate filtering (see [ImportInfo]/`isCandidateFile` in `FindReferencesTool.kt`) must never
 * drop these.
 *
 * Every supported language reuses its member-access node type for other, unrelated positions too —
 * so a plain "is this node's own type one of [ReferenceRules.memberNodeTypes]" check is not enough
 * on its own ([classifyReferenceKind] uses exactly that, which is fine for *labeling* a hit "member"
 * once it's already been decided the hit is included, but too broad for *deciding* inclusion): TS/JS
 * `property_identifier` is also an interface field name (`{ symbol: string }`) and an object literal
 * key (`{ symbol: 1 }`), Go/Rust `field_identifier` is also a struct field declaration and a struct
 * literal key — none of those are resolved by a receiver's type, so they must stay subject to
 * candidate filtering like any bare identifier. This checks the parent's exact shape (member
 * expression/selector/field-access with this node in the specific "property"/"field" position) for
 * every language, the same way it already did for Kotlin/Java/Python.
 */
fun isQualifiedAccess(node: TSNode, languageName: String): Boolean {
    val parent = node.parent ?: return false
    if (parent.isNull) return false
    return when (languageName) {
        "kotlin" -> parent.type == "navigation_suffix"
        "java" -> when (parent.type) {
            "field_access" -> fieldIs(parent, "field", node)
            "method_invocation" -> fieldIs(parent, "name", node) && parent.getChildByFieldName("object")?.isNull == false
            else -> false
        }
        "python" -> parent.type == "attribute" && fieldIs(parent, "attribute", node)
        "typescript", "tsx", "javascript" -> parent.type == "member_expression" && fieldIs(parent, "property", node)
        "go" -> parent.type == "selector_expression" && fieldIs(parent, "field", node)
        "rust" -> parent.type == "field_expression" && fieldIs(parent, "field", node)
        else -> false
    }
}

private fun fieldIs(parent: TSNode, fieldName: String, node: TSNode): Boolean {
    val field = parent.getChildByFieldName(fieldName) ?: return false
    return !field.isNull && field.startByte == node.startByte && field.endByte == node.endByte
}

/**
 * One import/use statement's contribution to [ImportInfo]. [importedNames] is every
 * identifier-shaped leaf found inside that one statement (path segments and aliases alike) — a
 * cheap over-approximation, not a resolved symbol table: it also catches Go's bare single-segment
 * import string (`import "fmt"` behaves like a local name), which happens to be exactly the name
 * Go code qualifies with.
 *
 * [resolvedFile] is the on-disk file this *specific* statement's module path points to, for
 * languages/forms [resolveImportTarget] can resolve (TS/JS relative imports, Python `from X import
 * Y`) — null means either unresolvable (bare/package specifier, external module) or not attempted
 * for this language/form, and callers must treat null as "unknown", not "resolves to nothing": see
 * `isCandidateFile` in `FindReferencesTool.kt` for why a null here still falls back to the old
 * name-mention heuristic instead of dropping the statement.
 */
data class ImportRecord(val importedNames: Set<String>, val isWildcard: Boolean, val resolvedFile: File?)

/**
 * A file's package/module declaration and imports, cheap to extract and used to narrow
 * `find_references` candidates — see [IMPORT_QUERIES_BY_LANGUAGE] and `extractImportInfo` in
 * `AstParser.kt`. [packageName] is `""` for files with no package declaration (Kotlin/Java's
 * "default package") or for languages without this concept — comparisons only happen for
 * [ImportQueryConfig.packageAware] languages, so the sentinel is never compared across languages.
 * [records] is one entry per import statement (see [ImportRecord]); [importedNames]/
 * [hasWildcardImport] are the flattened union, kept for languages that only ever do the coarse
 * whole-file check (Kotlin/Java/Go — see `isCandidateFile`).
 */
data class ImportInfo(val packageName: String, val records: List<ImportRecord>) {
    val importedNames: Set<String> get() = records.flatMap { it.importedNames }.toSet()
    val hasWildcardImport: Boolean get() = records.any { it.isWildcard }
}

/**
 * Where to find, in a language's grammar, the package/module declaration ([packageQuery], capturing
 * `@package` on the whole statement — the leading keyword is stripped textually) and import
 * statements ([importQuery], capturing `@import` once per statement). [wildcardNodeTypes] are node
 * types that, found anywhere inside an `@import` capture, mean "brings names into scope unqualified,
 * with no way to tell which ones from the statement's text alone" (Go's dot-import counts as this
 * language's wildcard form, hence the lone `"dot"` type instead of a `*`-shaped node).
 */
data class ImportQueryConfig(val packageQuery: String?, val importQuery: String, val wildcardNodeTypes: Set<String>) {
    val packageAware: Boolean get() = packageQuery != null
}

val IMPORT_QUERIES_BY_LANGUAGE: Map<String, ImportQueryConfig> = mapOf(
    "kotlin" to ImportQueryConfig(
        packageQuery = "(package_header) @package",
        importQuery = "(import_header) @import",
        wildcardNodeTypes = setOf("wildcard_import")
    ),
    "java" to ImportQueryConfig(
        packageQuery = "(package_declaration) @package",
        importQuery = "(import_declaration) @import",
        wildcardNodeTypes = setOf("asterisk")
    ),
    "typescript" to ImportQueryConfig(null, "(import_statement) @import", emptySet()),
    "tsx" to ImportQueryConfig(null, "(import_statement) @import", emptySet()),
    "javascript" to ImportQueryConfig(null, "(import_statement) @import", emptySet()),
    "python" to ImportQueryConfig(
        packageQuery = null,
        importQuery = "(import_statement) @import (import_from_statement) @import",
        wildcardNodeTypes = setOf("wildcard_import")
    ),
    "go" to ImportQueryConfig(
        packageQuery = "(package_clause) @package",
        importQuery = "(import_spec) @import",
        wildcardNodeTypes = setOf("dot")
    ),
    "rust" to ImportQueryConfig(null, "(use_declaration) @import", setOf("use_wildcard"))
)
