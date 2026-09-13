package com.hisab.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisab.app.AppLanguage
import com.hisab.app.MainActivity
import com.hisab.app.currentAppLanguage
import com.hisab.app.pinBanglaOnFirstRun
import com.hisab.app.pinBanglaOnFirstRunOnce
import com.hisab.app.resetFirstRunPinForTest
import com.hisab.app.setAppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Step 20: the app opens in Bangla by default, and the language switch
// (Steps 8–10) works both ways.
//
// AppCompat's language API only works while an Activity is alive, so every
// language change here goes through the Activity the rule owns. One Activity
// per test on purpose: closing one and launching another inside the same test
// leaves Espresso waiting for idle forever once other test classes have run.
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val banglaTitle = "হিসাবে স্বাগতম"
    private val englishTitle = "Welcome to Hisab"

    @After
    fun restoreBangla() {
        composeRule.activityRule.scenario.onActivity { setAppLanguage(AppLanguage.BANGLA) }
    }

    // Everything in one block on the main thread: clearing the language
    // restarts the Activity, and waiting for the screen to settle across that
    // restart hangs once a full test run has gone before it. What matters
    // here is the decision, not the redraw — the redraw is covered by
    // languageButtonSwitchesToEnglishAndBack, and a real first install was
    // checked by hand on the phone at Step 20.
    @Test
    fun firstRunPinsBanglaEvenOnAnEnglishPhone() {
        composeRule.activityRule.scenario.onActivity {
            // Start from English, so Bangla at the end can't be a leftover.
            setAppLanguage(AppLanguage.ENGLISH)
            assertEquals(AppLanguage.ENGLISH, currentAppLanguage())

            // Exactly the state of a brand-new install: nothing stored and
            // nothing pinned yet, then the startup pinning that
            // MainActivity.onCreate runs.
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            resetFirstRunPinForTest()
            pinBanglaOnFirstRunOnce()

            assertEquals(AppLanguage.BANGLA, currentAppLanguage())
        }
    }

    @Test
    fun pinningDoesNotOverrideAChosenLanguage() {
        composeRule.activityRule.scenario.onActivity { setAppLanguage(AppLanguage.ENGLISH) }
        waitForText(englishTitle)

        composeRule.activityRule.scenario.onActivity {
            pinBanglaOnFirstRun()
            assertEquals(AppLanguage.ENGLISH, currentAppLanguage())
        }
        composeRule.onNodeWithText(englishTitle).assertIsDisplayed()
    }

    // Pinning happens once per app start. Without this, the Activity restart
    // that a language change causes would run the pinning again, and a phone
    // slow to store the choice could keep restarting the screen.
    @Test
    fun pinningRunsOnlyOncePerAppStart() {
        composeRule.activityRule.scenario.onActivity {
            resetFirstRunPinForTest()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            pinBanglaOnFirstRunOnce()
            assertEquals(AppLanguage.BANGLA, currentAppLanguage())

            // Second call, with the choice cleared again: it must do nothing,
            // so no further restart can be triggered.
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            pinBanglaOnFirstRunOnce()
            assertEquals(
                "pinning must not run twice in one app start",
                LocaleListCompat.getEmptyLocaleList(),
                AppCompatDelegate.getApplicationLocales(),
            )
        }
    }

    @Test
    fun languageButtonSwitchesToEnglishAndBack() {
        composeRule.activityRule.scenario.onActivity { setAppLanguage(AppLanguage.BANGLA) }
        waitForText(banglaTitle)

        composeRule.onNodeWithText("English").performClick()
        waitForText(englishTitle)
        composeRule.onNodeWithText(englishTitle).assertIsDisplayed()
        assertEquals(AppLanguage.ENGLISH, currentAppLanguage())

        composeRule.onNodeWithText("বাংলা").performClick()
        waitForText(banglaTitle)
        composeRule.onNodeWithText(banglaTitle).assertIsDisplayed()
        assertEquals(AppLanguage.BANGLA, currentAppLanguage())
    }

    // Switching language recreates the Activity, so the new text appears a
    // moment later rather than immediately.
    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
