package com.hisab.app.data.sync

import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.customer.CustomerRepository
import com.hisab.app.data.product.ProductRepository
import com.hisab.app.data.sale.SaleRepository
import com.hisab.app.data.stock.StockRepository

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
 * (Steps 32, 33 and 47) — products, customers, sales and stock movements.
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
    private val customers = CustomerRepository(database, shopId = shopId)
    private val sales = SaleRepository(database, shopId = shopId)
    private val stock = StockRepository(database, shopId = shopId)

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

        // The server hands changes back a page at a time, so keep asking from
        // the new cursor until a page brings nothing new. Stopping after one
        // page would leave a phone that was offline for a busy week quietly
        // missing everything past the first page. The cursor is saved after
        // every page, so a sync cut off half way resumes where it stopped
        // instead of starting over.
        var cursor = metadata.get()?.lastServerCursor?.toLongOrNull() ?: 0L
        var pulled = 0
        var pages = 0
        while (pages < MAX_PAGES_PER_SYNC) {
            pages += 1
            val pull = api.pull(token, cursor)
            pull.changes.forEach { change -> apply(change) }
            pulled += pull.changes.size

            val advanced = pull.cursor > cursor
            if (advanced) {
                cursor = pull.cursor
                metadata.upsert(SyncMetadataEntity(deviceId = settings.deviceId, lastServerCursor = cursor.toString()))
            }
            if (pull.changes.isEmpty() || !advanced) break
        }

        return SyncReport(
            pushed = pushed,
            conflicts = conflicts,
            rejected = rejected,
            pulled = pulled,
            cursor = cursor,
        )
    }

    private suspend fun apply(change: PulledChange) {
        when (change.entityType) {
            ProductRepository.ENTITY_TYPE_PRODUCT -> {
                products.applyFromServer(ProductSyncPayload.fromJson(change.payload, change.entityId, LOCAL_SHOP_ID))
            }

            CustomerRepository.ENTITY_TYPE_CUSTOMER -> {
                customers.applyFromServer(CustomerSyncPayload.fromJson(change.payload, change.entityId, LOCAL_SHOP_ID))
            }

            SaleRepository.ENTITY_TYPE_SALE -> {
                sales.applyFromServer(SaleSyncPayload.fromJson(change.payload, LOCAL_SHOP_ID))
            }

            StockRepository.ENTITY_TYPE_STOCK_MOVEMENT -> {
                stock.applyFromServer(StockMovementSyncPayload.fromJson(change.payload))
            }

            // A kind of change this version of the app does not know yet — a
            // newer server. Skipped rather than failing the whole sync.
            else -> {}
        }
    }

    private suspend fun signIn(password: String?): String {
        val saved = settings.token
        if (saved != null && password == null) return saved

        val email = settings.email
        if (password == null || email.isBlank()) throw NotSignedInException()

        return api.login(email, password).also { settings.token = it }
    }

    private companion object {
        /**
         * A ceiling on pages in one sync, so a server that kept moving its
         * cursor forever could not keep the phone busy forever. At the server's
         * 500 changes a page, this is 100,000 changes — far more than a shop
         * makes between two syncs.
         */
        const val MAX_PAGES_PER_SYNC = 200
    }
}
