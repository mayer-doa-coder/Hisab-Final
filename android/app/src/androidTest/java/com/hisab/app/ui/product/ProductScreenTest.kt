package com.hisab.app.ui.product

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.AppLanguage
import com.hisab.app.MainActivity
import com.hisab.app.data.HisabDatabase
import com.hisab.app.setAppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Steps 25, 26 and 27 through the real screens on a real device: the list
 * reads from the local database in Bangla, an added product appears at once,
 * and search finds it by name and by alias.
 *
 * This test writes to the app's own database, so every row it creates is
 * named with a marker and deleted afterwards — and the deletion is checked.
 */
@RunWith(AndroidJUnit4::class)
class ProductScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val marker = "ZZTest"
    private val productName = "$marker চিনি"
    private val alias = "zzchini"

    @Before
    fun startInBangla() {
        composeRule.activityRule.scenario.onActivity { setAppLanguage(AppLanguage.BANGLA) }
        waitForText("পণ্য দেখুন")
    }

    @After
    fun removeTestProducts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HisabDatabase.get(context).openHelper.writableDatabase
        database.execSQL("DELETE FROM product WHERE name LIKE ?", arrayOf("$marker%"))

        database.query("SELECT COUNT(*) FROM product WHERE name LIKE '$marker%'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("test products should be cleaned up", 0, cursor.getInt(0))
        }
    }

    // Steps 25, 26 and 27 in the order a shopkeeper would do them.
    @Test
    fun addsAProductAndFindsItByNameAndAlias() {
        composeRule.onNodeWithText("পণ্য দেখুন").performClick()
        waitForText("পণ্য")

        // Step 26: add. Nothing here touches the network, so this is the same
        // with the phone offline.
        composeRule.onNodeWithText("নতুন পণ্য").performClick()
        waitForTag("field_name")

        composeRule.onNodeWithTag("field_name").performTextInput(productName)
        composeRule.onNodeWithTag("field_aliases").performTextInput(alias)
        composeRule.onNodeWithTag("field_selling").performTextInput("85.50")
        Espresso.closeSoftKeyboard()

        composeRule.onNodeWithText("সংরক্ষণ করুন").performClick()

        // Step 25: the saved product is on the list straight away, read back
        // from the local database.
        waitForText(productName)
        composeRule.onNodeWithText(productName).assertIsDisplayed()

        // Step 27: found by part of its name…
        composeRule.onNodeWithTag("field_search").performTextInput(marker)
        waitForText(productName)
        composeRule.onNodeWithText(productName).assertIsDisplayed()

        // …and by an alias, which is not shown as the product's name.
        composeRule.onNodeWithTag("field_search").performTextReplacement(alias)
        waitForText(productName)
        composeRule.onNodeWithText(productName).assertIsDisplayed()

        // Typed one letter at a time, the way a person types. If the box took
        // its value back from the database query, the letters would arrive out
        // of order — "cook" came out as "ookc" on a real phone before this was
        // fixed.
        composeRule.onNodeWithTag("field_search").performTextReplacement("")
        "zzchini".forEach { letter ->
            composeRule.onNodeWithTag("field_search").performTextInput(letter.toString())
        }
        waitForText(productName)
        composeRule.onNodeWithText(productName).assertIsDisplayed()

        // A search that matches nothing shows nothing.
        composeRule.onNodeWithTag("field_search").performTextReplacement("zzqqnothing")
        waitUntilGone(productName)
        composeRule.onNodeWithText("এই নামে কোনো পণ্য পাওয়া যায়নি।").assertIsDisplayed()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitUntilGone(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
