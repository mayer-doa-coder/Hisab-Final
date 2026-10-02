package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Step 87: the Croston family, with the attention the step asks for on
 * intermittent demand — which is the whole reason these three methods exist.
 *
 * Two claims are tested here that nothing else in this package can make:
 *
 *  1. On a product that sells in bursts, Croston reads the underlying rate
 *     exactly, while naive, moving average and EWMA — every method that
 *     averages sale days and quiet days together — do not.
 *  2. TSB notices when a product has stopped selling. Croston, by construction,
 *     never does.
 */
class CrostonMethodsTest {
    /** 4 units every fifth day: a true rate of 0.8 a day, in bursts of 4. */
    private fun everyFifthDay(days: Int = 60): DemandHistory =
        DemandHistory((0 until days).map { if (it % 5 == 4) Quantity.fromDecimal(4.0) else Quantity.ZERO })

    private fun scaled(
        method: ForecastMethod,
        history: DemandHistory,
    ): Long =
        method
            .fit(history)
            .forecast(1)
            .perDay
            .first()
            .scaledUnits

    @Test
    fun `Croston works out the size and the interval separately`() {
        // Ten days, selling 4 on the third day and 9 on the eighth.
        //   size     starts at 4, then 4   + 0.1 x (9 - 4) = 4.5
        //   interval starts at 3 (the first sale came on day three),
        //            then    3   + 0.1 x (5 - 3) = 3.2
        //   rate = 4.5 / 3.2 = 1.40625 a day
        val history = DemandHistory.ofUnits(0, 0, 4, 0, 0, 0, 0, 9, 0, 0)
        assertEquals(1406L, scaled(CrostonMethod.croston(), history))
    }

    @Test
    fun `SBA is Croston scaled down by exactly its own bias correction`() {
        val history = DemandHistory.ofUnits(0, 0, 4, 0, 0, 0, 0, 9, 0, 0)

        // 1.40625 x (1 - 0.1 / 2) = 1.3359375
        assertEquals(1336L, scaled(CrostonMethod.sba(), history))
        assertTrue(scaled(CrostonMethod.sba(), history) < scaled(CrostonMethod.croston(), history))
    }

    @Test
    fun `Croston starts its interval from when the first sale actually arrived`() {
        // One sale of 4, on the third day of a five-day history. Starting the
        // interval at 1 instead would claim 4 a day for a product that has sold
        // four units in five days. 4 / 3 = 1.333…
        val oneLateSale = DemandHistory.ofUnits(0, 0, 4, 0, 0)
        assertEquals(1333L, scaled(CrostonMethod.croston(), oneLateSale))
    }

    @Test
    fun `one sale is not a rhythm, and Croston says so`() {
        val oneSale = CrostonMethod.croston().fit(DemandHistory.ofUnits(0, 0, 4, 0, 0))
        // Long enough in days, but there is no gap between sales to have
        // measured — so the answer is reported as resting on short history.
        assertEquals(ForecastBasis.SHORT_HISTORY, oneSale.basis)

        val noSale = CrostonMethod.croston().fit(DemandHistory.ofUnits(0, 0, 0, 0, 0))
        assertEquals(ForecastBasis.SHORT_HISTORY, noSale.basis)
        assertEquals(Quantity.ZERO, noSale.forecast(7).total)
    }

    @Test
    fun `on intermittent demand Croston reads the true rate exactly`() {
        // 4 units every fifth day. The size estimate never moves off 4 and the
        // interval never moves off 5, so the rate is 4 / 5 = 0.8 — the truth.
        assertEquals(800L, scaled(CrostonMethod.croston(), everyFifthDay()))
    }

    @Test
    fun `on intermittent demand Croston beats every method that averages the quiet days in`() {
        val history = everyFifthDay()
        val truth = 800L

        fun errorOf(method: ForecastMethod): Long = abs(scaled(method, history) - truth)

        val croston = errorOf(CrostonMethod.croston())
        assertEquals(0L, croston)

        // Each of these is off by more than 30% of the true rate. They are not
        // badly implemented — they are being asked a question they cannot
        // answer, because they cannot tell a quiet day from a small day.
        listOf<ForecastMethod>(
            NaiveMethod(),
            MovingAverageMethod(ForecastMethods.SHORT_WINDOW_DAYS),
            EwmaMethod(),
            SeasonalNaiveMethod(),
        ).forEach { method ->
            assertTrue(
                "${method.id} was off by ${errorOf(method)}, which Croston should beat",
                errorOf(method) > truth * 3 / 10,
            )
        }
    }

    @Test
    fun `TSB notices a product that has stopped selling, and Croston never does`() {
        // Thirty days at 5 a day, then two months of nothing. The product is
        // gone from the shelf in every way except the product row.
        val abandoned =
            DemandHistory(List(30) { Quantity.fromDecimal(5.0) } + List(60) { Quantity.ZERO })

        // Croston updates only on sale days, so it still reports the rhythm it
        // last saw — 5 a day, two months after the last sale.
        assertEquals(5000L, scaled(CrostonMethod.croston(), abandoned))

        // TSB decays its demand probability on every quiet day: 0.95^60 is about
        // 0.046, so 5 a day becomes roughly a quarter of a unit.
        val tsb = scaled(TsbMethod(), abandoned)
        assertTrue("TSB still predicted $tsb", tsb in 100L..500L)
    }

    @Test
    fun `TSB and Croston agree when demand arrives every single day`() {
        // With no quiet days there is nothing for the probability to decay and
        // nothing for the interval to grow: both read 5 exactly.
        val steady = DemandHistory(List(40) { Quantity.fromDecimal(5.0) })
        assertEquals(5000L, scaled(CrostonMethod.croston(), steady))
        assertEquals(5000L, scaled(TsbMethod(), steady))
    }

    @Test
    fun `the Croston family keeps a handful of bytes and no history`() {
        assertEquals(BYTES_PER_DOUBLE * 2 + BYTES_PER_COUNTER, CrostonMethod.croston().stateBytes)
        assertEquals(CrostonMethod.croston().stateBytes, CrostonMethod.sba().stateBytes)
        // TSB needs no interval counter at all — it updates every day anyway.
        assertEquals(BYTES_PER_DOUBLE * 2, TsbMethod().stateBytes)
        assertTrue(TsbMethod().stateBytes < MovingAverageMethod(ForecastMethods.SHORT_WINDOW_DAYS).stateBytes)
    }

    @Test
    fun `every member of the family refuses a smoothing constant outside its range`() {
        assertThrows(IllegalArgumentException::class.java) { CrostonMethod.croston(alpha = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { CrostonMethod.sba(alpha = 1.1) }
        assertThrows(IllegalArgumentException::class.java) { TsbMethod(alpha = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { TsbMethod(beta = 2.0) }
    }

    @Test
    fun `Croston and SBA are named apart, so a comparison table cannot confuse them`() {
        assertEquals(ForecastFamily.CROSTON, CrostonMethod.croston().family)
        assertEquals(ForecastFamily.SBA, CrostonMethod.sba().family)
        assertTrue(CrostonMethod.croston().id != CrostonMethod.sba().id)
    }
}
