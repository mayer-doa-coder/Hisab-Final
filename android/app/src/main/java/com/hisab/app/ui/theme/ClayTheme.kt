package com.hisab.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hisab.app.AppLanguage
import com.hisab.app.ui.fontFor

/**
 * Claymorphism: soft pastel ground, thick rounded shapes that look moulded
 * rather than drawn, a light edge on top and a coloured shadow underneath,
 * and buttons that press in when touched.
 *
 * The palette stays deliberately light and high-contrast for text. A shop is
 * often read in daylight on a cheap screen, so pale-on-pale decoration is
 * kept to surfaces only, never to words or numbers.
 */
object ClayColors {
    val Background = Color(0xFFF1ECFA)
    val Surface = Color(0xFFFCFAFF)
    val SurfaceSunken = Color(0xFFEBE4F8)
    val Primary = Color(0xFF6244C4)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimarySoft = Color(0xFFE2D9FA)
    val Money = Color(0xFF1F7A63)
    val Danger = Color(0xFFB33A4B)
    val Ink = Color(0xFF272040)
    val InkMuted = Color(0xFF6B6390)
    val Shadow = Color(0xFF9F87D4)
    val Highlight = Color(0xFFFFFFFF)
}

object ClayDimens {
    val CardCorner = 26.dp
    val FieldCorner = 20.dp
    val ButtonCorner = 22.dp
    val ScreenPadding = 20.dp
    val CardPadding = 18.dp
    val RestingElevation = 10.dp
    val PressedElevation = 3.dp
}

val ClayShapes =
    Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(ClayDimens.FieldCorner),
        large = RoundedCornerShape(ClayDimens.CardCorner),
        extraLarge = RoundedCornerShape(32.dp),
    )

/**
 * The font for the app's own labels, decided by the app language (D010). Text
 * the user typed does not use this — it goes through `scriptAwareText`, which
 * picks a font per script inside the string.
 */
val LocalUiFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

@Composable
fun HisabTheme(
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        lightColorScheme(
            primary = ClayColors.Primary,
            onPrimary = ClayColors.OnPrimary,
            primaryContainer = ClayColors.PrimarySoft,
            onPrimaryContainer = ClayColors.Ink,
            secondary = ClayColors.Money,
            onSecondary = Color.White,
            background = ClayColors.Background,
            onBackground = ClayColors.Ink,
            surface = ClayColors.Surface,
            onSurface = ClayColors.Ink,
            surfaceVariant = ClayColors.SurfaceSunken,
            onSurfaceVariant = ClayColors.InkMuted,
            error = ClayColors.Danger,
            onError = Color.White,
            outline = ClayColors.Shadow,
        )

    CompositionLocalProvider(LocalUiFont provides fontFor(language)) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = ClayShapes,
            content = content,
        )
    }
}
