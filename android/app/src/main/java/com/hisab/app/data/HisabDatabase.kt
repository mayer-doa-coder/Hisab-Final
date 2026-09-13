package com.hisab.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.hisab.app.data.product.ProductDao
import com.hisab.app.data.product.ProductEntity
import com.hisab.app.data.sync.SyncMetadataDao
import com.hisab.app.data.sync.SyncMetadataEntity
import com.hisab.app.data.sync.SyncOutboxDao
import com.hisab.app.data.sync.SyncOutboxEntity

@Database(
    entities = [SyncOutboxEntity::class, SyncMetadataEntity::class, ProductEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(InstantConverters::class, AliasListConverters::class)
abstract class HisabDatabase : RoomDatabase() {
    abstract fun syncOutboxDao(): SyncOutboxDao

    abstract fun syncMetadataDao(): SyncMetadataDao

    abstract fun productDao(): ProductDao

    companion object {
        const val DATABASE_NAME = "hisab.db"

        @Volatile
        private var instance: HisabDatabase? = null

        /**
         * One database for the whole app. Room is expensive to open, and two
         * open copies of the same file would see different data.
         *
         * No destructive fallback on purpose: if a migration is ever missing,
         * the app must fail loudly rather than quietly wipe a shop's
         * unsynced data (PRD section 22).
         */
        fun get(context: Context): HisabDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        HisabDatabase::class.java,
                        DATABASE_NAME,
                    ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
