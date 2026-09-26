package com.hisab.app.data.customer

import androidx.room.withTransaction
import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.data.sync.CustomerSyncPayload
import com.hisab.app.data.sync.SyncOutboxEntity
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.generateId
import kotlinx.coroutines.flow.Flow
import java.time.Clock

/** What adding a customer by name came to. */
sealed interface CustomerCreateResult {
    data class Created(
        val customer: CustomerEntity,
    ) : CustomerCreateResult

    /** Someone in this shop already has that name; nothing was written. */
    data class NameTaken(
        val existing: CustomerEntity,
    ) : CustomerCreateResult
}

/**
 * Customers: enough for a credit sale to name who owes the money (Step 40), for
 * a customer to reach the server (Step 47), and for the Customers screens to
 * list, open and add them (Steps 52–55). Editing and deleting a customer are
 * not here yet.
 *
 * What a customer owes is not read here at all — it is the sum of their baki
 * entries (`BakiRepository`, D001).
 */
class CustomerRepository(
    private val database: HisabDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val shopId: String = LOCAL_SHOP_ID,
) {
    private val customers = database.customerDao()
    private val outbox = database.syncOutboxDao()

    fun observe(query: String = ""): Flow<List<CustomerEntity>> = customers.observe(shopId, query.trim())

    suspend fun byId(id: EntityId): CustomerEntity? = customers.byId(id.value)

    /**
     * The customer with this name, creating them if this is the first time
     * the shop has recorded one.
     *
     * Matching on the name is what stops one person becoming three rows
     * because the same credit sale was recorded on three different days. It
     * is a deliberate simplification for M2: two real customers who share a
     * name become one record, and there is no way to tell them apart until
     * the Customer screens arrive in M3 and phone numbers can be entered.
     * Recording that as a known limit is better than silently splitting one
     * person's baki across three rows, which is the failure that actually
     * loses a shopkeeper money (D035).
     *
     * A new customer is queued for sync in the same transaction as the row
     * (D003). Their credit sale is queued after, so the server always receives
     * the customer before the baki that names them.
     */
    suspend fun findOrCreate(name: String): CustomerEntity {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A customer needs a name." }

        return database.withTransaction {
            customers.byName(shopId, trimmed) ?: insertNew(trimmed, phone = null)
        }
    }

    /**
     * Adds a customer from the Customers screen (Step 52 onward), with an
     * optional phone number.
     *
     * A name that is already taken is *answered*, not merged and not repeated:
     * the caller is told who already has it, so the screen can say so and offer
     * that person. The same case-insensitive match as [findOrCreate] decides
     * "taken", because two rules for "the same customer" would let a credit sale
     * and this screen disagree about who owes what (D035).
     *
     * Like every customer write, it is queued for sync in the same transaction
     * (D003).
     */
    suspend fun create(
        name: String,
        phone: String?,
    ): CustomerCreateResult {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A customer needs a name." }

        return database.withTransaction {
            val existing = customers.byName(shopId, trimmed)
            if (existing != null) {
                CustomerCreateResult.NameTaken(existing)
            } else {
                CustomerCreateResult.Created(insertNew(trimmed, phone?.trim()?.takeIf { it.isNotEmpty() }))
            }
        }
    }

    /** Everyone this shop has recorded, by name, as a live query. */
    fun observeAll(): Flow<List<CustomerEntity>> = customers.observe(shopId, "")

    /** One customer as a live query; null if they do not exist. */
    fun observeById(id: EntityId): Flow<CustomerEntity?> = customers.observeById(id.value)

    /** Must run inside a transaction: the row and its sync event are saved together or not at all. */
    private suspend fun insertNew(
        name: String,
        phone: String?,
    ): CustomerEntity {
        val customer =
            CustomerEntity(
                id = generateId().value,
                shopId = shopId,
                name = name,
                phone = phone,
                revision = FIRST_REVISION,
                updatedAt = clock.instant(),
                deletedAt = null,
            )
        customers.insert(customer)
        outbox.insert(
            SyncOutboxEntity(
                eventId = generateId().value,
                entityType = ENTITY_TYPE_CUSTOMER,
                entityId = customer.id,
                operation = OPERATION_CREATE,
                payload = CustomerSyncPayload.toJson(customer),
                baseRevision = null,
                clientTimestamp = customer.updatedAt,
            ),
        )
        return customer
    }

    /** Saves a customer the server sent (Step 47). No sync event: sending it back would be an echo. */
    suspend fun applyFromServer(customer: CustomerEntity) {
        customers.upsert(customer.copy(shopId = shopId))
    }

    /** Really removes a row. Only for rows a test created. */
    suspend fun purge(id: EntityId) = customers.hardDelete(id.value)

    companion object {
        const val FIRST_REVISION = 1
        const val ENTITY_TYPE_CUSTOMER = "Customer"
        const val OPERATION_CREATE = "create"
    }
}
