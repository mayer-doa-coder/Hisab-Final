package com.hisab.app.ui.customer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.baki.BakiRepository
import com.hisab.app.data.customer.CustomerCreateResult
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.domain.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class CustomerListUiState(
    val query: String = "",
    val filter: CustomerFilter = CustomerFilter.ALL,
    /** What the list shows: the filter and the search applied, in the order to call people. */
    val rows: List<CustomerRow> = emptyList(),
    /** The whole shop, whatever the filter or search says. */
    val summary: CustomerSummary = CustomerSummary(Money.ZERO, owingCount = 0, overdueCount = 0),
    /** Every customer the shop has, so "no customers yet" can be told apart from "no match". */
    val customerCount: Int = 0,
    /** False until the first read comes back, so an empty screen never flashes "no customers" over real data. */
    val loaded: Boolean = false,
)

/**
 * The Customers list (Step 52).
 *
 * Both inputs are live queries — the customers and every baki entry — so a
 * payment recorded on the next screen changes what this emits, and the list
 * is right when the shopkeeper comes back with nothing to refresh (D001).
 * The sums are done off the main thread: a shop with hundreds of customers
 * should not cost a cheap phone a dropped frame on every keystroke of a search.
 */
class CustomerListViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val database = HisabDatabase.get(application)
    private val customers = CustomerRepository(database)
    private val baki = BakiRepository(database)

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(CustomerFilter.ALL)

    val uiState: StateFlow<CustomerListUiState> =
        combine(customers.observeAll(), baki.observeAll(), query, filter) { people, entries, text, chosen ->
            // Today is the phone's own day. It is read when the data changes, which
            // is often enough: a list left open across midnight is refreshed by the
            // next entry or by opening the screen again.
            val all = buildCustomerRows(people, entries, LocalDate.now(ZoneId.systemDefault()))
            CustomerListUiState(
                query = text,
                filter = chosen,
                rows = visibleRows(all, chosen, text),
                summary = summarize(all),
                customerCount = all.size,
                loaded = true,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = CustomerListUiState(),
            )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onFilterChange(value: CustomerFilter) {
        filter.value = value
    }

    /**
     * Adds a customer. Writes locally and answers — nothing waits on a network.
     * A name that is already taken is reported, not merged, so the screen can
     * say so and offer that person.
     */
    fun addCustomer(
        name: String,
        phone: String?,
        onResult: (CustomerCreateResult) -> Unit,
    ) {
        viewModelScope.launch { onResult(customers.create(name, phone)) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
