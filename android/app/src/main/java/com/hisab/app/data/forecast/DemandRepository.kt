package com.hisab.app.data.forecast

import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.stock.toDomain
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.forecast.DemandSeries
import com.hisab.app.domain.forecast.SoldQuantity
import com.hisab.app.domain.forecast.dailyDemand
import com.hisab.app.domain.forecast.dailyDemandByProduct
import com.hisab.app.domain.forecast.markStockOutDays
import com.hisab.app.domain.forecast.observedWindow
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns this shop's stored sales into daily demand series (Step 84).
 *
 * All the rules are in `domain/forecast/DemandSeries.kt`; this reads rows and
 * hands them over, exactly the way `SaleRepository` keeps the sale rules out of
 * the database layer. That split is why the pipeline's behaviour — quiet days
 * as zeros, reversals netted onto the original day, a discontinued product's
 * series stopping — is tested on a laptop in milliseconds, while this class only
 * has to be shown once, against a real database, to produce the right rows.
 *
 * Nothing here writes. Nothing here needs a network. A forecast on this phone
 * is built from this phone's own history, which is what makes it work in
 * airplane mode like everything else.
 *
 * [zone] decides which calendar day a sale belongs to, and is passed in rather
 * than read at the point of use so a test can put a sale at 23:40 and say which
 * day it means.
 */
class DemandRepository(
    private val database: HisabDatabase,
    private val shopId: String = LOCAL_SHOP_ID,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val demand = database.demandDao()
    private val movements = database.stockMovementDao()

    /**
     * Daily demand for every product this shop has ever sold, newest day last.
     *
     * [historyDays] bounds how far back the window starts — not a limit on
     * accuracy but on work: a shop trading for three years would otherwise
     * rebuild a thousand days per product every time a forecast is wanted, on a
     * phone (D028). A year is far more than any method here can use.
     */
    suspend fun series(
        today: LocalDate,
        historyDays: Int = DEFAULT_HISTORY_DAYS,
    ): List<DemandSeries> {
        val notBefore = today.minusDays(historyDays.toLong() - 1)
        val sold = soldQuantities(notBefore, today)
        val discontinued = demand.discontinuedProductIds(shopId).map { EntityId(it) }.toSet()
        return dailyDemandByProduct(
            sold = sold,
            today = today,
            discontinued = discontinued,
            notBefore = notBefore,
        )
    }

    /**
     * One product's daily demand, or null when it has never been sold — which is
     * not the same as a series of zeros, and Step 95 has to be able to tell the
     * difference.
     *
     * [withStockOutDays] replays the stock ledger to mark the days the shop was
     * empty. It is off by default because it costs a second query and a second
     * pass, and only the research evaluation (Step 88) needs the mark; a
     * shopkeeper's reorder suggestion does not.
     */
    suspend fun seriesFor(
        productId: EntityId,
        today: LocalDate,
        historyDays: Int = DEFAULT_HISTORY_DAYS,
        withStockOutDays: Boolean = false,
    ): DemandSeries? {
        val notBefore = today.minusDays(historyDays.toLong() - 1)
        val sold = soldQuantities(notBefore, today).filter { it.productId == productId }
        val discontinued = productId.value in demand.discontinuedProductIds(shopId)
        val window =
            observedWindow(
                sold = sold,
                productId = productId,
                today = today,
                discontinued = discontinued,
                notBefore = notBefore,
            ) ?: return null

        val series = dailyDemand(productId, sold, window)
        if (!withStockOutDays) return series
        return markStockOutDays(
            series = series,
            movements = movements.forProduct(productId.value).map { it.toDomain() },
            zone = zone,
        )
    }

    /**
     * The window is read a day wide at each end of the calendar days asked for:
     * `from` starts at midnight local on its day, and `to` ends at midnight
     * local on the day *after* the last one, which is what makes a sale at 23:40
     * belong to the day the shopkeeper made it rather than to the next one in
     * UTC.
     */
    private suspend fun soldQuantities(
        from: LocalDate,
        to: LocalDate,
    ): List<SoldQuantity> {
        val fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMillis =
            to
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        return demand.soldBetween(shopId, fromMillis, toMillis).map {
            SoldQuantity(
                productId = EntityId(it.productId),
                day = it.occurredAt.atZone(zone).toLocalDate(),
                quantity = Quantity(it.quantityScaled),
            )
        }
    }

    companion object {
        /** A year. More history than any method in Step 85–87 can use, and cheap to read. */
        const val DEFAULT_HISTORY_DAYS = 365
    }
}
