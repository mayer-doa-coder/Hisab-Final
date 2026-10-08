package com.hisab.app.domain.forecast

import com.hisab.app.domain.Quantity

// From one product's demand and stock to what to do about it (Steps 89–90).
//
// This is the join between the pieces already built: the demand series
// (Step 84), a forecasting method (Steps 85–87, chosen by Step 88) and the
// reorder arithmetic (Step 89). It decides three things about a product —
// whether there is enough history to say anything, when stock runs out, and
// what to buy — and it declines to guess when the first answer is no.

/** When a product's stock is expected to run out, if it does. */
sealed interface StockoutOutlook {
    /** Nothing left on the shelf as the ledger counts it, so there is nothing to wait for. */
    data object AlreadyOut : StockoutOutlook

    /** Stock is used up during day [days] counting from tomorrow as day 1, at the forecast rate. */
    data class InDays(
        val days: Int,
    ) : StockoutOutlook

    /** Not expected to run out within [lookaheadDays], the furthest this looks. Says nothing beyond that. */
    data class NotSoon(
        val lookaheadDays: Int,
    ) : StockoutOutlook
}

sealed interface ProductAdvice {
    /**
     * Too little sales history to forecast from, so no forecast is made — the
     * facts that were counted are returned so the reason can be said plainly,
     * rather than a number invented. See [InventoryAdvisor.MIN_DAYS_OBSERVED].
     */
    data class NotEnoughData(
        val daysObserved: Int,
        val demandDays: Int,
    ) : ProductAdvice

    data class Ready(
        val reorder: ReorderRecommendation,
        val outlook: StockoutOutlook,
        /**
         * Stock will not last the lead time: it is below what is expected to be
         * sold before a new order could arrive. Narrower than "needs buying",
         * which also counts the safety margin — this is the urgent subset.
         */
        val runsOutBeforeRestock: Boolean,
        /** Which method produced the forecast, so an answer can always be traced to it. */
        val methodId: String,
    ) : ProductAdvice
}

/**
 * Forecasts a product and says what to do.
 *
 * [method] defaults to [ForecastMethods.appDefault], the one the Step 88 rule
 * picked. It is injectable so a test can use a method whose answer is known
 * exactly, and so a real-data comparison can swap it without touching callers.
 */
class InventoryAdvisor(
    private val method: ForecastMethod = ForecastMethods.appDefault(),
    val settings: ReorderSettings = ReorderSettings(),
) {
    /**
     * [series] is null for a product that has never been sold — which is not a
     * series of zeros and gets the same "not enough data" answer.
     */
    fun advise(
        series: DemandSeries?,
        currentStock: Quantity,
        unit: String,
    ): ProductAdvice {
        val daysObserved = series?.dayCount ?: 0
        val demandDays = series?.days?.count { it.quantitySold.scaledUnits > 0 } ?: 0
        if (series == null || daysObserved < MIN_DAYS_OBSERVED || demandDays < MIN_DEMAND_DAYS) {
            return ProductAdvice.NotEnoughData(daysObserved, demandDays)
        }

        val fitted = method.fit(series.history())
        // The method's own judgement is a second gate: a method that says it has
        // too little to go on (one sale for Croston, under a week for ADIDA) is
        // believed, however long the series looks.
        if (fitted.basis != ForecastBasis.ENOUGH_HISTORY) {
            return ProductAdvice.NotEnoughData(daysObserved, demandDays)
        }

        val horizon = maxOf(settings.leadTimeDays, STOCKOUT_LOOKAHEAD_DAYS)
        val perDay = fitted.forecast(horizon).perDay
        val expectedDemand = perDay.take(settings.leadTimeDays).fold(Quantity.ZERO) { sum, day -> sum + day }

        val reorder = recommendReorder(currentStock, expectedDemand, unit, settings)
        return ProductAdvice.Ready(
            reorder = reorder,
            outlook = outlookFor(reorder.countedStock, perDay),
            runsOutBeforeRestock = reorder.countedStock.scaledUnits < reorder.expectedDemand.scaledUnits,
            methodId = method.id,
        )
    }

    /**
     * Walks the forecast a day at a time, taking each day's demand off the
     * stock, and reports the first day the stock is gone.
     *
     * A day at a time rather than stock divided by an average rate, so a method
     * whose forecast is not flat (seasonal naive, seasonal EWMA) gets its busy
     * days counted when they fall.
     */
    private fun outlookFor(
        stock: Quantity,
        perDay: List<Quantity>,
    ): StockoutOutlook {
        if (stock.scaledUnits <= 0) return StockoutOutlook.AlreadyOut

        var remaining = stock.scaledUnits
        for ((index, demand) in perDay.take(STOCKOUT_LOOKAHEAD_DAYS).withIndex()) {
            remaining -= demand.scaledUnits
            if (remaining <= 0) return StockoutOutlook.InDays(index + 1)
        }
        return StockoutOutlook.NotSoon(STOCKOUT_LOOKAHEAD_DAYS)
    }

    companion object {
        /**
         * Two weeks. **Provisional** — a round number with no study behind it,
         * chosen so a product that has just started selling is not told what to
         * order on the strength of three days. `docs/RESEARCH_PLAN.md` fixes a
         * stricter 56 days for what is *reported in research*; this governs only
         * whether the app speaks at all. Step 95 tests it on products with almost
         * no history, and real shops should move it.
         */
        const val MIN_DAYS_OBSERVED = 14

        /** Three sale days: with fewer, "how fast it sells" rests on one or two coincidences. Provisional like the above. */
        const val MIN_DEMAND_DAYS = 3

        /** How far ahead "when will it run out" looks. A month is plenty for a corner shop; beyond it the answer is "not soon". */
        const val STOCKOUT_LOOKAHEAD_DAYS = 30
    }
}
