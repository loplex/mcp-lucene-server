package cz.loplex.lucenemcp.ast

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*

import org.treesitter.TSLanguage
import org.treesitter.TreeSitterGo
import org.treesitter.TreeSitterJava
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterKotlin
import org.treesitter.TreeSitterPython
import org.treesitter.TreeSitterRust
import org.treesitter.TreeSitterTsx
import org.treesitter.TreeSitterTypescript
import org.treesitter.TreeSitterC
import org.treesitter.TreeSitterCpp
import org.treesitter.TreeSitterCSharp
import org.treesitter.TreeSitterPhp
import org.treesitter.TreeSitterRuby
import org.treesitter.TreeSitterSwift

/** Which tree-sitter grammar (a name shared with the per-language query tables in [AstQueries]) handles a file extension. */
private val LANGUAGE_NAME_BY_EXTENSION: Map<String, String> = mapOf(
    "kt" to "kotlin", "kts" to "kotlin",
    "java" to "java",
    "ts" to "typescript",
    "tsx" to "tsx",
    "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript",
    "py" to "python",
    "go" to "go",
    "rs" to "rust",
    "c" to "c", "h" to "c",
    "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hxx" to "cpp",
    "cs" to "c_sharp",
    "php" to "php",
    "rb" to "ruby",
    "swift" to "swift"
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
    "c" -> TreeSitterC()
    "cpp" -> TreeSitterCpp()
    "c_sharp" -> TreeSitterCSharp()
    "php" -> TreeSitterPhp()
    "ruby" -> TreeSitterRuby()
    "swift" -> TreeSitterSwift()
    else -> throw IllegalArgumentException("Unsupported language: $languageName")
}
