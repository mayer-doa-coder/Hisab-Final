package com.hisab.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle

/**
 * Text the user typed — a product name, an alias — can mix scripts: "চিনি
 * 1kg", "Coke বোতল". The rule is one font per script, never one font for the
 * whole string (D010, docs/LOCALIZATION.md), so the string is cut into runs
 * and each run gets its own font.
 *
 * Splitting happens between words, never inside one, so "500ml" keeps a
 * single font. A word with no letters — a gap, or a number on its own —
 * follows the word before it. Bangla digits ০–৯ are part of the Bengali
 * block, so they count as Bangla by themselves.
 */
private fun isBengali(character: Char): Boolean = character in 'ঀ'..'৿'

private fun isScriptCarrying(character: Char): Boolean = isBengali(character) || character.isLetter()

/** One stretch of text in a single script. */
data class ScriptRun(
    val text: String,
    val bengali: Boolean,
)

private val WORDS_AND_GAPS = Regex("""\S+|\s+""")

/**
 * Cuts a string into runs, each in one script. Plain Kotlin with no Compose
 * or Android in it, so the splitting itself is unit-tested.
 *
 * A whole word is never split: it takes the script of its first letter, so
 * "500ml" stays in one font instead of breaking between the digits and the
 * letters. A word with no letters at all — a gap, or a number on its own —
 * follows the word before it, and the very first one follows the word after
 * it.
 */
fun scriptRuns(text: String): List<ScriptRun> {
    if (text.isEmpty()) return emptyList()

    val words = WORDS_AND_GAPS.findAll(text).map { it.value }.toList()
    val scripts = words.map { word -> word.firstOrNull(::isScriptCarrying)?.let(::isBengali) }

    var carried = scripts.firstOrNull { it != null } ?: false
    val resolved =
        scripts.map { script ->
            if (script != null) carried = script
            carried
        }

    val runs = mutableListOf<ScriptRun>()
    words.forEachIndexed { index, word ->
        val bengali = resolved[index]
        val previous = runs.lastOrNull()
        if (previous != null && previous.bengali == bengali) {
            runs[runs.lastIndex] = previous.copy(text = previous.text + word)
        } else {
            runs += ScriptRun(word, bengali)
        }
    }
    return runs
}

fun scriptAwareText(
    text: String,
    banglaFont: FontFamily = NotoSansBengali,
    englishFont: FontFamily = Merriweather,
): AnnotatedString =
    buildAnnotatedString {
        for (run in scriptRuns(text)) {
            withStyle(SpanStyle(fontFamily = if (run.bengali) banglaFont else englishFont)) {
                append(run.text)
            }
        }
    }

/**
 * The same per-script rule inside a text field, while the user types. The
 * text itself is unchanged — only the fonts are — so positions map straight
 * through and the cursor stays where it belongs.
 */
class ScriptAwareVisualTransformation(
    private val banglaFont: FontFamily = NotoSansBengali,
    private val englishFont: FontFamily = Merriweather,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(
            scriptAwareText(text.text, banglaFont, englishFont),
            OffsetMapping.Identity,
        )
}
