package com.hisab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisab.app.AppLanguage
import com.hisab.app.R
import com.hisab.app.ui.theme.ClayButton
import com.hisab.app.ui.theme.ClayButtonStyle
import com.hisab.app.ui.theme.ClayCard
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens

@Composable
fun HomeScreen(
    language: AppLanguage,
    onChangeLanguage: (AppLanguage) -> Unit,
    onOpenProducts: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val font = fontFor(language)
    val otherLanguage = language.other()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ClayColors.Background)
                .safeDrawingPadding()
                .padding(ClayDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ClayCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.welcome_title),
                    fontFamily = font,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = ClayColors.Ink,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.welcome_subtitle),
                    fontFamily = font,
                    fontSize = 16.sp,
                    color = ClayColors.InkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    text = stringResource(R.string.setup_note),
                    fontFamily = font,
                    fontSize = 14.sp,
                    color = ClayColors.InkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }

        ClayButton(
            text = stringResource(R.string.products_open),
            onClick = onOpenProducts,
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
        )

        ClayButton(
            text = stringResource(R.string.sync_open),
            onClick = onOpenSync,
            style = ClayButtonStyle.SOFT,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        Text(
            text = stringResource(R.string.action_change_language),
            fontFamily = font,
            fontSize = 14.sp,
            color = ClayColors.InkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 28.dp),
        )
        ClayButton(
            // The button names the language you would switch to, written in
            // its own script — so it takes that language's font, not the
            // current screen's font (D010).
            text =
                stringResource(
                    when (otherLanguage) {
                        AppLanguage.BANGLA -> R.string.language_bangla
                        AppLanguage.ENGLISH -> R.string.language_english
                    },
                ),
            onClick = { onChangeLanguage(otherLanguage) },
            style = ClayButtonStyle.SOFT,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
