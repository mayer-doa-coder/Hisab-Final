package com.hisab.app.data.sync

import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.product.ProductRepository

/** What one sync run did, in numbers the user can be shown. */
data class SyncReport(
    val pushed: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val pulled: Int = 0,
    val cursor: Long = 0,
)

class NotSignedInException : IllegalStateException("no saved session, and no password given")

/**
 * One sync run: send what this phone changed, then take what the server has
 * (Steps 32 and 33).
 *
 * Sending comes first on purpose. Pulling first would overwrite a local edit
 * that has not been sent yet, and the whole point of the outbox is that no
 * confirmed change is lost.
 *
 * What this deliberately does not do yet: retry in the background, or on a
 * schedule. That is WorkManager, in M4. Here, a sync happens when someone
 * asks for one.
 */
class SyncEngine(
    private val database: HisabDatabase,
    private val api: SyncApi,
    private val settings: SyncSettings,
    shopId: String = LOCAL_SHOP_ID,
) {
    private val outbox = database.syncOutboxDao()
    private val metadata = database.syncMetadataDao()
    private val products = ProductRepository(database, shopId = shopId)

    suspend fun sync(password: String? = null): SyncReport {
        val token = signIn(password)

        var pushed = 0
        var conflicts = 0
        var rejected = 0

        val pending = outbox.withStatus(SyncOutboxEntity.STATUS_PENDING)
        if (pending.isNotEmpty()) {
            val byEventId = pending.associateBy { it.eventId }
            api.push(token, pending).forEach { result ->
                val queued = byEventId[result.eventId] ?: return@forEach
                when {
                    // Done with it: the server has the change, so the phone
                    // has nothing left to send for it.
                    result.isAccepted -> {
                        outbox.deleteByEventId(queued.eventId)
                        pushed += 1
                    }

                    // Refused because the product moved on. The row stays, so
                    // the change is not silently lost, and the pull below
                    // brings the version the server actually has.
                    result.status == PushResult.STATUS_CONFLICT -> {
                        outbox.update(
                            queued.copy(
                                status = SyncOutboxEntity.STATUS_CONFLICT,
                                retryCount = queued.retryCount + 1,
                            ),
                        )
                        conflicts += 1
                    }

                    else -> {
                        outbox.update(
                            queued.copy(
                                status = SyncOutboxEntity.STATUS_REJECTED,
                                retryCount = queued.retryCount + 1,
                            ),
                        )
                        rejected += 1
                    }
                }
            }
        }

        val lastCursor = metadata.get()?.lastServerCursor?.toLongOrNull() ?: 0L
        val pull = api.pull(token, lastCursor)
        pull.changes.forEach { change -> apply(change) }
        metadata.upsert(
            SyncMetadataEntity(deviceId = settings.deviceId, lastServerCursor = pull.cursor.toString()),
        )

        return SyncReport(
            pushed = pushed,
            conflicts = conflicts,
            rejected = rejected,
            pulled = pull.changes.size,
            cursor = pull.cursor,
        )
    }

    private suspend fun apply(change: PulledChange) {
        if (change.entityType != ProductRepository.ENTITY_TYPE_PRODUCT) return
        products.applyFromServer(
            ProductSyncPayload.fromJson(change.payload, change.entityId, LOCAL_SHOP_ID),
        )
    }

    private suspend fun signIn(password: String?): String {
        val saved = settings.token
        if (saved != null && password == null) return saved

        val email = settings.email
        if (password == null || email.isBlank()) throw NotSignedInException()

        return api.login(email, password).also { settings.token = it }
    }
}
