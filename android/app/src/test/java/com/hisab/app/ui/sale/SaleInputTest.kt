package com.hisab.app.ui.sale

import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.calculateCartTotal
import com.hisab.app.domain.calculateSaleTotal
import com.hisab.app.domain.completeCashSale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The parts of the New Sale screen that are plain functions, tested without a
 * phone (Steps 39–40).
 */
class SaleInputTest {
    @Test
    fun `a due date is optional`() {
        assertEquals(DueDate.None, parseDueDate(""))
        assertEquals(DueDate.None, parseDueDate("   "))
    }

    @Test
    fun `a due date is read as a plain ISO date`() {
        assertEquals(DueDate.On(LocalDate.of(2026, 10, 15)), parseDueDate("2026-10-15"))
    }

    @Test
    fun `a due date typed in Bangla digits is read the same way`() {
        assertEquals(DueDate.On(LocalDate.of(2026, 10, 15)), parseDueDate("২০২৬-১০-১৫"))
    }

    @Test
    fun `a due date typed as 8 plain digits is read the same way`() {
        // The field's keyboard is a numeric keypad, which has no "-" key on
        // stock Android/Samsung keyboards — this is the format it can
        // actually produce.
        assertEquals(DueDate.On(LocalDate.of(2026, 10, 15)), parseDueDate("20261015"))
    }

    @Test
    fun `8 plain digits typed in Bangla are read the same way`() {
        assertEquals(DueDate.On(LocalDate.of(2026, 10, 15)), parseDueDate("২০২৬১০১৫"))
    }

    @Test
    fun `a date that is not a date is refused, not guessed at`() {
        assertEquals(DueDate.Invalid, parseDueDate("15-10-2026"))
        assertEquals(DueDate.Invalid, parseDueDate("tomorrow"))
        assertEquals(DueDate.Invalid, parseDueDate("2026-13-01"))
        assertEquals(DueDate.Invalid, parseDueDate("20261301"))
        assertEquals(DueDate.Invalid, parseDueDate("2026101"))
    }

    @Test
    fun `one tap adds one piece, and half a kilo`() {
        assertEquals(Quantity(1000), quantityStepFor(ProductUnits.PIECE))
        assertEquals(Quantity(1000), quantityStepFor(ProductUnits.PACKET))
        assertEquals(Quantity(1000), quantityStepFor(ProductUnits.BOTTLE))
        assertEquals(Quantity(1000), quantityStepFor(ProductUnits.DOZEN))
        assertEquals(Quantity(500), quantityStepFor(ProductUnits.KG))
        assertEquals(Quantity(500), quantityStepFor(ProductUnits.LITRE))
    }

    @Test
    fun `an unknown unit still steps by one rather than by nothing`() {
        assertTrue(quantityStepFor("something-new").scaledUnits > 0)
    }

    // The number on the screen and the number written to the sale have to be
    // the same one. A shopkeeper who sees the total change on confirm has no
    // reason to trust either.
    @Test
    fun `the running total on screen equals the total the sale is saved with`() {
        val lines =
            listOf(
                SaleLine(EntityId("rice"), Quantity(1250), Money(9050)),
                SaleLine(EntityId("oil"), Quantity(500), Money(12_000)),
                SaleLine(EntityId("salt"), Quantity(333), Money(100)),
            )

        val onScreen = calculateCartTotal(lines)
        val saved = completeCashSale("shop-1", lines, Instant.parse("2026-09-16T10:00:00Z")).sale.total

        assertEquals(saved, onScreen)
        assertEquals(Money(17_346), onScreen)
    }

    @Test
    fun `an empty cart shows nothing owing, even though it cannot be confirmed`() {
        assertEquals(Money.ZERO, calculateCartTotal(emptyList()))
        assertEquals(Money.ZERO, calculateSaleTotal(emptyList()))
    }
}
