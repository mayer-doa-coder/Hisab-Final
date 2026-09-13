package com.hisab.app.data.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hisab.app.data.HisabDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SyncOutboxDaoTest {
    private lateinit var db: HisabDatabase
    private lateinit var dao: SyncOutboxDao

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        dao = db.syncOutboxDao()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun insertAndReadBack() =
        runBlocking {
            dao.insert(sampleEvent("evt-1"))

            val all = dao.all()
            assertEquals(1, all.size)
            assertEquals("evt-1", all[0].eventId)
            assertEquals(SyncOutboxEntity.STATUS_PENDING, all[0].status)
            assertEquals(0, all[0].retryCount)
        }

    @Test
    fun rowsComeBackInInsertionOrder() =
        runBlocking {
            dao.insert(sampleEvent("evt-1"))
            dao.insert(sampleEvent("evt-2"))
            dao.insert(sampleEvent("evt-3"))

            assertEquals(listOf("evt-1", "evt-2", "evt-3"), dao.all().map { it.eventId })
        }

    // The whole point of eventId (D004, D018): it must never be reused, so
    // the database itself refuses a duplicate rather than trusting callers.
    @Test
    fun eventIdMustBeUnique() =
        runBlocking {
            dao.insert(sampleEvent("evt-1"))

            var threw = false
            try {
                dao.insert(sampleEvent("evt-1"))
            } catch (expected: android.database.sqlite.SQLiteConstraintException) {
                threw = true
            }
            assertTrue("inserting a duplicate eventId must throw", threw)
            assertEquals(1, dao.count())
        }

    @Test
    fun withStatusFiltersToOnlyMatchingRows() =
        runBlocking {
            dao.insert(sampleEvent("evt-1"))
            dao.insert(sampleEvent("evt-2").copy(status = SyncOutboxEntity.STATUS_SENT))

            val pending = dao.withStatus(SyncOutboxEntity.STATUS_PENDING)
            assertEquals(1, pending.size)
            assertEquals("evt-1", pending[0].eventId)
        }

    @Test
    fun updateChangesStatusAndRetryCountInPlace() =
        runBlocking {
            dao.insert(sampleEvent("evt-1"))
            val stored = dao.all().first()

            dao.update(stored.copy(status = SyncOutboxEntity.STATUS_SENT, retryCount = 1))

            val updated = dao.all().first()
            assertEquals(SyncOutboxEntity.STATUS_SENT, updated.status)
            assertEquals(1, updated.retryCount)
            assertEquals(1, dao.count())
        }

    @Test
    fun deleteByEventIdRemovesOnlyThatRow() =
        runBlocking {
            dao.insert(sampleEvent("evt-1"))
            dao.insert(sampleEvent("evt-2"))

            dao.deleteByEventId("evt-1")

            val remaining = dao.all()
            assertEquals(1, remaining.size)
            assertEquals("evt-2", remaining[0].eventId)
        }

    private fun sampleEvent(eventId: String) =
        SyncOutboxEntity(
            eventId = eventId,
            entityType = "Product",
            entityId = "prod-1",
            operation = "create",
            payload = "{}",
            baseRevision = null,
            clientTimestamp = Instant.parse("2026-01-01T00:00:00Z"),
        )
}
