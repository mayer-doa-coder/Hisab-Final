package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Step 36. The arithmetic itself is checked against `fixtures/m2_sale_stock.tsv`,
 * which the backend runs too (Step 37). These tests cover what a shared number
 * cannot: what a completed sale is made of, and what a reversal leaves behind.
 */
class SaleTest {
    private val at = Instant.parse("2026-09-15T10:00:00Z")
    private val later = Instant.parse("2026-09-15T18:30:00Z")
    private val shop = "shop-1"

    private val rice = generateId()
    private val oil = generateId()

    private fun cart() =
        listOf(
            SaleLine(rice, Quantity(3000), Money(5000)),
            SaleLine(oil, Quantity(2000), Money(2500)),
        )

    @Test
    fun `a line total is quantity times price, in integer poisha`() {
        assertEquals(Money(15_000), calculateLineTotal(Quantity(3000), Money(5000)))
    }

    @Test
    fun `a half poisha rounds away from zero both ways, so a reversal cancels`() {
        val charged = calculateLineTotal(Quantity(1250), Money(9050))
        val refunded = calculateLineTotal(Quantity(-1250), Money(9050))
        assertEquals(Money(11_313), charged)
        assertEquals(Money(-11_313), refunded)
        assertEquals("no poisha is left behind", Money.ZERO, charged + refunded)
    }

    @Test
    fun `a negative price is refused - a discount is a lower price, not a negative one`() {
        assertThrows(IllegalArgumentException::class.java) { calculateLineTotal(Quantity(1000), Money(-1)) }
    }

    @Test
    fun `a sale total rounds each line first, then adds`() {
        val saleId = generateId()
        val items =
            listOf(
                SaleItem(saleId, rice, Quantity(1250), Money(9050)),
                SaleItem(saleId, oil, Quantity(1250), Money(9050)),
            )
        assertEquals(
            "not 22625, which rounding once at the end gives",
            Money(22_626),
            calculateSaleTotal(items),
        )
    }

    @Test
    fun `a cash sale records the sale, its lines and one stock movement each`() {
        val sale = completeCashSale(shop, cart(), at)

        assertEquals(Money(20_000), sale.sale.total)
        assertEquals(SalePayment.CASH, sale.sale.payment)
        assertEquals(2, sale.items.size)
        assertEquals(2, sale.stockMovements.size)
        assertEquals("stock left the shop", Quantity(-5000), calculateCurrentStock(sale.stockMovements))
    }

    @Test
    fun `a cash sale owes nobody anything`() {
        val sale = completeCashSale(shop, cart(), at)
        assertNull(sale.bakiEntry)
        assertNull(sale.sale.customerId)
    }

    @Test
    fun `every stock movement points back at the sale that caused it`() {
        val sale = completeCashSale(shop, cart(), at)
        sale.stockMovements.forEach {
            assertEquals(sale.sale.id.value, it.sourceReference)
            assertEquals(StockMovementType.SALE, it.type)
        }
    }

    @Test
    fun `a sale records when it happened, not when the server will see it`() {
        val sale = completeCashSale(shop, cart(), at)
        assertEquals(at, sale.sale.time.occurredAt)
        assertNull(sale.sale.time.serverReceivedAt)
    }

    @Test
    fun `a credit sale owes exactly what it totalled`() {
        val customer = generateId()
        val sale = completeCreditSale(shop, customer, cart(), at)

        assertEquals(SalePayment.CREDIT, sale.sale.payment)
        assertEquals(customer, sale.sale.customerId)
        assertEquals(sale.sale.total, sale.bakiEntry?.amountDelta)
        assertEquals(BakiEntryType.CREDIT_SALE, sale.bakiEntry?.type)
        assertEquals(sale.sale.id.value, sale.bakiEntry?.reference)
    }

    @Test
    fun `a credit sale can carry a due date, and does not have to`() {
        val customer = generateId()
        val due = LocalDate.of(2026, 10, 15)
        assertNull(completeCreditSale(shop, customer, cart(), at).bakiEntry?.dueDate)
        assertEquals(due, completeCreditSale(shop, customer, cart(), at, dueDate = due).bakiEntry?.dueDate)
    }

    @Test
    fun `a credit sale moves stock exactly like a cash sale`() {
        val cash = completeCashSale(shop, cart(), at)
        val credit = completeCreditSale(shop, generateId(), cart(), at)
        assertEquals(
            calculateCurrentStock(cash.stockMovements),
            calculateCurrentStock(credit.stockMovements),
        )
    }

    @Test
    fun `an empty cart is not a sale`() {
        assertThrows(IllegalArgumentException::class.java) { completeCashSale(shop, emptyList(), at) }
        assertThrows(IllegalArgumentException::class.java) {
            completeCreditSale(shop, generateId(), emptyList(), at)
        }
    }

    @Test
    fun `a zero or negative quantity is not a sale line`() {
        for (amount in listOf(0L, -1000L)) {
            assertThrows(IllegalArgumentException::class.java) {
                completeCashSale(shop, listOf(SaleLine(rice, Quantity(amount), Money(5000))), at)
            }
        }
    }

    @Test
    fun `the same product twice in one cart is refused, so lines cannot disagree with the total`() {
        assertThrows(IllegalArgumentException::class.java) {
            completeCashSale(
                shop,
                listOf(
                    SaleLine(rice, Quantity(1000), Money(5000)),
                    SaleLine(rice, Quantity(2000), Money(5000)),
                ),
                at,
            )
        }
    }

    @Test
    fun `reversing a cash sale gives the stock back and nets the money to zero`() {
        val sale = completeCashSale(shop, cart(), at)
        val undone = reverseSale(sale, later)

        assertEquals(Money.ZERO - sale.sale.total, undone.sale.total)
        assertEquals(sale.sale.id, undone.sale.reversesSaleId)
        assertNull("a cash sale owed nothing, so nothing is cleared", undone.bakiEntry)
        assertEquals(
            "stock is back where it started",
            Quantity.ZERO,
            calculateCurrentStock(sale.stockMovements + undone.stockMovements),
        )
    }

    @Test
    fun `a reversal is stock coming back in, pointing at the sale it undoes`() {
        val sale = completeCashSale(shop, cart(), at)
        val undone = reverseSale(sale, later)

        undone.stockMovements.forEach {
            assertEquals(StockMovementType.RETURN, it.type)
            assertEquals(sale.sale.id.value, it.sourceReference)
            assertTrue(it.quantityDelta.scaledUnits > 0)
        }
    }

    @Test
    fun `a reversal happens when it happens, not when the sale did`() {
        val sale = completeCashSale(shop, cart(), at)
        assertEquals(later, reverseSale(sale, later).sale.time.occurredAt)
    }

    // D021: the sale, its stock and its baki move together, or not at all.
    @Test
    fun `reversing a credit sale undoes the sale, the stock and the baki together`() {
        val customer = generateId()
        val sale = completeCreditSale(shop, customer, cart(), at)
        val undone = reverseSale(sale, later)

        assertEquals("money nets to zero", Money.ZERO, sale.sale.total + undone.sale.total)
        assertEquals(
            "stock nets to zero",
            Quantity.ZERO,
            calculateCurrentStock(sale.stockMovements + undone.stockMovements),
        )
        assertEquals(
            "baki nets to zero",
            Money.ZERO,
            (sale.bakiEntry!!.amountDelta) + (undone.bakiEntry!!.amountDelta),
        )
        assertEquals(customer, undone.bakiEntry!!.customerId)
        assertEquals(BakiEntryType.REVERSAL, undone.bakiEntry!!.type)
    }

    // The point of D021 is that there is no way to get a partial reversal.
    // This is the test that says so: the three parts arrive in one value, and
    // the value refuses to exist without all of them.
    @Test
    fun `a credit reversal cannot exist with the stock restored but the baki left standing`() {
        val customer = generateId()
        val sale = completeCreditSale(shop, customer, cart(), at)
        val undone = reverseSale(sale, later)

        assertThrows(IllegalArgumentException::class.java) { undone.copy(bakiEntry = null) }
        assertThrows(IllegalArgumentException::class.java) { undone.copy(stockMovements = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            undone.copy(bakiEntry = undone.bakiEntry!!.copy(amountDelta = Money(-1)))
        }
    }

    @Test
    fun `a reversal cannot itself be reversed`() {
        val undone = reverseSale(completeCashSale(shop, cart(), at), later)
        assertThrows(IllegalArgumentException::class.java) { reverseSale(undone, later) }
    }

    @Test
    fun `a day's takings already exclude a reversed sale, without editing history`() {
        val kept = completeCashSale(shop, cart(), at)
        val mistake = completeCashSale(shop, cart(), at)
        val undone = reverseSale(mistake, later)

        val takings = listOf(kept.sale, mistake.sale, undone.sale).map { it.total }.sum()
        assertEquals(kept.sale.total, takings)
    }
}
