package com.hisab.app.ui.sale

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.data.product.ProductEntity
import com.hisab.app.data.product.sellingPrice
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.data.stock.ProductWithStock
import com.hisab.app.data.stock.StockRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.SalePayment
import com.hisab.app.domain.calculateCartTotal
import com.hisab.app.domain.calculateLineTotal
import com.hisab.app.domain.normalizeDigits
import com.hisab.app.domain.stockShortfall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * One line of the cart, before the sale is confirmed.
 *
 * `stockOnHand` is carried here only so the screen can warn when a line sells
 * more than the ledger knows about. It never stops the sale (D031).
 */
data class CartLine(
    val product: ProductEntity,
    val quantity: Quantity,
    val stockOnHand: Quantity,
) {
    val unitPrice: Money get() = product.sellingPrice

    val lineTotal: Money get() = calculateLineTotal(quantity, unitPrice)

    /** How much of this line is not covered by recorded stock. Zero when there is enough. */
    val shortfall: Quantity get() = stockShortfall(stockOnHand, quantity)

    val isShort: Boolean get() = shortfall != Quantity.ZERO
}

data class SaleUiState(
    val query: String = "",
    /** Every product that can be sold, each with its current stock. */
    val available: List<ProductWithStock> = emptyList(),
    val lines: List<CartLine> = emptyList(),
    val payment: SalePayment = SalePayment.CASH,
    val customerName: String = "",
    val dueDateText: String = "",
    val customerError: Boolean = false,
    val dueDateError: Boolean = false,
    /**
     * How the last sale was paid, set once it is written and cleared as soon
     * as the next item is added. This is the only confirmation a shopkeeper
     * gets that the sale is really on the device, so it is state rather than a
     * one-shot event — a message that vanishes on a language switch or a
     * rotation would be no confirmation at all.
     */
    val justSaved: SalePayment? = null,
    /** False until the first read comes back, so "add a product first" isn't shown before anything is known. */
    val loaded: Boolean = false,
) {
    /** The running total, by the same rule the saved sale will use (D032). */
    val total: Money get() = calculateCartTotal(lines.map { SaleLine(EntityId(it.product.id), it.quantity, it.unitPrice) })

    val isEmpty: Boolean get() = lines.isEmpty()

    val shortLines: List<CartLine> get() = lines.filter { it.isShort }

    val canConfirm: Boolean get() = lines.isNotEmpty()
}

/**
 * The New Sale screen's state and the one write it performs (Steps 39–40).
 *
 * The cart lives here rather than in the screen because the Activity is
 * recreated on every language switch (D014) — a half-built cart must not
 * disappear because someone tapped "English" mid-sale.
 *
 * The arithmetic is not here. Line totals, the sale total, the stock
 * movements and the baki entry all come from `domain/Sale.kt`, which is
 * tested without a phone. This class only decides what is in the cart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaleViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val database = HisabDatabase.get(application)
    private val sales = SaleRepository(database)
    private val stock = StockRepository(database)
    private val customers = CustomerRepository(database)

    private val query = MutableStateFlow("")

    /** productId -> how much of it is in the cart. */
    private val cart = MutableStateFlow<Map<String, Quantity>>(emptyMap())
    private val payment = MutableStateFlow(SalePayment.CASH)
    private val customerName = MutableStateFlow("")
    private val dueDateText = MutableStateFlow("")
    private val customerError = MutableStateFlow(false)
    private val dueDateError = MutableStateFlow(false)
    private val justSaved = MutableStateFlow<SalePayment?>(null)

    private val errors = combine(customerError, dueDateError) { nameBad, dueBad -> nameBad to dueBad }

    private val form =
        combine(payment, customerName, dueDateText, errors, justSaved) { currentPayment, name, due, bad, saved ->
            FormState(currentPayment, name, due, bad.first, bad.second, saved)
        }

    val uiState: StateFlow<SaleUiState> =
        query
            .flatMapLatest { currentQuery -> stock.observeProductsWithStock(currentQuery) }
            .combine(cart) { available, lines -> available to lines }
            .combine(query) { (available, lines), currentQuery -> Triple(available, lines, currentQuery) }
            .combine(form) { (available, lines, currentQuery), formState ->
                SaleUiState(
                    query = currentQuery,
                    available = available,
                    lines = toCartLines(available, lines),
                    payment = formState.payment,
                    customerName = formState.customerName,
                    dueDateText = formState.dueDateText,
                    customerError = formState.customerError,
                    dueDateError = formState.dueDateError,
                    justSaved = formState.justSaved,
                    loaded = true,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = SaleUiState(),
            )

    /**
     * The cart, in the order products were added, with each line's current
     * stock attached. A product deleted while it sat in the cart drops out
     * rather than being sold as a ghost.
     */
    private fun toCartLines(
        available: List<ProductWithStock>,
        quantities: Map<String, Quantity>,
    ): List<CartLine> {
        val byId = available.associateBy { it.product.id }
        return quantities.mapNotNull { (productId, quantity) ->
            byId[productId]?.let { CartLine(it.product, quantity, Quantity(it.stockScaled)) }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Tapping a product adds one of it, or one more if it is already in the cart. */
    fun addOne(
        productId: String,
        unit: String,
    ) {
        justSaved.value = null
        val step = quantityStepFor(unit)
        cart.value =
            cart.value.toMutableMap().apply {
                this[productId] = (this[productId] ?: Quantity.ZERO) + step
            }
    }

    /** One step down. Reaching zero takes the line out rather than leaving an empty one. */
    fun removeOne(
        productId: String,
        unit: String,
    ) {
        val step = quantityStepFor(unit)
        val current = cart.value[productId] ?: return
        val next = current - step
        setQuantity(productId, next)
    }

    fun setQuantity(
        productId: String,
        quantity: Quantity,
    ) {
        cart.value =
            cart.value.toMutableMap().apply {
                if (quantity.scaledUnits <= 0) remove(productId) else this[productId] = quantity
            }
    }

    fun removeLine(productId: String) {
        cart.value = cart.value - productId
    }

    fun onPaymentChange(value: SalePayment) {
        payment.value = value
        if (value == SalePayment.CASH) {
            customerError.value = false
            dueDateError.value = false
        }
    }

    fun onCustomerNameChange(value: String) {
        customerName.value = value
        customerError.value = false
    }

    fun onDueDateChange(value: String) {
        dueDateText.value = value
        dueDateError.value = false
    }

    /**
     * Saves the sale and empties the cart. `onSaved` runs only after the
     * write has finished, so the confirmation a shopkeeper sees means the
     * sale is actually on the device.
     *
     * Nothing waits on a network: the whole path is local (Step 39's check).
     */
    fun confirm(onSaved: (SalePayment) -> Unit) {
        val state = uiState.value
        if (!state.canConfirm) return

        val lines = state.lines.map { SaleLine(EntityId(it.product.id), it.quantity, it.unitPrice) }

        if (state.payment == SalePayment.CASH) {
            viewModelScope.launch {
                sales.recordCashSale(lines)
                clearCart()
                justSaved.value = SalePayment.CASH
                onSaved(SalePayment.CASH)
            }
            return
        }

        val name = state.customerName.trim()
        if (name.isEmpty()) {
            customerError.value = true
            return
        }

        val due =
            when (val parsed = parseDueDate(state.dueDateText)) {
                is DueDate.Invalid -> {
                    dueDateError.value = true
                    return
                }

                is DueDate.None -> {
                    null
                }

                is DueDate.On -> {
                    parsed.date
                }
            }

        viewModelScope.launch {
            val customer = customers.findOrCreate(name)
            sales.recordCreditSale(EntityId(customer.id), lines, due)
            clearCart()
            justSaved.value = SalePayment.CREDIT
            onSaved(SalePayment.CREDIT)
        }
    }

    private fun clearCart() {
        cart.value = emptyMap()
        customerName.value = ""
        dueDateText.value = ""
        customerError.value = false
        dueDateError.value = false
        payment.value = SalePayment.CASH
        query.value = ""
    }

    private data class FormState(
        val payment: SalePayment,
        val customerName: String,
        val dueDateText: String,
        val customerError: Boolean,
        val dueDateError: Boolean,
        val justSaved: SalePayment?,
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** What one tap of + or − is worth, which depends on how the product is sold. */
fun quantityStepFor(unit: String): Quantity =
    when (unit) {
        com.hisab.app.domain.ProductUnits.KG,
        com.hisab.app.domain.ProductUnits.LITRE,
        -> Quantity(500)

        // half a kilo or half a litre, the usual shop step

        else -> Quantity(1000) // one piece, packet, bottle, dozen or gram
    }

sealed interface DueDate {
    data object None : DueDate

    data object Invalid : DueDate

    data class On(
        val date: LocalDate,
    ) : DueDate
}

/**
 * Reads a due date a shopkeeper typed. Bangla digits are accepted as readily
 * as ASCII ones, and blank means "no date", which is allowed — PRD section 10
 * makes the due date optional.
 *
 * Two shapes are accepted: `yyyy-MM-dd`, and 8 plain digits (`yyyyMMdd`) with
 * no separators. The plain-digit form exists because the field's keyboard is
 * a numeric keypad, which does not offer a "-" key on stock Android/Samsung
 * keyboards — without it, the hyphenated form the field's own hint shows
 * could never actually be typed on the phone.
 */
fun parseDueDate(input: String): DueDate {
    val normalized = normalizeDigits(input).trim()
    if (normalized.isEmpty()) return DueDate.None
    if (normalized.length == 8 && normalized.all(Char::isDigit)) {
        return try {
            DueDate.On(
                LocalDate.of(
                    normalized.substring(0, 4).toInt(),
                    normalized.substring(4, 6).toInt(),
                    normalized.substring(6, 8).toInt(),
                ),
            )
        } catch (_: DateTimeException) {
            DueDate.Invalid
        }
    }
    return try {
        DueDate.On(LocalDate.parse(normalized))
    } catch (_: DateTimeParseException) {
        DueDate.Invalid
    }
}
