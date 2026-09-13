package com.hisab.app

import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The two languages the app ships in. Bangla is the default (D009).
 */
enum class AppLanguage(
    val tag: String,
) {
    BANGLA("bn"),
    ENGLISH("en"),
    ;

    fun other(): AppLanguage = if (this == BANGLA) ENGLISH else BANGLA
}

/**
 * Reads the language the app is currently showing. Falls back to Bangla when
 * nothing has been chosen yet, so the phone's own language never decides this
 * for us — Bangla is the default regardless of device settings.
 */
fun currentAppLanguage(): AppLanguage {
    val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    return if (tags.startsWith(AppLanguage.ENGLISH.tag)) AppLanguage.ENGLISH else AppLanguage.BANGLA
}

/**
 * On the very first launch nothing is stored yet, and Android would otherwise
 * fall back to the phone's language — which would show English on an English
 * phone. Bangla is the default (D009), so pin it explicitly. Does nothing once
 * a language has been chosen.
 *
 * Call it from an Activity's onCreate, after super.onCreate. AppCompat's
 * language API needs a live Activity: called from Application.onCreate it
 * silently did nothing on a real Android 16 phone, so a new install opened in
 * English (found by HomeScreenTest at Step 20).
 */
fun pinBanglaOnFirstRun() {
    if (AppCompatDelegate.getApplicationLocales().isEmpty) {
        setAppLanguage(AppLanguage.BANGLA)
    }
}

private var firstRunPinDone = false

/**
 * The same thing, but at most once per app start.
 *
 * Pinning restarts the Activity, whose onCreate would pin again if the stored
 * language is not readable back yet. That is a loop, and on a slow phone it
 * could spin for a long time — one was seen running for 16 minutes in a test
 * at Step 23. Doing it once per process ends the loop by construction: the
 * choice is stored, and the next app start reads it normally.
 */
fun pinBanglaOnFirstRunOnce() {
    if (firstRunPinDone) return
    firstRunPinDone = true
    pinBanglaOnFirstRun()
}

/** Lets a test act as if the app had just started. Not used by the app itself. */
@VisibleForTesting
fun resetFirstRunPinForTest() {
    firstRunPinDone = false
}

/**
 * Switching languages via AppCompat (not a hand-rolled mechanism — D014), which
 * persists the choice and applies it on the next launch.
 */
fun setAppLanguage(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
}
