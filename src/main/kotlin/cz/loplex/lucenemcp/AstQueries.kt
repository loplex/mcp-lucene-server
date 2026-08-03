package cz.loplex.lucenemcp

import org.treesitter.TSNode

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
