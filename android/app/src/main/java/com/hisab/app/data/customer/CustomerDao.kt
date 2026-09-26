package com.hisab.app.data.customer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Insert
    suspend fun insert(customer: CustomerEntity)

    @Query("SELECT * FROM customer WHERE id = :id")
    suspend fun byId(id: String): CustomerEntity?

    /** One customer as a live query, so their screen follows an edit or a sync by itself. */
    @Query("SELECT * FROM customer WHERE id = :id")
    fun observeById(id: String): Flow<CustomerEntity?>

    /**
     * Case-insensitive exact match on the name. This is what stops a shop
     * ending up with three "রহিম"s because a credit sale was recorded on
     * three different days (see CustomerRepository.findOrCreate).
     */
    @Query(
        """
        SELECT * FROM customer
        WHERE shopId = :shopId AND deletedAt IS NULL AND name = :name COLLATE NOCASE
        LIMIT 1
        """,
    )
    suspend fun byName(
        shopId: String,
        name: String,
    ): CustomerEntity?

    /** Everyone this shop has recorded, for the credit-sale picker. */
    @Query(
        """
        SELECT * FROM customer
        WHERE shopId = :shopId
          AND deletedAt IS NULL
          AND (:query = '' OR name LIKE '%' || :query || '%')
        ORDER BY name COLLATE NOCASE ASC
        """,
    )
    fun observe(
        shopId: String,
        query: String,
    ): Flow<List<CustomerEntity>>

    /** Saving what the server sent: the row may or may not be here yet (Step 47). */
    @Upsert
    suspend fun upsert(customer: CustomerEntity)

    /** Really removes the row. Only for rows a test created. */
    @Query("DELETE FROM customer WHERE id = :id")
    suspend fun hardDelete(id: String)
}
