package com.hisab.app.data.suggest

import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.suggest.BasketLine
import com.hisab.app.domain.suggest.Cooccurrence
import com.hisab.app.domain.suggest.computeCooccurrence
import java.time.Clock
import java.time.Duration

/**
 * Turns this shop's stored sales into the "bought together" counts (Steps 91-93).
 *
 * All the rules are in `domain/suggest/`; this reads rows and hands them over, the
 * way `DemandRepository` does for forecasting. Nothing here writes and nothing here
 * needs a network, so suggestions work in airplane mode like the rest of the app.
 *
 * Only the last [windowDays] days are read. The PRD's own example is "in the last
 * 90 days", and the reason is practical as well: what is bought together changes
 * (a season, a new product), and an unbounded read grows with the age of the shop
 * on a phone that has to answer while a customer waits (D028). 90 days is the
 * PRD's example figure, not a tuned one.
 */
class SuggestionRepository(
    database: HisabDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val shopId: String = LOCAL_SHOP_ID,
) {
    private val dao = database.suggestionDao()

    suspend fun load(windowDays: Int = DEFAULT_WINDOW_DAYS): Cooccurrence {
        require(windowDays >= 1) { "The window is at least one day, got $windowDays." }
        val from = clock.instant().minus(Duration.ofDays(windowDays.toLong())).toEpochMilli()
        return computeCooccurrence(
            dao.basketRows(shopId, from).map { BasketLine(EntityId(it.saleId), EntityId(it.productId)) },
        )
    }

    companion object {
        const val DEFAULT_WINDOW_DAYS = 90
    }
}
