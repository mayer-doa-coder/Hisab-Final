package com.hisab.app.data.sale

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.stock.StockMovementDao
import com.hisab.app.data.stock.toDomain
import com.hisab.app.data.stock.toEntity
import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.SalePayment
import com.hisab.app.domain.StockMovementType
import com.hisab.app.domain.calculateCurrentStock
import com.hisab.app.domain.completeCashSale
import com.hisab.app.domain.completeCreditSale
import com.hisab.app.domain.generateId
import com.hisab.app.domain.restock
import com.hisab.app.domain.reverseSale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Step 38: the Sale, SaleItem and StockMovement tables, against a real Room
 * database held in memory so nothing is left on the phone afterwards.
 *
 * These tests write what the Step 35/36 functions produce, rather than
 * hand-made rows — so they also check that a completed sale survives the trip
 * through the database unchanged, which is what every M2 screen depends on.
 */
@RunWith(AndroidJUnit4::class)
class SaleStockDaoTest {
    private lateinit var database: HisabDatabase
    private lateinit var sales: SaleDao
    private lateinit var movements: StockMovementDao

    private val shopId = "shop-under-test"
    private val at = Instant.parse("2026-09-15T10:00:00Z")
    private val later = Instant.parse("2026-09-15T18:30:00Z")

    private val rice = generateId()
    private val oil = generateId()

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        sales = database.saleDao()
        movements = database.stockMovementDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun cart() =
        listOf(
            SaleLine(rice, Quantity(3000), Money(5000)),
            SaleLine(oil, Quantity(2000), Money(2500)),
        )

    /**
     * The sale, its lines and its stock movements are written in one
     * transaction — all of them or none (D021, PRD section 25).
     */
    private suspend fun save(transaction: com.hisab.app.domain.SaleTransaction) {
        database.withTransaction {
            sales.insert(transaction.sale.toEntity())
            sales.insertItems(transaction.items.map { it.toEntity() })
            movements.insertAll(transaction.stockMovements.map { it.toEntity() })
        }
    }

    @Test
    fun aCashSaleIsWrittenAndReadBackUnchanged() =
        runBlocking {
            val sale = completeCashSale(shopId, cart(), at)
            save(sale)

            val stored = sales.byId(sale.sale.id.value)
            assertNotNull(stored)
            assertEquals(sale.sale, stored!!.toDomain())
            assertEquals(SalePayment.CASH.name, stored.payment)
            assertNull("a cash sale has no customer", stored.customerId)
            assertNull("nothing has reached the server yet", stored.serverReceivedAt)

            val lines = sales.itemsFor(sale.sale.id.value)
            assertEquals(2, lines.size)
            assertEquals(sale.items.sortedBy { it.productId.value }, lines.map { it.toDomain() })
        }

    @Test
    fun aCreditSaleKeepsItsCustomerAndTotal() =
        runBlocking {
            val customer = generateId()
            val sale = completeCreditSale(shopId, customer, cart(), at)
            save(sale)

            val stored = sales.byId(sale.sale.id.value)!!
            assertEquals(customer.value, stored.customerId)
            assertEquals(SalePayment.CREDIT.name, stored.payment)
            assertEquals(20_000L, stored.totalPoisha)
        }

    @Test
    fun stockMovementsAreWrittenAndReadBackUnchanged() =
        runBlocking {
            val sale = completeCashSale(shopId, cart(), at)
            save(sale)

            val stored = movements.forProduct(rice.value)
            assertEquals(1, stored.size)
            assertEquals(sale.stockMovements.first { it.productId == rice }, stored.first().toDomain())
            assertEquals(StockMovementType.SALE.name, stored.first().movementType)
        }

    /** D020: current stock is a SUM over the ledger, never a stored column. */
    @Test
    fun currentStockIsTheSumOfTheMovements() =
        runBlocking {
            movements.insert(restock(rice, Quantity(10_000), at).toEntity())
            save(completeCashSale(shopId, listOf(SaleLine(rice, Quantity(3000), Money(5000))), at))

            assertEquals(7000L, movements.currentStockScaled(rice.value))
            assertEquals(7000L, movements.observeCurrentStockScaled(rice.value).first())
        }

    @Test
    fun aProductThatNeverMovedReadsAsZero() =
        runBlocking {
            assertEquals(0L, movements.currentStockScaled(generateId().value))
        }

    @Test
    fun theDatabaseAgreesWithTheDomainFunction() =
        runBlocking {
            movements.insert(restock(rice, Quantity(10_000), at).toEntity())
            save(completeCashSale(shopId, listOf(SaleLine(rice, Quantity(3000), Money(5000))), at))

            val fromLedger = calculateCurrentStock(movements.forProduct(rice.value).map { it.toDomain() })
            assertEquals(fromLedger.scaledUnits, movements.currentStockScaled(rice.value))
        }

    @Test
    fun reversingASaleRestoresStockAndLeavesBothSalesInHistory() =
        runBlocking {
            movements.insert(restock(rice, Quantity(10_000), at).toEntity())
            movements.insert(restock(oil, Quantity(10_000), at).toEntity())

            val sale = completeCashSale(shopId, cart(), at)
            save(sale)
            assertEquals(7000L, movements.currentStockScaled(rice.value))

            save(reverseSale(sale, later))
            assertEquals("stock is back", 10_000L, movements.currentStockScaled(rice.value))

            // The original sale row is untouched — history is never rewritten.
            assertNotNull(sales.byId(sale.sale.id.value))
            assertNotNull("the reversal is findable from the sale it undoes", sales.reversalOf(sale.sale.id.value))
        }

    @Test
    fun aDaysTakingsAlreadyExcludeAReversedSale() =
        runBlocking {
            val kept = completeCashSale(shopId, cart(), at)
            val mistake =
                completeCashSale(shopId, listOf(SaleLine(rice, Quantity(1000), Money(5000))), at)
            save(kept)
            save(mistake)
            save(reverseSale(mistake, later))

            val takings =
                sales.totalTakingsPoisha(
                    shopId,
                    fromInclusive = at.toEpochMilli(),
                    toExclusive = later.plusMillis(1).toEpochMilli(),
                )
            assertEquals(kept.sale.total.minorUnits, takings)
        }

    @Test
    fun historyShowsTheNewestSaleFirst() =
        runBlocking {
            val older = completeCashSale(shopId, cart(), at)
            val newer = completeCashSale(shopId, cart(), later)
            save(older)
            save(newer)

            val history = sales.observeRecent(shopId).first()
            assertEquals(listOf(newer.sale.id.value, older.sale.id.value), history.map { it.id })
        }

    @Test
    fun anotherShopsSalesAreNotListed() =
        runBlocking {
            save(completeCashSale(shopId, cart(), at))
            save(completeCashSale("someone-elses-shop", cart(), at))

            val history = sales.observeRecent(shopId).first()
            assertEquals(1, history.size)
            assertEquals(shopId, history.first().shopId)
        }

    /** The sale's lines cannot outlive it — the foreign key sees to that. */
    @Test
    fun purgingASaleTakesItsLinesWithIt() =
        runBlocking {
            val sale = completeCashSale(shopId, cart(), at)
            save(sale)
            assertEquals(2, sales.itemsFor(sale.sale.id.value).size)

            sales.hardDelete(sale.sale.id.value)
            assertNull(sales.byId(sale.sale.id.value))
            assertEquals(0, sales.itemsFor(sale.sale.id.value).size)
        }

    @Test
    fun everyMovementCausedByOneSaleIsFindableFromIt() =
        runBlocking {
            val sale = completeCashSale(shopId, cart(), at)
            save(sale)
            save(reverseSale(sale, later))

            // Two sale movements out, two return movements back in.
            val caused = movements.forReference(sale.sale.id.value)
            assertEquals(4, caused.size)
            assertEquals(0L, caused.sumOf { it.quantityDeltaScaled })
        }
}
