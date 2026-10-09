package com.hisab.app.data.suggest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.sale.ReversalResult
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.suggest.ComplementResult
import com.hisab.app.domain.suggest.rankComplements
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Step 91 against a real Room database: sales recorded through the real
 * `SaleRepository`, read back as "bought together" counts.
 *
 * The counting rules are proved on the JVM in `CooccurrenceTest`. What only a
 * database can prove is that the rows reaching them are the right rows: that a
 * sale taken back is not counted, that the window and the shop are respected,
 * and that reading writes nothing.
 *
 * Names are camelCase: a device test with spaces in its name compiles but fails
 * to dex at minSdk 26.
 */
@RunWith(AndroidJUnit4::class)
class SuggestionRepositoryTest {
    private lateinit var database: HisabDatabase
    private lateinit var suggestions: SuggestionRepository

    private var rice = EntityId("")
    private var oil = EntityId("")
    private var salt = EntityId("")

    private val now: Instant = Instant.parse("2026-10-01T12:00:00Z")

    private fun daysAgo(days: Long): Instant = now.minusSeconds(days * 86_400)

    private fun salesAt(
        at: Instant,
        shopId: String = com.hisab.app.data.LOCAL_SHOP_ID,
    ) = SaleRepository(database, clock = Clock.fixed(at, ZoneOffset.UTC), shopId = shopId)

    private fun line(product: EntityId) = SaleLine(product, Quantity(1000), Money(1000))

    @Before
    fun createDatabase(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
            suggestions = SuggestionRepository(database, clock = Clock.fixed(now, ZoneOffset.UTC))

            val products = ProductRepository(database)
            rice = products.create(ProductDraft(name = "চাল", sellingPrice = Money(1000))).id
            oil = products.create(ProductDraft(name = "তেল", sellingPrice = Money(1000))).id
            salt = products.create(ProductDraft(name = "লবণ", sellingPrice = Money(1000))).id
        }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun salesBecomeBasketsAndPairs(): Unit =
        runBlocking {
            repeat(3) { salesAt(daysAgo(2)).recordCashSale(listOf(line(rice), line(oil))) }
            salesAt(daysAgo(1)).recordCashSale(listOf(line(rice)))

            val counts = suggestions.load()

            assertEquals(4, counts.basketCount)
            assertEquals(4, counts.basketsWith(rice))
            assertEquals(3, counts.basketsWith(oil))
            assertEquals(3, counts.together(rice, oil))
            assertEquals(0, counts.together(rice, salt))
        }

    @Test
    fun aCreditSaleCountsLikeACashSale(): Unit =
        runBlocking {
            val customer =
                com.hisab.app.data.customer
                    .CustomerRepository(database)
                    .findOrCreate("রহিম")
            salesAt(daysAgo(1)).recordCreditSale(EntityId(customer.id), listOf(line(rice), line(oil)), null)
            salesAt(daysAgo(1)).recordCashSale(listOf(line(rice), line(oil)))

            assertEquals(2, suggestions.load().together(rice, oil))
        }

    @Test
    fun aSaleThatWasTakenBackIsNotCountedAndNeitherIsItsReversal(): Unit =
        runBlocking {
            val kept = salesAt(daysAgo(3)).recordCashSale(listOf(line(rice), line(oil))).sale.id
            val undone = salesAt(daysAgo(2)).recordCashSale(listOf(line(rice), line(salt))).sale.id

            assertTrue(salesAt(daysAgo(1)).reverse(undone) is ReversalResult.Reversed)

            val counts = suggestions.load()

            // One real basket remains. The undone sale and its reversal are both left out:
            // counting the first would suggest salt for rice on a sale the customer cancelled,
            // and counting the second would count a basket of negative lines.
            assertEquals(1, counts.basketCount)
            assertEquals(1, counts.together(rice, oil))
            assertEquals(0, counts.basketsWith(salt))
            assertEquals(0, counts.together(rice, salt))
            assertTrue(kept != undone)
        }

    @Test
    fun onlyTheLastNinetyDaysAreRead(): Unit =
        runBlocking {
            salesAt(daysAgo(100)).recordCashSale(listOf(line(rice), line(salt)))
            salesAt(daysAgo(10)).recordCashSale(listOf(line(rice), line(oil)))

            val recent = suggestions.load()
            assertEquals(1, recent.basketCount)
            assertEquals(0, recent.together(rice, salt))

            // A longer window brings the older sale back, which is what shows the window is the reason.
            val longer = suggestions.load(windowDays = 120)
            assertEquals(2, longer.basketCount)
            assertEquals(1, longer.together(rice, salt))
        }

    @Test
    fun anotherShopsSalesAreNeverCounted(): Unit =
        runBlocking {
            salesAt(daysAgo(1), shopId = "some-other-shop").recordCashSale(listOf(line(rice), line(oil)))
            salesAt(daysAgo(1)).recordCashSale(listOf(line(rice), line(salt)))

            val counts = suggestions.load()

            assertEquals(1, counts.basketCount)
            assertEquals(0, counts.together(rice, oil))
            assertEquals(1, counts.together(rice, salt))
        }

    @Test
    fun readingTheHistoryWritesNothing(): Unit =
        runBlocking {
            salesAt(daysAgo(1)).recordCashSale(listOf(line(rice), line(oil)))
            val outboxBefore = database.syncOutboxDao().count()
            val stockBefore = database.stockMovementDao().currentStockScaled(rice.value)

            suggestions.load()
            suggestions.load(windowDays = 365)

            assertEquals(outboxBefore, database.syncOutboxDao().count())
            assertEquals(stockBefore, database.stockMovementDao().currentStockScaled(rice.value))
        }

    @Test
    fun aKnownPairRecordedAsRealSalesIsRankedFirst(): Unit =
        runBlocking {
            // 25 sales: rice with oil 15 times, rice alone 5, salt alone 5.
            repeat(15) { salesAt(daysAgo(5)).recordCashSale(listOf(line(rice), line(oil))) }
            repeat(5) { salesAt(daysAgo(4)).recordCashSale(listOf(line(rice))) }
            repeat(5) { salesAt(daysAgo(3)).recordCashSale(listOf(line(salt))) }

            val result = rankComplements(suggestions.load(), listOf(rice), topN = 3)

            val ranked = (result as ComplementResult.Ranked).suggestions
            assertEquals(listOf(oil), ranked.map { it.productId })
            // 15 of rice's 20 sales: 75%.
            assertEquals(75, ranked.single().strongest.confidencePercent)
        }

    @Test
    fun aMovedWindowIsMeasuredFromTheClockNotFromTheLastSale(): Unit =
        runBlocking {
            salesAt(daysAgo(89)).recordCashSale(listOf(line(rice), line(oil)))
            // 89 days ago is inside a 90-day window from `now`, whenever the last sale was.
            assertEquals(1, suggestions.load().basketCount)
        }
}
