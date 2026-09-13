package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductFormTest {
    private fun valid(input: ProductFormInput): ProductFormResult.Valid = validateProductForm(input) as ProductFormResult.Valid

    private fun errors(input: ProductFormInput): Set<ProductFormError> = (validateProductForm(input) as ProductFormResult.Invalid).errors

    @Test
    fun acceptsANameAndASellingPrice() {
        val result = valid(ProductFormInput(name = "চিনি", sellingPriceText = "85"))

        assertEquals("চিনি", result.name)
        assertEquals(Money(8500), result.sellingPrice)
        assertNull(result.purchasePrice)
        assertEquals(ProductUnits.PIECE, result.unit)
        assertTrue(result.active)
    }

    @Test
    fun trimsTheName() {
        assertEquals("Coke", valid(ProductFormInput(name = "  Coke  ", sellingPriceText = "20")).name)
    }

    @Test
    fun needsAName() {
        assertEquals(
            setOf(ProductFormError.NAME_REQUIRED),
            errors(ProductFormInput(name = "   ", sellingPriceText = "20")),
        )
    }

    @Test
    fun needsASellingPrice() {
        assertEquals(
            setOf(ProductFormError.SELLING_PRICE_REQUIRED),
            errors(ProductFormInput(name = "Coke", sellingPriceText = "")),
        )
    }

    @Test
    fun rejectsASellingPriceThatIsNotANumber() {
        assertEquals(
            setOf(ProductFormError.SELLING_PRICE_INVALID),
            errors(ProductFormInput(name = "Coke", sellingPriceText = "twenty")),
        )
    }

    @Test
    fun reportsEveryProblemAtOnce() {
        val found = errors(ProductFormInput(name = "", sellingPriceText = "x", purchasePriceText = "y"))

        assertEquals(
            setOf(
                ProductFormError.NAME_REQUIRED,
                ProductFormError.SELLING_PRICE_INVALID,
                ProductFormError.PURCHASE_PRICE_INVALID,
            ),
            found,
        )
    }

    // Purchase price is optional (PRD section 7): blank means unknown, which
    // is not the same as zero.
    @Test
    fun leavesTheOptionalPurchasePriceUnset() {
        assertNull(valid(ProductFormInput(name = "Coke", sellingPriceText = "20")).purchasePrice)
    }

    @Test
    fun keepsThePurchasePriceWhenGiven() {
        val result = valid(ProductFormInput(name = "Coke", sellingPriceText = "20", purchasePriceText = "১৫.৫০"))

        assertEquals(Money(1550), result.purchasePrice)
    }

    @Test
    fun acceptsAPurchasePriceOfZeroAsDifferentFromBlank() {
        assertEquals(Money(0), valid(ProductFormInput(name = "Gift", sellingPriceText = "20", purchasePriceText = "0")).purchasePrice)
    }

    @Test
    fun splitsAliasesOnCommasAndDropsBlanksAndRepeats() {
        val result =
            valid(
                ProductFormInput(
                    name = "Coke",
                    aliasesText = " cook , coke ,, COOK , কোক ",
                    sellingPriceText = "20",
                ),
            )

        assertEquals(listOf("cook", "coke", "কোক"), result.aliases)
    }

    @Test
    fun keepsAKnownUnitAndFallsBackForAnUnknownOne() {
        assertEquals(
            ProductUnits.KG,
            valid(ProductFormInput(name = "চিনি", unit = ProductUnits.KG, sellingPriceText = "85")).unit,
        )
        assertEquals(
            ProductUnits.DEFAULT,
            valid(ProductFormInput(name = "চিনি", unit = "barrel", sellingPriceText = "85")).unit,
        )
    }
}
