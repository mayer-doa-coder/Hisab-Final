package com.hisab.app.ui.customer

import com.hisab.app.data.customer.CustomerEntity
import com.hisab.app.domain.BakiEntry
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.calculateBalance
import com.hisab.app.domain.normalizeDigits
import com.hisab.app.domain.overdueAmount
import java.time.LocalDate

// What the Customers list shows, as plain functions (Step 52).
//
// Nothing here touches a screen or a database. A customer's balance and what is
// overdue are never read from a stored field — they are worked out from the
// customer's entries by `calculateBalance` and `overdueAmount` (D001, D041), so
// this file adds no rule of its own about money. It only arranges the answers.

/** Which customers the list shows. */
enum class CustomerFilter {
    ALL,

    /** Owes something right now. */
    OWES,

    /** Has money past its due date. */
    OVERDUE,
}

/** How a balance reads to a shopkeeper. Negative means the customer has paid in advance (D041). */
enum class BalanceStatus { OWES, CLEAR, ADVANCE }

fun balanceStatus(balance: Money): BalanceStatus =
    when {
        balance.minorUnits > 0 -> BalanceStatus.OWES
        balance.minorUnits < 0 -> BalanceStatus.ADVANCE
        else -> BalanceStatus.CLEAR
    }

data class CustomerRow(
    val customer: CustomerEntity,
    val balance: Money,
    /** The part of [balance] that is past its due date; zero when nothing is. */
    val overdue: Money,
) {
    val status: BalanceStatus get() = balanceStatus(balance)
    val isOverdue: Boolean get() = overdue.minorUnits > 0
}

/** The whole shop at a glance, for the card at the top of the list. */
data class CustomerSummary(
    /** What customers owe, added up. An advance from one customer does not reduce what another owes. */
    val totalOwed: Money,
    val owingCount: Int,
    val overdueCount: Int,
)

/**
 * One row per customer, each worked out from that customer's own entries.
 *
 * [entries] may hold everyone's; `calculateBalance` and `overdueAmount` count
 * only the customer they are asked about, so one person's baki cannot leak into
 * another's row. [today] is passed in because it is the shop's day and because
 * a rule that reads the clock cannot be tested (D041).
 */
fun buildCustomerRows(
    customers: List<CustomerEntity>,
    entries: List<BakiEntry>,
    today: LocalDate,
): List<CustomerRow> {
    val byCustomer = entries.groupBy { it.customerId }
    return customers.map { customer ->
        val id = EntityId(customer.id)
        val mine = byCustomer[id].orEmpty()
        CustomerRow(
            customer = customer,
            balance = calculateBalance(mine, id),
            overdue = overdueAmount(mine, id, today),
        )
    }
}

fun summarize(rows: List<CustomerRow>): CustomerSummary =
    CustomerSummary(
        totalOwed = Money(rows.sumOf { maxOf(it.balance.minorUnits, 0L) }),
        owingCount = rows.count { it.status == BalanceStatus.OWES },
        overdueCount = rows.count { it.isOverdue },
    )

/**
 * What the list shows: the chosen filter, then the search, then an order that
 * puts first whoever most needs a phone call.
 *
 * The order is overdue customers first, then by how much is owed, largest
 * first, then by name. A shopkeeper opens this list to decide whom to ask for
 * money, so the answer should not need scrolling or sorting.
 *
 * Search matches part of a name, ignoring case, or part of a phone number typed
 * in either digits.
 */
fun visibleRows(
    rows: List<CustomerRow>,
    filter: CustomerFilter,
    query: String,
): List<CustomerRow> {
    val text = query.trim()
    val digits = normalizeDigits(text)

    return rows
        .filter {
            when (filter) {
                CustomerFilter.ALL -> true
                CustomerFilter.OWES -> it.status == BalanceStatus.OWES
                CustomerFilter.OVERDUE -> it.isOverdue
            }
        }.filter {
            text.isEmpty() ||
                it.customer.name.contains(text, ignoreCase = true) ||
                (digits.isNotEmpty() && it.customer.phone?.contains(digits) == true)
        }.sortedWith(
            compareByDescending<CustomerRow> { it.isOverdue }
                .thenByDescending { it.balance.minorUnits }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.customer.name }
                .thenBy { it.customer.id },
        )
}
