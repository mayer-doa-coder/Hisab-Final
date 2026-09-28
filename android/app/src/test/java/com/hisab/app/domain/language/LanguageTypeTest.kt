package com.hisab.app.domain.language

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fixed rule the Research Data Plan sets for deciding a sentence's
 * language, and the answer language that follows from it (Step 78: "in the
 * typed language").
 */
class LanguageTypeTest {
    @Test
    fun `bengali letters only is bangla`() {
        assertEquals(LanguageType.BANGLA, LanguageType.of("চিনি কত আছে"))
    }

    @Test
    fun `latin letters only is romanized`() {
        assertEquals(LanguageType.ROMANIZED, LanguageType.of("chini koto ache"))
    }

    @Test
    fun `both scripts is mixed`() {
        assertEquals(LanguageType.MIXED, LanguageType.of("চিনি stock কত"))
    }

    @Test
    fun `digits decide nothing either way`() {
        // Bangla digits in a Latin sentence must not make it bangla.
        assertEquals(LanguageType.ROMANIZED, LanguageType.of("coke 500ml stock ৩৫০"))
        // Latin digits in a Bangla sentence must not make it mixed.
        assertEquals(LanguageType.BANGLA, LanguageType.of("চিনি 350"))
    }

    @Test
    fun `the answer follows the question, not the app's own language`() {
        assertEquals(AnswerLanguage.BANGLA, AnswerLanguage.forQuestion("চিনি কত আছে"))
        assertEquals(AnswerLanguage.ENGLISH, AnswerLanguage.forQuestion("chini koto ache"))
    }

    @Test
    fun `a mixed question is answered in bangla, the app's default`() {
        assertEquals(AnswerLanguage.BANGLA, AnswerLanguage.forQuestion("চিনি stock কত"))
    }
}
