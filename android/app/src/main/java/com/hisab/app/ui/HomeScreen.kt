package com.hisab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisab.app.AppLanguage
import com.hisab.app.R

@Composable
fun HomeScreen(
    language: AppLanguage,
    onChangeLanguage: (AppLanguage) -> Unit,
) {
    val font = fontFor(language)
    val otherLanguage = language.other()

    Scaffold { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.welcome_title),
                fontFamily = font,
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.welcome_subtitle),
                fontFamily = font,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.setup_note),
                fontFamily = font,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
            Button(
                onClick = { onChangeLanguage(otherLanguage) },
                modifier = Modifier.padding(top = 32.dp),
            ) {
                // The button names the language you'd switch to, written in its
                // own script — so it takes that language's font, not the
                // current screen's font (D010).
                Text(
                    text =
                        stringResource(
                            when (otherLanguage) {
                                AppLanguage.BANGLA -> R.string.language_bangla
                                AppLanguage.ENGLISH -> R.string.language_english
                            },
                        ),
                    fontFamily = fontFor(otherLanguage),
                    fontSize = 16.sp,
                )
            }
        }
    }
}
