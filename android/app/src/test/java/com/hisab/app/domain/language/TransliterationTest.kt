package com.hisab.app.domain.language

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writing a Bangla name out in Latin letters, so a customer saved as "রহিম"
 * is found by someone typing "rahim". Without this, the ordinary way to ask
 * about a customer in a Bangla shop — Bangla name, English keyboard — found
 * nobody at all.
 */
class TransliterationTest {
    private fun forms(bangla: String) = TextNormalizer.transliterateFromBangla(bangla)

    @Test
    fun `a name comes out the way people actually type it`() {
        // Both spellings, because the vowel inside a bare consonant is not
        // written in Bangla and is heard both ways.
        assertTrue("রহিম -> ${forms("রহিম")}", forms("রহিম").containsAll(listOf("rahim", "rohim")))
        assertTrue("করিম -> ${forms("করিম")}", forms("করিম").containsAll(listOf("karim", "korim")))
    }

    @Test
    fun `a product name comes out readable too`() {
        assertTrue("চিনি -> ${forms("চিনি")}", forms("চিনি").contains("chini"))
        assertTrue("চাল -> ${forms("চাল")}", forms("চাল").contains("chal"))
        assertTrue("তেল -> ${forms("তেল")}", forms("তেল").contains("tel"))
        assertTrue("ডাল -> ${forms("ডাল")}", forms("ডাল").contains("dal"))
    }

    @Test
    fun `an explicit vowel sign is used instead of the unwritten one`() {
        // চাল is cha + l, never chaal or chala.
        assertTrue(forms("চাল").all { it == "chal" })
    }

    @Test
    fun `a word already in latin letters is left alone`() {
        assertTrue(forms("coke").all { it == "coke" })
    }

    // Step 79's check, at the matcher: a Bangla customer, a Latin question.
    @Test
    fun `a bangla customer is found by a latin question`() {
        val matcher =
            AliasMatcher(listOf(AliasEntry("c-rahim", AliasEntry.Kind.CUSTOMER, "রহিম")))

        assertTrue(matcher.resolveText("rahim er baki koto").isNotEmpty())
        assertTrue(matcher.resolveText("rohim er baki koto").isNotEmpty())
        assertTrue(matcher.resolveText("রহিমের বাকি কত").isNotEmpty())
    }
}
