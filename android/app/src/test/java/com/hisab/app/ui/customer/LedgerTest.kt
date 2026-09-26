package com.hisab.app.ui.customer

import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.addCredit
import com.hisab.app.domain.calculateBalance
import com.hisab.app.domain.completeCreditSale
import com.hisab.app.domain.receivePayment
import com.hisab.app.domain.reverseEntry
import com.hisab.app.domain.reverseSale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Steps 53-55: the ledger as shown, and the arithmetic the two forms preview. */
class LedgerTest {
    private val at = Instant.parse("2026-09-25T10:00:00Z")
    private val rahim = EntityId("rahim")

    private val first = addCredit(rahim, Money(50_000), at, id = EntityId("a"))
    private val second = receivePayment(rahim, Money(20_000), at.plusSeconds(60), id = EntityId("b"))
    private val third = addCredit(rahim, Money(10_000), at.plusSeconds(120), id = EntityId("c"))

    @Test
    fun `the ledger is newest first and each line ends on the balance after it`() {
        val lines = buildLedger(listOf(first, second, third))

        assertEquals(listOf("c", "b", "a"), lines.map { it.entry.id.value })
        assertEquals(listOf(40_000L, 30_000L, 50_000L), lines.map { it.balanceAfter.minorUnits })
    }

    @Test
    fun `the top line's balance is the balance, so the two places on screen cannot disagree`() {
        val entries = listOf(first, second, third, reverseEntry(second, at.plusSeconds(180)))
        val lines = buildLedger(entries)

        assertEquals(calculateBalance(entries, rahim), lines.first().balanceAfter)
        assertEquals(calculateBalance(entries, rahim), ledgerBalance(lines))
    }

    @Test
    fun `the order the entries arrive in does not change the ledger`() {
        val expected = buildLedger(listOf(first, second, third))

        assertEquals(expected, buildLedger(listOf(third, first, second)))
        assertEquals(expected, buildLedger(listOf(second, third, first)))
    }

    @Test
    fun `entries at the same moment are ordered by id on every phone`() {
        val x = addCredit(rahim, Money(100), at, id = EntityId("x"))
        val y = addCredit(rahim, Money(200), at, id = EntityId("y"))

        assertEquals(listOf("y", "x"), buildLedger(listOf(x, y)).map { it.entry.id.value })
        assertEquals(listOf("y", "x"), buildLedger(listOf(y, x)).map { it.entry.id.value })
    }

    @Test
    fun `an empty ledger is empty and owes nothing`() {
        assertTrue(buildLedger(emptyList()).isEmpty())
        assertEquals(Money.ZERO, ledgerBalance(emptyList()))
    }

    @Test
    fun `the running balance can go negative when someone overpays`() {
        val lines = buildLedger(listOf(first, receivePayment(rahim, Money(60_000), at.plusSeconds(60))))

        assertEquals(-10_000L, lines.first().balanceAfter.minorUnits)
    }

    // --- amount typed into a form ---

    @Test
    fun `an amount is read in either digits, with thousands commas`() {
        assertEquals(Money(50_000), parseBakiAmount("500"))
        assertEquals(Money(1_250), parseBakiAmount("12.50"))
        assertEquals(Money(50_000), parseBakiAmount("৫০০"))
        assertEquals(Money(100_000), parseBakiAmount("1,000"))
    }

    @Test
    fun `zero, negative, empty and non-numbers are refused, never guessed at`() {
        listOf("0", "0.00", "-5", "", "  ", "abc", "5 taka", "1.234").forEach {
            assertNull("'$it' should be refused", parseBakiAmount(it))
        }
    }

    @Test
    fun `adding baki previews the balance after it`() {
        assertEquals(Money(60_000), balanceAfterCredit(Money(50_000), Money(10_000)))
        assertEquals(Money(10_000), balanceAfterCredit(Money.ZERO, Money(10_000)))
    }

    @Test
    fun `a payment previews the balance after it, which can be negative`() {
        assertEquals(Money(30_000), balanceAfterPayment(Money(50_000), Money(20_000)))
        assertEquals(Money(-5_000), balanceAfterPayment(Money(10_000), Money(15_000)))
    }

    @Test
    fun `there is no preview until the amount is valid`() {
        assertNull(balanceAfterCredit(Money(50_000), null))
        assertNull(balanceAfterPayment(Money(50_000), null))
    }

    @Test
    fun `a payment above what is owed is an overpayment, including from someone who owes nothing`() {
        assertTrue(isOverpayment(Money(50_000), Money(60_000)))
        assertTrue(isOverpayment(Money.ZERO, Money(1)))
        assertTrue(isOverpayment(Money(-2_000), Money(1)))
    }

    @Test
    fun `paying exactly what is owed, or less, is not an overpayment`() {
        assertFalse(isOverpayment(Money(50_000), Money(50_000)))
        assertFalse(isOverpayment(Money(50_000), Money(1)))
        assertFalse("nothing typed yet", isOverpayment(Money(50_000), null))
    }

    // --- Step 57: which lines can be undone, and what the balance becomes ---

    @Test
    fun aHandWrittenCreditAndPaymentCanBeUndone() {
        val lines = buildLedger(listOf(first, second))

        assertTrue(lines.all { it.canUndo })
        assertTrue(lines.none { it.undone })
    }

    @Test
    fun anEntryThatHasBeenUndoneIsMarkedAndCannotBeUndoneAgain() {
        val undo = reverseEntry(second, at.plusSeconds(180))
        val lines = buildLedger(listOf(first, second, third, undo)).associateBy { it.entry.id.value }

        assertTrue(lines.getValue("b").undone)
        assertFalse("undone already, so no second undo", lines.getValue("b").canUndo)
        assertTrue("the others are untouched", lines.getValue("a").canUndo && lines.getValue("c").canUndo)
    }

    @Test
    fun theUndoEntryItselfCannotBeUndone() {
        val undo = reverseEntry(second, at.plusSeconds(180))
        val line = buildLedger(listOf(second, undo)).first { it.entry.id == undo.id }

        assertEquals(BakiEntryType.ENTRY_REVERSAL, line.entry.type)
        assertFalse(line.canUndo)
    }

    @Test
    fun aCreditSalesBakiIsUndoneWithItsSaleNotFromHere() {
        val sale = completeCreditSale("shop", rahim, listOf(SaleLine(EntityId("p"), Quantity(1000), Money(5_000))), at)
        val saleUndone = reverseSale(sale, at.plusSeconds(60))
        val lines = buildLedger(listOf(sale.bakiEntry!!, saleUndone.bakiEntry!!))

        assertTrue("neither the sale's baki nor its reversal offers an undo", lines.none { it.canUndo })
    }

    @Test
    fun undoingACreditLowersWhatIsOwedAndUndoingAPaymentRaisesIt() {
        assertEquals(Money(40_000), balanceAfterUndo(Money(50_000), third))
        assertEquals(Money(50_000), balanceAfterUndo(Money(30_000), second))
    }

    @Test
    fun theBalanceShownBeforeConfirmingIsTheBalanceTheOppositeEntryProduces() {
        val entries = listOf(first, second, third)
        val now = calculateBalance(entries, rahim)

        listOf(first, second, third).forEach { target ->
            val predicted = balanceAfterUndo(now, target)
            val actual = calculateBalance(entries + reverseEntry(target, at.plusSeconds(300)), rahim)

            assertEquals("${target.type}", actual, predicted)
        }
    }
}
