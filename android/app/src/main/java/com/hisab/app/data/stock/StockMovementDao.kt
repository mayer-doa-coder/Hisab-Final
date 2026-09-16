package com.hisab.app.data.stock

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reading and writing stock movements. There is no update and no delete:
 * a movement is never rewritten, only answered with an opposite one.
 */
@Dao
interface StockMovementDao {
    @Insert
    suspend fun insert(movement: StockMovementEntity)

    @Insert
    suspend fun insertAll(movements: List<StockMovementEntity>)

    @Query("SELECT * FROM stock_movement WHERE id = :id")
    suspend fun byId(id: String): StockMovementEntity?

    /** Oldest first — the stock history of one product, in the order it happened. */
    @Query("SELECT * FROM stock_movement WHERE productId = :productId ORDER BY occurredAt ASC, id ASC")
    suspend fun forProduct(productId: String): List<StockMovementEntity>

    /** Everything caused by one sale, including its reversal's movements. */
    @Query("SELECT * FROM stock_movement WHERE sourceReference = :reference ORDER BY occurredAt ASC, id ASC")
    suspend fun forReference(reference: String): List<StockMovementEntity>

    /**
     * Current stock, as the sum of the movements — D020 written in SQL.
     *
     * `COALESCE` is what makes a product with no movements read as zero
     * rather than null: a new product starts at nothing without anything
     * having to write a starting row.
     */
    @Query("SELECT COALESCE(SUM(quantityDeltaScaled), 0) FROM stock_movement WHERE productId = :productId")
    suspend fun currentStockScaled(productId: String): Long

    /** The same sum as a live query, so a restock or a sale shows on screen by itself. */
    @Query("SELECT COALESCE(SUM(quantityDeltaScaled), 0) FROM stock_movement WHERE productId = :productId")
    fun observeCurrentStockScaled(productId: String): Flow<Long>

    /** Current stock for every product that has ever moved — what the Stock screen lists (Step 41). */
    @Query(
        """
        SELECT productId, SUM(quantityDeltaScaled) AS stockScaled
        FROM stock_movement
        GROUP BY productId
        """,
    )
    fun observeCurrentStock(): Flow<List<ProductStock>>

    /** Really removes a movement. Only for rows a test created, never for a shopkeeper's correction. */
    @Query("DELETE FROM stock_movement WHERE id = :id")
    suspend fun hardDelete(id: String)
}

/** One row of the "stock per product" query above. */
data class ProductStock(
    val productId: String,
    val stockScaled: Long,
)
