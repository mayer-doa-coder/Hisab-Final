package com.hisab.app.domain

/**
 * Checking the add/edit product form, kept away from the screen so it can be
 * tested on its own and can never disagree with itself between the two
 * places a product is written.
 */
enum class ProductFormError {
    NAME_REQUIRED,
    SELLING_PRICE_REQUIRED,
    SELLING_PRICE_INVALID,
    PURCHASE_PRICE_INVALID,
}

data class ProductFormInput(
    val name: String = "",
    val aliasesText: String = "",
    val unit: String = ProductUnits.DEFAULT,
    val sellingPriceText: String = "",
    val purchasePriceText: String = "",
    val active: Boolean = true,
)

sealed interface ProductFormResult {
    data class Valid(
        val name: String,
        val aliases: List<String>,
        val unit: String,
        val sellingPrice: Money,
        val purchasePrice: Money?,
        val active: Boolean,
    ) : ProductFormResult

    data class Invalid(
        val errors: Set<ProductFormError>,
    ) : ProductFormResult
}

/**
 * A product needs a name and a selling price. The purchase price is optional
 * (PRD section 7): left blank it stays unknown, which is not the same as zero.
 */
fun validateProductForm(input: ProductFormInput): ProductFormResult {
    val errors = mutableSetOf<ProductFormError>()

    val name = input.name.trim()
    if (name.isEmpty()) errors += ProductFormError.NAME_REQUIRED

    val sellingText = input.sellingPriceText.trim()
    val sellingPrice =
        when {
            sellingText.isEmpty() -> {
                errors += ProductFormError.SELLING_PRICE_REQUIRED
                null
            }

            else -> {
                parseTaka(sellingText) ?: run {
                    errors += ProductFormError.SELLING_PRICE_INVALID
                    null
                }
            }
        }

    val purchaseText = input.purchasePriceText.trim()
    val purchasePrice =
        when {
            purchaseText.isEmpty() -> {
                null
            }

            else -> {
                parseTaka(purchaseText) ?: run {
                    errors += ProductFormError.PURCHASE_PRICE_INVALID
                    null
                }
            }
        }

    if (errors.isNotEmpty() || sellingPrice == null) {
        return ProductFormResult.Invalid(errors)
    }

    return ProductFormResult.Valid(
        name = name,
        aliases = parseAliases(input.aliasesText),
        unit = if (ProductUnits.isKnown(input.unit)) input.unit else ProductUnits.DEFAULT,
        sellingPrice = sellingPrice,
        purchasePrice = purchasePrice,
        active = input.active,
    )
}

/** Aliases are typed as one line, separated by commas. Blanks and repeats are dropped. */
fun parseAliases(text: String): List<String> =
    text
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
