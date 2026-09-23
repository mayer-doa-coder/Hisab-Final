package com.hisab.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hisab.app.R
import com.hisab.app.data.sale.SaleDetail
import com.hisab.app.data.sale.SaleItemWithProduct
import com.hisab.app.data.sale.quantity
import com.hisab.app.data.sale.total
import com.hisab.app.data.sale.unitPrice
import com.hisab.app.domain.Money
import com.hisab.app.domain.SalePayment
import com.hisab.app.domain.calculateLineTotal
import com.hisab.app.domain.toDisplayString
import com.hisab.app.domain.toTakaString
import com.hisab.app.ui.product.ScreenHeader
import com.hisab.app.ui.product.unitLabel
import com.hisab.app.ui.scriptAwareText
import com.hisab.app.ui.theme.ClayButton
import com.hisab.app.ui.theme.ClayButtonStyle
import com.hisab.app.ui.theme.ClayCard
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.ClayUserText

/**
 * One sale in full, and the one thing that can be done to it: undo it
 * (Step 43).
 *
 * Undoing never edits or removes the sale (CLAUDE.md). It records a second,
 * opposite sale beside it (D033), so after a reversal this screen shows both:
 * the original, marked reversed, with the time it was undone.
 *
 * The confirmation says in plain words what will happen — the items go back
 * into stock, and for a credit sale exactly how much comes off whose baki —
 * because that is the moment a shopkeeper decides, and "Are you sure?" alone
 * tells them nothing (D021).
 */
@Composable
fun SaleDetailScreen(
    state: SaleDetailUiState,
    onReverse: () -> Unit,
    onBack: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val detail = state.detail

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ClayColors.Background)
                .safeDrawingPadding()
                .padding(horizontal = ClayDimens.ScreenPadding),
    ) {
        ScreenHeader(title = stringResource(R.string.sale_detail_title), onBack = onBack)

        state.message?.let { MessageBanner(it) }

        when {
            detail != null -> {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SummaryCard(detail)
                    LinesCard(detail.lines)
                }

                // Offered only when there is something to undo: not for a sale
                // already reversed, and not for a reversal itself.
                val reversible = detail.reversedBy == null && detail.sale.reversesSaleId == null
                if (reversible) {
                    ClayButton(
                        text = stringResource(R.string.sale_reverse_action),
                        onClick = { confirming = true },
                        style = ClayButtonStyle.DANGER,
                        enabled = !state.reversing,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("button_reverse_sale"),
                    )
                }

                if (confirming && reversible) {
                    ConfirmReversal(
                        detail = detail,
                        onConfirm = {
                            confirming = false
                            onReverse()
                        },
                        onDismiss = { confirming = false },
                    )
                }
            }

            // Nothing is said until the first read has come back.
            state.loaded -> {
                ClayText(
                    text = stringResource(R.string.sale_not_found),
                    size = 16,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageBanner(message: ReversalMessage) {
    val (text, colour) =
        when (message) {
            ReversalMessage.REVERSED -> stringResource(R.string.sale_reversal_saved) to ClayColors.Money
            ReversalMessage.REVERSED_CREDIT -> stringResource(R.string.sale_reversal_saved_credit) to ClayColors.Money
            ReversalMessage.ALREADY_REVERSED -> stringResource(R.string.sale_already_reversed) to ClayColors.Danger
            ReversalMessage.NOT_FOUND -> stringResource(R.string.sale_not_found) to ClayColors.Danger
        }
    ClayText(
        text = text,
        size = 14,
        weight = FontWeight.Bold,
        color = colour,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .background(colour.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun SummaryCard(detail: SaleDetail) {
    val locale = LocalConfiguration.current.locales[0]
    val currency = stringResource(R.string.currency_symbol)
    val sale = detail.sale
    val isReversal = sale.reversesSaleId != null

    val label =
        when {
            isReversal -> R.string.history_sale_reversal
            sale.payment == SalePayment.CREDIT.name -> R.string.history_sale_credit
            else -> R.string.history_sale_cash
        }
    val accent: Color = if (isReversal) ClayColors.Danger else ClayColors.Money

    ClayCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(text = stringResource(label), colour = accent)
            if (detail.reversedBy != null) {
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    Badge(text = stringResource(R.string.sale_reversed_badge), colour = ClayColors.Danger)
                }
            }
        }

        ClayUserText(
            text = scriptAwareText("$currency ${sale.total.toTakaString(locale)}"),
            size = 30,
            weight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.padding(top = 12.dp).testTag("sale_detail_total"),
        )
        ClayUserText(
            text = scriptAwareText(rememberWhenText(sale.occurredAt, locale)),
            size = 13,
            color = ClayColors.InkMuted,
            modifier = Modifier.padding(top = 4.dp),
        )

        detail.customer?.let { customer ->
            ClayUserText(
                text = scriptAwareText(stringResource(R.string.sale_detail_owed_by, customer.name)),
                size = 15,
                weight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        detail.reversedBy?.let { reversal ->
            ClayUserText(
                text =
                    scriptAwareText(
                        stringResource(R.string.sale_reversed_on, rememberWhenText(reversal.occurredAt, locale)),
                    ),
                size = 13,
                weight = FontWeight.Bold,
                color = ClayColors.Danger,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        detail.reverses?.let { original ->
            ClayUserText(
                text =
                    scriptAwareText(
                        stringResource(R.string.sale_is_reversal, rememberWhenText(original.occurredAt, locale)),
                    ),
                size = 13,
                color = ClayColors.InkMuted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        ClayText(
            text =
                stringResource(
                    if (sale.serverReceivedAt != null) R.string.sale_detail_synced else R.string.sale_detail_not_synced,
                ),
            size = 12,
            color = ClayColors.InkMuted,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun LinesCard(lines: List<SaleItemWithProduct>) {
    val locale = LocalConfiguration.current.locales[0]
    val currency = stringResource(R.string.currency_symbol)

    ClayCard {
        ClayText(text = stringResource(R.string.sale_detail_lines), size = 13, weight = FontWeight.Bold, color = ClayColors.InkMuted)

        lines.forEachIndexed { index, line ->
            val item = line.item
            // A reversal's lines are negative; the list shows how much, and the
            // sign lives in the total above.
            val amount =
                if (item.quantity.scaledUnits < 0) {
                    com.hisab.app.domain
                        .Quantity(-item.quantityScaled)
                } else {
                    item.quantity
                }
            val lineTotal: Money = calculateLineTotal(item.quantity, item.unitPrice)
            val unit = line.productUnit?.let { unitLabel(it) }.orEmpty()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = if (index == 0) 10.dp else 14.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (line.productName == null) {
                        ClayText(text = stringResource(R.string.history_unknown_product), size = 15, color = ClayColors.InkMuted)
                    } else {
                        ClayUserText(text = scriptAwareText(line.productName), size = 16, weight = FontWeight.Bold, maxLines = 2)
                    }
                    ClayUserText(
                        text =
                            scriptAwareText(
                                "${amount.toDisplayString(locale)} $unit × $currency ${item.unitPrice.toTakaString(locale)}",
                            ),
                        size = 13,
                        color = ClayColors.InkMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                ClayUserText(
                    text = scriptAwareText("$currency ${lineTotal.toTakaString(locale)}"),
                    size = 16,
                    weight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ConfirmReversal(
    detail: SaleDetail,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val currency = stringResource(R.string.currency_symbol)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ClayText(text = stringResource(R.string.sale_reverse_confirm_title), size = 18, weight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ClayText(text = stringResource(R.string.sale_reverse_confirm_stock), size = 14)
                val customer = detail.customer
                if (customer != null) {
                    ClayUserText(
                        text =
                            scriptAwareText(
                                stringResource(
                                    R.string.sale_reverse_confirm_baki,
                                    customer.name,
                                    "$currency ${detail.sale.total.toTakaString(locale)}",
                                ),
                            ),
                        size = 14,
                        weight = FontWeight.Bold,
                    )
                }
                ClayText(text = stringResource(R.string.sale_reverse_confirm_history), size = 13, color = ClayColors.InkMuted)
            }
        },
        confirmButton = {
            ClayButton(
                text = stringResource(R.string.sale_reverse_confirm_action),
                onClick = onConfirm,
                style = ClayButtonStyle.DANGER,
            )
        },
        dismissButton = {
            ClayButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                style = ClayButtonStyle.SOFT,
            )
        },
    )
}
