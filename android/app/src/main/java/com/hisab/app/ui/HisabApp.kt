package com.hisab.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hisab.app.AppLanguage
import com.hisab.app.R
import com.hisab.app.data.product.ProductEntity
import com.hisab.app.data.product.ProductWriteResult
import com.hisab.app.ui.product.ProductEditScreen
import com.hisab.app.ui.product.ProductListScreen
import com.hisab.app.ui.product.ProductViewModel
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.HisabTheme

private const val ROUTE_HOME = "home"
private const val ROUTE_PRODUCTS = "products"
private const val ROUTE_PRODUCT_FORM = "product_form"

/**
 * Which screen is showing. Kept as a saved string rather than a navigation
 * library: there are three screens, and adding a library for that would be
 * structure ahead of need (CLAUDE.md). The id of the product being edited is
 * saved too, so the form survives the Activity being recreated.
 */
@Composable
fun HisabApp(
    language: AppLanguage,
    onChangeLanguage: (AppLanguage) -> Unit,
) {
    HisabTheme(language) {
        val viewModel: ProductViewModel = viewModel()
        val state by viewModel.uiState.collectAsState()
        var route by rememberSaveable { mutableStateOf(ROUTE_HOME) }
        var editingId by rememberSaveable { mutableStateOf<String?>(null) }
        var showConflict by rememberSaveable { mutableStateOf(false) }

        when (route) {
            ROUTE_PRODUCTS -> {
                BackHandler { route = ROUTE_HOME }
                ProductListScreen(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onIncludeInactiveChange = viewModel::onIncludeInactiveChange,
                    onAddProduct = {
                        editingId = null
                        showConflict = false
                        route = ROUTE_PRODUCT_FORM
                    },
                    onOpenProduct = { product ->
                        editingId = product.id
                        showConflict = false
                        route = ROUTE_PRODUCT_FORM
                    },
                    onBack = { route = ROUTE_HOME },
                )
            }

            ROUTE_PRODUCT_FORM -> {
                BackHandler { route = ROUTE_PRODUCTS }
                ProductFormRoute(
                    viewModel = viewModel,
                    editingId = editingId,
                    showConflict = showConflict,
                    onConflict = { showConflict = true },
                    onDone = { route = ROUTE_PRODUCTS },
                )
            }

            else -> {
                HomeScreen(
                    language = language,
                    onChangeLanguage = onChangeLanguage,
                    onOpenProducts = { route = ROUTE_PRODUCTS },
                )
            }
        }
    }
}

@Composable
private fun ProductFormRoute(
    viewModel: ProductViewModel,
    editingId: String?,
    showConflict: Boolean,
    onConflict: () -> Unit,
    onDone: () -> Unit,
) {
    var existing by remember(editingId) { mutableStateOf<ProductEntity?>(null) }
    var loading by remember(editingId) { mutableStateOf(editingId != null) }

    LaunchedEffect(editingId) {
        if (editingId != null) {
            existing = viewModel.byId(editingId)
            loading = false
        }
    }

    if (loading) {
        LoadingScreen()
        return
    }

    val product = existing
    val finish = { result: ProductWriteResult ->
        if (result is ProductWriteResult.Saved) onDone() else onConflict()
    }

    ProductEditScreen(
        existing = product,
        showConflict = showConflict,
        onSave = { valid ->
            if (product == null) {
                viewModel.create(valid) { onDone() }
            } else {
                viewModel.update(product.id, product.revision, valid, finish)
            }
        },
        onToggleActive = {
            if (product != null) {
                viewModel.setActive(product.id, product.revision, !product.active, finish)
            }
        },
        onDelete = {
            if (product != null) {
                viewModel.delete(product.id, product.revision, finish)
            }
        },
        onBack = onDone,
    )
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(ClayColors.Background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ClayText(text = stringResource(R.string.product_loading), size = 16, color = ClayColors.InkMuted)
    }
}
