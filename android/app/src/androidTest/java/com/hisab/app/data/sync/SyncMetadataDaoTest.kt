package com.hisab.app.data.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hisab.app.data.HisabDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncMetadataDaoTest {
    private lateinit var db: HisabDatabase
    private lateinit var dao: SyncMetadataDao

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        dao = db.syncMetadataDao()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun noRowUntilSomethingIsStored() =
        runBlocking {
            assertNull(dao.get())
        }

    @Test
    fun upsertInsertsThenUpdatesTheSameRow() =
        runBlocking {
            dao.upsert(SyncMetadataEntity(deviceId = "device-1", lastServerCursor = null))
            assertNull(dao.get()?.lastServerCursor)

            dao.upsert(SyncMetadataEntity(deviceId = "device-1", lastServerCursor = "cursor-42"))

            val stored = dao.get()
            assertEquals("device-1", stored?.deviceId)
            assertEquals("cursor-42", stored?.lastServerCursor)
        }
}
