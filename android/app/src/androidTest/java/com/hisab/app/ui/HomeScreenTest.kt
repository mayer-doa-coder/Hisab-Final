package com.hisab.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.AppLanguage
import com.hisab.app.MainActivity
import com.hisab.app.currentAppLanguage
import com.hisab.app.pinBanglaOnFirstRun
import com.hisab.app.setAppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Step 20: the app opens to this screen, in Bangla by default, and the
// language switch (Steps 8–10) works both ways and is remembered.
//
// AppCompat's language API only works while an Activity is alive, so every
// language change here goes through a launched MainActivity.
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val banglaTitle = "হিসাবে স্বাগতম"
    private val englishTitle = "Welcome to Hisab"

    @After
    fun restoreBangla() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { setAppLanguage(AppLanguage.BANGLA) }
        }
    }

    @Test
    fun firstRunOpensInBanglaEvenOnAnEnglishPhone() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Start from English, so seeing Bangla at the end can't be a
            // leftover from an earlier test.
            scenario.onActivity { setAppLanguage(AppLanguage.ENGLISH) }
            waitForText(englishTitle)

            // Wipe the stored choice — the state of a brand-new install. The
            // Activity is recreated, and its onCreate must pin Bangla again
            // instead of following the phone's own language.
            scenario.onActivity {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
            waitForText(banglaTitle)

            composeRule.onNodeWithText(banglaTitle).assertIsDisplayed()
            composeRule.onNodeWithText("English").assertIsDisplayed()
            scenario.onActivity { assertEquals(AppLanguage.BANGLA, currentAppLanguage()) }
        }
    }

    @Test
    fun pinningDoesNotOverrideAChosenLanguage() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { setAppLanguage(AppLanguage.ENGLISH) }
            waitForText(englishTitle)

            scenario.onActivity {
                pinBanglaOnFirstRun()
                assertEquals(AppLanguage.ENGLISH, currentAppLanguage())
            }
            composeRule.onNodeWithText(englishTitle).assertIsDisplayed()
        }
    }

    @Test
    fun languageButtonSwitchesToEnglishAndBack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { setAppLanguage(AppLanguage.BANGLA) }
            waitForText(banglaTitle)

            composeRule.onNodeWithText("English").performClick()
            waitForText(englishTitle)
            composeRule.onNodeWithText(englishTitle).assertIsDisplayed()
            scenario.onActivity { assertEquals(AppLanguage.ENGLISH, currentAppLanguage()) }

            composeRule.onNodeWithText("বাংলা").performClick()
            waitForText(banglaTitle)
            composeRule.onNodeWithText(banglaTitle).assertIsDisplayed()
            scenario.onActivity { assertEquals(AppLanguage.BANGLA, currentAppLanguage()) }
        }
    }

    @Test
    fun chosenLanguageIsStillThereAfterTheAppIsClosedAndOpenedAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { setAppLanguage(AppLanguage.ENGLISH) }
            waitForText(englishTitle)
        }

        // If the choice had been lost, onCreate would pin Bangla — so seeing
        // English here proves it was remembered.
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText(englishTitle)
            composeRule.onNodeWithText(englishTitle).assertIsDisplayed()
        }
    }

    // Switching language recreates the Activity, so the new text appears a
    // moment later rather than immediately.
    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
