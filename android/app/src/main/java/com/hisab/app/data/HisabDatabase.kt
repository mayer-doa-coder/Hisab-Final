package com.hisab.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.hisab.app.data.sync.SyncMetadataDao
import com.hisab.app.data.sync.SyncMetadataEntity
import com.hisab.app.data.sync.SyncOutboxDao
import com.hisab.app.data.sync.SyncOutboxEntity

@Database(
    entities = [SyncOutboxEntity::class, SyncMetadataEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(InstantConverters::class)
abstract class HisabDatabase : RoomDatabase() {
    abstract fun syncOutboxDao(): SyncOutboxDao

    abstract fun syncMetadataDao(): SyncMetadataDao

    companion object {
        const val DATABASE_NAME = "hisab.db"
    }
}
