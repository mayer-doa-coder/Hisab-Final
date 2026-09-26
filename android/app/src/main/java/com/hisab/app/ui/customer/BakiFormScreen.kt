package com.hisab.app.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hisab.app.R
import com.hisab.app.domain.Money
import com.hisab.app.domain.toEditableTaka
import com.hisab.app.ui.sale.DueDate
import com.hisab.app.ui.sale.parseDueDate
import com.hisab.app.ui.theme.ClayButton
import com.hisab.app.ui.theme.ClayCard
import com.hisab.app.ui.theme.ClayChip
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.ClayTextField
import java.time.LocalDate

/** The two things a shopkeeper records by hand against a customer. */
enum class BakiFormKind {
    /** The customer owes more, with no sale behind it (Step 54). */
    ADD_CREDIT,

    /** The customer paid something back (Step 55). */
    RECEIVE_PAYMENT,
}

/**
 * Add Baki and Receive Payment (Steps 54 and 55): one form in two modes, so
 * they look and behave the same and cannot drift apart.
 *
 * Built for speed at a counter: the amount field takes the cursor as the screen
 * opens with the number keypad already up, and Save stays above the keyboard.
 * Before anything is written it shows what the customer owes now and what they
 * will owe after, side by side, so the shopkeeper checks the arithmetic against
 * their own head rather than trusting it afterwards — the same idea as the
 * Stock screen.
 *
 * The balance shown is the live one from the ledger. It is never a copy typed
 * into the form, so it cannot go stale while the shopkeeper is typing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BakiFormScreen(
    kind: BakiFormKind,
    state: CustomerUiState,
    onSave: (amount: Money, dueDate: LocalDate?) -> Unit,
    onBack: () -> Unit,
) {
    val customer = state.customer

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ClayColors.Background)
                .safeDrawingPadding()
                .padding(horizontal = ClayDimens.ScreenPadding),
    ) {
        CustomerHeader(
            title =
                stringResource(
                    if (kind == BakiFormKind.ADD_CREDIT) R.string.baki_add_action else R.string.baki_pay_action,
                ),
            subtitle = customer?.name,
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
                FormBody(kind = kind, state = state, customerId = customer.id, onSave = onSave)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FormBody(
    kind: BakiFormKind,
    state: CustomerUiState,
    customerId: String,
    onSave: (Money, LocalDate?) -> Unit,
) {
    var amountText by rememberSaveable(kind, customerId) { mutableStateOf("") }
    var dueText by rememberSaveable(kind, customerId) { mutableStateOf("") }
    var amountError by rememberSaveable(kind, customerId) { mutableStateOf(false) }
    var dueError by rememberSaveable(kind, customerId) { mutableStateOf(false) }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val amount = parseBakiAmount(amountText)
    val after =
        if (kind == BakiFormKind.ADD_CREDIT) {
            balanceAfterCredit(state.balance, amount)
        } else {
            balanceAfterPayment(state.balance, amount)
        }

    val scroll = rememberScrollState()
    val overpaying = kind == BakiFormKind.RECEIVE_PAYMENT && isOverpayment(state.balance, amount)
    // The note explains what is about to happen, so it must never sit half-hidden
    // under the keyboard. It is scrolled into view when it appears, and again if
    // the room changes (the keypad opening) while it is showing.
    LaunchedEffect(overpaying, scroll.maxValue) {
        if (overpaying) scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ClayTextField(
            value = amountText,
            onValueChange = {
                amountText = it
                amountError = false
            },
            fieldTag = "field_baki_amount",
            focusRequester = focus,
            label =
                stringResource(
                    if (kind == BakiFormKind.ADD_CREDIT) R.string.baki_add_amount_label else R.string.baki_pay_amount_label,
                ) + " (" + stringResource(R.string.currency_symbol) + ")",
            placeholder = stringResource(R.string.baki_amount_hint),
            keyboardType = KeyboardType.Decimal,
            errorText = if (amountError) stringResource(R.string.error_amount_invalid) else null,
        )

        // Most payments are the whole amount, so that is one tap, not typing it out.
        if (kind == BakiFormKind.RECEIVE_PAYMENT && state.balance.minorUnits > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ClayChip(
                    label = stringResource(R.string.baki_pay_full) + " · " + moneyText(state.balance),
                    selected = amountText.trim() == state.balance.toEditableTaka(),
                    onClick = {
                        amountText = state.balance.toEditableTaka()
                        amountError = false
                    },
                    modifier = Modifier.testTag("chip_pay_full"),
                )
            }
        }

        if (kind == BakiFormKind.ADD_CREDIT) {
            ClayTextField(
                value = dueText,
                onValueChange = {
                    dueText = it
                    dueError = false
                },
                fieldTag = "field_baki_due",
                label = stringResource(R.string.sale_due_date_label),
                placeholder = stringResource(R.string.sale_due_date_hint),
                keyboardType = KeyboardType.Number,
                errorText = if (dueError) stringResource(R.string.error_due_date_invalid) else null,
            )
        }

        ClayCard {
            Row(modifier = Modifier.fillMaxWidth()) {
                PreviewBalance(balance = state.balance, isAfter = false, modifier = Modifier.weight(1f))
                PreviewBalance(balance = after, isAfter = true, valueTag = "baki_after", modifier = Modifier.weight(1f))
            }
        }

        // Informing, not blocking: the money is in the shopkeeper's hand either
        // way, so a payment larger than what is owed is recorded (D041).
        if (overpaying) {
            ClayText(
                text = stringResource(R.string.baki_pay_overpay_note),
                size = 13,
                color = ClayColors.Ink,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(ClayColors.PrimarySoft, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("baki_overpay_note"),
            )
        }
    }

    ClayButton(
        text = stringResource(if (kind == BakiFormKind.ADD_CREDIT) R.string.baki_add_action else R.string.baki_pay_confirm),
        onClick = {
            val due = if (kind == BakiFormKind.ADD_CREDIT) parseDueDate(dueText) else DueDate.None
            when {
                amount == null -> amountError = true
                due is DueDate.Invalid -> dueError = true
                else -> onSave(amount, (due as? DueDate.On)?.date)
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("button_save_baki"),
    )
}

/**
 * One of the two balances in the preview. A customer who has paid ahead is
 * shown as "paid in advance" with the amount held, never as a negative "owes",
 * which reads as a mistake. Null means the amount typed is not valid yet.
 */
@Composable
internal fun PreviewBalance(
    balance: Money?,
    isAfter: Boolean,
    modifier: Modifier = Modifier,
    valueTag: String? = null,
) {
    val status = balance?.let(::balanceStatus)
    val label =
        when {
            status == BalanceStatus.ADVANCE -> R.string.baki_advance_label
            isAfter -> R.string.baki_owes_after
            else -> R.string.baki_balance_label
        }
    val text =
        when {
            balance == null -> "—"
            status == BalanceStatus.ADVANCE -> moneyText(Money(-balance.minorUnits))
            else -> moneyText(balance)
        }
    val color =
        when {
            balance == null -> ClayColors.InkMuted
            status == BalanceStatus.ADVANCE -> ClayColors.Money
            isAfter -> ClayColors.Primary
            else -> statusColor(status!!)
        }

    Column(modifier = modifier) {
        ClayText(text = stringResource(label), size = 12, color = ClayColors.InkMuted)
        ClayText(
            text = text,
            size = 22,
            weight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(top = 2.dp).then(if (valueTag == null) Modifier else Modifier.testTag(valueTag)),
        )
    }
}
