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
 * Adding the product table must not cost a shop the data already on the
 * phone, some of which may not be synced yet. This opens a real version-1
 * database with a row in it, runs the migration, and lets Room check the
 * result against the exported version-2 schema.
 */
@RunWith(AndroidJUnit4::class)
class ProductMigrationTest {
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
    fun addingTheProductTableKeepsExistingData() {
        helper.createDatabase(TEST_DB, 1).use { version1 ->
            version1.execSQL(
                """
                INSERT INTO sync_outbox
                    (eventId, entityType, entityId, operation, payload, baseRevision, clientTimestamp, status, retryCount)
                VALUES ('event-1', 'Product', 'product-1', 'create', '{}', NULL, 1757800000000, 'pending', 0)
                """.trimIndent(),
            )
        }

        // Fails if the hand-written migration does not produce exactly the
        // schema Room expects for version 2.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        migrated.query("SELECT eventId FROM sync_outbox").use { cursor ->
            assertTrue("the queued sync event should survive the upgrade", cursor.moveToFirst())
            assertEquals("event-1", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM product").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
