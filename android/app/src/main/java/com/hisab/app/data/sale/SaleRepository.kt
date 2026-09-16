package com.hisab.app.data.sale

import androidx.room.withTransaction
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.baki.toEntity
import com.hisab.app.data.stock.toEntity
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.SaleTransaction
import com.hisab.app.domain.completeCashSale
import com.hisab.app.domain.completeCreditSale
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate

/**
 * Recording sales (Steps 39 and 40).
 *
 * The rules live in `domain/Sale.kt`; this only puts what they produce into
 * the database. That split is what lets every rule be tested without a phone,
 * and it is why this class has so little arithmetic in it — a sale's total is
 * never recomputed here, only stored.
 *
 * D021 is enforced twice over. `SaleTransaction` refuses to exist unless the
 * sale, its lines, its stock movements and (on credit) its baki entry all
 * agree; and `withTransaction` writes all of them or none. There is no order
 * of failures that leaves stock reduced with nothing owed, or a sale with no
 * stock movement.
 *
 * Nothing here touches the network, which is what makes a sale work the same
 * in airplane mode.
 */
class SaleRepository(
    private val database: HisabDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val shopId: String = LOCAL_SHOP_ID,
) {
    private val sales = database.saleDao()
    private val movements = database.stockMovementDao()
    private val baki = database.bakiEntryDao()

    /** Newest first, for the history screen (Step 42). */
    fun observeRecent(limit: Int = HISTORY_LIMIT): Flow<List<SaleSummary>> = sales.observeRecentSummaries(shopId, limit)

    suspend fun byId(id: EntityId): SaleEntity? = sales.byId(id.value)

    suspend fun itemsFor(id: EntityId): List<SaleItemEntity> = sales.itemsFor(id.value)

    /** A cash sale: stock goes out, nothing is owed. */
    suspend fun recordCashSale(lines: List<SaleLine>): SaleTransaction = record(completeCashSale(shopId, lines, clock.instant()))

    /** A credit sale: stock goes out and the customer owes the total (D021). */
    suspend fun recordCreditSale(
        customerId: EntityId,
        lines: List<SaleLine>,
        dueDate: LocalDate? = null,
    ): SaleTransaction = record(completeCreditSale(shopId, customerId, lines, clock.instant(), dueDate))

    /**
     * Writes everything one sale caused, in one database transaction.
     *
     * No sync event is queued yet, on purpose: the backend has no sale
     * endpoints until Step 46, and `SyncEngine` marks an event the server
     * refuses as REJECTED and never retries it — so queueing now would turn
     * every sale into permanent rubbish in the outbox and make the "waiting
     * to send" count on the Sync screen a lie. The queueing goes in with the
     * endpoints (Steps 46–47), in this one method.
     */
    private suspend fun record(transaction: SaleTransaction): SaleTransaction {
        database.withTransaction {
            sales.insert(transaction.sale.toEntity())
            sales.insertItems(transaction.items.map { it.toEntity() })
            movements.insertAll(transaction.stockMovements.map { it.toEntity() })
            transaction.bakiEntry?.let { baki.insert(it.toEntity()) }
        }
        return transaction
    }

    companion object {
        const val HISTORY_LIMIT = 100
    }
}
