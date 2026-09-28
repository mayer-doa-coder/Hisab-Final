package com.hisab.app.domain.language

import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity

/**
 * Which of the three the sentence is written in, by the fixed rule the
 * Research Data Plan sets: Bengali letters only is [BANGLA], Latin letters
 * only is [ROMANIZED], both is [MIXED]. Digits count for neither, so "৩৫০"
 * and "350" never decide it.
 *
 * A rule and not a judgement, so the dataset and the app agree about any
 * given sentence without anyone having to arbitrate.
 */
enum class LanguageType {
    BANGLA,
    ROMANIZED,
    MIXED,
    ;

    companion object {
        private val BENGALI_LETTER = Regex("[\\u0980-\\u09FF&&[^\\u09E6-\\u09EF]]")
        private val LATIN_LETTER = Regex("[A-Za-z]")

        fun of(text: String): LanguageType {
            val bengali = BENGALI_LETTER.containsMatchIn(text)
            val latin = LATIN_LETTER.containsMatchIn(text)
            return when {
                bengali && latin -> MIXED
                bengali -> BANGLA
                else -> ROMANIZED
            }
        }
    }
}

/**
 * Which language to answer in (Step 78: "in the typed language").
 *
 * This follows the question, not the app's own language setting: a
 * shopkeeper who types in Bangla gets Bangla back even if the screens are in
 * English, because the answer is a reply to them rather than part of the
 * app's furniture. Mixed counts as Bangla — the sentence has Bangla in it,
 * and Bangla is this app's default (CLAUDE.md).
 */
enum class AnswerLanguage {
    BANGLA,
    ENGLISH,
    ;

    companion object {
        fun forQuestion(text: String): AnswerLanguage =
            when (LanguageType.of(text)) {
                LanguageType.ROMANIZED -> ENGLISH
                LanguageType.BANGLA, LanguageType.MIXED -> BANGLA
            }
    }
}

/** One customer's name and what they owe, for the overdue list. */
data class OverdueCustomer(
    val name: String,
    val amount: Money,
)

/**
 * The facts of an answer, with no words in them.
 *
 * Kept separate from the text on purpose: the numbers can then be tested
 * without a device and without a language, and the same answer can be said
 * in Bangla or English without the two ever disagreeing about the figure.
 */
sealed interface AskAnswer {
    data class Stock(
        val productName: String,
        val quantity: Quantity,
        val unit: String,
    ) : AskAnswer

    data class CustomerBaki(
        val customerName: String,
        val balance: Money,
    ) : AskAnswer

    data class Overdue(
        val customers: List<OverdueCustomer>,
    ) : AskAnswer {
        val total: Money get() = Money(customers.sumOf { it.amount.minorUnits })
    }

    data class TodaySales(
        val total: Money,
    ) : AskAnswer

    data class PeriodSales(
        val period: Period,
        val total: Money,
    ) : AskAnswer

    /** The question named something the shop does not have. */
    data class UnknownProduct(
        val asked: String,
    ) : AskAnswer

    data class UnknownCustomer(
        val asked: String,
    ) : AskAnswer

    /** Understood as nothing this version can answer. Said plainly, not guessed at. */
    data object NotUnderstood : AskAnswer
}
