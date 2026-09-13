package com.hisab.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hisab.app.data.sync.SyncOutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * In-memory Room databases (used by the DAO tests) don't exercise everything
 * a real on-disk database does. This builds the actual file-backed database
 * the app will use, to catch anything that only shows up there.
 */
@RunWith(AndroidJUnit4::class)
class HisabDatabaseTest {
    @Test
    fun realFileBackedDatabaseOpensSurvivesRestartAndKeepsData(): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.deleteDatabase(HisabDatabase.DATABASE_NAME)

            val db1 =
                Room
                    .databaseBuilder(context, HisabDatabase::class.java, HisabDatabase.DATABASE_NAME)
                    .build()

            // Room opens the underlying connection lazily — .build() alone
            // doesn't touch it, so isOpen is only meaningful after a real
            // operation. The insert below is that operation and is the real
            // proof this works, not this flag on its own.
            db1.syncOutboxDao().insert(
                SyncOutboxEntity(
                    eventId = "evt-persisted",
                    entityType = "Product",
                    entityId = "prod-1",
                    operation = "create",
                    payload = "{}",
                    baseRevision = null,
                    clientTimestamp = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )
            assertTrue(db1.isOpen)
            db1.close()

            // Reopen as a fresh instance, simulating the app restarting —
            // proves data survives a real close/reopen, not just an
            // in-memory handle staying alive within one test.
            val db2 =
                Room
                    .databaseBuilder(context, HisabDatabase::class.java, HisabDatabase.DATABASE_NAME)
                    .build()
            val rows = db2.syncOutboxDao().all()
            assertEquals(1, rows.size)
            assertEquals("evt-persisted", rows[0].eventId)
            db2.close()

            context.deleteDatabase(HisabDatabase.DATABASE_NAME)
        }
}
