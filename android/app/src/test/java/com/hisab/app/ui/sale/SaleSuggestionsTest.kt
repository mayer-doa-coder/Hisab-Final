package com.hisab.app.ui.sale

import com.hisab.app.data.product.ProductEntity
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.suggest.BasketLine
import com.hisab.app.domain.suggest.computeCooccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * What the New Sale screen is told to show (Step 93), and — as much — what it is
 * told not to. The data is the same 60-sale shop the ranking tests use, so the
 * numbers behind each suggestion can be checked against those.
 */
class SaleSuggestionsTest {
    private fun product(
        id: String,
        name: String = id,
    ) = ProductEntity(
        id = id,
        shopId = "shop",
        name = name,
        aliases = emptyList(),
        unit = "piece",
        purchasePricePoisha = null,
        sellingPricePoisha = 1000,
        active = true,
        revision = 1,
        updatedAt = Instant.EPOCH,
        deletedAt = null,
    )

    private fun group(
        times: Int,
        prefix: String,
        vararg products: String,
    ): List<BasketLine> = (1..times).flatMap { n -> products.map { BasketLine(EntityId("$prefix-$n"), EntityId(it)) } }

    /** 12 x {biryani, coke}, 3 x {biryani}, 8 x {coke}, 5 x {biryani, salad}, 20 x {tea, biscuit}, 12 x {rice}. */
    private val shop =
        computeCooccurrence(
            listOf(
                group(12, "bc", "biryani", "coke"),
                group(3, "b", "biryani"),
                group(8, "c", "coke"),
                group(5, "bs", "biryani", "salad"),
                group(20, "tb", "tea", "biscuit"),
                group(12, "r", "rice"),
            ).flatten(),
        )

    private val everything =
        listOf("biryani", "coke", "salad", "tea", "biscuit", "rice", "brand-new").map { product(it) }

    private fun names(panel: SuggestionPanel) = panel.items.map { it.product.id }

    @Test
    fun `adding a product to the cart offers a relevant suggestion, strongest first`() {
        val panel = suggestionPanelFor(shop, listOf("biryani"), everything, dismissed = emptySet())

        assertEquals(listOf("coke", "salad"), names(panel))
        assertEquals(SuggestionHint.NONE, panel.hint)
        assertTrue(panel.isVisible)
    }

    @Test
    fun `each suggestion carries the sales behind it`() {
        val first = suggestionPanelFor(shop, listOf("biryani"), everything, emptySet()).items.first()

        assertEquals(12, first.rule.together)
        assertEquals(20, first.rule.givenBaskets)
        assertEquals(60, first.rule.confidencePercent)
    }

    @Test
    fun `the whole cart is the basis`() {
        // Tea alone suggests its biscuit; with biryani beside it, the biryani suggestions join in.
        assertEquals(listOf("biscuit"), names(suggestionPanelFor(shop, listOf("tea"), everything, emptySet())))
        assertEquals(
            setOf("biscuit", "coke", "salad"),
            names(suggestionPanelFor(shop, listOf("tea", "biryani"), everything, emptySet())).toSet(),
        )
    }

    @Test
    fun `an empty cart shows nothing`() {
        assertEquals(SuggestionPanel.EMPTY, suggestionPanelFor(shop, emptyList(), everything, emptySet()))
        assertFalse(SuggestionPanel.EMPTY.isVisible)
    }

    @Test
    fun `while the history is still being read, nothing is shown and nothing is said`() {
        val panel = suggestionPanelFor(model = null, cartProductIds = listOf("biryani"), sellable = everything, dismissed = emptySet())
        assertEquals(SuggestionPanel.EMPTY, panel)
    }

    @Test
    fun `a suggestion that was dismissed stays gone and the next one takes its place`() {
        val panel = suggestionPanelFor(shop, listOf("biryani"), everything, dismissed = setOf("coke"))
        assertEquals(listOf("salad"), names(panel))

        val noneLeft = suggestionPanelFor(shop, listOf("biryani"), everything, dismissed = setOf("coke", "salad"))
        assertEquals(emptyList<String>(), names(noneLeft))
        assertEquals(SuggestionHint.NONE, noneLeft.hint)
        assertFalse("a dismissed strip leaves nothing on the screen", noneLeft.isVisible)
    }

    @Test
    fun `a product the shop no longer sells is not offered, because tapping it would add nothing`() {
        val withoutCoke = everything.filter { it.id != "coke" }
        assertEquals(listOf("salad"), names(suggestionPanelFor(shop, listOf("biryani"), withoutCoke, emptySet())))
    }

    @Test
    fun `something already in the cart is not offered again`() {
        val panel = suggestionPanelFor(shop, listOf("biryani", "coke"), everything, emptySet())
        assertFalse("coke" in names(panel))
        assertEquals(listOf("salad"), names(panel))
    }

    @Test
    fun `at most three are shown`() {
        // Biryani sold with four partners that each pass every threshold.
        val wide =
            computeCooccurrence(
                listOf(
                    group(10, "p1", "main", "w"),
                    group(10, "p2", "main", "x"),
                    group(10, "p3", "main", "y"),
                    group(10, "p4", "main", "z"),
                    group(40, "o", "other"),
                ).flatten(),
            )
        val catalogue = listOf("main", "w", "x", "y", "z", "other").map { product(it) }

        val panel = suggestionPanelFor(wide, listOf("main"), catalogue, emptySet())
        assertEquals(MAX_SUGGESTIONS_SHOWN, panel.items.size)
        assertEquals(3, MAX_SUGGESTIONS_SHOWN)
    }

    // ---- Step 94 / 95 as the shopkeeper sees them -------------------------------------------

    @Test
    fun `a brand-new product in a busy shop shows nothing at all - no guess and no message`() {
        val panel = suggestionPanelFor(shop, listOf("brand-new"), everything, emptySet())

        assertEquals(emptyList<String>(), names(panel))
        assertEquals(SuggestionHint.NONE, panel.hint)
        assertFalse(panel.isVisible)
    }

    @Test
    fun `products never bought together show nothing for each other`() {
        // Rice is sold alone, always.
        val panel = suggestionPanelFor(shop, listOf("rice"), everything, emptySet())
        assertEquals(emptyList<String>(), names(panel))
        assertFalse(panel.isVisible)
    }

    @Test
    fun `a shop that is too new says so once instead of showing nothing`() {
        val young = computeCooccurrence(group(5, "s", "biryani", "coke"))
        val panel = suggestionPanelFor(young, listOf("biryani"), everything, emptySet())

        assertEquals(emptyList<String>(), names(panel))
        assertEquals(SuggestionHint.NOT_ENOUGH_HISTORY, panel.hint)
        assertTrue(panel.isVisible)
    }

    @Test
    fun `a product that cannot be found in the catalogue is simply never offered`() {
        // The history mentions "salad" but the catalogue passed in does not: no crash, no ghost button.
        val panel = suggestionPanelFor(shop, listOf("biryani"), everything.filter { it.id != "salad" }, emptySet())
        assertNull(panel.items.firstOrNull { it.product.id == "salad" })
    }
}
