package com.hisab.app.data.suggest

import androidx.room.Dao
import androidx.room.Query

/** One sale line reduced to the pair the counting needs. */
data class BasketRow(
    val saleId: String,
    val productId: String,
)

/**
 * The read side of the "bought together" counting (Step 91). It defines no table
 * and writes nothing: suggestions are derived from the sale ledger every time and
 * never stored (PRD section 17.3, the same rule stock follows, D020).
 */
@Dao
interface SuggestionDao {
    /**
     * Every line of every sale that really happened since [fromInclusive].
     *
     * "Really happened" is the point of this query. An undone sale is stored as a
     * second, opposite sale (D033) and the original stays in the ledger, so
     * reading the lines as they stand would count the sale twice over: once as a
     * purchase that was taken back, once as a basket of negative lines. Neither
     * tells the shopkeeper what goes out together, so both are left out:
     *
     * - `reversesSaleId IS NULL` drops the reversals themselves;
     * - `NOT EXISTS (...)` drops the originals they undid.
     *
     * An original whose reversal has not reached this phone yet cannot be told
     * apart from an ordinary sale and is counted until it does; the count then
     * corrects itself on the next read, with nothing to repair.
     *
     * Cash and credit sales are counted alike: what a customer paid with does not
     * change what they bought.
     */
    @Query(
        """
        SELECT i.saleId AS saleId, i.productId AS productId
        FROM sale_item i
        JOIN sale s ON s.id = i.saleId
        WHERE s.shopId = :shopId
          AND s.reversesSaleId IS NULL
          AND s.occurredAt >= :fromInclusive
          AND NOT EXISTS (SELECT 1 FROM sale r WHERE r.reversesSaleId = s.id)
        ORDER BY i.saleId ASC, i.productId ASC
        """,
    )
    suspend fun basketRows(
        shopId: String,
        fromInclusive: Long,
    ): List<BasketRow>
}
