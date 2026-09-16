package com.hisab.app.data.sale

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.Sale
import com.hisab.app.domain.SaleItem
import com.hisab.app.domain.SalePayment
import com.hisab.app.domain.TransactionTime
import java.time.Instant

/**
 * One completed sale (Step 38). Fields follow `docs/DATA_MODEL.md`.
 *
 * Sale is a ledger entity, so unlike Product it carries no `revision`,
 * `updatedAt` or `deletedAt`: it is never edited in place and never deleted,
 * so two devices have nothing to disagree about (D017). A mistake is undone
 * by a second, opposite sale — see `reversesSaleId`.
 *
 * `occurredAt` and `serverReceivedAt` are two separate columns, not one
 * timestamp (D019). An offline sale that syncs hours later must still report
 * the moment it really happened.
 *
 * Money and quantity are plain `Long` columns rather than the `Money` and
 * `Quantity` value classes, the same choice `ProductEntity` made — read them
 * through the helpers below, or through `toDomain()`.
 */
@Entity(
    tableName = "sale",
    indices = [
        Index(value = ["shopId", "occurredAt"]),
        Index(value = ["customerId"]),
        Index(value = ["reversesSaleId"]),
    ],
)
data class SaleEntity(
    @PrimaryKey val id: String,
    /** Never taken from user input — the server derives it from the session (D015). */
    val shopId: String,
    val totalPoisha: Long,
    /** `SalePayment` by name, so the stored value is language-neutral (D011). */
    val payment: String,
    /** Set for a credit sale, null for cash. */
    val customerId: String?,
    /**
     * Null for a normal sale. On a reversal, the id of the sale being undone.
     *
     * No foreign key to `sale` itself: on a phone catching up on sync, a
     * reversal can arrive before the sale it undoes, and refusing to store it
     * would lose the change rather than delay it.
     */
    val reversesSaleId: String?,
    val occurredAt: Instant,
    /** Null until the backend has actually processed this sale's sync event. */
    val serverReceivedAt: Instant?,
)

/**
 * One line of a sale. A line is identified by its sale and its product
 * together, exactly as `docs/DATA_MODEL.md` describes it, so one product
 * appears at most once in a sale.
 *
 * `unitPricePoisha` is copied onto the line rather than read from the product
 * later: a price the shopkeeper changes next month must never quietly change
 * what last month's receipt said.
 *
 * The foreign key makes a line without its sale impossible to store, which is
 * half of what D021 asks for — the other half is that both are written in one
 * `withTransaction` block. `CASCADE` is not an invitation to delete a sale
 * (confirmed history is never deleted); it only means a purge cannot leave
 * orphaned lines behind.
 */
@Entity(
    tableName = "sale_item",
    primaryKeys = ["saleId", "productId"],
    indices = [Index(value = ["productId"])],
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SaleItemEntity(
    val saleId: String,
    val productId: String,
    /** Quantity scaled by 1000: 3 pieces = 3000, 0.5 kg = 500 (D019). Negative on a reversal. */
    val quantityScaled: Long,
    val unitPricePoisha: Long,
)

val SaleEntity.total: Money get() = Money(totalPoisha)

val SaleItemEntity.quantity: Quantity get() = Quantity(quantityScaled)

val SaleItemEntity.unitPrice: Money get() = Money(unitPricePoisha)

fun SaleEntity.toDomain(): Sale =
    Sale(
        id = EntityId(id),
        shopId = shopId,
        total = Money(totalPoisha),
        payment = SalePayment.valueOf(payment),
        customerId = customerId?.let(::EntityId),
        reversesSaleId = reversesSaleId?.let(::EntityId),
        time = TransactionTime(occurredAt, serverReceivedAt),
    )

fun Sale.toEntity(): SaleEntity =
    SaleEntity(
        id = id.value,
        shopId = shopId,
        totalPoisha = total.minorUnits,
        payment = payment.name,
        customerId = customerId?.value,
        reversesSaleId = reversesSaleId?.value,
        occurredAt = time.occurredAt,
        serverReceivedAt = time.serverReceivedAt,
    )

fun SaleItemEntity.toDomain(): SaleItem =
    SaleItem(
        saleId = EntityId(saleId),
        productId = EntityId(productId),
        quantity = Quantity(quantityScaled),
        unitPrice = Money(unitPricePoisha),
    )

fun SaleItem.toEntity(): SaleItemEntity =
    SaleItemEntity(
        saleId = saleId.value,
        productId = productId.value,
        quantityScaled = quantity.scaledUnits,
        unitPricePoisha = unitPrice.minorUnits,
    )
