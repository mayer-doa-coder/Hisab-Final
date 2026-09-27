package com.hisab.app.data.baki

import androidx.room.withTransaction
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.sync.BakiEntrySyncPayload
import com.hisab.app.data.sync.SyncOutboxEntity
import com.hisab.app.domain.BakiEntry
import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.addCredit
import com.hisab.app.domain.generateId
import com.hisab.app.domain.receivePayment
import com.hisab.app.domain.reverseEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate

/** What undoing a hand-written baki entry came to (Step 57). */
sealed interface BakiReverseResult {
    /** The opposite entry was written; the original is untouched. */
    data class Reversed(
        val original: BakiEntry,
        val reversal: BakiEntry,
    ) : BakiReverseResult

    data object NotFound : BakiReverseResult

    /**
     * Only a hand-written credit or payment is undone here. A credit sale's baki
     * is undone with its sale and stock, together (D021), and a reversal is never
     * itself reversed.
     */
    data object NotReversible : BakiReverseResult

    /** An opposite entry for this one is already stored, so a second would count it twice. */
    data object AlreadyReversed : BakiReverseResult
}

/**
 * Reading and writing a customer's baki ledger (Steps 52–58).
 *
 * Every number a screen shows about what someone owes comes from the entries
 * this returns, through `calculateBalance` and `overdueAmount` in `Baki.kt`.
 * There is no balance to read or to edit here (D001), which is why a payment
 * shows up the moment it is saved: the list a screen already watches gets one
 * more entry.
 *
 * Writing goes through the domain functions, so the sign of an entry and its
 * type are decided in one place and tested there. Every write queues a sync
 * event in the same database transaction as the entry (D003), including an
 * undo — an undo is a new entry, so sync-wise it is just another creation
 * (D043).
 */
class BakiRepository(
    private val database: HisabDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val entries = database.bakiEntryDao()
    private val outbox = database.syncOutboxDao()

    /** Every entry of every customer, newest first, as a live query. */
    fun observeAll(): Flow<List<BakiEntry>> = entries.observeAll().map { rows -> rows.map { it.toDomain() } }

    /** One customer's entries, newest first, as a live query. */
    fun observeForCustomer(customerId: EntityId): Flow<List<BakiEntry>> =
        entries.observeForCustomer(customerId.value).map { rows -> rows.map { it.toDomain() } }

    /**
     * The customer owes [amount] more, with no sale behind it. [amount] is a
     * positive amount; the domain refuses anything else.
     */
    suspend fun addCredit(
        customerId: EntityId,
        amount: Money,
        dueDate: LocalDate?,
    ): BakiEntry = save(addCredit(customerId, amount, clock.instant(), dueDate))

    /** The customer paid [amount] back. [amount] is a positive amount; the entry stores it as a reduction. */
    suspend fun receivePayment(
        customerId: EntityId,
        amount: Money,
    ): BakiEntry = save(receivePayment(customerId, amount, clock.instant()))

    /**
     * Undoes a hand-written credit or payment by writing its opposite, never by
     * deleting or editing it (PRD section 10, CLAUDE.md). The original stays in
     * the ledger and the new entry references it, so both are history and the
     * balance is right again.
     *
     * "Has this already been undone?" is a question about stored rows, so it is
     * asked here, in the same transaction as the write (D041). Two taps, or a tap
     * while the first is still being written, therefore cannot each add an
     * opposite entry: the second finds the first and answers
     * [BakiReverseResult.AlreadyReversed]. Nothing is written in that case. The
     * same question between two *devices* is the server's to answer, once this
     * reaches it (D043).
     */
    suspend fun reverse(entryId: EntityId): BakiReverseResult =
        database.withTransaction {
            val row = entries.byId(entryId.value) ?: return@withTransaction BakiReverseResult.NotFound
            val original = row.toDomain()

            if (original.type != BakiEntryType.CREDIT && original.type != BakiEntryType.PAYMENT) {
                return@withTransaction BakiReverseResult.NotReversible
            }

            val alreadyUndone =
                entries.forReference(entryId.value).any { it.entryType == BakiEntryType.ENTRY_REVERSAL.name }
            if (alreadyUndone) return@withTransaction BakiReverseResult.AlreadyReversed

            val reversal = reverseEntry(original, clock.instant())
            writeWithEvent(reversal)
            BakiReverseResult.Reversed(original, reversal)
        }

    /**
     * Stores a hand-written entry the server sent (Step 58), or — when it is
     * one this phone made — notes that the server now has it. No sync event is
     * queued: sending it back would be an echo.
     */
    suspend fun applyFromServer(entry: BakiEntry) {
        database.withTransaction {
            if (entries.byId(entry.id.value) == null) {
                entries.insert(entry.toEntity())
            } else {
                entry.time.serverReceivedAt?.let { entries.markServerReceived(entry.id.value, it) }
            }
        }
    }

    private suspend fun save(entry: BakiEntry): BakiEntry {
        database.withTransaction { writeWithEvent(entry) }
        return entry
    }

    private suspend fun writeWithEvent(entry: BakiEntry) {
        entries.insert(entry.toEntity())
        outbox.insert(
            SyncOutboxEntity(
                eventId = generateId().value,
                entityType = ENTITY_TYPE_BAKI_ENTRY,
                entityId = entry.id.value,
                operation = OPERATION_CREATE,
                payload = BakiEntrySyncPayload.toJson(entry),
                baseRevision = null,
                clientTimestamp = entry.time.occurredAt,
            ),
        )
    }

    companion object {
        const val ENTITY_TYPE_BAKI_ENTRY = "BakiEntry"
        const val OPERATION_CREATE = "create"
    }
}
