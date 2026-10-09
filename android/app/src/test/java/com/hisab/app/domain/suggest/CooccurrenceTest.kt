package com.hisab.app.domain.suggest

import com.hisab.app.domain.EntityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Step 91's check: a known pair of frequently co-purchased products shows a high
 * count. Every other case here is a count small enough to do on paper.
 */
class CooccurrenceTest {
    private fun id(name: String) = EntityId(name)

    /** `sale("s1", "a", "b")` is sale s1 containing products a and b. */
    private fun sale(
        saleId: String,
        vararg products: String,
    ): List<BasketLine> = products.map { BasketLine(id(saleId), id(it)) }

    /** [times] separate sales that each contain exactly [products]. */
    private fun repeated(
        times: Int,
        prefix: String,
        vararg products: String,
    ): List<BasketLine> = (1..times).flatMap { sale("$prefix-$it", *products) }

    @Test
    fun `a known pair that is bought together often shows a high count`() {
        // Biryani and Coke go out together in 12 of 15 sales that include Biryani.
        val lines = repeated(12, "both", "biryani", "coke") + repeated(3, "biryani-only", "biryani") + repeated(8, "coke-only", "coke")
        val counts = computeCooccurrence(lines)

        assertEquals(12, counts.together(id("biryani"), id("coke")))
        assertEquals(15, counts.basketsWith(id("biryani")))
        assertEquals(20, counts.basketsWith(id("coke")))
        assertEquals(23, counts.basketCount)
    }

    @Test
    fun `counts worked out by hand on four small sales`() {
        // s1 {A,B}   s2 {A,B,C}   s3 {A}   s4 {B,C}
        val counts =
            computeCooccurrence(
                sale("s1", "A", "B") + sale("s2", "A", "B", "C") + sale("s3", "A") + sale("s4", "B", "C"),
            )

        assertEquals(4, counts.basketCount)
        assertEquals(3, counts.basketsWith(id("A")))
        assertEquals(3, counts.basketsWith(id("B")))
        assertEquals(2, counts.basketsWith(id("C")))
        assertEquals(2, counts.together(id("A"), id("B")))
        assertEquals(1, counts.together(id("A"), id("C")))
        assertEquals(2, counts.together(id("B"), id("C")))
        assertEquals(3, counts.productCount)
        assertEquals(3, counts.pairCount)
    }

    @Test
    fun `a pair is counted the same whichever way round it is asked`() {
        val counts = computeCooccurrence(repeated(4, "s", "A", "B"))

        assertEquals(counts.together(id("A"), id("B")), counts.together(id("B"), id("A")))
        assertEquals(mapOf(id("B") to 4), counts.partnersOf(id("A")))
        assertEquals(mapOf(id("A") to 4), counts.partnersOf(id("B")))
    }

    @Test
    fun `a product is never paired with itself`() {
        val counts = computeCooccurrence(repeated(5, "s", "A", "B"))
        assertEquals(0, counts.together(id("A"), id("A")))
        assertEquals(false, id("A") in counts.partnersOf(id("A")))
    }

    @Test
    fun `products never sold together, and products never sold, count zero`() {
        val counts = computeCooccurrence(sale("s1", "A") + sale("s2", "B"))

        assertEquals(0, counts.together(id("A"), id("B")))
        assertEquals(0, counts.basketsWith(id("never-sold")))
        assertEquals(0, counts.together(id("A"), id("never-sold")))
        assertEquals(emptyMap<EntityId, Int>(), counts.partnersOf(id("never-sold")))
    }

    @Test
    fun `a product counts once per sale however many units it sold`() {
        // The input holds one line per product per sale, so quantity never reaches it. A
        // line repeated in the input (it should not be) must still count once, not twice.
        val counts = computeCooccurrence(sale("s1", "A", "B") + sale("s1", "A"))

        assertEquals(1, counts.basketsWith(id("A")))
        assertEquals(1, counts.together(id("A"), id("B")))
        assertEquals(1, counts.basketCount)
    }

    @Test
    fun `the order of the lines changes nothing`() {
        val lines = repeated(6, "x", "A", "B", "C") + repeated(4, "y", "B", "D") + repeated(3, "z", "A", "D")
        val forward = computeCooccurrence(lines)
        val backward = computeCooccurrence(lines.reversed())
        val shuffled = computeCooccurrence(lines.shuffled(java.util.Random(7)))

        for (other in listOf(backward, shuffled)) {
            assertEquals(forward.basketCount, other.basketCount)
            for (a in listOf("A", "B", "C", "D")) {
                assertEquals(forward.basketsWith(id(a)), other.basketsWith(id(a)))
                assertEquals(forward.partnersOf(id(a)), other.partnersOf(id(a)))
            }
        }
    }

    @Test
    fun `no sales gives the empty model`() {
        val counts = computeCooccurrence(emptyList())

        assertSame(Cooccurrence.EMPTY, counts)
        assertEquals(0, counts.basketCount)
        assertEquals(0, counts.productCount)
        assertEquals(0, counts.pairCount)
    }

    @Test
    fun `a single-product sale is a sale but makes no pair`() {
        val counts = computeCooccurrence(sale("s1", "A") + sale("s2", "A"))

        assertEquals(2, counts.basketCount)
        assertEquals(2, counts.basketsWith(id("A")))
        assertEquals(0, counts.pairCount)
    }

    @Test
    fun `a sale of exactly the pair limit still makes every pair`() {
        val products = (1..MAX_PRODUCTS_PER_BASKET_FOR_PAIRS).map { "p$it" }
        val counts = computeCooccurrence(sale("big", *products.toTypedArray()))

        // 30 products make 30 x 29 / 2 pairs.
        assertEquals(435, counts.pairCount)
        assertEquals(0, counts.oversizedBaskets)
        assertEquals(1, counts.together(id("p1"), id("p30")))
    }

    @Test
    fun `a sale past the pair limit counts as a sale and for each product but makes no pairs`() {
        val products = (1..MAX_PRODUCTS_PER_BASKET_FOR_PAIRS + 1).map { "p$it" }
        val counts = computeCooccurrence(sale("clearance", *products.toTypedArray()) + sale("normal", "p1", "p2"))

        assertEquals(1, counts.oversizedBaskets)
        assertEquals(2, counts.basketCount)
        assertEquals(2, counts.basketsWith(id("p1")))
        assertEquals(1, counts.basketsWith(id("p31")))
        // Only the normal sale paired p1 with p2; the clearance paired nothing.
        assertEquals(1, counts.together(id("p1"), id("p2")))
        assertEquals(0, counts.together(id("p1"), id("p31")))
        assertEquals(1, counts.pairCount)
    }
}
