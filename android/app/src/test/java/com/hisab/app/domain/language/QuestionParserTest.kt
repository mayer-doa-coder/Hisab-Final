package com.hisab.app.domain.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Steps 78–82: reading what a question is asking, before anything looks up an answer. */
class QuestionParserTest {
    private val aliases =
        AliasMatcher(
            listOf(
                AliasEntry("p-coke", AliasEntry.Kind.PRODUCT, "Coca-Cola 500ml", listOf("coke", "cola")),
                AliasEntry("p-sugar", AliasEntry.Kind.PRODUCT, "চিনি", listOf("chini", "sugar")),
                AliasEntry("c-rahim", AliasEntry.Kind.CUSTOMER, "রহিম", listOf("rahim")),
                AliasEntry("c-karim", AliasEntry.Kind.CUSTOMER, "করিম", listOf("karim")),
            ),
        )

    private fun parse(text: String) = QuestionParser.parse(text, aliases)

    // Step 78's own check.
    @Test
    fun `coke stock koto asks for coke's stock`() {
        val parsed = parse("coke stock koto")
        assertEquals(Intent.GET_STOCK, parsed.intent)
        assertEquals("p-coke", parsed.product?.id)
    }

    @Test
    fun `the same question in bangla asks the same thing`() {
        val parsed = parse("চিনি স্টক কত")
        assertEquals(Intent.GET_STOCK, parsed.intent)
        assertEquals("p-sugar", parsed.product?.id)
    }

    @Test
    fun `stock is understood without the word stock`() {
        assertEquals(Intent.GET_STOCK, parse("coke koyta ase").intent)
        assertEquals(Intent.GET_STOCK, parse("চিনি কয়টা আছে").intent)
        assertEquals(Intent.GET_STOCK, parse("chini koto").intent)
    }

    // Step 79's own check.
    @Test
    fun `rahim er baki koto asks what rahim owes`() {
        val parsed = parse("rahim er baki koto")
        assertEquals(Intent.GET_CUSTOMER_BAKI, parsed.intent)
        assertEquals("c-rahim", parsed.customer?.id)
    }

    @Test
    fun `a bangla name with its ending glued on is still found`() {
        // রহিমের is রহিম plus a possessive ending.
        val parsed = parse("রহিমের বাকি কত")
        assertEquals(Intent.GET_CUSTOMER_BAKI, parsed.intent)
        assertEquals("c-rahim", parsed.customer?.id)
    }

    // Step 80's own check.
    @Test
    fun `asking who is late is an overdue question, not one customer's baki`() {
        assertEquals(Intent.GET_OVERDUE, parse("kar baki meyad periyeche").intent)
        assertEquals(Intent.GET_OVERDUE, parse("কার বাকির মেয়াদ পেরিয়েছে").intent)
        assertEquals(Intent.GET_OVERDUE, parse("কাদের baki overdue").intent)
    }

    @Test
    fun `naming a customer keeps it their question even when it sounds like overdue`() {
        val parsed = parse("rahim er baki koto")
        assertEquals(Intent.GET_CUSTOMER_BAKI, parsed.intent)
        assertNull("no period should be read into it", parsed.period)
    }

    // Step 81's own check.
    @Test
    fun `today's sales are recognised, with or without the word for today`() {
        assertEquals(Intent.GET_TODAY_SALES, parse("ajke koto sell hoise").intent)
        assertEquals(Intent.GET_TODAY_SALES, parse("আজকে কত বিক্রি হয়েছে").intent)
        assertEquals(Intent.GET_TODAY_SALES, parse("koto bikri holo").intent)
    }

    // Step 82's own check.
    @Test
    fun `a period other than today is read as that period`() {
        assertEquals(Period.THIS_MONTH, parse("ei mase koto sell hoise").period)
        assertEquals(Period.THIS_MONTH, parse("এই মাসে কত বিক্রি হয়েছে").period)
        assertEquals(Period.LAST_MONTH, parse("goto mase koto bikri").period)
        assertEquals(Period.THIS_WEEK, parse("ei soptahe koto bikri").period)
        assertEquals(Period.YESTERDAY, parse("kalke koto sell hoise").period)
    }

    @Test
    fun `a period question is a period question, not a today question`() {
        assertEquals(Intent.GET_PERIOD_SALES, parse("goto mase koto bikri").intent)
        assertEquals(Intent.GET_TODAY_SALES, parse("aj koto bikri").intent)
    }

    @Test
    fun `something this version cannot answer is not guessed at`() {
        assertEquals(Intent.UNKNOWN, parse("ei masher abohawa kemon").intent)
        assertEquals(Intent.UNKNOWN, parse("hello").intent)
        assertEquals(Intent.UNKNOWN, parse("").intent)
    }

    @Test
    fun `a question about stock does not carry a customer, and the reverse`() {
        val stock = parse("coke stock koto")
        assertNull(stock.customer)
        val baki = parse("rahim er baki koto")
        assertNull(baki.product)
    }

    @Test
    fun `periods are turned into real dates around the day the shop is having`() {
        val friday = LocalDate.of(2026, 9, 25)
        assertEquals(friday..friday, Period.TODAY.range(friday))
        assertEquals(
            LocalDate.of(2026, 9, 24)..LocalDate.of(2026, 9, 24),
            Period.YESTERDAY.range(friday),
        )
        assertEquals(LocalDate.of(2026, 9, 1)..friday, Period.THIS_MONTH.range(friday))
        assertEquals(
            LocalDate.of(2026, 8, 1)..LocalDate.of(2026, 8, 31),
            Period.LAST_MONTH.range(friday),
        )
    }
}
