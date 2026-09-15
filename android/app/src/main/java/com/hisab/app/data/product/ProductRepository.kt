package com.hisab.app.data.product

import androidx.room.withTransaction
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.sync.ProductSyncPayload
import com.hisab.app.data.sync.SyncOutboxEntity
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.ProductUnits
import com.hisab.app.domain.RevisionCheckResult
import com.hisab.app.domain.checkRevision
import com.hisab.app.domain.generateId
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant

/** What the user typed, before it becomes a row. */
data class ProductDraft(
    val name: String,
    val aliases: List<String> = emptyList(),
    val unit: String = ProductUnits.DEFAULT,
    val purchasePrice: Money? = null,
    val sellingPrice: Money,
    val active: Boolean = true,
)

sealed interface ProductWriteResult {
    data class Saved(
        val id: EntityId,
        val revision: Int,
    ) : ProductWriteResult

    /** Someone else changed this product since it was read — never overwrite it silently (D017). */
    data object Conflict : ProductWriteResult

    data object NotFound : ProductWriteResult
}

/**
 * Every write to a product goes through here, so the rules live in one place:
 * a device-generated id (D018), a revision that starts at 1 and only moves
 * forward, a stale-revision write that is rejected instead of overwriting
 * (D017), and deletion as a tombstone rather than a removed row.
 *
 * Each write also queues a sync event in the same database transaction
 * (D003): either both the product change and the queued event are saved, or
 * neither is, so a change can never be made without something to send. The
 * sending itself happens later and elsewhere (SyncEngine) — nothing here
 * touches the network, which is why saving works the same offline.
 */
class ProductRepository(
    private val database: HisabDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val shopId: String = LOCAL_SHOP_ID,
) {
    private val products = database.productDao()
    private val outbox = database.syncOutboxDao()

    fun observe(
        query: String = "",
        includeInactive: Boolean = false,
    ): Flow<List<ProductEntity>> = products.observe(shopId, query.trim(), includeInactive)

    suspend fun byId(id: EntityId): ProductEntity? = products.byId(id.value)

    suspend fun create(draft: ProductDraft): ProductWriteResult.Saved {
        val id = generateId()
        val product =
            ProductEntity(
                id = id.value,
                shopId = shopId,
                name = draft.name.trim(),
                aliases = cleanAliases(draft.aliases),
                unit = draft.unit,
                purchasePricePoisha = draft.purchasePrice?.minorUnits,
                sellingPricePoisha = draft.sellingPrice.minorUnits,
                active = draft.active,
                revision = FIRST_REVISION,
                updatedAt = now(),
                deletedAt = null,
            )

        database.withTransaction {
            products.insert(product)
            outbox.insert(queued(product, OPERATION_CREATE, baseRevision = null))
        }
        return ProductWriteResult.Saved(id, FIRST_REVISION)
    }

    suspend fun update(
        id: EntityId,
        baseRevision: Int,
        draft: ProductDraft,
    ): ProductWriteResult =
        write(id, baseRevision, OPERATION_UPDATE) { current ->
            current.copy(
                name = draft.name.trim(),
                aliases = cleanAliases(draft.aliases),
                unit = draft.unit,
                purchasePricePoisha = draft.purchasePrice?.minorUnits,
                sellingPricePoisha = draft.sellingPrice.minorUnits,
                active = draft.active,
            )
        }

    /** Deactivating keeps the product and its history; it just stops appearing in day-to-day lists. */
    suspend fun setActive(
        id: EntityId,
        baseRevision: Int,
        active: Boolean,
    ): ProductWriteResult = write(id, baseRevision, OPERATION_UPDATE) { current -> current.copy(active = active) }

    /** A tombstone, not a removed row, so the deletion can sync like any other change (D017). */
    suspend fun delete(
        id: EntityId,
        baseRevision: Int,
    ): ProductWriteResult {
        val deletedAt = now()
        return write(id, baseRevision, OPERATION_DELETE, timestamp = deletedAt) { current ->
            current.copy(deletedAt = deletedAt)
        }
    }

    /**
     * Saves a product the server sent (Step 33). No sync event is queued: this
     * change came from the server, so sending it back would be an echo. The
     * server's revision is kept, which is what later edits are checked against.
     */
    suspend fun applyFromServer(product: ProductEntity) {
        products.upsert(product.copy(shopId = shopId))
    }

    /** Really removes a row. Used to clean up after tests, and later to purge synced tombstones. */
    suspend fun purge(id: EntityId) = products.hardDelete(id.value)

    private suspend fun write(
        id: EntityId,
        baseRevision: Int,
        operation: String,
        timestamp: Instant = now(),
        change: (ProductEntity) -> ProductEntity,
    ): ProductWriteResult {
        val current = products.byId(id.value) ?: return ProductWriteResult.NotFound
        if (checkRevision(current.revision, baseRevision) !is RevisionCheckResult.Ok) {
            return ProductWriteResult.Conflict
        }

        val next = change(current).copy(revision = current.revision + 1, updatedAt = timestamp)
        database.withTransaction {
            products.update(next)
            outbox.insert(queued(next, operation, baseRevision = current.revision, at = timestamp))
        }
        return ProductWriteResult.Saved(id, next.revision)
    }

    /**
     * The change, ready to send. `baseRevision` is the revision the edit was
     * made from — the server refuses it if the product has moved on since
     * (D017).
     */
    private fun queued(
        product: ProductEntity,
        operation: String,
        baseRevision: Int?,
        at: Instant = now(),
    ) = SyncOutboxEntity(
        eventId = generateId().value,
        entityType = ENTITY_TYPE_PRODUCT,
        entityId = product.id,
        operation = operation,
        payload = ProductSyncPayload.toJson(product),
        baseRevision = baseRevision,
        clientTimestamp = at,
    )

    private fun now(): Instant = clock.instant()

    private fun cleanAliases(aliases: List<String>): List<String> =
        aliases
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    companion object {
        const val FIRST_REVISION = 1
        const val ENTITY_TYPE_PRODUCT = "Product"
        const val OPERATION_CREATE = "create"
        const val OPERATION_UPDATE = "update"
        const val OPERATION_DELETE = "delete"
    }
}
