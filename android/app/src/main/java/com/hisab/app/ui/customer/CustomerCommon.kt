package com.hisab.app.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hisab.app.R
import com.hisab.app.domain.Money
import com.hisab.app.domain.toTakaString
import com.hisab.app.ui.scriptAwareText
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.ClayUserText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Locale

// Small pieces the Customers screens share. Nothing here decides anything about
// money: amounts arrive already worked out from the ledger (D001).

/** An amount as a shopkeeper reads it: the taka sign, then the digits of the screen's own language. */
@Composable
fun moneyText(money: Money): String {
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(R.string.currency_symbol) + " " + money.toTakaString(locale)
}

/**
 * The colour a balance is drawn in. It backs up the words next to it, never
 * replaces them: every place that uses it also says "owes", "all clear" or
 * "paid in advance", so nobody has to tell red from green to read it.
 */
fun statusColor(status: BalanceStatus): Color =
    when (status) {
        BalanceStatus.OWES -> ClayColors.Danger
        BalanceStatus.ADVANCE -> ClayColors.Money
        BalanceStatus.CLEAR -> ClayColors.InkMuted
    }

/** A calendar date in the screen's language and its digits, the way money is: a Bangla month next to Latin digits reads as a bug. */
fun localizedDate(
    date: LocalDate,
    locale: Locale,
): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withDecimalStyle(DecimalStyle.of(locale))
        .format(date)

/** The customer's name as the title, with a back arrow whose tap area is a full 48 dp. */
@Composable
fun CustomerHeader(
    title: String,
    subtitle: String? = null,
    titleIsUserText: Boolean = false,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The arrow looks 40 dp but the tap area is 48, so it is easy to hit
        // with a thumb without the button looking heavy.
        Box(
            modifier = Modifier.size(48.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = stringResource(R.string.cd_back),
                tint = ClayColors.Ink,
                modifier = Modifier.size(40.dp).background(ClayColors.Surface, RoundedCornerShape(14.dp)).padding(8.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            if (titleIsUserText) {
                ClayUserText(text = scriptAwareText(title), size = 22, weight = FontWeight.Bold, maxLines = 1)
            } else {
                ClayText(text = title, size = 22, weight = FontWeight.Bold)
            }
            if (subtitle != null) {
                ClayUserText(text = scriptAwareText(subtitle), size = 14, color = ClayColors.InkMuted, maxLines = 1)
            }
        }
    }
}

/** A round badge with the first letter of the name — a quick way to tell rows apart at a glance. */
@Composable
fun Avatar(
    name: String,
    size: Dp = 44.dp,
) {
    val initial =
        remember(name) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) "?" else String(Character.toChars(trimmed.codePointAt(0)))
        }
    Box(
        modifier = Modifier.size(size).background(ClayColors.PrimarySoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        ClayUserText(text = scriptAwareText(initial), size = 18, weight = FontWeight.Bold, color = ClayColors.Primary)
    }
}

/** A short label on a tinted background, for a status that must not be missed. */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    ClayText(
        text = text,
        size = 12,
        weight = FontWeight.Bold,
        color = color,
        modifier =
            modifier
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Something to say instead of a blank list: what is missing, and what to do about it. */
@Composable
fun EmptyNote(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ClayText(text = title, size = 18, weight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (subtitle != null) {
            ClayText(
                text = subtitle,
                size = 14,
                color = ClayColors.InkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** [EmptyNote] centred in whatever room the screen has left, for a screen with nothing else on it. */
@Composable
fun CentredEmptyNote(
    title: String,
    subtitle: String? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyNote(title = title, subtitle = subtitle)
    }
}
