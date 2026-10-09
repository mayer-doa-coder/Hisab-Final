package com.hisab.app.ui.sale

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.AppLanguage
import com.hisab.app.data.product.ProductEntity
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.suggest.ComplementRule
import com.hisab.app.ui.theme.HisabTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The strip itself (Step 93): what it draws, and that its two buttons call back
 * with the right product. It is drawn on its own, from a hand-made panel, so what
 * is under test is the strip and not the history behind it (that is
 * `SaleSuggestionFlowTest`). Labels are found by tag, not by text, so the test
 * holds in either language.
 *
 * Names are camelCase: a device test with spaces in its name compiles but fails
 * to dex at minSdk 26.
 */
@RunWith(AndroidJUnit4::class)
class SuggestionStripTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun product(
        id: String,
        name: String,
        unit: String = "piece",
    ) = ProductEntity(
        id = id,
        shopId = "shop",
        name = name,
        aliases = emptyList(),
        unit = unit,
        purchasePricePoisha = null,
        sellingPricePoisha = 1000,
        active = true,
        revision = 1,
        updatedAt = Instant.EPOCH,
        deletedAt = null,
    )

    private fun suggested(
        id: String,
        name: String,
        unit: String = "piece",
    ) = SuggestedProduct(
        product(id, name, unit),
        ComplementRule(EntityId("biryani"), EntityId(id), together = 12, givenBaskets = 20, suggestedBaskets = 20, basketCount = 60),
    )

    private val panel =
        SuggestionPanel(
            // Two short names, so both chips are on screen on any phone: a LazyRow only draws what fits.
            items = listOf(suggested("coke", "Coke"), suggested("sugar", "চিনি", "kg")),
            hint = SuggestionHint.NONE,
        )

    private fun show(
        shown: SuggestionPanel,
        onAdd: (String, String) -> Unit = { _, _ -> },
        onDismiss: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            HisabTheme(AppLanguage.BANGLA) { SuggestionStrip(shown, onAdd, onDismiss) }
        }
    }

    @Test
    fun everySuggestionHasAnAddAndADismissControl() {
        show(panel)

        listOf("coke", "sugar").forEach { id ->
            composeRule.onNodeWithTag("suggestion_$id").assertExists()
            composeRule.onNodeWithTag("suggestion_dismiss_$id").assertExists()
        }
        composeRule.onNodeWithTag("suggestion_strip").assertIsDisplayed()
    }

    @Test
    fun tappingASuggestionAddsThatProductWithItsOwnUnit() {
        val added = mutableListOf<Pair<String, String>>()
        show(panel, onAdd = { id, unit -> added += id to unit })

        composeRule.onNodeWithTag("suggestion_coke").performClick()
        composeRule.onNodeWithTag("suggestion_sugar").performClick()

        // The unit travels with the product: sugar is sold by the kilo, so one tap is half a kilo (see addOne).
        assertEquals(listOf("coke" to "piece", "sugar" to "kg"), added)
    }

    @Test
    fun tappingDismissCallsBackWithOnlyThatProductAndDoesNotAddIt() {
        val added = mutableListOf<Pair<String, String>>()
        val dismissed = mutableListOf<String>()
        show(panel, onAdd = { id, unit -> added += id to unit }, onDismiss = { dismissed += it })

        composeRule.onNodeWithTag("suggestion_dismiss_sugar").performClick()

        assertEquals(listOf("sugar"), dismissed)
        assertEquals(emptyList<Pair<String, String>>(), added)
    }

    @Test
    fun aPanelWithNothingToShowDrawsNothing() {
        show(SuggestionPanel.EMPTY)

        composeRule.onNodeWithTag("suggestion_strip").assertDoesNotExist()
    }

    @Test
    fun aShopThatIsTooNewGetsOneQuietLineAndNoButtons() {
        show(SuggestionPanel(emptyList(), SuggestionHint.NOT_ENOUGH_HISTORY))

        composeRule.onNodeWithTag("suggestion_not_enough_history").assertIsDisplayed()
        composeRule.onNodeWithTag("suggestion_coke").assertDoesNotExist()
    }

    @Test
    fun theSameStripDrawsInEnglishToo() {
        composeRule.setContent {
            HisabTheme(AppLanguage.ENGLISH) { SuggestionStrip(panel, { _, _ -> }, {}) }
        }

        composeRule.onNodeWithTag("suggestion_coke").assertIsDisplayed()
    }
}
