package com.hisab.app.data.product

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Step 24: the rules around a product write — a device-made id, revisions
 * that only move forward, a stale write rejected rather than applied (D017),
 * and deletion as a tombstone.
 */
@RunWith(AndroidJUnit4::class)
class ProductRepositoryTest {
    private lateinit var database: HisabDatabase
    private lateinit var repository: ProductRepository

    private val createdAt = Instant.parse("2026-09-14T10:00:00Z")
    private val editedAt = Instant.parse("2026-09-14T11:30:00Z")
    private var clockNow = createdAt

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        clockNow = createdAt
        // A clock the test controls, so "updated at" is checked as a real
        // value rather than "some time around now".
        val clock =
            object : Clock() {
                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId?) = this

                override fun instant() = clockNow
            }
        repository = ProductRepository(database, clock, shopId = "shop-under-test")
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun draft(
        name: String = "চিনি",
        aliases: List<String> = emptyList(),
        selling: Long = 8500,
        purchase: Long? = null,
        unit: String = ProductUnits.KG,
        active: Boolean = true,
    ) = ProductDraft(
        name = name,
        aliases = aliases,
        unit = unit,
        purchasePrice = purchase?.let(::Money),
        sellingPrice = Money(selling),
        active = active,
    )

    private suspend fun visible(includeInactive: Boolean = false) = repository.observe(includeInactive = includeInactive).first()

    @Test
    fun createStoresTheProductAtRevisionOne(): Unit =
        runBlocking {
            val saved = repository.create(draft(name = "  চিনি  ", aliases = listOf("chini"), purchase = 8000))

            val stored = repository.byId(saved.id)!!
            assertEquals(1, saved.revision)
            assertEquals(1, stored.revision)
            assertEquals("চিনি", stored.name)
            assertEquals(listOf("chini"), stored.aliases)
            assertEquals(Money(8000), stored.purchasePrice)
            assertEquals(Money(8500), stored.sellingPrice)
            assertEquals(createdAt, stored.updatedAt)
            assertNull(stored.deletedAt)
        }

    @Test
    fun everyProductGetsItsOwnId(): Unit =
        runBlocking {
            val first = repository.create(draft(name = "One"))
            val second = repository.create(draft(name = "Two"))

            assertNotEquals(first.id, second.id)
            assertNotNull(repository.byId(first.id))
            assertNotNull(repository.byId(second.id))
        }

    @Test
    fun aliasesAreTrimmedAndRepeatsDropped(): Unit =
        runBlocking {
            val saved = repository.create(draft(aliases = listOf(" chini ", "CHINI", "sugar", "")))

            assertEquals(listOf("chini", "sugar"), repository.byId(saved.id)!!.aliases)
        }

    @Test
    fun editingSavesTheChangeAndMovesTheRevisionForward(): Unit =
        runBlocking {
            val saved = repository.create(draft(name = "Coke", selling = 2000))
            clockNow = editedAt

            val result = repository.update(saved.id, saved.revision, draft(name = "Coke 500ml", selling = 2500))

            assertTrue(result is ProductWriteResult.Saved)
            val stored = repository.byId(saved.id)!!
            assertEquals("Coke 500ml", stored.name)
            assertEquals(Money(2500), stored.sellingPrice)
            assertEquals(2, stored.revision)
            assertEquals(editedAt, stored.updatedAt)
        }

    // D017: a write against a revision that has moved on is refused, not
    // silently applied over someone else's change.
    @Test
    fun editingFromAStaleRevisionIsRejectedAndChangesNothing(): Unit =
        runBlocking {
            val saved = repository.create(draft(name = "Coke", selling = 2000))
            repository.update(saved.id, saved.revision, draft(name = "Coke 500ml", selling = 2500))

            val stale = repository.update(saved.id, saved.revision, draft(name = "Wrong", selling = 9900))

            assertEquals(ProductWriteResult.Conflict, stale)
            val stored = repository.byId(saved.id)!!
            assertEquals("Coke 500ml", stored.name)
            assertEquals(Money(2500), stored.sellingPrice)
            assertEquals(2, stored.revision)
        }

    @Test
    fun editingSomethingThatIsNotThereReportsNotFound(): Unit =
        runBlocking {
            assertEquals(
                ProductWriteResult.NotFound,
                repository.update(EntityId("no-such-product"), 1, draft()),
            )
        }

    @Test
    fun deactivatingKeepsTheProductButTakesItOutOfTheEverydayList(): Unit =
        runBlocking {
            val saved = repository.create(draft(name = "Old stock"))

            val result = repository.setActive(saved.id, saved.revision, active = false)

            assertTrue(result is ProductWriteResult.Saved)
            assertTrue(visible().isEmpty())
            assertEquals(listOf("Old stock"), visible(includeInactive = true).map { it.name })
            assertEquals(2, repository.byId(saved.id)!!.revision)
        }

    @Test
    fun aDeactivatedProductCanComeBack(): Unit =
        runBlocking {
            val saved = repository.create(draft(name = "Seasonal"))
            val off = repository.setActive(saved.id, saved.revision, active = false) as ProductWriteResult.Saved

            repository.setActive(saved.id, off.revision, active = true)

            assertEquals(listOf("Seasonal"), visible().map { it.name })
        }

    @Test
    fun deactivatingFromAStaleRevisionIsRejected(): Unit =
        runBlocking {
            val saved = repository.create(draft(name = "Coke"))
            repository.update(saved.id, saved.revision, draft(name = "Coke 500ml"))

            assertEquals(
                ProductWriteResult.Conflict,
                repository.setActive(saved.id, saved.revision, active = false),
            )
            assertTrue(repository.byId(saved.id)!!.active)
        }

    @Test
    fun deletingLeavesATombstoneInsteadOfRemovingTheRow(): Unit =
        runBlocking {
            val saved = repository.create(draft(name = "Discontinued"))
            clockNow = editedAt

            val result = repository.delete(saved.id, saved.revision)

            assertTrue(result is ProductWriteResult.Saved)
            assertTrue(visible(includeInactive = true).isEmpty())
            val stored = repository.byId(saved.id)!!
            assertEquals(editedAt, stored.deletedAt)
            assertEquals(2, stored.revision)
        }

    @Test
    fun deletingFromAStaleRevisionIsRejected(): Unit =
        runBlocking {
            val saved = repository.create(draft(name = "Coke"))
            repository.update(saved.id, saved.revision, draft(name = "Coke 500ml"))

            assertEquals(ProductWriteResult.Conflict, repository.delete(saved.id, saved.revision))
            assertNull(repository.byId(saved.id)!!.deletedAt)
        }

    @Test
    fun purgeRemovesTheRowForGood(): Unit =
        runBlocking {
            val saved = repository.create(draft(name = "Temporary"))

            repository.purge(saved.id)

            assertNull(repository.byId(saved.id))
        }

    // What Step 26 relies on: the list is a live query, so a write shows up
    // without anything asking again.
    @Test
    fun theListUpdatesItselfWhenAProductIsAdded(): Unit =
        runBlocking {
            assertTrue(visible().isEmpty())

            repository.create(draft(name = "New arrival"))

            assertEquals(listOf("New arrival"), visible().map { it.name })
        }
}
