package com.hisab.app.data.ask

import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.baki.BakiRepository
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.stock.StockRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.language.AliasEntry
import com.hisab.app.domain.language.AliasMatcher
import com.hisab.app.domain.language.AskAnswer
import com.hisab.app.domain.language.Intent
import com.hisab.app.domain.language.OverdueCustomer
import com.hisab.app.domain.language.ParsedQuestion
import com.hisab.app.domain.language.QuestionParser
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
) {
    private val products = ProductRepository(database, shopId = shopId)
    private val customers = CustomerRepository(database, shopId = shopId)
    private val stock = StockRepository(database, shopId = shopId)
    private val baki = BakiRepository(database)

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
