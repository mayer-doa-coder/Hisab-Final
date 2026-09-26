package com.hisab.app.data.baki

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Writing and reading baki entries. There is no update and no delete: a
 * confirmed entry is never rewritten, only answered with an opposite one.
 *
 * Reading is deliberately plain: a customer's balance is a `SUM` over their
 * rows (D001), and the rules that need more — overdue, what an entry undoes —
 * are in `domain/Baki.kt`, applied to the rows `forCustomer` returns.
 */
@Dao
interface BakiEntryDao {
    @Insert
    suspend fun insert(entry: BakiEntryEntity)

    @Query("SELECT * FROM baki_entry WHERE id = :id")
    suspend fun byId(id: String): BakiEntryEntity?

    @Query("SELECT * FROM baki_entry WHERE customerId = :customerId ORDER BY occurredAt ASC, id ASC")
    suspend fun forCustomer(customerId: String): List<BakiEntryEntity>

    /**
     * Every entry, newest first, as a live query: a payment recorded on the
     * next screen changes what this emits, so a list of balances built from it
     * updates by itself with nothing to refresh (D001).
     */
    @Query("SELECT * FROM baki_entry ORDER BY occurredAt DESC, id DESC")
    fun observeAll(): Flow<List<BakiEntryEntity>>

    /** One customer's entries, newest first, as a live query — their ledger on screen. */
    @Query("SELECT * FROM baki_entry WHERE customerId = :customerId ORDER BY occurredAt DESC, id DESC")
    fun observeForCustomer(customerId: String): Flow<List<BakiEntryEntity>>

    /** Everything caused by one sale, so a reversal can be found from the sale it undoes. */
    @Query("SELECT * FROM baki_entry WHERE reference = :reference ORDER BY occurredAt ASC, id ASC")
    suspend fun forReference(reference: String): List<BakiEntryEntity>

    /**
     * What a customer owes, as the sum of their entries — D001 written in SQL.
     * `COALESCE` makes a customer with no entries read as owing nothing.
     */
    @Query("SELECT COALESCE(SUM(amountDeltaPoisha), 0) FROM baki_entry WHERE customerId = :customerId")
    suspend fun balancePoisha(customerId: String): Long

    /** Records that the server has this entry — sync bookkeeping, not history (D019). */
    @Query("UPDATE baki_entry SET serverReceivedAt = :at WHERE id = :id AND serverReceivedAt IS NULL")
    suspend fun markServerReceived(
        id: String,
        at: Instant,
    )

    /** Really removes a row. Only for rows a test created. */
    @Query("DELETE FROM baki_entry WHERE id = :id")
    suspend fun hardDelete(id: String)
}
