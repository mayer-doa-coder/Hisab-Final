package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Step 37: the same fixture, run against both implementations.
 *
 * This is Kotlin and the backend is TypeScript, so the code cannot be shared.
 * The numbers are: this file and `server/src/domain/sharedFixture.test.ts`
 * both read `fixtures/m2_sale_stock.tsv` and must agree with it.
 *
 * Reading the real file, rather than copying its cases in here, is the whole
 * point. A case added to the fixture runs on both sides immediately, and
 * neither side can drift by quietly keeping its own numbers.
 */
class SharedFixtureTest {
    private val at = Instant.parse("2026-09-15T10:00:00Z")
    private val shop = "shop-1"

    @Test
    fun `the shared fixture file was actually found and has cases`() {
        assertTrue("no fixture cases loaded from $FIXTURE_PATH", cases().isNotEmpty())
    }

    @Test
    fun `calculateLineTotal agrees with the shared fixture`() {
        forEachCase("line_total") { case ->
            val (quantity, price) = case.pairs().single()
            assertEquals(
                case.label,
                Money(case.expected[0]),
                calculateLineTotal(Quantity(quantity), Money(price)),
            )
        }
    }

    @Test
    fun `calculateSaleTotal agrees with the shared fixture`() {
        forEachCase("sale_total") { case ->
            val saleId = generateId()
            val items =
                case.pairs().map { (quantity, price) ->
                    SaleItem(saleId, generateId(), Quantity(quantity), Money(price))
                }
            assertEquals(case.label, Money(case.expected[0]), calculateSaleTotal(items))
        }
    }

    @Test
    fun `calculateCurrentStock agrees with the shared fixture`() {
        forEachCase("stock_balance") { case ->
            val product = generateId()
            assertEquals(
                case.label,
                Quantity(case.expected[0]),
                calculateCurrentStock(case.movements(product), product),
            )
        }
    }

    @Test
    fun `correctStock agrees with the shared fixture`() {
        forEachCase("correction") { case ->
            val (counted, recorded) = case.pairs().single()
            val movement = correctStock(generateId(), Quantity(counted), Quantity(recorded), at)
            assertEquals(case.label, Quantity(case.expected[0]), movement.quantityDelta)
        }
    }

    @Test
    fun `completeCreditSale agrees with the shared fixture`() {
        forEachCase("credit_sale") { case ->
            val sale = completeCreditSale(shop, generateId(), case.lines(), at)
            assertEquals("${case.label} (sale total)", Money(case.expected[0]), sale.sale.total)
            assertEquals("${case.label} (baki delta)", Money(case.expected[1]), sale.bakiEntry?.amountDelta)
        }
    }

    @Test
    fun `reverseSale agrees with the shared fixture, and leaves nothing behind`() {
        forEachCase("reversal") { case ->
            val original = completeCreditSale(shop, generateId(), case.lines(), at)
            val undone = reverseSale(original, at)

            assertEquals("${case.label} (reversal total)", Money(case.expected[0]), undone.sale.total)
            assertEquals(
                "${case.label} (stock returned)",
                Quantity(case.expected[1]),
                calculateCurrentStock(undone.stockMovements),
            )
            assertEquals("${case.label} (baki delta)", Money(case.expected[2]), undone.bakiEntry?.amountDelta)

            // The point of the reversal: after it, nothing is left over.
            assertEquals(Money.ZERO, original.sale.total + undone.sale.total)
            assertEquals(
                Quantity.ZERO,
                calculateCurrentStock(original.stockMovements + undone.stockMovements),
            )
            assertEquals(
                Money.ZERO,
                original.bakiEntry!!.amountDelta + undone.bakiEntry!!.amountDelta,
            )
        }
    }

    @Test
    fun `a cash sale from the fixture totals the same as the credit one`() {
        val credit = cases().first { it.kind == "credit_sale" }
        val cash = completeCashSale(shop, credit.lines(), at)
        assertEquals(Money(credit.expected[0]), cash.sale.total)
        assertEquals(null, cash.bakiEntry)
    }

    private fun forEachCase(
        kind: String,
        check: (FixtureCase) -> Unit,
    ) {
        val matching = cases().filter { it.kind == kind }
        assertTrue("no '$kind' cases in $FIXTURE_PATH", matching.isNotEmpty())
        matching.forEach(check)
    }

    private inner class FixtureCase(
        val kind: String,
        val label: String,
        val input: String,
        val expected: List<Long>,
    ) {
        /** "3000:5000;2000:2500" -> pairs of numbers. "-" means no entries at all. */
        fun pairs(): List<Pair<Long, Long>> =
            if (input == "-") {
                emptyList()
            } else {
                input.split(";").map {
                    val (left, right) = it.split(":")
                    left.toLong() to right.toLong()
                }
            }

        /** A different product per line, so the "one line per product" rule holds. */
        fun lines(): List<SaleLine> = pairs().map { (quantity, price) -> SaleLine(generateId(), Quantity(quantity), Money(price)) }

        /**
         * "restock:10000;sale:-3000" -> real movements built by the real
         * functions, not hand-made records. The fixture names the type and the
         * signed delta it expects; each function is asked for the movement
         * that produces it, so the signs themselves are under test too.
         */
        fun movements(product: EntityId): List<StockMovement> {
            if (input == "-") return emptyList()

            return input.split(";").map { entry ->
                val (name, amount) = entry.split(":")
                val delta = amount.toLong()
                when (name) {
                    "restock" -> {
                        restock(product, Quantity(delta), at)
                    }

                    "sale" -> {
                        sell(product, Quantity(-delta), generateId(), at)
                    }

                    "return" -> {
                        returnStock(product, Quantity(delta), at)
                    }

                    "damage" -> {
                        damage(product, Quantity(-delta), at)
                    }

                    // A correction is a shelf count, so it is expressed as
                    // counted vs. recorded — the pair that produces this delta.
                    "correction" -> {
                        correctStock(
                            product,
                            Quantity(maxOf(delta, 0)),
                            Quantity(maxOf(-delta, 0)),
                            at,
                        )
                    }

                    else -> {
                        throw IllegalArgumentException("Unknown movement type in fixture: $name")
                    }
                }
            }
        }
    }

    private fun cases(): List<FixtureCase> =
        findFixture()
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val fields = line.split("\t")
                assertTrue("Fixture line needs at least 4 tab-separated fields: $line", fields.size >= 4)
                FixtureCase(
                    kind = fields[0],
                    label = fields[1],
                    input = fields[2],
                    expected = fields.drop(3).map { it.toLong() },
                )
            }

    private companion object {
        const val FIXTURE_PATH = "fixtures/m2_sale_stock.tsv"

        /**
         * Walks up from the working directory until the fixture turns up, so
         * it does not matter whether Gradle runs the tests from `android/app`,
         * `android/`, or the repository root.
         */
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
