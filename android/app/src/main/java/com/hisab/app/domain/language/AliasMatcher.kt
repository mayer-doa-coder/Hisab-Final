package com.hisab.app.domain.language

/**
 * One thing a sentence could be talking about — a product or a customer —
 * with every name it goes by.
 *
 * [aliases] is the shopkeeper's own list, typed on the Add Product screen
 * back in M1: "coke" for "Coca-Cola 500ml", "chini" for "চিনি". This
 * matcher never invents an alias; it only finds the ones the shop set.
 */
data class AliasEntry(
    val id: String,
    val kind: Kind,
    val display: String,
    val aliases: List<String> = emptyList(),
) {
    enum class Kind { PRODUCT, CUSTOMER }
}

/** Where in the sentence a known product or customer was found. */
data class AliasMatch(
    val id: String,
    val kind: AliasEntry.Kind,
    val display: String,
    /** The words from the sentence that matched, as the shopkeeper typed them. */
    val matchedTokens: List<String>,
    val startIndex: Int,
    val endIndex: Int,
) {
    val wordCount: Int get() = endIndex - startIndex
}

/**
 * The last stage of Step 73's pipeline, and the whole of Step 74: finding
 * which of the shop's own products and customers a sentence names.
 *
 * Both the sentence and every name are put through the same normalizer
 * first, so "COKE", "coke" and "Coke" are one word, and so a Romanized
 * name matches however it was spelled. That is the reason this is a stage
 * of the pipeline rather than a plain string comparison: "chini" typed in
 * a question has been through exactly what "Chini" typed as an alias has.
 *
 * Longest match wins. "coca cola 500ml" must beat "coca", or a shop with
 * both "Coca-Cola 500ml" and "Coca-Cola 1L" would answer about whichever
 * happened to be listed first.
 */
class AliasMatcher(
    entries: List<AliasEntry>,
) {
    /** Every name and alias, normalized once at construction rather than per question. */
    private val byPhrase: Map<List<String>, AliasEntry> =
        buildMap {
            for (entry in entries) {
                // A Bangla name is also indexed written out in Latin letters,
                // so a customer saved as "রহিম" is found by someone typing
                // "rahim" — the ordinary way to ask in a Bangla shop, and a
                // question that failed outright before. The shop's own
                // aliases are indexed first, so they always win over a guess.
                val written = listOf(entry.display) + entry.aliases
                val transliterated = written.flatMap(TextNormalizer::transliterateFromBangla)

                for (name in written + transliterated) {
                    val phrase = TextNormalizer.normalize(name).result
                    if (phrase.isEmpty()) continue
                    // First one wins, so a shop that uses the same alias for
                    // two products keeps answering about the same one rather
                    // than changing its mind between questions.
                    putIfAbsent(phrase, entry)
                }
            }
        }

    private val longestPhrase = byPhrase.keys.maxOfOrNull { it.size } ?: 0

    /** How many names and aliases are indexed, for Step 83's storage figure. */
    val indexedPhraseCount: Int get() = byPhrase.size

    /** How many distinct products and customers are behind those names. */
    val entryCount: Int = entries.size

    /**
     * Every product and customer named in an already-normalized sentence,
     * left to right, without overlapping. Pass the `result` of
     * [TextNormalizer.normalize].
     */
    fun resolve(normalizedTokens: List<String>): List<AliasMatch> {
        val matches = mutableListOf<AliasMatch>()
        var at = 0

        while (at < normalizedTokens.size) {
            val longestHere = minOf(longestPhrase, normalizedTokens.size - at)
            var matched = false

            // Longest first, so the most specific name in the sentence wins.
            for (length in longestHere downTo 1) {
                val phrase = normalizedTokens.subList(at, at + length)
                val entry = byPhrase[phrase] ?: entryForInflected(phrase) ?: continue
                matches +=
                    AliasMatch(
                        id = entry.id,
                        kind = entry.kind,
                        display = entry.display,
                        matchedTokens = phrase.toList(),
                        startIndex = at,
                        endIndex = at + length,
                    )
                at += length
                matched = true
                break
            }

            if (!matched) at += 1
        }
        return matches
    }

    /**
     * The same phrase with a grammatical ending taken off the last word —
     * "রহিমের" found as "রহিম", "cokeer" as "coke". Bangla glues its endings
     * straight onto a name, so without this a shop's own customer is invisible
     * in the most ordinary way of asking about them ("রহিমের বাকি কত").
     *
     * Only the last word is tried, because that is where the ending lands.
     */
    private fun entryForInflected(phrase: List<String>): AliasEntry? {
        val last = phrase.lastOrNull() ?: return null
        for (candidate in TextNormalizer.candidates(last).drop(1)) {
            byPhrase[phrase.dropLast(1) + candidate]?.let { return it }
        }
        return null
    }

    /** The whole pipeline in one call: raw text in, named things out. */
    fun resolveText(text: String): List<AliasMatch> = resolve(TextNormalizer.normalize(text).result)
}
