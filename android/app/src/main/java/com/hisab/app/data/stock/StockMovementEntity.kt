package com.hisab.app.data.stock

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.StockMovement
import com.hisab.app.domain.StockMovementType
import com.hisab.app.domain.TransactionTime
import java.time.Instant

/**
 * One line of the stock ledger (Step 38). Fields follow `docs/DATA_MODEL.md`.
 *
 * There is deliberately no `currentStock` column anywhere: stock is always
 * the sum of these rows (D002, D020, PRD section 9). `StockMovementDao`
 * answers "how much is there" with a `SUM`, which is that rule written in
 * SQL — there is no second number that could drift from this one.
 *
 * Like Sale, this is a ledger entity: no `revision`, no `deletedAt`, never
 * edited in place. A mistake is fixed with an opposite movement.
 *
 * `occurredAt` and `serverReceivedAt` are two separate columns, not one
 * timestamp (D019).
 */
@Entity(
    tableName = "stock_movement",
    indices = [
        Index(value = ["productId", "occurredAt"]),
        Index(value = ["sourceReference"]),
    ],
)
data class StockMovementEntity(
    @PrimaryKey val id: String,
    /**
     * No foreign key to `product`: a movement can arrive from sync before the
     * product row does, and refusing to store it would lose the change rather
     * than delay it.
     */
    val productId: String,
    /** `StockMovementType` by name, so the stored value is language-neutral (D011). */
    val movementType: String,
    /** Quantity scaled by 1000, signed: negative takes stock away, positive puts it back (D019). */
    val quantityDeltaScaled: Long,
    /** What caused this — a sale id for a sale or a reversal, null if nothing. */
    val sourceReference: String?,
    val occurredAt: Instant,
    /** Null until the backend has actually processed this movement's sync event. */
    val serverReceivedAt: Instant?,
)

val StockMovementEntity.quantityDelta: Quantity get() = Quantity(quantityDeltaScaled)

fun StockMovementEntity.toDomain(): StockMovement =
    StockMovement(
        id = EntityId(id),
        productId = EntityId(productId),
        type = StockMovementType.valueOf(movementType),
        quantityDelta = Quantity(quantityDeltaScaled),
        sourceReference = sourceReference,
        time = TransactionTime(occurredAt, serverReceivedAt),
    )

fun StockMovement.toEntity(): StockMovementEntity =
    StockMovementEntity(
        id = id.value,
        productId = productId.value,
        movementType = type.name,
        quantityDeltaScaled = quantityDelta.scaledUnits,
        sourceReference = sourceReference,
        occurredAt = time.occurredAt,
        serverReceivedAt = time.serverReceivedAt,
    )
