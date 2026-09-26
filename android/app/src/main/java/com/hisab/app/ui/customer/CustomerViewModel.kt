package com.hisab.app.ui.customer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.baki.BakiRepository
import com.hisab.app.data.baki.BakiReverseResult
import com.hisab.app.data.customer.CustomerEntity
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.calculateBalance
import com.hisab.app.domain.overdueAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class CustomerUiState(
    val customer: CustomerEntity? = null,
    /** Newest first, each with the balance after it. */
    val lines: List<LedgerLine> = emptyList(),
    /** The sum of the ledger — the same number the last line ends on. */
    val balance: Money = Money.ZERO,
    val overdue: Money = Money.ZERO,
    /** False until the first read comes back, so "not found" is never shown before anything is known. */
    val loaded: Boolean = false,
) {
    val isOverdue: Boolean get() = overdue.minorUnits > 0
    val status: BalanceStatus get() = balanceStatus(balance)
}

/**
 * One customer, and the two ways their baki changes by hand (Steps 53–55).
 *
 * This one instance serves the customer's own screen and both forms, so the
 * balance a form previews ("owes now → will owe") is the very number the screen
 * behind it shows, read from the same live query. Nothing is copied into the
 * form, so it cannot go stale while the shopkeeper types.
 *
 * Saving writes locally and answers. It never waits on a network, which is what
 * makes both forms work in airplane mode (Steps 54 and 55), and the new entry
 * changes the sum the screen already watches, so the balance updates at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val database = HisabDatabase.get(application)
    private val customers = CustomerRepository(database)
    private val baki = BakiRepository(database)

    private val customerId = MutableStateFlow<String?>(null)

    /** True while a write is in flight, so a double tap on Save cannot record it twice. */
    private var saving = false

    val uiState: StateFlow<CustomerUiState> =
        customerId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(CustomerUiState())
                } else {
                    val who = EntityId(id)
                    combine(customers.observeById(who), baki.observeForCustomer(who)) { customer, entries ->
                        CustomerUiState(
                            customer = customer,
                            lines = buildLedger(entries),
                            balance = calculateBalance(entries, who),
                            overdue = overdueAmount(entries, who, LocalDate.now(ZoneId.systemDefault())),
                            loaded = true,
                        )
                    }.onStart {
                        // Opening a different customer must not show the last one for a moment.
                        emit(CustomerUiState())
                    }
                }
            }.flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = CustomerUiState(),
            )

    /** Shows this customer. Opening the same one again changes nothing. */
    fun open(id: String) {
        customerId.value = id
    }

    /** The customer owes [amount] more, with no sale behind it. [onSaved] runs once the entry is stored. */
    fun addCredit(
        amount: Money,
        dueDate: LocalDate?,
        onSaved: () -> Unit,
    ) {
        val id = customerId.value ?: return
        save(onSaved) { baki.addCredit(EntityId(id), amount, dueDate) }
    }

    /** The customer paid [amount] back. [onSaved] runs once the entry is stored. */
    fun receivePayment(
        amount: Money,
        onSaved: () -> Unit,
    ) {
        val id = customerId.value ?: return
        save(onSaved) { baki.receivePayment(EntityId(id), amount) }
    }

    /**
     * Undoes a hand-written credit or payment (Step 57). Writes locally and
     * answers; the repository decides whether it can be undone, from stored
     * rows, in the same transaction as the write.
     */
    fun undo(
        entryId: String,
        onResult: (BakiReverseResult) -> Unit,
    ) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                onResult(baki.reverse(EntityId(entryId)))
            } finally {
                saving = false
            }
        }
    }

    private fun save(
        onSaved: () -> Unit,
        write: suspend () -> Unit,
    ) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                write()
                onSaved()
            } finally {
                saving = false
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
