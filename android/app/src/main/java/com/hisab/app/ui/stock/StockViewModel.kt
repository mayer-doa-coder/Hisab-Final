package com.hisab.app.ui.stock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.stock.ProductWithStock
import com.hisab.app.data.stock.StockRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Quantity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StockUiState(
    val query: String = "",
    val includeInactive: Boolean = false,
    val products: List<ProductWithStock> = emptyList(),
    /** False until the first read comes back, so "no products yet" isn't shown before anything is known. */
    val loaded: Boolean = false,
)

/**
 * The Stock screen (Step 41).
 *
 * Every number on the screen is a sum over the movement ledger, read as a
 * live query. That is what makes a restock appear immediately with nothing to
 * refresh: the row just inserted is part of the sum the screen is already
 * watching (D020).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StockViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = StockRepository(HisabDatabase.get(application))

    private val query = MutableStateFlow("")
    private val includeInactive = MutableStateFlow(false)

    val uiState: StateFlow<StockUiState> =
        combine(query, includeInactive) { currentQuery, showInactive -> currentQuery to showInactive }
            .flatMapLatest { (currentQuery, showInactive) ->
                repository.observeProductsWithStock(currentQuery, showInactive).map { products ->
                    StockUiState(
                        query = currentQuery,
                        includeInactive = showInactive,
                        products = products,
                        loaded = true,
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = StockUiState(),
            )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onIncludeInactiveChange(value: Boolean) {
        includeInactive.value = value
    }

    /**
     * Records goods arriving. Writes locally and returns — nothing waits on a
     * network, which is what makes restocking work in airplane mode.
     */
    fun restock(
        productId: String,
        quantity: Quantity,
        note: String,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            repository.restock(EntityId(productId), quantity, note)
            onDone()
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
