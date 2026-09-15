package com.hisab.app.data.sync

import com.hisab.app.data.product.ProductEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The wire shape of a product, written with `org.json` (part of Android — no
 * library needed, D006). It matches what the server accepts and returns, so
 * one shape travels in both directions (D026).
 */
object ProductSyncPayload {
    fun toJson(product: ProductEntity): String =
        JSONObject()
            .put("name", product.name)
            .put("aliases", JSONArray(product.aliases))
            .put("unit", product.unit)
            .put("sellingPricePoisha", product.sellingPricePoisha)
            .put(
                "purchasePricePoisha",
                product.purchasePricePoisha ?: JSONObject.NULL,
            ).put("active", product.active)
            .toString()

    /**
     * Reads a product the server sent back. The shop id is deliberately not
     * taken from the server: this phone keeps everything under its own local
     * shop until it can sign in (see CurrentShop).
     */
    fun fromJson(
        json: JSONObject,
        id: String,
        shopId: String,
    ): ProductEntity {
        val aliasesJson = json.optJSONArray("aliases") ?: JSONArray()
        val aliases = (0 until aliasesJson.length()).map { aliasesJson.getString(it) }

        return ProductEntity(
            id = id,
            shopId = shopId,
            name = json.getString("name"),
            aliases = aliases,
            unit = json.optString("unit", "piece"),
            purchasePricePoisha =
                if (json.isNull("purchasePricePoisha")) null else json.getLong("purchasePricePoisha"),
            sellingPricePoisha = json.getLong("sellingPricePoisha"),
            active = json.optBoolean("active", true),
            revision = json.optInt("revision", 1),
            updatedAt = parseInstant(json.optString("updatedAt")),
            deletedAt =
                if (json.isNull("deletedAt")) null else parseInstant(json.optString("deletedAt")),
        )
    }

    private fun parseInstant(value: String?): Instant = if (value.isNullOrBlank()) Instant.now() else Instant.parse(value)
}
