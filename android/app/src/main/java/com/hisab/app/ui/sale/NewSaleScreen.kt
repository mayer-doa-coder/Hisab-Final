package com.hisab.app.ui.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hisab.app.R
import com.hisab.app.data.product.sellingPrice
import com.hisab.app.data.stock.ProductWithStock
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SalePayment
import com.hisab.app.domain.parseQuantity
import com.hisab.app.domain.toDisplayString
import com.hisab.app.domain.toEditableQuantity
import com.hisab.app.domain.toTakaString
import com.hisab.app.ui.product.ScreenHeader
import com.hisab.app.ui.product.unitLabel
import com.hisab.app.ui.scriptAwareText
import com.hisab.app.ui.theme.ClayButton
import com.hisab.app.ui.theme.ClayCard
import com.hisab.app.ui.theme.ClayChip
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.ClayTextField
import com.hisab.app.ui.theme.ClayUserText
import com.hisab.app.ui.theme.claySurface

/**
 * New Sale, cash and baki in one screen (Steps 39 and 40).
 *
 * The layout follows what a shopkeeper actually does at a counter, which is
 * add items one after another while a customer waits:
 *
 * - The product list is the body of the screen, not behind a button, so
 *   adding an item is one tap. Tapping the same product again adds one more
 *   rather than making a second line, which is also what the domain requires
 *   (a product appears at most once in a sale).
 * - The total and the confirm button are pinned to the bottom, in the thumb's
 *   reach on a tall phone — the standard sticky cart-summary pattern.
 * - Quantity is changed with large −/+ buttons and can also be typed, because
 *   half a kilo is not reachable by tapping and 12 pieces should not need
 *   twelve taps.
 * - Nothing here is hidden behind a confirmation the shopkeeper has to read.
 *   Selling more than the recorded stock warns and lets the sale through
 *   (D031); it never blocks.
 */
@Composable
fun NewSaleScreen(
    state: SaleUiState,
    onQueryChange: (String) -> Unit,
    onAddOne: (productId: String, unit: String) -> Unit,
    onRemoveOne: (CartLine) -> Unit,
    onSetQuantity: (CartLine, Quantity) -> Unit,
    onRemoveLine: (CartLine) -> Unit,
    onPaymentChange: (SalePayment) -> Unit,
    onCustomerNameChange: (String) -> Unit,
    onDueDateChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    // The picker is the body until something is in the cart, so the first tap
    // of a new sale is on a product, not on "choose a product".
    var showingCart by rememberSaveable { mutableStateOf(false) }
    val cartVisible = showingCart && !state.isEmpty

    // An empty cart means a sale just finished or nothing is chosen yet, so
    // the next tap belongs on a product. Without this, a sale confirmed with
    // the cart open left it open, and the first product tapped for the next
    // customer swapped the product list out for the cart.
    LaunchedEffect(state.isEmpty) {
        if (state.isEmpty) showingCart = false
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ClayColors.Background)
                .safeDrawingPadding()
                .padding(horizontal = ClayDimens.ScreenPadding),
    ) {
        ScreenHeader(title = stringResource(R.string.sale_new_title), onBack = onBack)

        // The one confirmation that the last sale is really on the device. It
        // stays until the next item is added rather than fading on a timer,
        // so it survives a glance away, a rotation, or a language switch.
        if (state.justSaved != null) {
            ClayText(
                text =
                    stringResource(
                        if (state.justSaved == SalePayment.CREDIT) {
                            R.string.sale_saved_credit
                        } else {
                            R.string.sale_saved_cash
                        },
                    ),
                size = 14,
                weight = FontWeight.Bold,
                color = ClayColors.Money,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(ClayColors.Money.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                cartVisible -> {
                    CartList(
                        lines = state.lines,
                        onAddOne = { line -> onAddOne(line.product.id, line.product.unit) },
                        onRemoveOne = onRemoveOne,
                        onSetQuantity = onSetQuantity,
                        onRemoveLine = onRemoveLine,
                    )
                }

                state.available.isNotEmpty() -> {
                    ProductPicker(
                        state = state,
                        onQueryChange = onQueryChange,
                        onAddOne = onAddOne,
                    )
                }

                state.loaded && state.query.isNotEmpty() -> {
                    CentredMessage(stringResource(R.string.stock_search_empty), null)
                }

                state.loaded -> {
                    CentredMessage(
                        stringResource(R.string.sale_no_products_title),
                        stringResource(R.string.sale_no_products_subtitle),
                    )
                }
            }
        }

        SaleFooter(
            state = state,
            cartVisible = cartVisible,
            onToggleCart = { showingCart = !showingCart },
            onPaymentChange = onPaymentChange,
            onCustomerNameChange = onCustomerNameChange,
            onDueDateChange = onDueDateChange,
            onConfirm = onConfirm,
        )
    }
}

@Composable
private fun ProductPicker(
    state: SaleUiState,
    onQueryChange: (String) -> Unit,
    onAddOne: (productId: String, unit: String) -> Unit,
) {
    // The box keeps its own text rather than reading it back from the
    // database query, for the same reason the product search does: feeding it
    // back makes every keystroke wait for a round trip, and fast typing then
    // lands letters out of order.
    var queryText by rememberSaveable { mutableStateOf(state.query) }
    val inCart = state.lines.associateBy { it.product.id }

    Column {
        ClayTextField(
            value = queryText,
            onValueChange = {
                queryText = it
                onQueryChange(it)
            },
            fieldTag = "field_sale_search",
            placeholder = stringResource(R.string.stock_search_hint),
            leadingIcon = painterResource(R.drawable.ic_search),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = state.available, key = { it.product.id }) { entry ->
                PickerRow(
                    entry = entry,
                    inCart = inCart[entry.product.id]?.quantity,
                    onClick = { onAddOne(entry.product.id, entry.product.unit) },
                )
            }
        }
    }
}

@Composable
private fun PickerRow(
    entry: ProductWithStock,
    inCart: Quantity?,
    onClick: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val currency = stringResource(R.string.currency_symbol)
    val stock = Quantity(entry.stockScaled)

    ClayCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ClayUserText(
                    text = scriptAwareText(entry.product.name),
                    size = 17,
                    weight = FontWeight.Bold,
                    maxLines = 2,
                )
                Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ClayUserText(
                        text = scriptAwareText("$currency ${entry.product.sellingPrice.toTakaString(locale)}"),
                        size = 15,
                        weight = FontWeight.Bold,
                        color = ClayColors.Money,
                    )
                    ClayText(
                        text = " / ${unitLabel(entry.product.unit)}",
                        size = 13,
                        color = ClayColors.InkMuted,
                    )
                }
                ClayText(
                    text = stringResource(R.string.sale_stock_left, stock.toDisplayString(locale)),
                    size = 12,
                    color = if (stock.scaledUnits <= 0) ClayColors.Danger else ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (inCart != null) {
                ClayText(
                    text = inCart.toDisplayString(locale),
                    size = 16,
                    weight = FontWeight.Bold,
                    color = ClayColors.OnPrimary,
                    modifier =
                        Modifier
                            .background(ClayColors.Primary, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.action_add),
                    tint = ClayColors.Primary,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .background(ClayColors.PrimarySoft, RoundedCornerShape(14.dp))
                            .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun CartList(
    lines: List<CartLine>,
    onAddOne: (CartLine) -> Unit,
    onRemoveOne: (CartLine) -> Unit,
    onSetQuantity: (CartLine, Quantity) -> Unit,
    onRemoveLine: (CartLine) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = lines, key = { it.product.id }) { line ->
            CartRow(
                line = line,
                onAddOne = { onAddOne(line) },
                onRemoveOne = { onRemoveOne(line) },
                onSetQuantity = { onSetQuantity(line, it) },
                onRemove = { onRemoveLine(line) },
            )
        }
    }
}

@Composable
private fun CartRow(
    line: CartLine,
    onAddOne: () -> Unit,
    onRemoveOne: () -> Unit,
    onSetQuantity: (Quantity) -> Unit,
    onRemove: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val currency = stringResource(R.string.currency_symbol)

    ClayCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                ClayUserText(
                    text = scriptAwareText(line.product.name),
                    size = 17,
                    weight = FontWeight.Bold,
                    maxLines = 2,
                )
                ClayUserText(
                    text =
                        scriptAwareText(
                            "$currency ${line.unitPrice.toTakaString(locale)} / ${unitLabel(line.product.unit)}",
                        ),
                    size = 13,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                ClayUserText(
                    text = scriptAwareText("$currency ${line.lineTotal.toTakaString(locale)}"),
                    size = 18,
                    weight = FontWeight.Bold,
                    color = ClayColors.Money,
                )
                ClayText(
                    text = stringResource(R.string.sale_remove_line),
                    size = 13,
                    weight = FontWeight.Bold,
                    color = ClayColors.Danger,
                    modifier =
                        Modifier
                            .padding(top = 8.dp)
                            .clickable(onClick = onRemove)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }

        QuantityStepper(
            quantity = line.quantity,
            onAddOne = onAddOne,
            onRemoveOne = onRemoveOne,
            onSetQuantity = onSetQuantity,
            fieldTag = "field_qty_${line.product.id}",
            modifier = Modifier.padding(top = 12.dp),
        )

        if (line.isShort) {
            ClayText(
                text =
                    stringResource(
                        R.string.sale_stock_warning,
                        line.stockOnHand.toDisplayString(locale),
                        line.quantity.toDisplayString(locale),
                    ),
                size = 12,
                color = ClayColors.Danger,
                modifier =
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .background(ClayColors.Danger.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Big −/+ buttons with the number between them, and the number is also a
 * field you can type into.
 *
 * Both are here on purpose. Tapping is faster and more accurate than typing
 * for the common case of one or two of something, and touch accuracy rises
 * with target size — these are 48 dp, above the usual 44 dp floor and in line
 * with the 54 dp tap target D029 asks for elsewhere. But twelve taps for
 * twelve pieces is absurd, and half a kilo cannot be tapped at all, so the
 * number stays typeable.
 */
@Composable
private fun QuantityStepper(
    quantity: Quantity,
    onAddOne: () -> Unit,
    onRemoveOne: () -> Unit,
    onSetQuantity: (Quantity) -> Unit,
    fieldTag: String,
    modifier: Modifier = Modifier,
) {
    // Held locally while being typed: reading it back from the cart on every
    // keystroke would fight the cursor when a value is half-entered ("1." is
    // not a quantity yet, but it is on the way to one).
    var text by rememberSaveable(quantity) { mutableStateOf(quantity.toEditableQuantity()) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        StepperButton(
            icon = R.drawable.ic_minus,
            contentDescription = stringResource(R.string.cd_decrease),
            onClick = onRemoveOne,
        )

        Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            ClayTextField(
                value = text,
                onValueChange = {
                    text = it
                    parseQuantity(it)?.let(onSetQuantity)
                },
                fieldTag = fieldTag,
                keyboardType = KeyboardType.Decimal,
            )
        }

        StepperButton(
            icon = R.drawable.ic_add,
            contentDescription = stringResource(R.string.cd_increase),
            onClick = onAddOne,
        )
    }
}

@Composable
private fun StepperButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        tint = ClayColors.Primary,
        modifier =
            Modifier
                .size(48.dp)
                .claySurface(RoundedCornerShape(16.dp), 6.dp, ClayColors.PrimarySoft)
                .clickable(onClick = onClick)
                .padding(12.dp),
    )
}

/**
 * The bar the thumb lives on: how it is being paid, what it comes to, and the
 * one button that finishes the sale. It never scrolls away, so the total is
 * visible while items are being added.
 */
@Composable
private fun SaleFooter(
    state: SaleUiState,
    cartVisible: Boolean,
    onToggleCart: () -> Unit,
    onPaymentChange: (SalePayment) -> Unit,
    onCustomerNameChange: (String) -> Unit,
    onDueDateChange: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val currency = stringResource(R.string.currency_symbol)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 16.dp),
    ) {
        if (!state.isEmpty) {
            ClayCard(onClick = onToggleCart) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cart),
                        contentDescription = null,
                        tint = ClayColors.Primary,
                        modifier = Modifier.size(22.dp),
                    )
                    ClayText(
                        text = stringResource(R.string.sale_items_count, state.lines.size),
                        size = 14,
                        weight = FontWeight.Bold,
                        color = ClayColors.InkMuted,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                    ClayText(
                        text = stringResource(if (cartVisible) R.string.sale_pick_product else R.string.sale_view_cart),
                        size = 13,
                        color = ClayColors.Primary,
                        weight = FontWeight.Bold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ClayText(
                        text = stringResource(R.string.sale_total_label),
                        size = 15,
                        weight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    ClayUserText(
                        text = scriptAwareText("$currency ${state.total.toTakaString(locale)}"),
                        size = 26,
                        weight = FontWeight.Bold,
                        color = ClayColors.Money,
                        modifier = Modifier.testTag("sale_total"),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ClayChip(
                        label = stringResource(R.string.sale_pay_cash),
                        selected = state.payment == SalePayment.CASH,
                        onClick = { onPaymentChange(SalePayment.CASH) },
                    )
                    ClayChip(
                        label = stringResource(R.string.sale_pay_credit),
                        selected = state.payment == SalePayment.CREDIT,
                        onClick = { onPaymentChange(SalePayment.CREDIT) },
                    )
                }

                if (state.payment == SalePayment.CREDIT) {
                    ClayTextField(
                        value = state.customerName,
                        onValueChange = onCustomerNameChange,
                        fieldTag = "field_customer",
                        label = stringResource(R.string.sale_customer_label),
                        placeholder = stringResource(R.string.sale_customer_hint),
                        errorText =
                            if (state.customerError) stringResource(R.string.error_customer_required) else null,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    ClayTextField(
                        value = state.dueDateText,
                        onValueChange = onDueDateChange,
                        fieldTag = "field_due_date",
                        label = stringResource(R.string.sale_due_date_label),
                        placeholder = stringResource(R.string.sale_due_date_hint),
                        keyboardType = KeyboardType.Number,
                        errorText =
                            if (state.dueDateError) stringResource(R.string.error_due_date_invalid) else null,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                // Selling more than the ledger knows about is allowed, and
                // said out loud rather than blocked (D031).
                if (state.shortLines.isNotEmpty()) {
                    ClayText(
                        text = stringResource(R.string.sale_stock_warning_title),
                        size = 13,
                        weight = FontWeight.Bold,
                        color = ClayColors.Danger,
                        modifier =
                            Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .background(ClayColors.Danger.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        ClayButton(
            text =
                stringResource(
                    if (state.payment == SalePayment.CREDIT) {
                        R.string.sale_confirm_credit
                    } else {
                        R.string.sale_confirm_cash
                    },
                ),
            onClick = onConfirm,
            enabled = state.canConfirm,
            modifier = Modifier.fillMaxWidth().padding(top = if (state.isEmpty) 0.dp else 12.dp),
        )
    }
}

@Composable
private fun CentredMessage(
    title: String,
    subtitle: String?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ClayText(text = title, size = 18, weight = FontWeight.Bold)
        if (subtitle != null) {
            ClayText(
                text = subtitle,
                size = 14,
                color = ClayColors.InkMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
