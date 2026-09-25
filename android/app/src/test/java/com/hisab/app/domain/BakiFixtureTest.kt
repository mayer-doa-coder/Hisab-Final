package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Step 49: the same baki fixture, run against both implementations.
 *
 * This is Kotlin and the backend is TypeScript, so the code cannot be shared.
 * The numbers are: this file and `server/src/domain/bakiFixture.test.ts` both
 * read `fixtures/m3_baki.tsv` and must agree with it. The header of that file
 * explains the token language a ledger is written in.
 *
 * Every ledger is built with the real functions (`addCredit`, `receivePayment`,
 * `reverseEntry`, `completeCreditSale`, `reverseSale`), not hand-made rows, so
 * the signs and the types are under test as well as the sums.
 */
class BakiFixtureTest {
    private val start = Instant.parse("2026-09-15T10:00:00Z")
    private val shop = "shop-1"

    @Test
    fun `the shared baki fixture file was actually found and has cases`() {
        assertTrue("no fixture cases loaded from $FIXTURE_PATH", cases().isNotEmpty())
    }

    @Test
    fun `calculateBalance agrees with the shared fixture`() {
        forEachCase("baki_balance") { case ->
            val customer = generateId()
            val entries = ledger(case.input, customer)
            assertEquals(case.label, Money(case.expected.toLong()), calculateBalance(entries, customer))

            // Somebody else's baki, sitting in the same list, must change nothing.
            val stranger = generateId()
            val mixed = entries + addCredit(stranger, Money(99_999), start) + receivePayment(stranger, Money(1), start)
            assertEquals(
                "${case.label} (with another customer's entries mixed in)",
                calculateBalance(entries, customer),
                calculateBalance(mixed, customer),
            )
        }
    }

    @Test
    fun `overdueAmount agrees with the shared fixture`() {
        forEachCase("baki_overdue") { case ->
            val (today, tokens) = case.input.split("|")
            val customer = generateId()
            val entries = ledger(tokens, customer)
            val expected = Money(case.expected.toLong())

            assertEquals(case.label, expected, overdueAmount(entries, customer, LocalDate.parse(today)))
            assertEquals("${case.label} (isOverdue)", expected.minorUnits > 0, isOverdue(entries, customer, LocalDate.parse(today)))
        }
    }

    @Test
    fun `the entry the fixture says is refused is refused`() {
        forEachCase("baki_refused") { case ->
            assertEquals("refused", case.expected)
            val error = assertThrows(case.label, IllegalArgumentException::class.java) { ledger(case.input, generateId()) }
            assertFalse("the refusal should say why", error.message.isNullOrBlank())
        }
    }

    /** Builds one customer's ledger from the fixture's token language, in order. */
    private fun ledger(
        input: String,
        customer: EntityId,
    ): List<BakiEntry> {
        if (input == "-") return emptyList()

        val entries = mutableListOf<BakiEntry>()
        val sales = mutableMapOf<Int, SaleTransaction>()

        input.split(";").forEachIndexed { index, token ->
            val at = start.plusSeconds(index * 60L)
            val (name, argument) = token.split(":", limit = 2)
            val (amountText, dueText) = argument.split("@", limit = 2).let { it[0] to it.getOrNull(1) }
            val due = dueText?.let(LocalDate::parse)

            when (name) {
                "credit" -> {
                    entries += addCredit(customer, Money(amountText.toLong()), at, due)
                }

                "payment" -> {
                    entries += receivePayment(customer, Money(amountText.toLong()), at)
                }

                "sale" -> {
                    val line = SaleLine(generateId(), Quantity(1000), Money(amountText.toLong()))
                    val sale = completeCreditSale(shop, customer, listOf(line), at, due)
                    sales[index] = sale
                    entries += sale.bakiEntry!!
                }

                "undo" -> {
                    entries += reverseEntry(entries[amountText.toInt()], at)
                }

                "unsale" -> {
                    entries += reverseSale(sales.getValue(amountText.toInt()), at).bakiEntry!!
                }

                else -> {
                    throw AssertionError("Unknown token in fixture: $token")
                }
            }
        }
        return entries
    }

    private fun forEachCase(
        kind: String,
        check: (FixtureCase) -> Unit,
    ) {
        val matching = cases().filter { it.kind == kind }
        assertTrue("no '$kind' cases in $FIXTURE_PATH", matching.isNotEmpty())
        matching.forEach(check)
    }

    private class FixtureCase(
        val kind: String,
        val label: String,
        val input: String,
        val expected: String,
    )

    private fun cases(): List<FixtureCase> =
        findFixture()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val fields = line.split("\t")
                assertEquals("Fixture line needs exactly 4 tab-separated fields: $line", 4, fields.size)
                FixtureCase(kind = fields[0], label = fields[1], input = fields[2], expected = fields[3])
            }

    private companion object {
        const val FIXTURE_PATH = "fixtures/m3_baki.tsv"

        /** Walks up from the working directory until the fixture turns up, wherever Gradle runs the tests from. */
        fun findFixture(): File {
            val workingDirectory = System.getProperty("user.dir") ?: "."
            var directory: File? = File(workingDirectory).absoluteFile
            while (directory != null) {
                val candidate = File(directory, FIXTURE_PATH)
                if (candidate.isFile) return candidate
                directory = directory.parentFile
            }
            throw IllegalStateException("Could not find $FIXTURE_PATH above $workingDirectory")
        }
    }
}
