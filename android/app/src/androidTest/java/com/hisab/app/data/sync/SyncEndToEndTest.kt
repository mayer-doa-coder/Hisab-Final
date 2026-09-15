package com.hisab.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.product.ProductDraft
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Steps 32, 33 and 34 for real: this phone, over HTTP, against the actual
 * server and its Postgres. Nothing is faked.
 *
 * It needs the development server running and reachable from the phone:
 *
 *     cd server && npm start
 *     adb reverse tcp:3000 tcp:3000
 *
 * Without that, every test here skips itself rather than failing, because a
 * missing server is not a broken app. CI has no phone, so CI never runs it.
 */
@RunWith(AndroidJUnit4::class)
class SyncEndToEndTest {
    private val baseUrl = "http://127.0.0.1:3000"
    private val email = "rahim@example.com"
    private val password = "correct-horse-1"

    /** Marks every row this test makes on the server, so they can be told apart. */
    private val marker = "E2E-${UUID.randomUUID().toString().take(8)}"

    private lateinit var database: HisabDatabase
    private lateinit var repository: ProductRepository
    private lateinit var settings: SyncSettings
    private lateinit var api: HttpSyncApi
    private var token: String = ""

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("hisab_sync", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        api = HttpSyncApi(baseUrl)
        token =
            runBlocking {
                try {
                    api.login(email, password)
                } catch (error: Exception) {
                    Assume.assumeNoException(
                        "needs the development server (npm start) reachable from the phone " +
                            "(adb reverse tcp:3000 tcp:3000)",
                        error,
                    )
                    ""
                }
            }

        database = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        repository = ProductRepository(database)
        settings = SyncSettings(context)
        settings.serverUrl = baseUrl
        settings.email = email
        settings.token = token
    }

    @After
    fun tearDown() {
        if (this::database.isInitialized) database.close()
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("hisab_sync", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun engine() = SyncEngine(database, api, settings)

    private fun serverCall(
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun serverProductsNamed(name: String): JSONArray =
        serverCall("GET", "/products?q=${name.replace(" ", "%20")}&includeInactive=true")
            .getJSONArray("products")

    // Step 32: added on the phone (with no server involved at the time), then
    // sent when a sync happens.
    @Test
    fun aProductAddedOnThePhoneReachesTheServer(): Unit =
        runBlocking {
            val name = "$marker sugar"
            val saved = repository.create(ProductDraft(name = name, sellingPrice = Money(8500)))

            // Before syncing, it is only here, waiting to be sent.
            assertEquals(1, database.syncOutboxDao().all().size)
            assertEquals(0, serverProductsNamed(name).length())

            val report = engine().sync()

            assertEquals(1, report.pushed)
            assertTrue(database.syncOutboxDao().all().isEmpty())
            val onServer = serverProductsNamed(name)
            assertEquals(1, onServer.length())
            assertEquals(saved.id.value, onServer.getJSONObject(0).getString("id"))
            assertEquals(8500, onServer.getJSONObject(0).getInt("sellingPricePoisha"))
        }

    // Step 33: made straight on the server, and the phone learns about it.
    @Test
    fun aProductMadeOnTheServerReachesThePhone(): Unit =
        runBlocking {
            val id = UUID.randomUUID().toString()
            val name = "$marker server made"
            serverCall(
                "POST",
                "/products",
                JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("unit", "kg")
                    .put("sellingPricePoisha", 12000),
            )

            val report = engine().sync()

            assertTrue(report.pulled > 0)
            val onPhone = repository.byId(EntityId(id))
            assertNotNull(onPhone)
            assertEquals(name, onPhone!!.name)
            assertEquals(12000L, onPhone.sellingPricePoisha)
            assertEquals("kg", onPhone.unit)
        }

    // Step 34: two devices edit the same product from the same revision while
    // offline, then both sync. The second one to arrive must be refused.
    @Test
    fun theSecondDeviceToSyncTheSameEditIsRefused(): Unit =
        runBlocking {
            val name = "$marker contested"
            val saved = repository.create(ProductDraft(name = name, sellingPrice = Money(2000)))
            engine().sync()

            // The other device gets there first, editing from revision 1.
            serverCall(
                "PUT",
                "/products/${saved.id.value}",
                JSONObject()
                    .put("baseRevision", 1)
                    .put("name", "$name from the other phone")
                    .put("aliases", JSONArray())
                    .put("unit", "piece")
                    .put("sellingPricePoisha", 3000)
                    .put("purchasePricePoisha", JSONObject.NULL)
                    .put("active", true),
            )

            // This phone still thinks the product is at revision 1.
            repository.update(
                saved.id,
                baseRevision = 1,
                draft = ProductDraft(name = "$name from this phone", sellingPrice = Money(4000)),
            )

            val report = engine().sync()

            // Refused, not applied over the other device's change.
            assertEquals(1, report.conflicts)
            assertEquals(0, report.pushed)
            val queued = database.syncOutboxDao().all().single()
            assertEquals(SyncOutboxEntity.STATUS_CONFLICT, queued.status)

            // The server still has the first edit...
            val onServer = serverProductsNamed(name).getJSONObject(0)
            assertEquals("$name from the other phone", onServer.getString("name"))
            assertEquals(2, onServer.getInt("revision"))

            // ...and the same sync brought that version back to the phone.
            val onPhone = repository.byId(saved.id)!!
            assertEquals("$name from the other phone", onPhone.name)
            assertEquals(2, onPhone.revision)
        }
}
