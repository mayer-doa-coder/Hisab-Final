package com.hisab.app.data.stock

import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.StockMovement
import com.hisab.app.domain.StockMovementType
import com.hisab.app.domain.restock
import kotlinx.coroutines.flow.Flow
import java.time.Clock

/**
 * Reading stock and adding to it (Step 41).
 *
 * Every read here is a `SUM` over the movement ledger, never a stored number
 * (D002, D020). That is why restocking shows on screen immediately with
 * nothing to refresh: the row that was just inserted is part of the sum the
 * screen is already watching.
 *
 * Only restock writes through here. Sales write their own movements as part
 * of one sale transaction (`SaleRepository`), because a movement without its
 * sale would be a stock change nobody can explain.
 */
class StockRepository(
    private val database: HisabDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val shopId: String = LOCAL_SHOP_ID,
) {
    private val movements = database.stockMovementDao()

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
    ): StockMovement {
        val movement =
            restock(
                productId = productId,
                quantity = quantity,
                occurredAt = clock.instant(),
                sourceReference = note?.trim()?.takeIf { it.isNotEmpty() },
            )
        // No sync event yet — see the note in SaleRepository.record; the
        // backend has no stock endpoints until Step 46.
        movements.insert(movement.toEntity())
        return movement
    }

    companion object {
        const val HISTORY_LIMIT = 100
    }
}
