package com.hisab.app.data.product

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Insert
    suspend fun insert(product: ProductEntity)

    @Update
    suspend fun update(product: ProductEntity)

    @Query("SELECT * FROM product WHERE id = :id")
    suspend fun byId(id: String): ProductEntity?

    /**
     * The one query the product list uses, for browsing and for searching.
     *
     * An empty `query` means "no search, show everything". A search matches
     * the name or any alias (Step 27). Deleted products are always hidden;
     * inactive ones only appear when asked for, and then sort after the
     * active ones.
     *
     * Returns a Flow, so a local write shows up on screen immediately without
     * anything having to ask again — which is what makes an offline add feel
     * instant (Step 26).
     */
    @Query(
        """
        SELECT * FROM product
        WHERE shopId = :shopId
          AND deletedAt IS NULL
          AND (:includeInactive OR active = 1)
          AND (
            :query = ''
            OR name LIKE '%' || :query || '%'
            OR aliases LIKE '%' || :query || '%'
          )
        ORDER BY active DESC, name COLLATE NOCASE ASC
        """,
    )
    fun observe(
        shopId: String,
        query: String,
        includeInactive: Boolean,
    ): Flow<List<ProductEntity>>

    @Query("SELECT COUNT(*) FROM product WHERE deletedAt IS NULL")
    suspend fun countLive(): Int

    /**
     * Really removes the row, unlike the tombstone delete a user does. Only
     * for rows that must leave the device for good — a tombstone already
     * synced and purged, or a row a test created.
     */
    @Query("DELETE FROM product WHERE id = :id")
    suspend fun hardDelete(id: String)
}
