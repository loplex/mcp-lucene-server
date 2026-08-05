package cz.loplex.lucenemcp.tools

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.tools.*

import org.treesitter.TSNode
import java.io.File

fun runCallHierarchy(root: File, symbol: String, direction: String, astCache: AstCache = AstCache(), externalRoots: List<File> = emptyList()): String {
    if (symbol.isBlank()) return "Missing required argument: symbol"
    if (direction != "incoming" && direction != "outgoing") return "Direction must be 'incoming' or 'outgoing'"

    if (direction == "incoming") {
        return findIncomingCalls(root, symbol, astCache, externalRoots)
    } else {
        return findOutgoingCalls(root, symbol, astCache, externalRoots)
    }
}

private fun findOutgoingCalls(root: File, symbol: String, astCache: AstCache, externalRoots: List<File>): String {
    val results = mutableListOf<String>()
    
    // 1. Find definition of the symbol (function/method)
    val projectFiles = listProjectFiles(root, externalRoots).sortedBy { it.path }
    val candidates = projectFiles.filter { file ->
        val lang = languageNameFor(file.extension)
        lang != null && file.readText().contains(symbol)
    }

    astCache.prune(candidates.mapTo(HashSet()) { it.absolutePath })

    val callsFound = mutableSetOf<String>()

    for (file in candidates) {
        val lang = languageNameFor(file.extension) ?: continue
        val definitions = DEFINITIONS_BY_LANGUAGE[lang] ?: continue
        val parsed = astCache.getOrParse(file, file.extension) ?: continue

        // Find the function definition node
        val funcNodes = mutableListOf<TSNode>()
        for (def in definitions) {
            if (def.kind != "function" && def.kind != "method" && def.kind != "constructor") continue
            val query = compiledQuery(lang, def.source) ?: continue
            val cursor = org.treesitter.TSQueryCursor()
            cursor.exec(query, parsed.tree.rootNode)
            val match = org.treesitter.TSQueryMatch()
            while (cursor.nextMatch(match)) {
                var isMatch = false
                var definitionNode: TSNode? = null
                for (capture in match.captures) {
                    val captureName = query.getCaptureNameForId(capture.index)
                    if (captureName == "name" && parsed.textOf(capture.node) == symbol) {
                        isMatch = true
                    } else if (captureName.startsWith("definition.")) {
                        definitionNode = capture.node
                    }
                }
                if (isMatch && definitionNode != null) {
                    funcNodes.add(definitionNode)
                }
            }
        }

        // 2. For each function body, find all outgoing calls
        val rules = REFERENCE_RULES_BY_LANGUAGE[lang] ?: continue
        for (funcNode in funcNodes) {
            val outgoings = extractOutgoingCalls(funcNode, parsed, rules.identifierNodeTypes)
            for (out in outgoings) {
                if (callsFound.add(out)) {
                    val relativePath = if (file.absolutePath.startsWith(root.absolutePath)) {
                        file.relativeTo(root).path.replace(File.separatorChar, '/')
                    } else {
                        file.absolutePath.replace(File.separatorChar, '/')
                    }
                    results.add("- $out() called from $relativePath:${funcNode.startPoint.row + 1}")
                }
            }
        }
    }

    if (results.isEmpty()) {
        return "No outgoing calls found from symbol '$symbol'."
    }
    return "Outgoing calls from '$symbol':\n" + results.joinToString("\n")
}

private fun extractOutgoingCalls(node: TSNode, parsed: ParsedFile, identifierTypes: Set<String>): Set<String> {
    val results = mutableSetOf<String>()
    
    // We walk the AST inside the function body and find all identifier nodes that are part of a call expression.
    // As a rough approximation, we just find any node that is an identifier and whose parent/ancestor is a call.
    fun walk(n: TSNode) {
        if (identifierTypes.contains(n.type)) {
            // Check if it's a call
            var curr: TSNode? = n.parent
            var isCall = false
            while (curr != null && !curr.isNull) {
                val type = curr.type
                if (type.contains("call") || type.contains("invocation")) {
                    isCall = true
                    break
                }
                curr = curr.parent
            }
            if (isCall) {
                results.add(parsed.textOf(n))
            }
        }
        for (i in 0 until n.childCount) {
            walk(n.getChild(i))
        }
    }
    walk(node)
    
    return results
}

private fun findIncomingCalls(root: File, symbol: String, astCache: AstCache, externalRoots: List<File>): String {
    val results = mutableListOf<String>()
    
    // We reuse the first pass of find_references: text search for the symbol to find candidate files
    val projectFiles = listProjectFiles(root, externalRoots).sortedBy { it.path }
    val candidates = projectFiles.filter { file ->
        val lang = languageNameFor(file.extension)
        lang != null && file.readText().contains(symbol)
    }

    astCache.prune(candidates.mapTo(HashSet()) { it.absolutePath })

    val callersFound = mutableSetOf<String>()

    for (file in candidates) {
        val lang = languageNameFor(file.extension) ?: continue
        val rules = REFERENCE_RULES_BY_LANGUAGE[lang] ?: continue
        val parsed = astCache.getOrParse(file, file.extension) ?: continue

        val rootNode = parsed.tree.rootNode
        val matches = mutableListOf<TSNode>()
        fun walk(n: TSNode) {
            if (rules.identifierNodeTypes.contains(n.type) && parsed.textOf(n) == symbol) {
                matches.add(n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootNode)

        for (node in matches) {
            // Is it a call? (best effort via ancestor types if defined, or just assume it might be)
            var isCall = rules.callAncestorTypes.isEmpty()
            if (!isCall) {
                var curr: TSNode? = node.parent
                while (curr != null && !curr.isNull) {
                    if (rules.callAncestorTypes.contains(curr.type)) {
                        isCall = true
                        break
                    }
                    curr = curr.parent
                }
            }
            if (!isCall) continue

            // Find enclosing function/method
            val enclosingFunction = findEnclosingFunction(node, lang)
            if (enclosingFunction != null) {
                val funcName = extractFunctionName(enclosingFunction, parsed)
                if (funcName != null) {
                    val relativePath = if (file.absolutePath.startsWith(root.absolutePath)) {
                        file.relativeTo(root).path.replace(File.separatorChar, '/')
                    } else {
                        file.absolutePath.replace(File.separatorChar, '/')
                    }
                    val callerId = "$relativePath : $funcName"
                    if (callersFound.add(callerId)) {
                        val line = enclosingFunction.startPoint.row + 1
                        results.add("- $funcName() in $relativePath:$line")
                    }
                }
            }
        }
    }

    if (results.isEmpty()) {
        return "No incoming calls found for symbol '$symbol'."
    }
    return "Incoming calls to '$symbol':\n" + results.joinToString("\n")
}

private fun findEnclosingFunction(node: TSNode, lang: String): TSNode? {
    val functionTypes = when (lang) {
        "kotlin", "swift" -> setOf("function_declaration")
        "java", "c_sharp" -> setOf("method_declaration", "constructor_declaration")
        "typescript", "tsx", "javascript" -> setOf("function_declaration", "method_definition", "arrow_function")
        "python", "c", "cpp" -> setOf("function_definition")
        "go" -> setOf("function_declaration", "method_declaration")
        "rust" -> setOf("function_item")
        "php" -> setOf("function_definition", "method_declaration")
        "ruby" -> setOf("method", "singleton_method")
        else -> emptySet()
    }

    var curr: TSNode? = node.parent
    while (curr != null && !curr.isNull) {
        if (functionTypes.contains(curr.type)) {
            return curr
        }
        curr = curr.parent
    }
    return null
}

private fun extractFunctionName(funcNode: TSNode, parsed: ParsedFile): String? {
    // Best effort: find an identifier child
    for (i in 0 until funcNode.childCount) {
        val child = funcNode.getChild(i)
        if (child.type.contains("identifier") || child.type == "name" || child.type == "constant") {
            return parsed.textOf(child)
        }
    }
    return null
}
