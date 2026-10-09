package com.hisab.app.domain.suggest

import com.hisab.app.domain.EntityId

// Ranking what to suggest next to what is already in the cart (Step 92).
//
// Given the counts from `computeCooccurrence` and the products in the current
// sale, this says which other products are worth offering, most worthwhile first,
// and *why* — every suggestion carries the counts that produced it, so "bought
// together in 62% of the sales with Biryani" can be read straight off it (PRD
// section 17.4) and a wrong suggestion can be traced to the sales behind it.
//
// Two methods are here because the PRD asks for both to be compared (RQ5):
//
//   ASSOCIATION  support, confidence and lift. A product is suggested only when
//                it is bought with the cart product often enough (support count),
//                in a large enough share of that product's sales (confidence),
//                and more often than its own popularity would explain (lift).
//   FREQUENCY    the plain "bought together" count, with only the minimum count
//                and none of the rest. The baseline.
//
// The app uses ASSOCIATION. FREQUENCY exists so the comparison RQ5 asks for is
// one parameter away, not so that it can be switched on in a shop.
//
// The central rule, from PRD section 17.2: when there is not enough history, say
// so and suggest nothing. A suggestion made from two sales is a coincidence, and
// a shopkeeper who is offered coincidences stops reading the suggestions. Every
// threshold below exists to refuse that, and every refusal is typed
// ([ComplementResult.NotEnoughData]), not an empty list, so a caller can tell
// "not enough history yet" from "enough history and nothing stands out".

enum class SuggestionMethod {
    ASSOCIATION,
    FREQUENCY,
}

/**
 * How much evidence a suggestion needs.
 *
 * **Every default here is an assumption, not a finding.** They are round numbers
 * chosen so that a suggestion rests on a handful of real sales rather than one or
 * two, and nothing has been checked against a real shop. RQ5's held-out
 * evaluation (hide a slice of sales, see whether the suggestion would have
 * matched) is what should set them, and `docs/RESEARCH_PLAN.md` allows tuning
 * them only on sales before the held-out slice.
 */
data class SuggestionSettings(
    /** Sales in the window before the feature speaks at all. Below this, the whole shop is "not enough history yet". */
    val minBaskets: Int = DEFAULT_MIN_BASKETS,
    /** Sales a cart product must appear in before it can be a basis for a suggestion. */
    val minCartProductBaskets: Int = DEFAULT_MIN_CART_PRODUCT_BASKETS,
    /** Sales the cart product and the suggested one must share. */
    val minTogether: Int = DEFAULT_MIN_TOGETHER,
    /** Of the cart product's sales, the least share (whole percent) that must include the suggested one. */
    val minConfidencePercent: Int = DEFAULT_MIN_CONFIDENCE_PERCENT,
    /**
     * The least lift, in whole percent (120 = 1.2). Lift of 100 means the pair goes
     * out together exactly as often as chance would put them together, so a product
     * that is in nearly every sale (a carrier bag) has lift near 100 with everything
     * and is not a suggestion, however often it is bought.
     */
    val minLiftPercent: Int = DEFAULT_MIN_LIFT_PERCENT,
) {
    init {
        require(minBaskets >= 1) { "At least one sale is needed, got $minBaskets." }
        require(minCartProductBaskets >= 1) { "A product must be in at least one sale, got $minCartProductBaskets." }
        require(minTogether >= 1) { "A pair must share at least one sale, got $minTogether." }
        require(minConfidencePercent in 0..100) { "Confidence is a percentage, got $minConfidencePercent." }
        require(minLiftPercent >= 0) { "Lift cannot be negative, got $minLiftPercent." }
    }

    companion object {
        const val DEFAULT_MIN_BASKETS = 20
        const val DEFAULT_MIN_CART_PRODUCT_BASKETS = 5
        const val DEFAULT_MIN_TOGETHER = 3
        const val DEFAULT_MIN_CONFIDENCE_PERCENT = 20
        const val DEFAULT_MIN_LIFT_PERCENT = 120
    }
}

/**
 * One association rule, "sales with [given] often include [suggested]", with the
 * counts it was worked out from. Nothing here is stored or editable; it is read
 * off the sale history each time.
 */
data class ComplementRule(
    val given: EntityId,
    val suggested: EntityId,
    /** Sales containing both. */
    val together: Int,
    /** Sales containing [given]. */
    val givenBaskets: Int,
    /** Sales containing [suggested]. */
    val suggestedBaskets: Int,
    /** All sales counted. */
    val basketCount: Int,
) {
    init {
        require(givenBaskets > 0 && suggestedBaskets > 0 && basketCount > 0) { "A rule needs sales behind it." }
        require(together in 0..minOf(givenBaskets, suggestedBaskets)) {
            "Sales with both ($together) cannot exceed sales with either ($givenBaskets, $suggestedBaskets)."
        }
        require(givenBaskets <= basketCount && suggestedBaskets <= basketCount) { "A product cannot be in more sales than there are." }
    }

    /** Share of the [given] product's sales that include [suggested], 0 to 1. */
    val confidence: Double get() = together.toDouble() / givenBaskets

    /** The same share as a whole percent, rounded half up: what the shopkeeper-facing copy would say. */
    val confidencePercent: Int get() = ((together * 100L + givenBaskets / 2) / givenBaskets).toInt()

    /** How many times more often than chance the pair is bought together. 1.0 means no more than chance. */
    val lift: Double get() = together.toDouble() * basketCount / (givenBaskets.toDouble() * suggestedBaskets)

    /** Exact (integer) test of `lift >= minLiftPercent / 100`, so a threshold is never decided by rounding. */
    internal fun liftAtLeast(minLiftPercent: Int): Boolean =
        together.toLong() * basketCount * 100 >= minLiftPercent.toLong() * givenBaskets * suggestedBaskets

    /** Exact test of `confidence >= minConfidencePercent / 100`. */
    internal fun confidenceAtLeast(minConfidencePercent: Int): Boolean =
        together.toLong() * 100 >= minConfidencePercent.toLong() * givenBaskets
}

/** One product worth offering, its score, and every rule that supports it, strongest first. */
data class Suggestion(
    val productId: EntityId,
    /** What the ranking sorted by. Comparable between suggestions of one call, meaningless across calls. */
    val score: Double,
    val rules: List<ComplementRule>,
) {
    init {
        require(rules.isNotEmpty()) { "A suggestion with no evidence is a guess." }
    }

    /** The single rule that best explains this suggestion. */
    val strongest: ComplementRule get() = rules.first()
}

/** Why nothing was suggested for want of history. */
enum class NotEnoughDataReason {
    /** Fewer sales than [SuggestionSettings.minBaskets] in the window: the whole shop is too new. */
    SHOP_HISTORY,

    /** The shop has history, but none of the products in the cart has been sold often enough to base anything on. */
    CART_HISTORY,
}

sealed interface ComplementResult {
    /**
     * History was enough to judge. [suggestions] may still be empty: nothing stood out,
     * which is an answer and not a lack of one.
     */
    data class Ranked(
        val suggestions: List<Suggestion>,
    ) : ComplementResult

    /** Too little history to say anything. Suggest nothing; say why if asked. */
    data class NotEnoughData(
        val reason: NotEnoughDataReason,
        /** Sales available, to say "12 of the 20 needed" if a screen wants to. */
        val basketsAvailable: Int,
        val basketsNeeded: Int,
    ) : ComplementResult
}

/**
 * The products worth suggesting next to [productIds], best first, at most [topN].
 *
 * [productIds] is the whole cart, not the last item added: each cart product is a
 * basis, and a candidate supported by several of them scores higher, so the list
 * changes as items are added (PRD section 17.2).
 *
 * [eligible] is asked about every candidate before it can be ranked — a product
 * the shop no longer sells, or one the shopkeeper already dismissed. Filtering
 * before the top N (not after) is what keeps the list full when the best
 * candidate is not offerable.
 *
 * How the score is made, for ASSOCIATION: for each cart product that has enough
 * history, each product it was sold with becomes a candidate if the pair passes
 * the minimum count, confidence and lift; a candidate's score is the sum of its
 * confidences over the cart products that support it. Ties break by the
 * strongest lift, then by the total shared sales, then by product id, so the same
 * sales always give the same list in the same order.
 */
fun rankComplements(
    model: Cooccurrence,
    productIds: Collection<EntityId>,
    topN: Int,
    settings: SuggestionSettings = SuggestionSettings(),
    method: SuggestionMethod = SuggestionMethod.ASSOCIATION,
    eligible: (EntityId) -> Boolean = { true },
): ComplementResult {
    require(topN >= 1) { "Ask for at least one suggestion, got $topN." }

    val cart = productIds.toSet()
    if (cart.isEmpty()) return ComplementResult.Ranked(emptyList())

    if (model.basketCount < settings.minBaskets) {
        return ComplementResult.NotEnoughData(NotEnoughDataReason.SHOP_HISTORY, model.basketCount, settings.minBaskets)
    }

    // Sorted so the order the cart was built in can never change a result.
    val basis = cart.filter { model.basketsWith(it) >= settings.minCartProductBaskets }.sortedBy { it.value }
    if (basis.isEmpty()) {
        val best = cart.maxOf { model.basketsWith(it) }
        return ComplementResult.NotEnoughData(NotEnoughDataReason.CART_HISTORY, best, settings.minCartProductBaskets)
    }

    val rulesByCandidate = LinkedHashMap<EntityId, MutableList<ComplementRule>>()
    for (given in basis) {
        val givenBaskets = model.basketsWith(given)
        for ((candidate, together) in model.partnersOf(given)) {
            if (candidate in cart || !eligible(candidate)) continue
            val rule = ComplementRule(given, candidate, together, givenBaskets, model.basketsWith(candidate), model.basketCount)
            if (qualifies(rule, settings, method)) rulesByCandidate.getOrPut(candidate) { mutableListOf() }.add(rule)
        }
    }

    val ranked =
        rulesByCandidate
            .map { (candidate, rules) ->
                val ordered = rules.sortedWith(compareByDescending<ComplementRule> { it.confidence }.thenBy { it.given.value })
                Suggestion(candidate, score(ordered, method), ordered)
            }.sortedWith(
                compareByDescending<Suggestion> { it.score }
                    .thenByDescending { suggestion -> suggestion.rules.maxOf { it.lift } }
                    .thenByDescending { suggestion -> suggestion.rules.sumOf { it.together } }
                    .thenBy { it.productId.value },
            ).take(topN)

    return ComplementResult.Ranked(ranked)
}

private fun qualifies(
    rule: ComplementRule,
    settings: SuggestionSettings,
    method: SuggestionMethod,
): Boolean =
    when (method) {
        SuggestionMethod.FREQUENCY -> {
            rule.together >= settings.minTogether
        }

        SuggestionMethod.ASSOCIATION -> {
            rule.together >= settings.minTogether &&
                rule.confidenceAtLeast(settings.minConfidencePercent) &&
                rule.liftAtLeast(settings.minLiftPercent)
        }
    }

private fun score(
    rules: List<ComplementRule>,
    method: SuggestionMethod,
): Double =
    when (method) {
        SuggestionMethod.FREQUENCY -> rules.sumOf { it.together }.toDouble()
        SuggestionMethod.ASSOCIATION -> rules.sumOf { it.confidence }
    }
