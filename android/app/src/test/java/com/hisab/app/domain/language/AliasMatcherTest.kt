package com.hisab.app.domain.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 74: finding the shop's own products and customers in a question. */
class AliasMatcherTest {
    private val coke =
        AliasEntry(
            id = "p-coke",
            kind = AliasEntry.Kind.PRODUCT,
            display = "Coca-Cola 500ml",
            aliases = listOf("coke", "cola"),
        )
    private val sugar =
        AliasEntry(
            id = "p-sugar",
            kind = AliasEntry.Kind.PRODUCT,
            display = "চিনি",
            aliases = listOf("chini", "sugar"),
        )
    private val rahim =
        AliasEntry(id = "c-rahim", kind = AliasEntry.Kind.CUSTOMER, display = "রহিম")
    private val matcher = AliasMatcher(listOf(coke, sugar, rahim))

    // The check Step 74 names, word for word.
    @Test
    fun `coke matches Coca-Cola 500ml because the shop set that alias`() {
        val matches = matcher.resolveText("coke koyta ase")

        assertEquals(1, matches.size)
        assertEquals("p-coke", matches.single().id)
        assertEquals("Coca-Cola 500ml", matches.single().display)
    }

    @Test
    fun `the full product name matches too, punctuation and case and all`() {
        assertEquals("p-coke", matcher.resolveText("COCA-COLA 500ML stock koto").single().id)
    }

    @Test
    fun `a bangla product name matches when typed in bangla`() {
        assertEquals("p-sugar", matcher.resolveText("চিনি stock কত").single().id)
    }

    @Test
    fun `the same product matches by its romanized alias as well`() {
        assertEquals("p-sugar", matcher.resolveText("chini koto ache").single().id)
    }

    @Test
    fun `a customer is found and told apart from a product`() {
        val match = matcher.resolveText("রহিম er baki koto").single()
        assertEquals("c-rahim", match.id)
        assertEquals(AliasEntry.Kind.CUSTOMER, match.kind)
    }

    @Test
    fun `the longest name wins, so a bigger bottle is not mistaken for a smaller one`() {
        val litre =
            AliasEntry(
                id = "p-coke-1l",
                kind = AliasEntry.Kind.PRODUCT,
                display = "Coca-Cola 1L",
                aliases = listOf("coke 1l", "boro coke"),
            )
        val bigger = AliasMatcher(listOf(coke, litre))

        val match = bigger.resolveText("boro coke koyta ache").single()

        assertEquals("p-coke-1l", match.id)
        assertEquals(2, match.wordCount)
    }

    @Test
    fun `two different things in one question are both found, in order`() {
        val matches = matcher.resolveText("coke ar chini koto ache")

        assertEquals(listOf("p-coke", "p-sugar"), matches.map { it.id })
        assertTrue(matches[0].startIndex < matches[1].startIndex)
    }

    @Test
    fun `a question naming nothing the shop sells matches nothing`() {
        assertTrue(matcher.resolveText("ajke koto bikri hoyeche").isEmpty())
    }

    @Test
    fun `an alias the shop never set does not match`() {
        assertTrue(matcher.resolveText("pepsi koyta ache").isEmpty())
    }

    @Test
    fun `where the match sits in the sentence is reported, so the rest can be read around it`() {
        val match = matcher.resolveText("coke koyta ache").single()

        assertEquals(0, match.startIndex)
        assertEquals(1, match.endIndex)
        assertEquals(listOf("coke"), match.matchedTokens)
    }
}
