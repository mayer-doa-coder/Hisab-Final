package com.hisab.app.data.ask

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.data.stock.StockRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.forecast.EwmaMethod
import com.hisab.app.domain.forecast.InventoryAdvisor
import com.hisab.app.domain.forecast.StockoutOutlook
import com.hisab.app.domain.language.AnswerLanguage
import com.hisab.app.domain.language.AskAnswer
import com.hisab.app.ui.ask.ForecastAnswerText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Step 90's check: each of the three new questions returns a sensible answer
 * using real data — a real Room database, with sales recorded through the real
 * `SaleRepository` over four weeks and stock built up by real movements. Not a
 * stand-in, and not hand-built answers.
 *
 * "Real" here means real code paths and real rows. No real shop's data exists
 * yet (RQ4 waits on pilot shops), so the figures are ones this test sets up and
 * can work out by hand; each expected number has its arithmetic beside it.
 *
 * Today is fixed, and the shop is shopping in Dhaka's time zone.
 *
 * Names are camelCase: a device test with spaces in its name compiles but fails
 * to dex at minSdk 26.
 */
@RunWith(AndroidJUnit4::class)
class AskForecastTest {
    private lateinit var context: Context
    private lateinit var database: HisabDatabase
    private lateinit var products: ProductRepository
    private lateinit var ask: AskHisab

    private var coke = EntityId("")
    private var rice = EntityId("")
    private var sugar = EntityId("")
    private var oil = EntityId("")

    private val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    private val today: LocalDate = LocalDate.of(2026, 9, 25)

    private fun clockAt(date: LocalDate): Clock = Clock.fixed(date.atTime(12, 0).atZone(zone).toInstant(), zone)

    private fun salesOn(date: LocalDate) = SaleRepository(database, clock = clockAt(date))

    private suspend fun sell(
        product: EntityId,
        date: LocalDate,
        quantity: Long,
    ) {
        salesOn(date).recordCashSale(listOf(SaleLine(product, Quantity(quantity), Money(1000))))
    }

    // `: Unit` so JUnit sees a void method; see SaleFlowTest.
    @Before
    fun createShop(): Unit =
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
            products = ProductRepository(database)
            // EWMA, so every expected number below can be worked out on paper.
            // One test uses the app's own default method instead.
            ask = AskHisab(database, zone = zone, advisor = InventoryAdvisor(EwmaMethod()))

            coke = create("Coca-Cola 500ml", listOf("coke", "cola"), ProductUnits.PIECE)
            rice = create("চাল", listOf("chal", "rice"), ProductUnits.PIECE)
            sugar = create("চিনি", listOf("chini", "sugar"), ProductUnits.KG)
            oil = create("তেল", listOf("tel", "oil"), ProductUnits.LITRE)

            val firstDay = LocalDate.of(2026, 8, 28)
            val stock = StockRepository(database, clock = clockAt(firstDay.minusDays(1)))

            // Coke: 100 in, 3 sold every day for the 28 days up to yesterday, 16 left.
            stock.restock(coke, Quantity(100_000))
            // Rice: 600 in, 2 a day for 28 days, 544 left — far more than it needs.
            stock.restock(rice, Quantity(600_000))
            // Sugar: 35.5 kg in, 1.5 kg every day for 20 days, 5.5 kg left.
            stock.restock(sugar, Quantity(35_500))
            // Oil: a new product, 10 litres in.
            stock.restock(oil, Quantity(10_000))

            for (offset in 0 until 28) {
                val day = firstDay.plusDays(offset.toLong())
                sell(coke, day, 3_000)
                sell(rice, day, 2_000)
                if (offset >= 8) sell(sugar, day, 1_500)
            }
            // Oil has sold on only three of the last five days — not enough to forecast from.
            listOf(20, 22, 24).forEach { sell(oil, LocalDate.of(2026, 9, it), 1_000) }
        }

    private suspend fun create(
        name: String,
        aliases: List<String>,
        unit: String,
    ): EntityId = products.create(ProductDraft(name = name, aliases = aliases, unit = unit, sellingPrice = Money(1000))).id

    @After
    fun closeShop() {
        database.close()
    }

    private fun units(amount: Double) = Quantity.fromDecimal(amount)

    private fun text(
        answer: AskAnswer,
        language: AnswerLanguage,
    ): String = ForecastAnswerText.render(context, answer, language, today)!!

    // ---- GET_LOW_STOCK ----------------------------------------------------------------------

    @Test
    fun whatIsRunningLowListsOnlyTheProductsThatWillNotLastAndSaysWhoWasLeftOut(): Unit =
        runBlocking {
            val answer = ask.ask("ki ki kom ache", today) as AskAnswer.LowStock

            // Sugar: 5.5 kg at 1.5 a day is gone on day 4. Coke: 16 at 3 a day, day 6.
            // Rice has 544 against 14 expected and is not low. Oil has too little history.
            assertEquals(listOf("চিনি", "Coca-Cola 500ml"), answer.items.map { it.productName })
            assertEquals(listOf(StockoutOutlook.InDays(4), StockoutOutlook.InDays(6)), answer.items.map { it.outlook })
            assertEquals(units(5.5), answer.items.first().stock)
            assertEquals(1, answer.notEnoughData)
            assertEquals(7, answer.leadTimeDays)
        }

    @Test
    fun theSameQuestionInBanglaAndMixedWritingGetsTheSameAnswer(): Unit =
        runBlocking {
            val romanized = ask.ask("ki ki kom ache", today)
            assertEquals(romanized, ask.ask("কোন কোন জিনিস কম আছে", today))
            assertEquals(romanized, ask.ask("কোন কোন product কম আছে", today))
            assertEquals(romanized, ask.ask("kon kon product sesh hoye ashche", today))
        }

    // ---- GET_PREDICTED_STOCKOUT -------------------------------------------------------------

    @Test
    fun whenWillCokeRunOutGivesTheDayTheStockIsGone(): Unit =
        runBlocking {
            val answer = ask.ask("coke kobe sesh hobe", today) as AskAnswer.PredictedStockout

            assertEquals("Coca-Cola 500ml", answer.productName)
            assertEquals(units(16.0), answer.stock)
            // 16 pieces at 3 a day: 3, 6, 9, 12, 15, 18 — gone during the sixth day.
            assertEquals(StockoutOutlook.InDays(6), answer.outlook)
        }

    @Test
    fun aProductWithPlentyOfStockIsNotExpectedToRunOutSoon(): Unit =
        runBlocking {
            val answer = ask.ask("chal koto din cholbe", today) as AskAnswer.PredictedStockout
            // 544 pieces at 2 a day is 272 days; it only looks 30 ahead.
            assertEquals(StockoutOutlook.NotSoon(30), answer.outlook)
        }

    @Test
    fun theStockoutAnswerWorksInBanglaToo(): Unit =
        runBlocking {
            val answer = ask.ask("চিনি কতদিন চলবে", today) as AskAnswer.PredictedStockout
            assertEquals("চিনি", answer.productName)
            assertEquals(StockoutOutlook.InDays(4), answer.outlook)
        }

    @Test
    fun theRunOutDateInTheSentenceUsesTheLanguagesOwnDigits(): Unit =
        runBlocking {
            // Sugar runs out on day 4, which from 25 September is 29 September.
            val answer = ask.ask("chini kobe sesh hobe", today)
            val english = text(answer, AnswerLanguage.ENGLISH)
            val bangla = text(answer, AnswerLanguage.BANGLA)

            assertTrue(english, english.contains("29 September"))
            assertTrue(bangla, bangla.contains("২৯"))
            // The date is the only place a formatter could have slipped ASCII digits in.
            assertFalse(bangla, bangla.any { it in '0'..'9' })
        }

    @Test
    fun aProductWithTooLittleHistoryIsToldSoInsteadOfBeingGuessedAt(): Unit =
        runBlocking {
            val answer = ask.ask("tel kobe sesh hobe", today) as AskAnswer.NotEnoughData

            assertEquals("তেল", answer.productName)
            // Five days since its first sale, three of them with a sale.
            assertEquals(5, answer.daysObserved)
            assertEquals(3, answer.demandDays)
            // The stock is a fact and is still given: 10 in, 3 sold.
            assertEquals(units(7.0), answer.stock)
        }

    @Test
    fun aProductThatWasNeverSoldIsToldSoInPlainWordsInBothLanguages(): Unit =
        runBlocking {
            // Created here and not in the shared set-up, so the other tests' "N products left out" counts stay as they are.
            create("লবণ", listOf("lobon"), ProductUnits.PIECE)

            val stockout = ask.ask("lobon kobe sesh hobe", today) as AskAnswer.NotEnoughData
            val reorder = ask.ask("lobon koyta kinbo", today) as AskAnswer.NotEnoughData

            // Nothing was observed at all, which is different from "sold on 0 of 0 days".
            assertEquals(0, stockout.daysObserved)
            assertEquals(stockout, reorder)

            val english = text(stockout, AnswerLanguage.ENGLISH)
            val bangla = text(stockout, AnswerLanguage.BANGLA)
            assertTrue(english, english.contains("not sold yet"))
            assertFalse(english, english.contains("0 of 0"))
            assertTrue(bangla, bangla.contains("এখনো বিক্রি হয়নি"))
        }

    // ---- GET_REORDER ------------------------------------------------------------------------

    @Test
    fun whatToBuyOfCokeShowsArithmeticThatAddsUp(): Unit =
        runBlocking {
            val answer = ask.ask("coke koyta kinbo", today) as AskAnswer.Reorder
            val r = answer.recommendation

            // Seven days at 3 = 21; margin 20% = 4.2; 21 + 4.2 - 16 = 9.2; bought 10, 0.8 over.
            assertEquals(units(16.0), r.currentStock)
            assertEquals(units(21.0), r.expectedDemand)
            assertEquals(units(4.2), r.safetyMargin)
            assertEquals(units(0.8), r.roundedUpBy)
            assertEquals(units(10.0), r.quantityToBuy)
            assertEquals(r.countedStock + r.quantityToBuy, r.expectedDemand + r.safetyMargin + r.roundedUpBy)
        }

    @Test
    fun looseGoodsAreBoughtInHalfKilos(): Unit =
        runBlocking {
            val r = (ask.ask("chini koto kinte hobe", today) as AskAnswer.Reorder).recommendation

            // 1.5 kg a day for 7 days = 10.5; margin 2.1; 10.5 + 2.1 - 5.5 = 7.1 kg needed;
            // the next half kilo is 7.5, so 0.4 more than needed.
            assertEquals(units(10.5), r.expectedDemand)
            assertEquals(units(2.1), r.safetyMargin)
            assertEquals(units(7.5), r.quantityToBuy)
            assertEquals(units(0.4), r.roundedUpBy)
        }

    @Test
    fun aProductThatDoesNotNeedBuyingSaysSo(): Unit =
        runBlocking {
            val r = (ask.ask("chal koyta kinbo", today) as AskAnswer.Reorder).recommendation
            assertTrue(r.enoughStock)
            assertEquals(Quantity.ZERO, r.quantityToBuy)
        }

    @Test
    fun whatToBuyWithNoProductNamedListsTheWholeShopMostUrgentFirst(): Unit =
        runBlocking {
            val answer = ask.ask("ki ki kinte hobe", today) as AskAnswer.ReorderList

            // Sugar runs out first (day 4), then coke (day 6). Rice needs nothing; oil is left out and counted.
            assertEquals(listOf("চিনি", "Coca-Cola 500ml"), answer.items.map { it.productName })
            assertEquals(listOf(units(7.5), units(10.0)), answer.items.map { it.recommendation.quantityToBuy })
            assertEquals(1, answer.notEnoughData)
        }

    @Test
    fun theSameBuyingQuestionInBanglaGetsTheSameAnswer(): Unit =
        runBlocking {
            assertEquals(ask.ask("ki ki kinte hobe", today), ask.ask("কী কী কিনতে হবে", today))
            // One product, asked in Latin letters and in Bangla: the same recommendation.
            val romanized = ask.ask("chini koyta kinbo", today)
            assertTrue(romanized is AskAnswer.Reorder)
            assertEquals(romanized, ask.ask("চিনি কতটা কিনব", today))
        }

    // ---- the app's own default method -------------------------------------------------------

    @Test
    fun theAppsDefaultMethodGivesTheSameFiguresOnSteadyDemand(): Unit =
        runBlocking {
            val withDefault = AskHisab(database, zone = zone)
            val r = (withDefault.ask("coke koyta kinbo", today) as AskAnswer.Reorder).recommendation

            assertEquals(units(21.0), r.expectedDemand)
            assertEquals(units(10.0), r.quantityToBuy)
        }

    // ---- nothing is written -----------------------------------------------------------------

    @Test
    fun askingTheseQuestionsWritesNothing(): Unit =
        runBlocking {
            val outboxBefore = database.syncOutboxDao().count()
            val stockBefore = database.stockMovementDao().currentStockScaled(coke.value)

            listOf("ki ki kom ache", "coke kobe sesh hobe", "coke koyta kinbo", "ki ki kinte hobe", "tel kobe sesh hobe")
                .forEach { ask.ask(it, today) }

            assertEquals(outboxBefore, database.syncOutboxDao().count())
            assertEquals(stockBefore, database.stockMovementDao().currentStockScaled(coke.value))
        }

    // ---- the words, in the language of the question -----------------------------------------

    @Test
    fun theReorderSentenceInEnglishAddsUpOnItsFace(): Unit =
        runBlocking {
            val answer = ask.ask("coke koyta kinbo", today)
            val english = text(answer, AnswerLanguage.forQuestion("coke koyta kinbo"))
            val numbers =
                english.lines().drop(1).map { line ->
                    Regex("""[+]?\d+(\.\d+)?""")
                        .findAll(line)
                        .last()
                        .value
                        .toDouble()
                }

            // Lines: current stock, expected demand, margin, rounding. Buy 10.
            assertTrue(english, english.lines().first().contains("Coca-Cola 500ml"))
            assertTrue(english, english.lines().first().contains("buy 10"))
            val (stock, demand, margin, rounding) = numbers
            assertEquals(english, stock + 10.0, demand + margin + rounding, 1e-9)
        }

    @Test
    fun theSameAnswerInBanglaUsesBanglaWordsAndBanglaDigitsAndTheSameFigures(): Unit =
        runBlocking {
            val answer = ask.ask("coke koyta kinbo", today)
            val bangla = text(answer, AnswerLanguage.BANGLA)
            val english = text(answer, AnswerLanguage.ENGLISH)

            assertTrue(bangla, bangla.contains("কিনুন"))
            // Bengali digits for 10, 16, 21 and 4.2, not ASCII ones.
            listOf("১০", "১৬", "২১", "৪.২").forEach { assertTrue("$it in $bangla", bangla.contains(it)) }
            // No ASCII digits anywhere but inside the product name, which is the shop's own text.
            assertFalse(bangla, bangla.replace("Coca-Cola 500ml", "").any { it in '0'..'9' })
            assertTrue(english, english.contains("buy 10"))
            // The same number of lines in both: nothing is said in one language and left out of the other.
            assertEquals(english.lines().size, bangla.lines().size)
        }

    @Test
    fun theAnswerFollowsTheQuestionsLanguageNotThePhones(): Unit =
        runBlocking {
            assertEquals(AnswerLanguage.ENGLISH, AnswerLanguage.forQuestion("ki ki kom ache"))
            assertEquals(AnswerLanguage.BANGLA, AnswerLanguage.forQuestion("কোন কোন জিনিস কম আছে"))
            assertEquals(AnswerLanguage.BANGLA, AnswerLanguage.forQuestion("কোন কোন product কম আছে"))

            val answer = ask.ask("tel kobe sesh hobe", today)
            val english = text(answer, AnswerLanguage.ENGLISH)
            val bangla = text(answer, AnswerLanguage.BANGLA)
            // The not-enough-data reply carries its three counts in both languages.
            assertTrue(english, english.contains("not enough sales history"))
            assertTrue(bangla, bangla.contains("বিক্রির তথ্য"))
        }
}
