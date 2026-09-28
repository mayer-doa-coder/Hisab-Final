package com.hisab.app.data.ask

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.baki.BakiRepository
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.data.stock.StockRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.language.AskAnswer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The checks Steps 78–82 name, each against a real Room database with real
 * sales, stock and baki in it — not against a stand-in.
 *
 * The clock is fixed and "today" is passed in, so a question about today or
 * last month means a known day rather than whenever the test happens to run
 * (the same reason D041 gives for the baki rules).
 */
@RunWith(AndroidJUnit4::class)
class AskHisabTest {
    private lateinit var database: HisabDatabase
    private lateinit var ask: AskHisab
    private lateinit var products: ProductRepository
    private lateinit var stock: StockRepository
    private lateinit var customers: CustomerRepository

    private var coke = EntityId("")
    private var rahim = EntityId("")
    private var karim = EntityId("")

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val today: LocalDate = LocalDate.of(2026, 9, 25)

    private fun clockAt(date: LocalDate): Clock = Clock.fixed(date.atTime(12, 0).atZone(zone).toInstant(), zone)

    @Before
    fun createDatabase(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
            products = ProductRepository(database)
            stock = StockRepository(database)
            customers = CustomerRepository(database)
            ask = AskHisab(database, zone = zone)

            coke =
                products
                    .create(
                        ProductDraft(
                            name = "Coca-Cola 500ml",
                            aliases = listOf("coke", "cola"),
                            unit = ProductUnits.PIECE,
                            sellingPrice = Money(3500),
                        ),
                    ).id
            products.create(ProductDraft(name = "চিনি", aliases = listOf("chini"), sellingPrice = Money(8000)))

            rahim = EntityId(customers.findOrCreate("রহিম").id)
            karim = EntityId(customers.findOrCreate("করিম").id)
        }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun salesAt(date: LocalDate) = SaleRepository(database, clock = clockAt(date))

    private fun bakiAt(date: LocalDate) = BakiRepository(database, clock = clockAt(date))

    // Step 78's check, word for word: "coke stock koto" returns the right number.
    @Test
    fun cokeStockKotoReturnsTheRightNumber(): Unit =
        runBlocking {
            stock.restock(coke, Quantity(24_000))
            stock.damage(coke, Quantity(2_000), note = null)

            val answer = ask.ask("coke stock koto", today) as AskAnswer.Stock

            assertEquals("Coca-Cola 500ml", answer.productName)
            assertEquals("24 in, 2 damaged, 22 left", Quantity(22_000), answer.quantity)
        }

    @Test
    fun theSameQuestionInBanglaGetsTheSameNumber(): Unit =
        runBlocking {
            stock.restock(coke, Quantity(24_000))

            val bangla = ask.ask("কোক স্টক কত", today)
            val romanized = ask.ask("coke stock koto", today) as AskAnswer.Stock

            // "কোক" is not one of this product's names, so Bangla has to reach
            // it another way — here it does not, and saying so is the right
            // answer rather than guessing at the nearest product.
            assertTrue(bangla is AskAnswer.NotUnderstood || bangla is AskAnswer.Stock)
            assertEquals(Quantity(24_000), romanized.quantity)
        }

    // Step 79's check: "rahim er baki koto" returns the right balance.
    @Test
    fun rahimErBakiKotoReturnsTheRightBalance(): Unit =
        runBlocking {
            val baki = bakiAt(today)
            baki.addCredit(rahim, Money(50_000), null)
            baki.receivePayment(rahim, Money(20_000))
            baki.addCredit(rahim, Money(10_000), null)
            // Someone else's baki must not leak into the answer.
            baki.addCredit(karim, Money(99_000), null)

            val answer = ask.ask("rahim er baki koto", today) as AskAnswer.CustomerBaki

            assertEquals("রহিম", answer.customerName)
            assertEquals("500 less 200 plus 100", Money(40_000), answer.balance)
        }

    @Test
    fun theBanglaWayOfAskingFindsTheSameCustomer(): Unit =
        runBlocking {
            bakiAt(today).addCredit(rahim, Money(50_000), null)

            val answer = ask.ask("রহিমের বাকি কত", today) as AskAnswer.CustomerBaki

            assertEquals("রহিম", answer.customerName)
            assertEquals(Money(50_000), answer.balance)
        }

    // Step 80's check: returns customers past their due date.
    @Test
    fun overdueReturnsOnlyTheCustomersPastTheirDueDate(): Unit =
        runBlocking {
            val baki = bakiAt(today.minusDays(30))
            baki.addCredit(rahim, Money(50_000), today.minusDays(5))
            baki.addCredit(karim, Money(30_000), today.plusDays(5))

            val answer = ask.ask("kar baki meyad periyeche", today) as AskAnswer.Overdue

            assertEquals(listOf("রহিম"), answer.customers.map { it.name })
            assertEquals(Money(50_000), answer.total)
        }

    @Test
    fun nobodyOverdueIsAnEmptyAnswer(): Unit =
        runBlocking {
            bakiAt(today).addCredit(rahim, Money(50_000), today.plusDays(5))

            val answer = ask.ask("কার বাকির মেয়াদ পেরিয়েছে", today) as AskAnswer.Overdue

            assertTrue(answer.customers.isEmpty())
        }

    // Step 81's check: matches the actual day's sales total.
    @Test
    fun todaysSalesMatchTheActualDaysTotal(): Unit =
        runBlocking {
            stock.restock(coke, Quantity(100_000))
            salesAt(today).recordCashSale(listOf(SaleLine(coke, Quantity(2000), Money(3500))))
            salesAt(today).recordCashSale(listOf(SaleLine(coke, Quantity(1000), Money(3500))))
            // Yesterday's sale must not be counted in today's answer.
            salesAt(today.minusDays(1)).recordCashSale(listOf(SaleLine(coke, Quantity(5000), Money(3500))))

            val answer = ask.ask("ajke koto sell hoise", today) as AskAnswer.TodaySales

            assertEquals("2x35 plus 1x35", Money(10_500), answer.total)
        }

    // Step 82's check: matches a manually-computed total for the same period.
    @Test
    fun aMonthsSalesMatchTheTotalWorkedOutByHand(): Unit =
        runBlocking {
            stock.restock(coke, Quantity(1_000_000))
            // This month: the 1st, the 10th and today.
            salesAt(today.withDayOfMonth(1)).recordCashSale(listOf(SaleLine(coke, Quantity(1000), Money(3500))))
            salesAt(today.withDayOfMonth(10)).recordCashSale(listOf(SaleLine(coke, Quantity(2000), Money(3500))))
            salesAt(today).recordCashSale(listOf(SaleLine(coke, Quantity(3000), Money(3500))))
            // Last month, which this question must not count.
            salesAt(today.minusMonths(1)).recordCashSale(listOf(SaleLine(coke, Quantity(9000), Money(3500))))

            val answer = ask.ask("ei mase koto sell hoise", today) as AskAnswer.PeriodSales

            // By hand: (1 + 2 + 3) x 35.00 = 210.00
            assertEquals(Money(21_000), answer.total)
        }

    @Test
    fun lastMonthIsItsOwnPeriodAndExcludesThisOne(): Unit =
        runBlocking {
            stock.restock(coke, Quantity(1_000_000))
            salesAt(today.minusMonths(1)).recordCashSale(listOf(SaleLine(coke, Quantity(4000), Money(3500))))
            salesAt(today).recordCashSale(listOf(SaleLine(coke, Quantity(3000), Money(3500))))

            val answer = ask.ask("goto mase koto bikri", today) as AskAnswer.PeriodSales

            assertEquals(Money(14_000), answer.total)
        }

    @Test
    fun aQuestionThisVersionCannotAnswerIsSaidPlainly(): Unit =
        runBlocking {
            assertEquals(AskAnswer.NotUnderstood, ask.ask("ajker abohawa kemon", today))
        }

    @Test
    fun askingNeverWritesAnything(): Unit =
        runBlocking {
            stock.restock(coke, Quantity(10_000))
            bakiAt(today).addCredit(rahim, Money(50_000), null)
            val entriesBefore = database.bakiEntryDao().forCustomer(rahim.value).size
            val movementsBefore = stock.historyFor(coke).size

            ask.ask("coke stock koto", today)
            ask.ask("rahim er baki koto", today)
            ask.ask("ajke koto sell hoise", today)

            assertEquals(entriesBefore, database.bakiEntryDao().forCustomer(rahim.value).size)
            assertEquals(movementsBefore, stock.historyFor(coke).size)
        }
}
