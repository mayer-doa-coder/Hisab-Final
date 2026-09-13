package com.hisab.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.hisab.app.ui.HisabApp

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must come after super.onCreate — see pinBanglaOnFirstRun. On the very
        // first launch this redraws the screen once, in Bangla; after that it
        // does nothing.
        pinBanglaOnFirstRunOnce()

        setContent {
            HisabApp(
                language = currentAppLanguage(),
                onChangeLanguage = ::setAppLanguage,
            )
        }
    }
}
