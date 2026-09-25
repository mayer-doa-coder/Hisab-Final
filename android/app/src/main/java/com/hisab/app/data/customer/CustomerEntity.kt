package com.hisab.app.data.customer

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * One customer of one shop. Fields follow `docs/DATA_MODEL.md` exactly:
 * Customer is a mutable entity, so like Product it carries `revision`,
 * `updatedAt` and `deletedAt` for conflict detection and tombstoned deletes
 * (D017).
 *
 * The table came in M2, because a credit sale's baki entry cannot name who
 * owes the money without a customer to point at (Step 40, D035). Step 50's
 * insert-and-read check is `CustomerDaoTest`. The customer *screens* — list,
 * details, editing — are Steps 52 onward and are not here yet.
 *
 * Current baki is not a field here, and never will be: it is always the sum
 * of that customer's BakiEntry rows (D001).
 */
@Entity(
    tableName = "customer",
    indices = [Index(value = ["shopId", "name"])],
)
data class CustomerEntity(
    @PrimaryKey val id: String,
    /** Never taken from user input — the server derives it from the session (D015). */
    val shopId: String,
    val name: String,
    val phone: String?,
    val revision: Int,
    val updatedAt: Instant,
    /** Set instead of deleting the row, so the deletion can sync like any other change (D017). */
    val deletedAt: Instant?,
)
