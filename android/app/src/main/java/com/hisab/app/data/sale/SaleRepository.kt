package com.hisab.app.data.sale

import androidx.room.withTransaction
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.baki.toDomain
import com.hisab.app.data.baki.toEntity
import com.hisab.app.data.customer.CustomerEntity
import com.hisab.app.data.stock.toDomain
import com.hisab.app.data.stock.toEntity
import com.hisab.app.data.sync.SaleSyncPayload
import com.hisab.app.data.sync.SyncOutboxEntity
import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.SaleTransaction
import com.hisab.app.domain.StockMovementType
import com.hisab.app.domain.completeCashSale
import com.hisab.app.domain.completeCreditSale
import com.hisab.app.domain.generateId
import com.hisab.app.domain.reverseSale
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate

/** What happened when a sale was asked to be reversed (Step 43). */
sealed interface ReversalResult {
    data class Reversed(
        val transaction: SaleTransaction,
    ) : ReversalResult

    /** Someone already reversed it — on this phone, or on another one whose reversal arrived by sync. */
    data object AlreadyReversed : ReversalResult

    /** Undoing an undo is a new sale, not a reversal (D033). */
    data object IsAReversal : ReversalResult

    data object NotFound : ReversalResult
}

/** Everything the sale detail screen shows about one sale. */
data class SaleDetail(
    val sale: SaleEntity,
    val lines: List<SaleItemWithProduct>,
    val customer: CustomerEntity?,
    /** The sale that undid this one, if any. */
    val reversedBy: SaleEntity?,
    /** If this is itself a reversal, the sale it undid. */
    val reverses: SaleEntity?,
)

/**
 * Recording sales (Steps 39 and 40) and undoing them (Steps 43 and 44).
 *
 * The rules live in `domain/Sale.kt`; this only puts what they produce into
 * the database. That split is what lets every rule be tested without a phone,
 * and it is why this class has so little arithmetic in it — a sale's total is
 * never recomputed here, only stored.
 *
 * D021 is enforced twice over. `SaleTransaction` refuses to exist unless the
 * sale, its lines, its stock movements and (on credit) its baki entry all
 * agree; and one `withTransaction` writes all of them, together with the sync
 * event that will carry them to the server (D003). There is no order of
 * failures that leaves stock reduced with nothing owed, a sale with no stock
 * movement, or a change with nothing queued to send.
 *
 * Nothing here touches the network, which is what makes a sale — and its
 * reversal — work the same in airplane mode.
 */
class SaleRepository(
    private val database: HisabDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val shopId: String = LOCAL_SHOP_ID,
) {
    private val sales = database.saleDao()
    private val movements = database.stockMovementDao()
    private val baki = database.bakiEntryDao()
    private val customers = database.customerDao()
    private val outbox = database.syncOutboxDao()

    /** Newest first, for the history screen (Step 42). */
    fun observeRecent(limit: Int = HISTORY_LIMIT): Flow<List<SaleSummary>> = sales.observeRecentSummaries(shopId, limit)

    suspend fun byId(id: EntityId): SaleEntity? = sales.byId(id.value)

    suspend fun itemsFor(id: EntityId): List<SaleItemEntity> = sales.itemsFor(id.value)

    /** A cash sale: stock goes out, nothing is owed. */
    suspend fun recordCashSale(lines: List<SaleLine>): SaleTransaction {
        val transaction = completeCashSale(shopId, lines, clock.instant())
        save(transaction)
        return transaction
    }

    /** A credit sale: stock goes out and the customer owes the total (D021). */
    suspend fun recordCreditSale(
        customerId: EntityId,
        lines: List<SaleLine>,
        dueDate: LocalDate? = null,
    ): SaleTransaction {
        val transaction = completeCreditSale(shopId, customerId, lines, clock.instant(), dueDate)
        save(transaction)
        return transaction
    }

    /**
     * Undoes a sale by recording its opposite (Step 43): the stock comes back,
     * and for a credit sale the customer's baki goes down by exactly what the
     * sale added — all in one transaction, or not at all (Step 44, D021).
     *
     * The "has this already been undone?" check runs inside the same
     * transaction as the write, so tapping Reverse twice — or reversing on a
     * slow phone while the screen is still catching up — cannot undo a sale
     * twice.
     */
    suspend fun reverse(saleId: EntityId): ReversalResult =
        database.withTransaction {
            val original = transactionOf(saleId) ?: return@withTransaction ReversalResult.NotFound
            if (original.sale.reversesSaleId != null) return@withTransaction ReversalResult.IsAReversal
            if (sales.reversalOf(saleId.value) != null) return@withTransaction ReversalResult.AlreadyReversed

            val reversal = reverseSale(original, clock.instant())
            writeWithEvent(reversal)
            ReversalResult.Reversed(reversal)
        }

    /** Everything the detail screen needs about one sale, or null if there is no such sale. */
    suspend fun detail(saleId: EntityId): SaleDetail? {
        val sale = sales.byId(saleId.value) ?: return null
        return SaleDetail(
            sale = sale,
            lines = sales.itemsWithProductFor(sale.id),
            customer = sale.customerId?.let { customers.byId(it) },
            reversedBy = sales.reversalOf(sale.id),
            reverses = sale.reversesSaleId?.let { sales.byId(it) },
        )
    }

    /**
     * A stored sale rebuilt as the domain value it was made from: the sale,
     * its lines, the stock movements it caused and (on credit) its baki entry.
     *
     * A sale's own movements and entry are found by what they point at. An
     * ordinary sale's `sale` movements and `credit_sale` entry reference the
     * sale itself; a reversal's `return` movements and `reversal` entry
     * reference the sale it undid — which is exactly how `reverseSale` wrote
     * them.
     */
    suspend fun transactionOf(saleId: EntityId): SaleTransaction? {
        val sale = sales.byId(saleId.value) ?: return null
        val reversal = sale.reversesSaleId != null
        val reference = sale.reversesSaleId ?: sale.id
        val movementType = if (reversal) StockMovementType.RETURN else StockMovementType.SALE
        val bakiType = if (reversal) BakiEntryType.REVERSAL else BakiEntryType.CREDIT_SALE

        return SaleTransaction(
            sale = sale.toDomain(),
            items = sales.itemsFor(sale.id).map { it.toDomain() },
            stockMovements =
                movements
                    .forReference(reference)
                    .filter { it.movementType == movementType.name }
                    .map { it.toDomain() },
            bakiEntry =
                baki
                    .forReference(reference)
                    .firstOrNull { it.entryType == bakiType.name }
                    ?.toDomain(),
        )
    }

    /**
     * Stores a sale the server sent (Step 47), or — when it is one this phone
     * made — notes that the server now has it.
     *
     * No sync event is queued: the change came from the server, so sending it
     * back would be an echo.
     */
    suspend fun applyFromServer(transaction: SaleTransaction) {
        database.withTransaction {
            if (sales.byId(transaction.sale.id.value) == null) {
                write(transaction)
                return@withTransaction
            }
            val receivedAt = transaction.sale.time.serverReceivedAt ?: return@withTransaction
            sales.markServerReceived(transaction.sale.id.value, receivedAt)
            transaction.stockMovements.forEach { movements.markServerReceived(it.id.value, receivedAt) }
            transaction.bakiEntry?.let { baki.markServerReceived(it.id.value, receivedAt) }
        }
    }

    /**
     * Writes everything one sale caused, together with the sync event that
     * carries it to the server, in one database transaction (D003, D021).
     *
     * `internal` so on-phone tests can hand it a transaction whose write is
     * made to fail part way, and check that nothing at all was kept (Step 44).
     */
    internal suspend fun save(transaction: SaleTransaction) {
        database.withTransaction { writeWithEvent(transaction) }
    }

    private suspend fun writeWithEvent(transaction: SaleTransaction) {
        write(transaction)
        outbox.insert(
            SyncOutboxEntity(
                eventId = generateId().value,
                entityType = ENTITY_TYPE_SALE,
                entityId = transaction.sale.id.value,
                // A sale is never edited or deleted, so it only ever travels
                // as a create — a reversal included, which is a new sale.
                operation = OPERATION_CREATE,
                payload = SaleSyncPayload.toJson(transaction),
                baseRevision = null,
                clientTimestamp = transaction.sale.time.occurredAt,
            ),
        )
    }

    private suspend fun write(transaction: SaleTransaction) {
        sales.insert(transaction.sale.toEntity())
        sales.insertItems(transaction.items.map { it.toEntity() })
        movements.insertAll(transaction.stockMovements.map { it.toEntity() })
        transaction.bakiEntry?.let { baki.insert(it.toEntity()) }
    }

    companion object {
        const val HISTORY_LIMIT = 100
        const val ENTITY_TYPE_SALE = "Sale"
        const val OPERATION_CREATE = "create"
    }
}
