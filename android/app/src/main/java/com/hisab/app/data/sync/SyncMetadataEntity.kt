package com.hisab.app.data.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Exactly one row: this device's own sync bookmark. `deviceId` is generated
 * once, locally, the first time the app runs. `lastServerCursor` is null
 * until the first successful pull (Step 16) — nothing writes it yet.
 */
@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val deviceId: String,
    val lastServerCursor: String?,
)
