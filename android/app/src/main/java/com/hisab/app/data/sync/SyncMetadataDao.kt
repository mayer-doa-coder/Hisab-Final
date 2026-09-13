package com.hisab.app.data.sync

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncMetadataDao {
    @Upsert
    suspend fun upsert(metadata: SyncMetadataEntity)

    @Query("SELECT * FROM sync_metadata LIMIT 1")
    suspend fun get(): SyncMetadataEntity?
}
