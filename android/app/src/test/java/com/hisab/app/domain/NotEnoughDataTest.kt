package com.hisab.app.domain

import com.hisab.app.domain.forecast.DailyDemand
import com.hisab.app.domain.forecast.DemandSeries
import com.hisab.app.domain.forecast.ForecastMethod
import com.hisab.app.domain.forecast.ForecastMethods
import com.hisab.app.domain.forecast.InventoryAdvisor
import com.hisab.app.domain.forecast.ProductAdvice
import com.hisab.app.domain.suggest.BasketLine
import com.hisab.app.domain.suggest.ComplementResult
import com.hisab.app.domain.suggest.NotEnoughDataReason
import com.hisab.app.domain.suggest.computeCooccurrence
import com.hisab.app.domain.suggest.rankComplements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Step 95's check: forecasting, reorder and suggestions each say "not enough data"
 * for a product with almost no sales history, instead of guessing.
 *
 * One product plays all three parts here: it has been sold twice, once last
 * Tuesday and once yesterday. The same product is run through each feature, so a
 * rule that was added to one and forgotten in another shows up as a failure of
 * this file and not as a wrong number on a shopkeeper's screen.
 *
 * What each feature does about it:
 *  - forecasting and reorder are one path (`InventoryAdvisor`): no forecast is made
 *    and no quantity is recommended; the answer is the counts it did find;
 *  - suggestions return a typed "not enough data" with the reason, and the screen
 *    shows nothing for a single new product and a quiet line only when the whole
 *    shop is too new (`SaleSuggestionsTest`);
 *  - Ask Hisab turns the first into a plain sentence in the language of the question
 *    (`AskForecastTest`, on the phone).
 */
class NotEnoughDataTest {
    private val product = EntityId("almost-new")
    private val start = LocalDate.of(2026, 9, 1)

    /** Nine days observed, two of them with a sale: less than the advisor's two weeks and three sale days. */
    private val almostNothing =
        DemandSeries(
            product,
            (0 until 9).map { day ->
                DailyDemand(start.plusDays(day.toLong()), Quantity(if (day == 1 || day == 8) 2000L else 0L))
            },
        )

    private fun everyMethod(): List<ForecastMethod> = ForecastMethods.all()

    // ---- forecasting and reorder ------------------------------------------------------------

    @Test
    fun `a product sold twice gets no forecast and no reorder quantity from the app's method`() {
        val advice = InventoryAdvisor().advise(almostNothing, Quantity(5000), ProductUnits.PIECE)

        assertEquals(ProductAdvice.NotEnoughData(daysObserved = 9, demandDays = 2), advice)
    }

    @Test
    fun `no method in the catalogue can be used to get a guess out of it`() {
        // The gate is the advisor's, not the method's: a method that is happy with two sales
        // (naive needs one day) must not be able to talk the app into recommending a purchase.
        for (method in everyMethod()) {
            val advice = InventoryAdvisor(method).advise(almostNothing, Quantity(5000), ProductUnits.PIECE)
            assertTrue("${method.id} gave ${advice::class.simpleName}", advice is ProductAdvice.NotEnoughData)
        }
    }

    @Test
    fun `a product never sold is told apart from one sold a little, and neither is a series of zeros`() {
        val neverSold = InventoryAdvisor().advise(null, Quantity(5000), ProductUnits.PIECE)
        assertEquals(ProductAdvice.NotEnoughData(daysObserved = 0, demandDays = 0), neverSold)
    }

    @Test
    fun `the answer still carries the facts it did find, so a sentence can say them plainly`() {
        val advice = InventoryAdvisor().advise(almostNothing, Quantity.ZERO, ProductUnits.PIECE) as ProductAdvice.NotEnoughData

        assertEquals(9, advice.daysObserved)
        assertEquals(2, advice.demandDays)
    }

    // ---- suggestions ------------------------------------------------------------------------

    private fun sale(
        saleId: String,
        vararg products: String,
    ) = products.map { BasketLine(EntityId(saleId), EntityId(it)) }

    /** A busy shop (40 sales) in which "almost-new" has been sold twice, both times with coke. */
    private val busyShop =
        computeCooccurrence(
            (1..38).flatMap { sale("o-$it", "tea", "biscuit") } +
                sale("n-1", "almost-new", "coke") +
                sale("n-2", "almost-new", "coke"),
        )

    @Test
    fun `a product sold twice gets no suggestion, and the reason is its own history`() {
        val result = rankComplements(busyShop, listOf(product), topN = 3)

        assertEquals(
            ComplementResult.NotEnoughData(NotEnoughDataReason.CART_HISTORY, basketsAvailable = 2, basketsNeeded = 5),
            result,
        )
    }

    @Test
    fun `two sales that both include coke are not allowed to look like a pattern`() {
        // 100% of its sales, and still nothing: a rule from two sales is a coincidence.
        assertTrue(rankComplements(busyShop, listOf(product), topN = 3) is ComplementResult.NotEnoughData)
    }

    @Test
    fun `a whole shop with almost no sales says the shop is too new`() {
        val newShop = computeCooccurrence(sale("s1", "almost-new", "coke") + sale("s2", "almost-new", "coke"))

        assertEquals(
            ComplementResult.NotEnoughData(NotEnoughDataReason.SHOP_HISTORY, basketsAvailable = 2, basketsNeeded = 20),
            rankComplements(newShop, listOf(product), topN = 3),
        )
    }
}
