package com.hisab.app.domain.forecast.research

import com.hisab.app.domain.forecast.ForecastMethods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's default method is the answer to a rule written down before the
 * results existed (`research/forecasting/PROTOCOL.md`). This recomputes the rule
 * from the code and fails if the default in the app has drifted from it.
 *
 * If this fails after a method, a setting or the generator changed, the
 * evidence moved: re-run Step 88 (see `Step88EvaluationTest`), read the new
 * default, and change `ForecastMethods.appDefault()` on purpose.
 */
class AppDefaultTest {
    @Test
    fun `the app default is what the protocol's rule picks from the Step 88 results`() {
        val evaluation = Step88.evaluate(SyntheticDemand.generate(), ForecastMethods.all())
        val picked = Step88.provisionalDefault(Step88.standings(evaluation))

        assertEquals(picked.id, ForecastMethods.appDefault().id)
    }

    @Test
    fun `the app default is one of the catalogue's methods and is small`() {
        val default = ForecastMethods.appDefault()
        assertTrue(default.id in ForecastMethods.all().map { it.id })
        assertTrue("state ${default.stateBytes} bytes", default.stateBytes <= 64)
    }
}
