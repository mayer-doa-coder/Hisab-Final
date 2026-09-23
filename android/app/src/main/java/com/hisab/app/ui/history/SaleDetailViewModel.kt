package com.hisab.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.sale.ReversalResult
import com.hisab.app.data.sale.SaleDetail
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.domain.EntityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The outcome of the last reversal attempt, shown on the screen until it is left. */
enum class ReversalMessage { REVERSED, REVERSED_CREDIT, ALREADY_REVERSED, NOT_FOUND }

data class SaleDetailUiState(
    val detail: SaleDetail? = null,
    /** False until the first read comes back, so "not found" isn't shown before anything is known. */
    val loaded: Boolean = false,
    /** True while a reversal is being written, so a second tap cannot start a second one. */
    val reversing: Boolean = false,
    val message: ReversalMessage? = null,
)

/**
 * One sale, and undoing it (Step 43).
 *
 * The reversal itself is `SaleRepository.reverse`: one transaction that
 * checks the sale has not been undone already, writes its opposite, and
 * queues it for sync. This class only asks for it and shows the answer.
 */
class SaleDetailViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val sales = SaleRepository(HisabDatabase.get(application))

    private val state = MutableStateFlow(SaleDetailUiState())
    val uiState: StateFlow<SaleDetailUiState> = state.asStateFlow()

    private var saleId: String? = null

    /** Loads a sale. Opening a different sale clears the last one's message. */
    fun open(id: String) {
        if (id != saleId) {
            saleId = id
            state.value = SaleDetailUiState()
        }
        viewModelScope.launch { reload(id) }
    }

    fun reverse() {
        val id = saleId ?: return
        if (state.value.reversing) return
        state.value = state.value.copy(reversing = true)

        viewModelScope.launch {
            val message =
                when (val result = sales.reverse(EntityId(id))) {
                    is ReversalResult.Reversed -> {
                        if (result.transaction.bakiEntry != null) ReversalMessage.REVERSED_CREDIT else ReversalMessage.REVERSED
                    }

                    ReversalResult.AlreadyReversed -> {
                        ReversalMessage.ALREADY_REVERSED
                    }

                    // The screen never offers to reverse a reversal; if it is
                    // asked anyway, the honest answer is that there is nothing
                    // to undo here.
                    ReversalResult.IsAReversal -> {
                        ReversalMessage.ALREADY_REVERSED
                    }

                    ReversalResult.NotFound -> {
                        ReversalMessage.NOT_FOUND
                    }
                }
            reload(id)
            state.value = state.value.copy(reversing = false, message = message)
        }
    }

    private suspend fun reload(id: String) {
        val detail = sales.detail(EntityId(id))
        state.value = state.value.copy(detail = detail, loaded = true)
    }
}
