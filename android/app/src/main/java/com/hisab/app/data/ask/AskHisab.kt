package com.hisab.app.data.ask

import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.baki.BakiRepository
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.data.forecast.DemandRepository
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.stock.StockRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.forecast.InventoryAdvisor
import com.hisab.app.domain.forecast.ProductAdvice
import com.hisab.app.domain.forecast.StockoutOutlook
import com.hisab.app.domain.language.AliasEntry
import com.hisab.app.domain.language.AliasMatcher
import com.hisab.app.domain.language.AskAnswer
import com.hisab.app.domain.language.Intent
import com.hisab.app.domain.language.LowStockItem
import com.hisab.app.domain.language.OverdueCustomer
import com.hisab.app.domain.language.ParsedQuestion
import com.hisab.app.domain.language.QuestionParser
import com.hisab.app.domain.language.ReorderItem
import com.hisab.app.domain.overdueAmount
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ask Hisab (Steps 78–82): reads a question, then answers it from what is
 * already on this phone.
 *
 * Everything here is a lookup against data the app already keeps — stock is
 * still the sum of its movements and baki still the sum of its entries
 * (D001, D020). Asking a question never writes anything, and never needs a
 * network: no model, no server, nothing to wait for.
 *
 * [today] is passed in rather than read from the clock, so a question about
 * "today" can be tested (the same reason D041 gives for the baki rules).
 */
class AskHisab(
    private val database: HisabDatabase,
    private val shopId: String = LOCAL_SHOP_ID,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** What turns a product history and its stock into advice. Injectable so a test can fix the method. */
    private val advisor: InventoryAdvisor = InventoryAdvisor(),
) {
    private val products = ProductRepository(database, shopId = shopId)
    private val customers = CustomerRepository(database, shopId = shopId)
    private val stock = StockRepository(database, shopId = shopId)
    private val baki = BakiRepository(database)
    private val demand = DemandRepository(database, shopId = shopId, zone = zone)

    /** Everything this shop can be asked about by name, for Step 74's matcher. */
    private suspend fun matcher(): AliasMatcher {
        val productEntries =
            products.observe(includeInactive = true).first().map {
                AliasEntry(it.id, AliasEntry.Kind.PRODUCT, it.name, it.aliases)
            }
        val customerEntries =
            customers.observeAll().first().map {
                AliasEntry(it.id, AliasEntry.Kind.CUSTOMER, it.name)
            }
        return AliasMatcher(productEntries + customerEntries)
    }

    suspend fun parse(question: String): ParsedQuestion = QuestionParser.parse(question, matcher())

    suspend fun ask(
        question: String,
        today: LocalDate = LocalDate.now(zone),
    ): AskAnswer = answer(parse(question), today)

    suspend fun answer(
        parsed: ParsedQuestion,
        today: LocalDate,
    ): AskAnswer =
        when (parsed.intent) {
            Intent.GET_STOCK -> stockAnswer(parsed)
            Intent.GET_CUSTOMER_BAKI -> bakiAnswer(parsed)
            Intent.GET_OVERDUE -> overdueAnswer(today)
            Intent.GET_TODAY_SALES -> AskAnswer.TodaySales(takings(today, today))
            Intent.GET_PERIOD_SALES -> periodAnswer(parsed, today)
            Intent.GET_LOW_STOCK -> lowStockAnswer(today)
            Intent.GET_PREDICTED_STOCKOUT -> stockoutAnswer(parsed, today)
            Intent.GET_REORDER -> reorderAnswer(parsed, today)
            Intent.UNKNOWN -> AskAnswer.NotUnderstood
        }

    private suspend fun stockAnswer(parsed: ParsedQuestion): AskAnswer {
        val match = parsed.product ?: return AskAnswer.NotUnderstood
        val product =
            products.byId(EntityId(match.id))
                ?: return AskAnswer.UnknownProduct(match.matchedTokens.joinToString(" "))
        return AskAnswer.Stock(
            productName = product.name,
            quantity = stock.currentStock(EntityId(product.id)),
            unit = product.unit,
        )
    }

    /**
     * Which products will not last the lead time (Step 90).
     *
     * One query for every product with its stock, not one per product, and one
     * read of the sales history, so a shop of 200 products is two reads and a
     * pass in memory. Products without enough history are counted and left out
     * rather than guessed at; the answer carries that count.
     */
    private suspend fun lowStockAnswer(today: LocalDate): AskAnswer {
        val series = demand.series(lastCompleteDay(today)).associateBy { it.productId.value }
        var notEnoughData = 0
        val items = mutableListOf<LowStockItem>()

        for (row in stock.observeProductsWithStock().first()) {
            val product = row.product
            val onShelf = Quantity(row.stockScaled)
            when (val advice = advisor.advise(series[product.id], onShelf, product.unit)) {
                is ProductAdvice.NotEnoughData -> {
                    notEnoughData += 1
                }

                is ProductAdvice.Ready -> {
                    if (advice.runsOutBeforeRestock) {
                        items += LowStockItem(product.name, product.unit, onShelf, advice.outlook)
                    }
                }
            }
        }
        return AskAnswer.LowStock(items.sortedWith(MOST_URGENT_FIRST), notEnoughData, advisor.settings.leadTimeDays)
    }

    /** When one named product runs out. */
    private suspend fun stockoutAnswer(
        parsed: ParsedQuestion,
        today: LocalDate,
    ): AskAnswer {
        val match = parsed.product ?: return AskAnswer.NotUnderstood
        val product =
            products.byId(EntityId(match.id))
                ?: return AskAnswer.UnknownProduct(match.matchedTokens.joinToString(" "))
        val onShelf = stock.currentStock(EntityId(product.id))
        return when (val advice = advisor.advise(demand.seriesFor(EntityId(product.id), lastCompleteDay(today)), onShelf, product.unit)) {
            is ProductAdvice.NotEnoughData -> {
                AskAnswer.NotEnoughData(product.name, product.unit, onShelf, advice.daysObserved, advice.demandDays)
            }

            is ProductAdvice.Ready -> {
                AskAnswer.PredictedStockout(product.name, product.unit, onShelf, advice.outlook)
            }
        }
    }

    /** What to buy: of one named product, or across the shop when none is named. */
    private suspend fun reorderAnswer(
        parsed: ParsedQuestion,
        today: LocalDate,
    ): AskAnswer {
        val match = parsed.product ?: return reorderListAnswer(today)
        val product =
            products.byId(EntityId(match.id))
                ?: return AskAnswer.UnknownProduct(match.matchedTokens.joinToString(" "))
        val onShelf = stock.currentStock(EntityId(product.id))
        return when (val advice = advisor.advise(demand.seriesFor(EntityId(product.id), lastCompleteDay(today)), onShelf, product.unit)) {
            is ProductAdvice.NotEnoughData -> {
                AskAnswer.NotEnoughData(product.name, product.unit, onShelf, advice.daysObserved, advice.demandDays)
            }

            is ProductAdvice.Ready -> {
                AskAnswer.Reorder(product.name, product.unit, advice.reorder)
            }
        }
    }

    private suspend fun reorderListAnswer(today: LocalDate): AskAnswer {
        val series = demand.series(lastCompleteDay(today)).associateBy { it.productId.value }
        var notEnoughData = 0
        val items = mutableListOf<ReorderItem>()

        for (row in stock.observeProductsWithStock().first()) {
            val product = row.product
            when (val advice = advisor.advise(series[product.id], Quantity(row.stockScaled), product.unit)) {
                is ProductAdvice.NotEnoughData -> {
                    notEnoughData += 1
                }

                is ProductAdvice.Ready -> {
                    if (!advice.reorder.enoughStock) {
                        items += ReorderItem(product.name, product.unit, advice.reorder, advice.outlook)
                    }
                }
            }
        }
        return AskAnswer.ReorderList(items.sortedWith(MOST_URGENT_FIRST_TO_BUY), notEnoughData)
    }

    private suspend fun bakiAnswer(parsed: ParsedQuestion): AskAnswer {
        val match = parsed.customer ?: return AskAnswer.NotUnderstood
        val customer =
            customers.byId(EntityId(match.id))
                ?: return AskAnswer.UnknownCustomer(match.matchedTokens.joinToString(" "))
        return AskAnswer.CustomerBaki(
            customerName = customer.name,
            balance = Money(database.bakiEntryDao().balancePoisha(customer.id)),
        )
    }

    /**
     * Overdue is decided by the same `overdueAmount` the Customers screen
     * uses (D041), so a question and the list cannot disagree about who is
     * late. The rule is asked, not re-implemented here.
     */
    private suspend fun overdueAnswer(today: LocalDate): AskAnswer {
        val entries = baki.observeAll().first()
        return AskAnswer.Overdue(
            customers
                .observeAll()
                .first()
                .map { it to overdueAmount(entries, EntityId(it.id), today) }
                .filter { (_, overdue) -> overdue.minorUnits > 0 }
                .sortedByDescending { (_, overdue) -> overdue.minorUnits }
                .map { (customer, overdue) -> OverdueCustomer(customer.name, overdue) },
        )
    }

    private suspend fun periodAnswer(
        parsed: ParsedQuestion,
        today: LocalDate,
    ): AskAnswer {
        val period = parsed.period ?: return AskAnswer.NotUnderstood
        val range = period.range(today)
        return AskAnswer.PeriodSales(period, takings(range.start, range.endInclusive))
    }

    /**
     * Forecasts are built from finished days. Today is still being sold, so
     * counting it would read a half-day as a whole day of low sales and pull
     * every forecast down; the series therefore ends yesterday, and "day 1" of a
     * forecast is tomorrow.
     */
    private fun lastCompleteDay(today: LocalDate): LocalDate = today.minusDays(1)

    private companion object {
        /** Already out first, then soonest, then by name so the order never depends on the database. */
        fun urgency(outlook: StockoutOutlook): Int =
            when (outlook) {
                StockoutOutlook.AlreadyOut -> 0
                is StockoutOutlook.InDays -> outlook.days
                is StockoutOutlook.NotSoon -> Int.MAX_VALUE
            }

        val MOST_URGENT_FIRST: Comparator<LowStockItem> =
            compareBy<LowStockItem> { urgency(it.outlook) }.thenBy { it.productName.lowercase() }

        val MOST_URGENT_FIRST_TO_BUY: Comparator<ReorderItem> =
            compareBy<ReorderItem> { urgency(it.outlook) }.thenBy { it.productName.lowercase() }
    }

    /** Sales in a range of whole days, in the shop's own time zone. */
    private suspend fun takings(
        fromInclusive: LocalDate,
        toInclusive: LocalDate,
    ): Money {
        val from = fromInclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val to =
            toInclusive
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        return Money(database.saleDao().totalTakingsPoisha(shopId, from, to))
    }
}
