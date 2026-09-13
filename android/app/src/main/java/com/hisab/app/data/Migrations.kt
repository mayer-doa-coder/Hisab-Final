package com.hisab.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `product` table (Step 23). Written by hand rather than letting Room
 * wipe and rebuild: destructive migrations are never used, because a phone's
 * local database is the source of truth for data that may not be synced yet
 * (PRD section 22, CLAUDE.md).
 *
 * The statements must match what Room generates for the entity exactly —
 * `ProductMigrationTest` fails if they drift apart.
 */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `product` (
                    `id` TEXT NOT NULL,
                    `shopId` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `aliases` TEXT NOT NULL,
                    `unit` TEXT NOT NULL,
                    `purchasePricePoisha` INTEGER,
                    `sellingPricePoisha` INTEGER NOT NULL,
                    `active` INTEGER NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `deletedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_product_shopId` ON `product` (`shopId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_product_name` ON `product` (`name`)")
        }
    }
