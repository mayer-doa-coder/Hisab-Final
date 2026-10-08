package com.hisab.app.domain.forecast

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.domain.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 88's latency, measured on the phone rather than a laptop: the time to
 * `fit` a 56-day history and `forecast` seven days, for every method, on the
 * hardware a shopkeeper owns.
 *
 * The numbers are printed as `STEP88LATENCY|method|median µs` lines and copied
 * into `research/forecasting/results/step88-synthetic-v1/latency-phone.md`. The
 * history here is a fixed pseudo-random mix of steady and intermittent series,
 * not the research series, because the research generator lives in the unit-test
 * source set; latency depends on the amount of work and not on which shape of
 * demand produced it, so the figures describe the same cost.
 *
 * The assertion is a ceiling, not a target. It exists so the test fails if a
 * method becomes unreasonably slow, not to flatter the current state.
 *
 * Names are camelCase: a device test with spaces in its name compiles but fails
 * to dex at minSdk 26.
 */
@RunWith(AndroidJUnit4::class)
class ForecastLatencyTest {
    private fun histories(): List<DemandHistory> {
        var state = 0x2F6E2B1u

        fun next(bound: Int): Int {
            state = state xor (state shl 13)
            state = state xor (state shr 17)
            state = state xor (state shl 5)
            return (state % bound.toUInt()).toInt()
        }
        return List(600) { number ->
            DemandHistory(
                List(56) {
                    val units =
                        if (number % 3 == 0) {
                            if (next(100) < 25) 1 + next(4) else 0
                        } else {
                            2 + next(8)
                        }
                    Quantity(units * 1000L)
                },
            )
        }
    }

    private fun microsecondsPerCall(
        method: ForecastMethod,
        histories: List<DemandHistory>,
    ): Double {
        fun pass(): Long {
            var sink = 0L
            for (history in histories) {
                sink +=
                    method
                        .fit(history)
                        .forecast(7)
                        .total.scaledUnits
            }
            return sink
        }
        repeat(3) { pass() }
        val perCall =
            List(9) {
                val started = System.nanoTime()
                val sink = pass()
                val elapsed = System.nanoTime() - started
                // Read the result so the work cannot be optimised away.
                assertTrue(sink >= 0)
                elapsed / 1000.0 / histories.size
            }
        return perCall.sorted()[perCall.size / 2]
    }

    @Test
    fun everyMethodFitsAndForecastsQuicklyOnThePhone() {
        val histories = histories()
        val methods = ForecastMethods.all()
        assertEquals(13, methods.size)

        // Warm every method once, untimed, before timing any. The code they share
        // (the history, the quantity, the forecast) is compiled by the device's JIT
        // while the first method runs, and a first-run measurement then charges that
        // cost to whichever method happened to be first: the first run of this test
        // showed a 7-day average five times slower than a 28-day one.
        methods.forEach { microsecondsPerCall(it, histories) }

        for (method in methods) {
            val micros = microsecondsPerCall(method, histories)
            println("STEP88LATENCY|${method.id}|%.2f|${method.stateBytes}".format(micros))
            // A ceiling of 5 ms per product, thousands of times what any of these needs.
            assertTrue("${method.id} took $micros µs", micros < 5_000.0)
        }
    }

    @Test
    fun aWholeShopOfTwoHundredProductsIsForecastQuicklyWithTheDefaultMethod() {
        val histories = histories().take(200)
        val default = ForecastMethods.appDefault()

        // Warm up, then time one pass for the whole shop.
        repeat(3) { histories.forEach { default.fit(it).forecast(7) } }
        val started = System.nanoTime()
        histories.forEach { default.fit(it).forecast(7) }
        val millis = (System.nanoTime() - started) / 1_000_000.0

        println("STEP88SHOP|${default.id}|200 products|%.2f ms".format(millis))
        assertTrue("a 200-product shop took $millis ms", millis < 500.0)
    }
}
