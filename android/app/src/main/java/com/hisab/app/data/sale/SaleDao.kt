package com.hisab.app.data.sale

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Reading and writing sales. There is no update and no delete: confirmed
 * history is never rewritten (CLAUDE.md), so the only way to undo a sale is
 * to insert its reversal.
 */
@Dao
interface SaleDao {
    @Insert
    suspend fun insert(sale: SaleEntity)

    @Insert
    suspend fun insertItems(items: List<SaleItemEntity>)

    @Query("SELECT * FROM sale WHERE id = :id")
    suspend fun byId(id: String): SaleEntity?

    @Query("SELECT * FROM sale_item WHERE saleId = :saleId ORDER BY productId ASC")
    suspend fun itemsFor(saleId: String): List<SaleItemEntity>

    /** Newest first — what the Transaction History screen shows (Step 42). */
    @Query(
        """
        SELECT * FROM sale
        WHERE shopId = :shopId
        ORDER BY occurredAt DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(
        shopId: String,
        limit: Int = 100,
    ): Flow<List<SaleEntity>>

    /**
     * The reversal of a sale, if one was recorded. This is how "has this
     * already been undone?" is answered — the original sale row is never
     * touched, so there is no flag on it to read (Step 43).
     */
    @Query("SELECT * FROM sale WHERE reversesSaleId = :saleId LIMIT 1")
    suspend fun reversalOf(saleId: String): SaleEntity?

    /**
     * What the shop took in over a period, reversals included: a reversal is
     * a sale with a negative total, so it cancels itself out here without
     * anything having to exclude it.
     */
    @Query(
        """
        SELECT COALESCE(SUM(totalPoisha), 0) FROM sale
        WHERE shopId = :shopId AND occurredAt >= :fromInclusive AND occurredAt < :toExclusive
        """,
    )
    suspend fun totalTakingsPoisha(
        shopId: String,
        fromInclusive: Long,
        toExclusive: Long,
    ): Long

    /**
     * What the history screen shows (Step 42), newest first, with the number
     * of lines counted in the same query.
     *
     * Counting per sale in Kotlin instead would mean one extra query per row
     * — 100 round trips to fill one screen on a cheap phone (D028).
     */
    @Query(
        """
        SELECT s.*,
               (SELECT COUNT(*) FROM sale_item i WHERE i.saleId = s.id) AS lineCount,
               EXISTS (SELECT 1 FROM sale r WHERE r.reversesSaleId = s.id) AS reversed
        FROM sale s
        WHERE s.shopId = :shopId
        ORDER BY s.occurredAt DESC, s.id DESC
        LIMIT :limit
        """,
    )
    fun observeRecentSummaries(
        shopId: String,
        limit: Int = 100,
    ): Flow<List<SaleSummary>>

    /** One sale's lines with each product's name and unit joined on, for the sale detail screen (Step 43). */
    @Query(
        """
        SELECT i.*, p.name AS productName, p.unit AS productUnit
        FROM sale_item i
        LEFT JOIN product p ON p.id = i.productId
        WHERE i.saleId = :saleId
        ORDER BY p.name COLLATE NOCASE ASC, i.productId ASC
        """,
    )
    suspend fun itemsWithProductFor(saleId: String): List<SaleItemWithProduct>

    /**
     * Records that the server has this sale. The only column of a sale that
     * ever changes after it is written, and it is sync bookkeeping, not
     * history: nothing the shopkeeper recorded is touched (D019).
     */
    @Query("UPDATE sale SET serverReceivedAt = :at WHERE id = :id AND serverReceivedAt IS NULL")
    suspend fun markServerReceived(
        id: String,
        at: Instant,
    )

    /**
     * Really removes a sale and (through the foreign key) its lines. Only for
     * rows that must leave the device for good — a row a test created. A
     * shopkeeper's reversal never comes through here.
     */
    @Query("DELETE FROM sale WHERE id = :id")
    suspend fun hardDelete(id: String)
}

/** A sale, how many lines it had, and whether it has since been reversed — one history row. */
data class SaleSummary(
    @Embedded val sale: SaleEntity,
    val lineCount: Int,
    val reversed: Boolean,
)

/** A sale line with its product's name and unit, null if the product is gone. */
data class SaleItemWithProduct(
    @Embedded val item: SaleItemEntity,
    val productName: String?,
    val productUnit: String?,
)
