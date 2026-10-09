package com.hisab.app.ui.sale

import com.hisab.app.data.product.ProductEntity
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.suggest.ComplementResult
import com.hisab.app.domain.suggest.ComplementRule
import com.hisab.app.domain.suggest.Cooccurrence
import com.hisab.app.domain.suggest.NotEnoughDataReason
import com.hisab.app.domain.suggest.SuggestionSettings
import com.hisab.app.domain.suggest.rankComplements

// What the New Sale screen shows as suggestions (Step 93).
//
// Plain functions over plain values, so each rule below is tested without a phone
// (`SaleSuggestionsTest`). The view model only feeds them the current cart, the
// products that can be sold and what has been dismissed.

/** How many suggestions are shown at once. PRD section 17.1's own example shows three. */
const val MAX_SUGGESTIONS_SHOWN = 3

/** A product offered next to the cart, with the rule that justifies it. */
data class SuggestedProduct(
    val product: ProductEntity,
    /** Why: the strongest rule behind it. Kept so a suggestion is always traceable to sales (PRD 17.4). */
    val rule: ComplementRule,
)

/** What, if anything, to say when there is nothing to suggest. */
enum class SuggestionHint {
    NONE,

    /**
     * The whole shop has too little sales history for suggestions to mean anything yet.
     * Said once, quietly, so a new shopkeeper can tell "no suggestions yet" from
     * "this feature does not work" (Step 95).
     */
    NOT_ENOUGH_HISTORY,
}

data class SuggestionPanel(
    val items: List<SuggestedProduct>,
    val hint: SuggestionHint,
) {
    val isVisible: Boolean get() = items.isNotEmpty() || hint != SuggestionHint.NONE

    companion object {
        val EMPTY = SuggestionPanel(emptyList(), SuggestionHint.NONE)
    }
}

/**
 * The suggestions for the cart as it is now.
 *
 * - The basis is the whole cart, so the list changes as items are added (PRD 17.2).
 * - Only products that can be sold today are offered: [sellable] is the active,
 *   undeleted catalogue, never the search-filtered list, because a suggestion for
 *   something the shop no longer stocks would be a button that adds nothing.
 * - A product the shopkeeper dismissed stays gone for the rest of this sale.
 * - [model] is null while the history is still being read: nothing is shown, and
 *   nothing is said, rather than flashing a message that is gone a moment later.
 *
 * "Not enough history" is shown only when the *shop* is too new. A product with no
 * history in an otherwise busy shop shows nothing at all — Step 94: no suggestion
 * rather than a guess, and no message either, since nothing is wrong.
 */
fun suggestionPanelFor(
    model: Cooccurrence?,
    cartProductIds: Collection<String>,
    sellable: List<ProductEntity>,
    dismissed: Set<String>,
    topN: Int = MAX_SUGGESTIONS_SHOWN,
    settings: SuggestionSettings = SuggestionSettings(),
): SuggestionPanel {
    if (model == null || cartProductIds.isEmpty()) return SuggestionPanel.EMPTY

    val byId = sellable.associateBy { it.id }
    val result =
        rankComplements(
            model = model,
            productIds = cartProductIds.map { EntityId(it) },
            topN = topN,
            settings = settings,
            eligible = { it.value in byId && it.value !in dismissed },
        )

    return when (result) {
        is ComplementResult.Ranked -> {
            SuggestionPanel(
                items = result.suggestions.map { SuggestedProduct(byId.getValue(it.productId.value), it.strongest) },
                hint = SuggestionHint.NONE,
            )
        }

        is ComplementResult.NotEnoughData -> {
            SuggestionPanel(
                items = emptyList(),
                hint = if (result.reason == NotEnoughDataReason.SHOP_HISTORY) SuggestionHint.NOT_ENOUGH_HISTORY else SuggestionHint.NONE,
            )
        }
    }
}
