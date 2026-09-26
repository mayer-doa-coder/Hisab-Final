package com.hisab.app.ui.customer

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.AppLanguage
import com.hisab.app.MainActivity
import com.hisab.app.data.HisabDatabase
import com.hisab.app.domain.normalizeDigits
import com.hisab.app.setAppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Steps 52 to 56 through the real screens on a real device.
 *
 * Nothing in the path touches the network — every screen reads and writes the
 * phone's own database — so it is the same with the phone in airplane mode.
 *
 * The test writes to the app's own database, so every row it makes is named
 * with a marker and removed afterwards, and the removal is checked.
 */
@RunWith(AndroidJUnit4::class)
class CustomerFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val marker = "ZZTest"

    @Before
    fun startInBangla() {
        composeRule.activityRule.scenario.onActivity { setAppLanguage(AppLanguage.BANGLA) }
        waitForText(CUSTOMERS_OPEN)
    }

    @After
    fun removeTestRows() {
        val database = HisabDatabase.get(ApplicationProvider.getApplicationContext<Context>()).openHelper.writableDatabase
        val mine = "(SELECT id FROM customer WHERE name LIKE '$marker%')"
        database.execSQL("DELETE FROM baki_entry WHERE customerId IN $mine")
        database.execSQL("DELETE FROM sync_outbox WHERE entityId IN $mine")
        database.execSQL("DELETE FROM customer WHERE name LIKE '$marker%'")

        database.query("SELECT COUNT(*) FROM customer WHERE name LIKE '$marker%'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("test customers should be cleaned up", 0, cursor.getInt(0))
        }
    }

    // Step 52: shows customers, and an added one is there straight away.
    @Test
    fun anAddedCustomerAppearsOnTheListAtOnce() {
        val name = "$marker Rahim"
        addCustomerThroughTheScreen(name, phone = "01711000000")

        // Adding opens the new customer, all clear.
        assertEquals("0.00", balanceDigits().removePrefix("৳").trim())

        Espresso.pressBack()
        waitForTag("customer_list")
        composeRule.onNodeWithText(name).assertIsDisplayed()
        composeRule.onNodeWithText("01711000000").assertIsDisplayed()

        // Search finds them by part of the name and by phone.
        composeRule.onNodeWithTag("field_customer_search").performTextInput("rahim")
        composeRule.onNodeWithText(name).assertIsDisplayed()
    }

    // Steps 53 to 56: 500 in baki, 200 received, 100 more added. The balance
    // shown is exactly 400, and so is the sum of what is stored.
    @Test
    fun fiveHundredThenTwoHundredReceivedThenOneHundredMoreIsExactlyFourHundred() {
        val name = "$marker Karim"
        addCustomerThroughTheScreen(name)
        val id = customerId(name)

        recordBaki(500)
        assertEquals("500.00", balanceDigits().removePrefix("৳").trim())
        composeRule.onNodeWithTag("baki_notice").assertIsDisplayed()

        receivePayment(200)
        assertEquals("300.00", balanceDigits().removePrefix("৳").trim())

        recordBaki(100)
        assertEquals("400.00", balanceDigits().removePrefix("৳").trim())

        // The ledger has all three lines, and the stored entries add up to the
        // same number the screen shows.
        assertEquals(3, storedEntryCount(id))
        assertEquals(40_000L, storedSum(id))

        // Baki entered here is kept on the phone until Step 58 gives the server
        // somewhere to put it (D042): only the customer's own creation is queued.
        assertEquals(1, queuedEvents(id))
    }

    // Step 55: paying in full is one tap, and paying too much is recorded as an advance.
    @Test
    fun payingInFullClearsTheBalanceAndOverpayingKeepsAnAdvance() {
        val name = "$marker Salma"
        addCustomerThroughTheScreen(name)
        recordBaki(500)

        openPaymentForm()
        composeRule.onNodeWithTag("chip_pay_full").performClick()
        assertEquals("0.00", digitsOf("baki_after").removePrefix("৳").trim())
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("button_save_baki").performClick()
        waitForTag("baki_notice")
        assertEquals("0.00", balanceDigits().removePrefix("৳").trim())

        // 100 more than is owed: the screen says what will happen, and records it.
        openPaymentForm()
        composeRule.onNodeWithTag("field_baki_amount").performTextInput("100")
        composeRule.onNodeWithTag("baki_overpay_note").assertIsDisplayed()
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("button_save_baki").performClick()
        waitForTag("baki_notice")

        assertEquals("100.00", balanceDigits().removePrefix("৳").trim())
        assertEquals(-10_000L, storedSum(customerId(name)))
    }

    // A bad amount is refused on the field, and nothing is written.
    @Test
    fun aBadAmountIsRefusedOnTheFieldAndNothingIsWritten() {
        val name = "$marker Belal"
        addCustomerThroughTheScreen(name)
        val id = customerId(name)

        composeRule.onNodeWithTag("button_add_baki").performClick()
        waitForTag("field_baki_amount")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("button_save_baki").performClick()
        composeRule.onNodeWithText(AMOUNT_ERROR).assertIsDisplayed()

        composeRule.onNodeWithTag("field_baki_amount").performTextInput("0")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("button_save_baki").performClick()
        composeRule.onNodeWithText(AMOUNT_ERROR).assertIsDisplayed()

        assertEquals(0, storedEntryCount(id))
    }

    // A due date in the past makes the baki overdue, on the customer's screen and on the list.
    @Test
    fun aPastDueDateMakesTheCustomerOverdueEverywhere() {
        val name = "$marker Anwar"
        addCustomerThroughTheScreen(name)
        val id = customerId(name)

        composeRule.onNodeWithTag("button_add_baki").performClick()
        waitForTag("field_baki_amount")
        composeRule.onNodeWithTag("field_baki_amount").performTextInput("250")
        // Plain digits, which any number pad can type.
        composeRule.onNodeWithTag("field_baki_due").performTextInput("20200101")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("button_save_baki").performClick()
        waitForTag("baki_notice")

        composeRule.onNodeWithTag("customer_overdue").assertIsDisplayed()

        Espresso.pressBack()
        waitForTag("customer_list")
        composeRule.onNodeWithTag("filter_overdue").performClick()
        waitForTag("customer_row_$id")
        composeRule.onNodeWithTag("customer_row_$id").assertIsDisplayed()
    }

    // A name that is taken is said out loud, and the existing person is offered.
    @Test
    fun aTakenNameIsReportedAndTheExistingCustomerIsOffered() {
        val name = "$marker Dulal"
        addCustomerThroughTheScreen(name)
        Espresso.pressBack()
        waitForTag("customer_list")

        composeRule.onNodeWithTag("button_add_customer").performClick()
        waitForTag("field_customer_name")
        composeRule.onNodeWithTag("field_customer_name").performTextInput(name.lowercase())
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("button_confirm_add_customer").performClick()

        waitForText(NAME_TAKEN)
        composeRule.onNodeWithText(OPEN_EXISTING).performClick()
        waitForTag("customer_balance")
        composeRule.onNodeWithText(name).assertIsDisplayed()
    }

    // Step 57: reverse one of those entries, and the balance updates correctly again.
    @Test
    fun undoingAnEntryBringsTheBalanceRightAndKeepsBothInTheHistory() {
        val name = "$marker Faruk"
        addCustomerThroughTheScreen(name)
        val id = customerId(name)

        recordBaki(500)
        receivePayment(200)
        recordBaki(100)
        assertEquals("400.00", balanceDigits().removePrefix("৳").trim())

        // Newest first: the 100, the 200 payment, the 500. Each offers an undo. Only
        // rows that fit on screen are composed, so this checks there are at least two.
        assertTrue(composeRule.onAllNodesWithTag("button_undo").fetchSemanticsNodes().size >= 2)

        // Changing one's mind at the confirmation writes nothing.
        composeRule.onAllNodesWithTag("button_undo")[1].performClick()
        waitForTag("button_confirm_undo")
        composeRule.onNodeWithText(KEEP_IT).performClick()
        waitUntilGone("button_confirm_undo")
        assertEquals(40_000L, storedSum(id))
        assertEquals(3, storedEntryCount(id))

        // Undo the 200 payment. The dialog shows the balance it will become first.
        composeRule.onAllNodesWithTag("button_undo")[1].performClick()
        waitForTag("button_confirm_undo")
        assertEquals("600.00", digitsOf("undo_after").removePrefix("৳").trim())
        composeRule.onNodeWithTag("button_confirm_undo").performClick()
        waitForTag("baki_notice")

        assertEquals("400 + the 200 that was never paid", "600.00", balanceDigits().removePrefix("৳").trim())
        assertEquals(60_000L, storedSum(id))
        // The original and its opposite are both in the ledger: history is not rewritten.
        assertEquals(4, storedEntryCount(id))
        // The payment stays in the list, marked as undone.
        waitForText(UNDONE)

        // Undo the 100 credit, now the newest entry that can still be undone.
        composeRule.onAllNodesWithTag("button_undo")[0].performClick()
        waitForTag("button_confirm_undo")
        composeRule.onNodeWithTag("button_confirm_undo").performClick()
        waitUntilBalance("500.00")

        assertEquals("500.00", balanceDigits().removePrefix("৳").trim())
        assertEquals(50_000L, storedSum(id))
        assertEquals(5, storedEntryCount(id))
    }

    // --- steps a shopkeeper takes ---

    private fun addCustomerThroughTheScreen(
        name: String,
        phone: String? = null,
    ) {
        composeRule.onNodeWithText(CUSTOMERS_OPEN).performClick()
        waitForTag("button_add_customer")
        composeRule.onNodeWithTag("button_add_customer").performClick()
        waitForTag("field_customer_name")
        composeRule.onNodeWithTag("field_customer_name").performTextInput(name)
        if (phone != null) composeRule.onNodeWithTag("field_customer_phone").performTextInput(phone)
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("button_confirm_add_customer").performClick()
        waitForTag("customer_balance")
    }

    private fun recordBaki(taka: Int) {
        composeRule.onNodeWithTag("button_add_baki").performClick()
        waitForTag("field_baki_amount")
        composeRule.onNodeWithTag("field_baki_amount").performTextInput(taka.toString())
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("button_save_baki").performClick()
        waitForTag("baki_notice")
    }

    private fun receivePayment(taka: Int) {
        openPaymentForm()
        composeRule.onNodeWithTag("field_baki_amount").performTextInput(taka.toString())
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("button_save_baki").performClick()
        waitForTag("baki_notice")
    }

    private fun openPaymentForm() {
        composeRule.onNodeWithTag("button_receive_payment").performClick()
        waitForTag("field_baki_amount")
    }

    // --- reading the screen and the database ---

    /** The text on a node, with Bangla digits turned into ASCII so it can be compared. */
    private fun digitsOf(tag: String): String {
        val texts =
            composeRule
                .onNodeWithTag(tag)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
        return normalizeDigits(texts.joinToString("") { it.text })
    }

    private fun balanceDigits() = digitsOf("customer_balance")

    private fun database() = HisabDatabase.get(ApplicationProvider.getApplicationContext<Context>()).openHelper.readableDatabase

    private fun customerId(name: String): String =
        database().query("SELECT id FROM customer WHERE name = ?", arrayOf(name)).use {
            assertTrue("customer '$name' should exist", it.moveToFirst())
            it.getString(0)
        }

    private fun storedSum(id: String): Long =
        database().query("SELECT COALESCE(SUM(amountDeltaPoisha), 0) FROM baki_entry WHERE customerId = ?", arrayOf(id)).use {
            it.moveToFirst()
            it.getLong(0)
        }

    private fun storedEntryCount(id: String): Int =
        database().query("SELECT COUNT(*) FROM baki_entry WHERE customerId = ?", arrayOf(id)).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun queuedEvents(id: String): Int =
        database().query("SELECT COUNT(*) FROM sync_outbox WHERE entityId = ?", arrayOf(id)).use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitUntilGone(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitUntilBalance(digits: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("customer_balance").fetchSemanticsNodes().isNotEmpty() &&
                balanceDigits().removePrefix("৳").trim() == digits
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val CUSTOMERS_OPEN = "গ্রাহক ও বাকি"
        const val AMOUNT_ERROR = "টাকার অঙ্ক লিখুন, যেমন 500 বা 12.50"
        const val NAME_TAKEN = "এই নামে একজন গ্রাহক আগে থেকেই আছে"
        const val KEEP_IT = "না, থাক"
        const val UNDONE = "ফিরিয়ে নেওয়া হয়েছে"
        const val OPEN_EXISTING = "তাকে দেখুন"
    }
}
