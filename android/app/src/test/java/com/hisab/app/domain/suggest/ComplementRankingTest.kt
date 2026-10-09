package com.hisab.app.domain.suggest

import com.hisab.app.domain.EntityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 92's check (the known pair from Step 91 ranks near the top) and Step 94's
 * three edge cases (always together should suggest; never together should not;
 * a brand-new product should show nothing, not a guess).
 *
 * Every history below is a few groups of sales whose counts, confidences and lifts
 * are worked out in the comment beside it, so a changed threshold fails on a number.
 */
class ComplementRankingTest {
    private fun id(name: String) = EntityId(name)

    /** [times] separate sales that each hold exactly [products]. */
    private fun group(
        times: Int,
        prefix: String,
        vararg products: String,
    ): List<BasketLine> = (1..times).flatMap { n -> products.map { BasketLine(id("$prefix-$n"), id(it)) } }

    private fun model(vararg groups: List<BasketLine>) = computeCooccurrence(groups.toList().flatten())

    /**
     * 60 sales.
     *   12 x {biryani, coke}    3 x {biryani}    8 x {coke}    5 x {biryani, salad}
     *   20 x {tea, biscuit}     12 x {rice}
     * biryani 20 sales, coke 20, salad 5, tea 20, biscuit 20, rice 12.
     *   biryani -> coke:  together 12, confidence 60%, lift 12 x 60 / (20 x 20) = 1.8
     *   biryani -> salad: together  5, confidence 25%, lift  5 x 60 / (20 x  5) = 3.0
     */
    private val shopGroups =
        listOf(
            group(12, "bc", "biryani", "coke"),
            group(3, "b", "biryani"),
            group(8, "c", "coke"),
            group(5, "bs", "biryani", "salad"),
            group(20, "tb", "tea", "biscuit"),
            group(12, "r", "rice"),
        )

    private val shop = computeCooccurrence(shopGroups.flatten())

    private fun ranked(result: ComplementResult): List<Suggestion> = (result as ComplementResult.Ranked).suggestions

    private fun ids(suggestions: List<Suggestion>) = suggestions.map { it.productId.value }

    // ---- Step 92 ----------------------------------------------------------------------------

    @Test
    fun `the known pair ranks first, with the counts that justify it`() {
        val suggestions = ranked(rankComplements(shop, listOf(id("biryani")), topN = 3))

        assertEquals(listOf("coke", "salad"), ids(suggestions))
        val rule = suggestions.first().strongest
        assertEquals(id("biryani"), rule.given)
        assertEquals(12, rule.together)
        assertEquals(20, rule.givenBaskets)
        assertEquals(20, rule.suggestedBaskets)
        assertEquals(60, rule.basketCount)
        assertEquals(60, rule.confidencePercent)
        assertEquals(1.8, rule.lift, 1e-12)
    }

    @Test
    fun `the plain frequency baseline agrees on the order here`() {
        val byFrequency = ranked(rankComplements(shop, listOf(id("biryani")), topN = 3, method = SuggestionMethod.FREQUENCY))
        assertEquals(listOf("coke", "salad"), ids(byFrequency))
        assertEquals(12.0, byFrequency.first().score, 0.0)
    }

    @Test
    fun `topN cuts the list`() {
        assertEquals(listOf("coke"), ids(ranked(rankComplements(shop, listOf(id("biryani")), topN = 1))))
    }

    @Test
    fun `a product already in the cart is never suggested`() {
        val suggestions = ranked(rankComplements(shop, listOf(id("biryani"), id("coke")), topN = 5))
        assertFalse("coke" in ids(suggestions))
        assertFalse("biryani" in ids(suggestions))
    }

    @Test
    fun `a product that cannot be offered is skipped before the top N, so the list stays full`() {
        val suggestions = ranked(rankComplements(shop, listOf(id("biryani")), topN = 1, eligible = { it != id("coke") }))
        assertEquals(listOf("salad"), ids(suggestions))
    }

    @Test
    fun `an empty cart suggests nothing, and that is not a lack of data`() {
        assertEquals(ComplementResult.Ranked(emptyList()), rankComplements(shop, emptyList(), topN = 3))
    }

    @Test
    fun `asking for fewer than one suggestion is refused`() {
        assertThrows(IllegalArgumentException::class.java) { rankComplements(shop, listOf(id("biryani")), topN = 0) }
    }

    /**
     * 90 sales.  10 x {A,B,C}   10 x {A,D}   10 x {B}   20 x {C}   40 x {X}
     * A 20, B 20, C 30, D 10.
     *   A -> C: together 10, confidence 50%, lift 10 x 90 / (20 x 30) = 1.5
     *   A -> D: together 10, confidence 50%, lift 10 x 90 / (20 x 10) = 4.5
     *   B -> C: together 10, confidence 50%, lift 1.5
     */
    private val shared =
        model(
            group(10, "abc", "A", "B", "C"),
            group(10, "ad", "A", "D"),
            group(10, "b", "B"),
            group(20, "c", "C"),
            group(40, "x", "X"),
        )

    @Test
    fun `the cart as a whole is the basis, so the list changes when an item is added`() {
        // Cart {A}: B, C and D all score 0.5 (confidence 10/20), so the higher lift goes first:
        // D (4.5), then B (2.25), then C (1.5).
        assertEquals(listOf("D", "B", "C"), ids(ranked(rankComplements(shared, listOf(id("A")), topN = 5))))

        // Cart {A, B}: C is supported by both (0.5 + 0.5 = 1.0), D only by A (0.5).
        val both = ranked(rankComplements(shared, listOf(id("A"), id("B")), topN = 5))
        assertEquals(listOf("C", "D"), ids(both))
        assertEquals(1.0, both.first().score, 1e-12)
        assertEquals(2, both.first().rules.size)
    }

    @Test
    fun `the order the cart was built in changes nothing`() {
        assertEquals(
            rankComplements(shared, listOf(id("A"), id("B")), topN = 5),
            rankComplements(shared, listOf(id("B"), id("A")), topN = 5),
        )
    }

    @Test
    fun `the same sales always give the same list however the lines arrive`() {
        val lines = listOf(group(12, "bc", "biryani", "coke"), group(5, "bs", "biryani", "salad"), group(40, "o", "other")).flatten()
        val forward = rankComplements(computeCooccurrence(lines), listOf(id("biryani")), topN = 5)
        val shuffled = rankComplements(computeCooccurrence(lines.shuffled(java.util.Random(3))), listOf(id("biryani")), topN = 5)
        assertEquals(forward, shuffled)
    }

    @Test
    fun `a tie on everything is settled by product id, never by chance`() {
        // Two partners with identical counts: 10 x {A,P}, 10 x {A,Q}, and enough others to pass the gates.
        val tied = model(group(10, "ap", "A", "P"), group(10, "aq", "A", "Q"), group(30, "o", "other"))
        assertEquals(listOf("P", "Q"), ids(ranked(rankComplements(tied, listOf(id("A")), topN = 5))))
    }

    // ---- Step 94: the three edge cases -----------------------------------------------------

    /** 50 sales.  10 x {A,B}   30 x {C}   10 x {D,E}.  A and B are always bought together. */
    private val alwaysTogether = model(group(10, "ab", "A", "B"), group(30, "c", "C"), group(10, "de", "D", "E"))

    @Test
    fun `two products always bought together are suggested for each other`() {
        val forA = ranked(rankComplements(alwaysTogether, listOf(id("A")), topN = 3))
        assertEquals(listOf("B"), ids(forA))
        assertEquals(100, forA.single().strongest.confidencePercent)
        assertEquals(5.0, forA.single().strongest.lift, 1e-12)

        assertEquals(listOf("A"), ids(ranked(rankComplements(alwaysTogether, listOf(id("B")), topN = 3))))
    }

    @Test
    fun `products never bought together are not suggested for each other`() {
        // Tea has never been in a sale with biryani, and biryani never with tea.
        assertFalse("tea" in ids(ranked(rankComplements(shop, listOf(id("biryani")), topN = 10))))
        assertFalse("biryani" in ids(ranked(rankComplements(shop, listOf(id("tea")), topN = 10))))
        assertEquals(listOf("biscuit"), ids(ranked(rankComplements(shop, listOf(id("tea")), topN = 10))))
    }

    @Test
    fun `a product with sales but no partners gets an answer, an empty one`() {
        // Rice sold 12 times and always alone: the history is enough to judge, and nothing stands out.
        assertEquals(ComplementResult.Ranked(emptyList()), rankComplements(shop, listOf(id("rice")), topN = 3))
    }

    @Test
    fun `a brand-new product with no history shows nothing and says it lacks history`() {
        val result = rankComplements(shop, listOf(id("brand-new")), topN = 3)

        assertEquals(
            ComplementResult.NotEnoughData(NotEnoughDataReason.CART_HISTORY, 0, SuggestionSettings.DEFAULT_MIN_CART_PRODUCT_BASKETS),
            result,
        )
    }

    @Test
    fun `a product sold too few times is also not enough, however clean its few sales look`() {
        // Four sales, all with coke: a perfect 100%, and still only four sales.
        val young = computeCooccurrence(shopGroups.flatten() + group(4, "z", "Z", "coke"))
        val result = rankComplements(young, listOf(id("Z")), topN = 3)

        assertEquals(
            ComplementResult.NotEnoughData(NotEnoughDataReason.CART_HISTORY, 4, SuggestionSettings.DEFAULT_MIN_CART_PRODUCT_BASKETS),
            result,
        )
    }

    @Test
    fun `a new product beside an established one is judged on the established one`() {
        val suggestions = ranked(rankComplements(shop, listOf(id("brand-new"), id("biryani")), topN = 3))
        assertEquals(listOf("coke", "salad"), ids(suggestions))
    }

    // ---- not enough data, shop-wide ---------------------------------------------------------

    @Test
    fun `a shop with fewer sales than the minimum is not judged at all`() {
        val tiny = model(group(19, "ab", "A", "B"))
        assertEquals(
            ComplementResult.NotEnoughData(NotEnoughDataReason.SHOP_HISTORY, 19, SuggestionSettings.DEFAULT_MIN_BASKETS),
            rankComplements(tiny, listOf(id("A")), topN = 3),
        )
    }

    @Test
    fun `the minimum number of sales is enough`() {
        val exactly = model(group(20, "ab", "A", "B"))
        // 20 sales, all {A,B}: B is in every sale, so lift is 1.0 and ASSOCIATION refuses it; the point
        // here is only that the shop-wide gate has opened (a Ranked answer, not NotEnoughData).
        assertTrue(rankComplements(exactly, listOf(id("A")), topN = 3) is ComplementResult.Ranked)
    }

    // ---- the thresholds, exactly at their edges --------------------------------------------

    /** Builds a rule for arithmetic checks that does not need a whole shop behind it. */
    private fun rule(
        together: Int,
        given: Int,
        suggested: Int,
        total: Int,
    ) = ComplementRule(id("g"), id("s"), together, given, suggested, total)

    @Test
    fun `lift exactly at the minimum passes and one step below fails`() {
        // 100 sales, given in 10, suggested in 25, together 3: lift = 3 x 100 / (10 x 25) = 1.2 exactly.
        val atEdge = rule(together = 3, given = 10, suggested = 25, total = 100)
        assertEquals(1.2, atEdge.lift, 1e-12)
        assertTrue(atEdge.liftAtLeast(120))
        assertFalse(atEdge.liftAtLeast(121))
    }

    @Test
    fun `confidence exactly at the minimum passes and one step below fails`() {
        val atEdge = rule(together = 3, given = 10, suggested = 25, total = 100)
        assertTrue(atEdge.confidenceAtLeast(30))
        assertFalse(atEdge.confidenceAtLeast(31))
    }

    @Test
    fun `too few shared sales fails however strong the percentage`() {
        // given in 5 sales, together in 2: confidence 40% and lift well over 1, but only two sales.
        val thin = model(group(2, "ab", "A", "B"), group(3, "a", "A"), group(30, "o", "other"))
        assertEquals(ComplementResult.Ranked(emptyList()), rankComplements(thin, listOf(id("A")), topN = 3))
    }

    @Test
    fun `a product in nearly every sale is not suggested by association but is by frequency`() {
        // 25 x {A, bag} and 25 x {B, bag}: the bag is in all 50 sales, so lift is exactly 1.0.
        val bagEverywhere = model(group(25, "a", "A", "bag"), group(25, "b", "B", "bag"))

        assertEquals(1.0, rule(25, 25, 50, 50).lift, 1e-12)
        assertEquals(ComplementResult.Ranked(emptyList()), rankComplements(bagEverywhere, listOf(id("A")), topN = 3))
        assertEquals(
            listOf("bag"),
            ids(ranked(rankComplements(bagEverywhere, listOf(id("A")), topN = 3, method = SuggestionMethod.FREQUENCY))),
        )
    }

    @Test
    fun `stricter settings suggest less, looser settings suggest more`() {
        val strict = SuggestionSettings(minConfidencePercent = 70)
        val loose = SuggestionSettings(minConfidencePercent = 10, minLiftPercent = 100)

        assertEquals(emptyList<String>(), ids(ranked(rankComplements(shop, listOf(id("biryani")), topN = 5, settings = strict))))
        assertEquals(listOf("coke", "salad"), ids(ranked(rankComplements(shop, listOf(id("biryani")), topN = 5, settings = loose))))
    }

    // ---- the explanation --------------------------------------------------------------------

    @Test
    fun `confidence is shown as a whole percent, rounded half up`() {
        assertEquals(13, rule(together = 1, given = 8, suggested = 4, total = 40).confidencePercent) // 12.5
        assertEquals(33, rule(together = 1, given = 3, suggested = 4, total = 40).confidencePercent) // 33.33
        assertEquals(100, rule(together = 4, given = 4, suggested = 4, total = 40).confidencePercent)
    }

    @Test
    fun `a suggestion lists every rule behind it, strongest first`() {
        val both = ranked(rankComplements(shared, listOf(id("A"), id("B")), topN = 5)).first()
        assertEquals("C", both.productId.value)
        assertEquals(both.rules.sortedByDescending { it.confidence }, both.rules)
        assertEquals(both.rules.first(), both.strongest)
    }

    // ---- bad inputs are refused -------------------------------------------------------------

    @Test
    fun `a rule cannot claim more shared sales than either product has`() {
        assertThrows(IllegalArgumentException::class.java) { rule(together = 6, given = 5, suggested = 9, total = 20) }
        assertThrows(IllegalArgumentException::class.java) { rule(together = 1, given = 0, suggested = 9, total = 20) }
        assertThrows(IllegalArgumentException::class.java) { rule(together = 1, given = 21, suggested = 9, total = 20) }
    }

    @Test
    fun `settings outside their range are refused`() {
        assertThrows(IllegalArgumentException::class.java) { SuggestionSettings(minBaskets = 0) }
        assertThrows(IllegalArgumentException::class.java) { SuggestionSettings(minTogether = 0) }
        assertThrows(IllegalArgumentException::class.java) { SuggestionSettings(minConfidencePercent = 101) }
        assertThrows(IllegalArgumentException::class.java) { SuggestionSettings(minLiftPercent = -1) }
    }

    @Test
    fun `a suggestion with no evidence cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) { Suggestion(id("x"), 1.0, emptyList()) }
    }
}
