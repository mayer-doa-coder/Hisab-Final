package com.hisab.app.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hisab.app.R
import com.hisab.app.data.customer.CustomerCreateResult
import com.hisab.app.domain.Money
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
import java.text.NumberFormat

/**
 * Who the shop has recorded, and what each one owes (Step 52).
 *
 * The first thing a shopkeeper wants from this screen is whom to ask for money,
 * so the list is already in that order — overdue first, then the biggest baki —
 * and the total owed sits above it. Every amount is worked out from that
 * person's baki entries (D001), which is why a payment recorded on the next
 * screen is already here when they come back.
 *
 * Nothing on it needs a network: it reads the phone's own database.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomerListScreen(
    state: CustomerListUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (CustomerFilter) -> Unit,
    onAddCustomer: (name: String, phone: String?, onResult: (CustomerCreateResult) -> Unit) -> Unit,
    onOpenCustomer: (String) -> Unit,
    onBack: () -> Unit,
) {
    var queryText by rememberSaveable { mutableStateOf(state.query) }
    var adding by rememberSaveable { mutableStateOf(false) }

    if (adding) {
        AddCustomerDialog(
            onAdd = onAddCustomer,
            onOpenCustomer = {
                adding = false
                onOpenCustomer(it)
            },
            onDismiss = { adding = false },
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
        CustomerHeader(title = stringResource(R.string.customers_title), onBack = onBack)

        ClayTextField(
            value = queryText,
            onValueChange = {
                queryText = it
                onQueryChange(it)
            },
            fieldTag = "field_customer_search",
            placeholder = stringResource(R.string.customers_search_hint),
            leadingIcon = painterResource(R.drawable.ic_search),
        )

        // Chips wrap onto a second line rather than being clipped, so a long
        // Bangla label or a large system font never hides a filter.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(CustomerFilter.ALL, R.string.customers_filter_all, state, onFilterChange)
            FilterChip(CustomerFilter.OWES, R.string.customers_filter_owes, state, onFilterChange)
            FilterChip(CustomerFilter.OVERDUE, R.string.customers_filter_overdue, state, onFilterChange)
        }

        Column(modifier = Modifier.weight(1f)) {
            when {
                // Nothing is said until the first read has come back, so an empty
                // screen never flashes "no customers" over real data.
                !state.loaded -> {
                    Unit
                }

                state.customerCount == 0 -> {
                    CentredEmptyNote(
                        title = stringResource(R.string.customers_empty_title),
                        subtitle = stringResource(R.string.customers_empty_subtitle),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("customer_list"),
                        contentPadding = PaddingValues(top = 14.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "summary") { SummaryCard(state.summary) }

                        if (state.rows.isEmpty()) {
                            item(key = "nobody") {
                                EmptyNote(
                                    title =
                                        stringResource(
                                            when {
                                                queryText.isNotBlank() -> R.string.customers_search_empty
                                                state.filter == CustomerFilter.OVERDUE -> R.string.customers_filter_empty_overdue
                                                else -> R.string.customers_filter_empty_owes
                                            },
                                        ),
                                )
                            }
                        }

                        items(items = state.rows, key = { it.customer.id }) { row ->
                            CustomerRowCard(row = row, onClick = { onOpenCustomer(row.customer.id) })
                        }
                    }
                }
            }
        }

        // Adding a customer is the one thing this screen does, so it sits at the
        // bottom where a thumb already is, and stays put while the list scrolls.
        ClayButton(
            text = stringResource(R.string.customers_add_action),
            onClick = { adding = true },
            leadingIcon = painterResource(R.drawable.ic_add),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("button_add_customer"),
        )
    }
}

@Composable
private fun FilterChip(
    filter: CustomerFilter,
    label: Int,
    state: CustomerListUiState,
    onFilterChange: (CustomerFilter) -> Unit,
) {
    ClayChip(
        label = stringResource(label),
        selected = state.filter == filter,
        onClick = { onFilterChange(filter) },
        modifier = Modifier.testTag("filter_${filter.name.lowercase()}"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCard(summary: CustomerSummary) {
    val locale = LocalConfiguration.current.locales[0]
    val count = NumberFormat.getIntegerInstance(locale)

    ClayCard {
        ClayText(text = stringResource(R.string.customers_total_owed_label), size = 13, color = ClayColors.InkMuted)
        ClayText(
            text = moneyText(summary.totalOwed),
            size = 32,
            weight = FontWeight.Bold,
            color = if (summary.totalOwed.minorUnits > 0) ClayColors.Danger else ClayColors.Ink,
            modifier = Modifier.padding(top = 2.dp).testTag("summary_total_owed"),
        )
        // Both counts share a line when they fit, so the summary stays short and
        // the first customers are visible without scrolling on a small phone.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            ClayText(
                text = stringResource(R.string.customers_owing_count, count.format(summary.owingCount)),
                size = 14,
                color = ClayColors.InkMuted,
            )
            if (summary.overdueCount > 0) {
                Pill(
                    text = stringResource(R.string.customers_overdue_count, count.format(summary.overdueCount)),
                    color = ClayColors.Danger,
                )
            }
        }
    }
}

@Composable
private fun CustomerRowCard(
    row: CustomerRow,
    onClick: () -> Unit,
) {
    ClayCard(
        onClick = onClick,
        // One spoken item per row — name, status and amount together — rather
        // than a dozen fragments for a screen reader to step through.
        modifier = Modifier.testTag("customer_row_${row.customer.id}").semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = row.customer.name)

            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                ClayUserText(
                    text = scriptAwareText(row.customer.name),
                    size = 17,
                    weight = FontWeight.Bold,
                    maxLines = 2,
                )
                row.customer.phone?.let {
                    ClayText(text = it, size = 13, color = ClayColors.InkMuted, modifier = Modifier.padding(top = 2.dp))
                }
                if (row.isOverdue) {
                    Pill(
                        text = stringResource(R.string.customers_overdue_badge),
                        color = ClayColors.Danger,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                val shown = if (row.status == BalanceStatus.ADVANCE) Money(-row.balance.minorUnits) else row.balance
                ClayText(
                    text = moneyText(shown),
                    size = 18,
                    weight = FontWeight.Bold,
                    color = statusColor(row.status),
                )
                ClayText(
                    text =
                        stringResource(
                            when (row.status) {
                                BalanceStatus.OWES -> R.string.customers_status_owes
                                BalanceStatus.CLEAR -> R.string.customers_status_clear
                                BalanceStatus.ADVANCE -> R.string.customers_status_advance
                            },
                        ),
                    size = 12,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Adding a customer without leaving the list.
 *
 * A name that is already taken is said out loud and the existing person is one
 * tap away, instead of a second "Rahim" being made or the two being silently
 * merged: two records for one person would split their baki, which is the
 * failure that actually loses a shopkeeper money (D035). On success the new
 * customer opens straight away, because Add baki is what comes next.
 */
@Composable
private fun AddCustomerDialog(
    onAdd: (name: String, phone: String?, onResult: (CustomerCreateResult) -> Unit) -> Unit,
    onOpenCustomer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf<Int?>(null) }
    var phoneError by rememberSaveable { mutableStateOf(false) }
    var takenId by rememberSaveable { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        // A solid ground under the clay, so the list behind the dialog does not
        // show through the card's translucent highlight and blur the words.
        ClayCard(modifier = Modifier.background(ClayColors.Surface, RoundedCornerShape(ClayDimens.CardCorner))) {
            ClayText(text = stringResource(R.string.customers_add_title), size = 20, weight = FontWeight.Bold)

            ClayTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                    takenId = null
                },
                fieldTag = "field_customer_name",
                label = stringResource(R.string.customer_name_label),
                placeholder = stringResource(R.string.customer_name_hint),
                errorText = nameError?.let { stringResource(it) },
                modifier = Modifier.padding(top = 16.dp),
            )

            takenId?.let { id ->
                ClayButton(
                    text = stringResource(R.string.customers_open_existing),
                    onClick = { onOpenCustomer(id) },
                    style = ClayButtonStyle.SOFT,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }

            ClayTextField(
                value = phone,
                onValueChange = {
                    phone = it
                    phoneError = false
                },
                fieldTag = "field_customer_phone",
                label = stringResource(R.string.customer_phone_label),
                placeholder = stringResource(R.string.customer_phone_hint),
                keyboardType = KeyboardType.Phone,
                errorText = if (phoneError) stringResource(R.string.error_phone_invalid) else null,
                userTypedText = false,
                modifier = Modifier.padding(top = 12.dp),
            )

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
                        val parsedPhone = parsePhone(phone)
                        when {
                            name.isBlank() -> {
                                nameError = R.string.error_name_required
                            }

                            parsedPhone is PhoneInput.Invalid -> {
                                phoneError = true
                            }

                            else -> {
                                val number = (parsedPhone as? PhoneInput.Valid)?.number
                                onAdd(name, number) { result ->
                                    when (result) {
                                        is CustomerCreateResult.Created -> {
                                            onOpenCustomer(result.customer.id)
                                        }

                                        is CustomerCreateResult.NameTaken -> {
                                            nameError = R.string.error_customer_name_taken
                                            takenId = result.existing.id
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("button_confirm_add_customer"),
                )
            }
        }
    }
}
