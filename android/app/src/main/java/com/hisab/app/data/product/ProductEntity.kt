package com.hisab.app.data.product

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hisab.app.domain.Money
import java.time.Instant

/**
 * One product in one shop. Fields follow `docs/DATA_MODEL.md` — Product is a
 * mutable entity, so it carries `revision`, `updatedAt` and `deletedAt` for
 * conflict detection and tombstoned deletes (D017).
 *
 * Current stock is deliberately not a field here: it is always summed from
 * StockMovement rows (D020, PRD section 7).
 *
 * Prices are stored as plain `Long` poisha rather than the `Money` value
 * class, because `purchasePricePoisha` is nullable (PRD section 7 makes
 * purchase price optional) and Room's value-class support does not cover
 * nullable value-class columns. Read them through the `sellingPrice` /
 * `purchasePrice` helpers below, which hand back `Money`.
 */
@Entity(
    tableName = "product",
    indices = [Index(value = ["shopId"]), Index(value = ["name"])],
)
data class ProductEntity(
    @PrimaryKey val id: String,
    /** Never taken from user input or a request body — the server derives it from the session (D015). */
    val shopId: String,
    val name: String,
    /** Other names a customer might use ("cook" for "Coke"). Stored as one text column — see AliasListConverters. */
    val aliases: List<String>,
    /** Language-neutral unit code (see ProductUnits); the label shown to the user is localized (D011). */
    val unit: String,
    val purchasePricePoisha: Long?,
    val sellingPricePoisha: Long,
    val active: Boolean,
    val revision: Int,
    val updatedAt: Instant,
    /** Set instead of deleting the row, so the deletion can sync like any other change (D017). */
    val deletedAt: Instant?,
)

val ProductEntity.sellingPrice: Money get() = Money(sellingPricePoisha)

val ProductEntity.purchasePrice: Money? get() = purchasePricePoisha?.let(::Money)
