package com.hisab.app.ui.customer

import com.hisab.app.data.customer.CustomerEntity
import com.hisab.app.domain.BakiEntry
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.addCredit
import com.hisab.app.domain.receivePayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Step 52: what the Customers list shows, worked out from each customer's own entries. */
class CustomerRowsTest {
    private val at = Instant.parse("2026-09-25T10:00:00Z")
    private val today = LocalDate.of(2026, 9, 25)

    private fun customer(
        id: String,
        name: String,
        phone: String? = null,
    ) = CustomerEntity(id, "shop", name, phone, 1, at, null)

    private fun credit(
        who: String,
        poisha: Long,
        seconds: Long = 0,
        due: LocalDate? = null,
    ): BakiEntry = addCredit(EntityId(who), Money(poisha), at.plusSeconds(seconds), due)

    private fun payment(
        who: String,
        poisha: Long,
        seconds: Long = 0,
    ): BakiEntry = receivePayment(EntityId(who), Money(poisha), at.plusSeconds(seconds))

    private val rahim = customer("rahim", "Rahim Mia", "01711000000")
    private val karim = customer("karim", "karim uddin")
    private val salma = customer("salma", "সালমা বেগম", "01822000000")

    @Test
    fun `a balance is the sum of that customer's entries and nobody else's`() {
        val rows =
            buildCustomerRows(
                listOf(rahim, karim),
                listOf(credit("rahim", 50_000), payment("rahim", 20_000, 1), credit("karim", 7_000)),
                today,
            )

        assertEquals(Money(30_000), rows.first { it.customer.id == "rahim" }.balance)
        assertEquals(Money(7_000), rows.first { it.customer.id == "karim" }.balance)
    }

    @Test
    fun `the Step 56 numbers give exactly 400`() {
        // 500 in baki, 200 received, 100 more added.
        val rows =
            buildCustomerRows(
                listOf(rahim),
                listOf(credit("rahim", 50_000, 0), payment("rahim", 20_000, 1), credit("rahim", 10_000, 2)),
                today,
            )

        assertEquals(Money(40_000), rows.single().balance)
    }

    @Test
    fun `a customer with no entries owes nothing and is all clear`() {
        val row = buildCustomerRows(listOf(rahim), emptyList(), today).single()

        assertEquals(Money.ZERO, row.balance)
        assertEquals(BalanceStatus.CLEAR, row.status)
        assertFalse(row.isOverdue)
    }

    @Test
    fun `overpaying reads as an advance, not as owing`() {
        val row = buildCustomerRows(listOf(rahim), listOf(credit("rahim", 10_000), payment("rahim", 15_000, 1)), today).single()

        assertEquals(Money(-5_000), row.balance)
        assertEquals(BalanceStatus.ADVANCE, row.status)
    }

    @Test
    fun `overdue comes from the due dates and the payments made`() {
        val rows =
            buildCustomerRows(
                listOf(rahim, karim),
                listOf(
                    credit("rahim", 50_000, due = LocalDate.of(2026, 9, 1)),
                    payment("rahim", 20_000, 1),
                    credit("karim", 9_000, due = LocalDate.of(2026, 10, 1)),
                ),
                today,
            )

        assertEquals(Money(30_000), rows.first { it.customer.id == "rahim" }.overdue)
        assertTrue(rows.first { it.customer.id == "rahim" }.isOverdue)
        assertFalse("not due yet", rows.first { it.customer.id == "karim" }.isOverdue)
    }

    @Test
    fun `the summary adds up what is owed, and an advance does not cancel someone else's debt`() {
        val rows =
            buildCustomerRows(
                listOf(rahim, karim, salma),
                listOf(
                    credit("rahim", 30_000, due = LocalDate.of(2026, 9, 1)),
                    credit("karim", 10_000),
                    credit("salma", 5_000),
                    payment("salma", 9_000, 1),
                ),
                today,
            )
        val summary = summarize(rows)

        assertEquals("30000 + 10000, and salma's -4000 advance is not subtracted", Money(40_000), summary.totalOwed)
        assertEquals(2, summary.owingCount)
        assertEquals(1, summary.overdueCount)
    }

    @Test
    fun `an empty shop summarises to zero`() {
        assertEquals(CustomerSummary(Money.ZERO, 0, 0), summarize(emptyList()))
    }

    // --- filter, search, order ---

    private val rows =
        buildCustomerRows(
            listOf(rahim, karim, salma),
            listOf(
                credit("rahim", 30_000, due = LocalDate.of(2026, 9, 1)),
                credit("karim", 90_000),
                credit("salma", 5_000),
                payment("salma", 5_000, 1),
            ),
            today,
        )

    @Test
    fun `the owes filter keeps only people who owe something`() {
        assertEquals(setOf("rahim", "karim"), visibleRows(rows, CustomerFilter.OWES, "").map { it.customer.id }.toSet())
    }

    @Test
    fun `the overdue filter keeps only people past a due date`() {
        assertEquals(listOf("rahim"), visibleRows(rows, CustomerFilter.OVERDUE, "").map { it.customer.id })
    }

    @Test
    fun `everyone means everyone, including people who owe nothing`() {
        assertEquals(3, visibleRows(rows, CustomerFilter.ALL, "").size)
    }

    @Test
    fun `overdue people come first, then the biggest baki, then by name`() {
        // rahim is overdue (30000) so leads even though karim owes more (90000).
        assertEquals(listOf("rahim", "karim", "salma"), visibleRows(rows, CustomerFilter.ALL, "").map { it.customer.id })
    }

    @Test
    fun `equal balances are ordered by name ignoring case, so the order is the same on every phone`() {
        val a = customer("a", "beta")
        val b = customer("b", "Alpha")
        val even = buildCustomerRows(listOf(a, b), listOf(credit("a", 1_000), credit("b", 1_000)), today)

        assertEquals(listOf("b", "a"), visibleRows(even, CustomerFilter.ALL, "").map { it.customer.id })
    }

    @Test
    fun `search matches part of a name, ignoring case`() {
        assertEquals(listOf("karim"), visibleRows(rows, CustomerFilter.ALL, "UDDIN").map { it.customer.id })
    }

    @Test
    fun `search finds a Bangla name`() {
        assertEquals(listOf("salma"), visibleRows(rows, CustomerFilter.ALL, "সালমা").map { it.customer.id })
    }

    @Test
    fun `search finds a phone number, typed in Bangla digits too`() {
        assertEquals(listOf("rahim"), visibleRows(rows, CustomerFilter.ALL, "01711").map { it.customer.id })
        assertEquals(listOf("salma"), visibleRows(rows, CustomerFilter.ALL, "০১৮২২").map { it.customer.id })
    }

    @Test
    fun `search and filter work together`() {
        assertTrue(visibleRows(rows, CustomerFilter.OVERDUE, "karim").isEmpty())
    }

    @Test
    fun `a search with no match shows nobody`() {
        assertTrue(visibleRows(rows, CustomerFilter.ALL, "zzz").isEmpty())
    }

    @Test
    fun `surrounding spaces in a search are ignored`() {
        assertEquals(listOf("karim"), visibleRows(rows, CustomerFilter.ALL, "  karim ").map { it.customer.id })
    }

    @Test
    fun `balance status follows the sign`() {
        assertEquals(BalanceStatus.OWES, balanceStatus(Money(1)))
        assertEquals(BalanceStatus.CLEAR, balanceStatus(Money.ZERO))
        assertEquals(BalanceStatus.ADVANCE, balanceStatus(Money(-1)))
    }
}
