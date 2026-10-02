package com.hisab.app.data.forecast

import androidx.room.Dao
import androidx.room.Query
import java.time.Instant

/**
 * One sale line, dated by the day the sale happened, for the forecasting
 * pipeline (Step 84).
 *
 * [occurredAt] is the *effective* time: for a reversal it is the time of the
 * sale being undone, not the time of the undoing. See [DemandDao.soldBetween].
 */
data class DemandRow(
    val productId: String,
    val occurredAt: Instant,
    val quantityScaled: Long,
)

/**
 * The read side of the forecasting pipeline. Nothing here writes, and nothing
 * here forecasts — the rules live in `domain/forecast/`, and this only fetches
 * the rows they are fed.
 */
@Dao
interface DemandDao {
    /**
     * Every sale line in the window, each dated by the day its demand really
     * happened.
     *
     * The `LEFT JOIN` on `reversesSaleId` is the whole point of this query. A
     * reversal is stored as a second, opposite sale (D033), so its negative
     * line carries the date it was undone on. Left as it stands, a sale of 12
     * on Monday undone on Thursday would read as +12 demand on Monday and −12
     * on Thursday: one invented spike and one invented hole, neither of which
     * happened. Joining the original sale and taking *its* `occurredAt` puts
     * the negative line back on Monday, where it cancels the sale it undoes and
     * leaves the day at zero — which is what happened.
     *
     * When the original sale is not on this phone yet (a reversal can arrive
     * first over sync — `SaleEntity` says why there is no foreign key), the
     * `COALESCE` falls back to the reversal's own date. `dailyDemand` floors
     * such a day at zero, and the next sync fixes it.
     *
     * Reversals are not filtered out, they are netted. Filtering them would
     * count demand that was taken back.
     *
     * Rows come back oldest first. Nothing downstream depends on that — the
     * pipeline groups by day — but a query that returns history in order is
     * one less place an ordering can be lost (Step 84: never shuffle).
     */
    @Query(
        """
        SELECT i.productId AS productId,
               COALESCE(o.occurredAt, s.occurredAt) AS occurredAt,
               i.quantityScaled AS quantityScaled
        FROM sale_item i
        JOIN sale s ON s.id = i.saleId
        LEFT JOIN sale o ON o.id = s.reversesSaleId
        WHERE s.shopId = :shopId
          AND COALESCE(o.occurredAt, s.occurredAt) >= :fromInclusive
          AND COALESCE(o.occurredAt, s.occurredAt) < :toExclusive
        ORDER BY occurredAt ASC, i.productId ASC
        """,
    )
    suspend fun soldBetween(
        shopId: String,
        fromInclusive: Long,
        toExclusive: Long,
    ): List<DemandRow>

    /** Which products this shop no longer sells — their demand series stops at their last sale (Step 84, rule 4). */
    @Query("SELECT id FROM product WHERE shopId = :shopId AND (active = 0 OR deletedAt IS NOT NULL)")
    suspend fun discontinuedProductIds(shopId: String): List<String>
}
