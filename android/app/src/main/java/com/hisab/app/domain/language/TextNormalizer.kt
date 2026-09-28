package com.hisab.app.domain.language

import com.hisab.app.domain.normalizeDigits
import java.text.Normalizer

/**
 * What one sentence looked like after each stage of the pipeline (Step 73).
 *
 * The stages are kept rather than thrown away so they can be looked at one
 * by one — which is what Step 73's check asks for, and what makes a wrong
 * answer later traceable to the stage that caused it instead of guessed at.
 */
data class NormalizationStages(
    val input: String,
    val unicode: String,
    val lowercased: String,
    val digitsNormalized: String,
    val tokens: List<String>,
    val romanized: List<String>,
) {
    /** What the rest of Ask Hisab works from. */
    val result: List<String> get() = romanized
}

/**
 * Turns what a shopkeeper typed into something the intent rules can match
 * against, in the order Step 73 sets out: Unicode, case, digits, tokens,
 * Romanized Bangla. Alias resolution is the stage after this one and lives
 * in `AliasMatcher` (Step 74), because it needs the shop's own products and
 * customers, which this file deliberately knows nothing about.
 *
 * No model, no network: this is all rules, which is what `CLAUDE.md`
 * requires of Ask Hisab.
 */
object TextNormalizer {
    fun normalize(input: String): NormalizationStages {
        // 1. Unicode. Bangla can arrive composed or decomposed depending on
        //    the keyboard: চ + ি may be one code point or two, and without
        //    this the same word typed on two phones would not be equal.
        val unicode = Normalizer.normalize(input, Normalizer.Form.NFC)

        // 2. Case. Root locale on purpose — a Turkish phone lowercases "I"
        //    to a dotless "ı", which would stop "ITEM" matching "item".
        val lowercased = unicode.lowercase()

        // 3. Digits. ৩৫০ and 350 are the same number to a shopkeeper, so
        //    they must be the same to us. Reuses the function the money
        //    parser already uses rather than keeping a second copy (D026).
        val digitsNormalized = normalizeDigits(lowercased)

        // 4. Tokens.
        val tokens = tokenize(digitsNormalized)

        // 5. Romanized Bangla. Only Latin-script words are folded; a word
        //    already in Bangla script is left exactly as it is.
        val romanized = tokens.map(::foldRomanized)

        return NormalizationStages(
            input = input,
            unicode = unicode,
            lowercased = lowercased,
            digitsNormalized = digitsNormalized,
            tokens = tokens,
            romanized = romanized,
        )
    }

    /**
     * Splits on punctuation and spaces, so "rahim-er", "rahim's" and
     * "rahim er" all come apart the same way. The Bangla full stop (dari,
     * U+0964) is punctuation like any other here.
     *
     * A number keeps its decimal point, because "১২.৫০" is one amount and
     * splitting it into 12 and 50 would invent a second number.
     *
     * What counts as part of a word is deliberately wider than
     * `isLetterOrDigit`. In Bangla a vowel sign is a *combining mark*, not
     * a letter: ি in চিনি answers false to `isLetterOrDigit`, so relying on
     * that alone tore "চিনি" into "চ" and "ন" and silently threw the vowels
     * away — nearly every Bangla word would have been mangled. Marks, the
     * hasant that builds conjuncts, and the zero-width joiners that control
     * them are all part of the word they sit in.
     */
    fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }

        for ((index, character) in text.withIndex()) {
            val isDecimalPoint =
                character == '.' &&
                    current.isNotEmpty() &&
                    current.last().isDigit() &&
                    text.getOrNull(index + 1)?.isDigit() == true

            if (isWordCharacter(character) || isDecimalPoint) {
                current.append(character)
            } else {
                flush()
            }
        }
        flush()
        return tokens
    }

    private fun isWordCharacter(character: Char): Boolean =
        character.isLetterOrDigit() ||
            character.category == CharCategory.NON_SPACING_MARK ||
            character.category == CharCategory.COMBINING_SPACING_MARK ||
            character == ZERO_WIDTH_NON_JOINER ||
            character == ZERO_WIDTH_JOINER

    /**
     * One spelling for a Romanized Bangla word that people type many ways.
     *
     * Two parts, on purpose. The table handles the shop words that come up
     * constantly and whose spellings do not follow from any rule — "ase"
     * and "achhe" are the same word, but no letter rule gets you from one
     * to the other. The folding below handles everything else, so a word
     * nobody listed still has a chance of matching.
     *
     * A word in Bangla script is returned untouched: these rules are about
     * Latin spellings of Bangla sounds and would only damage real Bangla.
     */
    fun foldRomanized(token: String): String {
        if (!token.any { it in 'a'..'z' }) return token
        ROMANIZED_SPELLINGS[token]?.let { return it }

        var folded = token
        for ((pattern, replacement) in ROMANIZED_FOLDINGS) {
            folded = folded.replace(pattern, replacement)
        }
        // "koootoo" and "koto" are one word typed with a slipped finger.
        folded = folded.replace(DOUBLED_LETTER, "$1")
        return ROMANIZED_SPELLINGS[folded] ?: folded
    }

    /**
     * Shop words whose spellings vary in ways no letter rule predicts. The
     * value is the spelling the intent rules are written against; which one
     * of a group is chosen does not matter, only that they all agree.
     */
    private val ROMANIZED_SPELLINGS =
        mapOf(
            // how much / how many
            "koto" to "koto",
            "kto" to "koto",
            "kotto" to "koto",
            "kt" to "koto",
            "kotoo" to "koto",
            "koyta" to "koyta",
            "koita" to "koyta",
            "kayta" to "koyta",
            "kyta" to "koyta",
            "kota" to "koyta",
            // is / are / remaining
            "ase" to "ache",
            "ache" to "ache",
            "achhe" to "ache",
            "roilo" to "ache",
            "baki" to "baki",
            "baaki" to "baki",
            "bki" to "baki",
            // sell / sold
            "sell" to "bikri",
            "sale" to "bikri",
            "bikri" to "bikri",
            "bikry" to "bikri",
            "becha" to "bikri",
            "hoise" to "hoyeche",
            "hoyeche" to "hoyeche",
            "hoichhe" to "hoyeche",
            "hoeche" to "hoyeche",
            // stock
            "stock" to "stock",
            "stok" to "stock",
            "istock" to "stock",
            // today / yesterday / month
            "ajke" to "aj",
            "aj" to "aj",
            "ajk" to "aj",
            "aajke" to "aj",
            "kalke" to "kal",
            "kal" to "kal",
            "gotokal" to "kal",
            "mas" to "mas",
            "mash" to "mas",
            "maas" to "mas",
            // owes / due
            "dena" to "dena",
            "denna" to "dena",
            "pawna" to "pawna",
            "paona" to "pawna",
            "meyad" to "meyad",
            "meyaad" to "meyad",
            // possessive and small words that only add noise
            "er" to "er",
            "r" to "er",
            "ar" to "er",
            "era" to "er",
        )

    /**
     * General Banglish spelling folds, applied in order. These are the
     * regularities: the same sound written with one letter or two.
     *
     * These run *before* the doubled-letter collapse, and that order is a
     * choice. "oo" is usually a real sound in Banglish (khoob → khub, dood
     * → dud), not a slipped finger, so it is read as a sound first. The
     * cost is that a genuine typo like "kotoo" would fold to "kotu", which
     * is why the very common words carry their own entries in the table
     * above rather than relying on these rules.
     */
    private val ROMANIZED_FOLDINGS =
        listOf(
            Regex("chh") to "ch",
            Regex("sh") to "s",
            Regex("ph") to "f",
            Regex("aa+") to "a",
            Regex("ee+") to "i",
            Regex("oo+") to "u",
            Regex("ii+") to "i",
            Regex("uu+") to "u",
            Regex("z") to "j",
            Regex("v") to "b",
            Regex("w") to "o",
            Regex("y$") to "i",
        )

    private val DOUBLED_LETTER = Regex("([a-z])\\1+")

    private const val ZERO_WIDTH_NON_JOINER = '‌'
    private const val ZERO_WIDTH_JOINER = '‍'

    /**
     * The token itself, plus the same token with a grammatical ending taken
     * off — "রহিমের" also offered as "রহিম", "mase" also as "mas".
     *
     * Bangla glues its endings straight onto the word, and people do the
     * same when they type Bangla in Latin letters. Without this, a shop's
     * own customer "রহিম" is never found in "রহিমের বাকি কত", and "এই মাসে"
     * is not recognised as a month. Both really happened.
     *
     * The stripped form is offered *as well as* the original, never instead
     * of it, so a word that merely looks like it has an ending — "ache" is
     * not "ach" plus an "e" — still matches itself first.
     */
    fun candidates(token: String): List<String> {
        val forms = mutableListOf(token)
        for (ending in ENDINGS) {
            if (token.length > ending.length + 1 && token.endsWith(ending)) {
                forms += token.dropLast(ending.length)
            }
        }
        return forms
    }

    /**
     * A Bangla word written out in Latin letters, the way someone would type
     * it on an English keyboard — "রহিম" as "rahim" and "rohim", "চিনি" as
     * "chini".
     *
     * Two spellings come back, not one, because Bangla does not write the
     * vowel that sits inside a bare consonant and people hear it either way:
     * the same name is typed "rahim" and "rohim", "karim" and "korim",
     * "laban" and "lobon". Indexing both costs nothing and catches both.
     *
     * This is rough on purpose. It exists so a customer saved in Bangla can
     * still be found by someone typing in Latin — the ordinary case in a
     * Bangla shop, and one that would otherwise fail outright. Where it gets
     * a name wrong, the shopkeeper's own alias still wins, because that is
     * indexed too.
     */
    fun transliterateFromBangla(text: String): List<String> = listOf('a', 'o').map { inherent -> transliterate(text, inherent) }.distinct()

    private fun transliterate(
        text: String,
        inherentVowel: Char,
    ): String {
        val out = StringBuilder()
        val characters = text.toCharArray()

        for ((index, character) in characters.withIndex()) {
            val consonant = BANGLA_CONSONANTS[character]
            if (consonant != null) {
                out.append(consonant)
                // A bare consonant carries a vowel nobody writes — unless a
                // vowel sign or a hasant follows, or it ends the word, where
                // it is not pronounced either.
                val next = characters.getOrNull(index + 1)
                val carriesItsOwnVowel = next == HASANT || (next != null && next in BANGLA_VOWEL_SIGNS)
                if (!carriesItsOwnVowel && index != characters.lastIndex) out.append(inherentVowel)
                continue
            }
            BANGLA_VOWEL_SIGNS[character]?.let {
                out.append(it)
                continue
            }
            BANGLA_VOWELS[character]?.let {
                out.append(it)
                continue
            }
            if (character == HASANT || character in BANGLA_MARKS) continue
            out.append(character)
        }
        return out.toString()
    }

    private const val HASANT = '্'

    /**
     * Marks that carry no sound of their own: chandrabindu, the nukta, and
     * the zero-width joiners. Dropped rather than transliterated. The nukta
     * is why ড় and ঢ় are not in the consonant table — each is two code
     * points, not one, and NFC leaves them that way, so they arrive here as
     * ড or ঢ followed by a mark.
     */
    private val BANGLA_MARKS = setOf('ঁ', '়', ZERO_WIDTH_NON_JOINER, ZERO_WIDTH_JOINER)

    private val BANGLA_CONSONANTS =
        mapOf(
            'ক' to "k",
            'খ' to "kh",
            'গ' to "g",
            'ঘ' to "gh",
            'ঙ' to "ng",
            'চ' to "ch",
            'ছ' to "chh",
            'জ' to "j",
            'ঝ' to "jh",
            'ঞ' to "n",
            'ট' to "t",
            'ঠ' to "th",
            'ড' to "d",
            'ঢ' to "dh",
            'ণ' to "n",
            'ত' to "t",
            'থ' to "th",
            'দ' to "d",
            'ধ' to "dh",
            'ন' to "n",
            'প' to "p",
            'ফ' to "f",
            'ব' to "b",
            'ভ' to "bh",
            'ম' to "m",
            'য' to "j",
            'র' to "r",
            'ল' to "l",
            'শ' to "s",
            'ষ' to "s",
            'স' to "s",
            'হ' to "h",
            'ৎ' to "t",
            'ং' to "ng",
            'ঃ' to "h",
        )

    private val BANGLA_VOWEL_SIGNS =
        mapOf(
            'া' to "a",
            'ি' to "i",
            'ী' to "i",
            'ু' to "u",
            'ূ' to "u",
            'ৃ' to "ri",
            'ে' to "e",
            'ৈ' to "oi",
            'ো' to "o",
            'ৌ' to "ou",
        )

    private val BANGLA_VOWELS =
        mapOf(
            'অ' to "o",
            'আ' to "a",
            'ই' to "i",
            'ঈ' to "i",
            'উ' to "u",
            'ঊ' to "u",
            'ঋ' to "ri",
            'এ' to "e",
            'ঐ' to "oi",
            'ও' to "o",
            'ঔ' to "ou",
        )

    /**
     * Longest first, so "রহিমের" gives up "ের" rather than just "র". These
     * are the endings that actually turn up on a shop's nouns: possessive,
     * objective, the classifiers, and the plural.
     */
    private val ENDINGS =
        listOf(
            "গুলোর",
            "গুলির",
            "গুলো",
            "গুলি",
            "দের",
            "টার",
            "টির",
            "এর",
            "ের",
            "কে",
            "টা",
            "টি",
            "য়",
            "ে",
            "র",
            "gulor",
            "gulo",
            "guli",
            "der",
            "tar",
            "tir",
            "er",
            "te",
            "ke",
            "ta",
            "ti",
            "e",
        )
}
