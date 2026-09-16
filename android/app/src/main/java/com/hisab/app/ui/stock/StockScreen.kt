package com.hisab.app.ui.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hisab.app.R
import com.hisab.app.data.stock.ProductWithStock
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.parseQuantity
import com.hisab.app.domain.toDisplayString
import com.hisab.app.ui.product.ScreenHeader
import com.hisab.app.ui.product.unitLabel
import com.hisab.app.ui.scriptAwareText
import com.hisab.app.ui.theme.ClayButton
import com.hisab.app.ui.theme.ClayButtonStyle
import com.hisab.app.ui.theme.ClayCard
import com.hisab.app.ui.theme.ClayChip
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.ClayTextField
import com.hisab.app.ui.theme.ClayUserText

/**
 * Current stock per product, and adding to it (Step 41).
 *
 * Every number here is summed from the movement ledger, never stored (D020),
 * which is why a restock shows up the moment it is saved.
 *
 * Negative stock is shown as negative, in the danger colour, with a line
 * saying what it usually means. It is not hidden or clamped to zero: a
 * negative number is the ledger saying a delivery was never recorded, and
 * that is worth a shopkeeper's attention (D031).
 */
@Composable
fun StockScreen(
    state: StockUiState,
    onQueryChange: (String) -> Unit,
    onIncludeInactiveChange: (Boolean) -> Unit,
    onRestock: (productId: String, quantity: Quantity, note: String) -> Unit,
    onBack: () -> Unit,
) {
    var queryText by rememberSaveable { mutableStateOf(state.query) }
    var restocking by rememberSaveable { mutableStateOf<String?>(null) }
    // Which product was last restocked, so the confirmation can name it. Kept
    // as state rather than a timed message, so it survives a rotation or a
    // language switch — and it clears as soon as another restock starts.
    var justRestocked by rememberSaveable { mutableStateOf<String?>(null) }

    val target = state.products.firstOrNull { it.product.id == restocking }
    if (target != null) {
        RestockSheet(
            entry = target,
            onConfirm = { quantity, note ->
                onRestock(target.product.id, quantity, note)
                justRestocked = target.product.id
                restocking = null
            },
            onDismiss = { restocking = null },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ClayColors.Background)
                .safeDrawingPadding()
                .padding(horizontal = ClayDimens.ScreenPadding),
    ) {
        ScreenHeader(title = stringResource(R.string.stock_title), onBack = onBack)

        ClayTextField(
            value = queryText,
            onValueChange = {
                queryText = it
                onQueryChange(it)
            },
            fieldTag = "field_stock_search",
            placeholder = stringResource(R.string.stock_search_hint),
            leadingIcon = painterResource(R.drawable.ic_search),
        )

        Row(modifier = Modifier.padding(top = 12.dp)) {
            ClayChip(
                label = stringResource(R.string.product_show_inactive),
                selected = state.includeInactive,
                onClick = { onIncludeInactiveChange(!state.includeInactive) },
            )
        }

        if (justRestocked != null) {
            ClayText(
                text = stringResource(R.string.stock_added),
                size = 14,
                weight = FontWeight.Bold,
                color = ClayColors.Money,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .background(ClayColors.Money.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.products.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(items = state.products, key = { it.product.id }) { entry ->
                            StockRow(
                                entry = entry,
                                onRestock = {
                                    justRestocked = null
                                    restocking = entry.product.id
                                },
                            )
                        }
                    }
                }

                state.loaded && queryText.isNotEmpty() -> {
                    CentredMessage(stringResource(R.string.stock_search_empty), null)
                }

                state.loaded -> {
                    CentredMessage(
                        stringResource(R.string.stock_empty_title),
                        stringResource(R.string.stock_empty_subtitle),
                    )
                }
            }
        }
    }
}

@Composable
private fun StockRow(
    entry: ProductWithStock,
    onRestock: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val stock = Quantity(entry.stockScaled)
    val negative = stock.scaledUnits < 0

    ClayCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                ClayUserText(
                    text = scriptAwareText(entry.product.name),
                    size = 17,
                    weight = FontWeight.Bold,
                    maxLines = 2,
                )
                ClayText(
                    text = stringResource(R.string.stock_current_label),
                    size = 12,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                    ClayUserText(
                        text = scriptAwareText(stock.toDisplayString(locale)),
                        size = 26,
                        weight = FontWeight.Bold,
                        color = if (negative) ClayColors.Danger else ClayColors.Ink,
                    )
                    ClayText(
                        text = " ${unitLabel(entry.product.unit)}",
                        size = 14,
                        color = ClayColors.InkMuted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            ClayButton(
                text = stringResource(R.string.stock_add_action),
                onClick = onRestock,
                style = ClayButtonStyle.SOFT,
                leadingIcon = painterResource(R.drawable.ic_add),
            )
        }

        if (negative) {
            ClayText(
                text = stringResource(R.string.stock_negative_note),
                size = 12,
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

/**
 * Adding stock, in place rather than on another screen.
 *
 * It shows what is there now and what it will become, side by side, before
 * anything is saved — so the shopkeeper checks the arithmetic against the
 * shelf rather than trusting it afterwards.
 */
@Composable
private fun RestockSheet(
    entry: ProductWithStock,
    onConfirm: (Quantity, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    var amountText by rememberSaveable(entry.product.id) { mutableStateOf("") }
    var note by rememberSaveable(entry.product.id) { mutableStateOf("") }
    var showError by rememberSaveable(entry.product.id) { mutableStateOf(false) }

    val parsed = parseQuantity(amountText)
    val current = Quantity(entry.stockScaled)
    val after = if (parsed == null) null else current + parsed

    Dialog(onDismissRequest = onDismiss) {
        ClayCard {
            ClayText(
                text = stringResource(R.string.stock_add_title),
                size = 20,
                weight = FontWeight.Bold,
            )
            ClayUserText(
                text = scriptAwareText(entry.product.name),
                size = 15,
                color = ClayColors.InkMuted,
                modifier = Modifier.padding(top = 4.dp),
            )

            ClayTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    showError = false
                },
                fieldTag = "field_restock_quantity",
                label = stringResource(R.string.stock_add_quantity_label),
                keyboardType = KeyboardType.Decimal,
                errorText =
                    if (showError) stringResource(R.string.error_quantity_invalid) else null,
                modifier = Modifier.padding(top = 16.dp),
            )

            ClayTextField(
                value = note,
                onValueChange = { note = it },
                fieldTag = "field_restock_note",
                label = stringResource(R.string.stock_add_note_label),
                placeholder = stringResource(R.string.stock_add_note_hint),
                modifier = Modifier.padding(top = 12.dp),
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                BeforeAfter(
                    label = stringResource(R.string.stock_current_label),
                    value = current.toDisplayString(locale),
                    unit = unitLabel(entry.product.unit),
                    highlight = false,
                    modifier = Modifier.weight(1f),
                )
                BeforeAfter(
                    label = stringResource(R.string.stock_after_label),
                    value = after?.toDisplayString(locale) ?: "—",
                    unit = unitLabel(entry.product.unit),
                    highlight = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClayButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    style = ClayButtonStyle.SOFT,
                    modifier = Modifier.weight(1f),
                )
                ClayButton(
                    text = stringResource(R.string.action_add),
                    onClick = {
                        // A restock must be a positive amount — the domain
                        // refuses anything else, so the screen says so rather
                        // than letting the write throw.
                        if (parsed == null || parsed.scaledUnits <= 0) showError = true else onConfirm(parsed, note)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BeforeAfter(
    label: String,
    value: String,
    unit: String,
    highlight: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ClayText(text = label, size = 12, color = ClayColors.InkMuted)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
            ClayUserText(
                text = scriptAwareText(value),
                size = 22,
                weight = FontWeight.Bold,
                color = if (highlight) ClayColors.Money else ClayColors.Ink,
            )
            ClayText(
                text = " $unit",
                size = 12,
                color = ClayColors.InkMuted,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
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
