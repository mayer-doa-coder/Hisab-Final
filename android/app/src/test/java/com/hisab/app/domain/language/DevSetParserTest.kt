package com.hisab.app.domain.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every sentence in the development set, run through the parser, on the JVM.
 *
 * Step 83 measured this on the phone, which is where the latency has to be
 * measured but is a poor place to find out that a keyword added for one intent
 * has quietly captured another's sentences: it needs a device, so it runs when
 * someone remembers to. This runs on every build.
 *
 * It is a **regression guard, not a measure of accuracy.** The rules were written
 * while looking at this set, so passing it says they still do what they were
 * built to do — nothing about a sentence nobody has shown them. That number
 * comes from the held-out set at Step 108 (D047, D048).
 *
 * The catalogue is built from the annotations themselves, exactly as the on-phone
 * test builds it, so it cannot drift from the data.
 */
class DevSetParserTest {
    private data class Example(
        val text: String,
        val intent: String,
        val products: List<Pair<String, String>>,
        val customers: List<Pair<String, String>>,
    )

    private val entity = Regex("""\{"type":"([^"]*)","surface":"([^"]*)","value":"([^"]*)"\}""")

    private fun loadDevSet(): List<Example> {
        val file = findDevSet()
        return file
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val entities = entity.findAll(line.substringAfter("\"entities\":")).map { it.groupValues }.toList()
                Example(
                    text = Regex(""""text":"([^"]*)"""").find(line)!!.groupValues[1],
                    intent = Regex(""""intent":"([^"]*)"""").find(line)!!.groupValues[1],
                    products = entities.filter { it[1] == "product" }.map { it[2] to it[3] },
                    customers = entities.filter { it[1] == "customer" }.map { it[2] to it[3] },
                )
            }
    }

    private fun catalogue(examples: List<Example>): AliasMatcher {
        fun entries(
            kind: AliasEntry.Kind,
            prefix: String,
            pairs: List<Pair<String, String>>,
        ) = pairs
            .groupBy({ it.second }, { it.first })
            .map { (value, surfaces) -> AliasEntry("$prefix:$value", kind, value, surfaces.distinct()) }

        return AliasMatcher(
            entries(AliasEntry.Kind.PRODUCT, "product", examples.flatMap { it.products }) +
                entries(AliasEntry.Kind.CUSTOMER, "customer", examples.flatMap { it.customers }),
        )
    }

    @Test
    fun `every development sentence is read as the intent it was written for, with its product`() {
        val examples = loadDevSet()
        assertTrue("the development set should have all eight intents' sentences", examples.size >= 200)
        val matcher = catalogue(examples)

        val misses =
            examples.mapNotNull { example ->
                val parsed = QuestionParser.parse(example.text, matcher)
                val wrongIntent = parsed.intent.name != example.intent
                val wrongProduct =
                    example.products.isNotEmpty() &&
                        parsed.intent.name == example.intent &&
                        parsed.product?.id != "product:${example.products.first().second}"
                if (wrongIntent || wrongProduct) {
                    "${example.intent} -> ${parsed.intent.name} (product ${parsed.product?.id}) | ${example.text}"
                } else {
                    null
                }
            }

        assertEquals("sentences read wrongly:\n" + misses.joinToString("\n"), 0, misses.size)
    }

    @Test
    fun `all eight intents are in the development set`() {
        val counts = loadDevSet().groupingBy { it.intent }.eachCount()
        listOf(
            "GET_STOCK",
            "GET_CUSTOMER_BAKI",
            "GET_OVERDUE",
            "GET_TODAY_SALES",
            "GET_PERIOD_SALES",
            "GET_LOW_STOCK",
            "GET_PREDICTED_STOCKOUT",
            "GET_REORDER",
        ).forEach { intent -> assertTrue("$intent has ${counts[intent]} examples", (counts[intent] ?: 0) >= 10) }
    }

    private fun findDevSet(): File {
        var directory: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "research/language/dev/dev-set.jsonl")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("research/language/dev/dev-set.jsonl not found above the working directory")
    }
}
