package com.hisab.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hisab.app.AppLanguage
import com.hisab.app.R
import com.hisab.app.data.baki.BakiReverseResult
import com.hisab.app.data.product.ProductEntity
import com.hisab.app.data.product.ProductWriteResult
import com.hisab.app.domain.Money
import com.hisab.app.ui.customer.BakiFormKind
import com.hisab.app.ui.customer.BakiFormScreen
import com.hisab.app.ui.customer.BakiNotice
import com.hisab.app.ui.customer.BakiNoticeKind
import com.hisab.app.ui.customer.CustomerDetailScreen
import com.hisab.app.ui.customer.CustomerListScreen
import com.hisab.app.ui.customer.CustomerListViewModel
import com.hisab.app.ui.customer.CustomerViewModel
import com.hisab.app.ui.history.HistoryScreen
import com.hisab.app.ui.history.HistoryViewModel
import com.hisab.app.ui.history.SaleDetailScreen
import com.hisab.app.ui.history.SaleDetailViewModel
import com.hisab.app.ui.product.ProductEditScreen
import com.hisab.app.ui.product.ProductListScreen
import com.hisab.app.ui.product.ProductViewModel
import com.hisab.app.ui.sale.NewSaleScreen
import com.hisab.app.ui.sale.SaleViewModel
import com.hisab.app.ui.stock.StockScreen
import com.hisab.app.ui.stock.StockViewModel
import com.hisab.app.ui.sync.SyncScreen
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.HisabTheme
import kotlin.math.absoluteValue

private const val ROUTE_HOME = "home"
private const val ROUTE_PRODUCTS = "products"
private const val ROUTE_PRODUCT_FORM = "product_form"
private const val ROUTE_SALE = "sale"
private const val ROUTE_STOCK = "stock"
private const val ROUTE_HISTORY = "history"
private const val ROUTE_SALE_DETAIL = "sale_detail"
private const val ROUTE_SYNC = "sync"
private const val ROUTE_CUSTOMERS = "customers"
private const val ROUTE_CUSTOMER = "customer"
private const val ROUTE_BAKI_ADD = "baki_add"
private const val ROUTE_BAKI_PAY = "baki_pay"

/**
 * Which screen is showing. Kept as a saved string rather than a navigation
 * library: the screens are a flat list reached from Home, and adding a
 * library for that would be structure ahead of need (CLAUDE.md). The id of
 * the product being edited is saved too, so the form survives the Activity
 * being recreated — which happens on every language switch (D014).
 */
@Composable
fun HisabApp(
    language: AppLanguage,
    onChangeLanguage: (AppLanguage) -> Unit,
) {
    HisabTheme(language) {
        val viewModel: ProductViewModel = viewModel()
        val state by viewModel.uiState.collectAsState()
        var route by rememberSaveable { mutableStateOf(ROUTE_HOME) }
        var editingId by rememberSaveable { mutableStateOf<String?>(null) }
        var openSaleId by rememberSaveable { mutableStateOf<String?>(null) }
        var openCustomerId by rememberSaveable { mutableStateOf<String?>(null) }
        // What was last recorded against the open customer, so their screen can
        // say so. Saved state rather than a timed message, so it survives a
        // rotation or a language switch, and it clears when they leave or start
        // another entry.
        var noticeKind by rememberSaveable { mutableStateOf<String?>(null) }
        var noticeAmount by rememberSaveable { mutableLongStateOf(0L) }
        var showConflict by rememberSaveable { mutableStateOf(false) }

        when (route) {
            ROUTE_PRODUCTS -> {
                BackHandler { route = ROUTE_HOME }
                ProductListScreen(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onIncludeInactiveChange = viewModel::onIncludeInactiveChange,
                    onAddProduct = {
                        editingId = null
                        showConflict = false
                        route = ROUTE_PRODUCT_FORM
                    },
                    onOpenProduct = { product ->
                        editingId = product.id
                        showConflict = false
                        route = ROUTE_PRODUCT_FORM
                    },
                    onBack = { route = ROUTE_HOME },
                )
            }

            ROUTE_PRODUCT_FORM -> {
                BackHandler { route = ROUTE_PRODUCTS }
                ProductFormRoute(
                    viewModel = viewModel,
                    editingId = editingId,
                    showConflict = showConflict,
                    onConflict = { showConflict = true },
                    onDone = { route = ROUTE_PRODUCTS },
                )
            }

            ROUTE_SALE -> {
                BackHandler { route = ROUTE_HOME }
                NewSaleRoute(onBack = { route = ROUTE_HOME })
            }

            ROUTE_STOCK -> {
                BackHandler { route = ROUTE_HOME }
                StockRoute(onBack = { route = ROUTE_HOME })
            }

            ROUTE_HISTORY -> {
                BackHandler { route = ROUTE_HOME }
                HistoryRoute(
                    onOpenSale = { saleId ->
                        openSaleId = saleId
                        route = ROUTE_SALE_DETAIL
                    },
                    onBack = { route = ROUTE_HOME },
                )
            }

            ROUTE_SALE_DETAIL -> {
                BackHandler { route = ROUTE_HISTORY }
                SaleDetailRoute(saleId = openSaleId, onBack = { route = ROUTE_HISTORY })
            }

            ROUTE_SYNC -> {
                BackHandler { route = ROUTE_HOME }
                SyncScreen(viewModel = viewModel(), onBack = { route = ROUTE_HOME })
            }

            ROUTE_CUSTOMERS -> {
                BackHandler { route = ROUTE_HOME }
                CustomersRoute(
                    onOpenCustomer = { id ->
                        openCustomerId = id
                        noticeKind = null
                        route = ROUTE_CUSTOMER
                    },
                    onBack = { route = ROUTE_HOME },
                )
            }

            ROUTE_CUSTOMER -> {
                BackHandler {
                    noticeKind = null
                    route = ROUTE_CUSTOMERS
                }
                CustomerRoute(
                    customerId = openCustomerId,
                    notice = noticeKind?.let { BakiNotice(BakiNoticeKind.valueOf(it), Money(noticeAmount)) },
                    onAddBaki = {
                        noticeKind = null
                        route = ROUTE_BAKI_ADD
                    },
                    onReceivePayment = {
                        noticeKind = null
                        route = ROUTE_BAKI_PAY
                    },
                    onUndone = { amount ->
                        noticeKind = BakiNoticeKind.UNDONE.name
                        noticeAmount = amount.minorUnits
                    },
                    onBack = {
                        noticeKind = null
                        route = ROUTE_CUSTOMERS
                    },
                )
            }

            ROUTE_BAKI_ADD, ROUTE_BAKI_PAY -> {
                BackHandler { route = ROUTE_CUSTOMER }
                val kind = if (route == ROUTE_BAKI_ADD) BakiFormKind.ADD_CREDIT else BakiFormKind.RECEIVE_PAYMENT
                BakiFormRoute(
                    kind = kind,
                    customerId = openCustomerId,
                    onSaved = { amount ->
                        noticeKind = if (kind == BakiFormKind.ADD_CREDIT) BakiNoticeKind.ADDED.name else BakiNoticeKind.RECEIVED.name
                        noticeAmount = amount.minorUnits
                        route = ROUTE_CUSTOMER
                    },
                    onBack = { route = ROUTE_CUSTOMER },
                )
            }

            else -> {
                HomeScreen(
                    language = language,
                    onChangeLanguage = onChangeLanguage,
                    onOpenSale = { route = ROUTE_SALE },
                    onOpenStock = { route = ROUTE_STOCK },
                    onOpenHistory = { route = ROUTE_HISTORY },
                    onOpenCustomers = { route = ROUTE_CUSTOMERS },
                    onOpenProducts = { route = ROUTE_PRODUCTS },
                    onOpenSync = { route = ROUTE_SYNC },
                )
            }
        }
    }
}

@Composable
private fun ProductFormRoute(
    viewModel: ProductViewModel,
    editingId: String?,
    showConflict: Boolean,
    onConflict: () -> Unit,
    onDone: () -> Unit,
) {
    var existing by remember(editingId) { mutableStateOf<ProductEntity?>(null) }
    var loading by remember(editingId) { mutableStateOf(editingId != null) }

    LaunchedEffect(editingId) {
        if (editingId != null) {
            existing = viewModel.byId(editingId)
            loading = false
        }
    }

    if (loading) {
        LoadingScreen()
        return
    }

    val product = existing
    val finish = { result: ProductWriteResult ->
        if (result is ProductWriteResult.Saved) onDone() else onConflict()
    }

    ProductEditScreen(
        existing = product,
        showConflict = showConflict,
        onSave = { valid ->
            if (product == null) {
                viewModel.create(valid) { onDone() }
            } else {
                viewModel.update(product.id, product.revision, valid, finish)
            }
        },
        onToggleActive = {
            if (product != null) {
                viewModel.setActive(product.id, product.revision, !product.active, finish)
            }
        },
        onDelete = {
            if (product != null) {
                viewModel.delete(product.id, product.revision, finish)
            }
        },
        onBack = onDone,
    )
}

/**
 * New Sale (Steps 39–40).
 *
 * Staying on this screen after a sale is written is deliberate: the next
 * customer is already at the counter, and walking back through Home between
 * every sale would cost two taps each time. The cart empties and a
 * confirmation appears, so it is never unclear whether the last sale saved.
 */
@Composable
private fun NewSaleRoute(onBack: () -> Unit) {
    val viewModel: SaleViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    NewSaleScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onAddOne = viewModel::addOne,
        onRemoveOne = { line -> viewModel.removeOne(line.product.id, line.product.unit) },
        onSetQuantity = { line, quantity -> viewModel.setQuantity(line.product.id, quantity) },
        onRemoveLine = { line -> viewModel.removeLine(line.product.id) },
        onPaymentChange = viewModel::onPaymentChange,
        onCustomerNameChange = viewModel::onCustomerNameChange,
        onDueDateChange = viewModel::onDueDateChange,
        onConfirm = { viewModel.confirm {} },
        onBack = onBack,
    )
}

/** Stock (Step 41). */
@Composable
private fun StockRoute(onBack: () -> Unit) {
    val viewModel: StockViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    StockScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onIncludeInactiveChange = viewModel::onIncludeInactiveChange,
        onRestock = viewModel::restock,
        onDamage = viewModel::damage,
        onCount = viewModel::count,
        onBack = onBack,
    )
}

/** Transaction history (Step 42). */
@Composable
private fun HistoryRoute(
    onOpenSale: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: HistoryViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    HistoryScreen(
        state = state,
        onFilterChange = viewModel::onFilterChange,
        onOpenSale = onOpenSale,
        onBack = onBack,
    )
}

/**
 * One sale, and undoing it (Steps 43–44).
 *
 * Staying on this screen after a reversal is deliberate: it then shows the
 * sale marked reversed, which is the answer to "did that work?".
 */
@Composable
private fun SaleDetailRoute(
    saleId: String?,
    onBack: () -> Unit,
) {
    val viewModel: SaleDetailViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(saleId) { saleId?.let(viewModel::open) }

    SaleDetailScreen(state = state, onReverse = viewModel::reverse, onBack = onBack)
}

/** The Customers list (Step 52). */
@Composable
private fun CustomersRoute(
    onOpenCustomer: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: CustomerListViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    CustomerListScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onFilterChange = viewModel::onFilterChange,
        onAddCustomer = viewModel::addCustomer,
        onOpenCustomer = onOpenCustomer,
        onBack = onBack,
    )
}

/**
 * One customer (Step 53). The same [CustomerViewModel] serves the forms below,
 * so the balance a form previews is the very number this screen shows.
 */
@Composable
private fun CustomerRoute(
    customerId: String?,
    notice: BakiNotice?,
    onAddBaki: () -> Unit,
    onReceivePayment: () -> Unit,
    onUndone: (Money) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: CustomerViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(customerId) { customerId?.let(viewModel::open) }

    CustomerDetailScreen(
        state = state,
        notice = notice,
        onAddBaki = onAddBaki,
        onReceivePayment = onReceivePayment,
        onUndo = { entryId ->
            viewModel.undo(entryId) { result ->
                if (result is BakiReverseResult.Reversed) {
                    onUndone(Money(result.original.amountDelta.minorUnits.absoluteValue))
                }
            }
        },
        onBack = onBack,
    )
}

/**
 * Add Baki and Receive Payment (Steps 54-55).
 *
 * Saving returns to the customer's screen, where the balance has already
 * changed. That visible change, plus the line saying what was recorded, is the
 * answer to "did that work?".
 */
@Composable
private fun BakiFormRoute(
    kind: BakiFormKind,
    customerId: String?,
    onSaved: (Money) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: CustomerViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(customerId) { customerId?.let(viewModel::open) }

    BakiFormScreen(
        kind = kind,
        state = state,
        onSave = { amount, dueDate ->
            when (kind) {
                BakiFormKind.ADD_CREDIT -> viewModel.addCredit(amount, dueDate) { onSaved(amount) }
                BakiFormKind.RECEIVE_PAYMENT -> viewModel.receivePayment(amount) { onSaved(amount) }
            }
        },
        onBack = onBack,
    )
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(ClayColors.Background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ClayText(text = stringResource(R.string.product_loading), size = 16, color = ClayColors.InkMuted)
    }
}
