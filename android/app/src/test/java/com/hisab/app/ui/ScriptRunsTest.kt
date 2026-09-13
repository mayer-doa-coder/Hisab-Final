package com.hisab.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-script font rule (D010) depends on cutting a string into runs
 * correctly, so the cutting is tested on its own, without Compose.
 */
class ScriptRunsTest {
    @Test
    fun anEmptyStringHasNoRuns() {
        assertEquals(emptyList<ScriptRun>(), scriptRuns(""))
    }

    @Test
    fun aPureBanglaNameIsOneBanglaRun() {
        assertEquals(listOf(ScriptRun("চিনি", bengali = true)), scriptRuns("চিনি"))
    }

    @Test
    fun aPureEnglishNameIsOneEnglishRun() {
        assertEquals(listOf(ScriptRun("Coke", bengali = false)), scriptRuns("Coke"))
    }

    @Test
    fun aMixedNameIsSplitAtTheScriptChange() {
        assertEquals(
            listOf(ScriptRun("চিনি ", bengali = true), ScriptRun("Sugar", bengali = false)),
            scriptRuns("চিনি Sugar"),
        )
    }

    @Test
    fun splitsBackAndForthAcrossScripts() {
        assertEquals(
            listOf(
                ScriptRun("Coke ", bengali = false),
                ScriptRun("বোতল ", bengali = true),
                ScriptRun("500ml", bengali = false),
            ),
            scriptRuns("Coke বোতল 500ml"),
        )
    }

    // Spaces, digits and punctuation stay with the run before them, so the
    // font never changes in the middle of a number.
    @Test
    fun asciiDigitsStayWithThePrecedingRun() {
        assertEquals(listOf(ScriptRun("চিনি 1 ", bengali = true)), scriptRuns("চিনি 1 "))
        assertEquals(
            listOf(ScriptRun("চিনি 1 ", bengali = true), ScriptRun("kg", bengali = false)),
            scriptRuns("চিনি 1 kg"),
        )
    }

    // A word is never cut in half: "1kg" would otherwise render its digits
    // in one font and its letters in another.
    @Test
    fun aWordWithDigitsAndLettersStaysInOneRun() {
        assertEquals(
            listOf(ScriptRun("চিনি ", bengali = true), ScriptRun("1kg", bengali = false)),
            scriptRuns("চিনি 1kg"),
        )
    }

    // Bangla digits belong to the Bengali block, so they take the Bangla font.
    @Test
    fun banglaDigitsCountAsBangla() {
        assertEquals(listOf(ScriptRun("১২.৫০", bengali = true)), scriptRuns("১২.৫০"))
    }

    @Test
    fun textWithNoLettersAtAllIsOneRun() {
        assertEquals(listOf(ScriptRun("12.50", bengali = false)), scriptRuns("12.50"))
    }
}
