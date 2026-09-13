package com.hisab.app.data.product

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.domain.ProductUnits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Step 23 and Step 24. Runs against a real Room database held in memory, so
 * nothing is left behind on the phone when the tests finish.
 */
@RunWith(AndroidJUnit4::class)
class ProductDaoTest {
    private lateinit var database: HisabDatabase
    private lateinit var dao: ProductDao

    private val shopId = "shop-under-test"
    private val now = Instant.parse("2026-09-14T10:00:00Z")

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        dao = database.productDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun product(
        id: String,
        name: String,
        aliases: List<String> = emptyList(),
        active: Boolean = true,
        deletedAt: Instant? = null,
        purchase: Long? = null,
        selling: Long = 8500,
        shop: String = shopId,
    ) = ProductEntity(
        id = id,
        shopId = shop,
        name = name,
        aliases = aliases,
        unit = ProductUnits.KG,
        purchasePricePoisha = purchase,
        sellingPricePoisha = selling,
        active = active,
        revision = 1,
        updatedAt = now,
        deletedAt = deletedAt,
    )

    private suspend fun visible(
        query: String = "",
        includeInactive: Boolean = false,
    ) = dao.observe(shopId, query, includeInactive).first()

    // Step 23's check.
    @Test
    fun insertAndReadBackKeepsEveryField(): Unit =
        runBlocking {
            val sugar =
                product(
                    id = "p1",
                    name = "চিনি",
                    aliases = listOf("chini", "sugar"),
                    purchase = 8000,
                    selling = 8500,
                )
            dao.insert(sugar)

            val readBack = dao.byId("p1")

            assertEquals(sugar, readBack)
            assertEquals(listOf("chini", "sugar"), readBack?.aliases)
            assertEquals(8000L, readBack?.purchasePricePoisha)
            assertEquals(now, readBack?.updatedAt)
        }

    @Test
    fun aMissingPurchasePriceStaysNullRatherThanBecomingZero(): Unit =
        runBlocking {
            dao.insert(product(id = "p2", name = "Coke", purchase = null))

            assertNull(dao.byId("p2")?.purchasePricePoisha)
        }

    @Test
    fun updateChangesTheStoredRow(): Unit =
        runBlocking {
            dao.insert(product(id = "p3", name = "Coke", selling = 2000))

            dao.update(dao.byId("p3")!!.copy(name = "Coke 500ml", sellingPricePoisha = 2500, revision = 2))

            val updated = dao.byId("p3")!!
            assertEquals("Coke 500ml", updated.name)
            assertEquals(2500L, updated.sellingPricePoisha)
            assertEquals(2, updated.revision)
        }

    @Test
    fun onlyActiveProductsAreListedByDefault(): Unit =
        runBlocking {
            dao.insert(product(id = "p4", name = "Active one"))
            dao.insert(product(id = "p5", name = "Inactive one", active = false))

            assertEquals(listOf("Active one"), visible().map { it.name })
        }

    @Test
    fun inactiveProductsAppearWhenAskedForAndSortAfterActiveOnes(): Unit =
        runBlocking {
            dao.insert(product(id = "p6", name = "Bravo", active = false))
            dao.insert(product(id = "p7", name = "Alpha", active = true))

            assertEquals(
                listOf("Alpha", "Bravo"),
                visible(includeInactive = true).map { it.name },
            )
        }

    @Test
    fun deletedProductsNeverAppear(): Unit =
        runBlocking {
            dao.insert(product(id = "p8", name = "Gone", deletedAt = now))

            assertTrue(visible(includeInactive = true).isEmpty())
        }

    @Test
    fun anotherShopsProductsAreNeverListed(): Unit =
        runBlocking {
            dao.insert(product(id = "p9", name = "Mine"))
            dao.insert(product(id = "p10", name = "Theirs", shop = "another-shop"))

            assertEquals(listOf("Mine"), visible().map { it.name })
        }

    // Step 27, first half.
    @Test
    fun searchMatchesPartOfTheName(): Unit =
        runBlocking {
            dao.insert(product(id = "p11", name = "চিনি"))
            dao.insert(product(id = "p12", name = "Coke"))

            assertEquals(listOf("চিনি"), visible(query = "িন").map { it.name })
        }

    @Test
    fun searchIgnoresCaseForEnglishNames(): Unit =
        runBlocking {
            dao.insert(product(id = "p13", name = "Coke"))

            assertEquals(listOf("Coke"), visible(query = "coke").map { it.name })
            assertEquals(listOf("Coke"), visible(query = "COKE").map { it.name })
        }

    // Step 27, second half: the alias is the point of aliases.
    @Test
    fun searchMatchesAnAlias(): Unit =
        runBlocking {
            dao.insert(product(id = "p14", name = "চিনি", aliases = listOf("chini", "sugar")))
            dao.insert(product(id = "p15", name = "Coke"))

            assertEquals(listOf("চিনি"), visible(query = "sugar").map { it.name })
            assertEquals(listOf("চিনি"), visible(query = "chini").map { it.name })
        }

    @Test
    fun searchWithNoMatchFindsNothing(): Unit =
        runBlocking {
            dao.insert(product(id = "p16", name = "চিনি", aliases = listOf("chini")))

            assertTrue(visible(query = "biscuit").isEmpty())
        }

    @Test
    fun hardDeleteRemovesTheRowCompletely(): Unit =
        runBlocking {
            dao.insert(product(id = "p17", name = "Temporary"))

            dao.hardDelete("p17")

            assertNull(dao.byId("p17"))
            assertEquals(0, dao.countLive())
        }
}
