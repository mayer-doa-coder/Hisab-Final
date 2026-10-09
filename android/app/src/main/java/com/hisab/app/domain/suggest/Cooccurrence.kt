package com.hisab.app.domain.suggest

import com.hisab.app.domain.EntityId

// Which products are bought together (Step 91).
//
// Sales in, counts out. Nothing here touches the screen, the database or the
// network: `data/suggest/SuggestionRepository.kt` reads the rows and hands them
// to `computeCooccurrence`, which is what lets every rule below be tested on a
// laptop against a sale history written by hand.
//
// The counts are derived from the sale ledger every time and never stored or
// edited (PRD section 17.3, CLAUDE.md): there is no "suggestion table" that
// could drift from what was actually sold. Two sales that are 90 days apart and
// two that are a minute apart are counted alike here; ageing the evidence is a
// separate, optional question that the PRD lists as a variant to compare (RQ5)
// and that this step does not answer.
//
// Three rules decide what is counted:
//
//  1. A basket is one sale. Two products are "together" when they are lines of
//     the same sale, not when the same customer bought them on the same day.
//  2. A product counts once per basket. Selling five of it still makes it one
//     basket containing it, because the question is "how often do these two go
//     out together", not "how many units".
//  3. A sale that was undone is not a purchase, and neither is the reversal that
//     undid it. That is decided where the rows are read (the reversal is a second,
//     opposite sale, D033), so every row reaching this file is a real purchase.

/** One line of a sale, reduced to the pair the counting needs: which sale, which product. */
data class BasketLine(
    val saleId: EntityId,
    val productId: EntityId,
)

/**
 * How often products went out together over a stretch of sales.
 *
 * Built only by [computeCooccurrence]. All counts are whole numbers of sales; the
 * scores that are derived from them (confidence, lift) are worked out where they
 * are used and never stored, so the evidence behind a suggestion is always
 * visible as plain counts.
 */
class Cooccurrence internal constructor(
    /** Sales counted. */
    val basketCount: Int,
    private val basketsWithProduct: Map<EntityId, Int>,
    private val partners: Map<EntityId, Map<EntityId, Int>>,
    /** Sales left out of the pair counts for being too large; they still count everywhere else. */
    val oversizedBaskets: Int,
) {
    /** Distinct products that appear in at least one counted sale. */
    val productCount: Int get() = basketsWithProduct.size

    /** Distinct unordered pairs seen together at least once. */
    val pairCount: Int get() = partners.values.sumOf { it.size } / 2

    /** In how many sales [product] appears. Zero for a product never sold. */
    fun basketsWith(product: EntityId): Int = basketsWithProduct[product] ?: 0

    /** In how many sales [a] and [b] appear together. Symmetric; zero if they never did or if [a] is [b]. */
    fun together(
        a: EntityId,
        b: EntityId,
    ): Int = partners[a]?.get(b) ?: 0

    /** Every product that ever went out with [product], with how many sales they shared. */
    fun partnersOf(product: EntityId): Map<EntityId, Int> = partners[product] ?: emptyMap()

    companion object {
        /** Used when there are no sales at all. */
        val EMPTY = Cooccurrence(0, emptyMap(), emptyMap(), 0)
    }
}

/**
 * The most distinct products one sale may contribute pairs for.
 *
 * Pairs grow with the square of a basket: 30 products make 435 pairs, 200 make
 * 19,900. One unusually large sale (stock being written off through the till, a
 * whole shelf sold in a clearance) would otherwise add as many pairs as hundreds
 * of ordinary sales and dominate the counts, on a phone that has to hold them.
 * Such a sale still counts as a basket and still counts for each product in it;
 * it contributes no pairs, because a basket that large says nothing about which
 * two things belong together. It is reported as [Cooccurrence.oversizedBaskets].
 *
 * A round number, not a measured one: real shop sales are far smaller, and this
 * only has to be above any ordinary sale and below a pathological one.
 */
const val MAX_PRODUCTS_PER_BASKET_FOR_PAIRS = 30

/**
 * Counts, over [lines], how many sales each product is in and how many sales each
 * pair of products shares.
 *
 * The order of [lines] does not matter and the same product appearing twice in
 * one sale (it should not; a sale has one line per product) counts once. Product
 * ids are compared as they are: two ids are two products.
 */
fun computeCooccurrence(lines: List<BasketLine>): Cooccurrence {
    if (lines.isEmpty()) return Cooccurrence.EMPTY

    val baskets: Map<EntityId, Set<EntityId>> =
        lines
            .groupBy({ it.saleId }, { it.productId })
            .mapValues { (_, products) -> products.toSet() }

    val basketsWithProduct = HashMap<EntityId, Int>()
    val partners = HashMap<EntityId, HashMap<EntityId, Int>>()
    var oversized = 0

    for (products in baskets.values) {
        for (product in products) {
            basketsWithProduct.merge(product, 1, Int::plus)
        }
        if (products.size > MAX_PRODUCTS_PER_BASKET_FOR_PAIRS) {
            oversized += 1
            continue
        }
        // Each unordered pair once per basket, recorded in both directions so
        // that looking up a product's partners is one map read, not a scan.
        val ordered = products.toList()
        for (first in ordered.indices) {
            for (second in first + 1 until ordered.size) {
                val a = ordered[first]
                val b = ordered[second]
                partners.getOrPut(a) { HashMap() }.merge(b, 1, Int::plus)
                partners.getOrPut(b) { HashMap() }.merge(a, 1, Int::plus)
            }
        }
    }
    return Cooccurrence(baskets.size, basketsWithProduct, partners, oversized)
}
