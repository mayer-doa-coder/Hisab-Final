package com.hisab.app.ui.sale

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Step 93's check, through the real view model and a real Room database: adding a
 * product to a sale shows a relevant suggestion, and tapping it adds the item.
 *
 * The view model is given an in-memory database instead of the app's own, so
 * nothing here touches the shopkeeper's data and the history is exactly what each
 * test records. "Tapping" is `addOne`, which is what the screen's callback calls:
 * a suggestion is added by the same path as any product, and the strip's button
 * wiring is proved separately in `SuggestionStripTest`.
 *
 * Names are camelCase: a device test with spaces in its name compiles but fails
 * to dex at minSdk 26.
 */
@RunWith(AndroidJUnit4::class)
class SaleSuggestionFlowTest {
    private lateinit var application: Application
    private lateinit var database: HisabDatabase
    private lateinit var viewModel: SaleViewModel

    private var biryani = EntityId("")
    private var coke = EntityId("")
    private var tea = EntityId("")
    private var fresh = EntityId("")

    private val now: Instant = Instant.parse("2026-10-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun line(product: EntityId) = SaleLine(product, Quantity(1000), Money(1000))

    private fun create(name: String): EntityId =
        runBlocking {
            ProductRepository(database).create(ProductDraft(name = name, unit = ProductUnits.PIECE, sellingPrice = Money(1000))).id
        }

    @Before
    fun setUp(): Unit =
        runBlocking {
            application = ApplicationProvider.getApplicationContext()
            database = Room.inMemoryDatabaseBuilder(application, HisabDatabase::class.java).build()
            biryani = create("Biryani")
            coke = create("Coca-Cola 500ml")
            tea = create("Tea")
            fresh = create("Brand New")
        }

    @After
    fun tearDown() {
        // Stop the view model first. Its state flow keeps its database query alive for five
        // seconds after the last collector leaves, so closing the database underneath it makes
        // that query throw on a background thread, which ends the whole test run, not just
        // this test. (The app never closes its database; only a test does.)
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        database.close()
    }

    /** 30 sales: biryani with coke 15 times, biryani alone 5, tea alone 10. Biryani -> coke is 75%. */
    private suspend fun recordHistory() {
        val sales = SaleRepository(database, clock = clock)
        repeat(15) { sales.recordCashSale(listOf(line(biryani), line(coke))) }
        repeat(5) { sales.recordCashSale(listOf(line(biryani))) }
        repeat(10) { sales.recordCashSale(listOf(line(tea))) }
    }

    private fun startViewModel() {
        viewModel = SaleViewModel(application, database, clock)
    }

    /** Waits (on the real state flow) for a state that satisfies [condition], or fails after ten seconds. */
    private suspend fun waitFor(condition: (SaleUiState) -> Boolean): SaleUiState =
        withTimeout(10_000) { viewModel.uiState.first(condition) }

    private fun SaleUiState.suggestedIds() = suggestions.items.map { it.product.id }

    @Test
    fun addingABiryaniShowsTheCokeThatIsBoughtWithIt(): Unit =
        runBlocking {
            recordHistory()
            startViewModel()

            viewModel.addOne(biryani.value, ProductUnits.PIECE)
            val state = waitFor { it.suggestedIds().isNotEmpty() }

            assertEquals(listOf(coke.value), state.suggestedIds())
            assertEquals(
                75,
                state.suggestions.items
                    .single()
                    .rule.confidencePercent,
            )
            assertEquals(SuggestionHint.NONE, state.suggestions.hint)
        }

    @Test
    fun tappingTheSuggestionAddsItToTheSaleAndItLeavesTheStrip(): Unit =
        runBlocking {
            recordHistory()
            startViewModel()
            viewModel.addOne(biryani.value, ProductUnits.PIECE)
            waitFor { it.suggestedIds().isNotEmpty() }

            // The screen's tap on a suggestion is exactly this call.
            viewModel.addOne(coke.value, ProductUnits.PIECE)
            val state = waitFor { s -> s.lines.size == 2 }

            assertEquals(setOf(biryani.value, coke.value), state.lines.map { it.product.id }.toSet())
            assertFalse("an item in the cart is not suggested again", coke.value in state.suggestedIds())
            assertEquals(Money(2000), state.total)
        }

    @Test
    fun aDismissedSuggestionStaysGoneForTheRestOfTheSale(): Unit =
        runBlocking {
            recordHistory()
            startViewModel()
            viewModel.addOne(biryani.value, ProductUnits.PIECE)
            waitFor { it.suggestedIds().isNotEmpty() }

            viewModel.dismissSuggestion(coke.value)
            val state = waitFor { it.suggestedIds().isEmpty() }

            assertEquals(1, state.lines.size)
            assertFalse(state.suggestions.isVisible)
        }

    @Test
    fun theDismissalIsForgottenWhenTheSaleIsSavedSoTheNextCustomerSeesItAgain(): Unit =
        runBlocking {
            recordHistory()
            startViewModel()
            viewModel.addOne(biryani.value, ProductUnits.PIECE)
            waitFor { it.suggestedIds().isNotEmpty() }
            viewModel.dismissSuggestion(coke.value)
            waitFor { it.suggestedIds().isEmpty() }

            viewModel.confirm {}
            waitFor { it.justSaved != null && it.isEmpty }

            viewModel.addOne(biryani.value, ProductUnits.PIECE)
            val next = waitFor { it.suggestedIds().isNotEmpty() }
            assertEquals(listOf(coke.value), next.suggestedIds())
        }

    @Test
    fun aBrandNewProductShowsNoSuggestionAndNoMessage(): Unit =
        runBlocking {
            recordHistory()
            startViewModel()

            viewModel.addOne(fresh.value, ProductUnits.PIECE)
            val state = waitFor { it.lines.size == 1 && it.suggestions == SuggestionPanel.EMPTY }
            // Give a late history read a moment to arrive, then check nothing appeared.
            kotlinx.coroutines.delay(500)

            assertEquals(SuggestionPanel.EMPTY, viewModel.uiState.value.suggestions)
            assertEquals(1, state.lines.size)
        }

    @Test
    fun aShopWithTooLittleHistorySaysSoOnce(): Unit =
        runBlocking {
            SaleRepository(database, clock = clock).recordCashSale(listOf(line(biryani), line(coke)))
            startViewModel()

            viewModel.addOne(biryani.value, ProductUnits.PIECE)
            val state = waitFor { it.suggestions.hint == SuggestionHint.NOT_ENOUGH_HISTORY }

            assertTrue(state.suggestions.items.isEmpty())
            assertTrue(state.suggestions.isVisible)
        }

    @Test
    fun theSaleJustMadeCountsTowardTheNextSuggestions(): Unit =
        runBlocking {
            recordHistory()
            startViewModel()

            // A product that has never sold goes out with biryani 12 times: a pairing that did not
            // exist when the screen opened. Biryani is then in 32 of 42 sales, the new product in 12,
            // together in 12: confidence 38%, lift 42 / 32 = 1.31, both over their minimums.
            repeat(12) {
                viewModel.addOne(biryani.value, ProductUnits.PIECE)
                viewModel.addOne(fresh.value, ProductUnits.PIECE)
                waitFor { it.lines.size == 2 }
                viewModel.confirm {}
                waitFor { it.justSaved != null && it.isEmpty }
            }

            viewModel.addOne(biryani.value, ProductUnits.PIECE)
            val state = waitFor { s -> s.suggestions.items.any { it.product.id == fresh.value } }
            assertTrue(fresh.value in state.suggestedIds())
        }

    @Test
    fun typingASearchDoesNotHideTheOtherLinesInTheCartOrDropThemFromTheSale(): Unit =
        runBlocking {
            startViewModel()
            viewModel.addOne(coke.value, ProductUnits.PIECE)
            viewModel.addOne(tea.value, ProductUnits.PIECE)
            waitFor { it.lines.size == 2 }

            // The picker filters to "Tea"; the cart must still hold both, and so must the total.
            viewModel.onQueryChange("Tea")
            // Wait for the filtered list too: the query text and the filtered list arrive separately.
            val state = waitFor { it.query == "Tea" && it.available.size == 1 }

            assertEquals(1, state.available.size)
            assertEquals(2, state.lines.size)
            assertEquals(Money(2000), state.total)

            viewModel.confirm {}
            waitFor { it.justSaved != null && it.isEmpty }
            val saved = SaleRepository(database).observeRecent().first().single()
            assertEquals(2, saved.lineCount)
            assertEquals(Money(2000), Money(saved.sale.totalPoisha))
        }
}
