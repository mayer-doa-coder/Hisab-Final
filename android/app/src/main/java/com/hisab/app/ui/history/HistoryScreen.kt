package com.hisab.app.ui.history

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hisab.app.R
import com.hisab.app.data.sale.total
import com.hisab.app.data.stock.quantityDelta
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SalePayment
import com.hisab.app.domain.StockMovementType
import com.hisab.app.domain.toDisplayString
import com.hisab.app.domain.toTakaString
import com.hisab.app.ui.product.ScreenHeader
import com.hisab.app.ui.scriptAwareText
import com.hisab.app.ui.theme.ClayCard
import com.hisab.app.ui.theme.ClayChip
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.ClayUserText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Sales and stock movements, newest first (Step 42).
 *
 * Both are shown on one list because a shopkeeper asking "what happened
 * today?" does not think of them as two systems. They are told apart by a
 * coloured label and by which direction the number points: money coming in is
 * green, a reversal is red, stock arriving is positive, stock leaving is
 * negative.
 *
 * Every row shows when it actually happened (`occurredAt`), never when the
 * server saw it (D019) — a sale made offline at 9am and synced at 6pm still
 * reads as 9am, because that is when the customer paid.
 *
 * Nothing on this screen edits anything. History is never rewritten
 * (CLAUDE.md). Tapping a sale opens it, and a sale can be undone from there
 * by recording its opposite beside it — never by changing it (Step 43, D033).
 * A sale that has been undone says so here, so the list tells the truth at a
 * glance without opening anything.
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onFilterChange: (HistoryFilter) -> Unit,
    onOpenSale: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ClayColors.Background)
                .safeDrawingPadding()
                .padding(horizontal = ClayDimens.ScreenPadding),
    ) {
        ScreenHeader(title = stringResource(R.string.history_title), onBack = onBack)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClayChip(
                label = stringResource(R.string.history_filter_all),
                selected = state.filter == HistoryFilter.ALL,
                onClick = { onFilterChange(HistoryFilter.ALL) },
            )
            ClayChip(
                label = stringResource(R.string.history_filter_sales),
                selected = state.filter == HistoryFilter.SALES,
                onClick = { onFilterChange(HistoryFilter.SALES) },
            )
            ClayChip(
                label = stringResource(R.string.history_filter_stock),
                selected = state.filter == HistoryFilter.STOCK,
                onClick = { onFilterChange(HistoryFilter.STOCK) },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.entries.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(items = state.entries, key = ::entryKey) { entry ->
                            when (entry) {
                                is HistoryEntry.Sale -> SaleRow(entry, onClick = { onOpenSale(entry.sale.id) })
                                is HistoryEntry.Movement -> MovementRow(entry)
                            }
                        }
                    }
                }

                // Nothing is said until the first read has come back, so an
                // empty screen never flashes "nothing yet" over real data.
                state.loaded -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        ClayText(
                            text = stringResource(R.string.history_empty_title),
                            size = 18,
                            weight = FontWeight.Bold,
                        )
                        ClayText(
                            text = stringResource(R.string.history_empty_subtitle),
                            size = 14,
                            color = ClayColors.InkMuted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun entryKey(entry: HistoryEntry): String =
    when (entry) {
        is HistoryEntry.Sale -> "sale:${entry.sale.id}"
        is HistoryEntry.Movement -> "move:${entry.movement.id}"
    }

@Composable
private fun SaleRow(
    entry: HistoryEntry.Sale,
    onClick: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val currency = stringResource(R.string.currency_symbol)
    val sale = entry.sale
    val reversal = sale.reversesSaleId != null

    val label =
        when {
            reversal -> R.string.history_sale_reversal
            sale.payment == SalePayment.CREDIT.name -> R.string.history_sale_credit
            else -> R.string.history_sale_cash
        }
    val accent = if (reversal) ClayColors.Danger else ClayColors.Money

    ClayCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(text = stringResource(label), colour = accent)
                    if (entry.reversed) {
                        Row(modifier = Modifier.padding(start = 8.dp)) {
                            Badge(
                                text = stringResource(R.string.sale_reversed_badge),
                                colour = ClayColors.Danger,
                            )
                        }
                    }
                }
                ClayText(
                    text = stringResource(R.string.sale_items_count, entry.lineCount),
                    size = 13,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ClayUserText(
                    text = scriptAwareText(rememberWhenText(sale.occurredAt, locale)),
                    size = 12,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ClayUserText(
                text = scriptAwareText("$currency ${sale.total.toTakaString(locale)}"),
                size = 20,
                weight = FontWeight.Bold,
                color = accent,
            )
        }
    }
}

@Composable
private fun MovementRow(entry: HistoryEntry.Movement) {
    val locale = LocalConfiguration.current.locales[0]
    val movement = entry.movement
    val delta = movement.quantityDelta
    val adds = delta.scaledUnits >= 0

    val label =
        when (movement.movementType) {
            StockMovementType.RESTOCK.name -> R.string.history_movement_restock
            StockMovementType.RETURN.name -> R.string.history_movement_return
            StockMovementType.DAMAGE.name -> R.string.history_movement_damage
            else -> R.string.history_movement_correction
        }
    val accent = if (adds) ClayColors.Primary else ClayColors.Danger

    ClayCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Badge(text = stringResource(label), colour = accent)
                if (entry.productName == null) {
                    ClayText(
                        text = stringResource(R.string.history_unknown_product),
                        size = 14,
                        color = ClayColors.InkMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    ClayUserText(
                        text = scriptAwareText(entry.productName),
                        size = 16,
                        weight = FontWeight.Bold,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                ClayUserText(
                    text = scriptAwareText(rememberWhenText(movement.occurredAt, locale)),
                    size = 12,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // The sign is kept, not dropped: "−3" and "+3" are different
            // events, and a history that hides which way stock went is no
            // history at all.
            ClayUserText(
                text = scriptAwareText((if (adds) "+" else "−") + delta.absolute().toDisplayString(locale)),
                size = 20,
                weight = FontWeight.Bold,
                color = accent,
            )
        }
    }
}

@Composable
internal fun Badge(
    text: String,
    colour: Color,
) {
    ClayText(
        text = text,
        size = 12,
        weight = FontWeight.Bold,
        color = colour,
        modifier =
            Modifier
                .background(colour.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun Quantity.absolute(): Quantity = if (scaledUnits < 0) Quantity(-scaledUnits) else this

/**
 * When it happened, in the phone's own zone and the screen's own language.
 *
 * Today and yesterday are named rather than dated, because that is how a
 * shopkeeper checking the day's takings reads a list. Anything older gets its
 * date, since "four days ago" stops being useful quickly.
 *
 * The two day names are passed in rather than looked up here, so this stays a
 * plain function that a unit test can call without a phone.
 */
fun whenText(
    instant: Instant,
    locale: Locale,
    todayLabel: String,
    yesterdayLabel: String,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): String {
    val date = instant.atZone(zone).toLocalDate()
    val time =
        DateTimeFormatter
            .ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(instant)

    val dayText =
        when (date) {
            today -> todayLabel
            today.minusDays(1) -> yesterdayLabel
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
        }

    return "$dayText · $time"
}

/** The same thing, with the two day names read from resources. */
@Composable
internal fun rememberWhenText(
    instant: Instant,
    locale: Locale,
): String =
    whenText(
        instant = instant,
        locale = locale,
        todayLabel = stringResource(R.string.history_today),
        yesterdayLabel = stringResource(R.string.history_yesterday),
    )
