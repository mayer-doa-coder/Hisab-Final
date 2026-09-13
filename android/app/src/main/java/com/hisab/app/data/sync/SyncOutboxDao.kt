package com.hisab.app.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface SyncOutboxDao {
    @Insert
    suspend fun insert(event: SyncOutboxEntity): Long

    @Query("SELECT * FROM sync_outbox ORDER BY id ASC")
    suspend fun all(): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE status = :status ORDER BY id ASC")
    suspend fun withStatus(status: String): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun count(): Int

    @Update
    suspend fun update(event: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE eventId = :eventId")
    suspend fun deleteByEventId(eventId: String)
}
