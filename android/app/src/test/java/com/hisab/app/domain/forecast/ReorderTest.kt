package com.hisab.app.domain.forecast

import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 89's check: the numbers in a recommendation add up.
 *
 * "Add up" is tested two ways. By hand, on cases small enough to check on paper
 * (the PRD's own example first). And as a property over a grid of stocks,
 * demands, percentages and units: whatever the inputs, stock plus purchase must
 * equal demand plus margin plus rounding, to the thousandth.
 */
class ReorderTest {
    private fun units(amount: Double) = Quantity.fromDecimal(amount)

    private fun recommend(
        stock: Double,
        demand: Double,
        unit: String = ProductUnits.PIECE,
        percent: Int = 20,
        leadTimeDays: Int = 7,
    ) = recommendReorder(units(stock), units(demand), unit, ReorderSettings(leadTimeDays, percent))

    @Test
    fun `the PRD example purchase - stock 4, demand 14, buy 13 - with the rounding shown on its own line`() {
        // The PRD shows a margin of exactly 3 and no rounding line (4 + 13 = 14 + 3).
        // Here the configured 20% gives 2.8, so 14 + 2.8 - 4 = 12.8 is needed. A piece
        // cannot be split, so 13 are bought: the same purchase, with the extra 0.2 shown
        // as its own line instead of being folded into the margin (see D052).
        val answer = recommend(stock = 4.0, demand = 14.0)

        assertEquals(units(4.0), answer.currentStock)
        assertEquals(units(14.0), answer.expectedDemand)
        assertEquals(units(2.8), answer.safetyMargin)
        assertEquals(units(0.2), answer.roundedUpBy)
        assertEquals(units(13.0), answer.quantityToBuy)
        // And it adds up: 4 + 13 = 17 = 14 + 2.8 + 0.2.
        assertEquals(units(17.0), answer.countedStock + answer.quantityToBuy)
        assertEquals(units(17.0), answer.expectedDemand + answer.safetyMargin + answer.roundedUpBy)
    }

    @Test
    fun `nothing is bought when stock already covers demand and the margin`() {
        // 14 + 2.8 = 16.8 is needed and 20 are on the shelf.
        val answer = recommend(stock = 20.0, demand = 14.0)

        assertTrue(answer.enoughStock)
        assertEquals(Quantity.ZERO, answer.quantityToBuy)
        assertEquals(Quantity.ZERO, answer.roundedUpBy)
    }

    @Test
    fun `stock exactly equal to the target needs nothing`() {
        // Demand 10, margin 2.0, so 12.0 is the target and 12 are held.
        assertTrue(recommend(stock = 12.0, demand = 10.0).enoughStock)
        // One thousandth short is enough to need a whole piece.
        val short = recommendReorder(Quantity(11_999), units(10.0), ProductUnits.PIECE)
        assertEquals(units(1.0), short.quantityToBuy)
    }

    @Test
    fun `a need that is already a whole number is not rounded`() {
        // 10 + 2 - 3 = 9 exactly.
        val answer = recommend(stock = 3.0, demand = 10.0)

        assertEquals(units(9.0), answer.quantityToBuy)
        assertEquals(Quantity.ZERO, answer.roundedUpBy)
    }

    @Test
    fun `loose goods are bought in halves, not in thousandths`() {
        // 2.0 demand + 0.4 margin - 0.1 stock = 2.3 kg needed; the next half is 2.5.
        val answer = recommend(stock = 0.1, demand = 2.0, unit = ProductUnits.KG)
        assertEquals(units(2.5), answer.quantityToBuy)
        assertEquals(units(0.2), answer.roundedUpBy)

        // Exactly 2.5 is already a multiple of the step.
        val exact = recommend(stock = 0.0, demand = 2.0, unit = ProductUnits.LITRE, percent = 25)
        assertEquals(units(2.5), exact.quantityToBuy)
        assertEquals(Quantity.ZERO, exact.roundedUpBy)
    }

    @Test
    fun `counted units are whole for pieces, packets, bottles, dozens and grams`() {
        listOf(ProductUnits.PIECE, ProductUnits.PACKET, ProductUnits.BOTTLE, ProductUnits.DOZEN, ProductUnits.GRAM).forEach {
            assertEquals(it, Quantity(1000), purchaseStep(it))
        }
        assertEquals(Quantity(500), purchaseStep(ProductUnits.KG))
        assertEquals(Quantity(500), purchaseStep(ProductUnits.LITRE))
    }

    @Test
    fun `a ledger below zero is counted as zero and says so, instead of ordering to fill a hole in the records`() {
        // The ledger reads -3 because goods were sold before they were recorded.
        val negative = recommend(stock = -3.0, demand = 10.0)
        val zero = recommend(stock = 0.0, demand = 10.0)

        assertTrue(negative.stockWasNegative)
        assertEquals(units(-3.0), negative.currentStock)
        assertEquals(Quantity.ZERO, negative.countedStock)
        // Same purchase as for an empty shelf — not 3 more.
        assertEquals(zero.quantityToBuy, negative.quantityToBuy)
        assertFalse(zero.stockWasNegative)
    }

    @Test
    fun `no safety margin means none is added`() {
        val answer = recommend(stock = 4.0, demand = 14.0, percent = 0)

        assertEquals(Quantity.ZERO, answer.safetyMargin)
        assertEquals(units(10.0), answer.quantityToBuy)
    }

    @Test
    fun `the margin is a whole percentage worked out in integers and rounded half up`() {
        // 14.5 x 5% = 0.725, which to one decimal place is 0.7.
        assertEquals(units(0.7), recommend(stock = 0.0, demand = 14.5, percent = 5).safetyMargin)
        // 15.0 x 5% = 0.75, which rounds half up to 0.8.
        assertEquals(units(0.8), recommend(stock = 0.0, demand = 15.0, percent = 5).safetyMargin)
    }

    @Test
    fun `expected demand is rounded to one decimal place before anything is worked out from it`() {
        // 20.503 and 20.550 are forecasts, not measurements. Half up to a tenth.
        assertEquals(units(20.5), recommendReorder(Quantity(0), Quantity(20_503), ProductUnits.PIECE).expectedDemand)
        assertEquals(units(20.6), recommendReorder(Quantity(0), Quantity(20_550), ProductUnits.PIECE).expectedDemand)
    }

    @Test
    fun `the sum holds for every combination in a grid, to the thousandth`() {
        val stocks = listOf(-5.0, 0.0, 0.25, 1.0, 4.0, 9.5, 13.0, 40.0)
        val demands = listOf(0.0, 0.4, 3.0, 14.0, 14.286, 27.5, 100.0)
        val percents = listOf(0, 5, 20, 33, 100)
        val allUnits = ProductUnits.ALL
        var checked = 0

        for (stock in stocks) {
            for (demand in demands) {
                for (percent in percents) {
                    for (unit in allUnits) {
                        val r = recommend(stock, demand, unit, percent)
                        val step = purchaseStep(unit).scaledUnits
                        val label = "stock $stock demand $demand $percent% $unit"
                        checked += 1

                        if (r.quantityToBuy.scaledUnits > 0) {
                            // The identity the explanation depends on.
                            assertEquals(
                                label,
                                r.expectedDemand + r.safetyMargin + r.roundedUpBy,
                                r.countedStock + r.quantityToBuy,
                            )
                            assertEquals(label, 0L, r.quantityToBuy.scaledUnits % step)
                            assertTrue(label, r.roundedUpBy.scaledUnits in 0 until step)
                        } else {
                            assertTrue(label, r.countedStock.scaledUnits >= r.expectedDemand.scaledUnits + r.safetyMargin.scaledUnits)
                        }
                        assertTrue(label, r.quantityToBuy.scaledUnits >= 0)
                    }
                }
            }
        }
        assertEquals(8 * 7 * 5 * 7, checked)
    }

    @Test
    fun `more stock never means buying more`() {
        var previous = Long.MAX_VALUE
        for (stock in listOf(0.0, 1.0, 2.5, 5.0, 8.0, 12.0, 20.0)) {
            val buy = recommend(stock = stock, demand = 14.0).quantityToBuy.scaledUnits
            assertTrue("stock $stock bought $buy after $previous", buy <= previous)
            previous = buy
        }
    }

    @Test
    fun `the lead time and margin the recommendation was made with travel with it`() {
        val answer = recommend(stock = 4.0, demand = 14.0, percent = 30, leadTimeDays = 10)
        assertEquals(10, answer.leadTimeDays)
        assertEquals(30, answer.safetyPercent)
    }

    @Test
    fun `settings outside a sensible range are refused`() {
        assertThrows(IllegalArgumentException::class.java) { ReorderSettings(leadTimeDays = 0) }
        assertThrows(IllegalArgumentException::class.java) { ReorderSettings(leadTimeDays = 61) }
        assertThrows(IllegalArgumentException::class.java) { ReorderSettings(safetyPercent = -1) }
        assertThrows(IllegalArgumentException::class.java) { ReorderSettings(safetyPercent = 201) }
    }

    @Test
    fun `demand cannot be negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            recommendReorder(units(1.0), Quantity(-1), ProductUnits.PIECE)
        }
    }

    @Test
    fun `a recommendation that does not add up cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReorderRecommendation(
                currentStock = units(4.0),
                countedStock = units(4.0),
                expectedDemand = units(14.0),
                safetyMargin = units(2.8),
                roundedUpBy = units(0.2),
                // 4 + 12 is not 14 + 2.8 + 0.2.
                quantityToBuy = units(12.0),
                leadTimeDays = 7,
                safetyPercent = 20,
            )
        }
    }
}
