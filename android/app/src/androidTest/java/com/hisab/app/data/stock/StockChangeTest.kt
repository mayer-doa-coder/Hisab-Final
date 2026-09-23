package com.hisab.app.data.stock

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.data.sync.StockMovementSyncPayload
import com.hisab.app.data.sync.SyncOutboxDao
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.StockMovementType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three stock changes a shopkeeper makes by hand (Step 41): a delivery,
 * goods lost, and counting the shelf. Each writes one movement and queues one
 * change to send, in the same transaction (D003).
 */
@RunWith(AndroidJUnit4::class)
class StockChangeTest {
    private lateinit var database: HisabDatabase
    private lateinit var stock: StockRepository
    private lateinit var sales: SaleRepository
    private lateinit var products: ProductRepository
    private lateinit var outbox: SyncOutboxDao

    // Not lateinit: EntityId is a value class, so it cannot be one. Set in createDatabase.
    private var rice = EntityId("")

    @Before
    fun createDatabase(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
            stock = StockRepository(database)
            sales = SaleRepository(database)
            products = ProductRepository(database)
            outbox = database.syncOutboxDao()

            rice = products.create(ProductDraft(name = "চাল", unit = ProductUnits.KG, sellingPrice = Money(5000))).id
        }

    @After
    fun closeDatabase() {
        database.close()
    }

    private suspend fun stockMovements() = stock.historyFor(rice)

    @Test
    fun damageTakesStockAwayAndSaysWhy(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))

            stock.damage(rice, Quantity(1500), note = "ভেঙে গেছে")

            assertEquals(Quantity(8500), stock.currentStock(rice))
            val movement = stockMovements().last()
            assertEquals(StockMovementType.DAMAGE.name, movement.movementType)
            assertEquals(-1500L, movement.quantityDeltaScaled)
            assertEquals("ভেঙে গেছে", movement.sourceReference)
        }

    @Test
    fun countingTheShelfRecordsTheDifferenceNotTheCount(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))

            stock.count(rice, counted = Quantity(8500))

            assertEquals("the ledger now agrees with the shelf", Quantity(8500), stock.currentStock(rice))
            val movement = stockMovements().last()
            assertEquals(StockMovementType.CORRECTION.name, movement.movementType)
            assertEquals("what is kept is the difference", -1500L, movement.quantityDeltaScaled)
        }

    @Test
    fun countingCanPutRightStockThatWentNegative(): Unit =
        runBlocking {
            // Sold before the delivery was ever recorded (D031).
            sales.recordCashSale(listOf(SaleLine(rice, Quantity(3000), Money(5000))))
            assertEquals(Quantity(-3000), stock.currentStock(rice))

            stock.count(rice, counted = Quantity(5000), note = "গুনে দেখা হলো")

            assertEquals(Quantity(5000), stock.currentStock(rice))
            assertEquals("+8 kg to get from -3 to 5", 8000L, stockMovements().last().quantityDeltaScaled)
        }

    @Test
    fun countingWhatTheLedgerAlreadySaysStillRecordsThatTheShelfWasChecked(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))

            stock.count(rice, counted = Quantity(10_000))

            assertEquals(Quantity(10_000), stock.currentStock(rice))
            assertEquals(0L, stockMovements().last().quantityDeltaScaled)
            assertEquals(2, stockMovements().size)
        }

    @Test
    fun aCountCannotBeNegative(): Unit =
        runBlocking {
            val refused =
                try {
                    stock.count(rice, counted = Quantity(-1))
                    false
                } catch (_: IllegalArgumentException) {
                    true
                }
            assertTrue(refused)
            assertTrue(stockMovements().isEmpty())
        }

    // D003: the change and the record of it to send are written together.
    @Test
    fun everyStockChangeIsQueuedForTheServerInTheSameWrite(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000), note = "করিম স্টোর")
            stock.damage(rice, Quantity(1000))
            stock.count(rice, counted = Quantity(8000))

            val queued = outbox.all().filter { it.entityType == StockRepository.ENTITY_TYPE_STOCK_MOVEMENT }
            assertEquals(3, queued.size)
            assertEquals(stockMovements().map { it.id }, queued.map { it.entityId })

            // What is queued is the movement itself, in the shape the server reads.
            val first = StockMovementSyncPayload.fromJson(JSONObject(queued.first().payload))
            assertEquals(StockMovementType.RESTOCK, first.type)
            assertEquals(Quantity(10_000), first.quantityDelta)
            assertEquals("করিম স্টোর", first.sourceReference)
            assertNull("the server fills this in when it receives it", first.time.serverReceivedAt)
        }

    @Test
    fun aSalesOwnMovementIsNotQueuedOnItsOwn(): Unit =
        runBlocking {
            stock.restock(rice, Quantity(10_000))
            sales.recordCashSale(listOf(SaleLine(rice, Quantity(1000), Money(5000))))

            val queued = outbox.all()
            assertEquals(
                "the restock goes as a movement, the sale goes as a sale",
                1,
                queued.count { it.entityType == StockRepository.ENTITY_TYPE_STOCK_MOVEMENT },
            )
            assertEquals(1, queued.count { it.entityType == SaleRepository.ENTITY_TYPE_SALE })
        }
}
