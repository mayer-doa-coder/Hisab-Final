package com.hisab.app.data.product

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.sync.SyncOutboxEntity
import com.hisab.app.domain.Money
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 32, first half: a product change made offline must leave something to
 * send. The change and the queued event are written together, so there can
 * never be one without the other (D003).
 */
@RunWith(AndroidJUnit4::class)
class ProductOutboxTest {
    private lateinit var database: HisabDatabase
    private lateinit var repository: ProductRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        repository = ProductRepository(database, shopId = "shop-under-test")
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun draft(name: String = "চিনি") = ProductDraft(name = name, aliases = listOf("chini"), sellingPrice = Money(8500))

    private suspend fun queued(): List<SyncOutboxEntity> = database.syncOutboxDao().all()

    @Test
    fun addingAProductQueuesACreateToSend(): Unit =
        runBlocking {
            val saved = repository.create(draft())

            val events = queued()
            assertEquals(1, events.size)
            val event = events.first()
            assertEquals("Product", event.entityType)
            assertEquals(saved.id.value, event.entityId)
            assertEquals("create", event.operation)
            assertEquals(SyncOutboxEntity.STATUS_PENDING, event.status)
            // A create has nothing to be checked against yet (D017).
            assertNull(event.baseRevision)

            val payload = JSONObject(event.payload)
            assertEquals("চিনি", payload.getString("name"))
            assertEquals(8500L, payload.getLong("sellingPricePoisha"))
            assertEquals("chini", payload.getJSONArray("aliases").getString(0))
        }

    @Test
    fun editingQueuesAnUpdateCarryingTheRevisionItWasMadeFrom(): Unit =
        runBlocking {
            val saved = repository.create(draft())

            repository.update(saved.id, saved.revision, draft(name = "চিনি ১ কেজি"))

            val event = queued().last()
            assertEquals("update", event.operation)
            assertEquals(1, event.baseRevision)
            assertEquals("চিনি ১ কেজি", JSONObject(event.payload).getString("name"))
        }

    @Test
    fun deactivatingQueuesAnUpdateToo(): Unit =
        runBlocking {
            val saved = repository.create(draft())

            repository.setActive(saved.id, saved.revision, active = false)

            val event = queued().last()
            assertEquals("update", event.operation)
            assertEquals(1, event.baseRevision)
            assertEquals(false, JSONObject(event.payload).getBoolean("active"))
        }

    @Test
    fun deletingQueuesADelete(): Unit =
        runBlocking {
            val saved = repository.create(draft())

            repository.delete(saved.id, saved.revision)

            val event = queued().last()
            assertEquals("delete", event.operation)
            assertEquals(1, event.baseRevision)
        }

    // A refused edit changes nothing, so it must not leave anything to send
    // either — otherwise the phone would push a change it never made.
    @Test
    fun anEditRefusedForAStaleRevisionQueuesNothing(): Unit =
        runBlocking {
            val saved = repository.create(draft())
            repository.update(saved.id, saved.revision, draft(name = "first edit"))
            val beforeStaleEdit = queued().size

            val stale = repository.update(saved.id, saved.revision, draft(name = "stale edit"))

            assertTrue(stale is ProductWriteResult.Conflict)
            assertEquals(beforeStaleEdit, queued().size)
        }

    // A change that came from the server must not be sent back as if this
    // phone had made it.
    @Test
    fun applyingAServerChangeQueuesNothing(): Unit =
        runBlocking {
            val fromServer =
                ProductEntity(
                    id = "server-made-1",
                    shopId = "shop-under-test",
                    name = "Server product",
                    aliases = emptyList(),
                    unit = "piece",
                    purchasePricePoisha = null,
                    sellingPricePoisha = 1500,
                    active = true,
                    revision = 4,
                    updatedAt = java.time.Instant.parse("2026-09-14T10:00:00Z"),
                    deletedAt = null,
                )

            repository.applyFromServer(fromServer)

            assertTrue(queued().isEmpty())
            assertEquals(
                4,
                repository
                    .byId(
                        com.hisab.app.domain
                            .EntityId("server-made-1"),
                    )!!
                    .revision,
            )
        }
}
