package com.hisab.app.data.sync

import com.hisab.app.data.customer.CustomerEntity
import com.hisab.app.domain.BakiEntry
import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.Sale
import com.hisab.app.domain.SaleItem
import com.hisab.app.domain.SalePayment
import com.hisab.app.domain.SaleTransaction
import com.hisab.app.domain.StockMovement
import com.hisab.app.domain.StockMovementType
import com.hisab.app.domain.TransactionTime
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * The wire shape of a sale, a stock movement and a customer, written with
 * `org.json` (part of Android — no library, D006).
 *
 * It is exactly the shape the server's `domain/sale.ts` builds and its pull
 * returns (Step 47), so one shape travels in both directions (D026): the same
 * field names, money as integer poisha, quantity as integer ×1000 (D019), and
 * the enum codes in lower case (`cash`, `credit_sale`), which is how the server
 * spells them. Kotlin spells them in upper case; the conversion happens here
 * and nowhere else.
 */
object SaleSyncPayload {
    fun toJson(transaction: SaleTransaction): String = toJsonObject(transaction).toString()

    fun toJsonObject(transaction: SaleTransaction): JSONObject {
        val sale = transaction.sale
        return JSONObject()
            .put(
                "sale",
                JSONObject()
                    .put("id", sale.id.value)
                    .put("shopId", sale.shopId)
                    .put("total", sale.total.minorUnits)
                    .put("payment", sale.payment.name.lowercase())
                    .put("customerId", sale.customerId?.value ?: JSONObject.NULL)
                    .put("reversesSaleId", sale.reversesSaleId?.value ?: JSONObject.NULL)
                    .put("time", timeJson(sale.time)),
            ).put(
                "items",
                JSONArray().apply {
                    transaction.items.forEach { item ->
                        put(
                            JSONObject()
                                .put("saleId", item.saleId.value)
                                .put("productId", item.productId.value)
                                .put("quantity", item.quantity.scaledUnits)
                                .put("unitPrice", item.unitPrice.minorUnits),
                        )
                    }
                },
            ).put(
                "stockMovements",
                JSONArray().apply { transaction.stockMovements.forEach { put(StockMovementSyncPayload.toJsonObject(it)) } },
            ).put("bakiEntry", transaction.bakiEntry?.let(::bakiJson) ?: JSONObject.NULL)
    }

    /**
     * Reads a sale the server sent. The shop is deliberately not taken from
     * the server: this phone keeps everything under its own local shop (see
     * CurrentShop), just as ProductSyncPayload does.
     *
     * Building a `SaleTransaction` runs its checks, so a sale that arrived
     * half-formed throws here rather than being stored half-formed (D021).
     */
    fun fromJson(
        json: JSONObject,
        shopId: String,
    ): SaleTransaction {
        val saleJson = json.getJSONObject("sale")
        val saleId = EntityId(saleJson.getString("id"))
        val itemsJson = json.getJSONArray("items")
        val movementsJson = json.getJSONArray("stockMovements")

        return SaleTransaction(
            sale =
                Sale(
                    id = saleId,
                    shopId = shopId,
                    total = Money(saleJson.getLong("total")),
                    payment = SalePayment.valueOf(saleJson.getString("payment").uppercase()),
                    customerId = saleJson.optIdOrNull("customerId"),
                    reversesSaleId = saleJson.optIdOrNull("reversesSaleId"),
                    time = timeFrom(saleJson.getJSONObject("time")),
                ),
            items =
                (0 until itemsJson.length()).map { index ->
                    val item = itemsJson.getJSONObject(index)
                    SaleItem(
                        saleId = saleId,
                        productId = EntityId(item.getString("productId")),
                        quantity = Quantity(item.getLong("quantity")),
                        unitPrice = Money(item.getLong("unitPrice")),
                    )
                },
            stockMovements =
                (0 until movementsJson.length()).map { index ->
                    StockMovementSyncPayload.fromJson(movementsJson.getJSONObject(index))
                },
            bakiEntry = if (json.isNull("bakiEntry")) null else bakiFrom(json.getJSONObject("bakiEntry")),
        )
    }

    private fun bakiJson(entry: BakiEntry): JSONObject =
        JSONObject()
            .put("id", entry.id.value)
            .put("customerId", entry.customerId.value)
            .put("amountDelta", entry.amountDelta.minorUnits)
            .put("type", entry.type.name.lowercase())
            .put("reference", entry.reference ?: JSONObject.NULL)
            .put("dueDate", entry.dueDate?.toString() ?: JSONObject.NULL)
            .put("time", timeJson(entry.time))

    private fun bakiFrom(json: JSONObject): BakiEntry =
        BakiEntry(
            id = EntityId(json.getString("id")),
            customerId = EntityId(json.getString("customerId")),
            amountDelta = Money(json.getLong("amountDelta")),
            type = BakiEntryType.valueOf(json.getString("type").uppercase()),
            reference = json.optStringOrNull("reference"),
            dueDate = json.optStringOrNull("dueDate")?.let(LocalDate::parse),
            time = timeFrom(json.getJSONObject("time")),
        )
}

/** A stock movement that stands on its own — a restock, damage or shelf count. */
object StockMovementSyncPayload {
    fun toJson(movement: StockMovement): String = toJsonObject(movement).toString()

    fun toJsonObject(movement: StockMovement): JSONObject =
        JSONObject()
            .put("id", movement.id.value)
            .put("productId", movement.productId.value)
            .put("type", movement.type.name.lowercase())
            .put("quantityDelta", movement.quantityDelta.scaledUnits)
            .put("sourceReference", movement.sourceReference ?: JSONObject.NULL)
            .put("time", timeJson(movement.time))

    fun fromJson(json: JSONObject): StockMovement =
        StockMovement(
            id = EntityId(json.getString("id")),
            productId = EntityId(json.getString("productId")),
            type = StockMovementType.valueOf(json.getString("type").uppercase()),
            quantityDelta = Quantity(json.getLong("quantityDelta")),
            sourceReference = json.optStringOrNull("sourceReference"),
            time = timeFrom(json.getJSONObject("time")),
        )
}

/** A customer. Only created in M2 — by a credit sale — so the push carries just what was typed. */
object CustomerSyncPayload {
    fun toJson(customer: CustomerEntity): String =
        JSONObject()
            .put("name", customer.name)
            .put("phone", customer.phone ?: JSONObject.NULL)
            .toString()

    /** Reads a customer the server sent, keeping the server's revision (D017) and the local shop. */
    fun fromJson(
        json: JSONObject,
        id: String,
        shopId: String,
    ): CustomerEntity =
        CustomerEntity(
            id = id,
            shopId = shopId,
            name = json.getString("name"),
            phone = json.optStringOrNull("phone"),
            revision = json.optInt("revision", 1),
            updatedAt = json.optStringOrNull("updatedAt")?.let(Instant::parse) ?: Instant.now(),
            deletedAt = json.optStringOrNull("deletedAt")?.let(Instant::parse),
        )
}

private fun timeJson(time: TransactionTime): JSONObject =
    JSONObject()
        .put("occurredAt", time.occurredAt.toString())
        .put("serverReceivedAt", time.serverReceivedAt?.toString() ?: JSONObject.NULL)

private fun timeFrom(json: JSONObject): TransactionTime =
    TransactionTime(
        occurredAt = Instant.parse(json.getString("occurredAt")),
        serverReceivedAt = json.optStringOrNull("serverReceivedAt")?.let(Instant::parse),
    )

/** `optString` turns a JSON null into the text "null"; this keeps it null. */
private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.optIdOrNull(key: String): EntityId? = optStringOrNull(key)?.let(::EntityId)
