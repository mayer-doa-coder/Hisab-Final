package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selector picks per product from the product's own shape, and otherwise
 * behaves exactly as the method it picked — it must not add or remove anything
 * of its own to the forecast.
 */
class ProfileSelectMethodTest {
    private val selector = ProfileSelectMethod()

    private fun smooth(): DemandHistory = DemandHistory(List(40) { Quantity.fromDecimal(5.0) })

    /** 4 units every fifth day. */
    private fun intermittent(): DemandHistory =
        DemandHistory((0 until 60).map { if (it % 5 == 4) Quantity.fromDecimal(4.0) else Quantity.ZERO })

    private fun lumpy(): DemandHistory =
        DemandHistory(
            (0 until 60).map {
                when (it % 5) {
                    4 -> if (it % 10 == 4) Quantity.fromDecimal(1.0) else Quantity.fromDecimal(20.0)
                    else -> Quantity.ZERO
                }
            },
        )

    private fun erratic(): DemandHistory = DemandHistory((0 until 30).map { Quantity.fromDecimal(if (it % 2 == 0) 1.0 else 20.0) })

    private fun daily(
        method: ForecastMethod,
        history: DemandHistory,
    ): List<Quantity> = method.fit(history).forecast(5).perDay

    @Test
    fun `a product that sells most days gets exponential smoothing`() {
        assertEquals(ForecastFamily.EWMA, selector.chosenFor(smooth()).family)
        assertEquals(daily(EwmaMethod(), smooth()), daily(selector, smooth()))
    }

    @Test
    fun `a product with long quiet gaps gets the bias-corrected Croston`() {
        assertEquals(ForecastFamily.SBA, selector.chosenFor(intermittent()).family)
        assertEquals(daily(CrostonMethod.sba(), intermittent()), daily(selector, intermittent()))
    }

    @Test
    fun `lumpy demand goes to the intermittent method and erratic demand to the smooth one`() {
        assertEquals(DemandPattern.LUMPY, profileOf(lumpy()).pattern)
        assertEquals(ForecastFamily.SBA, selector.chosenFor(lumpy()).family)

        assertEquals(DemandPattern.ERRATIC, profileOf(erratic()).pattern)
        assertEquals(ForecastFamily.EWMA, selector.chosenFor(erratic()).family)
    }

    @Test
    fun `no history and no sales both fall to the smooth method, which says nothing was sold`() {
        assertEquals(ForecastFamily.EWMA, selector.chosenFor(DemandHistory.EMPTY).family)
        assertEquals(ForecastBasis.NO_HISTORY, selector.fit(DemandHistory.EMPTY).basis)
        assertEquals(Quantity.ZERO, selector.fit(DemandHistory(List(30) { Quantity.ZERO })).forecast(7).total)
    }

    @Test
    fun `the forecast carries the selector's own name, not the member it picked`() {
        assertEquals(selector.id, selector.fit(intermittent()).forecast(3).method)
        assertEquals(selector.id, selector.fit(smooth()).forecast(3).method)
    }

    @Test
    fun `the basis is the picked method's basis, so one sale is still called short history`() {
        val oneSale = DemandHistory.ofUnits(0, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0)
        assertEquals(ForecastBasis.SHORT_HISTORY, selector.fit(oneSale).basis)
    }

    @Test
    fun `its state is both members plus the counts the shape is worked out from`() {
        // EWMA 8, SBA 20, and 2 counters + 2 doubles = 24 for the profile.
        assertEquals(8 + 20 + 24, selector.stateBytes)
        assertTrue(selector.stateBytes < 100)
    }

    @Test
    fun `the choice follows the history as it changes`() {
        val soldEveryDay = List(30) { Quantity.fromDecimal(3.0) }
        val thenEveryFifthDay = (0 until 60).map { if (it % 5 == 4) Quantity.fromDecimal(3.0) else Quantity.ZERO }

        assertEquals(ForecastFamily.EWMA, selector.chosenFor(DemandHistory(soldEveryDay)).family)
        assertEquals(ForecastFamily.SBA, selector.chosenFor(DemandHistory(soldEveryDay + thenEveryFifthDay)).family)
    }
}
