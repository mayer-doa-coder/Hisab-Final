package com.hisab.app.ui.product

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hisab.app.R
import com.hisab.app.data.product.ProductEntity
import com.hisab.app.data.product.purchasePrice
import com.hisab.app.data.product.sellingPrice
import com.hisab.app.domain.ProductFormError
import com.hisab.app.domain.ProductFormInput
import com.hisab.app.domain.ProductFormResult
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.toEditableTaka
import com.hisab.app.domain.validateProductForm
import com.hisab.app.ui.theme.ClayButton
import com.hisab.app.ui.theme.ClayButtonStyle
import com.hisab.app.ui.theme.ClayChip
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.ClayTextField

/**
 * Add or edit one product (Step 26). `existing` being null means adding.
 *
 * Saving writes straight to the local database and returns — nothing here
 * waits for a network call, which is what makes it work in airplane mode.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductEditScreen(
    existing: ProductEntity?,
    showConflict: Boolean,
    onSave: (ProductFormResult.Valid) -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var aliasesText by
        rememberSaveable(existing?.id) { mutableStateOf(existing?.aliases?.joinToString(", ").orEmpty()) }
    var unit by rememberSaveable(existing?.id) { mutableStateOf(existing?.unit ?: ProductUnits.DEFAULT) }
    var sellingPriceText by
        rememberSaveable(existing?.id) { mutableStateOf(existing?.sellingPrice?.toEditableTaka().orEmpty()) }
    var purchasePriceText by
        rememberSaveable(existing?.id) { mutableStateOf(existing?.purchasePrice?.toEditableTaka().orEmpty()) }
    var errors by remember(existing?.id) { mutableStateOf(emptySet<ProductFormError>()) }
    var confirmingDelete by rememberSaveable(existing?.id) { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ClayColors.Background)
                .safeDrawingPadding()
                .padding(horizontal = ClayDimens.ScreenPadding),
    ) {
        ScreenHeader(
            title =
                stringResource(
                    if (existing == null) R.string.product_add_title else R.string.product_edit_title,
                ),
            onBack = onBack,
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    // Keeps the last row clear of the Save bar underneath,
                    // including its clay shadow.
                    .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showConflict) {
                ClayText(
                    text = stringResource(R.string.product_conflict_message),
                    size = 14,
                    color = ClayColors.Danger,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(ClayColors.Danger.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                )
            }

            ClayTextField(
                value = name,
                onValueChange = { name = it },
                fieldTag = "field_name",
                label = stringResource(R.string.product_name_label),
                placeholder = stringResource(R.string.product_name_hint),
                errorText =
                    if (ProductFormError.NAME_REQUIRED in errors) {
                        stringResource(R.string.error_name_required)
                    } else {
                        null
                    },
            )

            ClayTextField(
                value = aliasesText,
                onValueChange = { aliasesText = it },
                fieldTag = "field_aliases",
                label = stringResource(R.string.product_aliases_label),
                placeholder = stringResource(R.string.product_aliases_hint),
            )

            Column {
                ClayText(
                    text = stringResource(R.string.product_unit_label),
                    size = 13,
                    weight = FontWeight.Bold,
                    color = ClayColors.InkMuted,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProductUnits.ALL.forEach { code ->
                        ClayChip(
                            label = unitLabel(code),
                            selected = unit == code,
                            onClick = { unit = code },
                        )
                    }
                }
            }

            ClayTextField(
                value = sellingPriceText,
                onValueChange = { sellingPriceText = it },
                fieldTag = "field_selling",
                label = stringResource(R.string.product_selling_price_label),
                placeholder = stringResource(R.string.currency_symbol),
                keyboardType = KeyboardType.Decimal,
                errorText =
                    when {
                        ProductFormError.SELLING_PRICE_REQUIRED in errors -> {
                            stringResource(R.string.error_selling_price_required)
                        }

                        ProductFormError.SELLING_PRICE_INVALID in errors -> {
                            stringResource(R.string.error_price_invalid)
                        }

                        else -> {
                            null
                        }
                    },
            )

            ClayTextField(
                value = purchasePriceText,
                onValueChange = { purchasePriceText = it },
                fieldTag = "field_purchase",
                label = stringResource(R.string.product_purchase_price_label),
                placeholder = stringResource(R.string.currency_symbol),
                keyboardType = KeyboardType.Decimal,
                errorText =
                    if (ProductFormError.PURCHASE_PRICE_INVALID in errors) {
                        stringResource(R.string.error_price_invalid)
                    } else {
                        null
                    },
            )

            if (existing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ClayButton(
                        text =
                            stringResource(
                                if (existing.active) R.string.action_deactivate else R.string.action_activate,
                            ),
                        onClick = onToggleActive,
                        style = ClayButtonStyle.SOFT,
                    )
                    ClayButton(
                        text = stringResource(R.string.action_delete),
                        onClick = { confirmingDelete = true },
                        style = ClayButtonStyle.DANGER,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClayButton(
                text = stringResource(R.string.action_cancel),
                onClick = onBack,
                style = ClayButtonStyle.SOFT,
                modifier = Modifier.weight(1f),
            )
            ClayButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    val result =
                        validateProductForm(
                            ProductFormInput(
                                name = name,
                                aliasesText = aliasesText,
                                unit = unit,
                                sellingPriceText = sellingPriceText,
                                purchasePriceText = purchasePriceText,
                                active = existing?.active ?: true,
                            ),
                        )
                    when (result) {
                        is ProductFormResult.Valid -> {
                            errors = emptySet()
                            onSave(result)
                        }

                        is ProductFormResult.Invalid -> {
                            errors = result.errors
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { ClayText(text = stringResource(R.string.confirm_delete_title), size = 18, weight = FontWeight.Bold) },
            text = { ClayText(text = stringResource(R.string.confirm_delete_message), size = 14, color = ClayColors.InkMuted) },
            confirmButton = {
                ClayButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    style = ClayButtonStyle.DANGER,
                )
            },
            dismissButton = {
                ClayButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { confirmingDelete = false },
                    style = ClayButtonStyle.SOFT,
                )
            },
            containerColor = ClayColors.Surface,
            shape = RoundedCornerShape(ClayDimens.CardCorner),
        )
    }
}
