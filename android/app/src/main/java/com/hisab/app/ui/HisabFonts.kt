package com.hisab.app.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.hisab.app.AppLanguage
import com.hisab.app.R

/**
 * Only these two families are allowed anywhere in the app: Noto Sans Bengali
 * for Bangla, Merriweather for English (D010). Both are bundled rather than
 * downloaded, because the app must work fully offline.
 */
val NotoSansBengali =
    FontFamily(
        Font(R.font.noto_sans_bengali_regular, FontWeight.Normal),
        Font(R.font.noto_sans_bengali_bold, FontWeight.Bold),
    )

val Merriweather =
    FontFamily(
        Font(R.font.merriweather_regular, FontWeight.Normal),
        Font(R.font.merriweather_bold, FontWeight.Bold),
    )

/**
 * Picks the font by the script the text is actually written in — not by the
 * app's current language. A Bangla screen showing the word "English" must
 * still render that word in Merriweather (D010).
 */
fun fontFor(language: AppLanguage): FontFamily =
    when (language) {
        AppLanguage.BANGLA -> NotoSansBengali
        AppLanguage.ENGLISH -> Merriweather
    }
