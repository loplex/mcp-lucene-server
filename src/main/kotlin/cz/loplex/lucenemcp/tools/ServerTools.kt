package cz.loplex.lucenemcp.tools

import cz.loplex.lucenemcp.core.*
import cz.loplex.lucenemcp.index.*
import cz.loplex.lucenemcp.ast.*
import cz.loplex.lucenemcp.*
import com.google.gson.*
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.highlight.*
import java.io.StringReader
import java.io.File
import org.apache.lucene.analysis.Analyzer

fun handleSearchCode(arguments: JsonObject, indexManager: IndexManager, analyzer: Analyzer, watcherActive: Boolean): String {
    val queryStr = arguments.get("query")?.asString
    if (queryStr.isNullOrBlank()) return "Missing required argument: query"
    val limit = arguments.get("limit")?.asInt ?: DEFAULT_LIMIT

    if (!watcherActive) indexManager.sync()
    val searcher = indexManager.searcher

    return try {
        val queryParser = QueryParser("content", analyzer)
        val query = queryParser.parse(queryStr)
        val docs = searcher.search(query, limit)

        val formatter = SimpleHTMLFormatter("**", "**")
        val scorer = QueryScorer(query)
        val highlighter = Highlighter(formatter, scorer)
        highlighter.textFragmenter = SimpleFragmenter(160)
        highlighter.maxDocCharsToAnalyze = 1_000_000

        val sb = StringBuilder()
        sb.append("Found ${docs.totalHits.value} result(s). Showing top ${docs.scoreDocs.size}:\n\n")

        for (scoreDoc in docs.scoreDocs) {
            val doc = searcher.storedFields().document(scoreDoc.doc)
            val path = doc.get("path")
            val fullText = doc.get("content")

            sb.append("--- FILE: $path ---\n")
            val snippet = try {
                val tokenStream = analyzer.tokenStream("content", StringReader(fullText))
                highlighter.getBestFragments(tokenStream, fullText, 3, "\n...\n")
            } catch (e: Exception) {
                null
            }

            if (snippet.isNullOrBlank()) {
                sb.append(fullText.lineSequence().take(5).joinToString("\n"))
            } else {
                sb.append(snippet)
            }
            sb.append("\n\n")
        }

        sb.toString().trim()
    } catch (e: Exception) {
        "Lucene error parsing query: ${e.message}"
    }
}

fun handleReindexCode(indexManager: IndexManager): String {
    val result = indexManager.sync()
    return "Reindex complete: +${result.added} added, ~${result.updated} updated, -${result.deleted} deleted."
}

fun handleAddExternalRoots(arguments: JsonObject, indexManager: IndexManager): String {
    val directoriesStr = arguments.get("directories")?.asString
    if (directoriesStr.isNullOrBlank()) return "Missing required argument: directories"
    
    val dirs = directoriesStr.split(",").map { File(it.trim()) }
    val invalid = dirs.filter { !it.isDirectory }
    if (invalid.isNotEmpty()) {
        return "Error: The following paths are not valid directories: " + invalid.joinToString(", ") { it.absolutePath }
    }
    
    // Merge new roots, avoiding duplicates
    val existingRoots = indexManager.externalRoots.map { it.absolutePath }
    val newRoots = dirs.filter { it.absolutePath !in existingRoots }
    
    if (newRoots.isEmpty()) {
        return "No new valid directories to add. Current external roots: " + indexManager.externalRoots.joinToString(", ") { it.absolutePath }
    }
    
    indexManager.externalRoots = indexManager.externalRoots + newRoots
    val syncResult = indexManager.sync()
    
    return "Successfully added ${newRoots.size} external roots.\n" +
           "Sync result: +${syncResult.added} ~${syncResult.updated} -${syncResult.deleted}.\n" +
           "Total active external roots: ${indexManager.externalRoots.size}."
}

fun handleAddMavenDependencySources(arguments: JsonObject, indexManager: IndexManager): String {
    val artifact = arguments.get("artifact")?.asString
    if (artifact.isNullOrBlank()) return "Missing required argument: artifact"
    
    val parts = artifact.split(":")
    if (parts.size != 3) {
        return "Invalid artifact format. Must be groupId:artifactId:version"
    }
    
    val groupId = parts[0]
    val artifactId = parts[1]
    val version = parts[2]
    
    try {
        val process = ProcessBuilder("mvn", "dependency:get", "-Dartifact=$artifact:jar:sources")
            .redirectErrorStream(true)
            .start()
        
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        if (process.exitValue() != 0) {
            return "Maven failed to download sources:\n$output"
        }
        
        val m2Path = File(System.getProperty("user.home"), ".m2/repository")
        val groupPath = groupId.replace('.', '/')
        val jarFile = File(m2Path, "$groupPath/$artifactId/$version/$artifactId-$version-sources.jar")
        
        if (!jarFile.exists()) {
            return "Maven reported success, but sources jar was not found at expected path: ${jarFile.absolutePath}\nOutput:\n$output"
        }
        
        val existingRoots = indexManager.externalRoots.map { it.absolutePath }
        if (jarFile.absolutePath in existingRoots) {
            return "Sources for $artifact are already added."
        }
        
        indexManager.externalRoots = indexManager.externalRoots + jarFile
        val syncResult = indexManager.sync()
        return "Successfully downloaded and added sources for $artifact.\n" +
               "Sync result: +${syncResult.added} ~${syncResult.updated} -${syncResult.deleted}."
    } catch (e: Exception) {
        return "Failed to run Maven: ${e.message}"
    }
}


fun handleGrepCode(arguments: JsonObject, root: File, externalRoots: List<File>): String {
    val pattern = arguments.get("pattern")?.asString
    if (pattern.isNullOrBlank()) return "Missing required argument: pattern"

    val context = arguments.get("context")?.asInt ?: 0
    val options = GrepOptions(
        pattern = pattern,
        literal = arguments.get("literal")?.asBoolean ?: false,
        caseSensitive = arguments.get("caseSensitive")?.asBoolean ?: true,
        beforeContext = arguments.get("beforeContext")?.asInt ?: context,
        afterContext = arguments.get("afterContext")?.asInt ?: context,
        filePattern = arguments.get("filePattern")?.asString,
        outputMode = arguments.get("outputMode")?.asString ?: "content",
        maxMatches = arguments.get("maxMatches")?.asInt ?: DEFAULT_GREP_LIMIT
    )
    return runGrep(root, options, externalRoots)
}

fun handleReadFile(arguments: JsonObject, root: File): String {
    val path = arguments.get("path")?.asString
    if (path.isNullOrBlank()) return "Missing required argument: path"
    val startLine = arguments.get("startLine")?.asInt
    val endLine = arguments.get("endLine")?.asInt
    return readFileRange(root, path, startLine, endLine)
}

fun handleListFiles(arguments: JsonObject, root: File, externalRoots: List<File>): String {
    val pattern = arguments.get("pattern")?.asString
    val limit = arguments.get("limit")?.asInt ?: DEFAULT_LIST_LIMIT
    return runListFiles(root, pattern, limit, externalRoots)
}

fun handleFindDefinition(arguments: JsonObject, root: File, astCache: AstCache, externalRoots: List<File>): String {
    val symbol = arguments.get("symbol")?.asString
    if (symbol.isNullOrBlank()) return "Missing required argument: symbol"
    val maxMatches = arguments.get("maxMatches")?.asInt ?: DEFAULT_GREP_LIMIT
    return runFindDefinition(root, symbol, maxMatches, astCache, externalRoots)
}

fun handleExtractSymbol(arguments: JsonObject, root: File, astCache: AstCache, externalRoots: List<File>): String {
    val symbol = arguments.get("symbol")?.asString
    if (symbol.isNullOrBlank()) return "Missing required argument: symbol"
    val maxMatches = arguments.get("maxMatches")?.asInt ?: DEFAULT_GREP_LIMIT
    return runExtractSymbol(root, symbol, maxMatches, astCache, externalRoots)
}

fun handleFindReferences(arguments: JsonObject, root: File, astCache: AstCache, externalRoots: List<File>): String {
    val symbol = arguments.get("symbol")?.asString
    if (symbol.isNullOrBlank()) return "Missing required argument: symbol"
    val maxMatches = arguments.get("maxMatches")?.asInt ?: DEFAULT_GREP_LIMIT
    return runFindReferences(root, symbol, maxMatches, astCache, externalRoots)
}

fun handleFindImplementations(arguments: JsonObject, root: File, astCache: AstCache, externalRoots: List<File>): String {
    val type = arguments.get("type")?.asString
    if (type.isNullOrBlank()) return "Missing required argument: type"
    val maxMatches = arguments.get("maxMatches")?.asInt ?: DEFAULT_GREP_LIMIT
    return runFindImplementations(root, type, maxMatches, astCache, externalRoots)
}

fun handleOutline(arguments: JsonObject, root: File, astCache: AstCache): String {
    val path = arguments.get("path")?.asString ?: return "Missing path argument"
    return runOutline(root, path, astCache)
}

fun handleSearchAst(arguments: JsonObject, root: File, astCache: AstCache, externalRoots: List<File>): String {
    val query = arguments.get("query")?.asString ?: return "Missing query argument"
    val language = arguments.get("language")?.asString ?: return "Missing language argument"
    val pattern = arguments.get("pattern")?.asString
    return runSearchAst(root, query, language, pattern, astCache, externalRoots)
}

fun handleCallHierarchy(arguments: JsonObject, root: File, astCache: AstCache, externalRoots: List<File>): String {
    val symbol = arguments.get("symbol")?.asString ?: return "Missing symbol argument"
    val direction = arguments.get("direction")?.asString ?: return "Missing direction argument"
    return runCallHierarchy(root, symbol, direction, astCache, externalRoots)
}

