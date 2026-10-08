package com.hisab.app.domain.forecast.research

import com.hisab.app.domain.forecast.DemandHistory
import com.hisab.app.domain.forecast.ForecastMethod
import java.util.Locale
import kotlin.math.abs

/**
 * Step 88: every method, every series, every fold, then the tables.
 *
 * Everything that decides a number in the report is here and follows
 * `research/forecasting/PROTOCOL.md`. Nothing is tuned: methods arrive already
 * built, at the settings `ForecastMethods` gave them.
 */
object Step88 {
    /** A method's pooled score on one shape. */
    data class Cell(
        val shape: SyntheticShape,
        val seriesCount: Int,
        val summary: ShapeSummary,
    )

    /** One row of the ranking table. */
    data class Standing(
        val method: ForecastMethod,
        val meanMaeRank: Double,
        val meanMaseRank: Double,
        val paretoEfficient: Boolean,
    )

    class Evaluation(
        val methods: List<ForecastMethod>,
        val series: List<SyntheticSeries>,
        /** method id -> shape -> pooled score */
        val cells: Map<String, Map<SyntheticShape, Cell>>,
    ) {
        val shapes: List<SyntheticShape> = SyntheticShape.entries.filter { shape -> series.any { it.shape == shape } }

        fun cell(
            method: ForecastMethod,
            shape: SyntheticShape,
        ): Cell = cells.getValue(method.id).getValue(shape)
    }

    fun evaluate(
        series: List<SyntheticSeries>,
        methods: List<ForecastMethod>,
    ): Evaluation {
        val byShape = series.groupBy { it.shape }
        val cells =
            methods.associate { method ->
                method.id to
                    byShape
                        .mapValues { (shape, ofShape) ->
                            Cell(
                                shape = shape,
                                seriesCount = ofShape.size,
                                summary = WalkForward.summarise(ofShape.flatMap { WalkForward.scoreSeries(method, it) }),
                            )
                        }
            }
        return Evaluation(methods, series, cells)
    }

    // ---- ranking and the trade-off view ------------------------------------------------------

    /**
     * Rank within one shape, 1 = lowest [value]. Methods that tie — to within a
     * billionth, which is arithmetic noise rather than a difference — share the
     * average of the ranks they span, so listing order never decides a result.
     */
    internal fun ranks(values: Map<String, Double>): Map<String, Double> {
        val ordered = values.entries.sortedBy { it.value }
        val ranks = mutableMapOf<String, Double>()
        var start = 0
        while (start < ordered.size) {
            var end = start
            while (end + 1 < ordered.size && abs(ordered[end + 1].value - ordered[start].value) < TIE_TOLERANCE) end += 1
            val shared = (start + 1 + end + 1) / 2.0
            for (at in start..end) ranks[ordered[at].key] = shared
            start = end + 1
        }
        return ranks
    }

    fun standings(evaluation: Evaluation): List<Standing> {
        val maeRanks = meanRanks(evaluation) { it.mae }
        // A shape where MASE is undefined for every fold of a method has nothing to rank by.
        val maseRanks = meanRanks(evaluation) { it.mase ?: Double.MAX_VALUE }

        val bytes = evaluation.methods.associate { it.id to it.stateBytes }
        return evaluation.methods.map { method ->
            val rank = maeRanks.getValue(method.id)
            val efficient =
                evaluation.methods.none { other ->
                    other.id != method.id &&
                        maeRanks.getValue(other.id) <= rank &&
                        bytes.getValue(other.id) <= bytes.getValue(method.id) &&
                        (maeRanks.getValue(other.id) < rank || bytes.getValue(other.id) < bytes.getValue(method.id))
                }
            Standing(method, rank, maseRanks.getValue(method.id), efficient)
        }
    }

    private fun meanRanks(
        evaluation: Evaluation,
        metric: (ShapeSummary) -> Double,
    ): Map<String, Double> {
        val perShape =
            evaluation.shapes.map { shape ->
                ranks(evaluation.methods.associate { it.id to metric(evaluation.cell(it, shape).summary) })
            }
        return evaluation.methods.associate { method -> method.id to perShape.map { it.getValue(method.id) }.average() }
    }

    /**
     * The rule from the protocol: the lowest mean MAE rank wins, a method within
     * [TIE_RANK_POINTS] of the best counts as tied with it, and a tie goes to
     * the smaller state size, then to the better rank, then to the name.
     */
    fun provisionalDefault(standings: List<Standing>): ForecastMethod {
        val best = standings.minOf { it.meanMaeRank }
        return standings
            .filter { it.meanMaeRank <= best + TIE_RANK_POINTS }
            .sortedWith(
                compareBy<Standing> { it.method.stateBytes }
                    .thenBy { it.meanMaeRank }
                    .thenBy { it.method.id },
            ).first()
            .method
    }

    const val TIE_RANK_POINTS = 0.25
    private const val TIE_TOLERANCE = 1e-9

    // ---- stability across seeds --------------------------------------------------------------

    /**
     * How much of the ranking is the particular series that one seed happened to
     * draw. The whole comparison is repeated on [seeds] and the spread of each
     * method's mean MAE rank is reported, with how often the default rule picks
     * it.
     *
     * Supplementary and never used to choose: the protocol's rule runs on the
     * main seed. This only says how far that single run can be trusted, and it
     * stays on development data — it is not the final test.
     */
    class Stability(
        val seedCount: Int,
        val meanOfMeanRank: Map<String, Double>,
        val rankSpread: Map<String, Double>,
        val timesChosen: Map<String, Int>,
    )

    fun stability(
        methods: List<ForecastMethod>,
        seeds: List<Long>,
    ): Stability {
        val ranksPerSeed = mutableListOf<Map<String, Double>>()
        val chosen = methods.associate { it.id to 0 }.toMutableMap()
        for (seed in seeds) {
            val standings = standings(evaluate(SyntheticDemand.generate(seed), methods))
            ranksPerSeed += standings.associate { it.method.id to it.meanMaeRank }
            val pick = provisionalDefault(standings).id
            chosen[pick] = chosen.getValue(pick) + 1
        }
        val mean = methods.associate { m -> m.id to ranksPerSeed.map { it.getValue(m.id) }.average() }
        val spread =
            methods.associate { m ->
                val values = ranksPerSeed.map { it.getValue(m.id) }
                val average = mean.getValue(m.id)
                m.id to kotlin.math.sqrt(values.sumOf { (it - average) * (it - average) } / (values.size - 1))
            }
        return Stability(seeds.size, mean, spread, chosen)
    }

    /** The main seed and nine more, fixed here so the check can be repeated exactly. */
    val STABILITY_SEEDS: List<Long> = (0L..9L).map { SyntheticDemand.DEVELOPMENT_SEED + it * 7919L }

    fun stabilityCsv(stability: Stability): String =
        buildString {
            append("method,mean_of_mean_mae_rank,sd_of_mean_mae_rank,times_chosen_as_default_of_${stability.seedCount}")
            append(NEWLINE)
            for ((id, mean) in stability.meanOfMeanRank) {
                append(csvField(id)).append(',').append(mean.fixed(3)).append(',')
                append(stability.rankSpread.getValue(id).fixed(3)).append(',')
                append(stability.timesChosen.getValue(id)).append(NEWLINE)
            }
        }

    private const val NEWLINE = '\n'

    // ---- latency -----------------------------------------------------------------------------

    /**
     * Microseconds for one `fit` plus a 7-day `forecast`, per method: the median
     * of [PASSES] timed passes over every series' full history, after
     * [WARM_UP_PASSES] untimed ones so the JIT has run.
     *
     * Indicative only — a laptop's JVM says little about a cheap phone, which is
     * why the same measurement is made on the reference device.
     */
    fun latencyMicros(
        methods: List<ForecastMethod>,
        series: List<SyntheticSeries>,
    ): Map<String, Double> {
        val histories: List<DemandHistory> = series.map { it.history() }
        return methods.associate { method ->
            repeat(WARM_UP_PASSES) { runPass(method, histories) }
            val perCall =
                List(PASSES) {
                    val started = System.nanoTime()
                    runPass(method, histories)
                    (System.nanoTime() - started) / 1000.0 / histories.size
                }
            method.id to perCall.sorted()[PASSES / 2]
        }
    }

    private fun runPass(
        method: ForecastMethod,
        histories: List<DemandHistory>,
    ) {
        var sink = 0L
        for (history in histories) {
            sink +=
                method
                    .fit(history)
                    .forecast(HORIZON_FOR_LATENCY)
                    .total.scaledUnits
        }
        // Read the result so the work cannot be optimised away.
        check(sink >= 0)
    }

    private const val WARM_UP_PASSES = 3
    private const val PASSES = 9
    private const val HORIZON_FOR_LATENCY = 7

    // ---- files -------------------------------------------------------------------------------

    /** The saved series: one line per series, `id,shape,` then one whole-unit value per day. */
    fun seriesCsv(series: List<SyntheticSeries>): String =
        buildString {
            append("id,shape,").append((1..SyntheticDemand.DAYS).joinToString(",") { "d$it" }).append('\n')
            series.forEach {
                append(it.id)
                    .append(',')
                    .append(it.shape.code)
                    .append(',')
                    .append(it.units.joinToString(","))
                    .append('\n')
            }
        }

    /** The deterministic numbers, one line per method and shape. Compared byte for byte by the test. */
    fun resultsCsv(evaluation: Evaluation): String =
        buildString {
            append(
                "method,shape,series,folds,mae,mase,mase_undefined_folds,bias_per_day," +
                    "stockout_share,shortfall_of_demand,overstock_share,excess_of_demand,state_bytes\n",
            )
            for (method in evaluation.methods) {
                for (shape in evaluation.shapes) {
                    val cell = evaluation.cell(method, shape)
                    val s = cell.summary
                    append(
                        listOf(
                            csvField(method.id),
                            shape.code,
                            cell.seriesCount.toString(),
                            s.folds.toString(),
                            s.mae.fixed(),
                            s.mase.fixed(),
                            s.maseUndefinedFolds.toString(),
                            s.biasPerDay.fixed(),
                            s.stockoutShare.fixed(),
                            s.shortfallOfDemand.fixed(),
                            s.overstockShare.fixed(),
                            s.excessOfDemand.fixed(),
                            method.stateBytes.toString(),
                        ).joinToString(","),
                    ).append('\n')
                }
            }
        }

    fun latencyCsv(latency: Map<String, Double>): String =
        buildString {
            append("method,median_microseconds_per_fit_and_forecast\n")
            latency.forEach { (id, micros) -> append(csvField(id)).append(',').append(micros.fixed(2)).append('\n') }
        }

    /** RFC 4180: a field holding a comma or a quote is wrapped in quotes. Method ids hold commas, e.g. `tsb(a=0.1,b=0.05)`. */
    internal fun csvField(value: String): String {
        if (value.none { it == ',' || it == '"' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun Double?.fixed(digits: Int = 4): String = if (this == null) "" else String.format(Locale.ROOT, "%.${digits}f", this)

    // ---- the report --------------------------------------------------------------------------

    fun reportMarkdown(
        evaluation: Evaluation,
        latency: Map<String, Double>,
        stability: Stability,
    ): String {
        val standings = standings(evaluation)
        val chosen = provisionalDefault(standings)
        val sb = StringBuilder()

        sb.appendLine("# Step 88 — walk-forward comparison, development set (synthetic, v1)")
        sb.appendLine()
        sb.appendLine(
            "Generated by `Step88EvaluationTest` from `research/forecasting/PROTOCOL.md`. Do not edit by " +
                "hand; regenerate (see the end of this file).",
        )
        sb.appendLine()
        sb.appendLine(
            "**Read this first.** Every series here is `synthetic` and was designed by the developer who " +
                "wrote the methods. This table shows whether each implementation behaves as the literature says " +
                "it should, and which candidates deserve the real-data comparison. It does **not** show which " +
                "method is best for real shops. Some shapes match a method's own model by construction, so that " +
                "method doing well on them is expected, not a finding.",
        )
        sb.appendLine()
        val perShape = evaluation.series.count { it.shape == evaluation.shapes.first() }
        val foldCount = WalkForward.FOLDS.size
        val forecastDays = WalkForward.FOLDS.sumOf { it.horizon }
        sb.appendLine(
            "- Series: ${evaluation.series.size} (${evaluation.shapes.size} shapes × $perShape), " +
                "${SyntheticDemand.DAYS} days each, seed `${SyntheticDemand.DEVELOPMENT_SEED}`.",
        )
        sb.appendLine(
            "- Folds: fit on days 1–30 → predict 31–37; 1–37 → 38–44; 1–44 → 45–51. " +
                "$foldCount folds per series, $forecastDays forecast days per series.",
        )
        sb.appendLine("- No method setting was tuned.")
        sb.appendLine()

        sb.appendLine("## Trade-off view: accuracy against state size")
        sb.appendLine()
        sb.appendLine(
            "Mean rank is over the ${evaluation.shapes.size} shapes, each counting equally (1 = best). Pareto = no other method is " +
                "at least as accurate (mean MAE rank) **and** at least as small, and strictly better on one. " +
                "State size is the declared `stateBytes` (a model of footprint, not measured heap). Latency is on " +
                "the development laptop's JVM, indicative only; the phone's is in `latency-phone.md`.",
        )
        sb.appendLine()
        sb.appendLine("| Method | State (bytes) | Mean MAE rank | Mean MASE rank | Pareto | JVM latency (µs per fit + forecast) |")
        sb.appendLine("| --- | ---: | ---: | ---: | :---: | ---: |")
        for (standing in standings.sortedBy { it.meanMaeRank }) {
            sb.appendLine(
                "| `${standing.method.id}` | ${standing.method.stateBytes} | ${standing.meanMaeRank.fixed(2)} | " +
                    "${standing.meanMaseRank.fixed(2)} | ${if (standing.paretoEfficient) "yes" else ""} | " +
                    "${latency[standing.method.id].fixed(2)} |",
            )
        }
        sb.appendLine()
        sb.appendLine(
            "**Provisional app default, by the rule in the protocol** (lowest mean MAE rank; within ${TIE_RANK_POINTS.fixed(2)} rank " +
                "points counts as tied and goes to the smaller state): `${chosen.id}`. Provisional until the real-data " +
                "comparison.",
        )
        sb.appendLine()

        sb.appendLine("## How far the ranking can be trusted")
        sb.appendLine()
        sb.appendLine(
            "The whole comparison was repeated on ${stability.seedCount} different seeds (the main one and nine more). " +
                "SD is the spread of a method's mean MAE rank across them; *chosen* counts how many of the " +
                "${stability.seedCount} the default rule picked it. A gap in mean rank smaller than the SD is not a difference " +
                "this data can show.",
        )
        sb.appendLine()
        sb.appendLine("| Method | Mean of mean MAE rank | SD | Chosen as default |")
        sb.appendLine("| --- | ---: | ---: | ---: |")
        for ((id, mean) in stability.meanOfMeanRank.entries.sortedBy { it.value }) {
            val spread = stability.rankSpread.getValue(id).fixed(2)
            sb.appendLine("| `$id` | ${mean.fixed(2)} | $spread | ${stability.timesChosen.getValue(id)} |")
        }
        sb.appendLine()

        for (shape in evaluation.shapes) {
            sb.appendLine("## ${shape.code}")
            sb.appendLine()
            sb.appendLine(
                "| Method | MAE | MASE | MASE undefined folds | Bias / day | Stockout: folds short | Stockout: " +
                    "units short | Overstock: folds over | Overstock: units left |",
            )
            sb.appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
            val lowestMae = evaluation.methods.minOf { evaluation.cell(it, shape).summary.mae }
            for (method in evaluation.methods) {
                val s = evaluation.cell(method, shape).summary
                val mae = if (abs(s.mae - lowestMae) < TIE_TOLERANCE) "**${s.mae.fixed(3)}**" else s.mae.fixed(3)
                sb.appendLine(
                    "| `${method.id}` | $mae | ${s.mase.fixed(3)} | ${s.maseUndefinedFolds} | ${s.biasPerDay.fixed(3)} | " +
                        "${percent(s.stockoutShare)} | ${percent(s.shortfallOfDemand)} | " +
                        "${percent(s.overstockShare)} | ${percent(s.excessOfDemand)} |",
                )
            }
            sb.appendLine()
        }

        sb.appendLine("## Column meanings")
        sb.appendLine()
        sb.appendLine(
            "MAE is in units per day (bold = lowest in the shape). MASE is MAE divided by the training " +
                "window's one-step naive error; below 1 beats naive. *Folds short* is the share of folds where " +
                "real demand over the 7 days exceeded the forecast total; *units short* is the shortfall as a " +
                "share of real demand. *Folds over* and *units left* are the same for forecasting more than was " +
                "sold. Bias is mean forecast minus actual per day (positive = over-forecast); it is supplementary " +
                "and used for no ranking.",
        )
        sb.appendLine()
        sb.appendLine("## Regenerating")
        sb.appendLine()
        sb.appendLine(
            "From `android/`, with `HISAB_REGENERATE_RESEARCH=1` set: `./gradlew cleanTestDebugUnitTest " +
                "testDebugUnitTest --tests 'com.hisab.app.domain.forecast.research.Step88EvaluationTest'`. " +
                "Without the variable the same test fails if any saved file differs from what the code now " +
                "produces.",
        )
        return sb.toString()
    }

    private fun percent(value: Double?): String = if (value == null) "n/a" else String.format(Locale.ROOT, "%.1f%%", value * 100)
}
