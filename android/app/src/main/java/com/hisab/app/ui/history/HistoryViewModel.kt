package com.hisab.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.sale.SaleEntity
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.data.stock.StockMovementEntity
import com.hisab.app.data.stock.StockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/** Which kinds of entry the list is showing. */
enum class HistoryFilter { ALL, SALES, STOCK }

/**
 * One line of history. Sales and stock movements are different things, so
 * they are different shapes — a sale has a total and a payment method, a
 * movement has a signed quantity and a reason — and the screen draws each
 * accordingly rather than flattening both into a lowest common row.
 */
sealed interface HistoryEntry {
    val occurredAt: Instant

    data class Sale(
        val sale: SaleEntity,
        val lineCount: Int,
        /** True once a reversal of this sale has been recorded (Step 43). */
        val reversed: Boolean,
    ) : HistoryEntry {
        override val occurredAt: Instant get() = sale.occurredAt
    }

    data class Movement(
        val movement: StockMovementEntity,
        val productName: String?,
    ) : HistoryEntry {
        override val occurredAt: Instant get() = movement.occurredAt
    }
}

data class HistoryUiState(
    val filter: HistoryFilter = HistoryFilter.ALL,
    val entries: List<HistoryEntry> = emptyList(),
    /** False until the first read comes back, so "nothing yet" isn't shown before anything is known. */
    val loaded: Boolean = false,
)

/**
 * The Transaction History screen (Step 42).
 *
 * Sales and stock movements live in two tables and are merged here, newest
 * first. Merging in Kotlin rather than with a UNION keeps both rows their own
 * shape, and the lists are capped at 100 each, so the sort is over a few
 * hundred items at most — cheap even on the reference phone (D028).
 *
 * A sale's own stock movement is left out by the query: the sale is already
 * in the list, and showing its movement beside it would be the same event
 * listed twice.
 */
class HistoryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val database = HisabDatabase.get(application)
    private val sales = SaleRepository(database)
    private val stock = StockRepository(database)

    private val filter = MutableStateFlow(HistoryFilter.ALL)

    val uiState: StateFlow<HistoryUiState> =
        combine(
            sales.observeRecent(),
            stock.observeRecentMovements(),
            filter,
        ) { saleRows, movementRows, currentFilter ->
            val entries =
                buildList {
                    if (currentFilter != HistoryFilter.STOCK) {
                        saleRows.forEach { add(HistoryEntry.Sale(it.sale, it.lineCount, it.reversed)) }
                    }
                    if (currentFilter != HistoryFilter.SALES) {
                        movementRows.forEach { add(HistoryEntry.Movement(it.movement, it.productName)) }
                    }
                }.sortedByDescending { it.occurredAt }

            HistoryUiState(filter = currentFilter, entries = entries, loaded = true)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = HistoryUiState(),
        )

    fun onFilterChange(value: HistoryFilter) {
        filter.value = value
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
