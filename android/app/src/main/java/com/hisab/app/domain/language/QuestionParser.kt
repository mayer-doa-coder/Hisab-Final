package com.hisab.app.domain.language

import java.time.LocalDate

/**
 * The fixed intent list (Steps 78–82). `GET_LOW_STOCK`,
 * `GET_PREDICTED_STOCKOUT` and `GET_REORDER` are deliberately absent: they
 * need forecasting and the reorder engine, which do not exist until M6, and
 * answering them now would mean inventing numbers.
 */
enum class Intent {
    GET_STOCK,
    GET_CUSTOMER_BAKI,
    GET_OVERDUE,
    GET_TODAY_SALES,
    GET_PERIOD_SALES,

    /** Nothing this version can answer. Better said plainly than guessed at. */
    UNKNOWN,
}

/** A stretch of time a question asks about, resolved against the shop's own day. */
enum class Period {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    ;

    /** Inclusive start and exclusive end, so days never overlap or leave a gap. */
    fun range(today: LocalDate): ClosedRange<LocalDate> =
        when (this) {
            TODAY -> {
                today..today
            }

            YESTERDAY -> {
                today.minusDays(1)..today.minusDays(1)
            }

            THIS_WEEK -> {
                today.minusDays((today.dayOfWeek.value % 7).toLong())..today
            }

            THIS_MONTH -> {
                today.withDayOfMonth(1)..today
            }

            LAST_MONTH -> {
                val firstOfLast = today.withDayOfMonth(1).minusMonths(1)
                firstOfLast..firstOfLast.withDayOfMonth(firstOfLast.lengthOfMonth())
            }
        }
}

/**
 * What one question turned out to be asking. [product] and [customer] are
 * whichever of the shop's own things the sentence named (Step 74).
 */
data class ParsedQuestion(
    val intent: Intent,
    val product: AliasMatch? = null,
    val customer: AliasMatch? = null,
    val period: Period? = null,
)

/**
 * Turns a normalized question into an intent and its entities (Steps 78–82).
 *
 * Rules, not a model: `CLAUDE.md` rules out needing one, and a shopkeeper's
 * phone should answer without a network. The words below are matched against
 * the *folded* tokens from [TextNormalizer], so a rule written for "bikri"
 * also catches "sell", "sale" and "becha" without listing them again here.
 *
 * Bangla words are matched by `contains` rather than equality, because
 * Bangla glues its endings on: "মাসে" is "মাস" with a case ending, "আজকে" is
 * "আজ" with one, and requiring an exact token would miss both.
 */
object QuestionParser {
    fun parse(
        text: String,
        aliases: AliasMatcher,
    ): ParsedQuestion {
        val stages = TextNormalizer.normalize(text)

        // Keywords are looked for in both the folded and the unfolded words.
        // The Banglish folds are right for Bangla but wrong for the English
        // words people mix in: "v" becomes "b" because "vai" is "bhai", which
        // also turns "overdue" into "oberdue" and loses the keyword. Keeping
        // both spellings costs nothing and stops one rule breaking the other.
        // Worked out once per question, not once per keyword set. Doing it
        // per check meant every intent re-derived the same endings for every
        // word, and the worst question took 24 ms on the reference phone;
        // once, into a set, it is a handful of lookups instead.
        val tokens =
            (stages.romanized + stages.tokens)
                .flatMap(TextNormalizer::candidates)
                .toSet()
        val matches = aliases.resolve(stages.result)
        val product = matches.firstOrNull { it.kind == AliasEntry.Kind.PRODUCT }
        val customer = matches.firstOrNull { it.kind == AliasEntry.Kind.CUSTOMER }
        val period = periodIn(tokens)

        val intent =
            when {
                // Overdue before customer baki: "কার বাকির মেয়াদ পেরিয়েছে" is
                // about everyone at once, not about one named person, and it
                // says "baki" too — so asking "is anyone named?" is what
                // tells the two apart.
                isOverdue(tokens) && customer == null -> Intent.GET_OVERDUE

                hasAny(tokens, BAKI_WORDS) && customer != null -> Intent.GET_CUSTOMER_BAKI

                hasAny(tokens, SALES_WORDS) && period == Period.TODAY -> Intent.GET_TODAY_SALES

                hasAny(tokens, SALES_WORDS) && period != null -> Intent.GET_PERIOD_SALES

                // "ajke koto sell hoise" with no period word still means today.
                hasAny(tokens, SALES_WORDS) -> Intent.GET_TODAY_SALES

                isStock(tokens, product) -> Intent.GET_STOCK

                // A bare product name with a "how much" word and nothing else
                // is about stock: "chini koto".
                product != null && hasAny(tokens, AMOUNT_WORDS) -> Intent.GET_STOCK

                else -> Intent.UNKNOWN
            }

        return ParsedQuestion(
            intent = intent,
            product = if (intent == Intent.GET_STOCK) product else null,
            customer = if (intent == Intent.GET_CUSTOMER_BAKI) customer else null,
            period = if (intent == Intent.GET_PERIOD_SALES) period else null,
        )
    }

    private fun isOverdue(tokens: Set<String>): Boolean {
        val saysOverdue = hasAny(tokens, OVERDUE_WORDS)
        val asksWho = hasAny(tokens, WHO_WORDS)
        // "meyad" or "overdue" is enough on its own; "who" only counts when
        // the question is also about baki, or "kar baki koto" would be swept
        // in as an overdue question.
        return saysOverdue || (asksWho && hasAny(tokens, BAKI_WORDS))
    }

    private fun isStock(
        tokens: Set<String>,
        product: AliasMatch?,
    ): Boolean = hasAny(tokens, STOCK_WORDS) && (product != null || hasAny(tokens, AMOUNT_WORDS))

    private fun periodIn(tokens: Set<String>): Period? =
        when {
            hasAny(tokens, LAST_MONTH_WORDS) && hasAny(tokens, MONTH_WORDS) -> Period.LAST_MONTH
            hasAny(tokens, MONTH_WORDS) -> Period.THIS_MONTH
            hasAny(tokens, WEEK_WORDS) -> Period.THIS_WEEK
            hasAny(tokens, YESTERDAY_WORDS) -> Period.YESTERDAY
            hasAny(tokens, TODAY_WORDS) -> Period.TODAY
            else -> null
        }

    /**
     * [tokens] is every form the question's words can take — folded and
     * unfolded, with and without a grammatical ending — worked out once in
     * [parse]. So "মাসে" is in there as "মাস" too, and "mase" as "mas".
     *
     * Whole forms only. Matching anywhere inside a word would let "কে" match
     * "কেমন" and sweep unrelated questions into an intent.
     */
    private fun hasAny(
        tokens: Set<String>,
        words: Set<String>,
    ): Boolean = words.any { it in tokens }

    // The Latin entries here are the *folded* spellings TextNormalizer
    // produces, not every way a person might type them.
    private val STOCK_WORDS = setOf("stock", "স্টক", "মজুদ", "ache", "আছে")
    private val AMOUNT_WORDS = setOf("koto", "koyta", "কত", "কয়টা", "কয়টি")
    private val BAKI_WORDS = setOf("baki", "বাকি", "pawna", "পাওনা", "dena", "দেনা", "taka", "টাকা")
    private val OVERDUE_WORDS = setOf("meyad", "মেয়াদ", "overdue", "due", "সময়", "পেরিয়েছে", "পেরিয়ে")
    private val WHO_WORDS = setOf("kar", "kader", "ke", "কার", "কাদের", "কে", "কোন")

    // "aslo" is the folded form of "ashlo" — "how much money came in today"
    // is a sales question with no word for selling anywhere in it.
    private val SALES_WORDS = setOf("bikri", "বিক্রি", "বেচা", "আয়", "sell", "sale", "aslo", "এসেছে", "ঢুকেছে")
    private val TODAY_WORDS = setOf("aj", "আজ", "today")
    private val YESTERDAY_WORDS = setOf("kal", "গতকাল", "yesterday")
    private val WEEK_WORDS = setOf("soptah", "সপ্তাহ", "week")
    private val MONTH_WORDS = setOf("mas", "মাস", "month")
    private val LAST_MONTH_WORDS = setOf("goto", "গত", "last")
}
