package com.hisab.app.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.data.customer.CustomerEntity
import com.hisab.app.domain.BakiEntryType
import com.hisab.app.domain.EntityId
import com.hisab.app.domain.Money
import com.hisab.app.domain.Quantity
import com.hisab.app.domain.SaleLine
import com.hisab.app.domain.SalePayment
import com.hisab.app.domain.StockMovementType
import com.hisab.app.domain.completeCashSale
import com.hisab.app.domain.completeCreditSale
import com.hisab.app.domain.generateId
import com.hisab.app.domain.restock
import com.hisab.app.domain.reverseSale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/**
 * The shape a sale, a stock movement and a customer travel in (Step 47).
 *
 * It has to be exactly what the server reads and writes, so these tests pin
 * the field names and the lower-case codes the server spells its enums with
 * (`cash`, `credit_sale`, `restock`) as well as checking a value survives the
 * round trip. Run on a phone because `org.json` is Android's, not the JVM's.
 */
@RunWith(AndroidJUnit4::class)
class LedgerSyncPayloadTest {
    private val at = Instant.parse("2026-09-17T10:00:00Z")
    private val shopId = "local-shop"
    private val rice = generateId()
    private val oil = generateId()

    private fun cart() =
        listOf(
            SaleLine(rice, Quantity(3000), Money(5000)),
            SaleLine(oil, Quantity(2000), Money(2500)),
        )

    @Test
    fun aCashSaleSurvivesTheRoundTrip() {
        val sale = completeCashSale(shopId, cart(), at)

        val back = SaleSyncPayload.fromJson(JSONObject(SaleSyncPayload.toJson(sale)), shopId)

        assertEquals(sale.sale.id, back.sale.id)
        assertEquals(sale.sale.total, back.sale.total)
        assertEquals(SalePayment.CASH, back.sale.payment)
        assertNull(back.sale.customerId)
        assertNull(back.sale.reversesSaleId)
        assertEquals(at, back.sale.time.occurredAt)
        assertEquals(sale.items.map { it.productId }, back.items.map { it.productId })
        assertEquals(sale.stockMovements.map { it.quantityDelta }, back.stockMovements.map { it.quantityDelta })
        assertNull(back.bakiEntry)
    }

    @Test
    fun aCreditSaleKeepsItsCustomerAndDueDate() {
        val customer = generateId()
        val due = LocalDate.of(2026, 10, 15)
        val sale = completeCreditSale(shopId, customer, cart(), at, due)

        val back = SaleSyncPayload.fromJson(JSONObject(SaleSyncPayload.toJson(sale)), shopId)

        assertEquals(customer, back.sale.customerId)
        val owed = back.bakiEntry!!
        assertEquals(sale.sale.total, owed.amountDelta)
        assertEquals(BakiEntryType.CREDIT_SALE, owed.type)
        assertEquals(due, owed.dueDate)
    }

    @Test
    fun aReversalKeepsWhatItUndoes() {
        val original = completeCashSale(shopId, cart(), at)
        val reversal = reverseSale(original, at)

        val back = SaleSyncPayload.fromJson(JSONObject(SaleSyncPayload.toJson(reversal)), shopId)

        assertEquals(original.sale.id, back.sale.reversesSaleId)
        assertEquals(Money(-20_000), back.sale.total)
        assertEquals(StockMovementType.RETURN, back.stockMovements.first().type)
    }

    // The exact words the server uses. If these drift, sync fails at run time
    // against a server that is doing nothing wrong.
    @Test
    fun theCodesAreSpelledTheWayTheServerSpellsThem() {
        val customer = generateId()
        val json = JSONObject(SaleSyncPayload.toJson(completeCreditSale(shopId, customer, cart(), at)))

        assertEquals("credit", json.getJSONObject("sale").getString("payment"))
        assertEquals("sale", json.getJSONArray("stockMovements").getJSONObject(0).getString("type"))
        assertEquals("credit_sale", json.getJSONObject("bakiEntry").getString("type"))
        assertEquals(
            "cash",
            JSONObject(SaleSyncPayload.toJson(completeCashSale(shopId, cart(), at))).getJSONObject("sale").getString("payment"),
        )
    }

    @Test
    fun moneyAndQuantityTravelAsWholeNumbers() {
        val json = JSONObject(SaleSyncPayload.toJson(completeCashSale(shopId, cart(), at)))

        assertEquals(20_000L, json.getJSONObject("sale").getLong("total"))
        val item = json.getJSONArray("items").getJSONObject(0)
        assertEquals(3000L, item.getLong("quantity"))
        assertEquals(5000L, item.getLong("unitPrice"))
    }

    @Test
    fun aStockMovementSurvivesTheRoundTrip() {
        val movement = restock(rice, Quantity(10_000), at, sourceReference = "করিম স্টোর")

        val back = StockMovementSyncPayload.fromJson(JSONObject(StockMovementSyncPayload.toJson(movement)))

        assertEquals(movement.id, back.id)
        assertEquals(StockMovementType.RESTOCK, back.type)
        assertEquals(Quantity(10_000), back.quantityDelta)
        assertEquals("করিম স্টোর", back.sourceReference)
        assertNull(back.time.serverReceivedAt)
    }

    @Test
    fun aMovementWithNoNoteKeepsItNullRatherThanTheWordNull() {
        val movement = restock(rice, Quantity(1000), at)
        val back = StockMovementSyncPayload.fromJson(JSONObject(StockMovementSyncPayload.toJson(movement)))
        assertNull(back.sourceReference)
    }

    @Test
    fun aCustomerSurvivesTheRoundTrip() {
        val customer =
            CustomerEntity(
                id = generateId().value,
                shopId = shopId,
                name = "রহিম",
                phone = null,
                revision = 1,
                updatedAt = at,
                deletedAt = null,
            )

        val back =
            CustomerSyncPayload.fromJson(
                JSONObject(CustomerSyncPayload.toJson(customer))
                    .put("revision", 1)
                    .put("updatedAt", at.toString()),
                customer.id,
                shopId,
            )

        assertEquals(customer.name, back.name)
        assertNull(back.phone)
        assertEquals(at, back.updatedAt)
        assertNull(back.deletedAt)
    }

    @Test
    fun aSaleFromTheServerIsStoredUnderThisPhonesOwnShop() {
        val sale = completeCashSale("shop-1", cart(), at)
        val back = SaleSyncPayload.fromJson(JSONObject(SaleSyncPayload.toJson(sale)), EntityId("another-shop").value)
        assertEquals("another-shop", back.sale.shopId)
    }
}
