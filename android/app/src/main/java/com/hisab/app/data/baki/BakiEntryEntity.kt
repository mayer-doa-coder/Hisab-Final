package com.hisab.app.data.baki

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hisab.app.domain.BakiEntry
import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.TransactionTime
import java.time.Instant
import java.time.LocalDate

/**
 * One line of a customer's credit ledger. Fields follow `docs/DATA_MODEL.md`.
 *
 * The table came in M2, because a credit sale has to store its entry (Step 40,
 * D035). The rules that build and read entries are in `domain/Baki.kt`
 * (Step 48); the tests that prove an entry survives the round trip through
 * this table are `BakiEntryDaoTest` (Step 51). The entry types are stored by
 * name in [entryType] — a new type needs no schema change.
 *
 * Like Sale and StockMovement this is a ledger entity: no `revision`, no
 * `deletedAt`, never edited in place. A customer's balance is always the sum
 * of these rows, never a stored number (D001).
 */
@Entity(
    tableName = "baki_entry",
    indices = [
        Index(value = ["customerId", "occurredAt"]),
        Index(value = ["reference"]),
    ],
)
data class BakiEntryEntity(
    @PrimaryKey val id: String,
    /**
     * No foreign key to `customer`: an entry can arrive from sync before the
     * customer row does, and refusing to store it would lose the change
     * rather than delay it.
     */
    val customerId: String,
    /** Money in poisha, signed: positive is more owed, negative is less owed (D019). */
    val amountDeltaPoisha: Long,
    /** `BakiEntryType` by name, so the stored value is language-neutral (D011). */
    val entryType: String,
    /** What caused this — the sale id, for both a credit sale and its reversal. */
    val reference: String?,
    /** Days since the epoch, or null. A date, not an instant: "pay me back on the 15th" has no clock time. */
    val dueDateEpochDay: Long?,
    val occurredAt: Instant,
    /** Null until the backend has actually processed this entry's sync event. */
    val serverReceivedAt: Instant?,
)

val BakiEntryEntity.amountDelta: Money get() = Money(amountDeltaPoisha)

fun BakiEntryEntity.toDomain(): BakiEntry =
    BakiEntry(
        id = EntityId(id),
        customerId = EntityId(customerId),
        amountDelta = Money(amountDeltaPoisha),
        type = BakiEntryType.valueOf(entryType),
        reference = reference,
        dueDate = dueDateEpochDay?.let(LocalDate::ofEpochDay),
        time = TransactionTime(occurredAt, serverReceivedAt),
    )

fun BakiEntry.toEntity(): BakiEntryEntity =
    BakiEntryEntity(
        id = id.value,
        customerId = customerId.value,
        amountDeltaPoisha = amountDelta.minorUnits,
        entryType = type.name,
        reference = reference,
        dueDateEpochDay = dueDate?.toEpochDay(),
        occurredAt = time.occurredAt,
        serverReceivedAt = time.serverReceivedAt,
    )
