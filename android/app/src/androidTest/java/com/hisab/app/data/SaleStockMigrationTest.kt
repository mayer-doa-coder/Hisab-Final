package com.hisab.app.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every migration M2 adds, checked the only way that means anything: open a
 * real database of the older version with rows in it, run the migration, and
 * let Room check the result against the exported schema — which is what
 * catches hand-written SQL drifting from the entities.
 *
 * A shop's local database holds data that may not be synced yet, so losing it
 * to a migration is losing it for good (PRD section 22).
 */
@RunWith(AndroidJUnit4::class)
class SaleStockMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HisabDatabase::class.java,
        )

    @After
    fun deleteTestDatabase() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DB)
    }

    @Test
    fun addingTheSaleAndStockTablesKeepsExistingData() {
        helper.createDatabase(TEST_DB, 2).use { version2 ->
            version2.execSQL(
                """
                INSERT INTO product
                    (id, shopId, name, aliases, unit, purchasePricePoisha, sellingPricePoisha,
                     active, revision, updatedAt, deletedAt)
                VALUES ('product-1', 'shop-1', 'চিনি', 'chini', 'kg', 9000, 12000, 1, 1, 1757800000000, NULL)
                """.trimIndent(),
            )
            version2.execSQL(
                """
                INSERT INTO sync_outbox
                    (eventId, entityType, entityId, operation, payload, baseRevision, clientTimestamp, status, retryCount)
                VALUES ('event-1', 'Product', 'product-1', 'create', '{}', NULL, 1757800000000, 'pending', 0)
                """.trimIndent(),
            )
        }

        // Fails if the hand-written migration does not produce exactly the
        // schema Room expects for version 3.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        migrated.query("SELECT name FROM product").use { cursor ->
            assertTrue("the product should survive the upgrade", cursor.moveToFirst())
            assertEquals("চিনি", cursor.getString(0))
        }
        migrated.query("SELECT eventId FROM sync_outbox").use { cursor ->
            assertTrue("the queued sync event should survive the upgrade", cursor.moveToFirst())
            assertEquals("event-1", cursor.getString(0))
        }

        for (table in listOf("sale", "sale_item", "stock_movement")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table should exist and start empty", 0, cursor.getInt(0))
            }
        }
    }

    /**
     * Adding customer and baki_entry (Step 40) must not cost a shop the sales
     * already recorded on the phone, which may not be synced yet.
     */
    @Test
    fun addingTheCustomerAndBakiTablesKeepsExistingSales() {
        helper.createDatabase(TEST_DB, 3).use { version3 ->
            version3.execSQL(
                """
                INSERT INTO sale
                    (id, shopId, totalPoisha, payment, customerId, reversesSaleId, occurredAt, serverReceivedAt)
                VALUES ('sale-1', 'shop-1', 20000, 'CASH', NULL, NULL, 1757800000000, NULL)
                """.trimIndent(),
            )
            version3.execSQL(
                """
                INSERT INTO stock_movement
                    (id, productId, movementType, quantityDeltaScaled, sourceReference, occurredAt, serverReceivedAt)
                VALUES ('move-1', 'product-1', 'RESTOCK', 10000, NULL, 1757800000000, NULL)
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        migrated.query("SELECT totalPoisha FROM sale").use { cursor ->
            assertTrue("the sale should survive the upgrade", cursor.moveToFirst())
            assertEquals(20_000, cursor.getInt(0))
        }
        migrated.query("SELECT quantityDeltaScaled FROM stock_movement").use { cursor ->
            assertTrue("the movement should survive the upgrade", cursor.moveToFirst())
            assertEquals(10_000, cursor.getInt(0))
        }

        for (table in listOf("customer", "baki_entry")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table should exist and start empty", 0, cursor.getInt(0))
            }
        }
    }

    /** A phone that has never been upgraded, only ever installed fresh, takes the same path. */
    @Test
    fun aDatabaseCreatedAtVersionOneReachesTheNewestVersion() {
        helper.createDatabase(TEST_DB, 1).close()
        val migrated =
            helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        for (table in listOf("product", "sale", "sale_item", "stock_movement", "customer", "baki_entry")) {
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table should exist and start empty", 0, cursor.getInt(0))
            }
        }
    }

    companion object {
        private const val TEST_DB = "sale-stock-migration-test.db"
    }
}
