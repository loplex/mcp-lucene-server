package cz.loplex.lucenemcp.ast

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*

import org.treesitter.TSNode
import java.io.File

/**
 * Languages/forms [resolveImportTarget] can resolve a specific on-disk file for — TS/JS relative
 * imports and Python `from X import Y`. Other languages (Kotlin/Java/Go) import by
 * class/package path, not filesystem path, and Rust's `use` paths need crate-graph info we don't
 * have; those keep the coarser name/package-mention heuristic in `isCandidateFile` unconditionally.
 */
val PATH_RESOLVABLE_LANGUAGES: Set<String> = setOf("typescript", "tsx", "javascript", "python")

private val JS_SOURCE_EXTENSIONS = listOf("ts", "tsx", "js", "jsx", "mjs")

/**
 * Resolves [importNode]'s (a `@import`-captured node from [IMPORT_QUERIES_BY_LANGUAGE]) module
 * target to a file inside [root], if [languageName] and the statement's form support it. Returns
 * null for anything unresolvable — a bare/package specifier (`'lodash'`, `from os import path`),
 * a relative path that doesn't exist on disk, or a form this function doesn't attempt (Python's
 * plain `import a.b` binds a module object, not a symbol, and comma-separated names in one
 * statement can each target a different module — out of scope, always falls back to the
 * name-mention heuristic).
 */
fun resolveImportTarget(importNode: TSNode, parsed: ParsedFile, currentFile: File, root: File, languageName: String): File? =
    when (languageName) {
        "typescript", "tsx", "javascript" -> resolveJsImport(importNode, parsed, currentFile, root)
        "python" -> if (importNode.type == "import_from_statement") resolvePythonFromImport(importNode, parsed, currentFile, root) else null
        else -> null
    }

private fun resolveJsImport(importNode: TSNode, parsed: ParsedFile, currentFile: File, root: File): File? {
    val sourceField = importNode.getChildByFieldName("source")
    if (sourceField == null || sourceField.isNull) return null
    val fragment = directChildren(sourceField).firstOrNull { it.type == "string_fragment" } ?: return null
    val path = parsed.textOf(fragment)
    if (!path.startsWith(".")) return null // bare specifier (npm package, path alias) — not our filesystem to search
    val baseDir = currentFile.parentFile ?: return null
    return resolveOnDisk(File(baseDir, path), root, JS_SOURCE_EXTENSIONS)
}

private fun resolvePythonFromImport(importNode: TSNode, parsed: ParsedFile, currentFile: File, root: File): File? {
    val moduleNameNode = importNode.getChildByFieldName("module_name")
    if (moduleNameNode == null || moduleNameNode.isNull) return null

    return when (moduleNameNode.type) {
        "relative_import" -> {
            val children = directChildren(moduleNameNode)
            val prefix = children.firstOrNull { it.type == "import_prefix" } ?: return null
            val dots = parsed.textOf(prefix).length
            val dottedName = children.firstOrNull { it.type == "dotted_name" }
            val segments = dottedName?.let { pythonDottedNameSegments(it, parsed) } ?: return null // "from . import x" — ambiguous, see kdoc
            var baseDir: File = currentFile.parentFile ?: return null
            repeat(dots - 1) { baseDir = baseDir.parentFile ?: return null } // one dot = current directory
            resolvePythonModule(baseDir, segments, root)
        }
        "dotted_name" -> resolvePythonModule(root, pythonDottedNameSegments(moduleNameNode, parsed), root)
        else -> null
    }
}

private fun pythonDottedNameSegments(dottedName: TSNode, parsed: ParsedFile): List<String> =
    directChildren(dottedName).filter { it.type == "identifier" }.map { parsed.textOf(it) }

private fun resolvePythonModule(baseDir: File, segments: List<String>, root: File): File? {
    if (segments.isEmpty()) return null
    val dir = segments.dropLast(1).fold(baseDir) { acc, segment -> File(acc, segment) }
    val leaf = segments.last()
    return resolveOnDisk(File(dir, leaf), root, listOf("py"), packageInitFile = "__init__.py")
}

/**
 * Tries [target] as-is, then with each of [extensions] appended, then (if [packageInitFile] is
 * set) as a package directory's init file — the first one that exists as a real file inside
 * [root] wins. Guards against a relative import climbing outside the project root.
 */
private fun resolveOnDisk(target: File, root: File, extensions: List<String>, packageInitFile: String? = null): File? {
    val rootCanonical = root.canonicalFile
    val candidates = buildList {
        add(target)
        if (target.extension !in extensions) {
            extensions.forEach { add(File(target.path + ".$it")) }
            if (packageInitFile != null) add(File(target, packageInitFile))
        }
    }
    for (candidate in candidates) {
        val canonical = try {
            candidate.canonicalFile
        } catch (e: Exception) {
            continue
        }
        if (canonical != rootCanonical && !canonical.path.startsWith(rootCanonical.path + File.separator)) continue
        if (canonical.isFile) return canonical
    }
    return null
}

private fun directChildren(node: TSNode): List<TSNode> = (0 until node.childCount).map { node.getChild(it) }
