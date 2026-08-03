package cz.loplex.lucenemcp

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.standard.StandardTokenizer

/**
 * Tokenizes each contiguous run of letters/digits as exactly one term — no camelCase/word-part
 * splitting and no parallel paths, unlike [CodeAnalyzer]. Every identifier occupies exactly one
 * position slot, so slop in a `words:"a b"~N` phrase/proximity query counts real words apart.
 *
 * This is what `content` cannot do: WordDelimiterGraphFilter's decomposition of camelCase
 * identifiers inflates position counts on the query and the document side independently, so
 * phrase/proximity queries against `content` can match nothing even at a large slop (confirmed
 * empirically — see NOTES/AI/plan.md step 13). `StandardTokenizer` also cleanly drops punctuation
 * (`connect()` becomes `connect`), unlike `content`'s `WhitespaceTokenizer`, which leaves it stuck
 * to the `PRESERVE_ORIGINAL` copy of the token. No `LowerCaseFilter`: kept case-sensitive, matching
 * `content`'s behavior, since code identifiers are meaningfully cased.
 */
class WordAnalyzer : Analyzer() {
    override fun createComponents(fieldName: String?): TokenStreamComponents {
        return TokenStreamComponents(StandardTokenizer())
    }
}
