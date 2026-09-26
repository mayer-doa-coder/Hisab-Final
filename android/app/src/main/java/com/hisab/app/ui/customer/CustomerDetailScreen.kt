package com.hisab.app.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hisab.app.R
import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.Money
import com.hisab.app.ui.history.rememberWhenText
import com.hisab.app.ui.theme.ClayButton
import com.hisab.app.ui.theme.ClayButtonStyle
import com.hisab.app.ui.theme.ClayCard
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens
import com.hisab.app.ui.theme.ClayText
import kotlin.math.absoluteValue

/** What was just done, so the screen can say so until the next action starts. */
enum class BakiNoticeKind { ADDED, RECEIVED, UNDONE }

data class BakiNotice(
    val kind: BakiNoticeKind,
    val amount: Money,
)

/**
 * One customer: what they owe, and every entry that led to it (Step 53).
 *
 * The big number is the sum of the ledger below it, never a stored field, and
 * each row ends on the balance after it — so the last line a shopkeeper reads
 * is the same number at the top, and a mistake in either place would show. The
 * two things done from here, adding baki and receiving a payment, are pinned to
 * the bottom where a thumb is, and stay there while the history scrolls.
 */
@Composable
fun CustomerDetailScreen(
    state: CustomerUiState,
    notice: BakiNotice?,
    onAddBaki: () -> Unit,
    onReceivePayment: () -> Unit,
    onUndo: (entryId: String) -> Unit,
    onBack: () -> Unit,
) {
    val customer = state.customer
    var undoingId by rememberSaveable { mutableStateOf<String?>(null) }

    // Undoing money is confirmed first, and the confirmation says what the balance
    // will become, so a mis-tap on a row is one more tap away from harm, not zero.
    val undoing = state.lines.firstOrNull { it.entry.id.value == undoingId && it.canUndo }
    if (undoing != null) {
        UndoDialog(
            line = undoing,
            balance = state.balance,
            onConfirm = {
                undoingId = null
                onUndo(undoing.entry.id.value)
            },
            onDismiss = { undoingId = null },
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
        CustomerHeader(
            title = customer?.name ?: stringResource(R.string.customers_title),
            titleIsUserText = customer != null,
            onBack = onBack,
        )

        when {
            !state.loaded -> {
                Unit
            }

            customer == null -> {
                CentredEmptyNote(title = stringResource(R.string.customer_not_found))
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("customer_ledger"),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "balance") { BalanceCard(state = state, phone = customer.phone) }

                    if (notice != null) {
                        item(key = "notice") { NoticeBanner(notice) }
                    }

                    item(key = "history-title") {
                        ClayText(
                            text = stringResource(R.string.baki_history_title),
                            size = 18,
                            weight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    if (state.lines.isEmpty()) {
                        item(key = "empty") { EmptyNote(title = stringResource(R.string.baki_history_empty)) }
                    }

                    items(items = state.lines, key = { it.entry.id.value }) { line ->
                        LedgerRow(line, onUndo = { undoingId = line.entry.id.value })
                    }
                }

                // Equal height whatever the language or font size makes of the labels,
                // so a longer Bangla label wrapping never leaves one button taller.
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ClayButton(
                        text = stringResource(R.string.baki_add_action),
                        onClick = onAddBaki,
                        style = ClayButtonStyle.SOFT,
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("button_add_baki"),
                    )
                    // Money coming in is the more frequent of the two, so it is the solid one.
                    ClayButton(
                        text = stringResource(R.string.baki_pay_action),
                        onClick = onReceivePayment,
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("button_receive_payment"),
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(
    state: CustomerUiState,
    phone: String?,
) {
    val status = state.status
    // An advance is shown as the amount held, not as a negative number.
    val shown = if (status == BalanceStatus.ADVANCE) Money(state.balance.minorUnits.absoluteValue) else state.balance

    ClayCard {
        if (phone != null) {
            ClayText(text = phone, size = 14, color = ClayColors.InkMuted, modifier = Modifier.padding(bottom = 8.dp))
        }
        ClayText(
            text =
                stringResource(
                    when (status) {
                        BalanceStatus.OWES -> R.string.baki_balance_label
                        BalanceStatus.ADVANCE -> R.string.baki_advance_label
                        BalanceStatus.CLEAR -> R.string.baki_clear_label
                    },
                ),
            size = 13,
            weight = FontWeight.Bold,
            color = ClayColors.InkMuted,
        )
        ClayText(
            text = moneyText(shown),
            size = 38,
            weight = FontWeight.Bold,
            color = statusColor(status),
            modifier = Modifier.padding(top = 2.dp).testTag("customer_balance"),
        )
        if (state.isOverdue) {
            ClayText(
                text = stringResource(R.string.baki_overdue_banner, moneyText(state.overdue)),
                size = 14,
                weight = FontWeight.Bold,
                color = ClayColors.Danger,
                modifier =
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .background(ClayColors.Danger.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("customer_overdue"),
            )
        }
    }
}

/** Confirms what was just recorded. It is state, not a timer, so it survives a rotation or a language switch. */
@Composable
private fun NoticeBanner(notice: BakiNotice) {
    ClayText(
        text =
            stringResource(
                when (notice.kind) {
                    BakiNoticeKind.ADDED -> R.string.baki_saved_credit
                    BakiNoticeKind.RECEIVED -> R.string.baki_saved_payment
                    BakiNoticeKind.UNDONE -> R.string.baki_undone_notice
                },
                moneyText(notice.amount),
            ),
        size = 14,
        weight = FontWeight.Bold,
        color = ClayColors.Money,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(ClayColors.Money.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("baki_notice"),
    )
}

@Composable
private fun LedgerRow(
    line: LedgerLine,
    onUndo: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val entry = line.entry
    val delta = entry.amountDelta
    val owesMore = delta.minorUnits >= 0
    val amount = moneyText(Money(delta.minorUnits.absoluteValue))

    // An entry that has been undone stays in the list, softened and marked, so the
    // history still shows what happened and the opposite entry has something to pair with.
    ClayCard(modifier = if (line.undone) Modifier.alpha(0.7f) else Modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                ClayText(text = typeLabel(entry.type), size = 16, weight = FontWeight.Bold)
                ClayText(
                    text = rememberWhenText(entry.time.occurredAt, locale),
                    size = 12,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                entry.dueDate?.let { due ->
                    ClayText(
                        text =
                            stringResource(
                                R.string.baki_due_on,
                                localizedDate(due, locale),
                            ),
                        size = 12,
                        color = ClayColors.InkMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (line.undone) {
                    Pill(
                        text = stringResource(R.string.baki_undone_label),
                        color = ClayColors.InkMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                // The sign, the colour and the label all say which way it went.
                ClayText(
                    text = (if (owesMore) "+ " else "− ") + amount,
                    size = 17,
                    weight = FontWeight.Bold,
                    color = if (owesMore) ClayColors.Danger else ClayColors.Money,
                )
                ClayText(
                    text = stringResource(R.string.baki_balance_after, moneyText(line.balanceAfter)),
                    size = 11,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (line.canUndo) {
            UndoLink(
                description = stringResource(R.string.baki_undo_action) + ": " + typeLabel(entry.type) + " " + amount,
                onClick = onUndo,
            )
        }
    }
}

/**
 * A quiet text link, like the rarer actions on the Stock screen, not a button:
 * undoing is the exception, and a button on every row would crowd out the
 * amounts. The words are small but the tap area is a full 48 dp high.
 */
@Composable
private fun UndoLink(
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("button_undo")
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.CenterEnd,
    ) {
        ClayText(text = stringResource(R.string.baki_undo_action), size = 14, weight = FontWeight.Bold, color = ClayColors.Primary)
    }
}

/**
 * Confirms undoing one entry. It shows the entry, then what the customer owes
 * now beside what they will owe after, and says plainly that the entry stays in
 * the list. Nothing is deleted: the confirm writes an opposite entry.
 */
@Composable
private fun UndoDialog(
    line: LedgerLine,
    balance: Money,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entry = line.entry
    val owesMore = entry.amountDelta.minorUnits >= 0
    val amount = moneyText(Money(entry.amountDelta.minorUnits.absoluteValue))

    Dialog(onDismissRequest = onDismiss) {
        // A solid ground under the clay, so the screen behind does not show through.
        ClayCard(modifier = Modifier.background(ClayColors.Surface, RoundedCornerShape(ClayDimens.CardCorner))) {
            ClayText(text = stringResource(R.string.baki_undo_title), size = 20, weight = FontWeight.Bold)

            Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    ClayText(text = typeLabel(entry.type), size = 15, weight = FontWeight.Bold)
                    // Several entries can share an amount; the time tells them apart.
                    ClayText(
                        text = rememberWhenText(entry.time.occurredAt, LocalConfiguration.current.locales[0]),
                        size = 12,
                        color = ClayColors.InkMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                ClayText(
                    text = (if (owesMore) "+ " else "\u2212 ") + amount,
                    size = 16,
                    weight = FontWeight.Bold,
                    color = if (owesMore) ClayColors.Danger else ClayColors.Money,
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                PreviewBalance(balance = balance, isAfter = false, modifier = Modifier.weight(1f))
                PreviewBalance(
                    balance = balanceAfterUndo(balance, entry),
                    isAfter = true,
                    valueTag = "undo_after",
                    modifier = Modifier.weight(1f),
                )
            }

            ClayText(
                text = stringResource(R.string.baki_undo_note),
                size = 13,
                color = ClayColors.InkMuted,
                modifier = Modifier.padding(top = 14.dp),
            )

            // One above the other, full width, so a long Bangla label never has to
            // wrap, whatever the font size. The action is first, the way out second.
            Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ClayButton(
                    text = stringResource(R.string.baki_undo_confirm),
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().testTag("button_confirm_undo"),
                )
                ClayButton(
                    text = stringResource(R.string.baki_undo_keep),
                    onClick = onDismiss,
                    style = ClayButtonStyle.SOFT,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun typeLabel(type: BakiEntryType): String =
    stringResource(
        when (type) {
            BakiEntryType.CREDIT_SALE -> R.string.baki_type_credit_sale
            BakiEntryType.CREDIT -> R.string.baki_type_credit
            BakiEntryType.PAYMENT -> R.string.baki_type_payment
            BakiEntryType.REVERSAL -> R.string.baki_type_reversal
            BakiEntryType.ENTRY_REVERSAL -> R.string.baki_type_entry_reversal
        },
    )
