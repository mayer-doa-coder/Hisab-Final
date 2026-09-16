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
 * Why this table exists in M2 rather than M3, where the Customer *screens*
 * live (Step 50): Step 40 says a credit sale "saves the sale, reduces stock,
 * and creates a baki entry", and that entry cannot name who owes the money
 * without a customer to point at. So the table and a name lookup exist now,
 * and nothing else does — no customer list, no details screen, no editing.
 * Those are M3 and are deliberately absent.
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
