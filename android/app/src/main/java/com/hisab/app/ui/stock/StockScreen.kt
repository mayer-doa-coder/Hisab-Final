package com.hisab.app.ui.stock

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
 * Current stock per product, and the three ways a shopkeeper changes it by
 * hand (Step 41): a delivery arriving, goods lost or broken, and counting the
 * shelf.
 *
 * Every number here is summed from the movement ledger, never stored (D020),
 * which is why a change shows up the moment it is saved.
 *
 * Negative stock is shown as negative, in the danger colour, with a line
 * saying what it usually means. It is not hidden or clamped to zero: a
 * negative number is the ledger saying a delivery was never recorded, and
 * that is worth a shopkeeper's attention (D031). Counting the shelf is how it
 * gets put right, and that writes a correction with a reason — never a silent
 * edit of the number (D002).
 */
@Composable
fun StockScreen(
    state: StockUiState,
    onQueryChange: (String) -> Unit,
    onIncludeInactiveChange: (Boolean) -> Unit,
    onRestock: (productId: String, quantity: Quantity, note: String) -> Unit,
    onDamage: (productId: String, quantity: Quantity, note: String) -> Unit,
    onCount: (productId: String, counted: Quantity, note: String) -> Unit,
    onBack: () -> Unit,
) {
    var queryText by rememberSaveable { mutableStateOf(state.query) }
    var changingId by rememberSaveable { mutableStateOf<String?>(null) }
    var changeKind by rememberSaveable { mutableStateOf(StockChange.RESTOCK) }
    // What was last recorded, so the confirmation can say which. Kept as state
    // rather than a timed message, so it survives a rotation or a language
    // switch — and it clears as soon as another change starts.
    var justDid by rememberSaveable { mutableStateOf<StockChange?>(null) }

    val target = state.products.firstOrNull { it.product.id == changingId }
    if (target != null) {
        StockChangeSheet(
            kind = changeKind,
            entry = target,
            onConfirm = { amount, note ->
                when (changeKind) {
                    StockChange.RESTOCK -> onRestock(target.product.id, amount, note)
                    StockChange.DAMAGE -> onDamage(target.product.id, amount, note)
                    StockChange.COUNT -> onCount(target.product.id, amount, note)
                }
                justDid = changeKind
                changingId = null
            },
            onDismiss = { changingId = null },
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

        justDid?.let { kind ->
            ClayText(
                text =
                    stringResource(
                        when (kind) {
                            StockChange.RESTOCK -> R.string.stock_added
                            StockChange.DAMAGE -> R.string.stock_damaged
                            StockChange.COUNT -> R.string.stock_counted
                        },
                    ),
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
                                onChange = { kind ->
                                    justDid = null
                                    changeKind = kind
                                    changingId = entry.product.id
                                },
                            )
                        }
                    }
                }

                // Nothing is said until the first read has come back, so an
                // empty screen never flashes "no products" over real data.
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

/** The three ways stock changes by hand. A sale changes it too, but through the sale (D021). */
enum class StockChange { RESTOCK, DAMAGE, COUNT }

@Composable
private fun StockRow(
    entry: ProductWithStock,
    onChange: (StockChange) -> Unit,
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

            // Adding stock is the common one, so it keeps the button.
            ClayButton(
                text = stringResource(R.string.stock_add_action),
                onClick = { onChange(StockChange.RESTOCK) },
                style = ClayButtonStyle.SOFT,
                leadingIcon = painterResource(R.drawable.ic_add),
            )
        }

        // The rarer two sit under it as plain words rather than more buttons,
        // so the row stays readable on a 720-wide screen and the common action
        // keeps its weight.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SmallAction(
                text = stringResource(R.string.stock_damage_action),
                colour = ClayColors.Danger,
                onClick = { onChange(StockChange.DAMAGE) },
            )
            SmallAction(
                text = stringResource(R.string.stock_count_action),
                colour = ClayColors.Primary,
                onClick = { onChange(StockChange.COUNT) },
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

@Composable
private fun SmallAction(
    text: String,
    colour: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    ClayText(
        text = text,
        size = 14,
        weight = FontWeight.Bold,
        color = colour,
        modifier =
            Modifier
                .clickable(onClick = onClick)
                // Padding, not a smaller font, is what keeps this a real tap
                // target next to its neighbour (D029).
                .padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

/**
 * Recording a stock change, in place rather than on another screen.
 *
 * Whichever kind it is, it shows what is there now and what it will become,
 * side by side, before anything is saved — so the shopkeeper checks the
 * arithmetic against the shelf rather than trusting it afterwards. For a
 * count, it also shows the difference that will be recorded, because that
 * difference is the thing the ledger keeps.
 */
@Composable
private fun StockChangeSheet(
    kind: StockChange,
    entry: ProductWithStock,
    onConfirm: (Quantity, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val key = "${entry.product.id}:$kind"
    var amountText by rememberSaveable(key) { mutableStateOf("") }
    var note by rememberSaveable(key) { mutableStateOf("") }
    var showError by rememberSaveable(key) { mutableStateOf(false) }

    val parsed = parseQuantity(amountText)
    val current = Quantity(entry.stockScaled)
    val after =
        when {
            parsed == null -> null
            kind == StockChange.RESTOCK -> current + parsed
            kind == StockChange.DAMAGE -> current - parsed
            else -> parsed
        }
    val unit = unitLabel(entry.product.unit)

    Dialog(onDismissRequest = onDismiss) {
        ClayCard {
            ClayText(
                text =
                    stringResource(
                        when (kind) {
                            StockChange.RESTOCK -> R.string.stock_add_title
                            StockChange.DAMAGE -> R.string.stock_damage_title
                            StockChange.COUNT -> R.string.stock_count_title
                        },
                    ),
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
                fieldTag = "field_stock_quantity",
                label =
                    stringResource(
                        when (kind) {
                            StockChange.RESTOCK -> R.string.stock_add_quantity_label
                            StockChange.DAMAGE -> R.string.stock_damage_quantity_label
                            StockChange.COUNT -> R.string.stock_count_quantity_label
                        },
                    ),
                keyboardType = KeyboardType.Decimal,
                errorText = if (showError) stringResource(R.string.error_quantity_invalid) else null,
                modifier = Modifier.padding(top = 16.dp),
            )

            ClayTextField(
                value = note,
                onValueChange = { note = it },
                fieldTag = "field_stock_note",
                label =
                    stringResource(
                        if (kind == StockChange.RESTOCK) R.string.stock_add_note_label else R.string.stock_reason_label,
                    ),
                placeholder =
                    stringResource(
                        if (kind == StockChange.RESTOCK) R.string.stock_add_note_hint else R.string.stock_reason_hint,
                    ),
                modifier = Modifier.padding(top = 12.dp),
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                BeforeAfter(
                    label = stringResource(R.string.stock_current_label),
                    value = current.toDisplayString(locale),
                    unit = unit,
                    highlight = false,
                    modifier = Modifier.weight(1f),
                )
                BeforeAfter(
                    label = stringResource(R.string.stock_after_label),
                    value = after?.toDisplayString(locale) ?: "—",
                    unit = unit,
                    highlight = true,
                    modifier = Modifier.weight(1f),
                )
            }

            if (kind == StockChange.COUNT) {
                val difference = if (after == null) null else after - current
                ClayUserText(
                    text =
                        scriptAwareText(
                            stringResource(R.string.stock_difference_label) + ": " +
                                when {
                                    difference == null -> "—"
                                    difference.scaledUnits > 0 -> "+${difference.toDisplayString(locale)} $unit"
                                    else -> "${difference.toDisplayString(locale)} $unit"
                                },
                        ),
                    size = 13,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 10.dp),
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
                    text =
                        stringResource(
                            if (kind == StockChange.RESTOCK) R.string.action_add else R.string.action_record,
                        ),
                    onClick = {
                        // A delivery and a loss must be a positive amount, and
                        // a shelf count cannot be negative — the domain refuses
                        // anything else, so the screen says so rather than
                        // letting the write throw.
                        val valid =
                            parsed != null &&
                                if (kind == StockChange.COUNT) parsed.scaledUnits >= 0 else parsed.scaledUnits > 0
                        if (!valid) showError = true else onConfirm(parsed, note)
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
