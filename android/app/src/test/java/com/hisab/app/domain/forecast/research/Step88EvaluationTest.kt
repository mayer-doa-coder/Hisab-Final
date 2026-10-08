package com.hisab.app.domain.forecast.research

import com.hisab.app.domain.forecast.ForecastMethods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Step 88 — the comparison itself, and the guard that keeps its saved results
 * honest.
 *
 * Two modes, one test:
 *
 *  - **Regenerate** (`HISAB_REGENERATE_RESEARCH=1`): writes the series, the
 *    results, the report and the checksums into `research/forecasting/`.
 *  - **Every other run**: recomputes the same things and fails if any saved file
 *    differs from what the code now produces.
 *
 * The second mode is the point. A results table is only evidence about the
 * methods while it was produced by the methods, so a change to a method, a
 * default, the generator or a metric fails here until the numbers are
 * regenerated and the change to them is reviewed. The deterministic files are
 * compared byte for byte; latency, which is a stopwatch, is written but never
 * compared.
 *
 * Regenerate with, from `android/`:
 * `HISAB_REGENERATE_RESEARCH=1 ./gradlew cleanTestDebugUnitTest testDebugUnitTest --tests '*Step88EvaluationTest'`
 * (the clean is what makes Gradle run a test whose inputs have not changed).
 */
class Step88EvaluationTest {
    private val regenerate = System.getenv("HISAB_REGENERATE_RESEARCH") == "1"
    private val forecasting = findForecastingDirectory()
    private val seriesDirectory = File(forecasting, "synthetic/v1")
    private val resultsDirectory = File(forecasting, "results/step88-synthetic-v1")

    @Test
    fun `the saved series, results and checksums are exactly what the code produces`() {
        val series = SyntheticDemand.generate()
        val methods = ForecastMethods.all()
        val evaluation = Step88.evaluate(series, methods)

        val seriesCsv = Step88.seriesCsv(series)
        val resultsCsv = Step88.resultsCsv(evaluation)

        if (regenerate) {
            seriesDirectory.mkdirs()
            resultsDirectory.mkdirs()
            File(seriesDirectory, "series.csv").writeText(seriesCsv)
            File(resultsDirectory, "results.csv").writeText(resultsCsv)

            val stability = Step88.stability(methods, Step88.STABILITY_SEEDS)
            File(resultsDirectory, "seed-stability.csv").writeText(Step88.stabilityCsv(stability))

            val latency = Step88.latencyMicros(methods, series)
            File(resultsDirectory, "latency-jvm.csv").writeText(Step88.latencyCsv(latency))
            File(resultsDirectory, "REPORT.md").writeText(Step88.reportMarkdown(evaluation, latency, stability))

            File(seriesDirectory, "SHA256SUMS").writeText(sumLine(File(seriesDirectory, "series.csv")))
            File(resultsDirectory, "SHA256SUMS").writeText(
                sumLine(File(resultsDirectory, "results.csv")) + sumLine(File(resultsDirectory, "seed-stability.csv")),
            )
        }

        assertSaved(File(seriesDirectory, "series.csv"), seriesCsv)
        assertSaved(File(resultsDirectory, "results.csv"), resultsCsv)
        assertSaved(
            File(resultsDirectory, "seed-stability.csv"),
            Step88.stabilityCsv(Step88.stability(methods, Step88.STABILITY_SEEDS)),
        )
        assertEquals(sumLine(File(seriesDirectory, "series.csv")), File(seriesDirectory, "SHA256SUMS").readText())
        assertEquals(
            sumLine(File(resultsDirectory, "results.csv")) + sumLine(File(resultsDirectory, "seed-stability.csv")),
            File(resultsDirectory, "SHA256SUMS").readText(),
        )
    }

    @Test
    fun `every method has a row for every shape, with real numbers`() {
        val methods = ForecastMethods.all()
        val evaluation = Step88.evaluate(SyntheticDemand.generate(), methods)

        assertEquals(8, evaluation.shapes.size)
        for (method in methods) {
            for (shape in evaluation.shapes) {
                val summary = evaluation.cell(method, shape).summary
                assertEquals("${method.id} on ${shape.code}", 100 * 3, summary.folds)
                assertTrue("${method.id} on ${shape.code}: MAE ${summary.mae}", summary.mae.isFinite() && summary.mae >= 0.0)
                assertTrue(summary.stockoutShare in 0.0..1.0)
                assertTrue(summary.overstockShare in 0.0..1.0)
            }
        }
    }

    @Test
    fun `the comparison table names every method the catalogue holds`() {
        val methods = ForecastMethods.all()
        val csv = Step88.resultsCsv(Step88.evaluate(SyntheticDemand.generate(), methods))
        methods.forEach {
            assertTrue("${it.id} is missing from the results", csv.contains("\n${Step88.csvField(it.id)},"))
        }
        // One header, plus one line per method per shape.
        assertEquals(1 + methods.size * 8, csv.trimEnd().lines().size)
    }

    @Test
    fun `every line of the results file parses into the same number of fields`() {
        // Method ids hold commas, so an unquoted field would silently shift every
        // column to its right. Parse the way a spreadsheet would and count.
        val csv = Step88.resultsCsv(Step88.evaluate(SyntheticDemand.generate(), ForecastMethods.all()))
        val counts =
            csv
                .trimEnd()
                .lines()
                .map { fieldsOf(it).size }
                .toSet()
        assertEquals(setOf(13), counts)
    }

    /** A minimal RFC 4180 line reader: commas inside quotes do not split, and "" is a quote. */
    private fun fieldsOf(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var at = 0
        while (at < line.length) {
            val c = line[at]
            when {
                quoted && c == '"' && line.getOrNull(at + 1) == '"' -> {
                    current.append('"')
                    at += 1
                }

                c == '"' -> {
                    quoted = !quoted
                }

                c == ',' && !quoted -> {
                    fields += current.toString()
                    current.clear()
                }

                else -> {
                    current.append(c)
                }
            }
            at += 1
        }
        fields += current.toString()
        return fields
    }

    private fun assertSaved(
        file: File,
        expected: String,
    ) {
        assertTrue(
            "${file.path} is missing. Regenerate it: see the comment at the top of Step88EvaluationTest.",
            file.isFile,
        )
        assertEquals(
            "${file.name} no longer matches what the code produces. A method, a default, the generator or a metric " +
                "changed. Regenerate (see Step88EvaluationTest) and review the diff in the numbers.",
            expected,
            file.readText(),
        )
    }

    private fun sumLine(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) } + "  " + file.name + "\n"
    }

    private fun findForecastingDirectory(): File {
        var directory: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "research/forecasting")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile
        }
        error("research/forecasting not found above the working directory")
    }
}
