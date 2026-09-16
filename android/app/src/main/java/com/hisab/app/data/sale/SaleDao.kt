package com.hisab.app.data.sale

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
     * Really removes a sale and (through the foreign key) its lines. Only for
     * rows that must leave the device for good — a row a test created. A
     * shopkeeper's reversal never comes through here.
     */
    @Query("DELETE FROM sale WHERE id = :id")
    suspend fun hardDelete(id: String)
}
