package com.hisab.app.domain.language

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

/**
 * Step 83 — how well the rules do on the development set, and how long they
 * take, measured on a real phone rather than on a laptop: latency on the
 * hardware a shopkeeper actually owns is the number that matters (D028's
 * reference device is deliberately a cheap one).
 *
 * The numbers this prints are copied into
 * `research/language/step83-measurements.md`. The thresholds asserted below
 * are floors, not targets — they exist so the suite fails if the rules ever
 * quietly get worse, not to flatter the current state.
 */
@RunWith(AndroidJUnit4::class)
class DevSetMeasurementTest {
    private data class Example(
        val text: String,
        val intent: String,
        val entities: List<Triple<String, String, String>>,
    )

    private fun loadDevSet(): List<Example> {
        val raw =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("dev-set.jsonl")
                .bufferedReader()
                .readText()

        return raw
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val row = JSONObject(line)
                val entities = row.getJSONArray("entities")
                Example(
                    text = row.getString("text"),
                    intent = row.getString("intent"),
                    entities =
                        (0 until entities.length()).map { at ->
                            val entity = entities.getJSONObject(at)
                            Triple(
                                entity.getString("type"),
                                entity.getString("surface"),
                                entity.getString("value"),
                            )
                        },
                )
            }.toList()
    }

    /**
     * The shop's catalogue, built from the annotations themselves rather than
     * typed out again here — so it cannot drift away from the data set the
     * way a second hand-kept copy would.
     */
    private fun catalogueFor(examples: List<Example>): AliasMatcher {
        val aliasesByValue = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        for (example in examples) {
            for ((type, surface, value) in example.entities) {
                if (type != "product" && type != "customer") continue
                aliasesByValue.getOrPut(type to value) { mutableSetOf() } += surface
            }
        }
        return AliasMatcher(
            aliasesByValue.map { (key, surfaces) ->
                val (type, value) = key
                AliasEntry(
                    id = "$type:$value",
                    kind = if (type == "product") AliasEntry.Kind.PRODUCT else AliasEntry.Kind.CUSTOMER,
                    display = value,
                    aliases = surfaces.toList(),
                )
            },
        )
    }

    @Test
    fun measureAccuracyAndLatencyOnTheDevelopmentSet() {
        val examples = loadDevSet()
        assertTrue("the development set should have shipped with the tests", examples.size > 100)
        val catalogue = catalogueFor(examples)

        var intentCorrect = 0
        var productExpected = 0
        var productCorrect = 0
        var customerExpected = 0
        var customerCorrect = 0
        var periodExpected = 0
        var periodCorrect = 0
        var amountExpected = 0
        val confusions = mutableMapOf<String, Int>()

        for (example in examples) {
            val parsed = QuestionParser.parse(example.text, catalogue)

            if (parsed.intent.name == example.intent) {
                intentCorrect += 1
            } else {
                confusions.merge("${example.intent} read as ${parsed.intent.name}", 1, Int::plus)
                // The sentence itself, not just the tally: a count tells you
                // something is wrong, the words tell you what.
                println("STEP83MISS| ${example.intent} -> ${parsed.intent.name} | ${example.text}")
            }

            for ((type, _, value) in example.entities) {
                when (type) {
                    "product" -> {
                        productExpected += 1
                        if (parsed.product?.id == "product:$value") productCorrect += 1
                    }

                    "customer" -> {
                        customerExpected += 1
                        if (parsed.customer?.id == "customer:$value") customerCorrect += 1
                    }

                    "period" -> {
                        periodExpected += 1
                        if (parsed.period?.name?.lowercase() == value.lowercase()) periodCorrect += 1
                    }

                    "money", "quantity" -> {
                        amountExpected += 1
                    }
                }
            }
        }

        // Latency, warmed up first so the first-call cost of loading classes
        // is not reported as what every question costs.
        repeat(20) { QuestionParser.parse(examples.first().text, catalogue) }
        val timings =
            examples
                .map { example -> measureNanoTime { QuestionParser.parse(example.text, catalogue) } / 1_000.0 }
                .sorted()
        val median = timings[timings.size / 2]
        val p95 = timings[(timings.size * 95) / 100]
        val worst = timings.last()

        // Storage: what the rules themselves cost to keep, and what one
        // shop's catalogue costs on top.
        val indexSize = catalogueFor(examples).let { it.indexedPhraseCount }

        val percent = { part: Int, whole: Int -> if (whole == 0) "n/a" else "%.1f%%".format(100.0 * part / whole) }

        println("STEP83| examples                 | ${examples.size}")
        println("STEP83| intent accuracy          | ${percent(intentCorrect, examples.size)} ($intentCorrect/${examples.size})")
        println("STEP83| product matching         | ${percent(productCorrect, productExpected)} ($productCorrect/$productExpected)")
        println("STEP83| customer matching        | ${percent(customerCorrect, customerExpected)} ($customerCorrect/$customerExpected)")
        println("STEP83| period extraction        | ${percent(periodCorrect, periodExpected)} ($periodCorrect/$periodExpected)")
        println("STEP83| amount extraction        | no examples in the set ($amountExpected annotated)")
        println("STEP83| latency median           | %.0f us".format(median))
        println("STEP83| latency p95              | %.0f us".format(p95))
        println("STEP83| latency worst            | %.0f us".format(worst))
        println("STEP83| indexed names            | $indexSize phrases for ${catalogue.entryCount} things")
        for ((confusion, count) in confusions.entries.sortedByDescending { it.value }) {
            println("STEP83| confusion                | $confusion x$count")
        }

        // Floors, so a later change that makes the rules worse fails here.
        assertTrue("intent accuracy fell below 90%", intentCorrect * 100 >= examples.size * 90)
        assertTrue("product matching fell below 90%", productCorrect * 100 >= productExpected * 90)
        assertTrue("customer matching fell below 90%", customerCorrect * 100 >= customerExpected * 90)
        assertTrue("a question took longer than 20 ms, which a person would feel", worst < 20_000)
    }
}
