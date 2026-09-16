package com.hisab.app.data.customer

import com.hisab.app.data.HisabDatabase
import com.hisab.app.data.LOCAL_SHOP_ID
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.generateId
import kotlinx.coroutines.flow.Flow
import java.time.Clock

/**
 * Just enough of Customer for a credit sale to name who owes the money
 * (Step 40). The customer screens, editing, and the baki ledger are M3
 * (Steps 50–55) and are deliberately not here.
 */
class CustomerRepository(
    private val database: HisabDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val shopId: String = LOCAL_SHOP_ID,
) {
    private val customers = database.customerDao()

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
     * loses a shopkeeper money.
     */
    suspend fun findOrCreate(name: String): CustomerEntity {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A customer needs a name." }

        customers.byName(shopId, trimmed)?.let { return it }

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
        return customer
    }

    /** Really removes a row. Only for rows a test created. */
    suspend fun purge(id: EntityId) = customers.hardDelete(id.value)

    companion object {
        const val FIRST_REVISION = 1
    }
}
