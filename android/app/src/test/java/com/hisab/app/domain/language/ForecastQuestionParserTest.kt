package com.hisab.app.domain.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Step 90: telling the three forecast questions apart from each other and from
 * the five that already existed.
 *
 * The risk in adding intents is not that the new ones fail to be recognised; it
 * is that their words — "sesh" (finished), "kom" (less), "ache" (is) — are also
 * in the old ones. So half of this file is old questions that must keep
 * meaning what they meant.
 */
class ForecastQuestionParserTest {
    private val aliases =
        AliasMatcher(
            listOf(
                AliasEntry("p-coke", AliasEntry.Kind.PRODUCT, "Coca-Cola 500ml", listOf("coke", "cola")),
                AliasEntry("p-sugar", AliasEntry.Kind.PRODUCT, "চিনি", listOf("chini", "sugar")),
                AliasEntry("p-oil", AliasEntry.Kind.PRODUCT, "তেল", listOf("tel", "oil")),
                AliasEntry("c-rahim", AliasEntry.Kind.CUSTOMER, "রহিম", listOf("rahim")),
            ),
        )

    private fun parse(text: String) = QuestionParser.parse(text, aliases)

    // ---- what is running low ----------------------------------------------------------------

    @Test
    fun `asking what is running low, in all three kinds of writing, reads as low stock with no product`() {
        listOf(
            "ki ki kom ache",
            "কোন কোন জিনিস কম আছে",
            "কোন কোন product কম আছে",
            "kon kon product sesh hoye ashche",
            "কী কী ফুরিয়ে আসছে",
            "low stock ki ki",
        ).forEach { question ->
            val parsed = parse(question)
            assertEquals(question, Intent.GET_LOW_STOCK, parsed.intent)
            assertNull(question, parsed.product)
        }
    }

    // ---- when will it run out ---------------------------------------------------------------

    @Test
    fun `asking when one named product runs out reads as a predicted stockout for that product`() {
        listOf(
            "coke kobe sesh hobe",
            "chini koto din cholbe",
            "চিনি কবে ফুরাবে",
            "চিনি কতদিন চলবে",
            "oil kobe furabe",
            "তেলের স্টক আর কতদিন চলবে",
        ).forEach { question ->
            val parsed = parse(question)
            assertEquals(question, Intent.GET_PREDICTED_STOCKOUT, parsed.intent)
            assertEquals(question, true, parsed.product != null)
        }
        assertEquals("p-coke", parse("coke kobe sesh hobe").product?.id)
        assertEquals("p-sugar", parse("চিনি কবে শেষ হবে").product?.id)
    }

    @Test
    fun `a named product that is called low is asked about as a stockout too`() {
        // "is it low?" is "how long will it last?" — answering with the days is the useful reply.
        val parsed = parse("coke kom ache")
        assertEquals(Intent.GET_PREDICTED_STOCKOUT, parsed.intent)
        assertEquals("p-coke", parsed.product?.id)
    }

    @Test
    fun `a when question about something the shop does not have is not answered with a list of other things`() {
        // "pepsi" is nobody's alias here. Reading it as "what is running low" would
        // answer a different question than the one asked.
        assertEquals(Intent.UNKNOWN, parse("pepsi kobe sesh hobe").intent)
    }

    // ---- what to buy ------------------------------------------------------------------------

    @Test
    fun `what to buy, with no product named, reads as a reorder for the whole shop`() {
        listOf(
            "ki ki kinte hobe",
            "কী কী কিনতে হবে",
            "kon kon jinis kinbo",
            "what to buy",
            "আজ কী কী অর্ডার করব",
        ).forEach { question ->
            val parsed = parse(question)
            assertEquals(question, Intent.GET_REORDER, parsed.intent)
            assertNull(question, parsed.product)
        }
    }

    @Test
    fun `what to buy of one named product reads as a reorder for that product, not as its stock`() {
        // "coke koyta kinbo" has a product and a how-many word in it, which on its
        // own is a stock question (see below). The buying word decides.
        val parsed = parse("coke koyta kinbo")
        assertEquals(Intent.GET_REORDER, parsed.intent)
        assertEquals("p-coke", parsed.product?.id)

        assertEquals("p-sugar", parse("চিনি কতটা কিনব").product?.id)
        assertEquals(Intent.GET_REORDER, parse("চিনি কতটা কিনব").intent)
    }

    // ---- found by probing sentences outside the development set -----------------------------

    @Test
    fun `customers' orders are not a buying question`() {
        // "order" is what a shopkeeper says about customers too. Answering these with a
        // shopping list would be a reply to a different question.
        listOf("ajke koto order asche", "customer order koto").forEach {
            assertEquals(it, Intent.UNKNOWN, parse(it).intent)
        }
    }

    @Test
    fun `order with something done or given is still a buying question`() {
        listOf("ki ki order korbo", "coke order dite hobe koyta", "আজ কী কী অর্ডার করব").forEach {
            assertEquals(it, Intent.GET_REORDER, parse(it).intent)
        }
    }

    @Test
    fun `less next to a price is about the price, not about stock`() {
        assertEquals(Intent.UNKNOWN, parse("kom dame chini").intent)
        assertEquals(Intent.UNKNOWN, parse("চিনির দাম কম").intent)
        // The same word with no price is still a stock question.
        assertEquals(Intent.GET_PREDICTED_STOCKOUT, parse("chini kom ache").intent)
    }

    // ---- the old five must not move ---------------------------------------------------------

    @Test
    fun `stock questions that share words with the new ones are still stock questions`() {
        assertEquals(Intent.GET_STOCK, parse("coke koyta ache").intent)
        assertEquals(Intent.GET_STOCK, parse("coke stock koto").intent)
        assertEquals(Intent.GET_STOCK, parse("চিনি স্টক কত").intent)
        assertEquals(Intent.GET_STOCK, parse("chini koto").intent)
    }

    @Test
    fun `overdue questions keep their sesh and stay overdue questions`() {
        // "sesh" is how a due date ends, and it is also "finished" in a stockout.
        listOf("kar kar baki meyad sesh", "meyad sesh hoyeche kar", "কোন কোন customer এর সময় শেষ").forEach {
            assertEquals(it, Intent.GET_OVERDUE, parse(it).intent)
        }
    }

    @Test
    fun `baki and sales questions are untouched by the new words`() {
        assertEquals(Intent.GET_CUSTOMER_BAKI, parse("rahim er baki koto").intent)
        assertEquals(Intent.GET_TODAY_SALES, parse("ajke koto sell hoise").intent)
        assertEquals(Intent.GET_PERIOD_SALES, parse("ei mase koto sell hoise").intent)
        // "kom" in a sales question is still about sales.
        assertEquals(Intent.GET_TODAY_SALES, parse("ajke kom sell hoise").intent)
    }

    @Test
    fun `a sentence with none of these words is still not understood`() {
        assertEquals(Intent.UNKNOWN, parse("ajker abohawa kemon").intent)
    }

    @Test
    fun `only the intents that name a product carry one`() {
        assertNull(parse("ki ki kom ache").product)
        assertNull(parse("kar baki meyad sesh").product)
        assertEquals("p-coke", parse("coke koyta kinbo").product?.id)
    }
}
