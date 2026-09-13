package com.hisab.app.ui.product

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
import androidx.compose.ui.unit.dp
import com.hisab.app.R
import com.hisab.app.data.product.ProductEntity
import com.hisab.app.data.product.purchasePrice
import com.hisab.app.data.product.sellingPrice
import com.hisab.app.domain.toTakaString
import com.hisab.app.ui.scriptAwareText
import com.hisab.app.ui.theme.ClayButton
import com.hisab.app.ui.theme.ClayCard
import com.hisab.app.ui.theme.ClayChip
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.ClayTextField
import com.hisab.app.ui.theme.ClayUserText

/**
 * The product list (Step 25) with search over names and aliases (Step 27).
 * Everything shown comes from the local database, so it works the same with
 * the network off.
 */
@Composable
fun ProductListScreen(
    state: ProductListUiState,
    onQueryChange: (String) -> Unit,
    onIncludeInactiveChange: (Boolean) -> Unit,
    onAddProduct: () -> Unit,
    onOpenProduct: (ProductEntity) -> Unit,
    onBack: () -> Unit,
) {
    // The box keeps its own text. Feeding it back from the database query
    // would mean every keystroke waits for a round trip, and fast typing then
    // lands letters out of order — typing "cook" produced "ookc" on a real
    // phone. The search itself still runs off the value handed upwards.
    var queryText by rememberSaveable { mutableStateOf(state.query) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ClayColors.Background)
                .safeDrawingPadding()
                .padding(horizontal = ClayDimens.ScreenPadding),
    ) {
        ScreenHeader(title = stringResource(R.string.products_title), onBack = onBack)

        ClayTextField(
            value = queryText,
            onValueChange = {
                queryText = it
                onQueryChange(it)
            },
            fieldTag = "field_search",
            placeholder = stringResource(R.string.product_search_hint),
            leadingIcon = painterResource(R.drawable.ic_search),
            trailingContent =
                if (queryText.isEmpty()) {
                    null
                } else {
                    {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.cd_clear_search),
                            tint = ClayColors.InkMuted,
                            modifier =
                                Modifier
                                    .size(22.dp)
                                    .clickable {
                                        queryText = ""
                                        onQueryChange("")
                                    },
                        )
                    }
                },
        )

        Row(modifier = Modifier.padding(top = 12.dp)) {
            ClayChip(
                label = stringResource(R.string.product_show_inactive),
                selected = state.includeInactive,
                onClick = { onIncludeInactiveChange(!state.includeInactive) },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.products.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(items = state.products, key = { it.id }) { product ->
                            ProductRow(product = product, onClick = { onOpenProduct(product) })
                        }
                    }
                }

                // Nothing is said until the first read has come back, so an
                // empty screen never flashes "no products" over real data.
                !state.loaded -> {
                    Unit
                }

                queryText.isNotEmpty() -> {
                    EmptyMessage(
                        title = stringResource(R.string.product_search_empty),
                        subtitle = null,
                    )
                }

                else -> {
                    EmptyMessage(
                        title = stringResource(R.string.product_empty_title),
                        subtitle = stringResource(R.string.product_empty_subtitle),
                    )
                }
            }
        }

        ClayButton(
            text = stringResource(R.string.product_add_title),
            onClick = onAddProduct,
            leadingIcon = painterResource(R.drawable.ic_add),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun ProductRow(
    product: ProductEntity,
    onClick: () -> Unit,
) {
    // LocalConfiguration, not LocalContext: this recomposes when the language
    // changes, so prices switch digits with the rest of the screen.
    val locale = LocalConfiguration.current.locales[0]
    val currency = stringResource(R.string.currency_symbol)

    ClayCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                // Product names are typed by the shopkeeper and may mix
                // scripts, so they get a font per script, not one font (D010).
                ClayUserText(
                    text = scriptAwareText(product.name),
                    size = 17,
                    weight = FontWeight.Bold,
                    maxLines = 2,
                )
                if (product.aliases.isNotEmpty()) {
                    ClayUserText(
                        text = scriptAwareText(product.aliases.joinToString(", ")),
                        size = 13,
                        color = ClayColors.InkMuted,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ClayText(
                        text = unitLabel(product.unit),
                        size = 12,
                        color = ClayColors.InkMuted,
                        modifier =
                            Modifier
                                .background(ClayColors.SurfaceSunken, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    if (!product.active) {
                        ClayText(
                            text = stringResource(R.string.product_inactive_badge),
                            size = 12,
                            weight = FontWeight.Bold,
                            color = ClayColors.Danger,
                            modifier =
                                Modifier
                                    .padding(start = 8.dp)
                                    .background(ClayColors.Danger.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                ClayText(
                    text = stringResource(R.string.product_price_sell_short),
                    size = 12,
                    color = ClayColors.InkMuted,
                )
                ClayUserText(
                    text = scriptAwareText("$currency ${product.sellingPrice.toTakaString(locale)}"),
                    size = 18,
                    weight = FontWeight.Bold,
                    color = ClayColors.Money,
                )
                product.purchasePrice?.let { purchase ->
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        ClayText(
                            text = stringResource(R.string.product_price_buy_short),
                            size = 12,
                            color = ClayColors.InkMuted,
                        )
                        ClayUserText(
                            text = scriptAwareText(" $currency ${purchase.toTakaString(locale)}"),
                            size = 12,
                            color = ClayColors.InkMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(
    title: String,
    subtitle: String?,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ClayText(text = title, size = 18, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        if (subtitle != null) {
            ClayText(text = subtitle, size = 14, color = ClayColors.InkMuted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_back),
            contentDescription = stringResource(R.string.cd_back),
            tint = ClayColors.Ink,
            modifier =
                Modifier
                    .size(40.dp)
                    .background(ClayColors.Surface, RoundedCornerShape(14.dp))
                    .clickable(onClick = onBack)
                    .padding(8.dp),
        )
        ClayText(
            text = title,
            size = 22,
            weight = FontWeight.Bold,
            modifier = Modifier.padding(start = 14.dp).weight(1f),
        )
    }
}
