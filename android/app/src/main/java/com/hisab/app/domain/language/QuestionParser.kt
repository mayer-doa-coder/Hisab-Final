package com.hisab.app.domain.language

import java.time.LocalDate

/**
 * The fixed intent list: Steps 78–82, and the three that needed forecasting and
 * the reorder engine, added in Step 90.
 *
 * `GET_LOW_STOCK`, `GET_PREDICTED_STOCKOUT` and `GET_REORDER` answer from a
 * forecast, so they can answer "not enough data yet" where the first five
 * cannot: a stock level is a fact, a prediction needs history.
 */
enum class Intent {
    GET_STOCK,
    GET_CUSTOMER_BAKI,
    GET_OVERDUE,
    GET_TODAY_SALES,
    GET_PERIOD_SALES,

    /** Which products will not last until a new order could arrive. No product named. */
    GET_LOW_STOCK,

    /** When one named product runs out. */
    GET_PREDICTED_STOCKOUT,

    /** What to buy: for one named product, or for the whole shop if none is named. */
    GET_REORDER,

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

                // "What to buy" before everything stock-shaped: "coke koyta kinbo"
                // has a product and a "how many" word in it, and would otherwise
                // be read as a plain stock question.
                hasAny(tokens, BUY_WORDS) || isOrderingStock(tokens) -> Intent.GET_REORDER

                // "When will it run out" needs a product; without one the same
                // words ("kon kon jinis sesh hoye gese") ask which are running low.
                product != null && isStockout(tokens) -> Intent.GET_PREDICTED_STOCKOUT

                product == null && isLowStock(tokens) -> Intent.GET_LOW_STOCK

                isStock(tokens, product) -> Intent.GET_STOCK

                // A bare product name with a "how much" word and nothing else
                // is about stock: "chini koto".
                product != null && hasAny(tokens, AMOUNT_WORDS) -> Intent.GET_STOCK

                else -> Intent.UNKNOWN
            }

        return ParsedQuestion(
            intent = intent,
            product = if (intent in PRODUCT_INTENTS) product else null,
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

    /**
     * "order" on its own is not buying. A shopkeeper says it about customers too —
     * "ajke koto order asche" asks how many orders came in — and answering that with
     * a list of what to buy would be a reply to a different question. It counts only
     * together with something the shopkeeper does or gives: "order korbo", "order
     * dite hobe", "অর্ডার করব".
     */
    private fun isOrderingStock(tokens: Set<String>): Boolean = hasAny(tokens, ORDER_WORDS) && hasAny(tokens, DOING_WORDS)

    /**
     * "chini kobe sesh hobe", "coke koto din cholbe": a time word together with a
     * running-out or lasting word. Neither alone is enough — "kobe" is also
     * "when" in a question about anything, and "sesh" is also how a due date
     * ends — and a product has already been named by the caller.
     *
     * A product with a *low* word and no time word ("chini kom ache") is read the
     * same way: asking whether it is low is asking how long it will last.
     */
    private fun isStockout(tokens: Set<String>): Boolean {
        val saysRunsOut = hasAny(tokens, RUNOUT_WORDS) || hasAny(tokens, LAST_WORDS)
        val asksWhen = hasAny(tokens, WHEN_WORDS) || (hasAny(tokens, AMOUNT_WORDS) && hasAny(tokens, DAY_WORDS))
        return (saysRunsOut && asksWhen) || (hasAny(tokens, LOW_WORDS) && !hasAny(tokens, PRICE_WORDS))
    }

    /**
     * "ki ki kom ache", "kon kon jinis sesh hoye asche": running low, asked of the
     * whole shop. A "when" word rules it out: "pepsi kobe sesh hobe" is about one
     * thing the shop does not stock, and answering it with a list of other things
     * running low would be a reply to a different question.
     */
    private fun isLowStock(tokens: Set<String>): Boolean =
        (hasAny(tokens, LOW_WORDS) || hasAny(tokens, RUNOUT_WORDS)) &&
            !hasAny(tokens, WHEN_WORDS) &&
            !hasAny(tokens, PRICE_WORDS)

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

    // Step 90. Spellings are listed folded and unfolded where they differ, because
    // the question is searched in both (see `parse`): "sesh" folds to "ses", and
    // either may be what a keyword needs to meet.
    private val BUY_WORDS =
        setOf(
            "kinbo",
            "kinb",
            "kinte",
            "anbo",
            "anb",
            "ante",
            "tulbo",
            "tulte",
            "reorder",
            "buy",
            "purchase",
            "কিনব",
            "কিনবো",
            "কিনতে",
            "কিনবে",
            "কেনার",
            "কিনি",
            "আনব",
            "আনবো",
            "আনতে",
            "তুলব",
            "তুলবো",
            "তুলতে",
        )
    private val LOW_WORDS =
        setOf(
            "kom",
            "kome",
            "low",
            "furiye",
            "furiyeche",
            "furacche",
            "furaye",
            "ghatti",
            "কম",
            "কমে",
            "ফুরিয়ে",
            "ফুরোচ্ছে",
            "ফুরাচ্ছে",
            "ঘাটতি",
        )
    private val RUNOUT_WORDS =
        setOf(
            "sesh",
            "ses",
            "shesh",
            "furabe",
            "furab",
            "furaibo",
            "finish",
            "finished",
            "শেষ",
            "ফুরাবে",
            "ফুরোবে",
            "ফুরাব",
            "ফুরিয়ে",
        )

    // "order" counts as buying only with one of these; "dam" and friends rule "kom" out as
    // being about stock ("kom dame chini" is sugar at a lower price).
    private val ORDER_WORDS = setOf("order", "অর্ডার")
    private val DOING_WORDS =
        setOf("korbo", "korb", "korte", "dibo", "dib", "dite", "debo", "দিব", "দিবো", "দেব", "দেবো", "দিতে", "করব", "করবো", "করতে")
    private val PRICE_WORDS = setOf("dam", "daam", "dor", "price", "rate", "দাম", "দর")
    private val LAST_WORDS = setOf("cholbe", "cholb", "thakbe", "thakb", "চলবে", "থাকবে")
    private val WHEN_WORDS = setOf("kobe", "kob", "kokhon", "কবে", "কখন", "kotodin", "কতদিন")
    private val DAY_WORDS = setOf("din", "dine", "দিন", "দিনে")

    private val PRODUCT_INTENTS = setOf(Intent.GET_STOCK, Intent.GET_PREDICTED_STOCKOUT, Intent.GET_REORDER)
}
