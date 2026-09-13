package com.hisab.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.hisab.app.ui.HomeScreen

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must come after super.onCreate — see pinBanglaOnFirstRun. On the very
        // first launch this redraws the screen once, in Bangla; after that it
        // does nothing.
        pinBanglaOnFirstRun()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        language = currentAppLanguage(),
                        onChangeLanguage = ::setAppLanguage,
                    )
                }
            }
        }
    }
}
