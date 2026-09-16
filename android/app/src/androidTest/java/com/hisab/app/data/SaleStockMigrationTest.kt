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
 * Adding the sale, sale_item and stock_movement tables (Step 38) must not
 * cost a shop the products already on the phone, some of which may not be
 * synced yet. This opens a real version-2 database with rows in it, runs the
 * migration, and lets Room check the result against the exported version-3
 * schema — which is what catches hand-written SQL drifting from the entities.
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

    /** A phone that has never been upgraded, only ever installed fresh, takes the same path. */
    @Test
    fun aDatabaseCreatedAtVersionOneReachesVersionThree() {
        helper.createDatabase(TEST_DB, 1).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3)

        migrated.query("SELECT COUNT(*) FROM stock_movement").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    companion object {
        private const val TEST_DB = "sale-stock-migration-test.db"
    }
}
