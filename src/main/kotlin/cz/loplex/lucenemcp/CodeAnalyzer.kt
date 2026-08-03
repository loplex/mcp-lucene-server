package cz.loplex.lucenemcp

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.core.FlattenGraphFilter
import org.apache.lucene.analysis.core.WhitespaceTokenizer
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter

class CodeAnalyzer : Analyzer() {
    override fun createComponents(fieldName: String?): TokenStreamComponents {
        val tokenizer = WhitespaceTokenizer()
        val filter = WordDelimiterGraphFilter(
            tokenizer,
            WordDelimiterGraphFilter.GENERATE_WORD_PARTS or
            WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS or
            WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE or
            WordDelimiterGraphFilter.PRESERVE_ORIGINAL or
            WordDelimiterGraphFilter.SPLIT_ON_NUMERICS,
            null
        )
        // WordDelimiterGraphFilter emits a token *graph* (parallel paths at the same position, e.g.
        // "openDatabase" also splits into "open"+"Database"). Per its own Javadoc, indexing a graph
        // stream without flattening it first corrupts term positions — phrase/proximity queries then
        // silently match nothing, even though plain term queries (which ignore position) still work.
        return TokenStreamComponents(tokenizer, FlattenGraphFilter(filter))
    }
}
