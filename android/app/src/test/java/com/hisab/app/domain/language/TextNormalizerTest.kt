package com.hisab.app.domain.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 73: each stage of the pipeline, checked on its own. */
class TextNormalizerTest {
    @Test
    fun `bangla digits become the same number as latin ones`() {
        val stages = TextNormalizer.normalize("চিনি ৩৫০")
        assertEquals("চিনি 350", stages.digitsNormalized)
        assertEquals(listOf("চিনি", "350"), stages.tokens)
    }

    @Test
    fun `case is folded without a locale turning an I into something else`() {
        assertEquals("coke stock koto", TextNormalizer.normalize("COKE STOCK KOTO").lowercased)
    }

    @Test
    fun `a decomposed bangla word and a composed one come out identical`() {
        // The same "কি", typed as one code point and as two.
        val composed = TextNormalizer.normalize("কি").result
        val decomposed = TextNormalizer.normalize("কি").result
        assertEquals(composed, decomposed)
    }

    @Test
    fun `punctuation splits words but a decimal point does not split a number`() {
        assertEquals(listOf("rahim", "er", "baki"), TextNormalizer.tokenize("rahim-er baki?"))
        assertEquals(listOf("12.50", "taka"), TextNormalizer.tokenize("12.50 taka"))
        assertEquals(listOf("চিনি", "koto"), TextNormalizer.tokenize("চিনি koto।"))
    }

    @Test
    fun `the many ways people spell the same shop word all land on one spelling`() {
        val howMuch = listOf("koto", "kto", "kotto").map(TextNormalizer::foldRomanized)
        assertEquals(listOf("koto", "koto", "koto"), howMuch)

        val howMany = listOf("koyta", "koita", "kyta").map(TextNormalizer::foldRomanized)
        assertEquals(listOf("koyta", "koyta", "koyta"), howMany)

        val remaining = listOf("ase", "ache", "achhe").map(TextNormalizer::foldRomanized)
        assertEquals(listOf("ache", "ache", "ache"), remaining)
    }

    @Test
    fun `a word nobody listed is still folded by the general rules`() {
        // Not in the table: the letter rules have to carry it.
        assertEquals(TextNormalizer.foldRomanized("khaata"), TextNormalizer.foldRomanized("khata"))
        assertEquals(TextNormalizer.foldRomanized("zinis"), TextNormalizer.foldRomanized("jinis"))
    }

    @Test
    fun `a slipped finger on one key does not change the word`() {
        // A doubled consonant is always a slip, so the collapse handles it.
        assertEquals(TextNormalizer.foldRomanized("baki"), TextNormalizer.foldRomanized("bakki"))
        // A doubled vowel may be a real Banglish sound, so the common words
        // that could be mistaken for one are listed by hand instead.
        assertEquals("koto", TextNormalizer.foldRomanized("kotoo"))
    }

    @Test
    fun `a doubled vowel that is a real banglish sound is read as that sound`() {
        // khoob → khub, not khob: "oo" is উ here, not a typo.
        assertEquals("khub", TextNormalizer.foldRomanized("khoob"))
    }

    @Test
    fun `a bangla word keeps its vowel signs instead of being torn apart`() {
        // চিনি is চ + ি + ন + ি. The vowel signs are combining marks, which
        // are not letters — treating them as punctuation used to drop them.
        assertEquals(listOf("চিনি"), TextNormalizer.tokenize("চিনি"))
        assertEquals(listOf("রহিমের"), TextNormalizer.tokenize("রহিমের"))
        // A conjunct built with the hasant stays one word too.
        assertEquals(listOf("স্টক"), TextNormalizer.tokenize("স্টক"))
    }

    @Test
    fun `bangla script is never touched by the romanized folding`() {
        val bangla = "রহিমের"
        assertEquals(bangla, TextNormalizer.foldRomanized(bangla))
    }

    // Step 73's own check: the plan's example sentences, every stage visible.
    @Test
    fun `the plans own examples come through every stage`() {
        val examples =
            listOf(
                "rahim er baki koto",
                "রহিমের baki কত",
                "coke koyta ase",
                "চিনি stock কত",
                "ajke koto sell hoise",
            )

        for (example in examples) {
            val stages = TextNormalizer.normalize(example)
            assertTrue("$example produced no tokens", stages.tokens.isNotEmpty())
            assertEquals(
                "$example: folding must not add or drop words",
                stages.tokens.size,
                stages.romanized.size,
            )
            assertEquals(
                "$example: nothing should still be in upper case",
                stages.lowercased,
                stages.lowercased.lowercase(),
            )
        }

        assertEquals(listOf("rahim", "er", "baki", "koto"), TextNormalizer.normalize("rahim er baki koto").result)
        assertEquals(listOf("coke", "koyta", "ache"), TextNormalizer.normalize("coke koyta ase").result)
        assertEquals(listOf("চিনি", "stock", "কত"), TextNormalizer.normalize("চিনি stock কত").result)
        assertEquals(listOf("aj", "koto", "bikri", "hoyeche"), TextNormalizer.normalize("ajke koto sell hoise").result)
    }
}
