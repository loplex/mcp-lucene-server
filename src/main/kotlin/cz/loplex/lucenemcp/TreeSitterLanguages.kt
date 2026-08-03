package cz.loplex.lucenemcp

import org.treesitter.TSLanguage
import org.treesitter.TreeSitterGo
import org.treesitter.TreeSitterJava
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterKotlin
import org.treesitter.TreeSitterPython
import org.treesitter.TreeSitterRust
import org.treesitter.TreeSitterTsx
import org.treesitter.TreeSitterTypescript

/** Which tree-sitter grammar (a name shared with the per-language query tables in [AstQueries]) handles a file extension. */
private val LANGUAGE_NAME_BY_EXTENSION: Map<String, String> = mapOf(
    "kt" to "kotlin", "kts" to "kotlin",
    "java" to "java",
    "ts" to "typescript",
    "tsx" to "tsx",
    "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript",
    "py" to "python",
    "go" to "go",
    "rs" to "rust"
)

val SUPPORTED_AST_EXTENSIONS: Set<String> = LANGUAGE_NAME_BY_EXTENSION.keys

fun languageNameFor(extension: String): String? = LANGUAGE_NAME_BY_EXTENSION[extension.lowercase()]

/** A fresh TSLanguage handle for [languageName]. Cheap to create; tree-sitter-ng language objects are thin native pointers. */
fun newLanguageInstance(languageName: String): TSLanguage = when (languageName) {
    "kotlin" -> TreeSitterKotlin()
    "java" -> TreeSitterJava()
    "typescript" -> TreeSitterTypescript()
    "tsx" -> TreeSitterTsx()
    "javascript" -> TreeSitterJavascript()
    "python" -> TreeSitterPython()
    "go" -> TreeSitterGo()
    "rust" -> TreeSitterRust()
    else -> throw IllegalArgumentException("Unsupported language: $languageName")
}
