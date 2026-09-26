package com.hisab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisab.app.AppLanguage
import com.hisab.app.R
import com.hisab.app.ui.theme.ClayButton
import com.hisab.app.ui.theme.ClayButtonStyle
import com.hisab.app.ui.theme.ClayCard
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens
import com.hisab.app.ui.theme.ClayText

/**
 * Home, arranged by how often a shopkeeper does each thing rather than by
 * which milestone built it.
 *
 * Recording a sale happens dozens of times a day, so it is one large button
 * at the top with nothing above it to scroll past. Stock and history are
 * checked a few times a day and sit next to each other. Products and sync are
 * set-up and housekeeping, so they are quieter and further down.
 */
@Composable
fun HomeScreen(
    language: AppLanguage,
    onChangeLanguage: (AppLanguage) -> Unit,
    onOpenSale: () -> Unit,
    onOpenStock: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenCustomers: () -> Unit,
    onOpenProducts: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val font = fontFor(language)
    val otherLanguage = language.other()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ClayColors.Background)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(ClayDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.welcome_title),
            fontFamily = font,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = ClayColors.Ink,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.welcome_subtitle),
            fontFamily = font,
            fontSize = 15.sp,
            color = ClayColors.InkMuted,
            modifier = Modifier.padding(top = 6.dp, bottom = 22.dp),
        )

        // The one thing done most often, given the most room.
        ClayButton(
            text = stringResource(R.string.sale_open),
            onClick = onOpenSale,
            leadingIcon = painterResource(R.drawable.ic_cart),
            modifier = Modifier.fillMaxWidth(),
        )

        // Checking who owes money and taking a payment happen several times a
        // day, so this sits straight under selling, ahead of stock and history.
        ClayCard(modifier = Modifier.padding(top = 14.dp), onClick = onOpenCustomers) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_customers),
                    contentDescription = null,
                    tint = ClayColors.Primary,
                    modifier =
                        Modifier
                            .size(46.dp)
                            .background(ClayColors.PrimarySoft, RoundedCornerShape(16.dp))
                            .padding(11.dp),
                )
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    ClayText(text = stringResource(R.string.customers_open), size = 16, weight = FontWeight.Bold)
                    ClayText(
                        text = stringResource(R.string.customers_open_subtitle),
                        size = 13,
                        color = ClayColors.InkMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HomeTile(
                label = stringResource(R.string.stock_open),
                icon = R.drawable.ic_stock,
                onClick = onOpenStock,
                modifier = Modifier.weight(1f),
            )
            HomeTile(
                label = stringResource(R.string.history_open),
                icon = R.drawable.ic_history,
                onClick = onOpenHistory,
                modifier = Modifier.weight(1f),
            )
        }

        ClayButton(
            text = stringResource(R.string.products_open),
            onClick = onOpenProducts,
            style = ClayButtonStyle.SOFT,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )

        ClayButton(
            text = stringResource(R.string.sync_open),
            onClick = onOpenSync,
            style = ClayButtonStyle.SOFT,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )

        Text(
            text = stringResource(R.string.action_change_language),
            fontFamily = font,
            fontSize = 14.sp,
            color = ClayColors.InkMuted,
            modifier = Modifier.padding(top = 26.dp),
        )
        ClayButton(
            // The button names the language you would switch to, written in
            // its own script — so it takes that language's font, not the
            // current screen's font (D010).
            text =
                stringResource(
                    when (otherLanguage) {
                        AppLanguage.BANGLA -> R.string.language_bangla
                        AppLanguage.ENGLISH -> R.string.language_english
                    },
                ),
            onClick = { onChangeLanguage(otherLanguage) },
            style = ClayButtonStyle.SOFT,
            modifier = Modifier.padding(top = 10.dp, bottom = 12.dp),
        )
    }
}

/** A square-ish tile: icon above its label, so two fit side by side and stay readable. */
@Composable
private fun HomeTile(
    label: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClayCard(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = ClayColors.Primary,
                modifier =
                    Modifier
                        .size(46.dp)
                        .background(ClayColors.PrimarySoft, RoundedCornerShape(16.dp))
                        .clickable(onClick = onClick)
                        .padding(11.dp),
            )
            ClayText(
                text = label,
                size = 14,
                weight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
