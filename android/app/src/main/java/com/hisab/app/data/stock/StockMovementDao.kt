package com.hisab.app.data.stock

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import com.hisab.app.data.product.ProductEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

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

    /**
     * What the Stock screen shows (Step 41): every product this shop sells,
     * each with its stock worked out from the ledger.
     *
     * One query rather than one per product. A shop with 200 products on a
     * cheap phone (D028) would otherwise make 200 round trips to fill one
     * screen. The subquery is what keeps stock derived (D020) — there is
     * still no stored stock column to drift.
     *
     * Products are listed even when they have never moved: a new product
     * reads as zero, which is the honest answer, not an absence.
     */
    @Query(
        """
        SELECT p.*,
               COALESCE((
                   SELECT SUM(m.quantityDeltaScaled)
                   FROM stock_movement m
                   WHERE m.productId = p.id
               ), 0) AS stockScaled
        FROM product p
        WHERE p.shopId = :shopId
          AND p.deletedAt IS NULL
          AND (:includeInactive OR p.active = 1)
          AND (
            :query = ''
            OR p.name LIKE '%' || :query || '%'
            OR p.aliases LIKE '%' || :query || '%'
          )
        ORDER BY p.active DESC, p.name COLLATE NOCASE ASC
        """,
    )
    fun observeProductsWithStock(
        shopId: String,
        query: String,
        includeInactive: Boolean,
    ): Flow<List<ProductWithStock>>

    /**
     * Stock movements for the history screen (Step 42), newest first, with
     * the product's name already joined on so the screen does not have to
     * look up 100 names one at a time.
     *
     * `excludeType` is passed in rather than written into the SQL so the
     * caller names it with the enum. History uses it to leave out SALE
     * movements: a sale is already shown as a sale, and showing its stock
     * movement again would list the same event twice.
     *
     * The join is LEFT: a movement whose product was deleted still belongs in
     * history, with no name, rather than vanishing from it.
     */
    @Query(
        """
        SELECT m.*, p.name AS productName
        FROM stock_movement m
        LEFT JOIN product p ON p.id = m.productId
        WHERE m.movementType != :excludeType
        ORDER BY m.occurredAt DESC, m.id DESC
        LIMIT :limit
        """,
    )
    fun observeRecentMovements(
        excludeType: String,
        limit: Int = 100,
    ): Flow<List<MovementWithProduct>>

    /** Records that the server has this movement — sync bookkeeping, not history (D019). */
    @Query("UPDATE stock_movement SET serverReceivedAt = :at WHERE id = :id AND serverReceivedAt IS NULL")
    suspend fun markServerReceived(
        id: String,
        at: Instant,
    )

    /** Really removes a movement. Only for rows a test created, never for a shopkeeper's correction. */
    @Query("DELETE FROM stock_movement WHERE id = :id")
    suspend fun hardDelete(id: String)
}

/** A product and the stock its movements add up to. */
data class ProductWithStock(
    @Embedded val product: ProductEntity,
    val stockScaled: Long,
)

/** A stock movement and the name of the product it moved, null if that product is gone. */
data class MovementWithProduct(
    @Embedded val movement: StockMovementEntity,
    val productName: String?,
)
