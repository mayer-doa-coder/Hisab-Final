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

/**
 * Adds the `sale`, `sale_item` and `stock_movement` tables (Step 38). Written
 * by hand for the same reason as `MIGRATION_1_2`: a phone's local database is
 * the source of truth for data that may not be synced yet, so a destructive
 * migration is never an option (PRD section 22, CLAUDE.md).
 *
 * No table here has a `revision` or a `deletedAt`. These are ledgers — they
 * are never edited in place and never deleted (D017), so there is nothing for
 * two devices to disagree about.
 *
 * There is also no stock column anywhere. Current stock is always the sum of
 * `stock_movement.quantityDeltaScaled` (D002, D020).
 *
 * The statements must match what Room generates for the entities exactly —
 * `SaleStockMigrationTest` fails if they drift apart.
 */
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sale` (
                    `id` TEXT NOT NULL,
                    `shopId` TEXT NOT NULL,
                    `totalPoisha` INTEGER NOT NULL,
                    `payment` TEXT NOT NULL,
                    `customerId` TEXT,
                    `reversesSaleId` TEXT,
                    `occurredAt` INTEGER NOT NULL,
                    `serverReceivedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sale_shopId_occurredAt` ON `sale` (`shopId`, `occurredAt`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_customerId` ON `sale` (`customerId`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sale_reversesSaleId` ON `sale` (`reversesSaleId`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sale_item` (
                    `saleId` TEXT NOT NULL,
                    `productId` TEXT NOT NULL,
                    `quantityScaled` INTEGER NOT NULL,
                    `unitPricePoisha` INTEGER NOT NULL,
                    PRIMARY KEY(`saleId`, `productId`),
                    FOREIGN KEY(`saleId`) REFERENCES `sale`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sale_item_productId` ON `sale_item` (`productId`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stock_movement` (
                    `id` TEXT NOT NULL,
                    `productId` TEXT NOT NULL,
                    `movementType` TEXT NOT NULL,
                    `quantityDeltaScaled` INTEGER NOT NULL,
                    `sourceReference` TEXT,
                    `occurredAt` INTEGER NOT NULL,
                    `serverReceivedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_stock_movement_productId_occurredAt` " +
                    "ON `stock_movement` (`productId`, `occurredAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_stock_movement_sourceReference` " +
                    "ON `stock_movement` (`sourceReference`)",
            )
        }
    }

/**
 * Adds the `customer` and `baki_entry` tables (Step 40).
 *
 * Both belong to M3 by the build plan, where the customer and baki *screens*
 * live. They arrive here because Step 40's check is that a credit sale
 * "saves the sale, reduces stock, and creates a baki entry" — an entry that
 * is not stored has not been created, and it cannot say who owes the money
 * without a customer to point at. Only the tables and a name lookup exist
 * now; the ledger functions and every customer screen are still M3.
 *
 * `customer` is a mutable entity, so it carries revision/updatedAt/deletedAt
 * like Product (D017). `baki_entry` is a ledger and carries none of them.
 *
 * The statements must match what Room generates for the entities exactly —
 * `SaleStockMigrationTest` fails if they drift apart.
 */
val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `customer` (
                    `id` TEXT NOT NULL,
                    `shopId` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `phone` TEXT,
                    `revision` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `deletedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_customer_shopId_name` ON `customer` (`shopId`, `name`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `baki_entry` (
                    `id` TEXT NOT NULL,
                    `customerId` TEXT NOT NULL,
                    `amountDeltaPoisha` INTEGER NOT NULL,
                    `entryType` TEXT NOT NULL,
                    `reference` TEXT,
                    `dueDateEpochDay` INTEGER,
                    `occurredAt` INTEGER NOT NULL,
                    `serverReceivedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_baki_entry_customerId_occurredAt` " +
                    "ON `baki_entry` (`customerId`, `occurredAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_baki_entry_reference` ON `baki_entry` (`reference`)",
            )
        }
    }
