package com.hisab.app.ui.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hisab.app.R
import com.hisab.app.ui.scriptAwareText
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.ClayUserText

/**
 * "Often bought together" (Step 93): products worth offering next to what is already
 * in the cart, one tap to add and one to dismiss.
 *
 * It sits above the product list and the cart both, so it is in view whichever the
 * shopkeeper is looking at. It is a single row that scrolls sideways, because three
 * Bangla product names rarely fit side by side and a taller block would push the
 * list the shopkeeper is actually tapping off a small screen.
 *
 * When the whole shop has too little history it says so in one muted line instead of
 * showing nothing, so "no suggestions yet" is not mistaken for a broken feature
 * (Step 95). When one product simply has no history, nothing is shown (Step 94).
 *
 * Product names are the shop's own text, drawn per script (D010); every label here is
 * a string resource in both languages.
 */
@Composable
fun SuggestionStrip(
    panel: SuggestionPanel,
    onAdd: (productId: String, unit: String) -> Unit,
    onDismiss: (productId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!panel.isVisible) return

    Column(modifier = modifier.fillMaxWidth().testTag("suggestion_strip")) {
        if (panel.items.isEmpty()) {
            ClayText(
                text = stringResource(R.string.sale_suggest_not_enough_history),
                size = 13,
                color = ClayColors.InkMuted,
                modifier = Modifier.testTag("suggestion_not_enough_history"),
            )
            return@Column
        }

        ClayText(
            text = stringResource(R.string.sale_suggest_title),
            size = 13,
            weight = FontWeight.Bold,
            color = ClayColors.InkMuted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items = panel.items, key = { it.product.id }) { suggested ->
                SuggestionChip(
                    suggested = suggested,
                    onAdd = { onAdd(suggested.product.id, suggested.product.unit) },
                    onDismiss = { onDismiss(suggested.product.id) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionChip(
    suggested: SuggestedProduct,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = suggested.product.name
    val id = suggested.product.id

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .heightIn(min = 48.dp)
                .background(ClayColors.PrimarySoft, RoundedCornerShape(16.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .testTag("suggestion_$id")
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onAdd)
                    .padding(start = 12.dp, end = 6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.sale_suggest_add_desc, name),
                tint = ClayColors.Primary,
                modifier = Modifier.size(20.dp),
            )
            ClayUserText(
                text = scriptAwareText(name),
                size = 14,
                weight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = 180.dp),
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.sale_suggest_dismiss_desc, name),
            tint = ClayColors.InkMuted,
            modifier =
                Modifier
                    .testTag("suggestion_dismiss_$id")
                    .size(48.dp)
                    .clickable(onClick = onDismiss)
                    .padding(14.dp),
        )
    }
}
