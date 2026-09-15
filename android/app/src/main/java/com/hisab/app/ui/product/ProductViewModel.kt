package com.hisab.app.ui.product

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductEntity
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.product.ProductWriteResult
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.ProductFormResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProductListUiState(
    val query: String = "",
    val includeInactive: Boolean = false,
    val products: List<ProductEntity> = emptyList(),
    /** False until the first read comes back, so "no products yet" isn't shown before anything is known. */
    val loaded: Boolean = false,
)

/**
 * Holds the product list and performs writes. A ViewModel rather than screen
 * state because the Activity is recreated on every language switch (D014),
 * and a half-finished save must not be cancelled by that.
 *
 * The list itself is never held as a copy: it comes straight from the
 * database as a Flow, so a local write appears on screen by itself, with
 * nothing to refresh and nothing to wait for (Step 26).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = ProductRepository(HisabDatabase.get(application))

    private val query = MutableStateFlow("")
    private val includeInactive = MutableStateFlow(false)

    val uiState: StateFlow<ProductListUiState> =
        combine(query, includeInactive) { currentQuery, showInactive -> currentQuery to showInactive }
            .flatMapLatest { (currentQuery, showInactive) ->
                repository.observe(currentQuery, showInactive).map { products ->
                    ProductListUiState(
                        query = currentQuery,
                        includeInactive = showInactive,
                        products = products,
                        loaded = true,
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ProductListUiState(),
            )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onIncludeInactiveChange(value: Boolean) {
        includeInactive.value = value
    }

    suspend fun byId(id: String): ProductEntity? = repository.byId(EntityId(id))

    fun create(
        valid: ProductFormResult.Valid,
        onResult: (ProductWriteResult) -> Unit,
    ) {
        viewModelScope.launch { onResult(repository.create(valid.toDraft())) }
    }

    fun update(
        id: String,
        baseRevision: Int,
        valid: ProductFormResult.Valid,
        onResult: (ProductWriteResult) -> Unit,
    ) {
        viewModelScope.launch { onResult(repository.update(EntityId(id), baseRevision, valid.toDraft())) }
    }

    fun setActive(
        id: String,
        baseRevision: Int,
        active: Boolean,
        onResult: (ProductWriteResult) -> Unit,
    ) {
        viewModelScope.launch { onResult(repository.setActive(EntityId(id), baseRevision, active)) }
    }

    fun delete(
        id: String,
        baseRevision: Int,
        onResult: (ProductWriteResult) -> Unit,
    ) {
        viewModelScope.launch { onResult(repository.delete(EntityId(id), baseRevision)) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private fun ProductFormResult.Valid.toDraft(): ProductDraft =
    ProductDraft(
        name = name,
        aliases = aliases,
        unit = unit,
        purchasePrice = purchasePrice,
        sellingPrice = sellingPrice,
        active = active,
    )
