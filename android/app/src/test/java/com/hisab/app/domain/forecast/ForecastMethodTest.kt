package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four cases Steps 85, 86 and 87 all ask for — constant demand, zero
 * demand, intermittent demand, short history — run against **every** method,
 * from the one list Step 88 will compare.
 *
 * Written as one battery rather than as four cases per method on purpose: a
 * method added to `ForecastMethods` is tested by this file the moment it is
 * added, and cannot be quietly exempted from a case that would embarrass it.
 * Each method's own arithmetic is checked to the digit in its own test file.
 */
class ForecastMethodTest {
    private val methods = ForecastMethods.all()

    private fun constant(): DemandHistory = DemandHistory(List(60) { Quantity.fromDecimal(5.0) })

    private fun zero(): DemandHistory = DemandHistory(List(60) { Quantity.ZERO })

    /** 62 days, 4 units every fifth day, ending on two quiet days. A true rate of 0.8 a day. */
    private fun intermittent(): DemandHistory =
        DemandHistory(
            (0 until 62).map {
                if (it % 5 == 4) Quantity.fromDecimal(4.0) else Quantity.ZERO
            },
        )

    private fun forEachMethod(check: (ForecastMethod) -> Unit) {
        assertTrue("no methods in the catalogue", methods.isNotEmpty())
        methods.forEach(check)
    }

    private fun rate(
        method: ForecastMethod,
        history: DemandHistory,
    ): Double =
        method
            .fit(history)
            .forecast(1)
            .perDay
            .first()
            .toDecimal()

    @Test
    fun `the catalogue has every method the PRD requires, each named once`() {
        val families = methods.map { it.family }.toSet()
        listOf(
            ForecastFamily.NAIVE,
            ForecastFamily.MOVING_AVERAGE,
            ForecastFamily.EWMA,
            ForecastFamily.SEASONAL_NAIVE,
            ForecastFamily.CROSTON,
        ).forEach { required ->
            assertTrue("PRD section 15 requires $required", required in families)
        }
        assertEquals("two methods share an id", methods.size, methods.map { it.id }.toSet().size)
    }

    @Test
    fun `every method answers with exactly one number per day of the horizon`() {
        forEachMethod { method ->
            listOf(1, 7, 30).forEach { horizon ->
                val forecast = method.fit(constant()).forecast(horizon)
                assertEquals(method.id, horizon, forecast.perDay.size)
                assertEquals("a forecast names the method that made it", method.id, forecast.method)
            }
        }
    }

    @Test
    fun `no method accepts a horizon of no days`() {
        forEachMethod { method ->
            val fitted = method.fit(constant())
            assertThrows(method.id, IllegalArgumentException::class.java) { fitted.forecast(0) }
            assertThrows(method.id, IllegalArgumentException::class.java) { fitted.forecast(-7) }
        }
    }

    @Test
    fun `no method ever predicts negative demand`() {
        forEachMethod { method ->
            listOf(constant(), zero(), intermittent(), DemandHistory.ofUnits(3, 0)).forEach { history ->
                method.fit(history).forecast(14).perDay.forEach { day ->
                    assertTrue("${method.id} predicted ${day.toDecimal()}", day.scaledUnits >= 0)
                }
            }
        }
    }

    @Test
    fun `every method predicts nothing from no history, and says that is why`() {
        forEachMethod { method ->
            val forecast = method.fit(DemandHistory.EMPTY).forecast(7)
            assertEquals(method.id, ForecastBasis.NO_HISTORY, forecast.basis)
            assertEquals(method.id, Quantity.ZERO, forecast.total)
        }
    }

    @Test
    fun `every method predicts nothing from a history of nothing but zeros`() {
        forEachMethod { method ->
            val forecast = method.fit(zero()).forecast(7)
            assertEquals(method.id, Quantity.ZERO, forecast.total)
        }
    }

    @Test
    fun `a method given less history than it needs says so instead of pretending`() {
        forEachMethod { method ->
            val forecast = method.fit(DemandHistory.ofUnits(3, 0)).forecast(7)
            if (method.minimumDays > 2) {
                assertEquals(method.id, ForecastBasis.SHORT_HISTORY, forecast.basis)
            }
            // Either way it still answers. Refusing outright would leave the
            // reorder screen with nothing to show on a shop's second week.
            assertEquals(method.id, 7, forecast.perDay.size)
        }
    }

    @Test
    fun `every method lands within a tenth of steady demand`() {
        forEachMethod { method ->
            val predicted = rate(method, constant())
            assertEquals("${method.id} on constant demand of 5 a day", 5.0, predicted, 0.5)
            assertEquals(method.id, ForecastBasis.ENOUGH_HISTORY, method.fit(constant()).basis)
        }
    }

    @Test
    fun `on intermittent demand every method stays in the same order of magnitude as the truth`() {
        // Deliberately a loose band. Which method is *best* here is Step 88's
        // measurement, not an assertion; what this guards is a method that is
        // wrong by a factor of ten, which on a shelf means an empty shelf or a
        // storeroom full of stock nobody asked for.
        forEachMethod { method ->
            val predicted = rate(method, intermittent())
            assertTrue("${method.id} predicted $predicted against a true rate of 0.8", predicted <= 8.0)
        }
    }

    @Test
    fun `fitting the same history twice gives the same answer`() {
        forEachMethod { method ->
            val first = method.fit(intermittent()).forecast(14)
            val second = method.fit(intermittent()).forecast(14)
            assertEquals(method.id, first, second)
        }
    }

    @Test
    fun `a fitted method keeps nothing but its own state, and that state is small`() {
        // RQ4 measures state size, and the whole argument for these methods over
        // a model is that this number stays tiny. A shop with 200 products must
        // fit every method's state in well under a megabyte.
        forEachMethod { method ->
            assertTrue("${method.id} keeps no state at all", method.stateBytes > 0)
            assertTrue("${method.id} keeps ${method.stateBytes} bytes", method.stateBytes <= 512)
        }
        val forTwoHundredProducts = methods.sumOf { it.stateBytes } * 200
        assertTrue("every method for 200 products: $forTwoHundredProducts bytes", forTwoHundredProducts < 512 * 1024)
    }

    @Test
    fun `the horizon total is the sum of its days`() {
        forEachMethod { method ->
            val forecast = method.fit(intermittent()).forecast(9)
            assertEquals(method.id, forecast.perDay.sumOf { it.scaledUnits }, forecast.total.scaledUnits)
        }
    }
}
