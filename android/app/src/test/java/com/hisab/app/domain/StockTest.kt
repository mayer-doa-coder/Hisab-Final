package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Step 35. The numbers here are also in `fixtures/m2_sale_stock.tsv`, which
 * the backend runs against the same rules (Step 37) — these tests cover what
 * a shared number cannot: signs, types, references, timestamps and refusals.
 */
class StockTest {
    private val at = Instant.parse("2026-09-15T10:00:00Z")
    private val product = generateId()

    @Test
    fun `restock adds stock and is typed as a restock`() {
        val movement = restock(product, Quantity(10_000), at)
        assertEquals(StockMovementType.RESTOCK, movement.type)
        assertEquals(Quantity(10_000), movement.quantityDelta)
        assertEquals(product, movement.productId)
    }

    @Test
    fun `sell takes stock away, and says which sale did it`() {
        val saleId = generateId()
        val movement = sell(product, Quantity(3000), saleId, at)
        assertEquals(StockMovementType.SALE, movement.type)
        assertEquals("a sale is a negative delta", Quantity(-3000), movement.quantityDelta)
        assertEquals(saleId.value, movement.sourceReference)
    }

    @Test
    fun `returnStock puts stock back`() {
        val movement = returnStock(product, Quantity(500), at, sourceReference = "sale-7")
        assertEquals(StockMovementType.RETURN, movement.type)
        assertEquals(Quantity(500), movement.quantityDelta)
        assertEquals("sale-7", movement.sourceReference)
    }

    @Test
    fun `damage takes stock away`() {
        val movement = damage(product, Quantity(1000), at)
        assertEquals(StockMovementType.DAMAGE, movement.type)
        assertEquals(Quantity(-1000), movement.quantityDelta)
    }

    @Test
    fun `every movement records when it happened, and not yet when the server saw it`() {
        val movement = restock(product, Quantity(1000), at)
        assertEquals(at, movement.time.occurredAt)
        assertEquals("nothing has reached the server yet (D019)", null, movement.time.serverReceivedAt)
    }

    @Test
    fun `a movement with a fixed direction refuses a zero or negative amount`() {
        for (amount in listOf(0L, -1000L)) {
            assertThrows(IllegalArgumentException::class.java) { restock(product, Quantity(amount), at) }
            assertThrows(IllegalArgumentException::class.java) { sell(product, Quantity(amount), generateId(), at) }
            assertThrows(IllegalArgumentException::class.java) { returnStock(product, Quantity(amount), at) }
            assertThrows(IllegalArgumentException::class.java) { damage(product, Quantity(amount), at) }
        }
    }

    @Test
    fun `correctStock records the difference between the shelf and the ledger`() {
        val found = correctStock(product, countedQuantity = Quantity(8500), recordedQuantity = Quantity(10_000), occurredAt = at)
        assertEquals(StockMovementType.CORRECTION, found.type)
        assertEquals(Quantity(-1500), found.quantityDelta)

        val extra = correctStock(product, countedQuantity = Quantity(12_000), recordedQuantity = Quantity(10_000), occurredAt = at)
        assertEquals(Quantity(2000), extra.quantityDelta)
    }

    @Test
    fun `a count that agrees still writes a movement, of zero`() {
        val movement = correctStock(product, Quantity(10_000), Quantity(10_000), at)
        assertEquals(Quantity.ZERO, movement.quantityDelta)
    }

    @Test
    fun `a shelf count cannot be negative - nobody counts minus three bottles`() {
        assertThrows(IllegalArgumentException::class.java) { correctStock(product, Quantity(-1), Quantity(0), at) }
    }

    @Test
    fun `current stock is the sum of the movements, and nothing else`() {
        val movements =
            listOf(
                restock(product, Quantity(10_000), at),
                sell(product, Quantity(3000), generateId(), at),
                restock(product, Quantity(2000), at),
            )
        assertEquals(Quantity(9000), calculateCurrentStock(movements))
    }

    @Test
    fun `a product with no movements has no stock`() {
        assertEquals(Quantity.ZERO, calculateCurrentStock(emptyList()))
    }

    @Test
    fun `current stock counts only the product asked about`() {
        val other = generateId()
        val movements =
            listOf(
                restock(product, Quantity(10_000), at),
                restock(other, Quantity(4000), at),
                sell(product, Quantity(3000), generateId(), at),
            )
        assertEquals(Quantity(7000), calculateCurrentStock(movements, product))
        assertEquals(Quantity(4000), calculateCurrentStock(movements, other))
    }

    @Test
    fun `the order the movements are summed in does not change the answer`() {
        val movements =
            listOf(
                restock(product, Quantity(10_000), at),
                sell(product, Quantity(3000), generateId(), at),
                damage(product, Quantity(500), at),
                returnStock(product, Quantity(1000), at),
            )
        assertEquals(calculateCurrentStock(movements), calculateCurrentStock(movements.reversed()))
        assertEquals(Quantity(7500), calculateCurrentStock(movements))
    }

    @Test
    fun `selling more than was ever recorded goes negative rather than being refused (D031)`() {
        val movements =
            listOf(
                restock(product, Quantity(2000), at),
                sell(product, Quantity(5000), generateId(), at),
            )
        assertEquals(Quantity(-3000), calculateCurrentStock(movements))
    }

    @Test
    fun `stockShortfall says how much is missing, and nothing when there is enough`() {
        assertEquals(Quantity(3000), stockShortfall(available = Quantity(2000), wanted = Quantity(5000)))
        assertEquals(Quantity.ZERO, stockShortfall(available = Quantity(5000), wanted = Quantity(5000)))
        assertEquals(Quantity.ZERO, stockShortfall(available = Quantity(9000), wanted = Quantity(5000)))
        assertEquals(
            "already oversold",
            Quantity(4000),
            stockShortfall(available = Quantity(-3000), wanted = Quantity(1000)),
        )

        assertTrue(hasEnoughStock(Quantity(5000), Quantity(5000)))
        assertFalse(hasEnoughStock(Quantity(4999), Quantity(5000)))
    }

    @Test
    fun `a correction brings the ledger back to what was counted`() {
        val movements =
            listOf(
                restock(product, Quantity(10_000), at),
                sell(product, Quantity(3000), generateId(), at),
            )
        val counted = Quantity(6500)
        val corrected = movements + correctStock(product, counted, calculateCurrentStock(movements), at)
        assertEquals(counted, calculateCurrentStock(corrected))
    }
}
