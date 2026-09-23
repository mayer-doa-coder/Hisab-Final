package com.hisab.app.data.stock

import androidx.room.withTransaction
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.sync.StockMovementSyncPayload
import com.hisab.app.data.sync.SyncOutboxEntity
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.StockMovement
import com.hisab.app.domain.StockMovementType
import com.hisab.app.domain.correctStock
import com.hisab.app.domain.damage
import com.hisab.app.domain.generateId
import com.hisab.app.domain.restock
import kotlinx.coroutines.flow.Flow
import java.time.Clock

/**
 * Reading stock and changing it by hand (Step 41): a delivery arriving, goods
 * lost or broken, and a shelf count.
 *
 * Every read here is a `SUM` over the movement ledger, never a stored number
 * (D002, D020). That is why a change shows on screen immediately with nothing
 * to refresh: the row that was just inserted is part of the sum the screen is
 * already watching.
 *
 * Every write queues a sync event in the same database transaction as the
 * movement (D003), so a stock change can never exist with nothing waiting to
 * send it.
 *
 * Sales write their own movements as part of one sale transaction
 * (`SaleRepository`), because a movement without its sale would be a stock
 * change nobody can explain.
 */
class StockRepository(
    private val database: HisabDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val shopId: String = LOCAL_SHOP_ID,
) {
    private val movements = database.stockMovementDao()
    private val outbox = database.syncOutboxDao()

    /** Every product with the stock its movements add up to (Step 41). */
    fun observeProductsWithStock(
        query: String = "",
        includeInactive: Boolean = false,
    ): Flow<List<ProductWithStock>> = movements.observeProductsWithStock(shopId, query.trim(), includeInactive)

    suspend fun currentStock(productId: EntityId): Quantity = Quantity(movements.currentStockScaled(productId.value))

    suspend fun historyFor(productId: EntityId): List<StockMovementEntity> = movements.forProduct(productId.value)

    /**
     * Everything that moved stock other than a sale, newest first, for the
     * history screen (Step 42). A sale's own movement is left out because the
     * sale itself is already listed — showing both would be the same event
     * twice.
     */
    fun observeRecentMovements(limit: Int = HISTORY_LIMIT): Flow<List<MovementWithProduct>> =
        movements.observeRecentMovements(StockMovementType.SALE.name, limit)

    /** Goods arriving from a supplier. `note` is free text the shopkeeper may leave blank. */
    suspend fun restock(
        productId: EntityId,
        quantity: Quantity,
        note: String? = null,
    ): StockMovement =
        save(
            restock(
                productId = productId,
                quantity = quantity,
                occurredAt = clock.instant(),
                sourceReference = cleanNote(note),
            ),
        )

    /** Goods lost, broken or expired. `quantity` is how much was lost, as a positive amount. */
    suspend fun damage(
        productId: EntityId,
        quantity: Quantity,
        note: String? = null,
    ): StockMovement =
        save(
            damage(
                productId = productId,
                quantity = quantity,
                occurredAt = clock.instant(),
                sourceReference = cleanNote(note),
            ),
        )

    /**
     * A shelf count: `counted` is what is actually there, and the movement
     * records the difference from what the ledger said (`correctStock`).
     *
     * The ledger is read inside the same transaction as the write, so a sale
     * saved at the same moment cannot slip in between the reading and the
     * correction and leave the count wrong.
     */
    suspend fun count(
        productId: EntityId,
        counted: Quantity,
        note: String? = null,
    ): StockMovement =
        database.withTransaction {
            val movement =
                correctStock(
                    productId = productId,
                    countedQuantity = counted,
                    recordedQuantity = Quantity(movements.currentStockScaled(productId.value)),
                    occurredAt = clock.instant(),
                    sourceReference = cleanNote(note),
                )
            writeWithEvent(movement)
            movement
        }

    /**
     * Stores a stand-alone movement the server sent (Step 47), or — when it is
     * one this phone made — notes that the server now has it. No sync event is
     * queued: sending it back would be an echo.
     */
    suspend fun applyFromServer(movement: StockMovement) {
        database.withTransaction {
            if (movements.byId(movement.id.value) == null) {
                movements.insert(movement.toEntity())
            } else {
                movement.time.serverReceivedAt?.let { movements.markServerReceived(movement.id.value, it) }
            }
        }
    }

    private suspend fun save(movement: StockMovement): StockMovement {
        database.withTransaction { writeWithEvent(movement) }
        return movement
    }

    private suspend fun writeWithEvent(movement: StockMovement) {
        movements.insert(movement.toEntity())
        outbox.insert(
            SyncOutboxEntity(
                eventId = generateId().value,
                entityType = ENTITY_TYPE_STOCK_MOVEMENT,
                entityId = movement.id.value,
                operation = OPERATION_CREATE,
                payload = StockMovementSyncPayload.toJson(movement),
                baseRevision = null,
                clientTimestamp = movement.time.occurredAt,
            ),
        )
    }

    private fun cleanNote(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val HISTORY_LIMIT = 100
        const val ENTITY_TYPE_STOCK_MOVEMENT = "StockMovement"
        const val OPERATION_CREATE = "create"
    }
}
