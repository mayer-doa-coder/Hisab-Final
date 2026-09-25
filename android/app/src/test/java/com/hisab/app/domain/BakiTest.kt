package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Step 48. The numbers shared with the backend are in [BakiFixtureTest]; this is what is specific to one function. */
class BakiTest {
    private val at = Instant.parse("2026-09-15T10:00:00Z")
    private val today = LocalDate.of(2026, 9, 15)
    private val rahim = EntityId("rahim")
    private val karim = EntityId("karim")

    // --- addCredit ---

    @Test
    fun `addCredit makes a positive entry of type CREDIT with no reference`() {
        val entry = addCredit(rahim, Money(50_000), at, LocalDate.of(2026, 10, 1), EntityId("e1"))

        assertEquals(EntityId("e1"), entry.id)
        assertEquals(rahim, entry.customerId)
        assertEquals(Money(50_000), entry.amountDelta)
        assertEquals(BakiEntryType.CREDIT, entry.type)
        assertNull("a hand-written entry names nothing — reference is never free text", entry.reference)
        assertEquals(LocalDate.of(2026, 10, 1), entry.dueDate)
    }

    @Test
    fun `an entry records when it happened and is not yet known to the server`() {
        val entry = addCredit(rahim, Money(100), at)

        assertEquals(at, entry.time.occurredAt)
        assertNull(entry.time.serverReceivedAt)
        assertFalse(entry.time.isSynced)
    }

    @Test
    fun `addCredit has no due date unless one is given`() {
        assertNull(addCredit(rahim, Money(100), at).dueDate)
    }

    @Test
    fun `addCredit refuses zero and negative amounts, and points at receivePayment`() {
        listOf(0L, -1L, -50_000L).forEach { poisha ->
            val error = assertThrows(IllegalArgumentException::class.java) { addCredit(rahim, Money(poisha), at) }
            assertTrue(error.message!!.contains("receivePayment"))
        }
    }

    @Test
    fun `each entry gets its own id`() {
        assertFalse(addCredit(rahim, Money(1), at).id == addCredit(rahim, Money(1), at).id)
    }

    // --- receivePayment ---

    @Test
    fun `receivePayment stores the payment as a negative amount of type PAYMENT`() {
        val entry = receivePayment(rahim, Money(20_000), at, EntityId("p1"))

        assertEquals(EntityId("p1"), entry.id)
        assertEquals(Money(-20_000), entry.amountDelta)
        assertEquals(BakiEntryType.PAYMENT, entry.type)
        assertNull(entry.reference)
        assertNull("a payment has no due date", entry.dueDate)
    }

    @Test
    fun `receivePayment refuses zero and negative amounts`() {
        listOf(0L, -1L, -20_000L).forEach { poisha ->
            assertThrows(IllegalArgumentException::class.java) { receivePayment(rahim, Money(poisha), at) }
        }
    }

    // --- reverseEntry ---

    @Test
    fun `reversing a credit writes the opposite entry, and references the original`() {
        val credit = addCredit(rahim, Money(50_000), at, LocalDate.of(2026, 10, 1))
        val undone = reverseEntry(credit, at.plusSeconds(60), EntityId("r1"))

        assertEquals(EntityId("r1"), undone.id)
        assertEquals(rahim, undone.customerId)
        assertEquals(Money(-50_000), undone.amountDelta)
        assertEquals(BakiEntryType.ENTRY_REVERSAL, undone.type)
        assertEquals(credit.id.value, undone.reference)
        assertNull("the undo carries no due date of its own", undone.dueDate)
        assertEquals(at.plusSeconds(60), undone.time.occurredAt)
    }

    @Test
    fun `reversing a payment writes a positive entry`() {
        val payment = receivePayment(rahim, Money(20_000), at)
        assertEquals(Money(20_000), reverseEntry(payment, at).amountDelta)
    }

    @Test
    fun `reversing leaves the original untouched`() {
        val credit = addCredit(rahim, Money(50_000), at)
        val snapshot = credit.copy()
        reverseEntry(credit, at)
        assertEquals("history is never edited", snapshot, credit)
    }

    @Test
    fun `an entry and its reversal cancel exactly`() {
        listOf(addCredit(rahim, Money(12_345), at), receivePayment(rahim, Money(6_789), at)).forEach { original ->
            val undone = reverseEntry(original, at)
            assertEquals(Money.ZERO, calculateBalance(listOf(original, undone), rahim))
        }
    }

    @Test
    fun `a credit sale's baki cannot be reversed on its own, it goes with its sale and stock`() {
        val sale = completeCreditSale("shop-1", rahim, listOf(SaleLine(EntityId("p"), Quantity(1000), Money(5_000))), at)

        val error = assertThrows(IllegalArgumentException::class.java) { reverseEntry(sale.bakiEntry!!, at) }
        assertTrue(error.message!!.contains("reverseSale"))
    }

    @Test
    fun `a reversal cannot be reversed, whether it came from a sale or from an entry`() {
        val sale = completeCreditSale("shop-1", rahim, listOf(SaleLine(EntityId("p"), Quantity(1000), Money(5_000))), at)
        val saleReversal = reverseSale(sale, at).bakiEntry!!
        val entryReversal = reverseEntry(addCredit(rahim, Money(100), at), at)

        assertThrows(IllegalArgumentException::class.java) { reverseEntry(saleReversal, at) }
        assertThrows(IllegalArgumentException::class.java) { reverseEntry(entryReversal, at) }
    }

    // --- calculateBalance ---

    @Test
    fun `a customer with no entries owes nothing`() {
        assertEquals(Money.ZERO, calculateBalance(emptyList(), rahim))
    }

    @Test
    fun `calculateBalance counts only the customer asked about`() {
        val entries = listOf(addCredit(rahim, Money(500), at), addCredit(karim, Money(70), at), receivePayment(karim, Money(20), at))

        assertEquals(Money(500), calculateBalance(entries, rahim))
        assertEquals(Money(50), calculateBalance(entries, karim))
    }

    @Test
    fun `the balance does not depend on the order entries are listed in`() {
        val entries = listOf(addCredit(rahim, Money(500), at), receivePayment(rahim, Money(200), at), addCredit(rahim, Money(100), at))
        assertEquals(calculateBalance(entries, rahim), calculateBalance(entries.reversed(), rahim))
    }

    @Test
    fun `the balance of a sale and its reversal is zero`() {
        val sale = completeCreditSale("shop-1", rahim, listOf(SaleLine(EntityId("p"), Quantity(2500), Money(7_800))), at)
        val undone = reverseSale(sale, at)
        assertEquals(Money.ZERO, calculateBalance(listOf(sale.bakiEntry!!, undone.bakiEntry!!), rahim))
    }

    // --- overdue ---

    @Test
    fun `nothing is overdue for a customer with no entries`() {
        assertFalse(isOverdue(emptyList(), rahim, today))
        assertEquals(Money.ZERO, overdueAmount(emptyList(), rahim, today))
    }

    @Test
    fun `overdue is decided on the day given, not on the clock`() {
        val entries = listOf(addCredit(rahim, Money(500), at, LocalDate.of(2026, 9, 20)))

        assertFalse(isOverdue(entries, rahim, LocalDate.of(2026, 9, 20)))
        assertTrue(isOverdue(entries, rahim, LocalDate.of(2026, 9, 21)))
    }

    @Test
    fun `only the customer asked about can be overdue`() {
        val entries = listOf(addCredit(rahim, Money(500), at, LocalDate.of(2026, 9, 1)), addCredit(karim, Money(500), at))

        assertTrue(isOverdue(entries, rahim, today))
        assertFalse(isOverdue(entries, karim, today))
    }

    @Test
    fun `entries recorded at the same moment are settled in the same order on every phone`() {
        val early = addCredit(rahim, Money(300), at, LocalDate.of(2026, 9, 1), EntityId("a"))
        val late = addCredit(rahim, Money(300), at, LocalDate.of(2026, 9, 30), EntityId("b"))
        val payment = receivePayment(rahim, Money(300), at.plusSeconds(1))

        // Same instant, so the id decides: "a" is settled first, and it is the overdue one.
        assertEquals(Money.ZERO, overdueAmount(listOf(early, late, payment), rahim, today))
        assertEquals(Money.ZERO, overdueAmount(listOf(payment, late, early), rahim, today))
    }

    @Test
    fun `overdue never asks for more than is owed`() {
        val ledger =
            listOf(
                addCredit(rahim, Money(50_000), at, LocalDate.of(2026, 9, 1)),
                addCredit(rahim, Money(30_000), at.plusSeconds(60), LocalDate.of(2026, 9, 5)),
                receivePayment(rahim, Money(10_000), at.plusSeconds(120)),
            )
        val overdue = overdueAmount(ledger, rahim, today)

        assertEquals(Money(70_000), overdue)
        assertTrue(overdue.minorUnits <= calculateBalance(ledger, rahim).minorUnits)
    }

    @Test
    fun `everything unpaid always equals the balance, whatever the mix of entries`() {
        // With every credit past due, what is overdue is exactly what is unpaid,
        // which must be the balance (or nothing, when the customer is ahead).
        // This ties the two rules together: they cannot disagree about the money.
        val sale =
            completeCreditSale(
                "shop-1",
                rahim,
                listOf(SaleLine(EntityId("p"), Quantity(1000), Money(40_000))),
                at,
                LocalDate.of(2026, 1, 1),
            )
        val credit = addCredit(rahim, Money(25_000), at.plusSeconds(60), LocalDate.of(2026, 2, 1))
        val mistake = addCredit(rahim, Money(9_000), at.plusSeconds(120), LocalDate.of(2026, 3, 1))
        val payment = receivePayment(rahim, Money(15_000), at.plusSeconds(180))
        val wrongPayment = receivePayment(rahim, Money(4_000), at.plusSeconds(240))

        val ledgers =
            listOf(
                listOf(sale.bakiEntry!!, credit),
                listOf(sale.bakiEntry!!, credit, payment),
                listOf(sale.bakiEntry!!, credit, mistake, reverseEntry(mistake, at.plusSeconds(300)), payment),
                listOf(sale.bakiEntry!!, credit, payment, wrongPayment, reverseEntry(wrongPayment, at.plusSeconds(300))),
                listOf(sale.bakiEntry!!, reverseSale(sale, at.plusSeconds(300)).bakiEntry!!, credit),
                listOf(credit, receivePayment(rahim, Money(99_000), at.plusSeconds(400))),
            )
        ledgers.forEach { ledger ->
            val balance = calculateBalance(ledger, rahim).minorUnits
            assertEquals("ledger of ${ledger.size} entries", maxOf(balance, 0L), overdueAmount(ledger, rahim, today).minorUnits)
        }
    }
}
