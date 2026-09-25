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

/**
 * Just enough of Customer for a credit sale to name who owes the money
 * (Step 40), and for that customer to reach the server (Step 47). The customer
 * screens, editing, and the baki screens are M3 (Steps 52–55) and are not
 * here yet.
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
            customers.byName(shopId, trimmed)?.let { return@withTransaction it }

            val customer =
                CustomerEntity(
                    id = generateId().value,
                    shopId = shopId,
                    name = trimmed,
                    phone = null,
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
            customer
        }
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
