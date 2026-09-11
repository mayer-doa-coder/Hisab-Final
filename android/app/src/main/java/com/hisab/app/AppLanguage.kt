package com.hisab.app

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The two languages the app ships in. Bangla is the default (D009).
 */
enum class AppLanguage(val tag: String) {
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
 * Switching languages via AppCompat (not a hand-rolled mechanism — D014), which
 * persists the choice and applies it on the next launch.
 */
fun setAppLanguage(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
}
